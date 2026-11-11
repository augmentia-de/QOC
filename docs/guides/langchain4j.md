# LangChain4j in QOC

> **Status:** 2026-10-31 · LangChain4j 1.19.3 (via Quarkus LangChain4j 1.13.3)
> **Zusammengeführt aus:** `l4j-use.md` (Integration Guide) + `guides/langchain4j.md` (Ersatzpotenzial)

## Teil 1: Integration Guide

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
## Teil 2: Ersatzpotenzial und Abgrenzung

# LangChain4j in QOC – Einsatz, Ersatzpotenzial und Abgrenzung

> **Status:** 2026-10-31  
> **Version:** LangChain4j 1.19.3 (via Quarkus LangChain4j), `decision-gate` nutzt 1.22.0  
> **Zweck:** Wo LangChain4j die bestehende Implementierung ersetzen kann – von den
> LLM-HTTP-Calls rückwärts – und was bewusst custom bleibt.

---

## 1. Ausgangslage

QOC hat **LangChain4j 1.19.3 bereits auf dem Classpath**
(`quarkus-langchain4j-openai`), nutzt es aber **nicht** für den Provider. Der
produktive Pfad ist der eigene JDK-Adapter:

```
Engine/Step → ProviderAdapter → HttpProviderAdapter
  → OpenAiRequestEncoder + OpenAiSchema     (Request → JSON)
  → JdkHttpTransport + SseReader            (HTTP + SSE)
  → OpenAiEventTranslator + StreamValidator  (SSE → ModelEvent)
  → ProviderErrorClassifier                  (HTTP → FailureKind)
```

`langchain4j.md` beschreibt einen geplanten LC-Adapter; implementiert
ist er nicht. Der `decision-gate`-Prototyp nutzt LC 1.22.0, QOC pinnt 1.19.3.

---

## 2. Schicht für Schicht – von den HTTP-Calls rückwärts

### 2.1 HTTP-Transport & SSE (unterste Schicht)

| Heute | Datei | LangChain4j |
|---|---|---|
| JDK `HttpClient`, Header, Timeout | `engine/.../adapter/net/JdkHttpTransport.java` | `OpenAiStreamingChatModel` (eingebauter HTTP-Client) |
| SSE-Frame-Parsing | `SseReader.java` (67 Z.) | intern |
| Retry/Fehler-Stream | `HttpProviderAdapter.java` (155 Z.) | `httpClientBuilder`, `timeout`, `maxRetries` |

**Ersetzbar:** ja, fast vollständig. `HttpProviderAdapter`, `SseReader` und der
Großteil von `JdkHttpTransport` (~250–300 Zeilen) entfallen.

**Was bleibt:** `StreamValidator` (Protokoll-Invarianten) und
`ProviderErrorClassifier` (geschlossene `FailureKind`-Taxonomie).

**Caveat:** LC bringt einen eigenen HTTP-Client mit; der bestehende
`HttpTransport`-Port und `LlmTrace` werden umgangen. Über
`ChatModelListener`/Custom-HTTP-Client lösen oder den Port als dünnen Wrapper
behalten.

### 2.2 Request-Encoding

| Heute | Datei | LangChain4j |
|---|---|---|
| OpenAI-JSON (messages, tools, tool_choice, response_format, max_tokens, temperature, stop) | `OpenAiRequestEncoder.java` (200 Z.) | `ChatRequest.builder()`, `ChatRequestParameters` |
| JSON-Schema für Tools | `OpenAiSchema.java` (86 Z.) | `ToolSpecification` / `JsonObjectSchema` |
| Strukturierte Ausgabe | `OpenAiRequestEncoder` + `SubmitResult` | `ResponseFormat` / `JsonSchema` |
| Provider-Optionen/Header | `providerOptions`, `headers` | `customParameters`, `customHeaders` |

**Ersetzbar:** ja, hoher Gewinn (~286 Zeilen). **Was bleibt:** `cacheKey`/
Cache-Affinität, `SubmitResult`-Validierung, Modellkatalog.

### 2.3 Response-/Event-Übersetzung

| Heute | Datei | LangChain4j |
|---|---|---|
| SSE-Chunks → `TextDelta`, `ReasoningDelta`, `ToolCallRequested`, `Finished` | `OpenAiEventTranslator.java` (205 Z.) | `StreamingChatResponseHandler` |
| Tool-Input-Deltas | `ToolInputStarted/Delta/Ended` | `onPartialToolCall`, `onCompleteToolCall` |
| Reasoning | `ReasoningDelta` | `onPartialThinking` |
| Finish/Tokens | `Finished` | `ChatResponse.finishReason()`, `tokenUsage()` |
| Protokoll-Validierung | `StreamValidator.java` (165 Z.) | keine Entsprechung |

**Ersetzbar:** teilweise. Die **Mapping-Schicht** (`ModelEvent`) bleibt, aber
der SSE-Parser und die OpenAI-spezifische Chunk-Interpretation entfallen.
`StreamValidator` bleibt (defensive Invarianten). `JsonEventTranslator`
(204 Z.) bleibt für Tests/Scripted Provider.

### 2.4 Fehlerklassifikation & Retry

| Heute | Datei | LangChain4j |
|---|---|---|
| HTTP-Status/Code → `FailureKind` | `ProviderErrorClassifier.java` (44 Z.) | `OpenAiException`-Hierarchie (`RateLimitException`, `AuthenticationException`, …) |
| Retry | nicht implementiert | `maxRetries`, HTTP-Client-Retry |

**Ersetzbar:** teilweise. Die **geschlossene `FailureKind`-Taxonomie ist Spec**
und sollte bleiben; LC-Exceptions werden darauf gemappt. Retry kann LC
übernehmen, muss aber `Retry-After`-Cap/Spec berücksichtigen – sonst eigener
Retry.

### 2.5 Tool-Calling

| Heute | LangChain4j |
|---|---|
| `ToolDefinition` → JSON-Schema | `ToolSpecification` |
| `ToolCall` aus Response | `AiMessage.toolExecutionRequests()` |
| Tool-Ergebnis | `ToolExecutionResultMessage` |
| Tool-Ausführung | **`AiServices`/`@Tool` (verboten)** |

**Ersetzbar:** nur die **Serialisierung/Parsing**. Die Ausführung bleibt bei
QOC: Permission-Gate, HITL/ASK, durable Event-Log vor Side-Effect,
Snapshot-Freeze, Bounding.

### 2.6 Multi-Provider

| Heute | LangChain4j |
|---|---|
| Nur OpenAI-kompatibel (`engine.provider.url`) | OpenAI, Azure OpenAI, Anthropic, Gemini, Mistral, Ollama, Bedrock, … |

**Neu, nicht Ersatz:** Das ist der größte funktionale Gewinn. Der
`ProviderAdapter`-Port bleibt; pro Provider ein LC-`ChatModel`/
`StreamingChatModel` im `quarkus`-Adapter. Konfiguration kann bei
`engine.model.provider` bleiben und intern auf die passende LC-Modellklasse
zeigen.

### 2.7 Observability

| Heute | LangChain4j |
|---|---|
| `RotatingLlmTrace`, `LlmTrace` | `ChatModelListener` (`onRequest`, `onResponse`, `onError`) |
| OpenTelemetry manuell | Quarkus/LC-Metering |

**Ersetzbar:** ergänzend. Der rotierende Trace ist Betriebs-Spec; ein
LC-Listener kann zusätzlich Metriken/OTel liefern. Kein harter Ersatz nötig.

### 2.8 Token-Schätzung / Kontextgröße

| Heute | LangChain4j |
|---|---|
| `ContextSize` (eigene Schätzung) | `TokenCountEstimator`, `OpenAiTokenCountEstimator` |

**Optional:** genauere Schätzung, aber provider-spezifisch und zusätzliche
Abhängigkeit. Kein Muss.

### 2.9 Chat-Memory

| Heute | LangChain4j |
|---|---|
| Durable Event-Log + `ConversationFolding` | `ChatMemory`, `MessageWindowChatMemory` |

**Nicht ersetzen.** LC-Memory ist prozesslokal und nicht durable; es wäre eine
zweite Wahrheit neben dem Event-Log.

### 2.10 RAG / Embeddings / Vector Stores

LangChain4j bietet `EmbeddingModel`, `EmbeddingStore`, `ContentRetriever`,
`RetrievalAugmentor` und viele Vector-Store-Adapter.

**Neu, nicht Ersatz:** Könnte als zusätzliche Kontextquelle (Retrieval-Tool)
eingebaut werden. Nicht Teil des Provider-Ersatzes.

### 2.11 Guardrails / Moderation

LangChain4j hat `InputGuardrail`/`OutputGuardrail` und `ModerationModel`.

**Ergänzend, nicht Ersatz:** QOC hat Permission-Gate und den externen
Webhook-Guardrail. LC-Guardrails sind in-process und könnten PII/Moderation
lokal ergänzen, aber nicht die externe Policy ersetzen.

### 2.12 Agentic / AiServices

LangChain4j bietet `AiServices`, `@Tool`, `@MemoryId` sowie
`langchain4j-agentic`, `-agentic-a2a`, `-agentic-mcp`.

**Nicht für den Kern-Loop.** `AiServices` würde den Tool-Call sofort ausführen
und Permission-Gate, HITL, durable Log und Snapshot umgehen. Für isolierte
Hilfsagenten (Titel, Klassifikation) denkbar, nicht für `Step`/`ToolRuntime`.

### 2.13 Prompt-Templates

`PromptTemplate`/Mustache – QOC hat spec-definierte Prompt-Komposition.
**Nicht ersetzen.**

### 2.14 Compaction / Summarization

LC hat keinen direkten Compaction-Service. **Bleibt custom.**

### 2.15 Provider-Katalog / ModelInfo

LC hat keinen Modellkatalog (Kosten, Limits, Varianten). **Bleibt custom.**

---

## 3. Was LangChain4j kann (Capability-Matrix)

| Fähigkeit | LC 1.19.3 | Für QOC relevant |
|---|---|---|
| Chat + Streaming | ✅ | Ersatz HTTP/SSE |
| Tool-Calling (Specs, Requests, Results) | ✅ | Ersatz Serialisierung |
| Partial Tool Calls / Reasoning | ✅ | Live-Streaming |
| Structured Output (JSON Schema) | ✅ | Request-Seite |
| Multimodal (Text/Bild/Audio) | ✅ | Erweiterung |
| Multi-Provider | ✅ | Großer Gewinn |
| Retry/Timeout/Headers | ✅ | teilweise |
| Chat Memory | ✅ | **nicht** verwenden |
| RAG/Embeddings/Vector Stores | ✅ | neu |
| Guardrails/Moderation | ✅ | ergänzend |
| Observability Listener | ✅ | ergänzend |
| Token-Estimator | ✅ | optional |
| AiServices/Agentic | ✅ | **nicht** für Kern |
| Quarkus CDI + Native | ✅ | bereits vorhanden |

---

## 4. Empfohlene Reihenfolge

1. **LC-ProviderAdapter hinter dem bestehenden Port** (OpenAI zuerst)
   - `ProviderProducer` baut `OpenAiStreamingChatModel` mit `baseUrl`,
     `apiKey`, `timeout`, `customHeaders`.
   - `ModelRequest` → `ChatRequest`; `StreamingChatResponseHandler` →
     `ModelEvent`.
   - `StreamValidator` und `ProviderErrorClassifier` bleiben.
   - **Aufwand:** mittel, **Risiko:** niedrig–mittel, **Lesbarkeit:** gut,
     **Architektur:** sauber (Adapter im `quarkus`-Modul, Port im `engine`).

2. **Tool-Spec-Mapping** auf `ToolSpecification` (nur Serialisierung).

3. **Structured Output** auf `ResponseFormat`.

4. **Fehler-Mapping** LC-Exception → `FailureKind`.

5. **Multi-Provider** (Anthropic, Ollama, Azure, …) über weitere LC-Modelle.

6. **Optional:** `ChatModelListener` für OTel, `TokenCountEstimator`,
   RAG/Embeddings.

---

## 5. Was ausdrücklich custom bleibt

| Concern | Grund |
|---|---|
| Permission-Gate / HITL | LC `AiServices` führt Tools sofort aus |
| Durable Event-Log / Inbox | LC `ChatMemory` ist nicht durable |
| Snapshot / Revert / Compaction | LC hat keine Entsprechung |
| Provider-Katalog / Budget / Cache-Affinität | Spec-definiert |
| `FailureKind`-Taxonomie | Spec-definiert, LC ist exception-basiert |
| A2A/MCP-Integration | tiefer integriert als LC-Agentic-Module |
| Tool-Execution-Loop | Permission, Audit, Idempotenz |

---

## 6. Einschätzung: realer Gewinn und Native-Auswirkung

### Kurzfassung

**Der reale Gewinn hängt fast vollständig davon ab, ob QOC multi-provider
werden soll.**

- **Bleibt QOC OpenAI-only:** geringer Gewinn. Der bestehende Adapter ist
  getestet, native-verifiziert und transparent. LC würde funktionierenden Code
  durch eine Abhängigkeit ersetzen – mehr Risiko als Nutzen.
- **Soll QOC Anthropic, Gemini, Ollama, Azure, Mistral, Bedrock … können:**
  hoher Gewinn. Genau dafür ist LC gebaut; das spart Wochen
  provider-spezifischer HTTP/SSE-/Tool-Call-Arbeit.
- **Soll QOC das LC-Ökosystem nutzen (RAG, Embeddings, Guardrails,
  Observability):** mittlerer bis hoher Zusatznutzen – aber **additiv**, kein
  Ersatz.

### Realer Ersatzumfang

| Bereich | Heute | Mit LC | Realer Gewinn |
|---|---|---|---|
| HTTP/SSE | `JdkHttpTransport` + `SseReader` + `HttpProviderAdapter` | LC-interner Client | **Wartung**, weniger Boilerplate |
| Request-JSON | `OpenAiRequestEncoder` + `OpenAiSchema` | `ChatRequest` + `ToolSpecification` | **Wartung**, Schema-Handling |
| Response/Events | `OpenAiEventTranslator` | `StreamingChatResponseHandler` | **teilweise**, Mapping bleibt |
| Multi-Provider | nur OpenAI-kompatibel | 10+ Provider | **echter Funktionsgewinn** |
| Tool-Calling | eigene Serialisierung | LC-Typen | **Wartung** |
| Structured Output | eigener Encoder | `ResponseFormat` | **kleiner Gewinn** |
| Observability | eigener Trace | `ChatModelListener` | **ergänzend** |
| RAG/Embeddings | nicht vorhanden | LC-Module | **neue Fähigkeit** |
| Memory | durable Event-Log | `ChatMemory` | **kein Ersatz** (verboten) |
| Agentic | eigener Loop | `AiServices` | **kein Ersatz** (Permissions/HITL) |

Unterm Strich: **~700–1.000 Zeilen Provider-Client-Code** könnten durch
**~200–300 Zeilen Mapping** ersetzt werden. Das ist ein Wartungsgewinn, kein
Produktgewinn.

### Auswirkung auf Native Deployment

| Aspekt | Einschätzung |
|---|---|
| **Binary-Größe** | LC ist heute schon Compile-Dependency, aber der Provider-Pfad ist ungenutzt → GraalVM pruned viel. Mit Nutzung steigt die erreichbare Code-Menge: **realistisch +10–40 MB** (muss gemessen werden). |
| **Build-Zeit** | aktuell ~3–4,5 min; mit LC eher **+30–90 s**. |
| **Reflection** | Quarkus-LC-Extension registriert viel, aber **Records, JSON-Codecs, ServiceLoader** sind Kandidaten für Laufzeitfehler – exakt die Klasse von Problemen, die wir bei A2A hatten (Build grün, Runtime 500). |
| **Startup** | vernachlässigbar. |
| **Memory** | geringfügig höher; kein Show-Stopper auf e2-micro. |
| **Native-Verifikation** | **zwingend**: Agent-Card, Streaming, Tool-Calls, Structured Output, OIDC, E-Mail nach dem Umbau erneut testen. |

**Das Muster von A2A wiederholt sich:** Der Native-Build war erfolgreich, aber
der Agent-Card-Endpoint gab zur Laufzeit `500`, weil `ServiceLoader`-Metadaten,
`Record components` und `a2a-defaults.properties` fehlten. Bei LC wird es
ähnlich sein: **Build-Erfolg ≠ Runtime-Erfolg**. Man braucht einen expliziten
Native-E2E-Test für den LC-Adapter, nicht nur `mvn package -Dnative`.

### Technische Risiken

1. **Streaming-Semantik:** LC liefert `onPartialResponse`, `onPartialThinking`,
   `onPartialToolCall`, `onCompleteResponse`. Event-Reihenfolge,
   Tool-Input-Deltas und Finish-Gründe können sich anders verhalten als im
   Custom-Adapter. `StreamValidator` und UI-Streaming müssen angepasst werden.
2. **Fehler-Taxonomie:** LC-Exceptions (`RateLimitException`,
   `AuthenticationException`, …) müssen auf die bestehende
   `FailureKind`-Taxonomie gemappt werden. Retry-Semantik (`Retry-After`, Cap)
   bleibt Spec-kritisch.
3. **Version-Drift:** QOC pinnt 1.19.3, `decision-gate` nutzt 1.22.0. LC bewegt
   sich schnell; ein Upgrade kann APIs brechen.
4. **Kontrollverlust:** `cacheKey`/Cache-Affinität, `parallel_tool_calls`,
   `strict`-Tools, Provider-Optionen – vieles geht über `customParameters`,
   aber nicht alles.
5. **Debugging:** eine zusätzliche Abstraktionsschicht zwischen QOC und dem
   Provider macht Provider-Bugs schwerer nachvollziehbar.

### Aufwand

- Migration OpenAI-Adapter inkl. Tests: **1–2 Wochen**.
- Native-Verifikation (Reflection, Binary-Größe, E2E): **1–3 Tage**.
- Pro weiterem Provider: **1–3 Tage** (LC nimmt viel ab, Konfiguration/Tests
  bleiben).

### Scorecard

| Szenario | Gewinn | Risiko | Empfehlung |
|---|---|---|---|
| **OpenAI-only, keine Änderung** | 1/10 | – | Nicht integrieren |
| **OpenAI-only, LC als Alternative** | 3/10 | mittel | Optional, hinter Flag |
| **Multi-Provider (Anthropic/Ollama/…)** | **8/10** | mittel | **Integrieren** |
| **LC-Ökosystem (RAG/Embeddings)** | 6/10 | niedrig–mittel | Additiv, später |
| **AiServices/Agentic** | 1/10 | hoch | **Nicht** |

### Empfehlung

1. **Nicht als Ersatz, sondern als zweiter Adapter** hinter dem bestehenden
   `ProviderAdapter`-Port einführen. Der Custom-Adapter bleibt
   Fallback/Referenz.
2. **Mit OpenAI starten** und hinter `engine.provider.kind=langchain4j`
   schalten.
3. **Native-E2E von Anfang an** mitlaufen lassen (nicht erst am Ende).
4. **Multi-Provider als eigentlichen Treiber** nehmen – dann lohnt sich der
   Aufwand.
5. **Nicht anfassen:** `AiServices`, `ChatMemory`, Permission-Gate, Event-Log,
   `FailureKind`, Compaction.
6. **Version:** erst bei 1.19.3 bleiben; ein Upgrade auf 1.22+ separat
   entscheiden, wenn `DecisionModel`/Agentic wirklich gebraucht wird.

### Ehrliches Fazit

**Der reale Gewinn ist die Multi-Provider-Fähigkeit, nicht der HTTP-Client.**
Wenn QOC ein OpenAI-kompatibler Agent-Runner bleibt, ist LC-Integration
hauptsächlich Kosmetik mit Native-Risiko. Wenn QOC ein provider-neutraler
Agent-Runner werden soll, ist LC der richtige Weg – dann aber mit einem klaren
Native-Verifikationsbudget und ohne die LC-Agentic-Schicht.

## 7. Offene Entscheidungen

1. **Version:** bei 1.19.3 bleiben (für Provider reicht es) oder auf 1.22+
   gehen (für `DecisionModel`/Agentic)? Ein Upgrade muss mit `decision-gate`
   abgeglichen werden.
2. **Konfiguration:** `engine.provider.*` behalten und LC-Modelle manuell
   bauen, oder auf `quarkus.langchain4j.openai.*` umstellen? Empfehlung:
   manuell bauen, Config bleibt stabil.
3. **Streaming-Detailtiefe:** LC liefert Partial Tool Calls – Live-Tool-Input-
   Anzeige beibehalten? (Ja, `onPartialToolCall` nutzen.)
4. **Native:** LC ist bereits im Native-Build; der neue Adapter braucht
   Reflection-Registrierung für LC-Typen (Quarkus-Extension hilft). Nach dem
   Umbau Native-Build + A2A/OIDC-Test wiederholen.

---

## 8. Referenzen

- `langchain4j.md` – bestehender Integrationsguide
- `engine/src/main/java/de/augmentia/oc/engine/runtime/provider/client/`
- `engine/src/main/java/de/augmentia/oc/engine/core/contract/provider/`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/ProviderProducer.java`
- LangChain4j: `ChatModel`, `StreamingChatModel`, `ChatRequest`, `ChatResponse`,
  `ToolSpecification`, `ToolExecutionResultMessage`, `ChatModelListener`
