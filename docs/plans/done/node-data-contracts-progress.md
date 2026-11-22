# Progress: Node Data Contracts & Context Economy

**Status:** 2026-11-22 — All 8 phases implemented. Remaining follow-ups: test classes (NodeDataContractTest done), config flags, UI polish.
**Plan:** `docs/plans/node-data-contracts-and-context-economy.md`
**Test spec:** Section 9 of the plan

---

## Completed

### Phase 0 — Node Data Contract (CORE) ✅

**Commit:** `3eafb65`
**Files:**
- `workflow-runner/.../model/WorkflowDefinition.java` — `NodeData` extended with `outputContract`, `contextRefs` (+ backwards-compat constructor)
- `workflow-runner/.../runner/NodeContractValidator.java` (new) — minimal JSON Schema validator
- `workflow-runner/.../runner/ContractViolationException.java` (new) — typed exception with `path()`/`kind()`
- `workflow-runner/.../runner/DefaultNodeDispatcher.java` — `dispatch()` wraps `dispatchImpl()` + `validateOutputContract()`; `context_agent_executor` reads `contextRefs` from `NodeData`
- `workflow-runner/.../runner/ContextAssembler.java` — node outputs in `<node-output id="...">` delimiters
- `workflow-runner/.../store/WorkflowDefaults.java` — updated to new arity

**Tests:**
- Build green: workflow-runner (95 tests), engine (1110 tests)
- No new test classes (Phase 0 tests in `NodeDataContractTest` not yet implemented — planned)

---

### Phase 4 — Prompt Injection Defense ✅

**Commit:** `3eafb65`
**Files:**
- `engine/.../runtime/context/ConversationFolding.java` — tool results wrapped in `<tool-result name="..." id="...">` delimiters (Completed + Failed)

**Tests:**
- `ConversationFoldingCoverageTest` — 2 assertions updated (justified: delimiter wrapping is a design change)

---

### Phase 1 — Semantic Tool Outcomes + hasMore ✅

**Commit:** `b5e52ea`
**Files:**
- `engine/.../core/contract/tool/ToolOutcome.java` — new variants: `Empty`, `InvalidArgument`, `Unavailable`
- `engine/.../runtime/tool/runtime/ToolOutcomeJson.java` — Encode/Decode for new types
- `engine/.../runtime/tool/fs/GrepTool.java` — `hasMore` in metadata
- `engine/.../runtime/tool/fs/ReadTool.java` — `hasMore` in metadata (both paths)
- `engine/.../runtime/tool/fs/GlobTool.java` — `hasMore` in metadata
- `engine/.../core/ToolOutcomeSemanticTest.java` (new, 9 tests)

**Tests:**
- `ToolOutcomeSemanticTest` — 9 tests green
- `FsToolsTest` — 18 tests green (no regression)

---

### Phase 5 — Visual Context-Refs + Output-Contract Selectors (UI) ✅

**Commit:** `f63167b`
**Files:**
- `workflow-ui/src/types/workflow.ts` — `NodeData` extended with `outputContract`, `contextRefs`
- `workflow-ui/src/components/PropertyPanel.tsx` — Context-Refs checkbox list + Output-Contract SchemaSource for `agent` and `context_agent_executor` nodes

**Tests:** Build green (tsc + vite)

---

### Phase 7 — Contract Validation (Generator) ✅

**Commits:** `9940936`, `f238164`
**Files:**
- `workflow-runner/.../generate/WorkflowContractValidator.java` — deterministic check + LLM-based semantic pass (`validateWithLlm`)
- `workflow-runner/.../generate/WorkflowGenerator.java` — both passes integrated (best-effort, non-blocking)

**Note:** LLM pass is best-effort (swallowed exceptions). Test class `WorkflowGeneratorContractValidationTest` not yet implemented.

---

## Status

All 8 phases are (at least partially) implemented. Recommendations from open questions implemented:
- #2: `strict` flag in `NodeData` (Default: false = warn + continue, true = abort)
- #3: `contextRefs` are automatically derived from graph edges (`TopologicalSort.incomingSources`)
- #5: Output limit configurable via system property `qoc.output.max-bytes` (Default: 50 KB)

Remaining follow-ups: Test classes (NodeDataContractTest, etc.) and full integration (config flags instead of system properties, per-agent outputLimit in AgentConfig).

### Phase 0b — Fine-grained Field Selection + Structured Initial Input ✅ (Backend)

**Files:**
- `workflow-runner/.../model/WorkflowDefinition.java` — `inputSchema` field (+ backwards-compat constructors)
- `workflow-runner/.../runner/WorkflowContext.java` — `initialInput` field + getter/setter + persistence in `restore()`
- `workflow-runner/.../runner/ContextRefResolver.java` (new) — resolution + validation of path refs
- `workflow-runner/.../runner/ContextAssembler.java` — `appendCustom` uses `ContextRefResolver`; field rendering via `<context-value>`
- `workflow-runner/.../validation/GraphCompiler.java` — `validateContextRefs` + reserved node names
- `workflow-runner/.../runner/WorkflowExecutor.java` — `tryParseJson` + `inputSchema` validation via `NodeContractValidator`

**Tests:**
- `ContextRefResolverTest` — 11 tests green
- `ContextAssemblerTest` — 5 tests green (incl. field selection)
- `GraphCompilerTest` — +2 tests (reserved node names, unknown nodes in contextRefs)

**Open (UI):**
- Phase 5b field selector (JSON tree with checkboxes) not yet implemented.

**Remaining follow-ups:** NodeDataContractTest (P0-1..P0-13), full integration (config flags instead of system properties, per-agent outputLimit).

**Phase 3 (Strict Structured Outputs):** Detailed instructions in `docs/plans/strict-structured-outputs.md` (Status: Open). Three options analyzed: Option 1 (current implementation, non-strict + own validation), Option 2 with Path A (response_format) — not recommended, Path B (strict on ToolSpecification) — recommended but open (requires schema adjustments + real API testing).

**UI (2026-11-16, fourth round):**
- PropertyPanel now shows checkboxes per predecessor node (when `outputContract.properties` present) for top-level fields (`nodeId.field`).
- Text input for arbitrary paths (nodeId.field, initial.field, state.key, lastResult.field) remains.

**UI (2026-11-16, sixth round):**
- Full recursive JSON tree field selector (`FieldTree` component): arbitrary nesting depth, checkbox per field, paths added as `nodeId.a.b.c` to `contextRefs`.

**Additionally implemented (2026-11-16, third round):**
- `WorkflowContractValidatorTest` — 7 tests (naming convention, missing output contract, missing system prompt, LLM parse, null caller, null def).

**Additionally implemented (2026-11-16, fifth round):**
- `NodeDataContractTest` — 11 tests (contract validation valid/invalid/missing/additional/enum, no-contract passthrough, contextRefs field filtering, unknown ref, template type check/mismatch).
- Phase 5b UI: recursive JSON tree field selector (top-level + nested fields) in PropertyPanel.

## Open Questions (from the plan)

1. Should `outputContract` be mandatory or optional? → Recommendation: optional (Default = no contract → Legacy).
2. Should contract violations abort the workflow or only warn? → Recommendation: abort with `strict: true` flag, otherwise warn + continue.
3. Should `contextRefs` be derived automatically from graph edges? → Recommendation: yes, as default.
4. Enum conversion in tools: case-insensitive or strict? (Phase 1, P1-15)
5. Offloading threshold: global or per-agent? (Phase 2)
6. ChatContextReducer: default enabled or opt-in? (Phase 6)