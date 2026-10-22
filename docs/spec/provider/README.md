# Provider – specification

> **Subject:** The interface between the session engine and a language
> model: request, event stream, error classification, retry, catalog,
> budget, cache.
>
> **Status:** 2026-10-22 · Reference checkout `dev` @ `3108505b2f`

## Reading order

| File | Content |
|---|---|
| [`01-types.md`](./01-types.md) | `ModelRequest`, `ModelEvent`, `ProviderFailure`, `ModelInfo` |
| [`02-event-stream.md`](./02-event-stream.md) | The stream, its state machine, the protocol rules |
| [`03-classification.md`](./03-classification.md) | Error classes, retryability, overflow, content filter |
| [`04-catalog-response.md`](./04-catalog-response.md) | Catalog, variants, capabilities, media, budget, cache |
| [`05-invariants.md`](./05-invariants.md) | `V-01…` – the testable rules |
| [`06-java.md`](./06-java.md) | Java 21 implementation |

## The core in three sentences

1. **The provider knows exactly one model call.** It knows nothing of
   sessions, tools, permissions, history or orchestration.
2. **A call delivers a stream of events**, which is read **in full** –
   not only up to completion.
3. **Errors are classified, not decomposed into text.** The retryability
   decision is **never** based on the message text.

## What the provider knows – and what not

| The provider knows | The provider does **not** know |
|---|---|
| The model name, routing, credentials | Sessions, messages, history |
| The messages of **this** call | Tools (except as definitions) |
| Tool definitions (as translation) | Tool execution |
| The output budget | Permissions, approvals, policies |
| The stream events | Compaction, retry, resumption |
| The error reason (classified) | Cost, token accumulation, statistics |

> **The adapter only translates.** It translates request ↔ wire format
> and raw error text ↔ error class. It makes **no** decision about
> retry, compaction or permissions. Otherwise the retry logic would be
> bound to every provider.

## The responsibility boundary in a picture

```
┌─────────────── Session engine ───────────────┐   ┌──── Provider ────┐
│                                                │   │                  │
│  Build context                                 │   │  Model           │
│    → agent, instructions, history, tools      │──►│                  │
│                                                │   │  Catalog         │
│  Calculate budget                              │──►│  Credentials     │
│                                                │   │                  │
│  Read stream                                   │◄──│  Event stream    │
│  Classify errors                               │◄──│  Error class     │
│  Retry / compact / abort                       │   │                  │
└────────────────────────────────────────────────┘   └──────────────────┘
```

## The three hard spots

| # | Spot | Why it is hard |
|---|---|---|
| 1 | **The stream must be read in full** | Whoever reads only up to `finish` loses the ability to reuse the response for the next request – and cannot detect the “unknown finish". |
| 2 | **Error classification without text patterns** | “too many tokens" is **not** a context overflow. “rate limit" is **not** a context overflow. Anyone searching by text compacts wrongly or retries wrongly. |
| 3 | **The context overflow must compact exactly once** | The first overflow may compact, the second is terminal. Without this counting, infinite loops arise. |