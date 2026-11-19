# Plan: Strict Structured Outputs (Phase 3 — open)

**Status:** Open (not implemented) — 2026-11-16
**References:** `docs/plans/node-data-contracts-and-context-economy.md` (Phase 3),
`docs/plans/node-data-contracts-progress.md`

## 1. Current Implementation (Option 1) — with weaknesses

qoc uses the reserved `submit_result` tool (Function Calling) for structured outputs.
OpenAI validates tool arguments against the schema, but in **non-strict** mode the
model can deviate (extra fields, missing required fields, wrong enums).
These are caught reactively by qoc (`ToolRuntime.decode`, `NodeContractValidator`),
which makes retries necessary → higher latency and cost.

**Weaknesses:**
- OpenAI can deviate → retries necessary → higher latency/cost.
- Validation is **reactive** (after the call), not **preventive** (during generation).
- `submit_result` tool schema is not strict-compatible (additionalProperties: true, optional fields).
- No Constrained Decoding → in rare cases the model can produce invalid JSON.

**Strengths (already present):**
- Deviations are caught by `ToolRuntime.decode()` and `NodeContractValidator`.
- Semantic errors (`Empty`/`InvalidArgument`/`Unavailable`/`Failed`) are distinguishable.
- Node outputs are validated against `outputContract`.
- Works with all providers (Function Calling is portable).

## 2. OpenAI Strict as the next extension (Option 2)

**Goal:** Enable strict enforcement (Constrained Decoding) so the model
technically cannot deviate.

Two ways to achieve "strict" in qoc:

### Approach A: `response_format` route

Instead of the `submit_result` tool → set `response_format: json_schema` on the ChatRequest.

**Flow:**
1. `response_format: { type: "json_schema", json_schema: { strict: true } }` is set on the ChatRequest.
2. OpenAI enforces the schema via Constrained Decoding.
3. On parse error → force-prompt + retry.

**Advantages:**
- Uses OpenAI's Constrained Decoding directly (strict guaranteed).
- No tool-call overhead for structured output.

**Disadvantages:**
- Large refactor — loses the tool integration.
- Structured output and tools coexist poorly (structured output is ignored when tool calls are present).
- qoc's `submit_result` tool would have to be removed/reworked.
- Loses `NodeContractValidator` integration (no node-output contract anymore).

**Effort:** High (architectural change)

**Recommendation:** ❌ Not recommended for qoc — qoc's architecture is built on Function Calling.

### Approach B: `strict: true` on `ToolSpecification` (Function-Calling-Strict)

Set `strict(true)` on the `submit_result` tool. OpenAI then enforces the schema
when generating the tool arguments.

**Flow:**
1. `LangChain4jProviderAdapter.toToolSpecification()` sets `.strict(true)`.
2. `toJsonObjectSchema()` sets `additionalProperties(false)` (instead of `true` or unset).
3. All fields must be `required` (no `Schema.Opt` at top level).
4. OpenAI guarantees schema compliance during generation.

**Advantages:**
- Keeps qoc's architecture (submit_result tool, tool integration, NodeContractValidator).
- No large refactor.
- Preventive rather than reactive (no retries necessary).

**Disadvantages:**
- Requires schema adjustments (additionalProperties: false, all required).
- `Schema.Opt` (optional fields) not allowed at top level in strict mode.
- Only OpenAI/Azure (no provider portability).
- Requires real API testing.

**Effort:** Medium (schema adjustments + testing)

**Recommendation:** ✅ Recommended for qoc.

## 3. Implementation steps (Approach B)

### Step 1: Make tool schemas strict-compatible

**File:** `engine/src/main/java/de/augmentia/oc/engine/core/json/Schema.java`

- Forbid `Schema.Opt` (optional fields) at top level when strict mode is active.
- Alternatively: treat `Schema.Opt` in strict mode as `required` with a `null` default.

**File:** `quarkus/src/main/java/de/augmentia/oc/engine/http/LangChain4jProviderAdapter.java`

```java
private ToolSpecification toToolSpecification(ToolDefinition definition) {
    return ToolSpecification.builder()
            .name(definition.effectiveName())
            .description(definition.description())
            .parameters(toJsonObjectSchema(definition.inputSchema()))
            .strict(true)  // ← NEW
            .build();
}

private JsonObjectSchema toJsonObjectSchema(Schema schema) {
    if (schema instanceof Schema.Obj obj) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        List<String> required = new ArrayList<>();
        for (Map.Entry<String, Schema> field : obj.fields().entrySet()) {
            Schema inner = field.getValue();
            boolean optional = inner instanceof Schema.Opt;
            Schema unwrapped = optional ? ((Schema.Opt) inner).inner() : inner;
            builder.addProperty(field.getKey(), toLcSchema(unwrapped));
            // strict: all fields required
            required.add(field.getKey());
        }
        // strict: additionalProperties: false
        builder.additionalProperties(false);
        return builder.required(required).build();
    }
    // ...
}
```

### Step 2: Make the `strict` flag configurable

- System property: `qoc.structured-output.strict=true` (Default: `false` for backward compatibility).
- When `false`: current behavior (non-strict, own validation catches deviations).
- When `true`: strict mode activated (Constrained Decoding).

### Step 3: Tests

**Unit tests:**
- `toJsonObjectSchema` sets `additionalProperties(false)` when strict.
- `toJsonObjectSchema` sets all fields as `required` when strict.
- `toToolSpecification` sets `.strict(true)` when the strict flag is active.

**Integration tests (requires a real API key):**
- LLM with strict mode returns a schema-conforming `submit_result`.
- LLM without strict mode can deviate (baseline).
- Comparison: latency/cost with/without strict.

### Step 4: Documentation

- `docs/CONFIG.md`: new property `qoc.structured-output.strict`.
- `docs/guides/langchain4j.md`: Strict-Mode explanation.

## 4. Risks

| Risk | Mitigation |
|---|---|
| Schema not strict-compatible → OpenAI rejects it | Validation at startup: check whether the schema is strict-compatible |
| `Schema.Opt` (optional fields) not allowed | Either remove or treat as required with null default |
| Provider portability lost (only OpenAI/Azure) | Feature behind flag, default: false (portable) |
| Tests with demo-key fail | Integration tests only with a real key (CI secret) |

## 5. Dependencies

- LangChain4j `ToolSpecification.strict(Boolean)` — available since 1.19.3 (verified).
- OpenAI Function-Calling-Strict-Mode — available since Aug 2024 (gpt-4o, gpt-4o-mini).

## 6. Why open?

1. **Schema adjustments** require changes to `Schema.java` and all tool definitions.
2. **Real API testing** necessary — the Quarkus tests run with `demo-key` and cannot validate strict mode.
3. **Provider portability** — strict mode is OpenAI-exclusive; the default must remain `false`.
4. **Priority** — qoc's current implementation (non-strict + own validation) is functionally safe. Strict is a performance/reliability gain, not a security gain.

## 7. Recommendation

- **Immediately:** Not necessary — qoc's current implementation is functionally sufficient.
- **Medium term:** If high volume or critical applications → implement Approach B.
- **Long term:** If provider portability is no longer needed → evaluate Approach A (response_format).

## 8. Third option: Keeping the current implementation (Option 1)

If neither Approach A nor Approach B is implemented, the current implementation
(Option 1) remains in place:

- Non-strict Function Calling + own validation (`ToolRuntime.decode`, `NodeContractValidator`).
- Deviations are caught reactively → retries possible.
- Functionally safe, but not optimally efficient.
- Portable across all providers.

**Recommendation for Option 1:** Sufficient for most use cases. Strict mode (Approach B)
is worthwhile only at high volume or for critical applications.