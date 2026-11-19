# LangChain4j in QOC

> **Status:** 2026-10-31 · LangChain4j 1.19.3 (via Quarkus LangChain4j 1.13.3)
> **Merged from:** `l4j-use.md` (Integration Guide) + `guides/langchain4j.md` (replacement potential)

## Part 1: Integration Guide

# LangChain4j integration guide

> **Purpose:** How LangChain4j is used in the QOC Java implementation, where
> it lives, what it replaces, and what stays custom. Companion to
> `spec/provider/*`, `spec/session-engine/05-provider.md` and
> `spec/java-packages.md`.
>
> **Status:** 2026-10-22 · LangChain4j 1.19.3 (via Quarkus LangChain4j 1.13.3).

## 1. Module placement (pure-Java boundary)

| Module | LangChain4j? | Contents |
|---|---|---|
| `engine` (`core`, `runtime`, `http` contracts, `adapter.*`) | **No** – pure Java (JDK + JDBC + ArchUnit). | `core.contract.provider.ProviderAdapter` (interface), `ModelRequest`, `ModelEvent`, `ProviderFailure`, `HttpTransport` port. |
| `quarkus` | **Yes** – `quarkus-langchain4j-openai` (BOM + artifact). | `ProviderAdapter` implementation using `ChatModel`/`StreamingChatModel`; CDI wiring in `EngineConfig`. |

`engine` defines the contract; `quarkus` supplies the implementation. This is
the port principle from `spec/module-plan.md` §3. `engine` builds and tests
without Quarkus.

## 2. LangChain4j API surface used

### 2.1 Core interfaces

- `dev.langchain4j.model.chat.ChatModel`
  - `chat(ChatRequest)` → `ChatResponse` (synchronous)
  - `chat(String)` / `chat(List<ChatMessage>)` convenience
  - `defaultRequestParameters()`, `supportedCapabilities()`, `provider()`
- `dev.langchain4j.model.chat.StreamingChatModel`
  - `chat(ChatRequest, StreamingChatResponseHandler)` (streaming, callback-based)
  - `doChat(request, handler)`

### 2.2 Request / response

- `dev.langchain4j.model.chat.request.ChatRequest`
  - `messages()` – `List<ChatMessage>`
  - `parameters()` – `ChatRequestParameters` (model, temperature, topP, topK,
    frequencyPenalty, presencePenalty, maxOutputTokens, stopSequences)
  - `toolSpecifications()` – `List<ToolSpecification>`
  - `toolChoice()` – `ToolChoice`
  - `responseFormat()` – `ResponseFormat`
  - builder: `ChatRequest.builder()`
- `dev.langchain4j.model.chat.response.ChatResponse`
  - `aiMessage()` – `AiMessage` with `text()` and `toolExecutionRequests()`
  - `tokenUsage()` – `TokenUsage`
  - `finishReason()` – `FinishReason`
  - `metadata()` – provider-specific metadata

### 2.3 Messages

- `dev.langchain4j.data.message.SystemMessage`
- `dev.langchain4j.data.message.UserMessage`
- `dev.langchain4j.data.message.AiMessage` (text + toolExecutionRequests)
- `dev.langchain4j.data.message.ToolExecutionResultMessage` (id, toolName, content, isError)
- `dev.langchain4j.data.message.ChatMessage` (sealed interface)

### 2.4 Tool classes

- `dev.langchain4j.agent.tool.ToolSpecification`
  - `name()`, `description()`, `parameters()` (`JsonObjectSchema`),
    `metadata()`, `strict()`
  - builder: `ToolSpecification.builder()`, `toJson()`, `fromJson()`
- `dev.langchain4j.agent.tool.ToolExecutionRequest`
  - `id()`, `name()`, `arguments()` (JSON string)
- `dev.langchain4j.data.message.ToolExecutionResultMessage`
  - `id()`, `toolName()`, `contents()`, `isError()`

### 2.5 Streaming

- `dev.langchain4j.model.chat.response.StreamingChatResponseHandler`
  - partial text, tool execution requests, completion, error callbacks
- `dev.langchain4j.model.chat.response.StreamingHandle` – cancel handle

### 2.6 Capabilities

- `dev.langchain4j.model.chat.Capability` – e.g. `RESPONSE_FORMAT_JSON_SCHEMA`,
  `TOOL_CALLING`, etc. (`supportedCapabilities()` on the model).

## 3. Mapping: LangChain4j ↔ engine (adapter-internal)

The LangChain4j types never leave the `quarkus` module. Mapping happens
inside the adapter:

```
engine: ModelRequest            ──►  LC: ChatRequest
engine: ChatMessage/Content      ──►  LC: SystemMessage/UserMessage/AiMessage
engine: ToolDefinition          ──►  LC: ToolSpecification (name, description, parameters JSON schema)
engine: ToolChoice             ──►  LC: ToolChoice
engine: ResponseFormat         ──►  LC: ResponseFormat
engine: ChatRequestParameters  ──►  LC: ChatRequestParameters (model, temperature, topP, topK, …)

LC: ChatResponse.aiMessage().text()          ──►  engine: text part
LC: ChatResponse.aiMessage().toolExecutionRequests()  ──►  engine: ToolCall (id, name, arguments)
LC: ChatResponse.tokenUsage()                ──►  engine: Usage
LC: ChatResponse.finishReason()              ──►  engine: FinishReason (normalized)
LC: ChatResponse.metadata()                  ──►  engine: provider metadata (pass-through)
LC: StreamingChatResponseHandler callbacks   ──►  engine: ModelEvent (text delta, reasoning, tool-call, finish, error)

engine: ToolResult              ──►  LC: ToolExecutionResultMessage (id, toolName, content, isError)
```

## 4. What stays custom in `engine` (not LangChain4j)

| Concern | Spec source | Why not LangChain4j |
|---|---|---|
| Provider error classification | `provider/03-classification.md` | LC is exception-based; we need typed `ProviderFailure` hierarchy (rate-limit, auth, quota, content-filter, transport, …) and retryability. |
| Retry policy | `provider/03-classification.md`, O-01 | LC has no retry with retryAfter cap (15 min), 3 timeouts, jitter, 4 attempts. |
| Output budget | `provider/04-catalog-response.md` §5 | Formula `min(budget, max(1024, context - input - buffer))` with 15 % estimation error. LC has no budget management. |
| Context-overflow → compaction | `session-engine/04-algorithms.md` | Exactly one compaction, then retry. LC has no compaction. |
| Provider catalog | `provider/04-catalog-response.md` | `ModelInfo` with cost, limit, capabilities, variants. LC has no model catalog. |
| Cache affinity | `provider/04-catalog-response.md` §6 | Kind/fork cache keys. LC has no cache-affinity logic. |
| Permission gate before tool execution | `permissions/02-evaluation.md`, `03-effect-matrix.md` | LC `AiServices` executes `@Tool` immediately. We need `Permission.assert` first (deny before saved approval). |
| Durable logging before side effect | `session-engine/04-algorithms.md` §G (G-03) | LC has no durable event log. `ToolCalled` must be committed before the leaf runs. |
| Bounding / truncation | `tools/02-filesystem.md`, `tool-output` | `ToolOutput.truncate` with `outputPath`. LC has no bounding. |
| Snapshot freeze | `tools/01-types.md` | Registry snapshot frozen per step. LC has no tool snapshot. |
| Chat memory | `session-engine/02-events.md` | Durable event log is the truth. LC `ChatMemory` would be a second, non-durable truth. |
| Prompt composition | `session-engine/13-internal-prompts.md` | System part, message, tool description, structure hint with precedence. LC has `SystemMessage`/`UserMessage` but no composition rules. |
| Provider templates (dropped, PRM-A) | `session-engine/13-internal-prompts.md` | Only `SYS-BASE` + `agent.system`. |

### 5. What LangChain4j is used for (in `quarkus`)

1. **HTTP/SSE transport** to the provider (auth, connection pooling, SSE parsing).
2. **Streaming** via `StreamingChatModel.chat(request, handler)` → mapped to `ModelEvent`.
3. **Tool specification serialization** (`ToolSpecification` from `ToolDefinition`).
4. **Tool execution request parsing** (`AiMessage.toolExecutionRequests()` → `ToolCall`).
5. **Tool result messages** (`ToolExecutionResultMessage`).
6. **Token usage / finish reason / metadata** pass-through.

### 6. What LangChain4j must NOT be used for

- **`AiServices` / `@Tool` auto-execution** – bypasses permission gate, bounding, durable logging, snapshot freeze. Forbidden.
- **`ChatMemory`** – conflicts with the durable event log.
- **Retry / classification / budget / overflow** – spec-defined, keep custom.
- **Provider catalog** – spec-defined, keep custom.
- **Any engine-core logic** – `engine` stays pure Java.

> **Note:** LangChain4j does not replace the core loop. qoc's loop is DB-backed
> (durable steps), not in-memory. LangChain4j lacks permission enforcement and
> HITL — see `overview.md` §4.4.

### 7. Coexistence with the JDK-based `HttpProviderAdapter`

Both implement `core.contract.provider.ProviderAdapter`:

| | JDK adapter (`runtime.provider.client`) | LangChain4j adapter (`quarkus`) |
|---|---|---|
| Package | `engine.runtime` (pure Java) | `quarkus` (Quarkus) |
| Transport | JDK `HttpClient` + manual SSE | `ChatModel` / `StreamingChatModel` |
| Tool specs | manual JSON Schema | `ToolSpecification` (LC serializes) |
| Tests | `ScriptedHttp` (pure Java) | mocked `ChatModel` |
| Use case | reference / fallback / pure-Java environments | production (less HTTP boilerplate) |

Selection happens at wiring time (`EngineConfig`), by provider ID or config.
The JDK adapter remains as the reference implementation and fallback; the
LangChain4j adapter is the production default for Quarkus deployments.

### 8. Implementation sketch (M5)

```java
// In quarkus module:
public class LangChain4jProviderAdapter implements ProviderAdapter {
    private final ChatModel chatModel;          // or StreamingChatModel
    private final StreamingChatModel streaming;

    @Override
    public ModelEvent stream(ModelRequest request) {
        ChatRequest lcRequest = toChatRequest(request);   // mapping
        // use streaming model, map handler callbacks to ModelEvent
        streaming.chat(lcRequest, new StreamingChatResponseHandler() {
            public void onPartialResponse(String token) { /* emit text delta */ }
            public void onCompleteResponse(ChatResponse response) { /* emit finish, tool calls */ }
            public void onError(Throwable error) { /* classify → ProviderFailure */ }
        });
    }
}
```

Mapping details:
- `ModelRequest.messages` → `ChatMessage` list (`SystemMessage`, `UserMessage`,
  `AiMessage`, `ToolExecutionResultMessage`).
- `ModelRequest.tools` → `List<ToolSpecification>` (build from `ToolDefinition`).
- `ModelRequest.toolChoice` → `ToolChoice`.
- `ModelRequest.generation` → `ChatRequestParameters` (temperature, topP, topK,
  maxOutputTokens, stopSequences).
- `ModelRequest.responseFormat` → `ResponseFormat`.
- On response: `aiMessage().text()` → text part; `toolExecutionRequests()` →
  `ToolCall`; `tokenUsage()` → usage; `finishReason()` → normalized finish.
- On error: classify into `ProviderFailure` (rate-limit, auth, quota, …) using
  our classifier, not LC's exception type.

### 9. Testing strategy

- **`engine` tests:** pure Java, use `ScriptedHttp` (fake `HttpTransport`) to
  drive the JDK adapter. No LangChain4j dependency. Tests verify the neutral
  contract (ModelEvent sequence, error classification, retry, budget).
- **`quarkus` tests:** mock `ChatModel`/`StreamingChatModel` (Mockito) to
  verify the mapping (ChatRequest built correctly, callbacks mapped to
  ModelEvent, tool specs serialized). Integration test with a real provider
  behind a flag.

### 10. References

- Spec: `spec/provider/01-types.md`, `02-event-stream.md`, `03-classification.md`,
  `04-catalog-response.md`, `05-invariants.md`
- Spec: `spec/session-engine/05-provider.md` (engine view), `04-algorithms.md` (ModelRequest build)
- Spec: `spec/java-packages.md` (package placement, port principle)
- Spec: `spec/session-engine/Java-Impl.md` §10 (provider interface), §17 (error taxonomy)
- LangChain4j docs: `dev.langchain4j.model.chat.ChatModel`,
  `dev.langchain4j.model.chat.StreamingChatModel`,
  `dev.langchain4j.agent.tool.ToolSpecification`,
  `dev.langchain4j.data.message.ToolExecutionResultMessage`
- Quarkus: `io.quarkiverse.langchain4j:quarkus-langchain4j-openai` 1.13.3
  (LangChain4j 1.19.3)

## Part 2: Replacement potential and boundaries

# LangChain4j in QOC – usage, replacement potential and boundaries

> **Status:** 2026-10-31  
> **Version:** LangChain4j 1.19.3 (via Quarkus LangChain4j), `decision-gate` uses 1.22.0  
> **Purpose:** Where LangChain4j can replace the existing implementation – working
> backwards from the LLM HTTP calls – and what deliberately stays custom.

---

## 1. Starting point

QOC already has **LangChain4j 1.19.3 on the classpath**
(`quarkus-langchain4j-openai`), but **does not** use it for the provider. The
production path is the custom JDK adapter:

```
Engine/Step → ProviderAdapter → HttpProviderAdapter
  → OpenAiRequestEncoder + OpenAiSchema     (Request → JSON)
  → JdkHttpTransport + SseReader            (HTTP + SSE)
  → OpenAiEventTranslator + StreamValidator  (SSE → ModelEvent)
  → ProviderErrorClassifier                  (HTTP → FailureKind)
```

`langchain4j.md` describes a planned LC adapter; it is not implemented. The
`decision-gate` prototype uses LC 1.22.0, QOC pins 1.19.3.

---

## 2. Layer by layer – working backwards from the HTTP calls

### 2.1 HTTP transport & SSE (lowest layer)

| Today | File | LangChain4j |
|---|---|---|
| JDK `HttpClient`, Header, Timeout | `engine/.../adapter/net/JdkHttpTransport.java` | `OpenAiStreamingChatModel` (built-in HTTP client) |
| SSE frame parsing | `SseReader.java` (67 lines) | internal |
| Retry/error stream | `HttpProviderAdapter.java` (155 lines) | `httpClientBuilder`, `timeout`, `maxRetries` |

**Replaceable:** yes, almost entirely. `HttpProviderAdapter`, `SseReader` and
most of `JdkHttpTransport` (~250–300 lines) go away.

**What stays:** `StreamValidator` (protocol invariants) and
`ProviderErrorClassifier` (closed `FailureKind` taxonomy).

**Caveat:** LC ships its own HTTP client; the existing `HttpTransport` port and
`LlmTrace` are bypassed. Resolve via `ChatModelListener`/custom HTTP client, or
keep the port as a thin wrapper.

### 2.2 Request encoding

| Today | File | LangChain4j |
|---|---|---|
| OpenAI JSON (messages, tools, tool_choice, response_format, max_tokens, temperature, stop) | `OpenAiRequestEncoder.java` (200 lines) | `ChatRequest.builder()`, `ChatRequestParameters` |
| JSON schema for tools | `OpenAiSchema.java` (86 lines) | `ToolSpecification` / `JsonObjectSchema` |
| Structured output | `OpenAiRequestEncoder` + `SubmitResult` | `ResponseFormat` / `JsonSchema` |
| Provider options/headers | `providerOptions`, `headers` | `customParameters`, `customHeaders` |

**Replaceable:** yes, high gain (~286 lines). **What stays:** `cacheKey`/
cache affinity, `SubmitResult` validation, model catalog.

### 2.3 Response/event translation

| Today | File | LangChain4j |
|---|---|---|
| SSE chunks → `TextDelta`, `ReasoningDelta`, `ToolCallRequested`, `Finished` | `OpenAiEventTranslator.java` (205 lines) | `StreamingChatResponseHandler` |
| Tool input deltas | `ToolInputStarted/Delta/Ended` | `onPartialToolCall`, `onCompleteToolCall` |
| Reasoning | `ReasoningDelta` | `onPartialThinking` |
| Finish/tokens | `Finished` | `ChatResponse.finishReason()`, `tokenUsage()` |
| Protocol validation | `StreamValidator.java` (165 lines) | no equivalent |

**Replaceable:** partially. The **mapping layer** (`ModelEvent`) stays, but the
SSE parser and OpenAI-specific chunk interpretation go away. `StreamValidator`
stays (defensive invariants). `JsonEventTranslator` (204 lines) stays for
tests/scripted providers.

### 2.4 Error classification & retry

| Today | File | LangChain4j |
|---|---|---|
| HTTP status/code → `FailureKind` | `ProviderErrorClassifier.java` (44 lines) | `OpenAiException` hierarchy (`RateLimitException`, `AuthenticationException`, …) |
| Retry | not implemented | `maxRetries`, HTTP client retry |

**Replaceable:** partially. The **closed `FailureKind` taxonomy is spec** and
should stay; LC exceptions are mapped onto it. Retry can be delegated to LC,
but must respect the `Retry-After` cap/spec – otherwise keep a custom retry.

### 2.5 Tool calling

| Today | LangChain4j |
|---|---|
| `ToolDefinition` → JSON schema | `ToolSpecification` |
| `ToolCall` from response | `AiMessage.toolExecutionRequests()` |
| Tool result | `ToolExecutionResultMessage` |
| Tool execution | **`AiServices`/`@Tool` (forbidden)** |

**Replaceable:** only **serialization/parsing**. Execution stays with QOC:
permission gate, HITL/ASK, durable event log before side effect, snapshot
freeze, bounding.

### 2.6 Multi-provider

| Today | LangChain4j |
|---|---|
| Only OpenAI-compatible (`engine.provider.url`) | OpenAI, Azure OpenAI, Anthropic, Gemini, Mistral, Ollama, Bedrock, … |

**New, not a replacement:** This is the biggest functional gain. The
`ProviderAdapter` port stays; one LC `ChatModel`/`StreamingChatModel` per
provider in the `quarkus` adapter. Configuration can stay at
`engine.model.provider` and internally point to the matching LC model class.

### 2.7 Observability

| Today | LangChain4j |
|---|---|
| `RotatingLlmTrace`, `LlmTrace` | `ChatModelListener` (`onRequest`, `onResponse`, `onError`) |
| OpenTelemetry manual | Quarkus/LC metering |

**Replaceable:** complementary. The rotating trace is an ops spec; an LC
listener can additionally provide metrics/OTel. No hard replacement needed.

### 2.8 Token estimation / context size

| Today | LangChain4j |
|---|---|
| `ContextSize` (own estimation) | `TokenCountEstimator`, `OpenAiTokenCountEstimator` |

**Optional:** more accurate estimation, but provider-specific and an extra
dependency. Not required.

### 2.9 Chat memory

| Today | LangChain4j |
|---|---|
| Durable event log + `ConversationFolding` | `ChatMemory`, `MessageWindowChatMemory` |

**Do not replace.** LC memory is process-local and not durable; it would be a
second truth next to the event log.

### 2.10 RAG / embeddings / vector stores

LangChain4j offers `EmbeddingModel`, `EmbeddingStore`, `ContentRetriever`,
`RetrievalAugmentor` and many vector-store adapters.

**New, not a replacement:** Could be added as an additional context source
(retrieval tool). Not part of the provider replacement.

### 2.11 Guardrails / moderation

LangChain4j has `InputGuardrail`/`OutputGuardrail` and `ModerationModel`.

**Complementary, not a replacement:** QOC has the permission gate and the
external webhook guardrail. LC guardrails are in-process and could complement
PII/moderation locally, but cannot replace the external policy.

### 2.12 Agentic / AiServices

LangChain4j offers `AiServices`, `@Tool`, `@MemoryId` as well as
`langchain4j-agentic`, `-agentic-a2a`, `-agentic-mcp`.

**Not for the core loop.** `AiServices` would execute the tool call immediately
and bypass the permission gate, HITL, durable log and snapshot. Conceivable for
isolated helper agents (title, classification), not for `Step`/`ToolRuntime`.

### 2.13 Prompt templates

`PromptTemplate`/Mustache – QOC has spec-defined prompt composition. **Do not
replace.**

### 2.14 Compaction / summarization

LC has no direct compaction service. **Stays custom.**

### 2.15 Provider catalog / ModelInfo

LC has no model catalog (cost, limits, variants). **Stays custom.**

---

## 3. What LangChain4j can do (capability matrix)

| Capability | LC 1.19.3 | Relevant for QOC |
|---|---|---|
| Chat + streaming | ✅ | Replacement for HTTP/SSE |
| Tool calling (specs, requests, results) | ✅ | Replacement for serialization |
| Partial tool calls / reasoning | ✅ | Live streaming |
| Structured output (JSON schema) | ✅ | Request side |
| Multimodal (text/image/audio) | ✅ | Extension |
| Multi-provider | ✅ | Big gain |
| Retry/timeout/headers | ✅ | partial |
| Chat memory | ✅ | **do not** use |
| RAG/embeddings/vector stores | ✅ | new |
| Guardrails/moderation | ✅ | complementary |
| Observability listener | ✅ | complementary |
| Token estimator | ✅ | optional |
| AiServices/agentic | ✅ | **not** for core |
| Quarkus CDI + native | ✅ | already present |

---

## 4. Recommended order

1. **LC provider adapter behind the existing port** (OpenAI first)
   - `ProviderProducer` builds `OpenAiStreamingChatModel` with `baseUrl`,
     `apiKey`, `timeout`, `customHeaders`.
   - `ModelRequest` → `ChatRequest`; `StreamingChatResponseHandler` →
     `ModelEvent`.
   - `StreamValidator` and `ProviderErrorClassifier` stay.
   - **Effort:** medium, **Risk:** low–medium, **Readability:** good,
     **Architecture:** clean (adapter in the `quarkus` module, port in `engine`).
2. **Tool spec mapping** onto `ToolSpecification` (serialization only).
3. **Structured output** onto `ResponseFormat`.
4. **Error mapping** LC exception → `FailureKind`.
5. **Multi-provider** (Anthropic, Ollama, Azure, …) via additional LC models.
6. **Optional:** `ChatModelListener` for OTel, `TokenCountEstimator`,
   RAG/embeddings.

---

## 5. What explicitly stays custom

| Concern | Reason |
|---|---|
| Permission gate / HITL | LC `AiServices` executes tools immediately |
| Durable event log / inbox | LC `ChatMemory` is not durable |
| Snapshot / revert / compaction | LC has no equivalent |
| Provider catalog / budget / cache affinity | Spec-defined |
| `FailureKind` taxonomy | Spec-defined, LC is exception-based |
| A2A/MCP integration | more deeply integrated than LC agentic modules |
| Tool execution loop | Permission, audit, idempotency |

---

## 6. Assessment: real gain and native impact

### Summary

**The real gain depends almost entirely on whether QOC should become
multi-provider.**

- **If QOC stays OpenAI-only:** low gain. The existing adapter is tested,
  native-verified and transparent. LC would replace working code with a
  dependency – more risk than benefit.
- **If QOC should support Anthropic, Gemini, Ollama, Azure, Mistral,
  Bedrock …:** high gain. LC is built exactly for this; it saves weeks of
  provider-specific HTTP/SSE/tool-call work.
- **If QOC should use the LC ecosystem (RAG, embeddings, guardrails,
  observability):** medium to high additional benefit – but **additive**, not a
  replacement.

### Real replacement scope

| Area | Today | With LC | Real gain |
|---|---|---|---|
| HTTP/SSE | `JdkHttpTransport` + `SseReader` + `HttpProviderAdapter` | LC-internal client | **maintenance**, less boilerplate |
| Request JSON | `OpenAiRequestEncoder` + `OpenAiSchema` | `ChatRequest` + `ToolSpecification` | **maintenance**, schema handling |
| Response/events | `OpenAiEventTranslator` | `StreamingChatResponseHandler` | **partial**, mapping stays |
| Multi-provider | only OpenAI-compatible | 10+ providers | **real functional gain** |
| Tool calling | own serialization | LC types | **maintenance** |
| Structured output | own encoder | `ResponseFormat` | **small gain** |
| Observability | own trace | `ChatModelListener` | **complementary** |
| RAG/embeddings | not present | LC modules | **new capability** |
| Memory | durable event log | `ChatMemory` | **no replacement** (forbidden) |
| Agentic | own loop | `AiServices` | **no replacement** (permissions/HITL) |

Bottom line: **~700–1,000 lines of provider-client code** could be replaced by
**~200–300 lines of mapping**. That is a maintenance gain, not a product gain.

### Impact on native deployment

| Aspect | Assessment |
|---|---|
| **Binary size** | LC is already a compile dependency today, but the provider path is unused → GraalVM prunes a lot. With usage, the reachable code increases: **realistically +10–40 MB** (must be measured). |
| **Build time** | currently ~3–4.5 min; with LC rather **+30–90 s**. |
| **Reflection** | The Quarkus LC extension registers a lot, but **records, JSON codecs, ServiceLoader** are candidates for runtime errors – exactly the class of problems we had with A2A (build green, runtime 500). |
| **Startup** | negligible. |
| **Memory** | slightly higher; not a show-stopper on e2-micro. |
| **Native verification** | **mandatory**: re-test agent card, streaming, tool calls, structured output, OIDC, email after the rebuild. |

**The A2A pattern repeats:** The native build succeeded, but the agent-card
endpoint returned `500` at runtime because `ServiceLoader` metadata, `Record
components` and `a2a-defaults.properties` were missing. With LC it will be
similar: **build success ≠ runtime success**. You need an explicit native E2E
test for the LC adapter, not just `mvn package -Dnative`.

### Technical risks

1. **Streaming semantics:** LC delivers `onPartialResponse`, `onPartialThinking`,
   `onPartialToolCall`, `onCompleteResponse`. Event ordering, tool-input deltas
   and finish reasons may behave differently than in the custom adapter.
   `StreamValidator` and UI streaming must be adapted.
2. **Error taxonomy:** LC exceptions (`RateLimitException`,
   `AuthenticationException`, …) must be mapped onto the existing `FailureKind`
   taxonomy. Retry semantics (`Retry-After`, cap) remain spec-critical.
3. **Version drift:** QOC pins 1.19.3, `decision-gate` uses 1.22.0. LC moves
   fast; an upgrade can break APIs.
4. **Loss of control:** `cacheKey`/cache affinity, `parallel_tool_calls`,
   `strict` tools, provider options – much of this goes through
   `customParameters`, but not everything.
5. **Debugging:** an additional abstraction layer between QOC and the provider
   makes provider bugs harder to trace.

### Effort

- Migration of the OpenAI adapter incl. tests: **1–2 weeks**.
- Native verification (reflection, binary size, E2E): **1–3 days**.
- Per additional provider: **1–3 days** (LC takes a lot off, but
  configuration/tests remain).

### Scorecard

| Scenario | Gain | Risk | Recommendation |
|---|---|---|---|
| **OpenAI-only, no change** | 1/10 | – | Do not integrate |
| **OpenAI-only, LC as alternative** | 3/10 | medium | Optional, behind a flag |
| **Multi-provider (Anthropic/Ollama/…)** | **8/10** | medium | **Integrate** |
| **LC ecosystem (RAG/embeddings)** | 6/10 | low–medium | Additive, later |
| **AiServices/agentic** | 1/10 | high | **No** |

### Recommendation

1. **Introduce not as a replacement but as a second adapter** behind the
   existing `ProviderAdapter` port. The custom adapter stays fallback/reference.
2. **Start with OpenAI** and switch behind `engine.provider.kind=langchain4j`.
3. **Run native E2E from the start** (not only at the end).
4. **Take multi-provider as the real driver** – then the effort pays off.
5. **Do not touch:** `AiServices`, `ChatMemory`, permission gate, event log,
   `FailureKind`, compaction.
6. **Version:** stay at 1.19.3 first; decide on an upgrade to 1.22+ separately
   when `DecisionModel`/agentic is really needed.

### Honest conclusion

**The real gain is multi-provider capability, not the HTTP client.** If QOC
remains an OpenAI-compatible agent runner, LC integration is mostly cosmetics
with native risk. If QOC is to become a provider-neutral agent runner, LC is
the right path – but then with a clear native verification budget and without
the LC agentic layer.

## 7. Open decisions

1. **Version:** stay at 1.19.3 (sufficient for providers) or move to 1.22+
   (for `DecisionModel`/agentic)? An upgrade must be reconciled with
   `decision-gate`.
2. **Configuration:** keep `engine.provider.*` and build LC models manually, or
   switch to `quarkus.langchain4j.openai.*`? Recommendation: build manually,
   config stays stable.
3. **Streaming detail depth:** LC delivers partial tool calls – keep live
   tool-input display? (Yes, use `onPartialToolCall`.)
4. **Native:** LC is already in the native build; the new adapter needs
   reflection registration for LC types (the Quarkus extension helps). After
   the rebuild, repeat native build + A2A/OIDC test.

---

## 8. References

- `langchain4j.md` – existing integration guide
- `engine/src/main/java/de/augmentia/oc/engine/runtime/provider/client/`
- `engine/src/main/java/de/augmentia/oc/engine/core/contract/provider/`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/ProviderProducer.java`
- LangChain4j: `ChatModel`, `StreamingChatModel`, `ChatRequest`, `ChatResponse`,
  `ToolSpecification`, `ToolExecutionResultMessage`, `ChatModelListener`