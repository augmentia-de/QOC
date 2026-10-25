# 01 – System Context

## Purpose

The diagram shows QOC as a whole: client, HTTP layer, engine, LLM, tools,
external services and persistence. It makes visible where deterministic logic
lives and where a model or an external service is involved.

```mermaid
flowchart LR
  User["User / Browser / API-Client"]:::ext
  Edge["nginx / Reverse Proxy<br/>optional: TLS, Basic Auth"]:::ext
  Api["Quarkus HttpApi<br/>REST · SSE · BasicAuthFilter"]:::mixed
  Engine["Engine<br/>Wiring · Prompt-Loop · Agent Selection"]:::mixed
  Session["Session Core<br/>Admission · Delivery · Retention"]:::det
  Exec["Prompt Execution<br/>ContextBuilder · Step"]:::mixed
  Provider["ProviderAdapter<br/>HTTP/SSE Translation"]:::mixed
  Llm["LLM Provider<br/>OpenAI-compatible"]:::llm
  Tools["ToolRuntime<br/>Decode · Authorize · Execute · Bound"]:::det
  Perm["PermissionEngine<br/>Rules · Ask · Deny"]:::det
  ToolSet["Tools<br/>FS · Shell · Web · MCP · Subagent"]:::mixed
  Workspace[("Workspace<br/>Files")]:::store
  Mcp["MCP Server"]:::ext
  Web["Firecrawl / WebFetch"]:::ext
  Child["Subagent<br/>Child Session"]:::llm
  Store[("SQLite / PostgreSQL<br/>Event Log · Projections")]:::store
  Bus["EventBus<br/>Live Events"]:::det
  Git["Git / Worktree<br/>only if present"]:::ext

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

## What is deterministic?

| Part | Why |
|---|---|
| `HttpApi` validation and error codes | fixed rules, no model involvement |
| `SessionService`, `Admission`, `Delivery` | fixed order, idempotency, inbox rules |
| `Journal` / projections | append-only, sequence, transaction |
| `PermissionEngine` | rules, wildcards, deny/ask/allow |
| `ToolRuntime` | snapshot, schema-decode, authorizer, bounding |
| `ContextBuilder` | fixed order, history folding, budget |
| `SessionRetention` | timestamps and configured deletion rule |

## What is not deterministic?

| Part | Why |
|---|---|
| Model text output | LLM-generated |
| Selection of tool calls | LLM decides which tools to call |
| Title generation | LLM-generated |
| Compaction summary | LLM-generated (the trigger is deterministic) |
| Subagent result | separate LLM run in a child session |
| MCP, Firecrawl, WebFetch responses | external services |

## Important consequence

The **Step framework** is deterministic: it reads the stream completely,
validates it, executes requested tools in a defined order and produces exactly
one terminal result. **Which** tools are called, however, is decided by the
LLM. QOC is therefore today a deterministically framed agent – not yet a
deterministic workflow orchestrator.

## Sources

- `quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`
- `quarkus/src/main/java/de/augmentia/oc/engine/http/EventBus.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/step/Step.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/permission/engine/PermissionEngine.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/session/core/`
- `engine/src/main/java/de/augmentia/oc/engine/adapter/`