# Core – the extended agent-loop core

> **Purpose:** Synthesis and reference work for the *extended Core* of the
> agent loop: scope, components, ports, invariants, current state of the
> Java port, and the extraction boundary to the Outer Skin.
>
> **Relation to the Spec:** This document summarizes and organizes. In case of
> contradictions, the Spec under
> [`spec/session-engine/`](../spec/session-engine/README.md) applies, in
> particular
> [`12-execution-model.md`](../spec/session-engine/12-execution-model.md) and
> [`04-algorithms.md`](../spec/session-engine/04-algorithms.md).
>
> **Status:** 2026-11-09

## 1. Terms

| Term | Meaning |
|---|---|
| **Execution** | A "busy period" per session: Claim → Drain(s) → exactly one terminal event. |
| **Drain** | A runner pass: execute steps until no input remains or a control element (Move/Compaction) forms the boundary. |
| **Step** | Exactly one model call plus the tool executions resulting from it. |
| **Attempt** | A single model call within a Step (Retry/Continuation/Compaction create multiple Attempts per Step). |
| **Turn** | Conceptually: one user prompt until the finished answer; technically several Steps. |
| **Minimal core** | A single Step: build context → call model → execute tools → result. |
| **Extended core** | Minimal core **plus** Execution-Lifecycle: Admission, Delivery, Coordinator, Multi-Step-Drain, Compaction, Recovery. |

## 2. Scope of the extended core

### 2.1 Minimal core (A)

| Building block | Role |
|---|---|
| **Step** | A model call; consume stream, execute tool calls in parallel, normalize result. |
| **ContextBuilder** | Assemble agent, tools, instructions, history and model into a `Prepared` request. |
| **ProviderAdapter** | LLM interface (streaming, model metadata, error classification). |
| **ToolRuntime / ToolAuthorizer** | Tool snapshot, pre/post hooks, execution, authorization at the leaf. |
| **PermissionEngine** | Rule chain: Deny → Allow → Ask; stored approvals; hook. |
| **CompactionService** | Auto-compaction before a request when the context window fills up. |
| **Resilience** | `RetryPolicy`, `LoopDetector`, `ErrorClassifier`, `TriageService`, `PreflightChecker`. |

### 2.2 Extended core (A + B)

| Building block | Role |
|---|---|
| **Coordinator** | Exactly one Execution per session, Claim (Write-Ahead), Doorbell (Coalescing), Terminal-Event. |
| **ExecutionRunner / Drain** | The actual loop: `reach_boundary` → `run_step` → next Step, until `Done`/`Moved`/`Reloaded`. |
| **Admission** | Idempotent enqueue into the Inbox, hooks/attachments, then `wake`. |
| **Delivery** | Deliver steers/queue at safe boundaries; only delivered input is model-visible. |
| **Revert** | Commit open reverts before new work. |
| **Recovery** | Collect claims after process crash, Resume-Budget (max. 10), Synthetic-Message. |
| **SessionService / Retention** | Session lifecycle and cleanup. |
| **Ports** | Stores, Clock, Approval/Question/Policy sources, Wake, Notification. |

## 3. Components and responsibilities

### 3.1 Orchestration

| Class | Package | Responsibility |
|---|---|---|
| `Coordinator` | `runtime.execution` | `wake`/`interrupt`/`shutdown`; acquire Claim; exactly one terminal event; follow-up Execution via Doorbell. |
| `ExecutionRunner` | `runtime.execution` | Interface `run(session, execution, scope) → ExecutionOutcome`. **The Drain logic lives here.** |
| `ExecutionScope` | `runtime.execution` | Scope merge (`Input` dominates `Control`). |
| `SessionExecution` | `runtime.execution` | Runtime state of an Execution: Doorbell, Stop-Flag, interrupt reason. |
| `ExecutionOutcome` | `runtime.execution` | `Succeeded` / `Failed` / `Interrupted`. |
| `Recovery` | `runtime.execution` | Open claims after restart; resume counter; Synthetic-Message. |
| `Step` | `runtime.step` | A model call; parallel tool calls (max. 8, FIFO); `Finished`/`Failed`/`NeedsCompaction`/`Paused`. |
| `StepResult` | `runtime.step` | Result types of a Step (`Finished`, `Failed`, `NeedsCompaction`, `Paused`). |

### 3.2 Context and request

| Class | Package | Responsibility |
|---|---|---|
| `ContextBuilder` | `runtime.context` | `prepare(...)`: System-Prompt, Instructions, History, Tools, Tool-Choice, Header, Cache-Key. |
| `Prepared` | `runtime.context` | Frozen request for exactly one Step; `toRequest()`. |
| `AgentProfile` | `runtime.context` | Agent configuration (System, `maxSteps`, Permissions, Plan-Mode, Ask-Mode). |
| `ContextSize`, `ConversationFolding`, `TitleGenerator` | `runtime.context` | Context size, folding, title generation. |
| `ModelRequest`, `ModelMessage`, `GenerationOptions` | `core.contract.provider` | Provider-neutral request model. |

### 3.3 Model and tools

| Class | Package | Responsibility |
|---|---|---|
| `HttpProviderAdapter` | `runtime.provider.client` | HTTP/SSE adapter, event translation, stream validation. |
| `OpenAiRequestEncoder`, `JsonEventTranslator`, `SseReader` | `runtime.provider.client` | OpenAI-compatible serialization/deserialization. |
| `ToolRegistry` | `runtime.tool.registry` | Registration of tool definitions. |
| `ToolRuntime` | `runtime.tool.runtime` | Tool execution: hooks, decode, authorizer, PermissionGate, output normalization. |
| `ToolAuthorizer` | `runtime.tool.runtime` | Decides whether a tool is executable in the current snapshot. |
| `ToolCall`, `ToolOutcome`, `ToolDefinition`, `ToolContext` | `core.contract.tool` | Domain types of the tool layer. |

### 3.4 Permissions

| Class | Package | Responsibility |
|---|---|---|
| `PermissionEngine` | `runtime.permission.engine` | `decide`/`assertAllowed`/`reply`; Ask-Mode; stored approvals. |
| `PendingApprovals` | `runtime.permission.engine` | Open approval requests per session. |
| `PermissionCheck`, `PermissionDecision`, `PermissionRules` | `core.eval` | Rule evaluation, Deny-before-Allow, Ask dominance. |
| `ApprovalStore`, `ApprovalSource` | `core.port.store` / `core.port.human` | Persistence and human response source. |

### 3.5 Compaction

| Class | Package | Responsibility |
|---|---|---|
| `CompactionService` | `runtime.compaction` | Produce summary, set epoch, journal events. |
| `CompactionPolicy`, `CompactionWindow`, `CompactionContext` | `runtime.compaction` | When/how much to compact. |
| `OverflowCompaction` | `core.eval` | Detection "context overflow → compaction possible". |

### 3.6 Admission and delivery

| Class | Package | Responsibility |
|---|---|---|
| `Admission` | `runtime.session.core` | Idempotent enqueue, hooks/attachments, `wake`. |
| `AdmissionResult` | `runtime.session.core` | `admitted`, `new`, error cases. |
| `Delivery` | `runtime.session.delivery` | Steers before queue, control elements first; makes input visible. |
| `Revert` | `runtime.session.revert` | Commit open reverts before new input. |
| `SessionService`, `SessionRetention` | `runtime.session.core` | Lifecycle, retention. |

### 3.7 Cross-cutting

| Type | Package | Role |
|---|---|---|
| `EventBus`, `Journal`, `PayloadCodec` | `core.event` / `core.port.store` | Events, durable log, serialization. |
| `Clock` | `core.port.runtime` | Time source (testable). |
| `SessionWake` | `core.port.runtime` | Decoupling Admission → Coordinator. |
| `ApprovalSource`, `QuestionSource`, `PolicySource`, `NotificationChannel` | `core.port.human` | Human interaction. |
| `Json`, `JsonValue`, `Schema` | `core.json` | Value model and schema. |

## 4. Flow of the extended core

### 4.1 Execution (Coordinator)

```
wake(session, scope)
  ├─ already running? → Doorbell := merge(Doorbell, scope); return
  └─ otherwise: acquire Claim
       ├─ Claim empty → end
       └─ publish ExecutionStarted
            → runner.run(session, execution, scope)   ← Drain
            → Terminal-Event (Succeeded/Failed/Interrupted)
            → release Claim (except Shutdown)
            → Doorbell set? → wake(...) (Follow-up)
```

### 4.2 Drain (target state per Spec)

```
drain(session, scope, continuation)
  plugins.awaitActivation
  cleanOrphanedCompactions / cleanOrphanedToolCalls
  step := continuation.step ?? 1
  while true:
      r := reachBoundary(session, scope, step, entering, continuation)
      if r != Ready: return r
      continuation := r.continuation
      done := runStep(r.context, step)
      step += 1
      entering := false
```

`reachBoundary` decides:
- **Control elements** (Compaction/Move) → precedence, possibly new context.
- **Delivery** (Steers immediately, Queue only at calm boundary).
- **Abort** when nothing remains (`Done`).
- **Step-Reset** on new input.

### 4.3 Step (implemented in `Step`)

```
runStep(prepared, context)
  events := provider.stream(prepared.toRequest())
  toolCalls := []
  for e in events:
      publish(e)
      if e = ToolCallRequested: toolCalls += e
      if e = Finished: finish, usage
      if e = ProviderFailed:
          needsCompaction? → NeedsCompaction
          otherwise → Failed
  if finish == null → Failed(INCOMPLETE_STREAM)
  if toolsDisabled && ToolCallLeak → Failed
  if toolsDisabled && toolCalls not empty → all Calls Failed
  if hasQuestion → execute sequentially
  else → parallel (max. 8) via ExecutorService
  return Finished(finish, usage, events, outcomes)
```

### 4.4 Attempt/retry boundary

- **One Step = exactly one model call** (G-06). The next Step reloads the History.
- **Retry** (same Assistant-Message-ID) on transient errors before output.
- **Continuation** on errors after output (Synthetic-Message, new ID).
- **Compaction** on context overflow (new ID, new context).
- **OutputLimit** → Synthetic-Message "Split the work", new ID.
- **Paused** (Approval/Question) ends the Step in a typed way and is continued later.

## 5. Ports and adapters (hexagonal boundary)

The core knows **only interfaces**. Concrete technology lives in `engine.adapter.*`
and in the Quarkus-Skin.

| Port | Package | Adapter (examples) |
|---|---|---|
| `SessionStore`, `SessionReader` | `core.port.store` | `adapter.sql.SqlSessions`, `adapter.sqlite.SqliteSessions` |
| `ConversationStore`, `Conversation` | `core.port.store` | `adapter.sql.SqlConversation` |
| `Journal` | `core.port.store` | `adapter.sql.SqlJournal`, `adapter.sqlite.SqliteJournal` |
| `ClaimStore` | `core.port.store` | SQL/SQLite implementations |
| `SnapshotStore` | `core.port.store` | `adapter.sql.SqlSnapshots` |
| `ApprovalStore` | `core.port.store` | `adapter.sql.SqlApprovals` |
| `ToolOutputStore`, `ToolResultCache` | `core.port.store` | SQL/SQLite |
| `InstructionStore` | `core.port.store` | SQL/SQLite |
| `CredentialStore` | `core.port.store` | SQL/SQLite |
| `FileSystem` | `core.port.world` | `adapter.fs.NioFileSystem`, `CachedFileSystem` |
| `ProcessRunner` | `core.port.world` | `adapter.process.JdkProcessRunner`, `SandboxedProcessRunner` |
| `RemoteShell` | `core.port.world` | `adapter.ssh.MinaSshRemoteShell` |
| `HttpTransport` | `core.port.world` | `adapter.net.JdkHttpTransport` |
| `InstructionLoader` | `core.port.world` | `adapter.fs.NioInstructionLoader` |
| `Clock` | `core.port.runtime` | `adapter.time` (System-Clock, Test-Clock) |
| `SessionWake` | `core.port.runtime` | Coordinator-Wake |
| `ApprovalSource`, `QuestionSource`, `PolicySource` | `core.port.human` | HTTP/UI-Adapter |
| `ProviderAdapter` | `core.contract.provider` | `runtime.provider.client.HttpProviderAdapter` |

**Rule:** `engine.core.*` remains free of Quarkus, Jackson, CDI, HTTP and
database drivers. All technical concerns belong in `engine.adapter.*` or in the
Quarkus-Skin.

## 6. Invariants

### 6.1 Execution (X-series)

| # | Invariant |
|---|---|
| X-1 | At most one active Execution per session; further Wakes set exactly **one** Doorbell. |
| X-2 | An Execution = a virtual thread. |
| X-3 | Tool calls of a Step run in parallel, limited by a quota (default 8, configurable 1–32, FIFO). |
| X-4 | No blocking I/O under a lock; `ReentrantLock`, critical section only for state change. |
| X-5 | No `ThreadLocal` for session context; session ID is passed through. |
| X-6 | Abort is Interrupt **plus** its own channel; never reinterpreted as an error. |
| X-7 | Shutdown leaves the Claim standing (Recovery); user abort releases it. |
| X-8 | Waiting sessions hold no thread (Inbox). |

### 6.2 Ordering (G-series)

| # | Guarantee |
|---|---|
| G-01 | Admission before Execution: Inbox row exists before work is scheduled. |
| G-02 | Delivery before visibility: only `InboxDelivered` makes input model-visible. |
| G-03 | `ToolCalled` before the side effect. |
| G-04 | Terminal-Event after the side effect. |
| G-05 | Tool calls parallel, **publication** in call order. |
| G-06 | One Step = one model call; the next Step reloads the History. |
| G-07 | Terminal-Events after cleanup (immediate confirmation on interrupt). |
| G-08 | Interrupt ends running tool calls without discarding parked inputs. |

### 6.3 Recovery

- Recovery is **at-least-once**: it prevents parallel runs, not
  repeated side effects after an abort.
- Resume-Budget: **max. 10 attempts** per session; afterwards
  `ExecutionFailed` and Claim release.
- Before resuming, `SyntheticRaised` ("Server was restarted") is
  published.

## 7. Current state of the Java port

### 7.1 Present (engine module, pure Java)

| Area | Status |
|---|---|
| `Step` (a model call, parallel tools, Paused/Compaction/Failed) | ✅ implemented |
| `ContextBuilder` + `Prepared` | ✅ implemented |
| `ToolRuntime`, `ToolRegistry`, `ToolAuthorizer` | ✅ implemented |
| `PermissionEngine`, `PendingApprovals` | ✅ implemented |
| `CompactionService` | ✅ implemented |
| `Admission`, `Delivery`, `Revert` | ✅ implemented |
| `Coordinator`, `Recovery`, `SessionExecution` | ✅ implemented |
| Ports (`core.port.*`) + SQL/SQLite/FS/Process/SSH adapters | ✅ implemented |
| Provider-Client (`HttpProviderAdapter`) | ✅ implemented |
| `TurnPolicy` | ⚠️ defined, but **not used** |

### 7.2 Not yet in the core

| Gap | Details |
|---|---|
| **ExecutionRunner implementation** | Only the interface exists. The Drain loop (`advanceToStep`, `reach_boundary`, `Moved`/`Reloaded`) is **not ported**. |
| **Coordinator in the productive path** | `Coordinator` is currently only instantiated in tests. The productive loop runs in `quarkus/.../Engine.prompt` (`while (true)` with Step-Budget, Retry, Triage, Persistence). |
| **Cross-Step persistence** | Tool results/History are persisted in the Quarkus `Engine`, not in the core runner. |
| **Multi-Step boundary** | Delivery/Compaction/Move as Step boundary is described in the Spec, but not yet wired in the Java core. |

> **Consequence:** The "extended Core" is conceptually and in its building
> blocks present, but currently **split in two**: the individual parts live
> in the `engine` module, while the orchestrating Multi-Step-Loop is still in
> the Quarkus-Skin (`Engine.prompt`). A real extraction must first port the
> Drain loop into the core.

## 8. Extraction boundary

### 8.1 What belongs in the core

```
agent-kernel/
  core/
    contract/      ProviderAdapter, ToolExecutor, ToolGate, Hooks, ModelRequest
    eval/          TurnPolicy, PermissionRules, OutputBounding, ProviderClassifier
    model/         SessionId, AgentId, Messages, Events, Fehler
    port/          Stores, Clock, Approval/Question/Policy, FileSystem, ProcessRunner
    event/         EventBus, Journal, PayloadCodec
    json/          Json, Schema
  runtime/
    execution/     Coordinator, ExecutionRunner, Recovery, SessionExecution
    session/       Admission, Delivery, Revert, SessionService
    context/       ContextBuilder, Prepared, AgentProfile
    step/          Step, StepResult
    tool/          ToolRuntime, ToolRegistry, ToolAuthorizer
    permission/    PermissionEngine, PendingApprovals
    compaction/    CompactionService, CompactionPolicy
    resilience/    RetryPolicy, LoopDetector, ErrorClassifier, TriageService
    provider/      HttpProviderAdapter (or keep as adapter)
```

### 8.2 What stays outside

- `engine.adapter.*` – SQL/SQLite, FS, Process, SSH, Net, Logging
- `quarkus/` – HTTP-API, SSE, Security (OIDC/Basic), UI, Metrics, Native-Config
- `deploy/`, `cli/`, `tui/`, `test-client/` – Tools and skins
- UI, Auth0, Deployment

### 8.3 Dependency rules

1. `core.*` must **not** know anything from `runtime.*` or `adapter.*`.
2. `runtime.*` may know `core.*` and Ports, but no concrete adapter.
3. `adapter.*` implements Ports; must not make `core.*` and `runtime.*` depend downward.
4. The Quarkus-Skin wires everything via CDI and only calls
   `ExecutionRunner`/`Coordinator`/`Admission`.
5. No `Impl` classes outside the adapter layer.

## 9. Open points / next steps

1. **Port the Drain loop** (`ExecutionRunner` implementation):
   `reachBoundary`, Delivery, Compaction, Move, Step-Reset.
2. **Bring `Coordinator` into the productive path** and switch `Engine.prompt`
   to `Admission` + `Coordinator`.
3. **Activate `TurnPolicy`** (Step/Tool/Token/Wall-Time budget centrally
   instead of in the skin).
4. **Pull persistence into the runner**: Tool results, Assistant-Messages,
   Step endings and Terminal-Events must be published from the core.
5. **Add ArchUnit rules** for the core/adapter boundary.
6. **Load tests** for X-2/X-3/X-8 (virtual threads, tool quota, 1,000
   waiting sessions).

## 10. References

- [`spec/session-engine/README.md`](../spec/session-engine/README.md)
- [`spec/session-engine/04-algorithms.md`](../spec/session-engine/04-algorithms.md)
- [`spec/session-engine/12-execution-model.md`](../spec/session-engine/12-execution-model.md)
- [`spec/session-engine/07-invariants.md`](../spec/session-engine/07-invariants.md)
- [`spec/java-packages.md`](../spec/java-packages.md)
- [`analysis/flow-db-service/02-stufe-2-execution-runner.md`](../analysis/flow-db-service/02-stufe-2-execution-runner.md)
