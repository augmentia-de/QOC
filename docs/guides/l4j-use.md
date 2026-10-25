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

### 7. Coexistence with the JDK-based `HttpProviderAdapter`

Both implement `core.contract.provider.ProviderAdapter`:

| | JDK adapter (`runtime.provider.client`) | LangChain4j adapter (`quarkus`) |
|---|---|---|
| Package | `engine.runtime` (pure Java) | `quarkus` (Quarkus) |
| Transport | JDK `HttpClient` + manual SSE | `ChatModel` / `StreamingChatModel` |
| Tool specs | manual JSON Schema | `ToolSpecification` (LC serializes) |
| Tests | `ScriptedHttp` (rein Java) | mocked `ChatModel` |
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