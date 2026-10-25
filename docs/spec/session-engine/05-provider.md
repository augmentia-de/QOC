# 05 – Provider interface

> The neutral interface between the Session Engine and a
> language model. Goal: **one** provider adapter, arbitrarily many models; and
> **no** provider details in the engine.
>
> Principle from the package boundary: *authentication, permissions, plugins and
> orchestration belong to the engine; the provider knows exactly one
> model call.*

## 1. Role distribution

```
        ┌──────────────────────── Engine ────────────────────────┐
        │  Tools · Permissions · History · Compaction · Events   │
        └───────────────────────────┬──────────────────────────────┘
                                    │  neutral provider protocol
        ┌───────────────────────────┴──────────────────────────────┐
        │  Provider adapter: translation ⇄ request/response        │
        └──────────────────────────────────────────────────────────┘
```

The adapter may **only** translate. It may not check permissions, produce
events, or reclassify errors.

## 2. Request

```
type ModelRequest =
  { model: ModelRef
  , system: List<Text>
  , messages: List<ModelMessage>
  , tools: List<ToolDefinition>
  , toolChoice: Option<Choice>        # free | none | one | required
  , generation: GenerationOptions     # provider-neutral options
  , providerOptions: Map              # provider-specific, passed through unchanged
  , headers: Map<Text,Text>           # e.g. session/project identifiers, affinity
  , cacheKey: Option<Text> }

type ModelMessage =
  | User       { content: List<Content> }
  | Assistant  { content: List<Content> }
  | ToolResult { callID: CallID, name: ToolName, result: OutcomePayload }

type Content =
  | Text  { text: Text }
  | Media { mediaType: Image | Audio | Video | PDF, data: Bytes, name: Option<Text> }
  | ToolCall { callID: CallID, name: ToolName, input: Json }
```

**Rules:**

* **P-01** The message list contains **only** delivered history – no
  waiting inputs.
* **P-02** The system part consists of at most two blocks: the
  provider-/agent-specific basis block and the instruction block.
  An agent with its own system block **replaces** the basis block entirely.
  Precedence (Agent → Provider → Basis) and texts:
  [`13-internal-prompts.md`](./13-internal-prompts.md) §3.1.
* **P-03** The output budget is computed by the engine and passed through; the
  provider may not raise it on its own.

### Output budget

```
budget = min( catalogLimit, 256_000 )
budget = min( budget, max( 1_024, contextWindow - measuredInputSize - buffer ) )
```

* For compaction assignments an additional smaller upper bound (32,000) applies.
* For title and helper tasks **no** budget is set – their reasoning effort is
  hard to plan.
* The input-size estimate has a safety margin; catalogs occasionally
  overestimate the window.

## 3. Event stream

A call returns a **stream** of events. The model can mix
text, reasoning and tool calls; a tool call can still be incomplete
(streaming of input).

```
type ProviderEvent =
  | TextStarted        { blockID }
  | TextProgress       { blockID, text }
  | TextEnded          { blockID, text }

  | ReasoningStarted   { blockID }
  | ReasoningProgress  { blockID, text }
  | ReasoningEnded     { blockID, text }

  | ToolInputStarted   { callID, name }
  | ToolInputProgress  { callID, text }
  | ToolInputEnded     { callID, text }

  | ToolCalled         { callID, name, input: Json }
  | ToolResult         { callID, result: OutcomePayload }   # executed by the provider
  | ToolError          { callID, message }

  | Finished           { reason: FinishReason, usage: TokenCount, raw: Json }

  | ProviderError      { message, classification: FailureClass }
```

| Field | Meaning |
|---|---|
| `blockID` | identifies a text/reasoning block within the response |
| `callID` | identifies a tool call **within** a step |
| `FinishReason` | `normal` · `lengthLimit` · `contentFilter` · `unknown` · `aborted` |
| `OutcomePayload` | either content or an error text – never both |
| `usage` | token count, if the provider reports it |

**Rules:**

* **V-01** The stream is **read completely**, not only up to the
  completion event. Reason: the response should remain reusable for the next request.
* **V-02** The order of events is significant. A block is begun,
  continued and ended – exactly once.
* **V-03** A tool call is reported **before** its result. A result
  without a preceding call is a protocol error.
* **V-04** Exactly **one** result per call ID. A second result is a
  protocol error (exception: a late error result after an abort cleanup is
  harmless and is ignored).
* **V-05** Provider-side errors are reported as an **event**
  (`ProviderError`), not by aborting the stream – so the engine can
  decide whether an attempt is retried.
* **V-06** An abort of the stream is an abort, not an error.

## 4. Tool definitions

```
type ToolDefinition =
  { name: Text
  , description: Text
  , inputSchema: JsonSchema
  , outputSchema: Option<JsonSchema> }
```

**Rules:**

* **W-01** The name is unique for the provider and contains no
  provider-specific characters.
* **W-02** The input schema is **standard JSON Schema** (no
  provider-specific extensions). Unsupported schema constructs are simplified
  without changing validation.
* **W-03** An optional output schema describes the **machine** value
  (for code mode). The model still sees the content.
* **W-04** Names with a namespace become `<namespace>_<tool>`.

## 5. Error classification

The provider reports errors in its **own**, provider-neutral taxonomy.
The engine classifies them; the adapter only translates.

```
type FailureClass =
  | RateLimited        { waitTime: Option<Duration> }
  | ProviderInternal   { waitTime: Option<Duration> }
  | Transport          { delivery: Unsent | Accepted | Rejected
                        , operation: Send | Read
                        , code: Text }
  | InvalidOutput      { classification: IncompleteStream | Unknown }
  | InvalidRequest     { classification: ContextOverflow | Other }
  | Authentication     { hint: Option<Text> }
  | Quota              { hint: Option<Text> }
  | ContentPolicyViolated
  | NotSupported       { operation: Text }
  | NoRoute
  | UnknownProvider
  | Timeout
```

### Retryability

```
retryable(error) =
    if the provider supplies an explicit value: use it
    depending on class:
        RateLimited, ProviderInternal           → yes
        Transport:
            delivery = Rejected             → no  (the request was rejected)
            delivery = Accepted             → yes, only if operation = Read
            delivery = Unsent              → yes
        InvalidOutput: IncompleteStream → yes
        UnknownProvider                    → yes
        else                               → no
```

> **Principle:** *Unknown* errors are retried. The classification records only
> **proven** evidence; transient errors arrive exactly in forms no classifier
> foresees.

### Context overflow

```
contextOverflow(error) =
    error.type = InvalidRequest and error.classification = ContextOverflow
```

**Important:** Text patterns like "too many tokens" are **not** a context
overflow – they mean an output limit, which compaction does not fix. Likewise
"rate limit", "service unavailable" and "throttling error".

### Retry effort limits

```
waitTime = min( providerDesiredWaitTime, at most 15 minutes )
waitTime = max( waitTime, ownBackoffStep )
ownBackoffStep = exponential from 2 s, capped at 10 s per gap, with jitter
maxRetries = 10
maxRetriesOnTimeout = 3
```

> **Deviation from the present specification** (`specs/v2/session.md`):
> four retries are named there, the code allows **ten**. This specification
> follows the **code** (ten) and marks the deviation so it can be decided.
> See [`08-porting.md`](./08-porting.md).

## 6. Transport requirements

Requirements on the **binding**, not on a specific protocol:

| # | Requirement |
|---|---|
| T-01 | **Streaming.** The call returns a stream; the engine reads it completely. |
| T-02 | **Abort.** An abort by the engine ends the request; the provider should close the connection. |
| T-03 | **Error classification at the edge.** The adapter recognizes error classes (429, 5xx, network errors, …) and reports them classified. |
| T-04 | **Wait-time directives** are respected and never exceeded. |
| T-05 | **Reusability.** Responses may be cached for the next request (provider side). |
| T-06 | **Optional state channel.** Some providers offer a long-lived channel per session; the engine may use it but must work without it. |
| T-07 | **Headers** (session, project, affinity, cache key) are passed through unchanged. |
| T-08 | **Keys/tokens** come from the engine's credential layer, never from the provider adapter. |

## 7. What the provider does **not** know

| The provider does not know | Why |
|---|---|
| Sessions, messages, history | it is stateless; the engine holds state |
| Permissions and approvals | authorization belongs to the engine |
| Plugins and extensions | the engine materializes tools |
| Tool execution | the provider **requests**, the engine **executes** |
| Compaction | the engine decides what fits in context |
| Cost and token accumulation | the engine projects them |

Exception: **provider-executed tools** (server-side tool execution
of the provider). The provider reports these as a result; the engine does not
execute them. The event then carries a "executed by the provider" marker,
because otherwise the tool-call state machine would see a call without a
`ToolCalled` event.

## 8. Provider catalog

```
type ProviderEntry =
  { id: ProviderID
  , models: List<ModelInfo> }

type ModelInfo =
  { id: ModelID
  , providerID: ProviderID
  , variants: List<VariantID>
  , limits: { context: Int, output: Int }
  , cost: CostModel
  , capabilities: { input: List<MediaType>, output: List<MediaType> }
  , compaction: Option<CompactionModel>   # provider-side
  , transport: Option<StateChannel> }
```

**Rules:**

* **K-01** The catalog is the source for limits and capabilities.
  **Capabilities are checked before sending**: a medium the model cannot
  read is replaced by an explanatory text before the request – not rejected
  by the provider first.
* **K-02** Images are shrunk so that a storage budget is met; older images
  are removed first so that the unchanged prefix (and thus the cache) stays
  stable.
* **K-03** Variants affect reasoning effort and cost. The default is the
  one chosen by the user; there is **no** automatic rotation on errors.
* **K-04** A model not in the catalog is unavailable – it is not "guessed".