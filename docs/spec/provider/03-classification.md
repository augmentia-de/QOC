# 03 – Error classification and retry

> Why the decision “retry or not" **never** comes from the message text,
> which classes exist and what each means.

## 1. Why no text search

```
WRONG:
  if (message.contains("token") || message.contains("context")) → compact
  if (message.contains("rate"))                                → retry

RIGHT:
  switch (kind) {
    case InvalidRequest(_, ContextOverflow) → compact
    case RateLimited                        → retry
  }
```

**Three concrete counterexamples:**

| Message | Looks like | Actually is |
|---|---|---|
| `invalid_request_error: too many tokens in tool schema` | context overflow | **request error** – compaction does not help |
| `rate limit: 0 tokens remaining` | context overflow | **rate limit** |
| `Your credit balance is too low` | rate limit | **quota** – retrying does not help |

> **The rule:** The adapter classifies **once** at the provider boundary.
> After that the engine works only with `kind`. Anyone who looks at the
> text again later has two classifications – and two truths.

## 2. The classes

| `kind` | Meaning | Typical cause | Retryable |
|---|---|---|---|
| `RateLimited` | rate limit | too many requests | **yes** (with `retryAfter`) |
| `ProviderInternal` | provider error | 500/502/503 | **yes** |
| `Transport` | transport | connection drop | **conditionally** (§6) |
| `InvalidOutput` / `IncompleteStream` | stream truncated | connection, provider error | **yes** |
| `InvalidOutput` / `Unknown` | stream nonsensical | protocol error | no |
| `InvalidRequest` / `ContextOverflow` | context too large | window exceeded | **yes, once** (§4) |
| `InvalidRequest` / `Other` | request wrong | schema, tool definition | no |
| `Authentication` | not authenticated | missing/expired key | no |
| `QuotaExceeded` | quota exhausted | billing | no |
| `ContentPolicy` | content filter | provider policy | no |
| `UnsupportedOperation` | not supported | e.g. images to a text model | no |
| `NoRoute` | no route to provider | configuration | no |
| `UnknownProvider` | provider unknown | configuration | no |
| `Timeout` | engine timeout | slow response | **yes** (§7) |

> **`InvalidOutput` vs. `InvalidRequest`:** The one is a broken
> **response**, the other a broken **request**. Confusing them means
> retrying in vain or compacting where there is nothing to compact.

## 3. The retryability matrix

```
retryable(kind) =
  RateLimited                       → true
  ProviderInternal                  → true
  Timeout                           → true
  InvalidOutput(IncompleteStream)   → true
  InvalidOutput(Unknown)            → false
  InvalidRequest(ContextOverflow)   → true, BUT only once (§4)
  InvalidRequest(Other)             → false
  Transport(Unsent, *)              → true
  Transport(Accepted, Read)         → true
  Transport(Accepted, Send)         → false  (§6)
  Transport(Rejected, *)            → false
  Authentication | QuotaExceeded    → false
  ContentPolicy | Unsupported       → false
  NoRoute | UnknownProvider         → false
```

## 4. Context overflow – exactly **one** compaction

```
State: overflowCompactions = 0

on InvalidRequest(ContextOverflow):
  if overflowCompactions == 0:
      overflowCompactions = 1
      compact
      retry the same step
  else:
      FINAL ERROR   ("context overflow after compaction")
```

> **The counter is per step, not per session.** A later step may compact
> once again. Otherwise the second compaction in the same session would
> be impossible – and long sessions would get stuck.
>
> **Without the counting an infinite loop arises:** compact → still too
> large → compact → … Each iteration costs money and delivers nothing.

**What compaction does **not** solve:**

| Case | Why compaction does not help |
|---|---|
| Context of a **single** tool result too large | the result itself is too large – bounding must kick in |
| System instruction + tool schemas too large | they are **not** compacted |
| `ContextOverflow` for another reason | then it is not a real overflow – the classification was wrong |

> **Compaction has a lower bound.** If the fixed part (system, tools)
> already fills the window, any further compaction is pointless – the
> error is then **final**, and the message should say so.

## 5. Content filter

```
Finished(reason = ContentFilter)
  → ProviderFailed(kind = ContentPolicy)
  → NOT retried
  → step ends with a visible hint
```

> **A content filter is not a crash.** The response was deliberately
> interrupted. Retrying would produce the same response – and at worst
> cost money again.
>
> **The part already streamed remains visible.** It is real, it was
> generated, and hiding it would be a lie to the user.

## 6. Transport – the difference easily overlooked

```
Transport(delivery, operation, code)

delivery  = Unsent    → the request never reached the provider
          | Accepted  → the provider ACCEPTED it
          | Rejected  → the provider REJECTED it

operation = Send      → while sending
          | Read      → while reading the response
```

| Combination | Meaning | Action |
|---|---|---|
| `Unsent` + `Send` | nothing happened | **retry** |
| `Accepted` + `Read` | round ran, response did not arrive | **retry** (but: the round is recomputed – cost!) |
| `Accepted` + `Send` | round may have run, response unclear | **no** retry → one-off full-context rebuild |
| `Rejected` + `Send` | provider rejected | **do not** retry |

> **The `Accepted` + `Send` case is the most dangerous.** A naive retry
> produces a **second** model round. That is why the only safe way is
> not to treat the response as a continuation but to rebuild the context
> – and to inform the user that the round was computed twice.

## 7. Timeout

```
An ENGINE timeout is retryable – but only if it happened BEFORE
output. After output the response may already be billed.
```

| Phase | Retryable |
|---|---|
| Connection setup | **yes** |
| Waiting for the first event | **yes** |
| In the middle of the stream | **yes**, but the round was computed |

> **Practically:** the timeout is set on the **whole** call, not per
> event. A provider that sends a token every 30 s is not “healthy" – it
> would need hours.

## 8. Retry policy

```
attempts = 0

retry(kind):
  if !retryable(kind):                       → give up
  if attempts >= MAX_ATTEMPTS:               → give up
  if kind == ContextOverflow:                → compaction path (§4)

  wait = retryAfter ?? backoff(attempts)
  if wait > budget.remaining:                → give up

  attempts += 1
  wait(wait)

backoff(n) = min(2^n seconds, 30 seconds)
```

| Question | Recommendation |
|---|---|
| `MAX_ATTEMPTS` | **4** (spec) – in the reference checkout the logic allows **10**; open decision, see [`../session-engine/11-java-portability.md`](../session-engine/11-java-portability.md) |
| Backoff | exponential **with** jitter |
| Provider `retryAfter` | takes **precedence** over own backoff |
| Budget | a retry that exceeds the time budget is **not** started |

> **Jitter is not optional.** Without spreading, all sessions retry at
> the same time – and produce exactly the rate limit they are waiting
> for.

## 9. What is **never** retried

| Case | Why not |
|---|---|
| Authentication | a new attempt with the same key fails the same way |
| Quota | ditto |
| Content filter | the same request produces the same interruption |
| Request error (except overflow) | the request does not get better |
| `NoRoute` / `UnknownProvider` | configuration error |
| Rejection by the user | it is a decision, not an error |

## 10. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Error classes and `isRetryable` | `packages/ai/src/provider-error.ts:60-104` |
| Transport-`delivery` × `operation` | `packages/ai/src/provider-error.ts:14-41` |
| Context-overflow classification | `packages/ai/src/provider-error.ts:71-76` |
| Compaction on overflow, one attempt | `packages/core/src/session/runner/step.ts:171-186` |
| Content filter → `providerFailed` | `packages/core/src/session/runner/publish-llm-event.ts:578-582` |
| Timeout handling | `packages/core/src/session/runner/step.ts:140-152` |
