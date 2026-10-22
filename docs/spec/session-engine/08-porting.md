# 08 – Porting

> How the neutral specification maps onto a concrete implementation –
> today the TypeScript implementation, tomorrow any other.

## 1. Mapping

| Neutral specification | Current implementation | File |
|---|---|---|
| `Session` / `InboxEntry` / `Message` | `SessionSchema.Info`, `SessionInbox.Info`, `SessionMessage.Info` | `packages/schema/src/session*.ts` |
| `Event` (durable) | `SessionEvent.*` | `packages/schema/src/session-event.ts` |
| `LiveEvent` (ephemeral) | `SessionEvent.*` (selection via `DurableDefinitions`) | `packages/schema/src/session-event.ts` |
| Event log | table `event` + `event_sequence` | `packages/core/src/event/sql.ts` |
| `publish` | `Bus.publish` / `Bus.project` | `packages/core/src/bus.ts:289-419` |
| Projection layer A | `SessionProjector` | `packages/core/src/session/projector.ts:460-841` |
| Projection layer B | `SessionMessageUpdater.update` | `packages/core/src/session/message-updater.ts:31-483` |
| `aufnahme` | `Session.prompt` | `packages/core/src/session/session.ts:155-188` |
| Idempotency check | `SessionInbox.reconcile` | `packages/core/src/session/inbox.ts:155-167` |
| Preparation | `SessionPrompt.prepare` | `packages/core/src/session/prompt.ts:29-91` |
| `wecke` | `SessionExecution.wake` → `SessionRunCoordinator.wake` | `packages/core/src/session/execution.ts:169`, `run-coordinator.ts:131-140` |
| Execution coordination | `SessionRunCoordinator` | `packages/core/src/session/run-coordinator.ts` |
| `drain` | `SessionRunner.drain` | `packages/core/src/session/runner/llm.ts:56-193` |
| `grenze_erreichen` | `advanceToStep` | `packages/core/src/session/runner/llm.ts:73-183` |
| `befoerder` | `SessionInbox.promote` | `packages/core/src/session/inbox.ts:496-531` |
| `select_context` | `SessionContext.select` | `packages/core/src/session/context.ts:126-166` |
| `lade_kontext` | `SessionContext.load` | `packages/core/src/session/context.ts:168-184` |
| `baue_anfrage` | `SessionModelRequest.prepare` | `packages/core/src/session/model-request.ts:236-404` |
| `run_step` | `SessionRunner.runStep` | `packages/core/src/session/runner/llm.ts:203-324` |
| `versuch` | `SessionStep.attempt` | `packages/core/src/session/runner/step.ts:73-289` |
| `werkzeug_ausfuehren` | `Tool.Snapshot.execute` → `Tool.execute` → `runtime.execute` | `packages/core/src/tool.ts:264-285`, `:113-156`, `packages/core/src/tool/runtime.ts:30-62` |
| `pruefe_rechte` | `Permission.assert` / `evaluateInput` | `packages/core/src/permission.ts:235-267`, `:173-189` |
| `wiederherstellen` | `SessionRestart.resumeSuspendedSessions` | `packages/core/src/session/execution/restart.ts:193-231` |
| `verdichte` | `SessionCompaction.compact` | `packages/core/src/session/compaction.ts` |
| Provider interface | `LLM.stream` + protocol adapter | `packages/ai/src/llm.ts`, `packages/ai/src/protocols/` |
| Provider error classification | `AIError` + `isRetryable` | `packages/ai/src/provider-error.ts:83-…` |
| Retry plan | `ModelRetry.policy` | `packages/core/src/model-retry.ts:25-59` |
| `SessionError` | `SessionError.Error` | `packages/schema/src/session-error.ts` |
| Error mapping | `toSessionError` | `packages/core/src/session/to-session-error.ts:31-93` |
| Output truncation | `ToolOutput.truncate` | `packages/core/src/tool-output.ts:69-120` |
| Live distribution | `EventFeed` | `packages/server/src/event-feed.ts` |

> The neutral names in the left column are **deliberately chosen differently**
> from the implementation's names. Whoever ports uses the left column.

## 2. What is easy to overlook when porting

| # | Pitfall | Why it happens |
|---|---|---|
| 1 | **Reversing the permission-check order** (stored approvals before denial) | Then "allow permanently" lifts a denial – a security hole. |
| 2 | **Treating rejection as an ordinary error** | Then a "no" becomes a model-side error message and the model tries again. |
| 3 | **Publishing `ToolCalled` after** execution | Then a crash between the two can leave the side effect without a log trace. |
| 4 | **Implementing only one projection layer** | Data is correct, the conversation incomplete. |
| 5 | **Admission and execution in one step** | Then execution can start before the input is durable → input is lost. |
| 6 | **Tool loop in memory** | Breaks resumability, doubles tool calls and moves state out of the log. |
| 7 | **Turning a defect into an error** | A programming error becomes a user problem. |
| 8 | **Classifying provider errors by text pattern** | "rate limit" is not a context overflow, "too many tokens" is not a context overflow. |
| 9 | **Output truncation in the tool** instead of in the run | Then the model does not know the truncation rules and cannot work around them; truncation also happens **before** extension hooks. |
| 10 | **Reconstructing history from memory** | Then the session is not reconstructable after a restart. |
| 11 | **Resume counter in memory** | A crash loop across restarts. |
| 12 | **Writing live events into the log** | The live channel grows without bound; buffering becomes a problem. |

## 3. Porting checklist

### Phase 1 – Foundation

- [ ] Model the data types from [`01-types.md`](./01-types.md) (opaque identifiers!).
- [ ] Event log with monotone sequence per aggregate.
- [ ] `publish` as **one** transaction: sequence + event + all projections.
- [ ] Two projection layers implemented separately.
- [ ] Terminal events exclusive (one success **or** one failure).

### Phase 2 – Admission and delivery

- [ ] Inbox with admission, delivery, cancellation, mode change.
- [ ] Idempotency: first admission wins; conflicting ID fails.
- [ ] Per-session lock for admission/delivery/cancellation.
- [ ] Delivery modes: control (priority, safe boundary) and parked (only at quiet boundary, exactly one).

### Phase 3 – Execution

- [ ] Process-local execution per session, doorbell semantics.
- [ ] Write-ahead claim, release on terminal events (except shutdown).
- [ ] Abort channel separate from error channel.
- [ ] Resume on start with durable counter and budget per attempt.

### Phase 4 – Step and tool

- [ ] Step = one model call; reload context per step.
- [ ] Tool snapshot (definitions **and** executors) frozen per request.
- [ ] Execution chain: pre-hook → name resolution → input decoding → leaf → output encoding → post-hook → normalization → truncation.
- [ ] Permission check in chain order; rejection special handling.

### Phase 5 – Provider

- [ ] Neutral event protocol; adapter only translates.
- [ ] Error classification at the edge; `isRetryable` per the given table.
- [ ] Context overflow **only** on explicit classification.
- [ ] Retry budget, wait-time cap, jitter.

### Phase 6 – Hardening

- [ ] Defect detection for all protocol violations from [`06-errors.md`](./06-errors.md) §4.
- [ ] Compaction: basis, epoch, catalog limit.
- [ ] Live channel with own budget per connection, encoding once.
- [ ] Acceptance criteria A-01…A-12 from [`07-invariants.md`](./07-invariants.md) as tests.

## 4. Open decisions

These points are **deliberately undecided** in the present specification.
They are deviations between specification and code or open product questions.

| # | Question | Status | Location |
|---|---|---|---|
| O-01 | How many retries? | Spec says 4, code allows 10. This series follows the code (10) and marks the deviation. | `specs/v2/session.md:84` ↔ `packages/core/src/model-retry.ts:25-28` |
| O-02 | Will an organization-wide rule (`tool.use` with "allow") ever grant access? | No – it only lifts a broader denial. Noted, but the question is whether that stays intended. | `packages/managed-policy.ts` |
| O-03 | What should the location of organization-wide rules be called? | Currently top-level; an older spec still shows the deprecated location. | `packages/schema/src/config/experimental.ts:23-25` |
| O-04 | When will the file-content trace be retrofitted? | Open, marked as comment in code. | `packages/core/src/tool/plugin/write.ts:42-43` |
| O-05 | What is the domain relevance of the statistics/cost metrics? | Not specified. | `packages/core/src/session/stats.ts` |
| O-06 | How do foreign harness integrations (other coding agents) behave? | No specification exists. | `services/www/src/docs/content/console/harnesses/` |
| O-07 | Should the directory boundary (`external_directory`) stay "allow" by default? | Currently yes – a deliberate but **security-relevant** decision. | `packages/schema/src/agent.ts:46-51` |
| O-08 | Are duplicate admission IDs detected after delivery? | Yes, via the projected message – without retaining the enqueue history. | `packages/core/src/session/inbox.ts:113-143` |

> **Recommendation:** Decide O-01 and O-07 before a port – they behave
> visibly and are anchored in tests.

## 5. Recommended order

A port is **not** sensibly linear from bottom to top if tools are ported
first: without an event log the gaps between tool execution and state are
not testable.

```
1. Types + log + projections      (Phase 1)   → without that nothing is testable
2. Admission + delivery           (Phase 2)   → input must not be lost
3. Execution + claim + resume     (Phase 3)   → state survives crashes
4. Step + context + tool          (Phase 4)   → the actual behavior
5. Provider                       (Phase 5)   → outside world
6. Hardening + compaction + live  (Phase 6)   → edge cases
```

The order is the reverse of the usual "compiler first" logic: without log
and projections no test is meaningful, because state cannot be observed.<|end|><|start|>write to=assistant<|channel|>analysis<|message|>Wrote file successfully: spec/session-engine/08-porting.md