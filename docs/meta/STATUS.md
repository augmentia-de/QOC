# Documentation Status

> **Status:** 2026-11-22
> **Reference:** QOC Java engine under `qoc/`.  
> **Purpose:** Quick overview of which documents are specification, implementation
> or working material. The status is a documentation statement, not a substitute
> for build and test results.

## Status legend

| Status | Meaning |
|---|---|
| `implemented` | Spec is implemented in code; build/tests remain authoritative |
| `partial` | Parts are implemented or coverage is not fully verified |
| `spec-only` | Specification exists, implementation still missing |
| `reference` | Analysis/reference material, not an operative spec |
| `working` | Working catalog or decision register, no commitment |

## Operative specification

| Area | Document | Status | Note |
|---|---|---|---|
| Session engine | [`spec/session-engine/`](../spec/session-engine/README.md) | `implemented` | M0–M14 per [`ROADMAP.md`](./ROADMAP.md); build remains authoritative |
| Tools – base families | [`spec/tools/`](../spec/tools/README.md) | `implemented` | Filesystem, Bash, MCP |
| Tools – Subagent | [`spec/tools/07-subagent.md`](../spec/tools/07-subagent.md) | `implemented` | `SubagentTool`/`SubagentRunner` available |
| Tools – Remote Shell | [`spec/tools/08-remote-shell.md`](../spec/tools/08-remote-shell.md) | `spec-only` | `download`/`upload` incl. recursive copy not yet implemented |
| Permissions | [`spec/permissions/`](../spec/permissions/README.md) | `implemented` | Rule chain and effect matrix |
| Provider | [`spec/provider/`](../spec/provider/README.md) | `implemented` | Classification, budget, cache, event stream |
| Persistence | [`spec/persistence/`](../spec/persistence/README.md) | `implemented` | SQLite/PostgreSQL adapter |
| Operations | [`spec/betrieb/`](../spec/betrieb/README.md) | `implemented` | HTTP/SSE, skills, credentials |
| Quality | [`spec/qualitaet/`](../spec/qualitaet/README.md) | `partial` | Engine + Quarkus ArchUnit green, including the dedicated `core.wiring` rule (A2, 2026-11-22); coverage 80.26% (2026-11-22); POM gate not enforced |
| Agents | [`spec/agents/`](../spec/agents/README.md) | `implemented` | Catalog, tool restrictions, MCP scoping |
| Agents – SDLC workflow | [`spec/agents/05-aisdlc-workflow.md`](../spec/agents/05-aisdlc-workflow.md) | `partial` | SDLC process as a deterministic workflow graph (SDW-01…08); ConditionEvaluator implemented (Phase 1), graph compiler checks outstanding |
| OIDC/Auth0 | [`spec/betrieb/04-oidc-auth0.md`](../spec/betrieb/04-oidc-auth0.md) | `spec-only` | Auth0 as OIDC provider (planned) |
| Optimization | [`optimization.md`](../optimization.md) | `working` | Working catalog, no obligation |
| Diagrams | [`diagrams/`](../diagrams/README.md) | `reference` | Architecture and data flow diagrams |

## Extensions (over OpenCode)

| Feature | Document | Status | Note |
|---|---|---|---|
| OC-plus overview | [`enterprise.md`](../enterprise.md) | `reference` | Extensions over OpenCode |
| Enterprise readiness | [`enterprise.md`](../enterprise.md) | `working` | Four pillars + implementation plan |
| Async approvals | [`plans/async-approval.md`](../plans/async-approval.md) | `partial` | Phases 1+2+3 implemented; suspend/resume + tool result cache live |
| Skills | [`skill-loading.md`](../skill-loading.md) | `implemented` | BM25, per-agent allow/deny/prompt |
| A2A | [`guides/a2a.md`](../guides/a2a.md) | `implemented` | Client + opt-in Server |
| Server/Serverless-Modi | [`guides/deployment-modes.md`](../guides/deployment-modes.md) | `spec-only` | Two modes + MCP tool (planned) |
| AWS ECS minimal | [`deployment/04-aws-ecs-minimal.md`](../deployment/04-aws-ecs-minimal.md) | `implemented` | Test-Stack (ECS/Fargate/EFS/Neon) |
| AWS Lambda Serverless | [`deployment/05-aws-lambda-serverless.md`](../deployment/05-aws-lambda-serverless.md) | `spec-only` | Lambda-specific (planned) |
| Webhook Guardrail | [`webhook.md`](../webhook.md) | `partial` | Specification + service |
| SDLC-Workflow-Graph | [`engineering/Workflow-SDLC.md`](../engineering/Workflow-SDLC.md) | `working` | Complete definition (Nodes/Edges/Agents); migration plan Phase 1 (ConditionEvaluator) done 2026-11-14 |
| Workflow engine | [`engineering/Workflow.md`](../engineering/Workflow.md) | `implemented` | `workflow-runner`: graph compiler, executor, stores (memory/SQLite/Postgres), sub-workflows, context agents, node contracts, structured output, `verifyOnStop`, workflow generator |
| Workflow UI | [`engineering/Workflow.md`](../engineering/Workflow.md) | `implemented` | ReactFlow editor under `/workflow/`: workflows, runs, agents, MCP servers, settings, i18n, mobile |
| RBAC & security | [`engineering/Workflow-OIDC.md`](../engineering/Workflow-OIDC.md) | `partial` | Principal propagation, HMAC tool tokens, `SecureRest`, A2A OIDC, system-token, `ownerId`/`rolesAllowed`, execution/session guards; local Keycloak. Auth0 mapping and full OIDC phases remain spec-only |
| Structured output | [`engineering/Structured-Output.md`](../engineering/Structured-Output.md) | `implemented` | Java/YAML/OpenAPI schema sources, `strict` flag, automatic `contextRefs`, configurable output limit |
| MCP server persistence | [`engineering/MCP-UI-Integration.md`](../engineering/MCP-UI-Integration.md) | `implemented` | V12 migration, `SqlMcpServerStore`, CRUD/reload API, UI page |
| ACP adapter | [`plans/acp-adapter.md`](../plans/acp-adapter.md) | `partial` | Phase 2–5: JSON-RPC over stdio, session/new, prompt, cancel, load, list, delete, close, config, mode, auth; Phase 6 tests outstanding |
| Context agents | [`engineering/Workflow.md`](../engineering/Workflow.md) | `implemented` | `compactAfterRun`, compact nodes, `engine.context.default-task` (default `start`) |
| Tool configuration | [`plans/tool-configuration.md`](../plans/tool-configuration.md) | `implemented` | Dynamic tool list, presets, permission-check endpoint, `rolesAllowed` vs `permissions`, least-privilege default |
| Deployment – native memory | [`deployment/README.md`](../deployment/README.md) | `implemented` | `deploy/gcp-native-memory` (no DB), native memory runner, GCP/AWS ECS |
| Deployment – Keycloak | [`deployment/README.md`](../deployment/README.md) | `implemented` | Local Keycloak (`deploy/keycloak`, `deploy/docker-oidc-native`); Auth0 remains spec-only (`deployment/03-gcp-auth0.md`) |
| Resilience & retry | [`resilience.md`](../resilience.md) | `partial` | Error classification, retry policy, loop detector, webhook alerter, optional LLM triage; `SessionWake` is an intentional no-op |
| In-memory runtime | [`engineering/InMemory.md`](../engineering/InMemory.md) | `implemented` | All storage/coordination ports replaceable by in-memory adapters (no restart recovery) |
| Memory/storage analysis | [`memory-use.md`](../memory-use.md) | `reference` | Memory and DB/file storage during a task |
| Saga / Undo (M1–M4) | [`plans/saga-undo-tools.md`](../plans/saga-undo-tools.md) | `implemented` | Contract, registry, generated `undo_<tool>`, Engine wiring; reference pairs `write → delete` and `worktree_create → worktree_remove`; 1191 engine / 143 Quarkus tests green (2026-11-22) |
| Saga orchestration (M5–M10) | [`plans/saga-orchestration.md`](../plans/saga-orchestration.md) | `spec-only` | Complete saga: scope, durable compensation state machine, recovery, backup/restore, REST/UI; opt-in via `SagaPolicy` (`off/manual/observe/best-effort/strict`) |

## Cross-cutting

| Document | Purpose |
|---|---|
| [`INDEX.md`](./INDEX.md) | Role-based entry |
| [`spec/CONVENTIONS.md`](../spec/CONVENTIONS.md) | Writing and structure rules for specs |
| [`spec/INVARIANTS.md`](../spec/INVARIANTS.md) | Register of invariant prefixes and sources |
| [`open-questions.md`](./open-questions.md) | Decision register (mostly decided) |
| [`ROADMAP.md`](./ROADMAP.md) | Implementation order M0–M14 |
| [`FUTURE-WORK.md`](./FUTURE-WORK.md) | Planned and not-yet-implemented parts |
| [`deployment/`](../deployment/README.md) | Deployment documentation (GCP, AWS, Docker) |

## Maintenance rule

When changing any spec or milestone:

1. Check status in this file.
2. Update affected README indices.
3. Run `node docs/tools/check-docs.mjs`.
4. For code changes, additionally run `mvn test` in `qoc/`.
