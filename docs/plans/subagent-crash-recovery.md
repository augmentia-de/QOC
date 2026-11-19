# Plan: Subagent crash — exact session resume

**Status:** Step 1 implemented — 2026-11-19; Step 2 open
**Reference:** `docs/spec/learnings.md` (P0-02), `docs/engineering/Async-use.md` (§9),
`docs/spec/session-engine/04-algorithms.md`, `docs/plans/qoc-weaknesses-remediation.md`
**Trigger:** Workflow test with `subagent` tool call; after a process crash the
child session was not resumed, instead the node was re-executed and a new child
session was created.

---

## 1. Findings

### 1.1 What happens today

1. The parent (workflow node or agent turn) calls `subagent`.
   `SubagentTool.execute` delegates to `Engine.runSubagent`.
2. `Engine.runSubagent` creates a child session with a parent link
   (`Engine.java:2351`), sets the claim, writes the prompt into the
   child conversation and starts the agent loop (`Engine.java:2373`).
3. **Hard process crash during the child run:**
   - The parent's assistant message with the tool call is not persisted.
     `QocEngine` writes it only after all tool calls have been executed
     (`QocEngine.java:1143-1180`).
   - The child conversation is durable up to the last completed step;
     the child claim stays open (`QocEngine.java:744-749`,
     `1400-1402`).
   - `BackgroundTasks` and `InMemorySubagentSessionRegistry` are in-memory and
     lost (`BackgroundTasks.java:17-21`).
4. **Restart:**
   - `QocEngine.recover()` sees the open child claim. The child is not a
     workflow session and is started as `build` with the prompt
     `server-restarted` (`QocEngine.java:518-566`). The actual agent
     (`SessionInfo.agent()`) is ignored.
   - `WorkflowRecovery` resumes the workflow (`WorkflowRecovery.java:193-222`).
     `WorkflowExecutor.resume` sets `currentNodeId` to the last
     **completed** node (`WorkflowExecutor.java:171-173`).
     `WorkflowStepProviderImpl` computes the next node from that
     (`WorkflowStepProviderImpl.java:175-202`) — i.e. the interrupted node.
     `DefaultNodeDispatcher.runAgent` creates a **new** child session
     (`DefaultNodeDispatcher.java:627-645`).
   - Result: duplicate work, two child sessions, possible workspace conflicts.

### 1.2 Additional gaps

- **Child dies without process crash:** `QocEngine.runPrompt` catches the
  exception and returns `EngineResult.failed`. `Engine.runPrompt` turns that
  into `TurnOutcome.Cancelled`. `Engine.runSubagent` does not check
  `PromptResult.completed()` and returns the error text as a successful
  `SubagentResult` (`Engine.java:2369-2401`). The parent sees `ToolOutcome.Ok`.
- **Idempotency key does not take effect:** The key is
  `idem_{session}_{callId}` (`IdempotencyKey.java:17-21`). The `callId` comes
  from the LLM (`Step.java:151-155`). After the crash the new LLM call
  produces a new `callId` and thus a new key. The `ToolResultCache` is also
  only populated after successful execution (`ToolRuntime.java:92-104`,
  `161-164`).
- **Intent/result missing:** `docs/spec/learnings.md:285-317` (P0-02) requires
  persisting the intent before the side effect and transporting the key
  across approval, execution and recovery. This is not implemented.
- **Active node is not used:** `WorkflowRunJournal` records
  `activeNodeId` (`WorkflowRunJournal.java:85-101`), but
  `WorkflowExecutor.resume` uses only `currentNodeId`.
- **Recovery runs with a reduced tool set:** `Engine.start` calls
  `qocEngine.recover()` before the Quarkus `Step`, the real
  `ToolSnapshot` and the `subagent` runner are wired
  (`Engine.java:1022-1053`). The recovery path therefore uses the core snapshot
  and the no-op permission gate, not the Quarkus tools/rights.

### 1.3 Non-goals

- No exactly-once guarantee. The semantics remain **at-least-once**.
- No SAGA/compensating-action framework in this plan; that is a separate
  topic.
- No recovery in `memory` mode. There, session and journal are gone after
  restart (already verified in phase 3).

---

## 2. Target picture

Two stages, sequential:

| Stage | Goal | Effort |
|---|---|---|
| **1** | No incorrect resume, no silent subagent failure, no duplicate orphan | S |
| **2** | Exact resume of the same child session incl. parent handoff | L |

Stage 1 is an immediate hardening and can be merged independently.
Stage 2 builds on it and changes the persistence and recovery semantics.

---

## 3. Step 1 — immediate hardening

### 3.1 Propagate subagent failures

**File:** `quarkus/.../http/Engine.java` (`runSubagent`)

- After `runPrompt(child, ...)` check the result:
  - `result.outcome() instanceof TurnOutcome.Completed` → success.
  - `TurnOutcome.Paused` → treat as error (subagents may not pause;
    `question` is already filtered).
  - `TurnOutcome.Failed` / `Cancelled` / `Refused` → throw a typed error.
- Error message with `finish`, `outcome` and truncated `text` (max. 500
  characters).
- The existing `catch (Throwable t)` block in `runSubagent` sets the
  `BackgroundTasks` to `FAILED` and publishes `SubagentFinished(state=failed)`.
- `SubagentTool.execute` catches the `RuntimeException` and returns
  `ReadTool.failure("subagent", ...)` → the parent sees `ToolOutcome.Failed`.

**Test:** `SubagentFailurePropagationTest`
- Fake `PromptResult` with `TurnOutcome.Failed` → `SubagentTool` returns
  `ToolOutcome.Failed`.
- Fake `PromptResult` with `TurnOutcome.Cancelled` → likewise `Failed`.
- Successful `Completed` case remains `Ok`.

### 3.2 No longer resume child sessions as `build`

**File:** `engine/.../core/model/SessionId.java`

- New method `Option<SessionId> parent()`:
  - Top-level ID (`ses_<hex>`) → `Option.none()`.
  - Child ID (`ses_ses_<parentHex>_<childHex>`) → parse the parent.
  - Grandchild (`ses_ses_ses_...`) → direct parent.
- New method `boolean isChild()` as a shorthand.
- Tests: top-level, child, grandchild, invalid ID.

**File:** `engine/.../runtime/QocEngine.java` (`createSession`)

- Overload `createSession(String agentName, String user, String mode, SessionId parent)`.
- Existing method delegates with `null`.
- `SessionService.create(location, agent, model, at, parent)` is already
  supported; the child ID then carries the parent.

**File:** `workflow-runner/.../runner/DefaultNodeDispatcher.java`

- `runAgent` creates the child session with the workflow run as parent:
  `engine.createSession(prefixedName, principal.id(), "workspace", parent)`,
  where `parent` is parsed from `cacheKey` when `cacheKey` is a valid
  `SessionId`; otherwise `null`.
- `cacheKey` is the `runId`/workflow session (`WorkflowExecutor.java:217`).
- Thus workflow-node child sessions also carry their parent in the ID.

**File:** `engine/.../runtime/QocEngine.java` (`recover`)

- Before resuming, load `SessionInfo info = stores.sessions().info(sessionId)`.
- If `sessionId.isChild()`:
  - No `runPrompt(..., "build", ...)`.
  - Release the claim.
  - Journal a new `SessionEvent.ChildSessionOrphaned(sessionId, parentId, reason)`.
    If that is too much surface, alternatively use
    `SessionEvent.ExecutionFailed` with
    `SessionError.Unknown("recovery", "child session orphaned; parent must resume")`.
    The new event is cleaner because it avoids the terminal
    `ExecutionFailed` state and can turn into a resume in stage 2.
  - Log: `child session <id> orphaned; parent <parent> owns recovery`.
- If not a child:
  - Resolve the agent name from `info.agent()` via `agentResolver.nameOf(...)`;
    fallback `"build"`.
  - Rest as before.
- Important: stage 1 does **not** resume the child itself. It only prevents
  it from running incorrectly as `build`. Exact resume comes in stage 2.

**Test:** `QocEngineRecoveryTest`
- Open claim on a child session → `ChildSessionOrphaned` (or
  `ExecutionFailed(recovery)` as fallback), claim released, no
  `ExecutionStarted`.
- Open claim on a normal session → still `SyntheticRaised` +
  `ExecutionStarted` (existing behavior).
- Workflow-node child has a parent-encoded ID.

### 3.3 Recovery wiring (finding, full fix in stage 2)

**File:** `quarkus/.../http/Engine.java` (`start`)

- `qocEngine.recover()` is currently called before wiring `step`,
  `toolRuntime` and `subagentTool.setRunner(...)`
  (`Engine.java:1022-1053`).
- In stage 1 this is only a documented side finding: `QocEngine.recover()`
  calls `this.runPrompt(...)` and therefore uses the **QocEngine-internal**
  `Step`/`ToolSnapshot`, not the Quarkus `Step`. Merely moving the call
  therefore does not restore tool parity.
- In stage 1 `recover()` is moved to the end of `start()`, so that at least
  the `subagent` runner is set. This is an improvement, but not a complete
  solution.
- The complete correction (recovery via the outer engine with
  `ExecutionOverrides`) is part of stage 2 (see 4.6).

**Test:** Startup ordering
- A recovery run starts only after `subagentTool.setRunner(...)` (e.g. via an
  ordering flag/fake runner).
- Tool parity is tested only in 4.6.

### 3.4 Acceptance stage 1

- `mvn test` in `qoc/` green.
- A failed child appears at the parent as `ToolOutcome.Failed`, not as
  successful text.
- An orphaned child session is no longer started as `build`.
- Existing recovery tests remain green.
- No change to the functional spec.

---

## 4. Step 2 — Exact resume of the same child session

### 4.1 Durable invocation record

**Goal:** Before the child starts, it is durably known which session, agent
and tool call belong together.

**Option A (preferred): journal events in the parent**

New events in `SessionEvent`:

```java
record SubagentInvocationStarted(
        SessionId parentSession,
        String invocationId,
        String callId,
        String agent,
        String promptHash,
        SessionId childSession,
        String nodeId,              // optional, workflow node
        Instant at) implements SessionEvent {}

record SubagentInvocationFinished(
        SessionId parentSession,
        String invocationId,
        String status,              // SUCCEEDED | FAILED | CANCELLED | ORPHANED
        String resultRef,           // ToolOutputStore key or short text
        String error,
        Instant at) implements SessionEvent {}
```

- Add `EventCodec` and `Journal` serialization.
- `invocationId` = `sub_{parentSession}_{callId}` or UUID; must be stable.
- `promptHash` = SHA-256 over `agent + "\n" + prompt`, first 16 hex
  characters.
- The record is written **before** the child starts, once the child ID is
  known.

**Option B (fallback): table `subagent_invocation`**

If events are too unwieldy: Flyway migration with
`id, parent_session, invocation_id, call_id, agent, prompt_hash, child_session,
node_id, status, result_ref, error, created_at, finished_at`.
Port `SubagentInvocationStore` with `start`, `finish`, `findOpen`,
`findBySemanticKey`.

**Recommendation:** Option A, because the workflow runner is already
journal-based and needs no new schema.

### 4.2 Child resume with its own agent

**File:** `quarkus/.../http/Engine.java` (new method `resumeSubagent`)

- Input: `childSession`, `agentName`, `prompt` (optional), `principal`.
- Flow:
  1. Load `SessionInfo`; resolve `agentName` from `info.agent()` if not passed.
  2. Check whether the child conversation already has a terminal assistant
     message (`ExecutionSucceeded` in the child journal). If yes: read the
     last text, no LLM call.
  3. Otherwise: bring a continue hint into the child context. Two variants:
     - **Preferred:** new `Engine.runPrompt(..., boolean storePrompt)` overload
       that persists the hint "You were interrupted by a restart. Continue the
       task from the last completed step. Do not repeat completed side
       effects." as a user message (`storePrompt=true`).
     - **Alternative:** `QocEngine.runPrompt(..., storePrompt=false)` plus a
       `SyntheticRaised` event; for this `ContextBuilder` must evaluate the
       synthetic events (it does not today).
  4. Return the result as `SubagentResult`.
- The child runs with its own agent profile, not with `build`.
- `subagentDepths` and `sessionRoles` must be set again for the child
  (durable marking, e.g. via `SessionInfo` metadata or the invocation
  record). Otherwise the child can call `question`.

**Test:** `SubagentResumeTest`
- Child with completed run → text is returned without an LLM call.
- Child with open run → `runPrompt` is called with its own agent.
- `question` tool is not available in the child.

### 4.3 Parent handoff

There are two variants; both should exist in the medium term.

#### 4.3a Semantic dedupe (simpler, covers workflow retry)

- The `subagent` tool call computes a semantic key:
  `sem_{parentSession}_{agent}_{promptHash}` (optional `nodeId`).
- Before starting:
  - `findBySemanticKey(...)` in the invocation record.
  - `SUCCEEDED` → return the result from the `ToolOutputStore`/record.
  - `STARTED`/`RUNNING` → resume the child and await the result.
  - `FAILED`/`ORPHANED` → restart or return an error (policy:
    `retryable=true` → restart, otherwise error).
- After starting: write `SubagentInvocationStarted`.
- After finishing: write `SubagentInvocationFinished`.
- Thus a workflow node retry with the same agent/prompt produces no new
  child, even though the `callId` is new.

**Limit:** Two intentional identical calls in the same parent would be
deduplicated. Therefore optional parameter `dedupe=false` on the `subagent`
tool or an explicit `dedupeKey`.

#### 4.3b Turn checkpoint (correct, covers all tools)

**Goal:** The assistant message with the tool calls is persisted **before**
tool execution. After the crash the open calls can be replayed specifically.

**Files:**
- `engine/.../runtime/step/Step.java`: split `run` into two phases:
  - `plan(...)` → `ModelEvents` + `ToolCallRequested` list, without execution.
  - `execute(...)` → executes the calls and returns `StepResult.Finished`.
- `engine/.../runtime/QocEngine.java`:
  - After `plan(...)` write a `StepCheckpoint` event
    (`messageId`, `stepNo`, `text`, `toolCalls`, `idempotencyKeys`).
  - Persist the assistant message with `ToolState.Pending`.
  - After `execute(...)` update the tool states to `Completed`/`Failed`.
- `engine/.../core/contract/tool/ToolCall.java`:
  - Add `IdempotencyKey idempotencyKey` (spec P0-02).
- `engine/.../core/contract/tool/ToolDefinition.java`:
  - Add `boolean sideEffecting`, `boolean supportsIdempotency`.
- Recovery:
  - `StepCheckpoint` without `StepEnded` → re-execute the open calls.
  - `readOnly`/`supportsIdempotency` → retry automatically.
  - `sideEffecting` without dedupe → `ToolOutcome.Failed(UNKNOWN)` or
    `NeedsReview`; no silent retry.
- The `subagent` call uses the invocation record to find and resume the
  existing child instead of creating a new one.

**Effort:** M–L. This is the generalized P0-02 implementation and solves
partial execution for **all** tools, not just subagents.

### 4.4 Workflow: link node → child and resume active node

**File:** `engine/.../core/model/SessionEvent.java`

- New event `WorkflowNodeChildLinked(sessionId, nodeId, childSessionId, agent, attempt)`.
- `WorkflowStepProviderImpl` writes it directly after child creation
  (in `DefaultNodeDispatcher.runAgent`), i.e. **before** node completion.
- `WorkflowRunJournal` reads it and extends `WorkflowRunSnapshot` by
  `activeNodeChildSession`.
- `WorkflowExecutor.resume` passes `snapshot.activeNodeId()` and
  `snapshot.activeNodeChildSession()` to the `WorkflowStepProviderImpl`.
- `WorkflowStepProviderImpl`:
  - On the first `provideStep` after a resume, if `resumeNodeId` is set:
    dispatch exactly that node, not `getNextNodeId`.
  - Delete `resumeNodeId` after successful dispatch.
- `DefaultNodeDispatcher.runAgent`:
  - If `resumeChild` is set: `engine.runPrompt(resumeChild, ...)` instead of
    `engine.createSession(...)`.
  - If the child already has `ExecutionSucceeded`: read the last assistant
    text and return it as `NodeResult` without an LLM call.
  - Otherwise: resume the child with a "continue" prompt.

**Test:** `WorkflowCrashResumeTest`
- Crash during node B (A completed, B started, child exists).
- Resume dispatches B, uses the same child ID, creates no new one.
- Crash after child success, before `WorkflowNodeCompleted` → child result is
  reused.

### 4.5 Make background tasks durable

**File:** `engine/.../runtime/tool/subagent/BackgroundTasks.java`

- New record/port `BackgroundTaskStore` (journal event in the parent or
  table).
- Status: `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, `ORPHANED`.
- On start: `TaskStarted` with `taskId`, `parent`, `agent`, `childSession`.
- On end: `TaskFinished` with status/result.
- On restart: `RUNNING` without `TaskFinished` → `ORPHANED`.
- `task(status)` returns `ORPHANED` incl. `childSession`.
- Policy:
  - Child resumable → `resumeSubagent(...)`.
  - Otherwise → `FAILED(orphaned)`.
- In-memory implementation remains for `memory` mode and tests.

**Test:** `BackgroundTaskRecoveryTest`
- TaskStarted without terminal → `ORPHANED`.
- `task(status)` shows `ORPHANED`.
- Resume policy starts the child or marks `FAILED`.

### 4.6 Recovery via the outer engine

**Problem:** `QocEngine.recover()` uses the core `ToolSnapshot` and the
no-op permission gate. A resumed child therefore does not have the Quarkus
tools (MCP, `subagent`, …) and not the real rights.

**Solution:**
- `QocEngine` gets a callback
  `Function<SessionId, ExecutionOverrides> recoveryOverrides`.
- `Engine.start` sets it after building `step`, `toolSnapshot`,
  `contextBuilder` and `subagentTool.setRunner(...)`.
- `QocEngine.recover` uses the overrides when present; otherwise falls back
  to the core path (framework-neutral mode).
- Alternatively: orchestrate recovery entirely in `Engine` and have
  `QocEngine.recover` only return `openClaims()`.

**Test:** Recovery of a child agent that uses an MCP tool/subagent tool →
the tool is present in the snapshot, the permission gate is called.

### 4.7 Acceptance stage 2

- Crash during a `subagent` call → the **same** child session is resumed; no
  new child ID.
- Crash after child success, before parent handoff → child result is reused;
  no second LLM/tool run.
- Crash during a workflow node → `activeNodeId` is re-entered; the existing
  child session is used.
- `BackgroundTask` is visible as `ORPHANED` after restart.
- `IdempotencyKey` remains stable across the resume; a `ToolResultCache` hit
  prevents the second side effect.
- `mvn test` green; JaCoCo ≥ 80 %.

---

## 5. Test matrix

| Scenario | Stage | Expectation |
|---|---|---|
| Child LLM failure without crash | 1 | `ToolOutcome.Failed`, no silent success |
| Orphaned child after crash | 1 | `ChildSessionOrphaned`, claim released, no `build` run |
| Workflow-node child ID | 1 | Parent-encoded `SessionId` |
| Recovery wiring | 1 | `recover()` runs after runner wiring |
| Crash during child | 2 | same child ID resumed |
| Crash after child success | 2 | result reused, no new run |
| Crash during workflow node | 2 | `activeNodeId` + existing child |
| Background-task crash | 2 | `ORPHANED` visible, policy applied |
| Idempotency key across resume | 2 | same key, cache hit |
| Parallel double start | 2 | claim prevents second execution |

---

## 6. Order and effort

1. **Stage 1.1** Error propagation (S)
2. **Stage 1.2** `SessionId.parent()` + child detection + recovery skip (S–M)
3. **Stage 1.3** Recovery wiring (S)
4. **Stage 1.4** Tests and docs (S)
5. **Stage 2.1** Invocation record (M)
6. **Stage 2.2** Child resume with own agent (M)
7. **Stage 2.3a** Semantic dedupe (M)
8. **Stage 2.4** Workflow node→child + active node (M)
9. **Stage 2.5** Background tasks durable (M)
10. **Stage 2.6** Recovery via outer engine (M)
11. **Stage 2.3b** Turn checkpoint / P0-02 (L, optional afterwards)

**Recommendation:** Merge stage 1 completely, then stages 2.1–2.4. The turn
checkpoint (2.3b) is the generalized solution but not a prerequisite for
subagent resume when semantic dedupe is available.

---

## 7. Risks and non-goals

- **Duplicate side effects:** At-least-once remains. Even with resume, the
  last in-flight tool call may run again. Tools must be idempotent or
  support check-before-retry.
- **Semantic dedupe:** Two intentional identical subagent calls could be
  deduplicated. Mitigation: `dedupe=false`/`dedupeKey` on the tool.
- **Conversation resume:** A child that died mid tool call only sees the
  completed steps on resume. The open call is proposed again by the LLM;
  that is intended, but side-effecting tools need dedupe.
- **Turn checkpoint:** Changes the persistence order of conversation and
  tool execution. Existing tests and UI projections must be adjusted.
- **Memory mode:** No resume; documented non-goal.
- **SAGA:** Not part of this plan. Compensating actions for non-idempotent
  steps remain a separate undertaking.

---

## 8. Implementation status

### Step 1 — implemented (2026-11-19)

- `SessionId.parent()` / `isChild()` added; child IDs carry their parent.
- `QocEngine.createSession(..., parent)` added; `DefaultNodeDispatcher`
  creates workflow-node child sessions with the run as parent.
- `SessionEvent.ChildSessionOrphaned` added (`EventCodec`,
  `NativeReflectionConfig`).
- `QocEngine.recover()` no longer resumes child sessions as `build`,
  journals `ChildSessionOrphaned` and releases the claim.
- Generic recovery uses the session's actual agent
  (`AgentResolver.nameOf`) instead of the hard-coded `build`.
- `Engine.runSubagent` checks `PromptResult.completed()` and propagates
  errors/aborts as tool failure.
- Recovery call in `Engine.start()` moved behind the wiring of `step` and
  `SubagentRunner`.

**Tests:**
`SessionIdTest`, `QocEngineChildRecoveryTest`, `SubagentToolFailureTest`,
`EngineSubagentOutcomeTest`, `DefaultNodeDispatcherParentSessionTest`,
`EventCodecTest`.

**Verification:** `mvn test` green; `mvn -pl engine verify` incl.
JaCoCo gate (80 %) green.

### Step 2 — open

See sections 4.1–4.7. In particular, the durable invocation record, exact
child resume with parent handoff, the turn checkpoint and the recovery
overrides for the outer engine are still missing.