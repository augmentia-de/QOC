# 02 – Events, log and projections

> Core rule: **State is a projection of the log.** Everything that must be
> answered is derived from the event log – never from mutable side state.

## 1. The log

```
type LogEntry =
  { id: EventID
  , aggregateID: SessionID
  , seq: Seq                # monotone, gapless per aggregate
  , type: EventType
  , data: Json              # encoded payload
  , created: Timestamp }
```

Properties:

* **Append only.** No update, no delete (exception: administrative
  deletion of the whole session).
* **Sequence per aggregate.** Two sessions do not share a sequence.
* **Gapless.** `seq` increases by exactly 1. A gap is a defect.
* **Immutable.** An entry is never overwritten.

## 2. `publish` – the only write path

```
publish(e: Event) =
  atomisch {
    seq ← next sequence for the aggregate
    kodiert ← kodiere(e)
    for jede Projektion p, die auf e reagiert:
        p(kodiert, seq)          # dieselbe Transaktion
    commit(seq)                  # optionale Nebenwirkung, z. B. Claim setzen
    schreibe Protokolleintrag
  }
```

Guarantees:

1. **Atomicity.** Event and all projections arise together or not at all.
   There is no intermediate state in which the event exists but the
   derived table is missing.
2. **Order.** Projections see events in sequence order.
3. **The `commit` hook** allows a conditional side effect in the same
   transaction (e.g. "set the execution claim"). If it fails, the event
   fails with it.
4. **No silent loss.** If a projection fails, the transaction is
   discarded and the error propagates. An event that cannot be projected
   is an error, not a hint.

> **Consequence for the implementation:** Every projection must be
> *idempotent with respect to the sequence* and must not trigger any
> external side effect inside the transaction. Side effects belong **before**
> or **after** the transaction, never inside it.

## 3. Two projection layers

There are **two** separate projections, and both must know every new durable
event:

```
Layer A — Data state
    Event → domain rows (session, messages, inbox, instructions, permissions)
    Purpose: answer machine-readable questions (cost, open inputs, context)

Layer B — Message folding
    Event → ConversationView (messages, tool states, errors, retries)
    Purpose: present the session as the user experienced it
```

**Why two?** Layer A is optimizable, indexable, queryable. Layer B
must preserve the *order and nesting* of content (text block,
reasoning block, tool card). A single table would have to combine both
and would lose either in queries or in presentation.

> **Rule for changes:** Whoever adds a durable event must extend both
> layers. Otherwise the data state is correct and the conversation is
> incomplete – an error that only surfaces at the user.

## 4. Projection catalog

The following table is the complete core catalog. "State change"
describes what the projection does.

### Session

| Event | State change (Layer A) | State change (Layer B) |
|---|---|---|
| `SessionCreated` | Create session row | — |
| `SessionRenamed` | Set title | — |
| `SessionArchived` / `SessionUnarchived` | Set/remove archive time | — |
| `SessionMetadataUpdated` | Set metadata | — |
| `SessionPermissions` | Replace rule set | — |
| `SessionAgentSelected` | Set agent | Message `AgentSwitched` |
| `SessionModelSelected` | Set model | Message `ModelSwitched` |
| `SessionViewed` | Advance timestamp (monotone) | — |
| `SessionMoved` | Update directory/project | Message `LocationSwitched` |
| `SessionForked` | New session + message copies up to boundary | — |
| `SessionDeleted` | Delete session and dependent rows | — |

### Inbox

| Event | State change |
|---|---|
| `InboxEnqueued` | Create **one** inbox row (key = ID, conflict ⇒ error) |
| `InboxDelivered` | Delete inbox row **and** insert visible message – in the same transaction |
| `InboxCancelled` | Delete inbox row |
| `InboxDeliveryChanged` | Switch delivery mode (only if the old mode matches) |

**Core rule:** `InboxDelivered` turns a pending input into a
visible message. Only after that is the input part of the model history.

### Instructions

| Event | State change |
|---|---|
| `InstructionsUpdated` | Per changed source a delta (`Hash`, or "removed") + state advance |

The text content itself is stored **once** in a blob store and
referenced only by its hash. The delta entry never contains the
privileged content.

### Execution

| Event | State change |
|---|---|
| `ExecutionStarted` | Set claim (`suspended`), advance timestamp |
| `ExecutionSucceeded` | `idle` + result `Succeeded`, release claim, reset resume counter |
| `ExecutionFailed` | `idle` + result `Failed` + error, release claim, reset counter |
| `ExecutionInterrupted` | `idle` + result `Interrupted`; release claim **only** for `User`; for `Shutdown` it stays |

> **The `Shutdown` case is intentional:** A shutdown interrupt is meant to
> continue the work on the next start, not discard it. The claim is the only
> durable signal for that.

### Step and content

| Event | State change (Layer B, assistant message) |
|---|---|
| `StepStarted` | Create assistant message (or reset) |
| `StepStreamed` | Set stream timestamp |
| `StepEnded` | Completion, finish reason, cost, tokens, snapshot range |
| `StepFailed` | Completion with error, cost/tokens if present |
| `TextStarted` / `TextEnded` | Append text block / fill it |
| `ReasoningStarted` / `ReasoningEnded` | Append reasoning block / fill it |
| `ToolInputStarted` / `ToolInputEnded` | Create tool card (state `Streaming`) / fill input |
| `ToolCalled` | State → `Running`, set execution time |
| `ToolSuccess` | State → `Completed`, set content + metadata |
| `ToolFailed` | State → `Failed`, set error |

> **A tool call has exactly one terminal event.** `ToolSuccess` and
> `ToolFailed` are mutually exclusive for the same call ID. A second
> terminal event is a defect.

### Compaction

| Event | State change |
|---|---|
| `CompactionStarted` | Message `Compaction` with status `Running` |
| `CompactionEnded` | Status `Completed`, summary, advance epoch |
| `CompactionFailed` | Status `Failed`, error |

### Other

| Event | State change |
|---|---|
| `SyntheticRaised` | Message `Synthetic` |
| `SkillActivated` | Message `Skill` |
| `ShellStarted` / `ShellEnded` | Message `Shell` with status |
| `RevertStaged` / `RevertCleared` / `RevertCommitted` | Revert state; on commit delete instruction state |
| `RetryScheduled` | Set retry state on the assistant message |
| `UsageRecorded` | Cost/tokens into the session row |

## 5. Visibility

The central dividing line of the whole system:

```
Input in the inbox container   →  NOT visible to the model
Delivery (InboxDelivered)      →  part of model history, visible
```

Everything the model sees in a step comes from **Layer B** (plus
the instruction block). A pending inbox entry does not appear there.

## 6. Replay

```
Replay(aggregate, abSeq) → sequence of events
```

* Events are read **after** the exclusive sequence boundary.
* A live subscription is set up **before** the replay, so no event is
  lost between replay and subscription; afterwards a synchronization
  marker is sent.
* Old event shapes are lifted to the current format at **one** place.
  Unknown shapes are an **error**, not a silent drop.

> **Replay semantics:** The history is replayable, the *side effects* are
> not. A replay shows that a tool ran – not which bytes it wrote. Anyone
> who needs "exactly once" needs a different mechanism (idempotent tools
> or an effect log).

## 7. Live distribution

The live channel distributes events to multiple connections:

```
publish → encode (once) → send to all queues
```

Rules:

1. **Encode once.** Encoding happens per event, not per connection.
2. **Each connection has its own finite budget.** Exceeding it ends
   **only that** connection; publishing and other connections continue.
3. **Heartbeats and internal events consume no budget.**
4. **Encoding errors** end the current connections with an encoding
   error; the faulty event is skipped, the channel stays usable for
   later connections.

> Point 2 is the actual design decision: **slow connections are punished
> in isolation, not the sender.**

## 8. Rules in brief

| # | Rule |
|---|---|
| E-01 | Every state change is a durable event. |
| E-02 | Event + all projections arise in one transaction. |
| E-03 | The sequence per aggregate is gapless and monotone. |
| E-04 | A durable event needs **both** projection layers. |
| E-05 | A projection must not trigger an external side effect inside the transaction. |
| E-06 | A projection error discards the transaction; nothing is written half. |
| E-07 | Inputs are visible only after delivery. |
| E-08 | Unknown event shapes are an error, not a silent drop. |
| E-09 | Live encoding happens once per event; each connection has its own budget. |
| E-10 | Terminal events are exclusive (one success **or** one failure). |
