# Enterprise Readiness – QOC

> **Status:** 2026-10-31  
> **Purpose:** Positioning of QOC against the four pillars that turn an
> agent demo into a production-ready solution: **transactions,
> idempotency, audit, human approval.** QOC addresses all four — but
> with varying levels of maturity and some deliberate limits. Each item
> includes an assessment of effort, risk, readability, and clean
> architecture.

---

## Thesis

> 90 % of an agent's production readiness is distributed systems, not AI.
> Transactions, idempotency, audit, permissions, human approval.
> That is exactly where the decision is made between demo and production —
> and exactly where most agent frameworks lack enterprise experience.

QOC is closer to "production-ready" than pure agent frameworks, but the
distributed pillars are **implemented session-locally**, not
cross-tenant/cross-instance. That is where the remaining enterprise gap
lies — multi-instance approval, global audit, distributed transactions.

---

## 1. Transactions

### Status

**Present:**

- **DB transactions** via `Sql.transaction` / `Sqlite.transaction` for
  sessions, journal, inbox, approvals, claims.
  Example: inbox delivery is a transaction (`DELETE session_inbox` +
  `INSERT session_message` together).
- **Revert/snapshots:** Before every turn, a file snapshot is captured;
  `revert` restores the state (file-level, not DB TX).
- **Event sourcing:** `journal` is append-only; events are the source of
  truth, `seq` monotonic.

**Gap:**

- Tool calls are **not** part of a distributed transaction. QOC does not
  wrap external systems (DBs, APIs) in a TX.
- Rollback across multiple tools exists only via the file snapshot, not
  via side effects in external systems.

### What can be implemented

#### 1.1 Tool-Level Compensation (Saga pattern)

**Description:** Each tool can declare a compensating action
(e.g. `create_file` → `delete_file`). On a partial failure within a
tool chain, compensations run in reverse order. Analogous to SAGA in
distributed systems.

**Effort:** high (new contract in `ToolDefinition`/`ToolOutcome`,
compensations registry, orchestration in `Step`/`ToolRuntime`).

**Risk:** medium-high (complexity, partial-failure semantics, tool
authors must implement compensations).

**Readability:** medium (new abstraction, but clear contract).

**Clean architecture:** good – compensation as a port/contract in
`engine.core`, implementation in `runtime`.

**Recommendation:** Introduce only for explicitly marked side-effecting
tools, do not enforce globally.

#### 1.2 Transactional Tool Groups

**Description:** Execute multiple tools as an atomic group: all succeed or
all revert. Files via snapshot, external effects via compensation.

**Effort:** medium for file-level; high for external systems.

**Risk:** medium.

**Readability:** medium.

**Clean architecture:** moderate – requires grouping in `ToolRuntime`.

**Recommendation:** Optional mode, not the default.

#### 1.3 Turn-Level Idempotency (see section 2)

---

## 2. Idempotency

### Status

**Present:**

- `IdempotencyKey` per tool call (`idem_<session>_<call>`), stable across
  approval, execution and recovery.
- Inbox admission is idempotent (repeated enqueue with the same ID wins
  on the first).
- `session_claim` with `ON CONFLICT DO NOTHING` (global per session).
- Durable resume counter.

**Gap (important):**

- QOC guarantees **at-least-once**, not exactly-once. Side-effecting tools
  must be idempotent themselves.
- There are no idempotency keys for whole prompts/turns, only for tool
  calls.

### What can be implemented

#### 2.1 Prompt-/Turn-Level Idempotency

**Description:** Each prompt gets an idempotency key (client-provided or
generated). On repetition (retry, double click) the existing turn is
returned instead of being re-executed. Persisted in `session_inbox` or a
new `turn_idempotency` table.

**Effort:** medium (extend `InboxItem`/`Admission`, store method
`findTurn(key)`).

**Risk:** low (purely additive, no breaking change).

**Readability:** good.

**Clean architecture:** good – new port `TurnIdempotencyStore`.

**Recommendation:** Quick to implement, high value for retries.

#### 2.2 Durable Tool-Result Cache

**Description:** Tool results are stored under the `IdempotencyKey`. On a
retry with the same key the cached result is returned instead of
re-executing. Especially for side-effecting tools.

**Effort:** medium (extend `ToolOutputStore` with lookup by key).

**Risk:** low-medium (cache invalidation, staleness).

**Readability:** good.

**Clean architecture:** good – extension of an existing port.

**Recommendation:** Useful for MCP and A2A tools.

#### 2.3 Exactly-Once Guarantee for External Systems

**Description:** QOC can only propagate keys and deduplicate locally;
external systems must support the keys themselves. Documentation + SDK
help for tool authors.

**Effort:** low (documentation, example implementations).

**Risk:** low.

**Readability:** good.

**Clean architecture:** n/a (documentation).

**Recommendation:** Immediately implementable, clarifies expectations.

---

## 3. Audit

### Status

**Present:**

- **Event sourcing:** `StoredEvent` with type, time, seq; journal
  append-only.
- `audit(sessionId, limit)` projects tool/execution/step/compaction
  events.
- **Rotating LLM trace** (request/response, 2 MiB × 20 files).
- Audit logging for permission, tool, and guardrail decisions.
- `session_message` contains tool states (`streaming → running →
  completed/error`); messages are not deleted on compaction.
- `pending_approval` table persists approvals (visible after restart,
  reconcilable/expire).

**Gap:**

- Audit is **session-scoped**, not global/cross-tenant.
- Journal can be deleted on session delete → no immutable, append-only
  audit log across sessions.
- No separate audit table for security events outside the session.

### What can be implemented

#### 3.1 Global, Immutable Audit Log

**Description:** Separate `audit_event` table, append-only, no
`ON DELETE CASCADE`, with `tenant_id`/`instance_id`. Writes
security-relevant events (permission, guardrail, tool, session lifecycle)
independently of the session lifetime.

**Effort:** medium-high (migration, store, API endpoint, retention
policy).

**Risk:** low (purely additive).

**Readability:** good (clear new port `AuditStore`).

**Clean architecture:** good – new port in `engine.core`, implementation in
`adapter`.

**Recommendation:** High value for compliance; implement early.

#### 3.2 Security-Event Audit

**Description:** Permission decisions, guardrail results, auth failures are
logged in a structured way (principal, action, resource, outcome, time).
Already partially present; consolidate and persist.

**Effort:** medium.

**Risk:** low.

**Readability:** good.

**Clean architecture:** good – extension of the existing
`PermissionEngine`.

**Recommendation:** Quick, high compliance value.

#### 3.3 Audit Export / SIEM Integration

**Description:** Endpoint or file export (JSON/CSV) for audit events,
optional webhook push. Retention policy configurable.

**Effort:** medium.

**Risk:** low.

**Readability:** good.

**Clean architecture:** good – exporter as adapter.

**Recommendation:** Implement after 3.1.

---

## 4. Approval (Human-in-the-Loop)

### Status

**Present:**

- Permission effects: `ALLOW` / `DENY` / `ASK`.
- `PendingApprovals` (in-memory + persisted in `pending_approval`), TTL
  300 s.
- Web UI endpoints: `GET /api/session/{id}/permission`,
  `POST .../permission/{requestId}/reply`.
- `ApprovalStore`: project-wide "always allow" approvals, persisted.
- `QuestionTool` for questions to humans.
- `ASK` is enforced by the `ToolRuntime` permission gate.
- **Webhook guardrail:** external policy service can influence decisions.
- **Ephemeral/one-shot:** ASK futures are process-local (restart loses
  them, but the request is persisted → reconcilable/expire).

**Gap:**

- Approval futures are **process-local**; multi-instance approval
  coordination is incomplete (`session_claim` exists but is not used in
  `Engine`).
- No "human in the loop" for individual tool steps beyond
  `ASK`/`Question`.
- No granular approval by cost/sensitivity (only permission rules).

### What can be implemented

#### 4.1 Multi-Instance Approval Coordination

**Description:** Coordinate approval futures via the DB: use `session_claim`
as a lease, persist approval requests (exists), reconstruct futures
after restart/other instance from the DB. Polling or DB notification
instead of in-memory `CompletableFuture`.

**Effort:** high (new coordination layer, race conditions, timeout
handling).

**Risk:** medium (distribution problems, consistency).

**Readability:** medium (new abstraction `ApprovalCoordinator`).

**Clean architecture:** good – extend port `ApprovalSource`,
implementation in adapter.

**Recommendation:** Necessary for real multi-instance HITL; single-instance
is sufficient before that.

#### 4.2 Pre-Execution Approval for Individual Tool Steps

**Description:** Tools can declare `requiresApproval`; before execution an
approval request is created and execution pauses. Already partially
available via the `ASK` effect; extend to tool level with context (cost,
sensitivity).

**Effort:** medium (extend `ToolDefinition`, step integration).

**Risk:** low.

**Readability:** good.

**Clean architecture:** good – extension of `ToolDefinition.options()`.

**Recommendation:** Useful for expensive/sensitive tools.

#### 4.3 Granular Approval by Cost/Sensitivity

**Description:** Extend permission rules with cost thresholds and
sensitivity tags (e.g. `effect: ASK if cost > 1.00` or
`sensitivity: high`). Policy engine evaluates these.

**Effort:** medium (extend permission rules, extend rule schema).

**Risk:** low.

**Readability:** good.

**Clean architecture:** good – extension of existing contracts.

**Recommendation:** Implement after 4.2.

#### 4.4 Human-Decision Audit Trail

**Description:** Who approved/rejected what and when, with rationale.
Persisted in `pending_approval` (exists) + audit log (section 3).

**Effort:** low-medium.

**Risk:** low.

**Readability:** good.

**Clean architecture:** good – extension of existing tables.

**Recommendation:** Quick, high compliance value.

---

## 5. Additional Distributed Aspects

| Area | QOC status | Next step |
|---|---|---|
| Multi-instance DB | Sessions globally visible; `instance_id` for ephemeral; retention global | Instance affinity for persistent sessions (filter in `list()`) |
| A2A | Client + server, egress guard, agent-card cache | Push notifications, transport-level egress |
| MCP | Client + server, OAuth, resource tools | Tool output streaming |
| Egress | Allowlists for webhook and A2A (host/IP, TLS) | Central egress policy |
| Principal/auth | none/basic/oidc/proxy/mcp-oauth, trusted-proxy CIDRs | Tenant isolation |
| Observability | OpenTelemetry, rotating LLM trace, health/ready | Metrics for approval latency |

---

## 6. Recommended Implementation Order

1. **Immediately (low effort, high value):**
   - 2.3 Exactly-once documentation for tool authors
   - 3.2 Security-event audit consolidation
   - 4.4 Human-decision audit trail

2. **Short term (medium effort):**
   - 2.1 Prompt-/turn-level idempotency
   - 2.2 Durable tool-result cache
   - 3.1 Global audit log
   - 3.3 Audit export/SIEM
   - 4.2 Pre-execution approval

3. **Medium term (high effort):**
   - 4.1 Multi-instance approval coordination
   - 4.3 Granular approval by cost/sensitivity
   - 1.1 Tool-level compensation (Saga)
   - Multi-instance session affinity

4. **Long term (architecture):**
   - 1.2 Transactional tool groups
   - Distributed transaction coordination (Saga across external systems)

---

## 7. Conclusion

The four pillars are **conceptually present and partly production-ready**
in QOC:

- **Transactions/audit** are strong through event sourcing and DB
  transactions.
- **Idempotency** is laid out via `IdempotencyKey`, but the burden lies
  with the tools (at-least-once).
- **Approval** is fully implemented (ASK, pending approvals, Web UI,
  webhook).

QOC is therefore **closer to "production-ready"** than pure agent
frameworks, but the distributed pillars are **implemented session-locally**,
not cross-tenant/cross-instance. That is where the remaining enterprise gap
lies — multi-instance approval, global audit, distributed transactions.

# QOC – Extensions over OpenCode

> **Status:** 2026-10-31  
> **Purpose:** Overview of the capabilities QOC provides beyond the
> OpenCode reference code (`oc/`). QOC is a Java/Quarkus reimplementation
> of the OpenCode engine; `oc/` serves only as a reference and is never
> modified.

QOC is a **Java/Quarkus reimplementation of the OpenCode engine**. Beyond
that, QOC contains a range of extensions that go beyond OpenCode.

---

## 1. Platform & Architecture

- **Pure Java 21 / Quarkus 3.40** instead of Bun/TypeScript; engine core
  (`engine.core`) without Quarkus, CDI, Jackson, or HTTP (ports/adapters).
- **Three runtime forms:** JVM, Native Image (GraalVM), Container;
  systemd unit, Docker (JVM + Native).
- **Two databases:** SQLite (WAL) or PostgreSQL (Flyway migrations,
  Dev Services).
- **ArchUnit rules** and JaCoCo ≥ 80 % as quality gate.

## 2. Security & Identity (beyond OpenCode)

- **Authentication modes:** `none`, `basic`, `oidc` (Keycloak), `proxy`
  (trusted reverse proxy), `mcp-oauth`.
- **Principal propagation** via CDI `PrincipalContext` +
  `ToolContext.metadata()`.
- **Trusted-proxy CIDRs**, egress allowlists for webhook and A2A.
- **Three layers:** OS hardening, engine policy (deny by default), shell
  scanner.
- **Webhook guardrail:** optional external policy service (PII, security,
  logging) as CDI SPI.
- **Audit logging** for permission, tool, and guardrail decisions.
- **OS-based security guide** (`docs/guides/OS-based-security.md`).

## 3. A2A (Agent-to-Agent) – Client and Server

- **Client:** external A2A agents as tools, agent-card discovery with
  TTL cache, auth, egress guard.
- **Server:** opt-in JSON-RPC server (`engine.a2a.server.enabled`), agent
  card, SSE streaming, task lifecycle, sessions as bounded LRU.
- **DataPart/FilePart, skill enrichment, task-input extraction.**
- Discovery endpoint `/api/a2a/agents`, UI.

## 4. MCP (Client + Server)

- **Client:** MCP tools and resources as tools, OAuth support.
- **Server:** QOC as MCP server (`qoc_run_agent`), one-shot tasks,
  session reuse.
- MCP resource tools (`list_mcp_resources`, `read_mcp_resource`).

## 5. Skills

- Progressive disclosure, 8 KiB body limit.
- **BM25 filter** (`topK`, `minScore`, `thresholdCount`, `alwaysInclude`).
- **Per-agent configuration:** `allow`/`deny` are compiled into permission
  rules, prompt modes `none`/`core`/`top-k`/`all`, `max`.
- Hidden skills via `disable-model-invocation`, `skill_list`.
- Debug logging for skill loading/usage.

## 6. Session Engine

- Inbox with `STEER`/`QUEUE`, compaction (automatic + manual),
  snapshots/revert.
- **Subagents** (depth limit, concurrency semaphore, background tasks).
- Parallel tool calls, fork, location switch, worktrees.
- Event sourcing (journal), SSE streaming, live events.
- **Session retention** (configurable, SQLite/PostgreSQL).
- **Ephemeral one-shot sessions** with temp directory – tools are
  redirected per session to a separate directory, session + directory are
  deleted at the end.

## 7. Tools

- Filesystem (read/write/edit/patch/glob/grep/list), shell (optional
  PTY), web (fetch, Firecrawl), skills, subagents, todos, questions,
  worktrees, MCP, A2A.
- **Remote shell** (spec-only, not yet implemented).

## 8. Context & Instructions

- Automatic `AGENTS.md`/`CLAUDE.md`/`SYSTEM.md`/`APPEND_SYSTEM.md`
  discovery.
- Context-size estimation, compaction strategies.

## 9. Provider

- OpenAI-compatible, SSE streaming, token budgets, prompt cache,
  retry/classification, rotating LLM trace.

## 10. Runtime Agents

- **Runtime agent creation** (`agent_create` tool): agents can be defined
  at runtime (prompt, tools, skills, mode) and used as subagents.
- **Email tool** (`email_fetch`, `email_read`, `email_send`):
  IMAP/SMTP-based email client with folder listing, search, reading
  (including attachments as Base64) and sending (text/HTML + attachments).
  Credentials via config/env.
- Agent import from OpenCode + agent modes (primary/subagent/all).

## 11. Operations & Observability

- Docker (JVM/Native), Compose (A2A/MCP interop), systemd, Nginx/TLS.
- OpenTelemetry, rotating LLM trace, health/ready endpoints.
- GCP VM deployment (native binary).

## 12. Quality

- ArchUnit, JaCoCo ≥ 80 %, E2E tests (skill loading, orchestration,
  compaction, A2A/MCP).
- Docker-based interop suite (concept).

## 13. Prototypes / Spec-only

- **Decision gate** (LangChain4j `DecisionModel`) as isolated prototype in
  `decision-gate/` (QOC pins 1.19.3, Decision API needs 1.21+).
- Remote shell (download/upload) spec-only.
- `infratest` module (PostgreSQL deployment tests).

---

## Summary

QOC extends OpenCode with: **Java/Quarkus runtime, A2A client/server,
MCP server mode, webhook guardrail, skill filtering & per-agent skills,
ephemeral one-shot sessions, runtime agent creation,
principal propagation/security layer, PostgreSQL persistence,
compaction/subagent orchestration, OpenTelemetry/tracing, and native
container deployments.**
