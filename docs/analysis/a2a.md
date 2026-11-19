# A2A Integration Analysis for QOC

**Status:** 2026-10-28  
**Source:** [Quarkus Blog - Getting Started with Quarkus and A2A Java SDK 0.3.0](https://quarkus.io/blog/quarkus-a2a-java-0-3-0-alpha-release/)  
**Goal:** Outline the possibilities for QOC to use A2A (Agent-to-Agent)

## 1. What is A2A?

The [Agent2Agent (A2A) Protocol](https://a2a-protocol.org/latest/specification/) is an open standard (originally from Google, now Linux Foundation) for letting AI agents communicate independent of framework/language/vendor. Version 0.3.0 brings, among other things:

- **gRPC transport** in addition to JSON-RPC 2.0
- **HTTP+JSON/REST** is expected to follow soon
- Reworked client API via `ClientBuilder`
- Better multi-transport support via `AgentCard.additionalInterfaces`
- Unified event processing on the client side

## 2. A2A Java SDK 0.3.0 (Quarkus)

### Server side
Dependencies (group `io.github.a2asdk`, temporary):

| Transport | Dependency | Note |
|---|---|---|
| JSON-RPC 2.0 | `a2a-java-sdk-reference-jsonrpc` | Provides HTTP endpoint for JSON-RPC, AgentCard under `/.well-known/agent-card.json` |
| gRPC | `a2a-java-sdk-reference-grpc` | Provides gRPC service |

Both can be used in parallel. The `AgentCard` must contain a primary endpoint + `preferredTransport` + `additionalInterfaces` for all supported transports (`AgentInterface` per transport).

### Client side
```xml
<dependency>
  <groupId>io.github.a2asdk</groupId>
  <artifactId>a2a-java-sdk-client</artifactId>
</dependency>
<!-- optional for gRPC -->
<dependency>
  <groupId>io.github.a2asdk</groupId>
  <artifactId>a2a-java-sdk-client-transport-grpc</artifactId>
</dependency>
```

`ClientBuilder` selects the transport based on `AgentCard` + client configuration. `ClientConfig` controls `acceptedOutputModes`, streaming, etc. Events are received via `BiConsumer<ClientEvent, AgentCard>` (`MessageEvent`, `TaskEvent`, `TaskUpdateEvent`). Streaming errors are handled via a separate handler.

## 3. QOC architecture overview (relevant to A2A)

From `README.md` and code:

- **Modules**: `engine/` (core/runtime/adapter, pure Java), `quarkus/` (HTTP/SSE, CDI, wiring), `docs/` (spec is source of truth)
- **HTTP API**: REST/SSE under `/api/*` (see `HttpApi.java`) – sessions, prompts, events (SSE), permissions, questions, tools/files, MCP, agents/skills/models
- **Session engine**: inbox (`STEER`/`QUEUE`), execution, compaction/revert, parallel tool calls, subagents
- **Tools**: registry, permission enforcement, many builtins (fs, shell, web, skill, subagent, todo, question, worktree, MCP)
- **Provider**: via engine/ports, modelled as provider adapter
- **MCP**: already integrated (server list, status, OAuth flow)
- **Security**: auth modes, principal propagation, trusted proxy, webhook guardrails, audit

Important points for A2A:
- QOC already has its own session/task model and its own HTTP/SSE API
- Agent definitions exist (`/api/agent`)
- Tools/MCP are present
- Quarkus is already in use

## 4. Integration options for QOC

### 4.1 QOC as A2A server (exposed)
Make QOC agents accessible to other A2A clients.

**Goals:**
- External agents (e.g. other frameworks) can call QOC via A2A
- Interoperability without a QOC-specific client SDK
- Multi-transport (JSON-RPC + gRPC)

**Approach:**
1. **AgentCard production**: generate an `AgentCard` for each QOC agent (or a configurable set). Mapping from QOC agent definition (`/api/agent`) + skills/capabilities.
2. **A2A server endpoints**: reference the Quarkus module/extension for the A2A server. Automatically expose `/.well-known/agent-card.json`.
3. **Task/message mapping**: map A2A `Task`/`Message`/`Part` to QOC session + prompt. QOC sessions can be associated with A2A tasks.
4. **Streaming**: map A2A streaming events to the QOC SSE/event stream or use the A2A transport directly.
5. **AuthN/Z**: validate A2A requests against QOC security (principal/permissions).

**Pros:** interoperability, ecosystem access  
**Effort:** medium-high (mapping A2A<->QOC domain, task lifecycle)

### 4.2 QOC as A2A client (consumer)
QOC can call other A2A servers and use their capabilities as tools.

**Goals:**
- Integrate external A2A agents as tools in QOC sessions
- Agent handoffs / delegation
- Hybrid multi-agent workflows

**Approach:**
1. **Client integration**: add `a2a-java-sdk-client` + optionally the gRPC transport to `quarkus/`
2. **A2A tool factory**: generate tools from an `AgentCard` (e.g. `a2a.<agentId>.<skillId>`). Map tool input/output to A2A message/task
3. **Discovery**: configuration of A2A endpoints (URLs) in QOC config/settings
4. **Task management**: manage the A2A client (`Client`), track tasks, react to updates
5. **Permissions**: map calls to external A2A agents through QOC permissions
6. **Auth**: tokens/credentials for external A2A servers (analogous to MCP OAuth)

**Pros:** very practical, uses existing tool system  
**Effort:** medium (tool adapter, discovery, config)

### 4.3 Hybrid: A2A as transport for QOC MCP/tools
A2A could complement or partially replace MCP, but MCP is already strongly integrated. The more likely scenario is **complementary**: A2A for agent-to-agent, MCP for tool-to-server.

### 4.4 Subagent use over A2A
QOC already has subagents. A2A could enable remote subagents (other processes/services).

## 5. Concrete implementation plan for QOC

### Phase 0: Preparation & analysis (completed)
- [x] Read the A2A spec (Task/Message/Part, AgentCard, transports, streaming)
- [x] Review the A2A Java SDK API
- [x] Understand the QOC tool contract & permission system
- [x] Decision: client first, then server

### Phase 1: A2A client as QOC tool (implemented)
External A2A agents are registered as QOC tools.

**Implementation:**
1. **Dependencies**: `org.a2aproject.sdk:a2a-java-sdk-client:1.4.0.Final`
   (optionally `a2a-java-sdk-client-transport-grpc`).
2. **Config**: `engine.a2a.urls` (comma-separated) and
   `engine.a2a.endpoints` (`url=...|id=...|tool=...|enabled=true;`).
3. **Tool provider**: `runtime/tool/a2a/A2AToolProvider` loads AgentCards and
   builds one tool per endpoint.
4. **Tool implementation**: `A2ATool` maps JSON input to an A2A message,
   calls the agent synchronously and maps task/message responses to
   `ToolResult`.
5. **Registry integration**: the tools go through the same registry,
   permission and hook chain as builtins.
6. **Egress**: host/IP allowlists and TLS checks also apply to A2A targets.

### Phase 2: A2A server (QOC exposed) (implemented)
QOC agents are offered as an A2A agent. Transport is JSON-RPC.

**Implementation:**
1. **Dependencies**: `a2a-java-sdk-reference-jsonrpc` and
   `a2a-java-sdk-client` at `1.4.0.Final`. The SDK's own CDI beans
   (`TaskStore`, `QueueManager`, `DefaultRequestHandler`,
   `MainEventBusProcessor`, `A2AServerRoutes`) are reused.
2. **AgentCard producer**: `A2AAgentCardProducer` builds the card from
   `AgentCatalogue`; every visible QOC agent becomes a skill. The card
   declares JSON-RPC for protocol versions 1.0 and 0.3.
3. **Agent executor**: `QocAgentExecutor` translates A2A tasks into
   QOC sessions and prompts and returns the result as a text artifact.
4. **Session mapping**: `A2ASessionRegistry` maps `contextId` (or `taskId`
   without a context) to a QOC session, LRU-capped at 1000 contexts.
5. **Streaming**: the card reports `streaming=true`; execution is currently
   blocking and returns a final artifact. SSE fine-tuning to follow.
6. **Auth**: `A2AAuthenticationMechanism` supplies the Vert.x routes with a
   Quarkus identity (none/basic/proxy; OIDC defers to the OIDC module).
   `A2ATaskAuthorizationProvider` enforces task ownership and is fail-closed.
7. **Opt-in**: `engine.a2a.server.enabled=false` is the default; a route gate
   otherwise answers the A2A paths with `404`.
8. **Configuration**:
   - `engine.a2a.server.enabled`
   - `engine.a2a.server.name`
   - `engine.a2a.server.description`
   - `engine.a2a.server.version`
   - `engine.a2a.server.base-url` (public URL in the AgentCard)
   - `engine.a2a.server.agent` (`none` = primary agent)

### Phase 3: Discovery & UI (implemented)

- `GET /api/a2a/agents` lists all configured external agents with
  effective configuration, AgentCard metadata (name, version, protocol,
  skills), tool names and status (`ok`, `error`, `disabled`).
- `POST /api/a2a/agents/refresh` (or `GET ...?refresh=true`) invalidates the
  card cache and reloads the cards.
- **AgentCard caching**: shared TTL cache for tool registration and
  discovery (`engine.a2a.cache.ttl-seconds`, default 300, `0` disables,
  maximum 86400). Failed fetches are not cached; the next call retries.
  The cache is process-local and thread-safe.
- **UI**: the "A2A" button in the web UI opens a read-only overview of the
  configured agents including status, skills and refresh.
- **Configuration** remains file/environment-based (`engine.a2a.urls`,
  `engine.a2a.endpoints`). Runtime editing is intentionally not included,
  because it would require re-registering the tool snapshots.

## 6. Evaluation against QOC principles

QOC follows: pure Java core, ports/adapters, spec as source of truth, ArchUnit, tests.

| Aspect | Rating | Note |
|---|---|---|
| **Core freedom** | Good | A2A client/server only in `runtime.tool` or `adapter.a2a` / `quarkus/`, not in `core` |
| **Tool system** | Very good | Fits perfectly - A2A agents are treated like tools |
| **Permissions** | Good | Already present, extensible |
| **MCP compatibility** | Good | Complementary, not conflicting |
| **Transports** | Good | JSON-RPC/gRPC supported via Quarkus |
| **Client-first effort** | Low-medium | Immediately usable, no own server needed |

## 7. Recommendations

**Recommendation: client-first, server opt-in (implemented)**

Reasons:
1. **Minimally invasive**: client is only a tool adapter, no change to core session logic
2. **Immediate benefit**: QOC can delegate to other agents
3. **Uses existing design**: tool registry, permissions, execution chain
4. **Testable**: testable against the own server and external A2A agents
5. **Server stays opt-in**: no unwanted exposure

**Next concrete steps:**
1. Connect push notifications to QOC events (optional, low priority)
2. Egress gating via custom HTTP client at transport level (currently application-level guard)
3. Tenant support (`/{tenant}`) — planned separately
4. Automated interop suite against external reference agents

## 8. Open questions

- [x] SDK version finalized (`org.a2aproject.sdk:1.4.0.Final`)
- [x] Skill-to-tool mapping: one tool per agent, description enriched with skills, optional `skill` parameter
- [x] Task lifecycle: blocking and SSE streaming (incremental chunks via EventBus) implemented
- [x] Auth for external A2A agents: `auth=bearer:...`/`basic:...`/`headers=...` in `engine.a2a.endpoints`
- [x] Content types: TextPart, DataPart, FilePart are processed client- and server-side
- [x] Egress gating: application-level guard (`A2AEgress`) with host/IP allowlists and TLS; transport level open
- [ ] How to fully map QOC tool schemas to A2A message parts? (DataPart used; FilePart as reference)
- [ ] Tenant support (`/{tenant}`) — planned separately
- [ ] Caching of AgentCards on the server side? (client cache implemented)

## 9. Improvements / TODOs (non-blocking)

### 9.1 Push notifications (optional, low priority)
- **What:** SDK supports `TaskPushNotificationConfig` (webhook URL, auth) for asynchronous task updates. QOC can act as a push receiver.
- **Why not yet:** requires a `PushNotificationConfigStore` implementation and wiring into the QOC EventBus/webhooks. Only relevant for clients that cannot wait synchronously.
- **Effort:** medium. **Priority:** low.

### 9.2 Egress gating at transport level
- **What:** currently an application-level guard in `A2AEgress` (checks before card fetch and before `sendMessage`). A custom `A2AHttpClient` (JDK-based) would enforce the check at transport level.
- **Why not yet:** the SDK HTTP client is internal; a wrapper requires implementing all builder interfaces (`GetBuilder`, `PostBuilder`, `DeleteBuilder`).
- **Effort:** medium-high. **Priority:** medium (security).

### 9.3 Automated interop suite
- **What:** Docker concept (`docs/spec/qualitaet/04-docker-a2a-mcp-e2e.md`) and Compose stack (profile `a2a` in `deploy/docker-compose.yml`) exist and were tested manually. No automated test suite.
- **Why not yet:** requires a runner container with an SDK client and scenarios A2A-01…10 / MCP-01…06.
- **Effort:** medium. **Priority:** medium (quality assurance).

### 9.4 Server-side card caching
- **What:** the AgentCard is rebuilt from `AgentCatalogue` on every request. Caching could be worthwhile.
- **Effort:** low. **Priority:** low.

### 9.5 QOC tool schema → A2A message parts
- **What:** currently QOC tool results are sent to the remote agent as text. DataPart/FilePart are processed client-side, but mapping QOC tool schemas onto A2A message parts is not complete.
- **Effort:** medium. **Priority:** low.

## References

- [A2A Spec](https://a2a-protocol.org/latest/specification/)
- [A2A Java SDK](https://github.com/a2aproject/a2a-java)
- [A2A Samples](https://github.com/a2aproject/a2a-samples)
- [Quarkus Blog Post](https://quarkus.io/blog/quarkus-a2a-java-0-3-0-alpha-release/)
- [QOC README](../meta/README.md)
- [QOC HttpApi](../../quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java)