# 09 – Audit: invariants against the code

> **Audit task:** Check each of the 111 invariants from
> [`07-invariants.md`](./07-invariants.md) against the actual code.
> **Status:** 2026-10-22, repo state `dev` (working state `dev` @ `3108505b2f`).
> **Method:** Code read directly, not derived from the docs notes.
> Each invariant gets evidence `path:line` or the classification
> "convention" (not machine-checkable).

## Result

| Classification | Count | Meaning |
|---|---:|---|
| **Confirmed** | 102 | Rule is evidenced by code, wording holds |
| **Refinement** | 4 | Rule holds, but wording was too coarse/sharp |
| **Correction** | 2 | Rule was **wrong** – the code behaves differently |
| **Enforcement note** | 2 | Rule holds, but is enforced elsewhere than claimed |
| **Convention** | 1 | Rule describes an intent the code does not enforce |
| **Total** | **111** | |

The two **corrections** are content-wise important (see §2). They have been
carried into `07-invariants.md`; the checks are noted there.

## 1. The two corrections

### K-1 · I-08-10 was wrong

**Claimed:** "IF a revert is committed, THEN the
instruction state is deleted."

**Code:**

```ts
// packages/core/src/session/instruction-state.ts:160-170
export const rewind = (db, sessionID, boundary) =>
  db.delete(InstructionStateTable).where(
    and(eq(InstructionStateTable.session_id, sessionID),
        gte(InstructionStateTable.through_seq, boundary)),
  )
```

The state is deleted **only** when the fold state reaches the boundary.
The comment above says it explicitly:

> *"Clears the fold only when a committed revert removed an update or the epoch
> start. Otherwise the fold still matches the surviving history, and keeping its
> baseline keeps the prompt cache."*

Caller: `projector.ts:826` (in the `RevertCommitted` projection).
The specification `specs/v2/session.md:96` says the same:
*"committed revert retains state unless it removed an instruction update, in
which case it clears state so the next boundary establishes a fresh baseline."*

**Corrected wording:**

> IF a revert is committed, THEN the instruction state is deleted
> **only if** the revert removed an instruction update or the
> epoch start; otherwise the baseline remains (the prompt cache
> stays valid).

### K-2 · I-09-04 was wrong

**Claimed:** "IF a compaction fails, THEN the previous basis
remains **and the input keeps waiting**."

**Code:**

```ts
// packages/core/src/session/runner/llm.ts:214-215   (automatic compaction)
const compacted = yield* compaction.compact({ reason: "auto", context: loaded })
if (compacted.status === "failed") return yield* new StepFailedError({ error: compacted.error })
```

```ts
// packages/core/src/session/runner/llm.ts:144-153   (manual compaction)
if (Exit.isFailure(compacted)) {
  yield* bus.publish(SessionEvent.Compaction.Failed, { … })
  return yield* Effect.failCause(compacted.cause)
}
```

A compaction failure ends the **run** with an error – the input does
**not** keep waiting. Only the case `Instructions.InitializationBlocked`
leaves the admitted input pending (`llm.ts:197-198`).

**Corrected wording:**

> IF a compaction fails, THEN the previous basis remains untouched
> (no partial summary is stored) **and the run ends with an error** –
> it is not continued with a damaged basis.

## 2. The four refinements

| # | Invariant | Wording too coarse/sharp | Correct is |
|---|---|---|---|
| P-1 | I-02-09 | "new **user input** delivered ⇒ step budget reset" | The counter is reset on **every** delivery – including synthetic inputs and controls. `llm.ts:175`: `if (promoted > 0) step = 1`. The "once per batch" part holds (one `promote` call ⇒ one reset). |
| P-2 | I-03-06 | "IF an unknown session is interrupted, THEN an error is returned" | This holds at the **HTTP boundary**: the session-location middleware checks the session and returns `SessionNotFoundError` (`packages/server/src/middleware/session-location.ts:25`, `packages/server/src/location.ts:64-69`; the endpoint declares the error, `packages/protocol/src/groups/session.ts:693`). **At core level** `Session.interrupt` returns `interrupted: false` for an unknown session – no session check takes place (`packages/core/src/session/session.ts:325-328` → `run-coordinator.ts:145`). |
| P-3 | I-05-04 | "IF a tool declares no output value, THEN it **must** deliver non-empty model content" | The code does **not** enforce this: `normalizeContent` stringifies the missing content (`packages/core/src/tool/runtime.ts:259-263`), producing the text `"undefined"`. It is a **duty for tool authors** (`specs/v2/tools.md:142`), not an enforced contract. |
| P-4 | I-09-02 | "provider-side compaction window does not exceed the compaction limit" | The code **aims** with a budget below the limit (`packages/core/src/session/compaction.ts:224-231`, `:346-355`), but the **provider window itself is opaque** and is not counter-checked. The native path passes a provider object through unchanged (`compaction.ts:357-364`). |

## 3. Two enforcement notes

| # | Invariant | Claimed enforcement | Actual enforcement |
|---|---|---|---|
| D-1 | I-01-05, I-01-06 | Uniqueness of the tool card / exactly one terminal event | Uniqueness is enforced in the **publisher**: `tool.settled` as guard, double completion ⇒ `Effect.die` (`packages/core/src/session/runner/publish-llm-event.ts:373`, `:613`). The **projection** is lenient: `latestTool` finds the **last** hit (`packages/core/src/session/message-updater.ts:37-38`). The invariant holds, but is not prevented at projection level. |
| D-2 | I-04-04 | exactly one terminal event per step | Doubly secured: `stepFailure` is set only once (`publish-llm-event.ts:409`), `publishStepFailure` is guarded against that (`:418`), and in the step `Step.Failed` **or** `Step.Ended` is published (`packages/core/src/session/runner/step.ts:249-260`). |

## 4. The convention

| # | Invariant | Finding |
|---|---|---|
| K-1 · I-07-02 | "IF a durable event arises, THEN **both** projection layers must know it" | The code **cannot** enforce this. `bus.project` (tables) and `message-updater` (folding) are two independent registrations (`packages/core/src/session/projector.ts:460-841`, `packages/core/src/session/message-updater.ts:79-482`). An event registered in only one layer is projected without comment. This is the only rule of the whole series protected **exclusively** by review and tests – and therefore the most important one for a port. |

## 5. Evidence overview by invariant group

| Group | Count | Sources checked |
|---|---:|---|
| I-01 Identity | 6 | `session/session.ts:155-188`, `session/inbox.ts:113-143`, `:155-202`, `runner/publish-llm-event.ts:370-386` |
| I-02 Admission/Delivery | 11 | `session/session.ts:155-188`, `session/prompt.ts:29-91`, `session/inbox.ts:62-63`, `:230-260`, `:386-424`, `:496-550`, `session/runner/llm.ts:73-183` |
| I-03 Execution | 9 | `session/execution.ts:47-51`, `:78-83`, `:109-167`, `session/run-coordinator.ts:55-174`, `session/store.ts:208-227` |
| I-04 Step | 12 | `session/runner/llm.ts:56-324`, `session/runner/step.ts:73-321`, `session/runner/publish-llm-event.ts:361-434`, `:493-568`, `session/compaction.ts:208-254` |
| I-05 Tools | 14 | `tool.ts:103-156`, `:227-296`, `tool/runtime.ts:30-134`, `:259-263`, `tool/plugin/write.ts:64-96`, `tool/plugin/read.ts:47-133`, `tool-output.ts:69-120`, `state.ts:160-251` |
| I-06 Permissions | 15 | `permission.ts:87-97`, `:148-189`, `:227-329`, `schema/src/agent.ts:46-51`, `util/wildcard.ts:3-13`, `file-access.ts:93-161` |
| I-07 Events | 11 | `bus.ts:55-95`, `:225-266`, `:289-419`, `:834-877`, `server/src/event-feed.ts:8-89`, `server/src/handlers/event.ts:11-35`, `session/projector.ts:460-841` |
| I-08 Instructions | 10 | `instructions/index.ts:55-256`, `schema/src/instruction.ts:1-25`, `session/instruction-state.ts:21-282`, `session/projector.ts:716-721`, `:792-828`, `session/instructions.ts:70-79` |
| I-09 Compaction | 5 | `session/compaction.ts:208-254`, `:346-384`, `session/history.ts:39-141`, `session/runner/llm.ts:326-354`, `session/projector.ts:763-769` |
| I-10 Errors/Recovery | 8 | `session/runner/step.ts:129-283`, `session/runner/retry.ts:32-78`, `model-retry.ts:12-73`, `packages/ai/src/provider-error.ts:60-104`, `session/execution/restart.ts:33-231`, `session/store.ts:242-249` |
| I-11 Boundaries | 10 | `SECURITY.md`, `session/runner/model.ts:75-91`, `session/context.ts:94-124`, `tool/plugin/write.ts:42-43`, `permission.ts:220`, `:274` |
| I-12 Acceptance | 12 | Derived from I-01…I-11; no new factual claims |

## 6. What could **not** be checked

| Point | Reason | Consequence |
|---|---|---|
| Semantics of `FiberMap.run` (I-04-12) | `node_modules` is not installed in this checkout; the Effect source was not readable | The conclusion rests on **usage**: the title fiber is never joined (`packages/core/src/session/runner/llm.ts:171-174`), so the call returns immediately. |
| Behavior of `Effect.uninterruptibleMask` | as above | The neutral pseudocode primitives in [`04-algorithms.md`](./04-algorithms.md) are deliberately **abstract**; mapping to Effect is a porting question, not an invariant question. |
| "exactly one model call" (I-04-02) | only provable via a stub provider | Statically evidenced: one `llm.stream` per attempt (`runner/llm.ts:107`), one attempt per step (`runner/llm.ts:203-324`). |
| Order `ToolCalled` before side effect (I-05-07) | not statically provable | The code publishes `ToolCalled` in the publisher stream **before** starting the tool fiber (`runner/step.ts:121-134`); the actual order is therefore structurally secured, but not evidenced by a test. |

## 7. Recommended next steps

1. The three corrections in `07-invariants.md` are already incorporated
   (see head of this file and the change markers in `07`).
2. **Secure I-07-02:** either a test that checks both layers over the
   event manifest, or a generator that reconciles the manifests. Without that
   the most important invariant of the series stays unprotected.
3. **Secure I-04-12 and I-05-07** with one integration test each
   (title fiber does not block; tool call is logged before the effect).
4. **Decide O-01 (retry count)** from [`08-porting.md`](./08-porting.md)
   – invariants I-10-07 and the acceptance criteria depend on it.