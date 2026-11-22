# Plan: ACP (Agent Client Protocol) Adapter for QOC

**Status:** Partially implemented (2026-11-22) — Transport (AcpTransport, AcpServer, AcpStartup), session mapping (AcpSessionManager, AcpHandler with session/new/prompt/cancel/load/list/delete/close) implemented. Missing: AcpEventTranslator, AcpPermissionBridge, AcpElicitationBridge, session/update streaming, session/requestPermission, elicitation/create, advanced features (resume, fork, set_config_option, set_mode, authenticate), MCP passthrough, tests.
**Reference:** `oc/packages/cli/src/acp/` (OpenCode ACP reference implementation),
`oc/services/www/src/docs/content/acp.mdx` (protocol documentation)

## 1. Is ACP bidirectional?

**Yes.** ACP is based on JSON-RPC 2.0 over stdio (ndjson). Both sides can send
Requests and Notifications:

- **Client → Agent:** `initialize`, `session/new`, `session/prompt`, `session/cancel`,
  `session/load`, `session/list`, `session/delete`, `session/resume`, `session/fork`,
  `session/close`, `session/set_config_option`, `session/set_mode`, `authenticate`
- **Agent → Client:** `session/update` (Notifications), `session/requestPermission`,
  `elicitation/create`, `extNotification`

This is bidirectional. QOC must implement **both sides**:
- **Server side (Agent):** receives Client requests, responds with QOC data
- **Client side:** sends Agent→Client notifications/requests (sessionUpdate, requestPermission, elicitation)

## 2. Architecture decision

QOC already has an HTTP/SSE API (`/api/session`, `/api/run`). ACP is an
alternative transport protocol. The ACP layer is an **adapter** on top of
QOC's existing engine API — no new business logic.

```
┌─────────────────────────────────────┐
│  Editor (Zed, VS Code, ...)         │
│  ── JSON-RPC over stdio ──          │
└──────────────┬──────────────────────┘
               │ ndjson (stdin/stdout)
┌──────────────┴──────────────────────┐
│  QOC ACP Adapter (new)              │
│  - AcpServer: JSON-RPC Dispatcher   │
│  - AcpSessionManager: Session-Mapping│
│  - AcpTransport: stdio read/write    │
└──────────────┬──────────────────────┘
               │ QOC Engine API
┌──────────────┴──────────────────────┐
│  QOC Engine (existing)              │
│  - Session, Agent, Run, Tools        │
└─────────────────────────────────────┘
```


## Implementation Status (2026-11-22)

### Implemented
- **AcpTransport** — ndjson read/write over stdin/stdout
- **AcpServer** — JSON-RPC 2.0 dispatcher with handler registration
- **AcpSessionManager** — ACP-Session ↔ QOC-Session mapping
- **AcpHandler** — session/new, session/prompt, session/cancel, session/load, session/list, session/delete, session/close
- **AcpStartup** — startup wiring
- **AcpConfig** — configuration

### Not implemented
- **AcpEventTranslator** — QOC Live-Sink → ACP session/update notifications (streaming)
- **AcpPermissionBridge** — QOC PendingApproval → ACP requestPermission
- **AcpElicitationBridge** — QOC Question-Tool → ACP elicitation/create
- **session/update streaming** — text/tool/result notifications over stdio
- **Advanced features** — session/resume, session/fork, session/set_config_option, session/set_mode, authenticate (OIDC), MCP passthrough
- **Tests** — no unit or integration tests for ACP

### Verdict
Core transport and session CRUD implemented (~30% of plan). Event translation, permission/elicitation bridges, advanced features, and tests are missing.

## 3. ACP method mapping (Client → Agent)

| ACP method | QOC equivalent | Status |
|---|---|---|
| `initialize` | Health/Config | Implement new |
| `authenticate` | OIDC (optional) | Implement new |
| `session/new` | `POST /api/session` | Mapping needed |
| `session/prompt` | `POST /api/run` | Mapping needed |
| `session/cancel` | Abort-Run | Implement new |
| `session/load` | `GET /api/session/{id}` | Mapping needed |
| `session/list` | `GET /api/session` (or store.list) | Mapping needed |
| `session/delete` | `DELETE /api/session/{id}` | Mapping needed |
| `session/resume` | `POST /api/workflows/runs/{id}/resume` | Mapping needed |
| `session/close` | Session cleanup | Implement new |
| `session/fork` | Fork-Session | Implement new |
| `session/set_config_option` | Agent-Config update | Mapping needed |
| `session/set_mode` | Plan/Build-Mode switch | Mapping needed |

## 4. Agent → Client notifications/requests

| ACP (Agent→Client) | QOC equivalent | Status |
|---|---|---|
| `session/update` (TextDelta, ToolCall, etc.) | SSE-Events / Live-Sink | Mapping needed |
| `session/requestPermission` | HITL-Approval (PendingApprovalStore) | Mapping needed |
| `elicitation/create` | Question-Tool (web UI modal) | Mapping needed |
| `extNotification` | Custom events | Optional |

## 5. Components (to be created)

```
quarkus/src/main/java/de/augmentia/oc/engine/acp/
├── AcpServer.java              # JSON-RPC Dispatcher (stdin/stdout)
├── AcpTransport.java           # ndjson read/write over stdin/stdout
├── AcpSessionManager.java      # ACP-Session ↔ QOC-Session Mapping
├── AcpMethodHandler.java       # Method-Dispatch (initialize, session/*, etc.)
├── AcpEventTranslator.java     # QOC-Events → ACP session/update Notifications
├── AcpPermissionBridge.java    # QOC PendingApproval → ACP requestPermission
└── AcpElicitationBridge.java   # QOC Question-Tool → ACP elicitation/create
```

## 6. Implementation steps

### Phase 1: Transport + Dispatcher (2 days)
1. `AcpTransport`: stdin/stdout ndjson reader/writer (java.io)
2. `AcpServer`: JSON-RPC 2.0 Dispatcher (parse request, dispatch, write response)
3. `Initialize`-Handler: capabilities, version, auth methods
4. CLI-Entry: `qoc acp` (or `java -jar qoc.jar acp`) starts ACP mode

### Phase 2: Session-Mapping (2 days)
5. `AcpSessionManager`: ACP-Session-ID ↔ QOC-Session-ID
6. `session/new` → QOC `createSession`
7. `session/prompt` → QOC `run` (with streaming via session/update)
8. `session/cancel` → QOC abort
9. `session/load`, `session/list`, `session/delete`

### Phase 3: Event-Translation (2 days)
10. `AcpEventTranslator`: QOC Live-Sink → ACP `session/update` Notifications
    - TextDelta → `session/update` (text content)
    - ToolCall → `session/update` (tool call status)
    - ToolResult → `session/update` (tool result)
11. Streaming over stdio (ndjson)

### Phase 4: Permission + Elicitation (2 days)
12. `AcpPermissionBridge`: QOC PendingApproval → ACP `requestPermission`
    - QOC's HITL-Approval → ACP permission request
    - Response → QOC approval decision
13. `AcpElicitationBridge`: QOC Question-Tool → ACP `elicitation/create`

### Phase 5: Advanced Features (2 days)
14. `session/resume`, `session/fork`, `session/close`
15. `session/set_config_option`, `session/set_mode`
16. `authenticate` (OIDC)
17. MCP-Server-Passthrough (ACP's `mcpServers` in `session/new`)

### Phase 6: Tests (2 days)
18. Unit-Tests: JSON-RPC parsing, method dispatch, session mapping
19. Integration-Tests: End-to-End ACP-Session over stdio
20. Compatibility-Tests against ACP-SDK (TypeScript)

## 7. Dependencies

- No new backend dependencies (JSON-RPC parsing with Jackson, already available)
- CLI-Entry-Point: either a new Main argument or a separate `acp` subcommand
- MCP-Server-Passthrough: ACP's `mcpServers` config must be mapped to QOC's McpServerConfig

## 8. Risks

- **Protocol versioning:** ACP continues to evolve. Version check in `initialize` needed.
- **Streaming performance:** ndjson over stdio can be slow for large outputs. Buffering recommended.
- **Bidirectional deadlocks:** Agent→Client requests (permission) block the agent thread. Async handling needed.
- **MCP-Passthrough:** ACP clients can configure MCP servers. QOC must register these dynamically.

## 9. Effort

| Phase | Effort |
|---|---|
| Phase 1: Transport + Dispatcher | 2 days |
| Phase 2: Session-Mapping | 2 days |
| Phase 3: Event-Translation | 2 days |
| Phase 4: Permission + Elicitation | 2 days |
| Phase 5: Advanced Features | 2 days |
| Phase 6: Tests | 2 days |
| **Total** | **12 days** |

## 10. Open questions

1. Should ACP be implemented as a standalone mode (`qoc acp`) or as an HTTP endpoint (`/api/acp`)?
   → Recommendation: Standalone mode (stdio), analogous to OpenCode. HTTP endpoint as fallback.
2. Should QOC's existing REST API remain alongside ACP?
   → Recommendation: Yes. ACP is an additional transport, not a replacement.
3. How is authentication (OIDC) handled in ACP mode?
   → Recommendation: `authenticate` method with Device-Code-Flow (analogous to OpenCode).
