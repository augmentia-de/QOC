# QOC – Complete Introduction and Architecture Overview

> Location: `docs/qoc-complete-guide.md` (outside the Git repo)
> Language: English
> Version: 2026-11 (workflow version with structured output, RBAC plan, in-memory minimal mode)

---

## 1. What is QOC?

**QOC** (Quarkus OpenCode) is a Java port of the OpenCode framework –
not a copy, but **spec-driven**. First analysis, then specification,
then code. The specification (`qoc/docs/spec/`) is the authoritative
source of domain truth; the implementation follows it.

QOC is a **configurable AI agent** that can be used as a worker in
arbitrary workflows. It supports:

- **Arbitrary agent definitions** (system prompt, model, tools, guardrails,
  skills, permissions, structured output).
- **Tools** (file system, shell, web, email, MCP, subagents, A2A).
- **MCP integration** (as client and server).
- **Two operating modes**: short-lived sessions for one-off tasks (e.g. MCP)
  and durable multi-turn sessions for longer agent runs (context,
  compaction, resume).

### 1.1 Lineage from OpenCode

QOC is based on the analysis of the OpenCode checkout (`oc/`). OpenCode is a
TypeScript/Node.js framework with a SQLite backend, WAL mode, embedded
database (`~/.local/share/opencode/opencode.db`), Git snapshots, tool outputs
on the file system, and Markdown-based agent definitions.

QOC adopts the **concepts** (session engine, event sourcing, permissions,
tools, MCP), but not the code. The implementation is spec-driven in Java 21
with Quarkus. Insights from OpenCode (e.g. an `event_sequence` table
instead of `history().size()`, snapshots in Git instead of in the DB, tool
outputs on the FS) flow into the architecture as recommendations.

### 1.2 Core principles

1. **Spec-driven**: the specification defines the semantics, not the code.
2. **Pure core layer**: `engine.core` is pure Java – no Quarkus, CDI,
   Jackson, or HTTP dependencies. External technology lives in
   `engine.adapter.*`.
3. **Replaceable adapters**: SQL, HTTP, processes, and the file system are
   encapsulated behind ports. Storage backend (Postgres/SQLite/in-memory)
   via configuration.
4. **Architecture rules secured with ArchUnit**.
5. **Least privilege by default**: permissions are deny-by-default.

---

## 2. Architecture

### 2.1 Layering

```
┌─────────────────────────────────────────────────────┐
│  Quarkus Outer Skin (de.augmentia.oc.engine.http)   │
│  REST endpoints, OIDC, SessionAccessGuard,           │
│  WorkflowResource, AgentStoreResource, SchemaResource │
├─────────────────────────────────────────────────────┤
│  QocEngine Facade (engine.runtime)                  │
│  runPrompt, createSession, resolveAgentConfig,       │
│  Step, ToolRuntime, ContextBuilder, CompactionService│
├─────────────────────────────────────────────────────┤
│  engine.core (pure Java)                            │
│  Model, Ports, Contracts, Eval, JSON, Schema        │
├─────────────────────────────────────────────────────┤
│  engine.adapter (sql, sqlite, memory, fs, http,     │
│  process, time)                                     │
└─────────────────────────────────────────────────────┘
```

### 2.2 Package structure

| Package | Role |
|---|---|
| `engine.core.model` | Value types (SessionId, AgentId, Message, Event, …) |
| `engine.core.port.store` | Storage ports (SessionStore, Journal, ConversationStore, …) |
| `engine.core.port.runtime` | Runtime ports (Clock, SessionWake, ApprovalSource, …) |
| `engine.core.contract` | Tool, provider, step contracts |
| `engine.core.eval` | Context evaluation, output bounding, cache affinity |
| `engine.core.json` | JSON/schema parser (`JsonSchema.parse`, `JsonValues`) |
| `engine.core.wiring` | `Stores`, `StorageConfig`, `StorageKind` |
| `engine.runtime` | `QocEngine`, `Step`, `ToolRuntime`, `ContextBuilder`, `Coordinator` |
| `engine.runtime.session` | Session service, admission, delivery, recovery |
| `engine.runtime.tool` | Tools (fs, shell, web, mcp, subagent, structured) |
| `engine.runtime.workflow` | WorkflowStepProvider, workflow mode |
| `engine.runtime.compaction` | Auto-compaction |
| `engine.adapter` | SQL, SQLite, memory, FS, HTTP, process, time |
| `engine.adapter.memory` | In-memory adapter (minimal) |
| `quarkus.http` | REST endpoints, security, WorkflowResource |

### 2.3 Technologies

| Technology | Version | Usage |
|---|---|---|
| Java | 21 | Language |
| Quarkus | 3.40.1.1 | Outer skin, REST, CDI, OIDC |
| LangChain4j | (via Quarkus extension) | LLM provider integration |
| Maven | 3.9+ | Build |
| Jackson | 2.18.2 | JSON serialization |
| Flyway | (Quarkus extension) | DB migrations |
| ArchUnit | (test) | Architecture rules |
| JaCoCo | 0.8.12 | Coverage (≥ 80 %) |
| swagger-parser | 2.1.22 | OpenAPI import |
| bubblewrap (bwrap) | system | Shell sandbox |
| React + Vite + TypeScript + Tailwind | 19 | Workflow UI |
| ReactFlow | – | Graph editor |
| Temporal | 1.40.0 (planned) | Workflow durability |

---

## 3. Session engine

### 3.1 Session lifecycle

1. **Creation**: `SessionService.create(location, agent, model, now)` →
   `SessionId` (random, `ses_` + 64 hex) + `SessionCreated` event in the
   journal.
2. **Execution**: `QocEngine.runPrompt(session, …)` → acquire claim →
   preflight → store prompt → build context → execute step → publish events
   → compaction → release claim.
3. **Resume**: on crash → `recover()` scans open claims and continues
   execution.
4. **Deletion**: `deleteSession` removes session, inbox, conversation,
   claims, workspaces, instructions.

### 3.2 Core components

- **SessionStore / SessionReader**: session metadata (agent, location, title,
  owner, tokens, cost).
- **ConversationStore**: message history (append-only, gapless seq).
- **Journal**: event sourcing (write-ahead log). Per aggregate
  `ArrayList<StoredEvent>` + `AtomicLong seq`.
- **ClaimStore**: at most one execution per session (`putIfAbsent`).
- **SessionWorkspaceStore**: execution mode, workspace root, instance id, retain.
- **CompactionService**: auto-compaction of long histories.
- **InstructionStore**: instruction entries and blobs (content-addressed).

### 3.3 Event sourcing

Events are the source of truth. Projections (session, conversation) are
read models. Events:
`SessionCreated`, `ExecutionStarted`, `ToolCalled`, `ToolSuccess`,
`ExecutionSucceeded`/`Failed`/`Paused`, `SessionForked`,
`WorkflowRunStarted`, `WorkflowNodeStarted`/`Completed`/`Failed`,
`WorkflowRunFinished`/`Failed`/`Resumed`.

---

## 4. Tools

### 4.1 Built-in tools

| Tool | Description |
|---|---|
| `read` | Read file/directory (with artifact store for large outputs) |
| `grep` | Regex search in files |
| `write` / `edit` | Write/edit file |
| `bash` / `shell` | Shell commands (sandboxed via bwrap) |
| `glob` | File pattern search |
| `web_fetch` | HTTP fetch |
| `subagent` / `task` | Subagent delegation (child session) |
| `submit_result` | Structured output (JSON schema) |
| `question` | Question to the user |
| `email` (imap/smtp) | Read/send email |
| `mcp_*` | MCP server tools |
| `a2a_*` | A2A agents |

### 4.2 Tool runtime

- **Permission gate**: every tool call is checked (least-privilege/allow-all).
- **Output bounding**: large outputs are truncated (2,000 lines / 50 KiB),
  the full text is stored as an artifact.
- **Idempotency**: tool result cache prevents duplicate execution on retries.
- **Hooks**: `beforeToolCall` / `afterToolCall` for audit/transformation.

---

## 5. Permissions & security

### 5.1 Modes

| Mode | Description |
|---|---|
| `none` | No login; local admin (loopback) |
| `basic` | HTTP Basic Auth (`OPENCODE_SERVER_PASSWORD`) |
| `oidc` | OIDC (Keycloak/Auth0), roles from JWT |
| `proxy` | Trusted-proxy auth (nginx/LB) |
| `mcp_oauth` | OAuth for MCP servers |

### 5.2 Permission default

- `least-privilege` (deny-by-default) or `allow-all` (OpenCode-compatible).
- Ask mode: `deny` / `allow` / `interactive`.

### 5.3 Shell sandbox

- bwrap (bubblewrap) with `workspace-write` mode.
- Disabled in Docker (unprivileged) — boundary comes from mounts.
- Seccomp deny list against `ptrace`, `mount`, `unshare`, etc.

### 5.4 Container hardening

Read-only root FS, targeted mounts, tmpfs, `cap_drop ALL`,
`no-new-privileges`, `pids_limit`, non-root UID 1000.

---

## 6. Provider

- **HTTP provider**: OpenAI-compatible (`/chat/completions`).
- **Scripted provider**: for tests/dev (no real LLM call).
- **Model configuration**: `engine.model.provider`, `engine.model.id`,
  `engine.turn.max.tokens`.
- **Retry policy**: max 3 attempts, delays 10s/30s.
- **Preflight checks**: DNS, HTTP (configurable).

---

## 7. Persistence (storage)

### 7.1 Backends

| Backend | Configuration | Usage |
|---|---|---|
| Postgres | `engine.storage=postgres` | Multi-node, production |
| SQLite | `engine.storage=sqlite` | Single-node, WAL |
| In-memory | `engine.storage=memory` | Embedded, tests, helper |

### 7.2 Storage factory

`StorageFactory.create(config, memoryState, dataSource, clock, fileSystem)`
selects the backend per port. Per-port overrides possible (intermediate
stages).

### 7.3 In-memory minimal mode

Only 6 stores are real (session, conversation, journal, claim, workspace,
tool output). The remaining 5 ports are no-ops. Budget: 300 MiB (only tool
outputs LRU-bounded).

### 7.4 Workflow storage

`engine.workflow.storage`: `auto` | `file` | `memory` | `postgres` | `sqlite`.
File-based: JSON files in `wf-data/`, version snapshots.

---

## 8. Workflow orchestration

### 8.1 Data model

- `WorkflowDefinition`: nodes (id, type, position, data) + edges (source,
  target) + agents.
- Node types: `start`, `end`, `agent`, `decision`, `fork`, `join`, `loop`,
  `hitl`, `subworkflow`, `mcp_call`, `llm_eval`, `llm_summarize`.
- `Agent`: name, version, model, systemPrompt, tools, guardrails, hooks,
  jsonOutput, active, responseSchema.

### 8.2 Execution

- `WorkflowExecutor.executeRun(def, input)` → topological sort (Kahn) →
  per node `dispatch()` → `runAgent()` for agent nodes → journal events.
- `runId = sessionId` (no separate run id).
- Agent nodes create child sessions.
- Resume: `WorkflowExecutor.resume(runId)` → replay from checkpoint.
- HITL: `hitl` nodes pause; continuation via signal.

### 8.3 Structured output

- `responseSchema` (JSON schema) on agent/node.
- LLM is forced to structured output via the `submit_result` tool.
- Result in `EngineResult.structured()` / `NodeResult.structured`.
- Converters: `JavaRecordToJsonSchema`, `OpenApiToJsonSchema`.
- Endpoints: `/api/schema/from-class`, `/from-openapi`, `/validate`.

### 8.4 Three-pillar model

1. **Deterministic nodes**: rules, routing, transformation, 0 tokens.
2. **Subagent nodes**: isolated ReAct loops, only final result returned.
3. **Context agents**: curated overall view, optimal caching.

---

## 9. UI (workflow UI)

- React 19 + Vite + TypeScript + Tailwind + ReactFlow.
- Pages: Dashboard, Workflows (GraphEditor), Agents, Runs, Observe, Testbench,
  Settings, MCP Servers.
- GraphEditor: drag & drop, custom node types, property panel, auto-layout
  (elkjs).
- SSE streaming for live updates.

---

## 10. Deployment

### 10.1 Docker variants

| Service | Profile | Port | Characteristics |
|---|---|---|---|
| qoc-dev | dev | 8070 | Hot reload |
| qoc-local | local | 8071 | Server stack (Postgres) |
| qoc-memory | memory | 8082 | In-memory, no DB |
| qoc-secure | secure | 8075 | Hardened, least privilege |
| qoc-native | native | 8077 | Native image (GraalVM) |

### 10.2 Native image

- Mandrel 25, Quarkus 3.40.
- workflow-runner is a library (no uber-JAR).
- slf4j-api instead of slf4j-simple.

---

## 11. Testing & quality

- **Unit tests**: 950+ (engine), 11+ (workflow runner).
- **ArchUnit**: architecture rules (no Quarkus imports in core, etc.).
- **JaCoCo**: coverage gate ≥ 80 % (currently 72 % — pre-existing).
- **TCK**: contract tests for storage adapters (planned).

---

## 12. Extension vision

### 12.1 Context caching

- `CacheAffinity` sends session headers (already).
- `cacheKey` field exists, but no provider adapter consumes it.
- Planned: OpenAI `prompt_cache_key`, Anthropic `cache_control`.

### 12.2 RBAC + system user

- Roles: `admin`, `user`, `system`, `viewer`.
- System user: service account for automation.
- Ownership: `ownerId` on workflow/agent.
- OIDC integration for workflows (see `Workflow-OIDC.md` plan).

### 12.3 Further

- `transform` node (data transformation without LLM/MCP).
- Per-pillar metrics (OpenTelemetry).
- Subagent session cleanup (ephemeral).
- Automatic curation (summarize nodes).
- Temporal integration (workflow durability, HITL via signals).
- Redis hot tier (multi-node).

---

## 13. Open questions

- Multi-node vs. single-node (SQLite)?
- Event-sourcing projection (conversation derivable)?
- Retention (events/history)?
- Credentials in secret manager?
- Git snapshots vs. content-addressed FS?

---

## 14. References

| Topic | Path |
|---|---|
| Specification | `qoc/docs/spec/` |
| Roadmap | `qoc/docs/ROADMAP.md` |
| Open questions | `qoc/docs/open-questions.md` |
| Storage analysis | `qoc/docs/engineering/Storage-alternative.md` |
| In-memory | `qoc/docs/engineering/InMemory.md` |
| Multi-memory | `qoc/docs/engineering/Multi-Memory.md` |
| Workflow | `qoc/docs/engineering/Workflow.md` |
| Structured output | `qoc/docs/engineering/Structured-Output.md` |
| Native deployment | `qoc/docs/engineering/Native-Deployment.md` |
| Security hardening | `qoc/docs/deployment/06-security-hardening.md` |
| CONFIG | `qoc/docs/CONFIG.md` |
| Permission interviewer | `qoc/docs/Agent-Permission-Interviewer.md` |
| LinkedIn follow-up | `2oc/docs/linkedin-followup-2026-11.md` |
| 3-pillars vision | `2oc/docs/linkedin-3-pillars-vision.md` |
| Workflow vs. agent loop | `2oc/docs/qoc-engine-workflow-vs-agent-loop.md` |
