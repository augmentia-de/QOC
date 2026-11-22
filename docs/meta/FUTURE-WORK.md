# Future Work – Planned and Not-Yet-Implemented Parts

> **Status:** 2026-11-22
> **Purpose:** Central overview of all planned, spec-only, or partial parts.
> `STATUS.md` remains the operational status source (what is implemented);
> this file is the planning one (what is planned).

## Delivered since 2026-11-05

| Area | Status | Reference |
|---|---|---|
| Workflow engine + UI | implemented | [`engineering/Workflow.md`](../engineering/Workflow.md) |
| RBAC & security | partial (core) | [`engineering/Workflow-OIDC.md`](../engineering/Workflow-OIDC.md) |
| Structured output | implemented | [`engineering/Structured-Output.md`](../engineering/Structured-Output.md) |
| MCP server persistence | implemented | [`engineering/MCP-UI-Integration.md`](../engineering/MCP-UI-Integration.md) |
| ACP adapter | partial (Phase 2–5) | [`plans/acp-adapter.md`](../plans/acp-adapter.md) |
| Context agents | implemented | [`engineering/Workflow.md`](../engineering/Workflow.md) |
| Tool configuration & presets | implemented | [`plans/tool-configuration.md`](../plans/tool-configuration.md) |
| Agent-specific config (M15–M17) | implemented | tools/permissions/MCP/model/maxSteps, agent-scoped snapshots, `mcpServers` |
| Multi-agent workflow handoff (M18) | implemented | [`engineering/Workflow.md`](../engineering/Workflow.md) (`workflow-runner`) |
| Saga/undo MVP (M1–M4) | implemented | [`plans/saga-undo-tools.md`](../plans/saga-undo-tools.md) |
| GCP native memory / ECS / Keycloak | implemented | [`deployment/README.md`](../deployment/README.md) |

## Spec-only (not yet implemented)

| Area | Document | Description |
|---|---|---|
| Remote Shell | `spec/tools/08-remote-shell.md` | SSH exec/read/write/list/stat + download/upload |
| AWS Lambda Serverless | `deployment/05-aws-lambda-serverless.md` | Serverless mode with agent sync |
| Server/Serverless modes | `guides/deployment-modes.md` | Two modes + MCP tool `qoc_run_agent_with_config` |
| OIDC/Auth0 | `spec/betrieb/04-oidc-auth0.md` | Auth0 as OIDC provider |

## Partial (partially implemented)

| Area | Document | Description |
|---|---|---|
| Quality | `spec/qualitaet/README.md` | Engine coverage 80.26% (2026-11-22); engine/quarkus ArchUnit green, including the dedicated `core.wiring` rule (A2); POM gate not enforced |
| Async Approvals | `plans/async-approval.md` | Phases 1+2+3 implemented; suspend/resume live |
| Webhook Guardrail | `webhook.md` | Specification + service |
| GCP Auth0 | `deployment/03-gcp-auth0.md` | Auth0 on GCP (partial) |
| A2A Server | `guides/a2a.md` | Opt-in server |
| Saga orchestration (M5–M10) | `plans/saga-orchestration.md` | MVP (manual per-call undo) implemented; complete saga (scope, durable compensation, recovery, backup/restore, REST/UI) planned |
| SDLC workflow graph | `engineering/Workflow-SDLC.md` | Phase 1 (ConditionEvaluator) done; graph compiler checks / semantic review still partial |
| Task-level SAGA | `engineering/SAGA.md` | Concept + storage requirements documented; `SagaStore`/durable task-level compensation not implemented (tool-level MVP exists) |
| Directory scoping | `engineering/Directory-Scoping.md` | Plan only (per-task writable directories) |
| Task orchestration | `engineering/Task-Orchestration.md` | Analysis/planning (dependencies, tiered versions, SAGA) |
| RBAC/OIDC – Auth0 | `engineering/Workflow-OIDC.md` | Core RBAC/system-token/ownerId implemented; Auth0 mapping and full OIDC phases spec-only |
| ACP adapter – tests | `plans/acp-adapter.md` | Phase 2–5 implemented; Phase 6 tests outstanding |

## Planned Milestones (M15+)

| # | Deliverable | Status |
|---|---|---|
| M15 | Agent-specific config (tools, permissions, MCP, model, maxSteps) | delivered |
| M16 | Agent-scoped tool snapshot and permission rules | delivered |
| M17 | MCP server subsets per agent | delivered (`mcpServers`) |
| M18 | Multi-agent workflow handoff | delivered (`workflow-runner`) |
| M21 | Saga orchestration (M5–M10): scope, durable compensation, recovery, backup/restore, REST/UI | planned |
| M22 | SDLC workflow graph: graph compiler checks / semantic review | partial |
| M23 | Task-level SAGA + directory scoping | planned |
| M24 | Remote Shell / Lambda serverless / Auth0 / quality gates | spec-only |

## Architecture hygiene (2026-11-22 engine review)

Source: [`review.md`](./review.md) §7. **A1/A2 done 2026-11-22**; A3–A8, A10 and A11
open, A9 withdrawn. Order matters for a redo: **A1 before A2**, otherwise the dedicated
ArchUnit rule fails on the existing `core.wiring` leak.

| # | Measure | Effort | Status |
|---|---|---|---|
| A1 | Decouple `ToolDependencies` from `ToolRegistry` via a core-owned `ToolRegistrar` | S | done (2026-11-22) |
| A2 | Add a dedicated `core.wiring` ArchUnit rule (no runtime/adapter; not S0, because S0 forbids `core.contract`); add `workflow-runner`/`http` to the engine module-boundary rule | S | done (2026-11-22) |
| A3 | Split `runtime/task` (orchestration) from `runtime/tool/subagent` (adapters); keep both quarkus and workflow-runner working | M | open |
| A4 | Move `MAX_CONCURRENT_SUBAGENTS`/`Semaphore` from `quarkus.Engine` to `runtime.task.TaskQuota` | S–M | open |
| A5 | Capability flags or explicit `Unsupported` for minimal-mode `NoOp*` adapters | S | open |
| A6 | Builder for `QocEngine`/`EngineConfig` (keep `create(...)` overloads) | M | open |
| A7 | Document lock ordering (session → claim → journal) | S | open |
| A8 | `MetricsPort` + Micrometer/OTel adapter | M | open |
| A9 | `ContextBuilder` decomposition | — | **withdrawn** (299 lines / 6 fields) |
| A10 | Correct `Layering.md` §7/§8 (workflow layer = `workflow-runner`, task primitives = `engine.runtime.task`) | S | open |
| A11 | Split `quarkus.Engine` (3,558 lines) | L | open (existing §3.1 work) |

Consumer-side rules (separate from A2): `workflow-runner` may use `engine.core`,
`engine.runtime`, `engine.adapter` but not `quarkus`/`cli`; `quarkus` may use `engine` +
`workflow-runner` but not `cli`; `cli` stays HTTP-only. The engine ArchUnit test cannot
enforce these incoming directions; they need small tests in the consumer modules.

## Open L-Items

| ID | Description | Status |
|---|---|---|
| L-1 | Markdown agent files (`agent/<id>.md`) | decided, not implemented |
| L-6 | Command macros (`command/<id>.md` with `$ARGUMENTS`) | decided, not implemented |
| L-7 | Model catalog snapshot and refresh | decided, not implemented |
| Persistent PTY | Across calls | open |

## Planned Features (from Guides)

| Feature | Document | Description |
|---|---|---|
| Server Mode (VM) | `guides/deployment-modes.md` | Agents locally, admin creates new ones |
| Serverless Mode (Lambda) | `guides/deployment-modes.md` | Agents loaded from server, OIDC |
| MCP tool `qoc_run_agent_with_config` | `guides/deployment-modes.md` | Stateless subagent run with config |
| Remote Shell | `spec/tools/08-remote-shell.md` | SSH-based remote operations |

## Planned Documentation

| Document | Description |
|---|---|
| `spec/betrieb/04-oidc-auth0.md` | Auth0/OIDC spec (spec-only) |
| `spec/tools/10-mcp-serverless.md` | MCP server for serverless (optional) |
