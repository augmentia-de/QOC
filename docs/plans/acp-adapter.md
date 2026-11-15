# Plan: ACP (Agent Client Protocol) Adapter für QOC

**Status:** Plan (2026-11-15)
**Bezug:** `oc/packages/cli/src/acp/` (OpenCode ACP-Referenzimplementierung),
`oc/services/www/src/docs/content/acp.mdx` (Protokoll-Dokumentation)

## 1. Ist ACP bidirektional?

**Ja.** ACP basiert auf JSON-RPC 2.0 über stdio (ndjson). Beide Seiten können
Requests und Notifications senden:

- **Client → Agent:** `initialize`, `session/new`, `session/prompt`, `session/cancel`,
  `session/load`, `session/list`, `session/delete`, `session/resume`, `session/fork`,
  `session/close`, `session/set_config_option`, `session/set_mode`, `authenticate`
- **Agent → Client:** `session/update` (Notifications), `session/requestPermission`,
  `elicitation/create`, `extNotification`

Das ist bidirektional. QOC muss **beide Seiten** implementieren:
- **Server-Seite (Agent):** empfängt Client-Requests, antwortet mit QOC-Daten
- **Client-Seite:** sendet Agent→Client-Notifications/Requests (sessionUpdate, requestPermission, elicitation)

## 2. Architektur-Entscheidung

QOC hat bereits eine HTTP/SSE-API (`/api/session`, `/api/run`). ACP ist ein
alternatives Transport-Protokoll. Die ACP-Schicht ist ein **Adapter** über
QOC's bestehende Engine-API — keine neue Business-Logik.

```
┌─────────────────────────────────────┐
│  Editor (Zed, VS Code, ...)         │
│  ── JSON-RPC over stdio ──          │
└──────────────┬──────────────────────┘
               │ ndjson (stdin/stdout)
┌──────────────┴──────────────────────┐
│  QOC ACP Adapter (neu)              │
│  - AcpServer: JSON-RPC Dispatcher   │
│  - AcpSessionManager: Session-Mapping│
│  - AcpTransport: stdio read/write    │
└──────────────┬──────────────────────┘
               │ QOC Engine API
┌──────────────┴──────────────────────┐
│  QOC Engine (bestehend)             │
│  - Session, Agent, Run, Tools       │
└─────────────────────────────────────┘
```

## 3. ACP-Methoden-Mapping (Client → Agent)

| ACP-Methode | QOC-Äquivalent | Status |
|---|---|---|
| `initialize` | Health/Config | Neu implementieren |
| `authenticate` | OIDC (optional) | Neu implementieren |
| `session/new` | `POST /api/session` | Mapping nötig |
| `session/prompt` | `POST /api/run` | Mapping nötig |
| `session/cancel` | Abort-Run | Neu implementieren |
| `session/load` | `GET /api/session/{id}` | Mapping nötig |
| `session/list` | `GET /api/session` (oder store.list) | Mapping nötig |
| `session/delete` | `DELETE /api/session/{id}` | Mapping nötig |
| `session/resume` | `POST /api/workflows/runs/{id}/resume` | Mapping nötig |
| `session/close` | Session cleanup | Neu implementieren |
| `session/fork` | Fork-Session | Neu implementieren |
| `session/set_config_option` | Agent-Config update | Mapping nötig |
| `session/set_mode` | Plan/Build-Mode switch | Mapping nötig |

## 4. Agent → Client Notifications/Requests

| ACP (Agent→Client) | QOC-Äquivalent | Status |
|---|---|---|
| `session/update` (TextDelta, ToolCall, etc.) | SSE-Events / Live-Sink | Mapping nötig |
| `session/requestPermission` | HITL-Approval (PendingApprovalStore) | Mapping nötig |
| `elicitation/create` | Question-Tool (web UI modal) | Mapping nötig |
| `extNotification` | Custom events | Optional |

## 5. Komponenten (neu zu erstellen)

```
quarkus/src/main/java/de/augmentia/oc/engine/acp/
├── AcpServer.java              # JSON-RPC Dispatcher (stdin/stdout)
├── AcpTransport.java           # ndjson read/write über stdin/stdout
├── AcpSessionManager.java      # ACP-Session ↔ QOC-Session Mapping
├── AcpMethodHandler.java       # Method-Dispatch (initialize, session/*, etc.)
├── AcpEventTranslator.java     # QOC-Events → ACP session/update Notifications
├── AcpPermissionBridge.java    # QOC PendingApproval → ACP requestPermission
└── AcpElicitationBridge.java   # QOC Question-Tool → ACP elicitation/create
```

## 6. Implementierungsschritte

### Phase 1: Transport + Dispatcher (2 Tage)
1. `AcpTransport`: stdin/stdout ndjson reader/writer (java.io)
2. `AcpServer`: JSON-RPC 2.0 Dispatcher (parse request, dispatch, write response)
3. `Initialize`-Handler: capabilities, version, auth methods
4. CLI-Entry: `qoc acp` (oder `java -jar qoc.jar acp`) startet ACP-Modus

### Phase 2: Session-Mapping (2 Tage)
5. `AcpSessionManager`: ACP-Session-ID ↔ QOC-Session-ID
6. `session/new` → QOC `createSession`
7. `session/prompt` → QOC `run` (mit Streaming über session/update)
8. `session/cancel` → QOC abort
9. `session/load`, `session/list`, `session/delete`

### Phase 3: Event-Translation (2 Tage)
10. `AcpEventTranslator`: QOC Live-Sink → ACP `session/update` Notifications
    - TextDelta → `session/update` (text content)
    - ToolCall → `session/update` (tool call status)
    - ToolResult → `session/update` (tool result)
11. Streaming über stdio (ndjson)

### Phase 4: Permission + Elicitation (2 Tage)
12. `AcpPermissionBridge`: QOC PendingApproval → ACP `requestPermission`
    - QOC's HITL-Approval → ACP permission request
    - Antwort → QOC approval decision
13. `AcpElicitationBridge`: QOC Question-Tool → ACP `elicitation/create`

### Phase 5: Advanced Features (2 Tage)
14. `session/resume`, `session/fork`, `session/close`
15. `session/set_config_option`, `session/set_mode`
16. `authenticate` (OIDC)
17. MCP-Server-Passthrough (ACP's `mcpServers` in `session/new`)

### Phase 6: Tests (2 Tage)
18. Unit-Tests: JSON-RPC parsing, method dispatch, session mapping
19. Integration-Tests: End-to-End ACP-Session über stdio
20. Compatibility-Tests gegen ACP-SDK (TypeScript)

## 7. Abhängigkeiten

- Keine neuen Backend-Dependencies (JSON-RPC parsing mit Jackson, bereits vorhanden)
- CLI-Entry-Point: entweder neues Main-Argument oder separates `acp`-Subcommand
- MCP-Server-Passthrough: ACP's `mcpServers` config muss auf QOC's McpServerConfig gemappt werden

## 8. Risiken

- **Protokoll-Versionierung:** ACP entwickelt sich weiter. Version-Check in `initialize` nötig.
- **Streaming-Performance:** ndjson über stdio kann bei großen Outputs langsam sein. Buffering empfohlen.
- **Bidirektionale Deadlocks:** Agent→Client-Requests (permission) blockieren den Agent-Thread. Async-Handling nötig.
- **MCP-Passthrough:** ACP-Clients können MCP-Server konfigurieren. QOC muss diese dynamisch registrieren.

## 9. Aufwand

| Phase | Aufwand |
|---|---|
| Phase 1: Transport + Dispatcher | 2 Tage |
| Phase 2: Session-Mapping | 2 Tage |
| Phase 3: Event-Translation | 2 Tage |
| Phase 4: Permission + Elicitation | 2 Tage |
| Phase 5: Advanced Features | 2 Tage |
| Phase 6: Tests | 2 Tage |
| **Gesamt** | **12 Tage** |

## 10. Offene Fragen

1. Soll ACP als eigenständiger Modus (`qoc acp`) oder als HTTP-Endpoint (`/api/acp`) implementiert werden?
   → Empfehlung: Eigenständiger Modus (stdio), analog zu OpenCode. HTTP-Endpoint als Fallback.
2. Soll QOC's bestehende REST-API parallel zu ACP bestehen bleiben?
   → Empfehlung: Ja. ACP ist ein zusätzlicher Transport, kein Ersatz.
3. Wie wird die Authentifizierung (OIDC) in ACP-Modus gehandhabt?
   → Empfehlung: `authenticate`-Methode mit Device-Code-Flow (analog OpenCode).