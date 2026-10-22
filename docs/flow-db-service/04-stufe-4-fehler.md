# 04 – Level 4: Error handling

> Depth: **Level 4**. Every error at every level: where it comes from, how it
> is classified, what stays durable, what the user sees, and what happens on
> the next start.

## 1. What the client sees

Every error that reaches the runner becomes a `SessionError.Error`
(`packages/schema/src/session-error.ts:6-14`):

```ts
{ type: string, message: string, status?: number, response?: { body: string }, retryAfterMs?: number }
```

`type` is a **free string**. The types produced live in
`packages/core/src/session/to-session-error.ts:31-93`:

| `type` | Source | Line |
|---|---|---|
| `provider.rate-limit` | `AIError` / `RateLimit` | `:34-35` |
| `provider.auth` | `AIError` / `Authentication` | `:36-37` |
| `provider.quota` | `AIError` / `QuotaExceeded` | `:38-39` |
| `provider.free-limit` | `QuotaExceeded` + `FreeUsageLimitError` | `:40-43` |
| `provider.content-filter` | `AIError` / `ContentPolicy` | `:44-45` |
| `provider.transport` | `AIError` / `Transport` | `:46-47` |
| `provider.internal` | `AIError` / `ProviderInternal` | `:48-49` |
| `provider.invalid-output` | `AIError` / `InvalidProviderOutput` | `:50-51` |
| `provider.invalid-request` | `AIError` / `InvalidRequest` | `:52-53` |
| `provider.unsupported-operation` | `AIError` / `UnsupportedOperation` | `:54-55` |
| `provider.no-route` | `AIError` / `NoRoute`, model/variant errors | `:56-57`, `:81-90` |
| `provider.unknown-provider` | `AIError` / `UnknownProvider` | `:58-59` |
| `provider.timeout` | `AIError` / `Timeout` | `:60-61` |
| `permission.rejected` | `Permission.BlockedError` / `CorrectedError` | `:68-69` |
| `tool.execution` | `ToolFailure` / `Tool.Error` | `:70-76` |
| `tool.unknown` | "Unknown tool: …" | `publish-llm-event.ts:559-562` |
| `unknown` | everything else | `:80`, `:92` |
| `aborted` | interrupt | `step.ts:62-64` |
| `compaction.failed` / `compaction.interrupted` | compaction | `llm.ts:148-151`, `:351` |
| `output-limit` | output limit exhausted | `llm.ts:305-308` |

Special case "ChatGPT token sharing": nine specific codes are translated into
readable text (`to-session-error.ts:10-26`).

## 2. Provider errors in detail

### 2.1 The `AIError` class

`packages/ai/src/provider-error.ts` classifies provider errors:

```ts
export const isRetryable = (error: AIError) => {          // :83-…
  const override = error.reason.http?.headers["x-should-retry"]
  if (override === "true") return true
  if (override === "false") return false
  switch (error.reason._tag) {
    case "RateLimit": case "ProviderInternal": return true
    case "Transport":
      return error.reason.delivery !== "rejected" &&
        (error.reason.delivery !== "accepted" || error.reason.operation === "read")
    case "InvalidProviderOutput": return error.reason.classification === "incomplete-stream"
    case "UnknownProvider": return true
    …
  }
}
```

Rule of thumb: **unrecognized errors are retried** – classification only
records affirmative, deterministic evidence; transient errors come in forms no
classifier can foresee (comment `:100-102`).

`isContextOverflowFailure` (`:74-77`): only `InvalidRequest` with
`classification === "context-overflow"`. Text-pattern errors (`isContextOverflow`,
`:68-70`) are explicitly excluded: "throttling error", "rate limit", "too many
requests", "max_tokens must be less".

### 2.2 The retry mechanism

Two levels:

**a) The scheduled retry** (`packages/core/src/model-retry.ts`):

```ts
const schedule = Schedule.max([                                  // :25-28
  Schedule.min([Schedule.exponential("2 seconds"), Schedule.spaced("10 seconds")]),
  Schedule.recurs(10),
]).pipe(Schedule.jittered, …)
```

* Delay: exponential from 2 s, **capped at 10 s per gap**, jittered.
* Count: `Schedule.recurs(10)` → at most **10** retries.
* `retryAfter` from the provider is respected, but capped at **15 minutes**
  (`:12`, `:14-20`).
* Timeouts: at most **3** attempts (`:40`, `:53-57`) – a dead provider must
  not hold a call for an hour.

**b) The session runner** (`packages/core/src/session/runner/retry.ts`):

```ts
const decide = (input) => {                                   // :36-53
  const next = yield* decide(input)                            // ModelRetry
  if (!next) return { retry: false }
  yield* input.hook(event)                                    // Plugin-Hook session.retry
  if (!event.decision.retry) return event.decision
  return { retry: true, attempt: next.attempt, delay: normalized }
}
```

`wait` (`:60-76`) publishes `session.retry.scheduled` with `attempt` and `at`
and then sleeps for the wait time – **the step waits**, it is not aborted.

> **Spec drift (unconfirmed, but code-backed):**
> `specs/v2/session.md:84` says "The initial request plus at most **four**
> retries". The code allows up to **10** retries
> (`packages/core/src/model-retry.ts:25-28`). The spec description in
> `docs/01-use-cases.md` (BR-S-14) follows the spec, not the code. Before
> changing the retry policy this should be clarified.

### 2.3 The retry state machine in the step (`step.ts:169-204`)

```
streamFailure (AIError) or unknown finish
   ↓
llmError = toSessionError(llmFailure)      (unless already providerFailed)
   ↓
recoverContinuation && transport error with recovery "retry-full"/"rotate-and-retry-full"
   && !outputStarted                            → Outcome.RecoverFull   (:182-188)
   ↓
retry = input.retry(llmFailure, llmError, isRetryable || (outputStarted && interrupted))
   ↓
retry.retry && !outputStarted                  → Outcome.Retry         (:200-204)
   ↓
llmError                                       → publisher.failAssistant  (:205)
   ↓
retry.retry && outputStarted && no declines
   && no tool interrupts                    → Outcome.Continue      (:263-271)
```

Difference:

| Situation | Outcome | Assistant message ID |
|---|---|---|
| Error **before** durable output | `Retry` | **stays the same** (`:201-202`) |
| Error **after** durable output | `Continue` | **new** + synthetic "Continue from where you left off" (`llm.ts:275-283`) |
| Transport error with `retry-full` before output | `RecoverFull` | stays; `recoverContinuation = false` (`llm.ts:318-320`) |
| Context overflow before output | `Compacted` | new (`llm.ts:314-317`) |

The retry state is projected in the message fold
(`packages/core/src/session/message-updater.ts:406-414`) and cleared again on a
terminal event (`:53-61`).

### 2.4 Context overflow → compaction

```ts
// step.ts:113-120  (in the stream)
if (LLMEvent.is.providerError(event) && isContextOverflowFailure(event)
    && !publisher.record().outputStarted) { overflowFailure = event; return }
```

```ts
// step.ts:155-167
if (!publisher.record().outputStarted && isContextOverflowFailure(overflow)
    && (yield* restore(input.recoverOverflow)))
  return Outcome.Compacted()
```

`recoverOverflow` (`:259-265`) calls `compaction.compact({reason:"overflow"})` –
**exactly once**: after the first `Compacted`, `recoverOverflow = false`
(`llm.ts:314-317`). A second overflow or an overflow **after** durable output
is terminal.

### 2.5 Content filter and unknown finish

* `step-finish` with `reason.normalized === "content-filter"` →
  `providerFailed = true` and `failAssistant(contentFilterError(...))`
  (`publish-llm-event.ts:578-582`). The partially streamed content **stays
  visible**.
* `finish === "unknown"` → the step counts as an error with
  `classification: "incomplete-stream"` (`step.ts:171-179`).
* `provider-error` → `failAssistant({type:"provider.unknown"})`
  (`publish-llm-event.ts:586-589`).

## 3. Tool errors in detail

| Error | Origin | Handling | Model sees |
|---|---|---|---|
| **Invalid input** | `decodeInput` fails (`runtime.ts:64-70`) | `Tool.Error` | "Invalid arguments for tool \"x\": … Update the arguments and call the tool again." (`:89-101`) |
| **Invalid output** | `encodeOutput` fails (`runtime.ts:111-134`) | `Tool.Error` | "Tool returned an invalid value for its output schema: …" |
| **Output without schema** | `tool.output` defined but result has no `output` (`runtime.ts:55`) | `Tool.Error` | "Tool did not return its declared output" |
| **Output without schema, but declared** | `runtime.ts:48` | `Effect.die` | **Defect** – the runner aborts |
| **Foreign failure** (plugin) | `runtime.ts:39-45` | normalized to `Tool.Error` | plain text of the failure |
| **Permission denied** | `Permission.BlockedError` | leaf maps to `ToolFailure` (`write.ts:95`) | `permission.rejected` + message |
| **User declines** | `Permission.DeclinedError` | defect tunnel → `executeTool` recovers → typed failure | **no** tool output; the step ends |
| **User declines with feedback** | `Permission.CorrectedError` | stays typed, leaf turns it into `ToolFailure` | `permission.rejected` + feedback |
| **Question cancelled** | `QuestionTool.CancelledError` | like decline | "The user declined this tool call" (`step.ts:207-215`) |
| **Tool not available** | `tool.ts:275-284` | `Tool.Error` | "Tool is not available for this request: x" / "No tool named \"x\" is currently available." |
| **Execution interrupted** | fiber interrupt | `TOOLS_INTERRUPTED` (`step.ts:62`) | `{type:"aborted", message:"Tool execution interrupted"}` |
| **Result missing** | parser delivers call without result | `RESULT_MISSING` (`step.ts:64`) | `{type:"tool.result-missing"}` |

### The end of a step with tool errors (`step.ts:207-283`)

```
declines (user rejections)      → publisher.failTool(..., "aborted")          :207-215
interrupted = declines > 0 || streamInterrupted || tools.interrupted              :216
toolFailure = TOOLS_INTERRUPTED | toSessionError(tools.failure) | TOOLS_INTERRUPTED  :217-223
if (toolFailure) publisher.failUnsettledTools(toolFailure)                        :224
if (interrupted) publisher.failAssistant(STEP_INTERRUPTED)                        :225
if (llmError || stream ok) {
   missing = publisher.failUnsettledTools(RESULT_MISSING, "hosted")               :229
   if (missing && !llmError && !record.finish) publisher.failAssistant(RESULT_MISSING)  :230
   publisher.failIncompleteTools()                                                 :231
}
…
if (tools.declines.length > 0) {
   if (isLocationClosed()) return Outcome.Completed({ needsContinuation: true })   :275
   return yield* Effect.interrupt                                                  :276
}
if (tools.interrupted && tools.failure) return yield* Effect.failCause(tools.failure)  :278
if (record.failure) return yield* new StepFailedError({ error: record.failure })    :280
```

> **Important:** A user reject **ends the step** (`Effect.interrupt`,
> `step.ts:276`) – the model gets no tool output, the turn ends. The user can
> steer again afterward.

## 4. Interruption

| Kind | Trigger | Claim | Event | Session |
|---|---|---|---|---|
| **User interrupt** | `Session.interrupt` (`session.ts:325-328`) → `coordinator.interrupt(key, "user")` (`run-coordinator.ts:142-162`) | released | `execution.interrupted` (`reason:"user"`) | `idle_outcome = "interrupted"` |
| **Shutdown interrupt** | process exit | **stays in place** | `execution.interrupted` (`reason:"shutdown"`) | resumed on next start |
| **Tool interrupt** | `Fiber.interruptAll(toolRuns)` (`step.ts:90`, `:150`, `:152`) | – | `tool.failed` with `aborted` | – |
| **Location closed** | `lifecycle.isClosed()` (`llm.ts:77-80`) | – | – | `Reloaded` → new drain |

`SessionExecution.interrupt` (`execution.ts:150-167`):

* `coordinator.interrupt` sets `stopping = true`, clears the doorbell and fires
  `Fiber.interrupt(execution.owner)` – **fire and forget**
  (`run-coordinator.ts:158-161`). The interrupt endpoint confirms immediately,
  cleanup runs asynchronously.
* With `resume: true` it then checks whether a promotable item exists
  (`execution.ts:162-166`): a `steer` or a control item wakes execution again;
  **parked queue prompts stay parked**.
* An interrupt on a known but idle session is a **no-op**
  (`run-coordinator.ts:145`); the public API rejects an unknown session
  (`session.ts:328` → `Session.NotFoundError`).

The shutdown interrupt is why the claim is **not** released on shutdown
(`execution.ts:125-132`): the work should continue on the next start.

## 5. Crash recovery (`packages/core/src/session/execution/restart.ts`)

On server start:

```
resumeSuspendedSessions()                                    :193
 ├─ execution.active (local ownership only)                  :194
 ├─ jobs.pendingBackground → shell/subagent recovery         :195-217
 ├─ store.listSuspended() → sessions with open claim         :221-228
 └─ for each: execution.resume(sessionID)
      └─ prepareResume(sessionID)                             :77-99
           ├─ store.countResume(sessionID) → durable attempt counter
           ├─ attempts > maxAttempts (10) → Execution.Failed (RESUME_EXHAUSTED)   :82-90
           └─ otherwise: publish Session.Synthetic("The server restarted while you were working…")  :92-97
```

The rules:

* **At least once**, not exactly once: "local coordination prevents
  concurrent drains, not repeated external side effects after a crash"
  (`:52-53`).
* The attempt counter is **durable** and incremented **before** resume – a
  crash during resume counts (`:78-80`).
* The budget is **per turn**, not per session; a turn that keeps dying cannot
  create a crash loop across restarts (`:25-29`).
* Only a terminal event resets the counter and releases the claim – both in
  the same transaction (`packages/core/src/session/store.ts:220-227`).
* Shells and subagents are notified first, before a child session starts
  (`:206-215`).

Additionally at drain start:

* `settleStaleCompactions` (`llm.ts:326-354`): compaction messages with
  `status = "running"` are marked failed.
* `settleStaleToolCalls` (`llm.ts:356-379`): tool calls still projected as
  `streaming`/`running` are failed as `aborted` – **before** further model
  work starts.

## 6. Errors in admission (Level 1)

| Error | Location | Effect |
|---|---|---|
| `Session.NotFoundError` | `session.ts:158` | nothing admitted |
| `Session.PromptConflictError` | `session.ts:182` (from `LifecycleConflict`) | nothing admitted; item ID reserved for another type/session |
| `Session.AttachmentError` | `prompt.ts:108`, `:193`, `:199` | nothing admitted |
| `Session.SkillNotFoundError` | `prompt.ts:66` | nothing admitted |
| `SessionInbox.LifecycleConflict` as defect in projection | `inbox.ts:293`, `:316`, `:334` | transaction aborts → **event is not written** |

> **Important:** An error in the projection rolls back the entire bus
> transaction (`bus.ts:322-323`). There is no half-written admission.

## 7. Errors in projection / event log

| Error | Location | Meaning |
|---|---|---|
| `InvalidDurableEventError` | `bus.ts:305-318`, `:367-372`, `:394-399` | aggregate mismatch, sequence gap, replay divergence, duplicate event ID |
| Replay divergence | `bus.ts:350-372` | stored event differs from expected → **no** silent drop |
| Unknown event type | `bus.ts:60-90` | decoding throws – old shapes pass through `SessionLegacy.event(...)` |
| `MessageDecodeError` | `history.ts:91-100` | a message row cannot be decoded |
| `Instructions.InitializationBlocked` | `history.ts:236` | initial instruction baseline blocked → admitted input stays pending |

## 8. The most important pitfalls

1. **`catchCause` / `catch` around `execute`** – swallows interruption and
   defects; the runner can no longer abort the call
   (`packages/core/src/tool/AGENTS.md`).
2. **Editing client files by hand** – they are generated.
3. **`packages/core` importing `packages/client`** – violates the layer
   direction.
4. **Creating a `.changeset`** – this repo uses no changesets.
5. **Reusing V1 names** – `write`/`patch` → `edit`, `task` → `subagent`,
   `bash` → `shell`.
6. **Using `experimental.policies`** – deprecated, top-level `policies` is
   the place.
7. **Starting tests from the repo root** – guard `do-not-run-tests-from-root`.
8. **Using `session_pending`** – legacy, active is `session_inbox`.
9. **`CLAUDE.md` as instruction file** – V2 only knows `AGENTS.md`.
10. **Expecting `todowrite`** – only registered when
    `experimental.todo === true`.
11. **Expecting agent `request` overlays** – the V2 runner receives them but
    does not send them.
12. **Injecting instruction files via `Session.synthetic`** – creates a layer
    cycle; use `SessionEvent.Synthetic`
    (`packages/core/src/session/instructions.ts:70-79`).
13. **A tool with `output` but no output in the result** – produces a defect
    (`runtime.ts:47-48`).
14. **Expecting file mutations to be durable** – the bytes are not logged
    (`tool/plugin/write.ts:42-43`).
15. **Expecting `permission.asked` as audit log** – it is ephemeral; there is
    **no** permission audit log.
16. **Expecting snapshots in the DB** – they live in their own Git repo.

## 9. Debugging checklist

| Symptom | First suspect | Tool |
|---|---|---|
| Prompt appears but agent does not react | inbox item was never promoted | `SELECT * FROM session_inbox` |
| "Tool is not available for this request" | tool deregistered between snapshot and call | registry log, `plugin/internal.ts` |
| Permission asks repeatedly | `save` pattern missing or `deny` fires | `SELECT * FROM permission` |
| Model answers with "Unknown tool" | namespace/definition missing in catalog | `packages/core/src/tool.ts:227-288` |
| Step ends without tool result | `RESULT_MISSING` – parser delivered call without result | `publish-llm-event.ts:512-547` |
| Session hangs after restart | open claim without recovery | `session_v2.time_suspended`, `resume_attempts` |
| Tool output truncated | `ToolOutput.truncate` | `metadata.outputPath`, `~/.local/share/opencode/tool-output/` |
| Costs missing in session | `session.usage.updated` is a projection, not an event | `session_v2.cost` |

## Next

* The concrete prompt → [`05-szenario.md`](./05-szenario.md)