# 05 – Invariants

> The testable rules of the provider layer, numbered `V-01…`.
> Check types: **State** · **Flow** · **Effect** · **Log**.

## V-01 Responsibility boundary

| # | Rule | Check type |
|---|---|---|
| **V-01** | The provider knows **exactly one** model call. It knows no sessions, tools, permissions or compaction. | State |
| **V-02** | The adapter only translates: request ↔ wire format, raw error ↔ error class. It decides **nothing** about retry. | State |
| **V-03** | The provider executes **no** tools. It reports them. | Effect |
| **V-04** | The provider writes **no** session state. It delivers events. | Effect |

## V-02 Request

| # | Rule | Check type |
|---|---|---|
| **V-10** | WHEN a medium has an unsupported modality, THEN it is **removed** before the request becomes durable. | State |
| **V-11** | WHEN a medium is removed, THEN a **hint** appears in the text. | State |
| **V-12** | WHEN a provider rejects an image type, THEN that type is removed **when inserted**. | State |
| **V-13** | WHEN a model is chosen and not in the catalog, THEN a **configuration error** arises – no fallback. | State |
| **V-14** | `providerOptions` is passed through **unchanged**. | State |
| **V-15** | The request contains **no** session ID as a structural field – only as a header. | State |

## V-03 Stream

| # | Rule | Check type |
|---|---|---|
| **V-20** | The stream is read **in full**, not only up to `Finished`. | Flow |
| **V-21** | Every block is begun **exactly once** and ended **exactly once**. | Log |
| **V-22** | A delta without a begun block is a **defect**. | Log |
| **V-23** | Every tool call is reported **before** its result. | Log |
| **V-24** | There is **exactly one** result per tool call. | Log |
| **V-25** | A late **error** result after abort cleanup is harmless; a late **success** result is a **defect**. | Log |
| **V-26** | A tool call executed by the provider itself is **not** executed again. | Effect |
| **V-27** | `Finished(Unknown)` is treated as an **error** (incomplete stream). | State |
| **V-28** | An abort of the stream is **never** converted into an error. | State |
| **V-29** | An abort also terminates the running tool executions. | Effect |

## V-04 Errors

| # | Rule | Check type |
|---|---|---|
| **V-30** | Errors are **classified**, not derived from the message text. | State |
| **V-31** | `kind` is closed; `message` is free and **never** the basis for decisions. | State |
| **V-32** | `raw` carries the provider reason and is **not** evaluated. | State |
| **V-33** | A provider error is reported as an **event**, not as a stream abort. | Flow |
| **V-34** | `Finished(ContentFilter)` becomes `ContentPolicy` and is **not** retried. | State |
| **V-35** | `Transport` distinguishes `Unsent` / `Accepted` / `Rejected` **and** `Send` / `Read`. | State |
| **V-36** | `Accepted` + `Send` is **not** retried. | Flow |

## V-05 Retry

| # | Rule | Check type |
|---|---|---|
| **V-40** | WHEN `kind` is not retryable, THEN it is **not** retried. | Flow |
| **V-41** | WHEN `ContextOverflow` occurs, THEN compaction happens **at most once** per step. | Flow |
| **V-42** | WHEN `ContextOverflow` occurs again after compaction, THEN the error is **final**. | Flow |
| **V-43** | The provider's `retryAfter` takes **precedence** over own backoff. | Flow |
| **V-44** | Backoff is exponential **with** jitter. | Flow |
| **V-45** | A retry that exceeds the time budget is **not** started. | Flow |
| **V-46** | The number of attempts is **bounded** and configurable. | State |
| **V-47** | A retry begins the **same** step, not a new one. | Flow |

## V-06 Budget and cache

| # | Rule | Check type |
|---|---|---|
| **V-50** | The output budget is `min(desired, max(1 024, free space))`. | State |
| **V-51** | The estimate adds **15 %** and is rounded up. | State |
| **V-52** | The budget never drops below **1 024** tokens. | State |
| **V-53** | The compaction output is capped at **32 000**. | State |
| **V-54** | Cache affinity for child and fork is that of the **parent/source**. | State |
| **V-55** | The affinity value is **session-stable** and does not change per step. | State |
| **V-56** | The provider-specific cache key is only reshaped when the **form matches**. | State |

## V-07 Catalog

| # | Rule | Check type |
|---|---|---|
| **V-60** | A catalog transformation replaces the catalog **as a whole**. | State |
| **V-61** | Organization rules take effect **last**. | Flow |
| **V-62** | A blocked provider does **not** appear in the catalog. | State |
| **V-63** | A model not in the catalog produces an **error**, no fallback. | State |
| **V-64** | An unknown model produces **no** model request. | Effect |

## Acceptance criteria

| # | Check | Kind |
|---|---|---|
| A-01 | A provider that names `too many tokens` in the text but classifies as `InvalidRequest/Other` is **not** compacted. | Unit test |
| A-02 | `ContextOverflow` compacts exactly once; the second is final. | Integration test |
| A-03 | `Accepted` + `Send` produces **no** second model round. | Integration test |
| A-04 | `ContentFilter` produces a visible hint and no retry. | Integration test |
| A-05 | A delta without a begun block ends the call as a defect. | Unit test |
| A-06 | A late success result on the same call is a defect. | Unit test |
| A-07 | A subagent carries the parent's affinity. | Unit test |
| A-08 | A GIF to an xAI provider is removed when inserted. | Unit test |
| A-09 | The budget never drops below 1 024 tokens, even with a full window. | Unit test |
| A-10 | A provider with Policy-`deny` does not appear in the catalog. | Integration test |
| A-11 | The stream is read to the end after `Finished`. | Unit test |
| A-12 | An abort produces **no** error event but an abort event. | Integration test |
