# QOC vs. OpenCode – Extensions (prioritized)

> **Purpose:** Complete, prioritized inventory of the genuine QOC extensions
> compared to the OpenCode reference code (`oc/`). Ports of OC behavior
> are not counted as QOC extensions.
>
> **Status:** 2026-11-18  
> **QOC reference:** `qoc/` (HEAD `323ffdc7`)  
> **OpenCode reference:** `oc/` (HEAD `863ea41364`)  
> **Method:** direct code inspection of both codebases; OC read-only only.

---

## 1. Rating schema

| Symbol | Meaning |
|---|---|
| **V** | User/product value (1 = niche, 5 = central differentiator) |
| **T** | Technical depth (1 = configuration/UI, 5 = architecture/runtime) |
| **P0** | Prioritize immediately / core differentiator |
| **P1** | High value, but after P0 or with groundwork |
| **P2** | Concept, special case, or only after fundamentals |
| **Status** | `implemented`, `partial`, `concept` |

---

## 2. Executive summary

QOC is not simply a port of OpenCode to Java. The genuine added value lies
in four blocks:

1. **Workflow platform on the same engine loop**  
   ReactFlow graphs are executed as `LoopMode.WORKFLOW` inside the same
   `QocEngine` loop, with journal replay, resume, recovery, versioning and
   prompt-to-workflow generation.

2. **Enterprise operations**  
   PostgreSQL alongside SQLite/Memory, OIDC/Proxy/RBAC/Ownership, DB-based
   agent and workflow definitions, and a ReactFlow UI.

3. **Durable human-in-the-loop**  
   Async approvals/questions with token, deadline, e-mail/UI, `paused_prompt`,
   resume and recovery.

4. **Agent ecosystem and contracts**  
   A2A (client + server), MCP server exposition, structured outputs,
   node contracts, context-budget gates and SDLC graph invariants.

Important correction versus older feature lists: QOC has **no declarative
guardrail rule engine**. `GuardrailHooks` calls an external webhook and
can block, rewrite or redact. Likewise, `subworkflow` and `fork` nodes are
today **structural/expanding**, not parallel or nested workflow execution.

---

## 3. Priority overview

| Prio | Extensions | Core benefit |
|---|---|---|
| **P0** | Workflow runner/loop, durable runs, workflow generation, OIDC/RBAC/Ownership, durable HITL, PostgreSQL, A2A | Product and enterprise differentiators |
| **P1** | MCP server, structured outputs, node contracts/context budget, graph validation/SDLC, guardrail webhook, resilience, tool cache, extended toolset, agent store, ReactFlow UI, classify | Usability, robustness, integration |
| **P2** | Runtime adaptation (concept), MCP OAuth server (stub), Temporal (concept) | Future, not yet productive capabilities |

---

## 4. Detailed extensions

### E01 – Workflow runner as second loop mode

- **V:** 5 / **T:** 5 / **Prio:** P0 / **Status:** implemented (with partial steps)
- **OC:** only agent loop, subagents, ACP, MCP client.
- **QOC:** `QocEngine.runPrompt(...)` switches to `LoopMode.WORKFLOW` when
  `workflowStepProvider != null && workflowContext != null`; `WorkflowStepProviderImpl`
  delivers exactly one node step per call.
- **Evidence:** `engine/.../runtime/QocEngine.java:42-44`, `workflow-runner/.../runner/WorkflowStepProviderImpl.java`,
  `workflow-runner/.../runner/DefaultNodeDispatcher.java`
- **Nodes:** 16 backend types (`agent`, `context_agent_executor`, `decision`, `start`, `end`,
  `fork`, `join`, `loop`, `hitl`, `subworkflow`, `compact`, `mcp_call`, `rest_call`, `classify`,
  `guard`, `inject`). The ReactFlow editor currently registers 14 types; `guard`/`inject` are
  backend types but not in the editor palette.
- **Limitation:** `subworkflow` expands the child graph but does not execute it as a
  nested run; `fork`/`join` are structural, not parallel.

### E02 – Durable workflow runs (journal, resume, recovery, versioning)

- **V:** 5 / **T:** 5 / **Prio:** P0 / **Status:** implemented
- **OC:** event log, claims and inbox exist, but no workflow-run semantics.
- **QOC:** A run is a normal session titled `workflow:<id>`; state lives in
  `WorkflowRun*` events. `WorkflowRunJournal.load(...)` replays node outputs, state,
  `currentNodeId`, child session IDs, resume count and status.
  `WorkflowExecutor.resume(...)` loads the matching definition version via
  `definitionVersion`/`contentHash`, releases a stale claim and continues from the
  last successful node. `WorkflowRecovery` scans up to 200 runs.
- **Evidence:** `workflow-runner/.../runner/WorkflowRunJournal.java`,
  `workflow-runner/.../runner/WorkflowExecutor.java:148-203`,
  `engine/.../core/model/SessionEvent.java` (WorkflowRun*-events)
- **Specialty:** deliberately **no `workflow_run` table**; definition and version are
  separate from run state.

### E03 – Prompt-to-workflow generation

- **V:** 4 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** no workflow generation.
- **QOC:** `WorkflowGenerator.generate(...)` orchestrates prompt → LLM → strip fences →
  parse steps → build graph → validate → persist agents → save. Agents receive
  `gen-<workflowId>-<agentId>` IDs and are stored in the global agent store.
- **Evidence:** `workflow-runner/.../generate/WorkflowGenerator.java`,
  `workflow-runner/.../generate/WorkflowPlanParser.java`,
  `workflow-runner/.../generate/WorkflowBuilder.java`,
  `quarkus/.../http/WorkflowResource.java:146-172`
- **Switch:** `ENGINE_WORKFLOW_GENERATE_ENABLED` (default off).

### E04 – PostgreSQL as backend option

- **V:** 4 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** exclusively SQLite (`bun:sqlite`/`node:sqlite`/workerd), no network DB.
- **QOC:** `StorageKind` allows per port `MEMORY`, `SQLITE`, `POSTGRES`; `StorageFactory`
  selects the adapter; Flyway migrations V1–V12 for Postgres and SQLite; Postgres uses
  `jsonb`, `timestamptz`, `bigserial`.
- **Evidence:** `engine/.../core/wiring/StorageKind.java`,
  `engine/.../adapter/StorageFactory.java`,
  `engine/src/main/resources/db/migration/V1__engine.sql` … `V12__mcp_server.sql`,
  `engine/src/main/resources/db/migration-sqlite/...`
- **Note:** the OC reference uses WAL-SQLite; QOC additionally offers multi-node-capable
  Postgres.

### E05 – OIDC, trusted proxy, RBAC and ownership

- **V:** 5 / **T:** 4 / **Prio:** P0 / **Status:** implemented (configuration-dependent)
- **OC:** basic auth / local password authentication, no OIDC/RBAC ownership semantics
  for workflows.
- **QOC:** `OidcPrincipalProducer` (JWT/OIDC), `TrustedProxyFilter`, `RolesAllowedFilter`,
  `SecurityConfig` (NONE/BASIC/OIDC/PROXY/MCP_OAUTH), `SessionAccessGuard` with owner/admin
  checks, workflow/agent ownership in REST resources.
- **Evidence:** `quarkus/.../http/security/OidcPrincipalProducer.java`,
  `quarkus/.../http/security/TrustedProxyFilter.java`,
  `quarkus/.../http/security/RolesAllowedFilter.java`,
  `quarkus/.../http/security/SecurityConfig.java`,
  `quarkus/.../http/SessionAccessGuard.java`,
  `quarkus/.../http/WorkflowResource.java:78-137`
- **Gap:** `WorkflowResource` checks ownership for definitions, but not consistently for
  `/workflows/runs`, `/workflows/runs/{runId}` and `/resume`. This is a security/
  consistency gap, not a finished extension.

### E06 – Durable async approvals and questions

- **V:** 5 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** in-memory `Permission` requests with deferred future; stored approvals as
  `permission` table. No e-mail reply links or paused sessions.
- **QOC:** `DurableApprovalSource` persists `pending_approval` with single-use token,
  deadline, status and sends via `NotificationChannel` (e-mail). `DurableQuestionSource`
  does the same for questions. `WebApprovalSource` combines in-memory future with DB
  persistence. Workflow HITL pauses durably via `NodeResult.paused(...)`.
- **Evidence:** `quarkus/.../http/DurableApprovalSource.java`,
  `quarkus/.../http/DurableQuestionSource.java`,
  `quarkus/.../http/WebApprovalSource.java`,
  `quarkus/.../http/HttpApi.java:69-82`,
  `engine/src/main/resources/db/migration/V3__pending_approvals.sql`,
  `engine/src/main/resources/db/migration/V8__question_requests.sql`
- **Caveat:** HITL `approvalId` uses `System.identityHashCode(ctx)` and is therefore
  not stable across process restarts; the pause itself is durable, but ID stability is
  fragile.

### E07 – A2A (agent-to-agent)

- **V:** 4 / **T:** 4 / **Prio:** P0 / **Status:** implemented
- **OC:** no A2A client/server.
- **QOC:** `A2ATool`/`A2AToolProvider` bind external agents as tools (agent-card cache,
  discovery). `QocAgentExecutor`, `A2AAgentCardProducer`, `A2ARouteGate` and
  `A2ASessionRegistry` provide an opt-in JSON-RPC A2A server.
- **Evidence:** `engine/.../runtime/tool/a2a/A2ATool.java`,
  `engine/.../runtime/tool/a2a/A2AToolProvider.java`,
  `quarkus/.../http/a2a/QocAgentExecutor.java`,
  `quarkus/.../http/a2a/A2AAgentCardProducer.java`
- **UI:** `reactflow/src/pages/A2APage.tsx`.

### E08 – MCP server exposition of QOC

- **V:** 4 / **T:** 3 / **Prio:** P1 / **Status:** partial
- **OC:** MCP client and MCP management API, but no QOC MCP server for agent execution.
- **QOC:** `McpServerResource` offers JSON-RPC `initialize`, `tools/list`, `tools/call`
  with the tools `qoc_run_agent` and `qoc_run_agent_with_config`, including transient
  agents, structured output and registry/serverless roles.
- **Evidence:** `quarkus/.../http/McpServerResource.java`,
  `quarkus/src/main/resources/application.properties` (`engine.mcp.server.*`)
- **Limits:** only tools, no resources/prompts/sessions; OAuth endpoints `/mcp/authorize`
  and `/mcp/callback` are stubs (`501 Not Implemented`).

### E09 – Structured outputs

- **V:** 4 / **T:** 4 / **Prio:** P1 / **Status:** implemented (non-strict; strict as open plan)
- **OC:** no corresponding `submit_result` mechanism found.
- **QOC:** the LLM is forced to deliver a result via the synthetic tool `submit_result`;
  `EngineResult.structured()`/`NodeResult.structured` and
  `StructuredOutputConverter.toMap(...)`. Schema converters for Java records, Java source,
  OpenAPI and YAML; endpoints `/api/schema/from-class`, `/from-openapi`, `/validate`.
- **Evidence:** `docs/engineering/Structured-Output.md`,
  `workflow-runner/.../runner/StructuredOutputConverter.java`,
  `workflow-runner/.../runner/JavaRecordToJsonSchema.java`,
  `workflow-runner/.../runner/OpenApiToJsonSchema.java`,
  `workflow-runner/.../runner/YamlToJsonSchema.java`,
  `quarkus/.../http/McpServerResource.java:292-300`
- **Open:** strict mode (constrained decoding) is planned but not implemented
  (`docs/plans/strict-structured-outputs.md`).

### E10 – Node contracts, context budget and context policies

- **V:** 4 / **T:** 4 / **Prio:** P1 / **Status:** implemented
- **OC:** no workflow node contracts.
- **QOC:** `ContextAssembler` with `FULL`/`FOCUSED`/`ARTIFACTS`/`CUSTOM`; `ContextBudgetGate`
  throws `WorkflowBudgetExceededException`; `NodeContractValidator` validates `outputContract`
  (type, required, additionalProperties, enum, bounds, items). Template resolver supports
  `${node.output.field}` and `${state.key}` with strict mode.
- **Evidence:** `workflow-runner/.../runner/ContextAssembler.java`,
  `workflow-runner/.../runner/ContextBudgetGate.java`,
  `workflow-runner/.../runner/NodeContractValidator.java`,
  `workflow-runner/.../runner/TemplateResolver.java`
- **Benefit:** reduces context overflow and makes node data flow verifiable.

### E11 – Graph validation and SDLC invariants

- **V:** 3 / **T:** 4 / **Prio:** P1 / **Status:** implemented
- **OC:** no workflow graphs.
- **QOC:** `GraphCompiler` checks node types, IDs, edges, agent refs, templates, cycles,
  budget binding on cycles (SDW-03) and gate bypass (SDW-07). `validateTemplatesOnly(...)`
  allows draft saving.
- **Evidence:** `workflow-runner/.../validation/GraphCompiler.java`,
  `docs/engineering/Workflow-SDLC.md`
- **Correction:** code requires at least one `end` node, not exactly one; fork/join are
  structural, not parallel execution.

### E12 – External guardrail webhook

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** plugin hooks, but no webhook-based tool guardrail.
- **QOC:** `GuardrailHooks` (before/after tool call) invokes `WebhookClient` and supports
  `allow`, `block`, `deny`, `ask`, `redact`, `replace`, `rewrite`, shadow mode and
  fail-open/closed. Agent config carries `guardrailsInput`/`guardrailsOutput`.
- **Evidence:** `quarkus/.../guardrail/GuardrailHooks.java`,
  `quarkus/.../guardrail/WebhookClient.java`,
  `quarkus/.../guardrail/GuardrailRegistry.java`
- **Correction:** there is no declarative rule engine and no CDI SPI for local guardrails.

### E13 – Resilience stack in the agent loop

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** retry/backoff and compaction exist, but no preflight/triage/loop-detector
  in this form.
- **QOC:** preflight checker (DNS/HTTP/TTL), `TriageService`, `LoopDetector`, `WebhookAlerter`,
  `RetryPolicy`, step timeout, turn budgets (tool calls/tokens/wall time), verify-on-stop.
  Verify-on-stop allows `CONTINUE`/`FAIL`/`WARN` before turn end.
- **Evidence:** `engine/.../runtime/resilience/*`,
  `engine/.../runtime/QocEngine.java:48-183,1204-1230`,
  `engine/.../core/eval/VerifyRule.java`,
  `docs/plans/verify-on-stop-guards.md`

### E14 – Tool result cache and idempotency

- **V:** 3 / **T:** 2 / **Prio:** P1 / **Status:** implemented
- **OC:** no own tool result cache port.
- **QOC:** `ToolRuntime` uses `ToolResultCache` with `IdempotencyKey`; cache hit returns
  decoded `ToolOutcome`; results are stored after execution. Table `tool_result_cache`.
- **Evidence:** `engine/.../runtime/tool/runtime/ToolRuntime.java:84-170`,
  `engine/src/main/resources/db/migration/V7__tool_result_cache.sql`

### E15 – Extended toolset

- **V:** 3 / **T:** 2 / **Prio:** P1 / **Status:** implemented
- **OC:** shell, read/write/edit/patch, glob/grep, web fetch/search, todo, question, worktree,
  subagent, MCP resources, skill.
- **QOC:** additionally `email` (SMTP/IMAP with limits), `remote-shell` (SSH), `secure-rest`
  (allowlist/credentials), Firecrawl, check tools (`test_runner`, `hash_checker`),
  `agent_create`/`agent_list`, `submit_result`, A2A tools, MCP call tools.
- **Evidence:** `engine/.../runtime/tool/email/EmailLimits.java`,
  `engine/.../runtime/tool/remote/*`,
  `engine/.../runtime/tool/web/*`,
  `quarkus/.../runtime/tool/agent/AgentCreateTool.java`,
  `workflow-runner/.../runner/CheckTools.java`

### E16 – Global agent store and runtime agent authoring

- **V:** 4 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** agents as markdown/config files; no DB-based agent SSOT with ownership.
- **QOC:** `AgentStore`/`AgentStorePort` persist agents as JSON (file/sqlite/postgres),
  with owner filter, CRUD, import/export to OpenCode markdown, generated `gen-...` agents
  and propagation into the engine.
- **Evidence:** `workflow-runner/.../store/AgentStore.java`,
  `workflow-runner/.../store/AgentStorePort.java`,
  `quarkus/.../http/AgentStoreResource.java`,
  `quarkus/.../http/AgentCatalogue.java`

### E17 – ReactFlow UI and workflow operations pages

- **V:** 4 / **T:** 2 / **Prio:** P1 / **Status:** implemented
- **OC:** TUI/web UI, but no ReactFlow workflow editor surface.
- **QOC:** `reactflow/` contains editor, dashboard, workflows, runs, agents, HITL, MCP servers,
  A2A, generate, settings, observe, testbench, LLM trace.
- **Evidence:** `reactflow/src/EditorPage.tsx`,
  `reactflow/src/pages/*.tsx`,
  `reactflow/src/services/workflowStore.ts`,
  `quarkus/src/main/resources/META-INF/resources/index.html`

### E18 – Classify node (typed decision model)

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented (external API dependency)
- **OC:** no typed decision classification.
- **QOC:** the `classify` node calls `TypeSafeDecisionClient`, normalizes yes/no/choice/scale,
  computes margin/low-margin and writes state deltas. The guard node can bound
  `low_margin` paths.
- **Evidence:** `workflow-runner/.../runner/TypeSafeDecisionClient.java`,
  `workflow-runner/.../runner/DefaultNodeDispatcher.java:339-408`,
  `workflow-runner/.../runner/DefaultNodeDispatcher.java:299-331`

### E19 – Workflow-specific event types and journal replay

- **V:** 3 / **T:** 3 / **Prio:** P1 / **Status:** implemented
- **OC:** generic event log exists; no workflow-run events.
- **QOC:** `SessionEvent.WorkflowRunStarted/NodeStarted/NodeCompleted/NodeFailed/RunResumed/
  RunFinished/RunFailed` plus `JournalWorkflowEventSink`; replay in `WorkflowRunJournal`.
- **Evidence:** `engine/.../core/model/SessionEvent.java`,
  `workflow-runner/.../runner/JournalWorkflowEventSink.java`,
  `workflow-runner/.../runner/WorkflowRunJournal.java`
- **Note:** event sourcing itself is not a QOC extension; the workflow-run events are.

### E20 – Runtime adaptation (concept)

- **V:** 4 / **T:** 1 / **Prio:** P2 / **Status:** concept
- **OC:** no comparable runtime plan adaptation.
- **QOC:** documents `RequirementChecker`, `AdaptationPlan`, `RuntimePlan`, `PlanValidator`,
  but not implemented in code.
- **Evidence:** `docs/next/runtime-plan-adaptation.md`,
  `docs/plans/runtime-plan-adaptation.md`
- **Status:** not implemented; do not sell as a finished extension.

### E21 – MCP OAuth server

- **V:** 3 / **T:** 1 / **Prio:** P2 / **Status:** concept/stub
- **OC:** MCP client, no OAuth server role.
- **QOC:** configuration `engine.mcp.server.oauth.enabled` and `AuthMode.MCP_OAUTH` exist,
  but `/mcp/authorize` and `/mcp/callback` return `501 Not Implemented`.
- **Evidence:** `quarkus/.../http/McpServerResource.java:165-186`

### E22 – Temporal integration

- **V:** 2 / **T:** 1 / **Prio:** P2 / **Status:** concept
- **OC:** no Temporal integration.
- **QOC:** documents and plans exist, no implementation in runtime code.
- **Evidence:** `docs/engineering/ReactFlow-temporal.md`,
  `docs/plans/Hybrid.md`

### E23 – Quarkus/JVM operations model

- **V:** 3 / **T:** 2 / **Prio:** P2 / **Status:** implemented
- **OC:** Bun/Node-based CLI and server.
- **QOC:** Quarkus 3.40, JVM/native image, systemd/Docker deployment, dev services,
  profiles for memory/SQLite/Postgres.
- **Evidence:** `qoc/pom.xml`, `qoc/quarkus/pom.xml`,
  `docs/deployment/*.md`,
  `docs/betrieb/*` (if present)
- **Note:** this is a platform difference, but relevant for enterprise deployments.

---

## 5. Not counted as QOC extension (baseline/parity)

These capabilities already exist in OC or are ported by QOC; they are not
QOC-specific added value:

| Area | OC evidence | QOC |
|---|---|---|
| Event log, sequences, claims, resume counter | `packages/core/src/session/event.ts`, `execution.ts`, `inbox.ts` | ported |
| Session inbox (STEER/QUEUE) | `packages/core/src/session/inbox.ts` | ported |
| Compaction, revert, snapshots | `packages/core/src/session/compaction.ts`, `revert.ts` | ported |
| Skills (scan, frontmatter, disclosure) | `packages/core/src/skill/*` | ported |
| Subagents/tasks | `packages/core/src/tool/plugin/subagent.ts` | ported |
| ACP | `packages/cli/src/commands/commands.ts` (`acp`) | ported |
| MCP client | `packages/core/src/mcp/*`, `packages/server/src/handlers/mcp.ts` | ported |
| Permissions (deny/ask/allow, saved approvals) | `packages/core/src/permission.ts` | ported |
| Plugins/hooks | `packages/core/src/plugin/*` | ported |
| SQLite/in-memory SQLite | `packages/core/src/database/sqlite.*.ts` | ported (+ Postgres) |
| Basic auth | OC server auth | ported (+ OIDC/Proxy) |

---

## 6. Corrections versus `Features.md`

| Location | Problem | Recommended correction |
|---|---|---|
| `Features.md` §2.4 "16 node types" | true for backend, but editor registers only 14 | "16 backend node types (14 in editor; `guard`/`inject` backend only)" |
| `Features.md` §2.4 "exactly one `end`" | `GraphCompiler` requires at least one `end` | "at least one `end` node" |
| `Features.md` §3 "Guardrails: declarative rules, CDI SPI" | only external webhook | "external webhook guardrail; no declarative rule engine" |
| `Features.md` §4 "Owner/access guard" for runs | `/workflows/runs*` and `/resume` check no ownership | Add guard for runs or restrict wording |
| `Features.md` §2.4 "subworkflow: expanded at start" | no nested execution | "expands the child graph, but does not execute it as a nested run" |

---

## 7. Recommended implementation order

1. **Harden P0:** close ownership gap for workflow runs (E05/E02), HITL ID stability
   (E06), subworkflow execution or clear documentation (E01).
2. **Productize P1:** MCP server resources/prompts (E08), strict structured output as
   an option (E09), make context/contract gates visible in UI (E10/E11).
3. **P1 operations:** guardrail webhook documentation and defaults (E12), resilience metrics
   (E13), tool cache TTL/invalidation (E14).
4. **Prepare P2:** runtime adaptation only as specification (E20), MCP OAuth (E21),
   Temporal (E22) only after a stable workflow platform.

---

## 8. Verdict

The strongest QOC extensions are **workflow runner on the same engine loop**,
**durable runs/resume/recovery**, **OIDC/RBAC/ownership**, **PostgreSQL**,
**durable HITL** and **A2A**. These five (plus workflow generation) should be
treated as QOC core narrative and roadmap focus. Everything else is valuable,
but secondary or still conceptual.