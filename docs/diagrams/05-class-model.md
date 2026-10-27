# 05 – Class and Role Model

## Purpose

The diagram shows the central types and their most important relationships –
not every helper class. The stereotypes mark the role in the determinism
model; here they replace the color coding of the flow diagrams.

```mermaid
classDiagram
  direction LR

  class HttpApi {
    <<deterministic boundary>>
    +createSession()
    +prompt()
    +events()
  }

  class Engine {
    <<wiring + mixed loop>>
    +prompt()
    +runPrompt()
    +runSubagent()
  }

  class EventBus {
    <<deterministic>>
    +publishLive()
    +subscribeLive()
  }

  class AgentCatalogue {
    <<deterministic>>
    +resolve()
    +config()
  }

  class SessionService {
    <<deterministic>>
    +create()
    +enqueue()
    +deliver()
  }

  class Admission {
    <<deterministic>>
    +admit()
  }

  class Delivery {
    <<deterministic>>
    +deliver()
  }

  class ContextBuilder {
    <<deterministic>>
    +prepare()
  }

  class Step {
    <<mixed>>
    +run()
  }

  class Compaction {
    <<mixed>>
    +compact()
  }

  class ProviderAdapter {
    <<interface / adapter>>
    +stream()
  }

  class ToolRuntime {
    <<deterministic>>
    +execute()
  }

  class ToolSnapshot {
    <<deterministic>>
    +require()
  }

  class PermissionGate {
    <<port>>
    +assertAllowed()
  }

  class PermissionEngine {
    <<deterministic>>
    +assertAllowed()
    +reply()
  }

  class ToolRegistry {
    <<deterministic>>
    +register()
    +snapshot()
  }

  class SubagentRunner {
    <<interface / mixed>>
    +run()
  }

  class Journal {
    <<port>>
    +append()
    +history()
  }

  class Conversation {
    <<port>>
    +messages()
  }

  class ClaimStore {
    <<port>>
    +acquire()
    +release()
  }

  class FileSystem {
    <<port>>
    +read()
    +write()
  }

  class HttpTransport {
    <<port>>
    +postJson()
  }

  class ProcessRunner {
    <<port>>
    +run()
    +start()
  }

  class SqlJournal {
    <<adapter>>
  }

  class SqliteJournal {
    <<adapter>>
  }

  class NioFileSystem {
    <<adapter>>
  }

  class JdkHttpTransport {
    <<adapter>>
  }

  class JdkProcessRunner {
    <<adapter>>
  }

  class LlmProvider {
    <<llm / external>>
  }

  HttpApi --> Engine
  Engine --> EventBus
  Engine --> AgentCatalogue
  Engine --> SessionService
  Engine --> ContextBuilder
  Engine --> Step
  Engine --> ToolRuntime
  Engine --> ToolRegistry
  Engine --> SubagentRunner
  Engine --> Journal

  SessionService --> Admission
  SessionService --> Delivery
  SessionService --> Journal
  Delivery --> Journal

  ContextBuilder --> Conversation
  Step --> ProviderAdapter
  Step --> ToolRuntime
  ToolRuntime --> PermissionGate
  ToolRuntime --> ToolSnapshot
  ToolRegistry --> ToolSnapshot
  Engine --> PermissionGate
  PermissionGate ..> PermissionEngine : wiring

  ProviderAdapter --> LlmProvider

  SqlJournal ..|> Journal
  SqliteJournal ..|> Journal
  NioFileSystem ..|> FileSystem
  JdkHttpTransport ..|> HttpTransport
  JdkProcessRunner ..|> ProcessRunner

  note for Step "MIXED: LLM provides text/tool calls; execution and validation are deterministic"
  note for Engine "Wiring + prompt loop; currently no separate workflow orchestrator"
  note for PermissionGate "implemented as a lambda in the engine wiring; delegates to PermissionEngine"
  note for ProviderAdapter "translates request/stream deterministically; the remote LLM service is not deterministic"
```

## Legend

| Stereotype | Meaning |
|---|---|
| `<<deterministic>>` | fixed rules, no model decision |
| `<<mixed>>` | deterministic framework with an LLM call |
| `<<llm / external>>` | model or external service |
| `<<port>>` | interface implemented by an adapter |
| `<<adapter>>` | technical implementation of a port |
| `<<wiring + mixed loop>>` | wires everything together and contains the prompt loop |

## The most important contracts

| Contract | Meaning |
|---|---|
| `ProviderAdapter.stream(request)` | a stream of `ModelEvent`, no callback/future semantics |
| `ToolRuntime.execute(call, context)` | Decode → Permission → Execute → Encode/Bound |
| `PermissionEngine.assertAllowed(check)` | `Denied` is visible to the model, `Aborted` ends the step without model visibility |
| `Journal.append(draft)` | assigns the next sequence and writes the event; the spec additionally requires the projections in the same transaction, the current state separates them |
| `SessionWake.wake(session)` | decouples the session write side from execution |

## Why this split?

- `core` knows only values, ports and contracts.
- `runtime` orchestrates without knowing HTTP or Quarkus.
- `quarkus` wires the technology and provides REST/SSE.
- Adapters implement ports and are instantiated only by the wiring layer.

## Sources

- `engine/src/main/java/de/augmentia/oc/engine/core/port/`
- `engine/src/main/java/de/augmentia/oc/engine/core/contract/`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/`
- `docs/spec/java-packages.md`


## Planned security and MCP types

The following types are not yet present in the class model, but are planned
for the security/MCP work:

- `Principal`
- `PermissionEvaluation` with principal
- `CredentialStore` / `SqliteCredentials`
- `McpServerManager`
- `McpServerResource`
- `McpDiscoveryClient`
- `McpOAuth`
- `GuardrailRegistry`
- `ToolCallJournal`

These are intentionally listed separately so the current class diagram stays
accurate.
