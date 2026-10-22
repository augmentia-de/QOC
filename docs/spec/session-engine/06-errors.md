# 06 – Errors, defects and recovery

> Three channels, cleanly separated. The separation is the most important
> error-handling decision of the whole system.

## 1. Three channels

| Channel | Meaning | Example | Propagate? |
|---|---|---|---|
| **Error** | expected, classified failure | provider reports rate limit | yes, until someone handles it |
| **Abort** | deliberate stop | user cancels, server shuts down | yes, as its own channel |
| **Defect** | violation of an invariant | provider protocol violation, duplicate terminal event | **no** – it is a bug |

**Why the separation matters:**

* Errors may become **model-visible** ("the file does not exist").
* Rejections by the user must **not** become model-visible – otherwise a
  "no" turns into a politely phrased error message and the model tries
  again.
* Defects must **never** be packaged as errors – otherwise a bug becomes a
  user problem.

> **Rule:** Converting a rejection channel into an ordinary error is
> **forbidden**, unless the user gave a reason.

## 2. Error taxonomy

```
type SessionError =
  { type: Text, message: Text, status: Option<Int>,
    response: Option<{ body: Text }>, retryAfterMs: Option<Duration> }
```

| `type` | Origin | Meaning for the user |
|---|---|---|
| `anbieter.ratenbegrenzung` | rate limit | "too many requests, retry shortly" |
| `anbieter.auth` | authentication | check key/OAuth |
| `anbieter.kontingent` | quota exhausted | check plan/quota |
| `anbieter.freikontingent` | free quota exhausted | "quota reached" |
| `anbieter.inhaltsfilter` | content policy | rephrase request |
| `anbieter.transport` | network | retry |
| `anbieter.intern` | provider error | retry |
| `anbieter.un Gueltige-ausgabe` | provider returned unusable | retry |
| `anbieter.ungueltige-anfrage` | request rejected | check input |
| `anbieter.nicht-unterstuetzt` | operation missing | switch model/plan |
| `anbieter.keine-route` | no route to model | check model/route |
| `anbieter.unbekannt` | unknown provider | check configuration |
| `anbieter.zeitueberschreitung` | timeout | retry |
| `rechte.verweigert` | permission check negative | check rules |
| `werkzeug.ausfuehrung` | tool error | usually fixable by the model |
| `werkzeug.unbekannt` | tool name unknown | check model prompt |
| `verdichtung.fehlgeschlagen` | compaction impossible | check context |
| `verdichtung.unterbrochen` | compaction aborted | – |
| `ausgabe.limit` | output limit exhausted | split task |
| `abgebrochen` | abort | – |
| `unbekannt` | everything else | – |

> `type` is an **open** string. New classes may arise without changing a
> central enumeration. The table is a **convention**, not a closed enum.

## 3. Errors per layer

| Layer | Error | Handling | Visible? |
|---|---|---|---|
| **Transport** | invalid request | schema check before logic | HTTP error |
| **Admission** | session unknown | error | HTTP 404 |
| **Admission** | ID conflict | error, **nothing admitted** | HTTP 409 |
| **Admission** | attachment too large/invalid | error, **nothing admitted** | HTTP 400 |
| **Execution** | model not chosen/available | error → terminal event | in history |
| **Execution** | agent not found | error → terminal event | in history |
| **Context** | instruction basis blocked | input stays **pending**, no model call | no error visible |
| **Model call** | provider error | retry or compaction or terminal | in history |
| **Model call** | content filter | step fails, partial result stays visible | in history |
| **Model call** | context overflow | one compaction, then retry | transparent |
| **Model call** | incomplete stream | retry (before output) / continuation (after output) | transparent |
| **Tool** | invalid input | error to the model, with hint to correct | in tool result |
| **Tool** | invalid output | error to the model | in tool result |
| **Tool** | output without schema | **defect** | – |
| **Tool** | foreign error from an extension module | normalized to a tool error at the boundary | in tool result |
| **Permission** | denied | tool error to the model | in tool result |
| **Permission** | rejected without reason | **abort**, no tool result, step ends | only in history |
| **Permission** | rejected with reason | tool error, model may continue | in tool result |
| **Permission** | question unanswered (location closed) | **abort** | in history |
| **Projection** | event not projectable | transaction discarded, error | – |

### The rule "error to the model"

A tool error becomes **model-visible** – as text in the
tool result. This is the difference between:

* `File not found: src/db.ts` → the model corrects the path and
  tries again. **Good.**
* `The user rejected the operation` → the model tries
  **further**. **Bad.** That is why the rejection is not made model-visible.

## 4. Defects (protocol violations)

Defects arise when a component violates an invariant. The
rule: **immediately, loudly and locally**.

| Violation | Detection | Consequence |
|---|---|---|
| Tool call without a preceding call event | when publishing the result | abort of the step |
| Name of a tool call changes mid-stream | when publishing | abort |
| Duplicate tool call with the same ID | when publishing | abort |
| Duplicate terminal event for a call ID | when publishing | abort |
| Tool result without content | when publishing | abort |
| Result without a preceding call event | when publishing | abort |
| Sequence gap in the log | when appending | transaction discarded |
| Event with wrong aggregate | when appending | transaction discarded |
| Unknown event shape on read | when decoding | error, no silent drop |
| Message row not decodable | when reading | error |

**Rules:**

* **D-01** A defect ends the **current** operation. It is not "repaired"
  and not converted into an error.
* **D-02** A defect is **logged** (log, not event log – the log is for
  domain facts).
* **D-03** No silent drop. "We ignored it" is always worse than a crash
  with a clear cause.

> **Why strict?** Errors treated as "known and ignored" are the most
> common cause of hard-to-find data loss. A crash with a clear message is
> cheaper.

## 5. Recovery

### 5.1 After process abort

```
Key point: the execution claim is the only durable trace.
```

| Situation | Claim | Behavior on next start |
|---|---|---|
| Normal completion | released | nothing |
| Error | released | nothing |
| Abort by user | released | nothing |
| Abort on shutdown | **stays** | session is continued |
| Process dies (crash, SIGKILL) | **stays** | session is continued |
| Restart during continuation | **stays** | counter rises, attempt budget applies |

**Resume budget:**

* The counter is **durable** and is **incremented before** continuing.
* The budget is **per attempt**, not per session: at most 10 attempts per attempt.
* An attempt that exceeds the budget is **terminalized** (error), and the
  claim is released.
* Only a terminal event resets the counter.

**Order on start:**

1. Report running background work (shell results, subagent results)
   **before** a child session starts.
2. Then continue sessions with an open claim.
3. Before the first model request of a continued session: mark orphaned
   tool calls as failed; mark orphaned compactions as failed.

**Guarantee:** **at-least-once.** Local coordination prevents concurrent
runs, but not repeated side effects after a crash. Anyone who needs
"exactly once" must make tools idempotent or keep a separate effect log.

### 5.2 After network/provider error

| Error | Reaction |
|---|---|
| Rate limit | wait (up to 15 min), retry |
| Provider internal | wait, retry |
| Transport, unsent | retry |
| Transport, accepted, read error only | retry (output may be partially visible) |
| Transport, accepted, send error | **do not** retry → rebuild full context (once) |
| Incomplete stream | retry before output, continue after output |
| Context overflow | compact once, then retry |
| Content filter | **do not** retry |
| Authentication | **do not** retry |
| Timeout | retry, but at most 3 times |

## 6. What is **not** logged

| Event | Reason |
|---|---|
| Permission request and reply | live-only; there is **no** permission audit log |
| Text/reasoning progress | live-only, not replayable |
| Tool progress | live-only |
| **Content of a file change** | tool call is logged, the bytes are not |
| **Summary of compaction content in the prompt cache** | separate, machine-local |
| Snapshot history | lives in its own version-control repository, **not** in the database |

**Consequence:** A database backup contains **no** undo history. And a
log replay shows *that* a file was written, but not *what*.

## 7. Error handling – ground rules

| # | Rule |
|---|---|
| F-01 | Errors are **classified**, not split into text. |
| F-02 | Errors the model can fix go **model-side**. |
| F-03 | User rejections do **not** go model-side. |
| F-04 | Interruption is **not** an error. |
| F-05 | Defects are **not** turned into errors. |
| F-06 | Every error produces **exactly one** terminal event (or none). |
| F-07 | Retries are **bounded** and **observable** (retry event). |
| F-08 | A retry after already produced output keeps the output and issues **one** continuation instruction. |
| F-09 | The resume counter is **durable** and is **incremented before** the run. |
| F-10 | Errors while projecting lead to **discarding** the transaction. |<|end|><|start|>write to=assistant<|channel|>analysis<|message|>Wrote file successfully: spec/session-engine/06-errors.md