# 01 – Types

## 1. Request

```
type ModelRequest =
  { model: ModelRef
  , system: List<Text>
  , messages: List<ModelMessage>
  , tools: List<ToolDefinition>
  , toolChoice: Option<Choice>        # free | none | one | required
  , generation: GenerationOptions     # provider-neutral options
  , providerOptions: Map              # provider-specific, passed through unchanged
  , headers: Map<Text, Text>          # session/project IDs, affinity
  , cacheKey: Option<Text> }

type ModelMessage =
  | User      { content: List<Content> }
  | Assistant { content: List<Content> }
  | ToolResult{ callID: CallID, name: ToolName, outcome: OutcomePayload }

type Content =
  | Text  { text: Text }
  | Media { modality: Image | Audio | Video | PDF, data: Bytes, name: Option<Text> }
  | ToolCall { callID: CallID, name: ToolName, input: Json }

type Choice = Free | None | One { name: ToolName } | Required
```

> **`providerOptions` is an open bag.** It is passed through **unchanged**.
> This is the only place where provider-specific options enter – without
> the engine needing to know them.

## 2. Event stream

```
type ModelEvent =
  | TextStarted        { blockID }
  | TextDelta          { blockID, text }
  | TextEnded          { blockID, text }

  | ReasoningStarted   { blockID }
  | ReasoningDelta     { blockID, text }
  | ReasoningEnded     { blockID, text }

  | ToolInputStarted   { callID, name }
  | ToolInputDelta    { callID, text }
  | ToolInputEnded     { callID, text }

  | ToolCallRequested  { callID, name, input: Json }
  | ToolResultProvided { callID, outcome: OutcomePayload }   # executed by the provider
  | ToolFailed         { callID, message }

  | Finished           { reason: FinishReason, usage: TokenCount, raw: Json }
  | ProviderFailed     { kind: FailureKind, message: Text, status: Option<Int>
                       , retryAfter: Option<Duration> }

type FinishReason = Normal | LengthLimit | ContentFilter | Unknown | Aborted
```

### Meaning of the fields

| Field | Meaning |
|---|---|
| `blockID` | identifies a text/reasoning block **within** the response |
| `callID` | identifies a tool call **within** the call |
| `reason` | **normalized** – the raw provider reason is in `raw` |
| `usage` | token count, **if** the provider reports it |
| `kind` | the **error class** – never the message text |

> **`FinishReason` is normalized.** “max_tokens", “length", “MAX_TOKENS"
> and `STOP_SEQUENCE` are mapped to four values. The provider reason
> remains in `raw` – for diagnosis, not for decisions.

## 3. Errors

```
type FailureKind =
  | RateLimited      { retryAfter: Option<Duration> }
  | ProviderInternal { retryAfter: Option<Duration> }
  | Transport        { delivery: Unsent | Accepted | Rejected
                     , operation: Send | Read
                     , code: Text }
  | InvalidOutput    { classification: IncompleteStream | Unknown }
  | InvalidRequest   { classification: ContextOverflow | Other }
  | Authentication   { hint: Option<Text> }
  | QuotaExceeded    { hint: Option<Text> }
  | ContentPolicy
  | UnsupportedOperation { operation: Text }
  | NoRoute
  | UnknownProvider
  | Timeout
```

> **The classes are closed, the messages are not.** `kind` decides;
> `message` is only for humans. That is exactly why the retryability
> decision must **never** look at `message`.

### `Transport.delivery` – the underestimated field

| Value | Meaning | Retry? |
|---|---|---|
| `Unsent` | The request was **never** delivered | **yes** |
| `Accepted` | The provider **accepted** the request | only when `operation = Read` |
| `Rejected` | The request was **rejected** | **no** |

> **Why this matters:** an `Accepted` error while **sending** can mean a
> model round already ran. Re-sending then produces a **duplicate**
> round. An `Accepted` error while **reading** is harmless – only the
> response was not fully read.
>
> With `Accepted` + sending, the right way is not a retry but a
> **one-off full-context rebuild**.

## 4. Catalog

```
type ModelInfo =
  { id: ModelID
  , providerID: ProviderID
  , variants: List<VariantID>
  , limits: { context: Int, output: Int }
  , cost: CostModel
  , capabilities: { input: List<Modality>, output: List<Modality> }
  , compaction: Option<CompactionMode>     # provider-side compaction
  , transport: Option<ChannelMode> }      # long-lived channel

type CompactionMode = Native { encrypted: Bool }
type ChannelMode = WebSocket
```

> **`limits.context` is the window size, `limits.output` the output
> limit.** Both are needed for budget calculation. An unknown window is
> an **error case**, not a reason to guess.

## 5. Usage

```
type TokenCount =
  { input: Int, output: Int, reasoning: Int
  , cacheRead: Int, cacheWrite: Int }

type CostModel = { input: MoneyPerToken, output: MoneyPerToken
                 , cacheRead: MoneyPerToken, cacheWrite: MoneyPerToken }
```

> **Usage is processed, not computed by the provider.** The provider
> reports what it measured; the engine accumulates.

## 6. Type flow rules

| Rule | Why |
|---|---|
| `kind` is closed, `message` is text | classification without text patterns |
| `raw` carries the provider reason but is **never** evaluated | diagnosis instead of decision |
| `FinishReason` is normalized | one provider, five meanings |
| `usage` is optional | some providers report no usage |
| `blockID` and `callID` are **different** types | blocks and calls are different axes |
| `providerOptions` is a `Map` | provider-specific options without engine knowledge |
| `ModelRequest` contains **no** session ID as a field | it is in the headers – the engine knows no session in the provider |

## 7. Verified semantics (reference checkout)

| Concept | Location |
|---|---|
| Request, headers, cache key, budget | `packages/core/src/session/model-request.ts:236-404` |
| Event normalization (`FinishReason`) | `packages/core/src/session/runner/publish-llm-event.ts:569-583` |
| Error classes and retryability | `packages/ai/src/provider-error.ts:60-104` |
| Model resolution, variants, limits | `packages/core/src/session/runner/model.ts:70-96` |
| Output budget calculation | `packages/core/src/session/model-request.ts:87-97` |
| Usage and cost | `packages/core/src/session/usage.ts` |
