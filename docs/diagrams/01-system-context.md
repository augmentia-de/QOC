# 01 – Systemkontext

## Zweck

Das Diagramm zeigt QOC als Ganzes: Client, HTTP-Schicht, Engine, LLM, Tools,
externe Dienste und Persistenz. Es macht sichtbar, wo deterministische Logik
liegt und wo ein Modell oder ein externer Dienst beteiligt ist.

```mermaid
flowchart LR
  User["User / Browser / API-Client"]:::ext
  Edge["nginx / Reverse Proxy<br/>optional: TLS, Basic Auth"]:::ext
  Api["Quarkus HttpApi<br/>REST · SSE · BasicAuthFilter"]:::mixed
  Engine["Engine<br/>Wiring · Prompt-Loop · Agent-Auswahl"]:::mixed
  Session["Session Core<br/>Admission · Delivery · Retention"]:::det
  Exec["Prompt-Ausführung<br/>ContextBuilder · Step"]:::mixed
  Provider["ProviderAdapter<br/>HTTP/SSE-Übersetzung"]:::mixed
  Llm["LLM-Provider<br/>OpenAI-kompatibel"]:::llm
  Tools["ToolRuntime<br/>Decode · Authorize · Execute · Bound"]:::det
  Perm["PermissionEngine<br/>Regeln · Ask · Deny"]:::det
  ToolSet["Tools<br/>FS · Shell · Web · MCP · Subagent"]:::mixed
  Workspace[("Workspace<br/>Dateien")]:::store
  Mcp["MCP-Server"]:::ext
  Web["Firecrawl / WebFetch"]:::ext
  Child["Subagent<br/>Child-Session"]:::llm
  Store[("SQLite / PostgreSQL<br/>Event-Log · Projektionen")]:::store
  Bus["EventBus<br/>Live-Events"]:::det
  Git["Git / Worktree<br/>nur wenn vorhanden"]:::ext

  User -->|"HTTP"| Edge
  Edge -->|"Proxy"| Api
  Api --> Engine
  Engine --> Session
  Engine --> Exec
  Exec --> Provider
  Provider --> Llm
  Exec --> Tools
  Tools --> Perm
  Tools --> ToolSet
  ToolSet --> Workspace
  ToolSet --> Mcp
  ToolSet --> Web
  ToolSet --> Child
  Session --> Store
  Engine --> Store
  Engine --> Bus
  Bus --> Api
  ToolSet -.->|"optional"| Git

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef llm fill:#ffe0b2,stroke:#e65100,stroke-width:1px,color:#111;
  classDef mixed fill:#fff9c4,stroke:#f9a825,stroke-width:1px,color:#111;
  classDef ext fill:#eceff1,stroke:#546e7a,stroke-width:1px,color:#111;
  classDef store fill:#e3f2fd,stroke:#1565c0,stroke-width:1px,color:#111;
```

## Was ist deterministisch?

| Teil | Warum |
|---|---|
| `HttpApi`-Validierung und Fehlercodes | feste Regeln, keine Modellbeteiligung |
| `SessionService`, `Admission`, `Delivery` | feste Reihenfolge, Idempotenz, Inbox-Regeln |
| `Journal` / Projektionen | append-only, Sequenz, Transaktion |
| `PermissionEngine` | Regeln, Wildcards, deny/ask/allow |
| `ToolRuntime` | Snapshot, Schema-Decode, Authorizer, Bounding |
| `ContextBuilder` | feste Reihenfolge, History-Folding, Budget |
| `SessionRetention` | Zeitstempel und konfigurierte Löschregel |

## Was ist nicht deterministisch?

| Teil | Warum |
|---|---|
| Text-Ausgabe des Modells | LLM-generiert |
| Auswahl der Tool-Calls | LLM entscheidet, welche Tools es aufruft |
| Titel-Generierung | LLM-generiert |
| Compaction-Zusammenfassung | LLM-generiert (der Trigger ist deterministisch) |
| Subagent-Ergebnis | eigener LLM-Lauf in einer Child-Session |
| MCP-, Firecrawl-, WebFetch-Antworten | externe Dienste |

## Wichtige Konsequenz

Der **Step-Rahmen** ist deterministisch: Er liest den Stream vollständig,
validiert ihn, führt angeforderte Tools in definierter Reihenfolge aus und
erzeugt genau ein terminales Ergebnis. **Welche** Tools aufgerufen werden,
entscheidet jedoch das LLM. Deshalb ist QOC heute ein deterministisch
gerahmter Agent – noch kein deterministischer Workflow-Orchestrator.

## Quellen

- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`
- `qoc/quarkus/src/main/java/de/augmentia/oc/engine/http/EventBus.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/permission/engine/PermissionEngine.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/session/core/`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/adapter/`
