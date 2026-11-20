# Plan: Saga-Pattern Undo Tools for Tools

## 0. Goal & Scope

Tools can declare an **undo pattern** (compensating action). Undo is performed
**deterministically** from the tool execution log (the session journal:
`ToolCalled` / `ToolSucceeded` / `ToolFailed`) and is executed through
**targeted, registered undo tools**. Undo tools are registered only for tools
that declare an undo pattern (Saga pattern: forward action + compensating
action).

**MVP scope (M1–M4):** contract, registry, compensator, Engine wiring, tests.
**Phase 2 (M5):** automatic multi-step saga orchestration on step failure.
**Non-goals (MVP):** `dryRun`, persistent undo history outside the journal,
automatic orchestration.

## 1. Architecture Overview

```
ToolDefinition (forward)
  └─ ToolOptions.undo: Option<UndoSpec>
       ├─ compensatingToolName: ToolName
       ├─ mapper: UndoInputMapper  (pure)
       └─ guard: UndoGuard (optional)

Registration flow (Engine init):
  1. Register all forward tools into ToolRegistry (existing).
  2. UndoRegistry.rebuildFrom(toolRegistry)  // scans options().undo()
  3. For each UndoSpec: UndoToolFactory.create(...) -> ToolDefinition
     registered into ToolRegistry (name "undo_<effectiveName>").
  4. toolSnapshot = registry.snapshot(allowAll)
  5. toolRuntime = new ToolRuntime(toolSnapshot, ...)
  6. undoRuntime = new UndoRuntime(journal, registry, undoRegistry,
                                    toolRuntime, authorizer, permissionGate, clock)

Undo invocation (agent calls undo_<tool> with {callId, sessionId?}):
  UndoRuntime.undo(request, ctx)
    -> read Journal (deterministic reconstruction)
    -> resolve UndoSpec
    -> guard
    -> mapper -> undoInput
    -> permission/authorizer check on compensating tool
    -> ToolRuntime.execute(compensatingCall, derivedCtx)
    -> audit journal entry (metadata marker undoOf)
    -> return UndoOutcome
```

Undo tools are first-class `ToolDefinition`s: they appear in the catalog,
snapshot, and permission system like any other tool.

## 2. Data Model Changes

### 2.1 `ToolOptions` (modify)

File: `engine/src/main/java/.../core/contract/tool/ToolOptions.java`

Add component `Option<UndoSpec> undo` as the **7th** component (last). Keep
component order stable to minimize churn; existing constructors delegate.

```java
public record ToolOptions(
    Option<String> namespace,
    Option<String> permission,
    boolean codemode,
    boolean pinned,
    boolean strict,
    Option<java.util.function.Function<JsonValue, String>> approvalMessage,
    Option<UndoSpec> undo) {

  public ToolOptions {
    if (namespace == null) namespace = Option.none();
    if (permission == null) permission = Option.none();
    if (approvalMessage == null) approvalMessage = Option.none();
    if (undo == null) undo = Option.none();
  }

  // Backward-compat constructors delegate with undo = none
  public ToolOptions(Option<String> namespace, Option<String> permission,
                     boolean codemode, boolean pinned) {
    this(namespace, permission, codemode, pinned, false, Option.none(), Option.none());
  }
  public ToolOptions(Option<String> namespace, Option<String> permission,
                     boolean codemode, boolean pinned, boolean strict) {
    this(namespace, permission, codemode, pinned, strict, Option.none(), Option.none());
  }
  public ToolOptions(Option<String> namespace, Option<String> permission,
                     boolean codemode, boolean pinned, boolean strict,
                     java.util.function.Function<JsonValue, String> approvalMessage) {
    this(namespace, permission, codemode, pinned, strict,
         approvalMessage == null ? Option.none() : Option.some(approvalMessage),
         Option.none());
  }

  public static ToolOptions defaults() {
    return new ToolOptions(Option.none(), Option.none(), true, false, false,
                           Option.none(), Option.none());
  }

  public ToolOptions withStrict(boolean strict) {
    return new ToolOptions(namespace, permission, codemode, pinned, strict,
                           approvalMessage, undo);
  }
  public ToolOptions withApprovalMessage(java.util.function.Function<JsonValue, String> fn) {
    return new ToolOptions(namespace, permission, codemode, pinned, strict,
        fn == null ? Option.none() : Option.some(fn), undo);
  }
  public ToolOptions withUndo(UndoSpec spec) {
    return new ToolOptions(namespace, permission, codemode, pinned, strict,
                           approvalMessage, spec == null ? Option.none() : Option.some(spec));
  }
}
```

No call-site changes required (all existing constructors preserved).

### 2.2 `ToolDefinition` (no change)

Undo lives in `ToolOptions`. No record change. Access via
`definition.options().undo()`.

### 2.3 New types in `core/contract/tool/`

**`UndoSpec.java`**
```java
public record UndoSpec(
    ToolName compensatingToolName,
    UndoInputMapper mapper,
    UndoGuard guard) {
  public UndoSpec {
    if (compensatingToolName == null)
      throw new IllegalArgumentException("compensatingToolName must not be null");
    if (mapper == null)
      throw new IllegalArgumentException("mapper must not be null");
    if (guard == null) guard = (i, o, m) -> true;
  }
  public static UndoSpec of(ToolName compensatingToolName, UndoInputMapper mapper) {
    return new UndoSpec(compensatingToolName, mapper, (i, o, m) -> true);
  }
}
```

**`UndoInputMapper.java`**
```java
@FunctionalInterface
public interface UndoInputMapper {
  JsonValue map(JsonValue originalInput, JsonValue originalOutput,
                java.util.Map<String, Object> metadata);
}
```

**`UndoGuard.java`**
```java
@FunctionalInterface
public interface UndoGuard {
  boolean allow(JsonValue originalInput, JsonValue originalOutput,
                java.util.Map<String, Object> metadata);
}
```

**`UndoRequest.java`** (in `runtime/tool/undo/`)
```java
public record UndoRequest(Option<String> sessionId, String callId) {
  public UndoRequest { if (callId == null || callId.isBlank())
      throw new IllegalArgumentException("callId must not be blank"); }
}
```

**`UndoOutcome.java`** (in `runtime/tool/undo/`)
```java
public sealed interface UndoOutcome {
  record Undone(String originalCallId, String undoCallId, ToolOutcome outcome)
      implements UndoOutcome {}
  record AlreadyUndone(String originalCallId) implements UndoOutcome {}
  record NotApplicable(String originalCallId, String reason) implements UndoOutcome {}
  record NoPattern(String toolName) implements UndoOutcome {}
  record GuardRejected(String originalCallId, String reason) implements UndoOutcome {}
  record MissingLogEntry(String callId) implements UndoOutcome {}
  record Failed(String originalCallId, SessionError error) implements UndoOutcome {}
}
```

### 2.4 Optional dedicated audit event (Phase 1b)

In `core/model/SessionEvent.java`, add (optional, deferred to keep MVP small):

```java
record ToolUndone(SessionId sessionId, MessageId assistantMessageId, CallId callId,
                  CallId undoCallId, ToolName toolName) implements SessionEvent {}
```

MVP decision: **defer**. Use metadata marker `{undoOf, undoTool}` on existing
`ToolCalled`/`ToolSucceeded` instead. This avoids `EventCodec` + reflection
changes in MVP. Dedicated event becomes a follow-up.

## 3. UndoRegistry

File: `engine/.../runtime/tool/undo/UndoRegistry.java`

```java
public final class UndoRegistry {
  private final Map<ToolName, UndoSpec> specs = new LinkedHashMap<>();

  public synchronized void register(ToolDefinition definition) {
    if (definition == null) throw new IllegalArgumentException(...);
    definition.options().undo().ifPresent(spec -> specs.put(
        new ToolName(definition.effectiveName()), spec));
  }
  public synchronized void rebuildFrom(ToolRegistry registry) {
    specs.clear();
    for (ToolDefinition d : registry.list()) register(d);
  }
  public synchronized Option<UndoSpec> specOf(ToolName name) {
    return Option.ofNullable(specs.get(name));
  }
  public synchronized boolean hasUndo(ToolName name) { return specs.containsKey(name); }
  public synchronized Map<ToolName, UndoSpec> snapshot() { return Map.copyOf(specs); }
  public synchronized int size() { return specs.size(); }
}
```

## 4. UndoToolFactory

File: `engine/.../runtime/tool/undo/UndoToolFactory.java`

```java
public final class UndoToolFactory {
  private UndoToolFactory() {}

  public static ToolDefinition create(ToolDefinition forward, UndoSpec spec,
                                     UndoRuntime runtime) {
    String effective = forward.effectiveName();
    ToolName undoName = new ToolName("undo_" + effective);
    Schema input = Schema.obj(Map.of(
        "callId", Schema.str(),
        "sessionId", Schema.str()), List.of("callId"));
    ToolExecutor exec = (input, ctx) -> runtime.executeUndo(input, ctx, forward, spec);
    ToolOptions opts = new ToolOptions(
        forward.options().namespace(),
        Option.of("undo_" + normalize(effective)),
        forward.options().codemode(),
        false, false, Option.none(), Option.none());
    return new ToolDefinition(undoName, "Undo a prior " + effective + " call",
        input, Option.none(), exec, opts);
  }
}
```

Note: `runtime.executeUndo` is the entry point that takes the forward tool + spec
so the factory does not need to resolve them again.

## 5. UndoRuntime

File: `engine/.../runtime/tool/undo/UndoRuntime.java`

```java
public final class UndoRuntime {
  private final Journal journal;
  private final ToolRegistry registry;
  private final UndoRegistry undoRegistry;
  private final ToolRuntime toolRuntime;
  private final ToolAuthorizer authorizer;
  private final PermissionGate permission;
  private final Clock clock;

  public UndoRuntime(Journal journal, ToolRegistry registry, UndoRegistry undoRegistry,
                     ToolRuntime toolRuntime, ToolAuthorizer authorizer,
                     PermissionGate permission, Clock clock) { ... null checks ... }

  public UndoOutcome executeUndo(JsonValue input, ToolContext ctx,
                                ToolDefinition forward, UndoSpec spec) {
    // 1. parse request
    String callIdStr = requireString(input, "callId");
    String sessionIdStr = optionalString(input, "sessionId").orElse(ctx.sessionId().value());
    SessionId sessionId = SessionId.of(sessionIdStr);
    CallId originalCallId = CallId.of(callIdStr);

    // 2. reconstruct from log
    List<StoredEvent> events = journal.history(sessionId.value(), Seq.zero());
    StoredEvent toolCalled = findToolCalled(events, originalCallId);
    if (toolCalled == null) return new UndoOutcome.MissingLogEntry(callIdStr);
    SessionEvent.ToolCalled tc = (SessionEvent.ToolCalled) toolCalled.payload();
    ToolName forwardName = tc.toolName();

    // verify spec matches
    if (!forwardName.equals(new ToolName(forward.effectiveName())))
        return new UndoOutcome.NoPattern(forward.effectiveName());

    StoredEvent succeeded = findToolSucceeded(events, originalCallId);
    StoredEvent failed = findToolFailed(events, originalCallId);
    if (failed != null) return new UndoOutcome.NotApplicable(callIdStr, "original failed");
    if (succeeded == null) return new UndoOutcome.NotApplicable(callIdStr, "no success record");

    SessionEvent.ToolSucceeded ts = (SessionEvent.ToolSucceeded) succeeded.payload();
    JsonValue originalInput = tc.input();
    JsonValue originalOutput = toolSucceededOutput(ts); // content -> JsonValue
    Map<String,Object> metadata = ts.metadata();

    // 3. idempotency: already undone?
    if (alreadyUndone(events, originalCallId)) {
      return new UndoOutcome.AlreadyUndone(callIdStr);
    }

    // 4. guard
    if (!spec.guard().allow(originalInput, originalOutput, metadata)) {
      return new UndoOutcome.GuardRejected(callIdStr, "guard rejected");
    }

    // 5. mapper
    JsonValue undoInput;
    try { undoInput = spec.mapper().map(originalInput, originalOutput, metadata); }
    catch (RuntimeException e) { return new UndoOutcome.Failed(callIdStr,
        new SessionError.ToolExecutionError("mapper_error", e.getMessage())); }

    // 6. resolve compensating tool
    ToolDefinition compensating = registry... require(spec.compensatingToolName());
    // permission
    PermissionRequest req = authorizer.request(ctx, compensating, new ToolCall(...));
    try { permission.assertAllowed(req); }
    catch (PermissionDenied denied) { return ... Failed(permission_denied) }

    // 7. execute
    CallId undoCallId = CallId.create(); // or derived
    ToolContext derivedCtx = new ToolContext(sessionId, ctx.agentId(),
        ctx.messageId(), undoCallId, IdempotencyKey.of(sessionId, undoCallId),
        ctx.principal(), ctx.toolTokenService());
    ToolCall undoCall = new ToolCall(spec.compensatingToolName(), undoInput, undoCallId);
    ToolOutcome outcome = toolRuntime.execute(undoCall, derivedCtx);

    // 8. audit journal entry
    try {
      journal.append(new EventDraft(sessionId.value(), 1,
        new SessionEvent.ToolCalled(sessionId, ctx.messageId(), undoCallId,
            new ToolName("undo_" + ...), input, true)));
      // + ToolSucceeded/Failed mirror outcome with metadata marker undoOf
    } catch (RuntimeException ignored) {}

    return (outcome instanceof ToolOutcome.Ok ok)
        ? new UndoOutcome.Undone(callIdStr, undoCallId.value(), outcome)
        : new UndoOutcome.Failed(callIdStr, ...);
  }
}
```

Helpers: `findToolCalled`, `findToolSucceeded`, `findToolFailed`, `alreadyUndone`,
`toolSucceededOutput` (convert `List<ToolContent>` to JsonValue; if single text
item use its text, else encode list).

Note: `ToolCalled.input` is `JsonValue`; `ToolSucceeded.content` is
`List<ToolContent>` and `metadata` is `Map<String,Object>`. Need conversion
utility. `ToolOutcomeJson` / `ReadTool` may help. Check `ToolContent`.

### 9. Engine wiring

File: `quarkus/.../http/Engine.java`, around the tool registration block
(lines ~846–858):

```java
// existing
registry.register(SkillTool.definition(...));
...
toolSnapshot = registry.snapshot(allowAll);

// NEW: build undo registry + undo tools
undoRegistry = new UndoRegistry();
undoRegistry.rebuildFrom(registry);
for (ToolDefinition fwd : new ArrayList<>(registry.list())) {
  fwd.options().undo().ifPresent(spec -> {
    ToolDefinition undoTool = UndoToolFactory.create(fwd, spec, null /*runtime not ready*/);
    // runtime not ready yet -> factory stores a lazy reference
  });
}
```

Problem: `UndoToolFactory.create` needs `UndoRuntime`, which needs `toolRuntime`,
which needs `toolSnapshot`, which needs undo tools registered first → cycle.

Resolution: two-pass:
1. First pass: register all forward tools (existing).
2. `undoRegistry.rebuildFrom(registry)`.
3. Create `UndoRuntime` with a **lazy/late-bound** `ToolRuntime` reference
   (e.g., `UndoRuntime` holds `Supplier<ToolRuntime>` or is constructed
   after toolRuntime). Since UndoRuntime.executeUndo calls toolRuntime.execute,
   and undo tools are only invoked at runtime (after init), we can construct
   UndoRuntime after toolRuntime and inject it into the undo tool executors via
   a mutable holder.

Approach: `UndoToolFactory.create(forward, spec, runtimeHolder)` where
`runtimeHolder` is `AtomicReference<ToolRuntime>` (or a small holder). The
executor calls `holder.get().execute(...)`. After `toolRuntime` is created,
set `holder.set(toolRuntime)`. This breaks the cycle cleanly.

Alternatively, construct undo tools with an executor that looks up `toolRuntime`
from `Engine` via a supplier. I'll use `AtomicReference<ToolRuntime> holder`.

Also `UndoRuntime` itself needs `toolRuntime` for executing the compensating
tool → pass the same holder's value (resolved at call time) or construct
`UndoRuntime` after `toolRuntime`. `UndoRuntime` constructor can take
`Supplier<ToolRuntime>`.

Let me adjust: `UndoRuntime` holds `Supplier<ToolRuntime> toolRuntimeSupplier`.
`UndoToolFactory` executor calls `undoRuntime.executeUndo(...)` (UndoRuntime
already exists and holds the supplier). So:

1. Register forward tools.
2. `undoRegistry.rebuildFrom(registry)`.
3. Create `undoRuntime = new UndoRuntime(journal, registry, undoRegistry,
   () -> toolRuntime, authorizer, permissionGate, clock)` (supplier).
   But toolRuntime not yet assigned. Use `AtomicReference<ToolRuntime> toolRuntimeRef = new AtomicReference<>()`.
   `undoRuntime = new UndoRuntime(..., toolRuntimeRef::get, ...)`.
4. For each forward with undo: `registry.register(UndoToolFactory.create(fwd, spec, undoRuntime))`.
5. `toolSnapshot = registry.snapshot(allowAll)`.
6. `toolRuntime = new ToolRuntime(toolSnapshot, ...)`; `toolRuntimeRef.set(toolRuntime)`.

This resolves the cycle. Undo tools are in the snapshot.


## 10. Implementation Details (precise)

### 10.1 Verified signatures to use

- `ToolCall` record: `(CallId id, ToolName name, JsonValue input)`.
- `ToolContent`: sealed interface with `TextItem(String text)` and
  `FileItem(String uri, String mime, Option<String> name)`.
- `StoredEvent`: `(String aggregateId, Seq seq, String type, int version,
  Payload payload, Instant time)`; `.payload()` returns `Payload`.
- `SessionEvent.ToolSucceeded`: `(sessionId, assistantMessageId, callId,
  content: List<ToolContent>, metadata: Map<String,Object>, executed: boolean)`.
- `SessionEvent.ToolCalled`: `(sessionId, assistantMessageId, callId, toolName,
  input: JsonValue, executed: boolean)`.
- `Seq.zero()` / `Seq.of(long)`; `Journal.history(String aggregateId, Seq after)`.
- `IdempotencyKey.of(SessionId, CallId)`; `CallId.create()` / `CallId.of(String)` /
  `CallId.value()`.
- `SessionError.ToolExecutionError(String code, String message)`.

### 10.2 UndoRuntime helper methods (exact)

```java
private StoredEvent findToolCalled(List<StoredEvent> events, CallId callId) {
  for (StoredEvent e : events) {
    if (e.payload() instanceof SessionEvent.ToolCalled tc
        && tc.callId().equals(callId)) return e;
  }
  return null;
}
private StoredEvent findToolSucceeded(List<StoredEvent> events, CallId callId) { /* ToolSucceeded */ }
private StoredEvent findToolFailed(List<StoredEvent> events, CallId callId) { /* ToolFailed */ }

private boolean alreadyUndone(List<StoredEvent> events, CallId originalCallId) {
  for (StoredEvent e : events) {
    if (e.payload() instanceof SessionEvent.ToolSucceeded ts) {
      Object undoOf = ts.metadata().get("undoOf");
      if (originalCallId.value().equals(String.valueOf(undoOf))) return true;
    }
  }
  return false;
}

private JsonValue toolSucceededOutput(SessionEvent.ToolSucceeded ts) {
  List<ToolContent> content = ts.content();
  if (content.isEmpty()) return JsonValue.ofNull();
  if (content.size() == 1 && content.get(0) instanceof ToolContent.TextItem ti) {
    try { return Json.parse(ti.text()); }
    catch (RuntimeException ignored) { return JsonValue.of(ti.text()); }
  }
  return JsonValue.of(content.stream().map(c -> ...).toList());
}
```

Use existing `Json` utility (`de.augmentia.oc.engine.core.json.Json`).

### 10.3 Error handling

`UndoRuntime.executeUndo` returns `UndoOutcome` (sealed). It must NOT throw for
expected cases (missing log, no pattern, guard, already undone, permission
denied, mapper error, original failed). Only programming errors throw.

`PermissionDenied` is caught and mapped to
`UndoOutcome.Failed(..., permission_denied)`.

### 10.4 Audit journal append

After compensating execution, append:

```java
journal.append(new EventDraft(sessionId.value(), 1,
    new SessionEvent.ToolCalled(sessionId, ctx.messageId(), undoCallId,
        new ToolName("undo_" + forward.effectiveName()),
        JsonValue.of(Map.of("callId", originalCallId.value())),
        true)));
// mirror outcome:
if (outcome instanceof ToolOutcome.Ok ok) {
  Map<String,Object> marker = new LinkedHashMap<>(ok.result().metadata());
  marker.put("undoOf", originalCallId.value());
  journal.append(new EventDraft(sessionId.value(), 1,
    new SessionEvent.ToolSucceeded(sessionId, ctx.messageId(), undoCallId,
        List.of(new ToolContent.TextItem(
            ok.result().output().map(Json::encode).orElse(""))),
        marker, true)));
} else if (outcome instanceof ToolOutcome.Failed f) {
  Map<String,Object> marker = new LinkedHashMap<>(f.metadata());
  marker.put("undoOf", originalCallId.value());
  journal.append(new EventDraft(sessionId.value(), 1,
    new SessionEvent.ToolFailed(sessionId, ctx.messageId(), undoCallId,
        f.error(), marker, true)));
}
```

Wrap appends in try/catch (log only) so audit failures don't fail the undo.

### 10.5 Permission naming

Undo tool permission action: `"undo_" + normalize(effectiveName)` (lowercased,
non-alphanumeric → `_`). Allows separate permission rules. Default effect
follows global default.

Compensating tool uses its own permission (unchanged).

### 10.6 Idempotency key for undo call

`IdempotencyKey.of(sessionId, undoCallId)` (derived). Prevents cache
collisions with the forward call.

### 10.7 ToolCall construction in UndoRuntime

```java
ToolCall undoCall = new ToolCall(undoCallId, spec.compensatingToolName(), undoInput);
```

### 10.8 Engine wiring (precise, cycle-safe)

File: `quarkus/.../http/Engine.java`, replace the block around
`toolSnapshot = registry.snapshot(allowAll);`:

```java
registry.register(McpResourceTools.list(mcp.manager()));
registry.register(McpResourceTools.read(mcp.manager()));

// --- Undo / Saga support ---
UndoRegistry undoRegistry = new UndoRegistry();
undoRegistry.rebuildFrom(registry);

AtomicReference<ToolRuntime> toolRuntimeRef = new AtomicReference<>();
UndoRuntime undoRuntime = new UndoRuntime(journal, registry, undoRegistry,
    toolRuntimeRef::get, effectiveAuthorizer, permissionGate, clock);

for (ToolDefinition fwd : new ArrayList<>(registry.list())) {
  fwd.options().undo().ifPresent(spec ->
      registry.register(UndoToolFactory.create(fwd, spec, undoRuntime)));
}

toolSnapshot = registry.snapshot(allowAll);
toolRuntime = new ToolRuntime(toolSnapshot, effectiveAuthorizer,
    permissionGate, toolHooks, toolOutput, toolResultCache, stepTimeoutSeconds);
toolRuntimeRef.set(toolRuntime);

// store for later access / tests
this.undoRegistry = undoRegistry;
this.undoRuntime = undoRuntime;
```

Add fields `private UndoRegistry undoRegistry;` and `private UndoRuntime undoRuntime;`.
`effectiveAuthorizer`, `permissionGate`, `clock` are in scope at that point.

## 11. File-by-file Task List

### M1 — Contract (engine module)
1. Create `core/contract/tool/UndoSpec.java`.
2. Create `core/contract/tool/UndoInputMapper.java`.
3. Create `core/contract/tool/UndoGuard.java`.
4. Modify `core/contract/tool/ToolOptions.java` (add `undo` component +
   constructors + `withUndo`).
5. Tests: extend `ContractTypesTest` / new `ToolOptionsUndoTest`:
   - default undo = none
   - withUndo carries through withStrict / withApprovalMessage
   - UndoSpec null guard defaults to allow-all
   - UndoSpec null compensatingToolName / mapper throws

### M2 — Registry + Factory (engine module)
6. Create `runtime/tool/undo/UndoRegistry.java`.
7. Create `runtime/tool/undo/UndoToolFactory.java`.
8. Tests: `UndoRegistryTest`, `UndoToolFactoryTest`.

### M3 — Compensator (engine module)
9. Create `runtime/tool/undo/UndoRequest.java`, `UndoOutcome.java`.
10. Create `runtime/tool/undo/UndoRuntime.java`.
11. Tests: `UndoRuntimeTest` covering:
    - happy path reverses
    - missing log entry → MissingLogEntry
    - original failed → NotApplicable
    - no pattern → NoPattern
    - guard rejected → GuardRejected
    - mapper throws → Failed(mapper_error)
    - permission denied → Failed(permission_denied)
    - double undo → AlreadyUndone
    - compensating tool failure → Failed

### M4 — Wiring (quarkus module)
12. Modify `quarkus/.../http/Engine.java` (§10.8).
13. Verify `mvn -pl engine,quarkus -am test` passes.
14. Manual smoke: register a test tool with undo spec, invoke, then
    `undo_<tool>` with its callId → verify reversal + journal marker.

### M5 — (optional) Saga orchestration
15. `SagaScope` tracking executed callIds per turn; on step failure compensate
    in reverse order. Deferred.

### M6 — Docs + ArchUnit
16. Update `docs/spec/tools/` (if exists) with undo contract.
17. ArchUnit: `core` package has no Quarkus/Jackson imports.

## 12. Backward Compatibility & Migration

- `ToolOptions` keeps all existing constructors (delegating with undo=none).
  All 28 call sites compile unchanged.
- `ToolDefinition` unchanged.
- `UndoRegistry.rebuildFrom` is additive.
- No database migration (journal schema unchanged; undo uses existing events
  + metadata marker).
- Native reflection: no new event classes in MVP (dedicated event deferred).

## 13. Test Strategy

- Unit tests in `engine` module (pure Java, no Quarkus).
- `UndoRuntimeTest` uses `InMemoryJournal` + a fake compensating tool.
- Mapper purity verified by code review + tests using a mapper that records
  calls.
- Integration smoke in quarkus module (optional, manual).

## 14. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Cycle: undo tools need runtime, runtime needs snapshot | `AtomicReference<ToolRuntime>` holder resolved at call time |
| Journal output → JsonValue conversion ambiguity | `toolSucceededOutput` tries JSON parse, falls back to text wrapper |
| Double undo | Metadata marker + `alreadyUndone` check |
| Partial undo on crash | Undo is a normal tool call logged in journal; recovery re-runs via saga (Phase 2) |
| Permission bypass via undo | Undo tool + compensating tool both go through authorizer/permission gate |

## 15. Definition of Done (MVP)

- [ ] `ToolOptions` has `undo` component, backward-compat constructors preserved.
- [ ] `UndoSpec`, `UndoInputMapper`, `UndoGuard` in `core/contract/tool`.
- [ ] `UndoRegistry`, `UndoToolFactory`, `UndoRuntime`, `UndoRequest`,
      `UndoOutcome` in `runtime/tool/undo`.
- [ ] Engine wires undo registry + undo tools + runtime.
- [ ] Tests green: M1–M4 unit tests.
- [ ] `mvn -pl engine,quarkus -am test` passes.
- [ ] At least one built-in tool (e.g. `write`) declares an undo pattern as
      a reference implementation, with a passing integration test.
- [ ] Docs updated (`docs/spec/tools/` or relevant).
