# 07 – Invariants

> **Verified:** All 111 rules were checked against the actual code. Result:
> 102 confirmed, 4 refined, 2 corrected, 2 with enforcement note, 1 marked as
> convention. Audit report: [`09-invariants-audit.md`](./09-invariants-audit.md).
>
> Every rule is formulated so that it is **testable**. The column
> "Check type" says how to prove it:
>
> * **Log** – visible in the event log
> * **State** – visible in the projected data
> * **Effect** – visible in the outside world (file, network, provider)
> * **Flow** – derivable only from temporal behavior
>
> This list is the actual contract. An implementation that satisfies these
> rules is conformant – regardless of language, database and
> concurrency model.

---

## I-01 Identity and types

| # | Rule | Check type |
|---|---|---|
| I-01-01 | IF the same session ID is used again, THEN the **existing** session is taken over. | State |
| I-01-02 | IF the same input ID is submitted again with the same session and same type, THEN the **first** admission wins; the retry changes neither content nor metadata nor delivery mode. | Log |
| I-01-03 | IF the same input ID is used for a different session or a different type, THEN the operation **fails** and **nothing** is admitted. | Log |
| I-01-04 | IF an input has already been delivered, THEN it **no longer** exists in the inbox; resumption relies on the projected message. | State |
| I-01-05 | IF a tool call is completed, THEN there is **exactly one** stored tool card for its call ID. Uniqueness is enforced at publish time (double completion ⇒ abort); the projection is lenient and keeps the last hit. | State |
| I-01-06 | IF a tool call fails, THEN there is **exactly one** error and **at most one** concluding partial state. Enforced at publish time, not in the projection. | State |

## I-02 Admission and delivery

| # | Rule | Check type |
|---|---|---|
| I-02-01 | IF an input is admitted, THEN there is **exactly one** inbox row **before** execution is scheduled. | State + Flow |
| I-02-02 | IF an input is admitted, THEN it lies **outside** the model-side history. | State |
| I-02-03 | IF an input is delivered, THEN the inbox row is deleted **and** the visible message inserted – in the same transaction. | State |
| I-02-04 | IF `continue` is missing or true, THEN execution is additionally woken; if false, **only** admission happens. | Flow |
| I-02-05 | IF several controls are pending, THEN they are delivered in admission order. | Log |
| I-02-06 | IF a compaction control is pending, THEN it is delivered **before** older controls, so its text is preserved literally. | Log |
| I-02-07 | IF a move is pending, THEN **no** control is delivered across the move boundary. | Log |
| I-02-08 | IF a parked input is waiting and the session is idle, THEN controls take precedence; otherwise **exactly one** parked input is delivered and then re-evaluated. | Log |
| I-02-09 | IF **any** input is delivered (user input, synthetic, or control), THEN the step budget is reset; a batch resets it **once**. | State |
| I-02-10 | IF an input is admitted, THEN an **open revert** is committed first. | Log |
| I-02-11 | IF an input is admitted, THEN extension hooks and attachment resolution run **only** the first time. | Flow |

## I-03 Execution and concurrency

| # | Rule | Check type |
|---|---|---|
| I-03-01 | IF execution starts, THEN the claim is set in the same transaction as the start event. | State + Log |
| I-03-02 | IF execution ends, THEN there is **exactly one** terminal event for the whole execution period – even with coalesced passes. | Log |
| I-03-03 | IF success, error or user abort occurs, THEN the claim is released. | State |
| I-03-04 | IF a shutdown abort occurs, THEN the claim **stays**. | State |
| I-03-05 | IF a session is inactive and an interrupt arrives, THEN the interrupt is a **no-op**. | Flow |
| I-03-06 | IF an unknown session is interrupted via the **public interface**, THEN an error is returned (session check in middleware). At core level the interrupt is a no-op. | Flow |
| I-03-07 | IF new work is admitted during a pass, THEN **exactly one** further pass follows. | Flow |
| I-03-08 | IF two sessions run, THEN they run **in parallel**. | Flow |
| I-03-09 | IF an interrupt with continuation arrives, THEN controls and control elements continue, parked inputs stay parked. | Flow |

## I-04 Step and model call

| # | Rule | Check type |
|---|---|---|
| I-04-01 | IF a step begins, THEN history is reloaded, agent and model resolved, instructions prepared and tools materialized. | Flow |
| I-04-02 | IF a step runs, THEN it is **exactly one** model call. | Effect |
| I-04-03 | IF a local tool result is present, THEN a **new** step begins; there is no tool loop in memory. | Effect |
| I-04-04 | IF a step ends, THEN there is **exactly one** terminal event. | Log |
| I-04-05 | IF a step fails, THEN an already produced partial content stays visible. | State |
| I-04-06 | IF the last permitted step budget is reached, THEN tools are switched off, but their definitions stay in the request. | Effect |
| I-04-07 | IF an error occurs **before** first output, THEN the step keeps its number and its message ID and consumes **no** further step budget. | Log |
| I-04-08 | IF an incomplete stream occurs **after** output, THEN the failed part stays visible, **one** continuation instruction is added and continuation happens with a **new** message ID. | Log |
| I-04-09 | IF a context overflow occurs **before** output, THEN at most **one** compaction may follow; a second overflow is terminal. | Flow |
| I-04-10 | IF an overflow occurs **after** output, THEN it is terminal. | Flow |
| I-04-11 | IF the request would exceed the context window, THEN compaction happens as long as enough older history exists. | Flow |
| I-04-12 | IF a title fails as a side task, THEN the title message does **not** block the main run. | Flow |

## I-05 Tools

| # | Rule | Check type |
|---|---|---|
| I-05-01 | IF a tool is called, THEN **exactly the** version advertised in the model request runs. | Effect |
| I-05-02 | IF the input is not decoded, THEN the tool is **not** executed. | Effect |
| I-05-03 | IF the output value is not decoded, THEN **no** successful run arises. | Effect |
| I-05-04 | IF a tool declares no output value, THEN it **should** deliver non-empty model content. This is a duty for tool authors, **not** an enforced contract – the code stringifies missing content. | State |
| I-05-05 | IF a tool declares an output value, THEN it MUST return one. | Effect |
| I-05-06 | IF an extension module throws a foreign failure, THEN it is normalized to a tool error at the tool boundary. | Effect |
| I-05-07 | IF a tool call begins, THEN it is **durably logged before** the side effect begins. | Log + Flow |
| I-05-08 | IF a tool call ends, THEN there is **exactly one** terminal event. | Log |
| I-05-09 | IF a tool needs approval, THEN approval is checked **before** the side effect. | Flow |
| I-05-10 | IF a tool output is too large, THEN it is truncated, marked as truncated and stored in full. | State |
| I-05-11 | IF truncation is needed, THEN the **full** output remains findable. | Effect |
| I-05-12 | IF a registration is closed, THEN **only that one** disappears; the previous one becomes visible again. | State |
| I-05-13 | IF a tool is filtered from the catalog, THEN that is **catalog visibility**, not execution authorization. | Flow |
| I-05-14 | IF the model content of a tool is truncated, THEN the machine output value does **not** change. | State |

## I-06 Permissions

| # | Rule | Check type |
|---|---|---|
| I-06-01 | IF several rules match, THEN the **last** wins. | State |
| I-06-02 | IF no rule matches, THEN "ask" applies. | State |
| I-06-03 | IF any resource yields "deny", THEN the operation is blocked – even across a stored approval. | State |
| I-06-04 | IF several resources are checked, THEN "ask" wins over "allow". | State |
| I-06-05 | IF an extension hook changes the evaluation, THEN it may change effect **and** message. | State |
| I-06-06 | IF "always" is answered, THEN the pattern suggested by the **tool** is stored, **per project**. | State |
| I-06-07 | IF "always" is answered, THEN all other open requests are re-evaluated and possibly auto-approved. | Flow |
| I-06-08 | IF "reject" is answered, THEN **all** parallel requests of the same session are rejected. | Flow |
| I-06-09 | IF rejected without a reason, THEN the error is **not** model-visible. | State |
| I-06-10 | IF rejected with a reason, THEN the error stays model-visible and the model may continue. | State |
| I-06-11 | IF a path lies outside location and project workspace, THEN the **directory boundary** is checked first, then the file rule. | State |
| I-06-12 | IF the directory boundary is not expressly governed, THEN "allow" applies – external access raises **no** prompt. | State |
| I-06-13 | IF a pattern ending in `*` matches a command **without** arguments, THEN it counts as a hit. | State |
| I-06-14 | IF action `*` and resource `*` are denied, THEN code mode and all tools reachable only through it disappear from the catalog. | State |
| I-06-15 | IF a tool is filtered from the catalog, THEN its nested tools keep their own rules. | State |

## I-07 Events and projections

| # | Rule | Check type |
|---|---|---|
| I-07-01 | IF a durable event arises, THEN event and **all** projections arise in the same transaction. | State |
| I-07-02 | IF a durable event arises, THEN **both** projection layers must know it. **Convention, not machine-enforced** – the two registrations are independent; a test over the event manifest is needed. | State |
| I-07-03 | IF an event cannot be projected, THEN the transaction is discarded – no silent loss. | State |
| I-07-04 | IF a stored event is decoded, THEN the payload **always** runs through the same upgrade function. | State |
| I-07-05 | IF an unknown event type comes from storage, THEN an error is raised – **no** silent discards. | State |
| I-07-06 | IF an event is appended, THEN the sequence per aggregate increases by exactly one. | Log |
| I-07-07 | IF a log is subscribed to with a cursor, THEN subscription happens **before** replay and a synchronization marker is sent afterwards. | Flow |
| I-07-08 | IF a session is moved, THEN the event reaches **both** locations unchanged; location translation happens **only after** commit. | Flow |
| I-07-09 | IF a live connection exceeds its budget, THEN **only that** connection dies; publishing and other connections continue. | Flow |
| I-07-10 | IF a public event cannot be encoded, THEN the current connections are ended and the event skipped; the channel stays usable. | Flow |
| I-07-11 | IF internal events, connection acknowledgements or heartbeats occur, THEN they consume **no** connection budget. | Flow |

## I-08 Instructions

| # | Rule | Check type |
|---|---|---|
| I-08-01 | IF an instruction source changes, THEN a hash is stored per changed key; missing sources are marked "removed". | Log |
| I-08-02 | IF instruction values are stored, THEN they stay privileged: visible is **which** key changed, not the content. | State |
| I-08-03 | IF two sources carry the same key, THEN that is an error. | Flow |
| I-08-04 | IF a source renders no text, THEN that is an error – no silent skipping. | Flow |
| I-08-05 | IF an instruction source is unavailable, THEN that blocks **only** the initial delta; the stored value remains. | State |
| I-08-06 | IF a file is inserted as an instruction after a successful read, THEN it happens as a **durable** event, not via a redirection that creates a layer cycle. | Log |
| I-08-07 | IF an identical value is rendered, THEN the bytes are identical (same hash). | State |
| I-08-08 | IF a compaction ends, THEN the epoch start moves to its exact sequence. | State |
| I-08-09 | IF a session is moved, THEN the instruction state is retained, so target instructions appear as an update. | State |
| I-08-10 | IF a revert is committed, THEN the instruction state is deleted **only if** the revert removed an instruction update or the epoch start; otherwise the baseline remains (prompt cache stays valid). | State |

## I-09 Compaction

| # | Rule | Check type |
|---|---|---|
| I-09-01 | IF compaction happens, THEN the **full** transcript is retained durably. | State |
| I-09-02 | IF a provider-side compaction window is present, THEN the compaction request **aims** below the limit. The provider window itself is opaque and is **not** counter-checked. | Effect |
| I-09-03 | IF a provider-side window comes from a different model, THEN it is not replayed. | State |
| I-09-04 | IF a compaction fails, THEN the previous basis remains untouched (no partial summary) **and the run ends with an error** – it is not continued with a damaged basis. | State |
| I-09-05 | IF a process dies, THEN orphaned running compactions are marked failed. | State |

## I-10 Errors and recovery

| # | Rule | Check type |
|---|---|---|
| I-10-01 | IF a process starts, THEN sessions with an open claim are continued, at most 10 attempts per attempt. | Flow + State |
| I-10-02 | IF orphaned tool calls exist at start, THEN they are failed **before** further model work starts. | Log |
| I-10-03 | IF recovery claims **exactly-once**, THEN that is forbidden – recovery is at-least-once. | Flow |
| I-10-04 | IF a tool throws a foreign failure, THEN the call is **not** left untouched. | Effect |
| I-10-05 | IF a provider error occurs, THEN it is classified, not split into raw text. | State |
| I-10-06 | IF a content filter triggers, THEN the step fails; the partially streamed content stays visible. | State |
| I-10-07 | IF a transport error occurs with accepted delivery and send error, THEN a **single** full-context rebuild may occur. | Flow |
| I-10-08 | IF an error happens, THEN **exactly one** terminal event is produced (or none, if the operation ends without a terminal). | Log |

## I-11 System boundaries

These rules describe what the system **does not** do. They are just as
binding as the others.

| # | Rule |
|---|---|
| I-11-01 | The system **does not sandbox the agent**. The permission check is a usability feature, not a security boundary. |
| I-11-02 | There is **no** permission audit log. |
| I-11-03 | History is replayable, **side effects** are not. |
| I-11-04 | Execution is **process-local**. Clustering is not part of this specification. |
| I-11-05 | A step is a model call. There is **no** automatic model rotation on errors – exceptions are title and helper models. |
| I-11-06 | Instructions are **value differences**. There is **no** instruction registry. |
| I-11-07 | File changes are **not** logged as byte content. |
| I-11-08 | Undo lies **outside** the database. |
| I-11-09 | A reject **without** a reason ends the step. |
| I-11-10 | Events that nobody reads are not produced – the log is not a diary. |

## I-12 Acceptance criteria

A minimum set of checks for an implementation to count as conformant:

| # | Check | Kind |
|---|---|---|
| A-01 | Admission produces exactly one inbox row **before** execution; delivery produces exactly one visible message. | Integration test |
| A-02 | Re-admission of the same ID changes nothing; a conflicting ID fails. | Integration test |
| A-03 | A step produces exactly one provider call (countable via a stub provider). | Integration test |
| A-04 | After a tool result a **new** provider call follows (no loop in memory). | Integration test |
| A-05 | The tool call is logged before the side effect (order in the log). | Integration test |
| A-06 | Deny beats a stored approval; "ask" beats "allow". | Unit test |
| A-07 | Rejection without a reason produces **no** model-side tool text. | Integration test |
| A-08 | Rejection with a reason produces model-side text and the model can continue. | Integration test |
| A-09 | A defective event discards the transaction completely. | Integration test |
| A-10 | A crash with an open claim leads to continuation on restart, with counter. | Integration test |
| A-11 | A duplicate terminal event for a call ID is recognized as a defect. | Unit test |
| A-12 | The full transcript survives a compaction. | Integration test |