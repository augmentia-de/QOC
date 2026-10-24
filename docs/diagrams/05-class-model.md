# 05 – Klassen- und Rollenmodell

## Zweck

Das Diagramm zeigt die zentralen Typen und ihre wichtigsten Beziehungen –
nicht jede Hilfsklasse. Die Stereotype markieren die Rolle im
Determinismus-Modell; sie ersetzen hier die Farbkennzeichnung der
Flussdiagramme.

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

  note for Step "MIXED: LLM liefert Text/Tool-Calls; Ausführung und Validierung sind deterministisch"
  note for Engine "Wiring + Prompt-Loop; aktuell kein separater Workflow-Orchestrator"
  note for PermissionGate "im Engine-Wiring als Lambda implementiert; delegiert an PermissionEngine"
  note for ProviderAdapter "übersetzt Request/Stream deterministisch; der entfernte LLM-Dienst ist nicht deterministisch"
```

## Legende

| Stereotyp | Bedeutung |
|---|---|
| `<<deterministic>>` | feste Regeln, keine Modellentscheidung |
| `<<mixed>>` | deterministischer Rahmen mit LLM-Aufruf |
| `<<llm / external>>` | Modell oder externer Dienst |
| `<<port>>` | Schnittstelle, die von einem Adapter erfüllt wird |
| `<<adapter>>` | technische Implementierung eines Ports |
| `<<wiring + mixed loop>>` | verdrahtet alles und enthält den Prompt-Loop |

## Die wichtigsten Verträge

| Vertrag | Bedeutung |
|---|---|
| `ProviderAdapter.stream(request)` | ein Stream von `ModelEvent`, keine Callback-/Future-Semantik |
| `ToolRuntime.execute(call, context)` | Decode → Permission → Execute → Encode/Bound |
| `PermissionEngine.assertAllowed(check)` | `Denied` ist modellseitig sichtbar, `Aborted` beendet den Step ohne Modellsicht |
| `Journal.append(draft)` | vergibt die nächste Sequenz und schreibt das Event; die Spec fordert zusätzlich die Projektionen in derselben Transaktion, der Ist-Stand trennt sie |
| `SessionWake.wake(session)` | entkoppelt Session-Schreibseite von der Ausführung |

## Warum diese Aufteilung?

- `core` kennt nur Werte, Ports und Verträge.
- `runtime` orchestriert, ohne HTTP oder Quarkus zu kennen.
- `quarkus` verdrahtet die Technik und stellt REST/SSE bereit.
- Adapter implementieren Ports und werden nur von der Wiring-Schicht
  instanziiert.

## Quellen

- `qoc/engine/src/main/java/de/augmentia/oc/engine/core/port/`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/core/contract/`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/`
- `qoc/docs/spec/java-packages.md`
