# Documentation Inventory and Consolidation Recommendation

> **Status:** 2026-11-22
> **Purpose:** Overview of all Markdown files in `docs/` with categorization,
> redundancy analysis, and consolidation recommendation. The operative specification
> under `spec/` is excluded from consolidation.

---

## 1. Categorization

### 1.1 Original Specification (`spec/`) — DO NOT TOUCH

The operative, language-neutral specification. Source of truth.

| File/Folder | Content |
|---|---|
| `spec/README.md` | Entry point, reading order, module plan |
| `spec/module-plan.md` | Modules, ports, dependencies, M0–M14 |
| `spec/java-packages.md` | Java packages, ArchUnit rules |
| `spec/CONVENTIONS.md` | Writing rules for specs |
| `spec/INVARIANTS.md` | Invariants register (I-*, P-*, W-*, etc.) |
| `spec/learnings.md` | ⚠️ Misnamed – contains learnings, not backlog |
| `spec/session-engine/` (14 files) | Session engine: types, events, states, algorithms, provider, errors, invariants |
| `spec/tools/` (10 files) | Tool contract, filesystem, bash, MCP, subagent, remote shell (spec-only), MCP server |
| `spec/permissions/` (6 files) | Permission types, evaluation, effect matrix, invariants, Java impl |
| `spec/provider/` (7 files) | Provider types, event stream, classification, catalog, invariants, Java impl |
| `spec/persistence/` (3 files) | JDBC/Flyway, data ownership |
| `spec/betrieb/` (5 files) | HTTP streaming, skills, credentials, OIDC/Auth0 (spec-only) |
| `spec/qualitaet/` (4 files) | Tests, architecture pipeline, implementability, Docker E2E |
| `spec/agents/` (5 files) | Agent config, tool restrictions, MCP scoping, workflows |

### 1.2 Top-Level Operative Documents

| File | Content | Status |
|---|---|---|
| `ROADMAP.md` | M0–M14 + M15+ milestones, Java impl phases | Active |
| `STATUS.md` | Implemented/Partial/Spec-only per area | Active |
| `INDEX.md` | Role-based entry point, spec map, guides, deployment | Active |
| `FUTURE-WORK.md` | Central overview of planned/unimplemented parts | New |
| `README.md` | Documentation roles, entry point | Active |
| `Features.md` | Compact feature inventory (engine + workflow runner) | Active |
| `review.md` | Code review 2026-11-09 + addendum 2026-11-22 + §7 engine architecture review | Active |
| `open-questions.md` | Decision register (mostly decided) | Reference |
| `optimization.md` | Optimization catalog | Working |
| `resilience.md` | Robust agent infrastructure (retry, triage, escalation) | Working |
| `CONFIG.md` | Copy-paste configuration profiles | Active |
| `settings.md` | Configuration hierarchy and modes | Active |
| `maintenance.md` | Database maintenance/cleanup | Active |

### 1.3 Security & Deployment

| File/Folder | Content | Status |
|---|---|---|
| `security.md` | Security model, OAuth target picture, Auth0 plan | Uncommitted, active |
| `cicd.md` | CI/CD pipeline, operating modes, deployment strategy | Uncommitted, active |
| `enterprise.md` | Enterprise readiness (4 pillars) | Working |
| `enterprise.md` | Extensions vs. OpenCode | Reference |
| `deployment/` (8 files) | Local Docker, GCP native, GCP Auth0, AWS ECS, Lambda, security hardening, free tier | Active |

### 1.4 Guides

| File | Content | Status |
|---|---|---|
| `guides/a2a.md` | A2A client/server configuration | Implemented |
| `guides/deployment-modes.md` | Server/serverless/worker modes | Design document |
| `optimization.md` | Ideas/sketch catalog | Working |
| `guides/langchain4j.md` | LangChain4j integration guide | Implemented |
| `guides/LoadAgentConfig.md` | Agent config reload | Implemented |
| `guides/OS-based-security.md` | OS-user-based security | Implemented |
| `guides/deployment-modes.md` | Server/serverless modes (spec-only, superseded) | Spec-only |
| `guides/Subagent-Delegation.md` | Worker pool, routing, cloud topologies | Design (not implemented) |
| `guides/a2a.md` | A2A client/server | Implemented |

### 1.5 Analysis & Reference

| File/Folder | Content | Status |
|---|---|---|
| `analysis/overview.md` | System overview | Reference |
| `analysis/01-use-cases.md` | Use cases, Gherkin, acceptance tests | Reference |
| `analysis/02-data-flow.md` | Data flow, events, tables | Reference |
| `analysis/03-bounded-contexts.md` | Module boundaries, coupling | Reference |
| `analysis/04-change-impact.md` | Pitfalls, guardrails | Reference |
| `analysis/ANALYSIS-ARCHITECTURE-JAVA.md` | Meta-analysis Java architecture | Reference |
| `analysis/a2a.md` | A2A integration analysis | Reference |
| `analysis/flow-db-service/` (7 files) | Deep analysis DB service flow | Reference |
| `analysis/learnings.md` | Shared learnings (D-01–D-14) | Working |
| `analysis/analysis/learnings/nessy.md` | Learnings from Nessy | Working |
| `analysis/analysis/learnings/oc-open.md` | Learnings from OpenCode | Working |
| `analysis/analysis/learnings/oh-my-pi.md` | Learnings from oh-my-pi | Working |
| `analysis/analysis/learnings/pi.md` | Learnings from pi coding agent | Working |

### 1.6 Diagrams

| File | Content |
|---|---|
| `diagrams/01-system-context.md` to `11-security-and-mcp.md` | Mermaid diagrams: system context, architecture, runtime dataflow, session lifecycle, class model, dependencies, tool-permission chain, agent orchestrator, persistence, deployment, security/MCP |
| `diagrams/README.md` | Diagram index and legend |

### 1.7 Plans & Issues

| File | Content | Status |
|---|---|---|
| `plans/async-approval.md` | Async approvals (phases 1+2+3 implemented) | Partial |
| `plans/e2e-security-test.md` | E2E security test plan | Working |
| `plans/acp-adapter.md` | ACP adapter (Phase 2–5) | Partial |
| `plans/tool-configuration.md` | Tool configuration, presets, permission JSON | Implemented |
| `plans/saga-undo-tools.md` | Saga undo MVP M1–M4 (implemented) | Implemented |
| `plans/saga-orchestration.md` | Complete saga M5–M10 (opt-in via `SagaPolicy`) | Plan |
| `issues/qoc-findings.md` | Current problems/findings | Working |

### 1.8 Engineering

| File | Content | Status |
|---|---|---|
| `engineering/Workflow.md` | Workflow orchestration + ReactFlow integration | Implemented |
| `engineering/Workflow-OIDC.md` | RBAC, system user, OIDC | Partial (core RBAC; Auth0 spec-only) |
| `engineering/Workflow-SDLC.md` | AI-SDLC workflow graph | Partial |
| `engineering/Workflow-Startup.md` | Workflow stack startup guide | Working |
| `engineering/Quarkus-Workflow.md` | Quarkus workflow integration | Working |
| `engineering/Structured-Output.md` | JSON-schema output for agents/workflows | Implemented |
| `engineering/MCP-UI-Integration.md` | MCP tools in the UI | Implemented |
| `engineering/Task-Orchestration.md` | Dependencies, tiered versions, SAGA | Plan |
| `engineering/SAGA.md` | Task-level SAGA concept | Concept |
| `engineering/InMemory.md` | In-memory runtime | Implemented |
| `engineering/Native-Deployment.md` | Native deployment | Implemented |
| `engineering/Unified-Storage.md` | Unified storage configuration | Implemented |
| `engineering/Multi-Memory.md` | Selectable storage backends | Implemented |
| `engineering/Storage-alternative.md` | Storage alternatives per port | Analysis |
| `engineering/Two-Agent-Systems.md` | QOC agents vs workflow agents | Analysis |
| `engineering/Directory-Scoping.md` | Per-task writable directories | Plan |
| `engineering/Additional-Nodes.md` | Dynamic node registration | Working |
| `engineering/Async-use.md` | Async agent calls and heartbeat | Concept |
| `engineering/ReAct-Workflow.md` | ReAct workflow architecture | Plan |
| `engineering/ReAct-Implementation.md` | ReAct implementation plan | Plan |
| `engineering/ReactFlow-temporal.md` | ReactFlow + engine analysis | Draft |
| `engineering/Test.md` | Manual test plan | Working |

### 1.9 Top-Level Extensions

| File | Content | Status |
|---|---|---|
| `enterprise.md` | Extensions vs. OpenCode | Reference |
| `enterprise.md` | Enterprise readiness (4 pillars) | Working |
| `skill-loading.md` | Skill discovery, filtering, per-agent config | Implemented |
| `context-build.md` | Context building, skill-permission gaps | Design draft |
| `skill-loading.md` | Skill discovery and filtering | Implemented |
| `optimization.md` | Prompt minimization, context economy | Design draft |
| `understand.md` | LLM call flow & tool execution | Design note |
| `webhook.md` | Webhook guardrail service | Partial |
| `analysis/learnings/deepseek-harness.md` | Learnings from deepseek-harness | Working |
| `guides/langchain4j.md` | LangChain4j usage/replacement potential | Working |
| `skill-loading.md` | Skill loading | Implemented |

---

## 2. Redundancies

### R1: LangChain4j documentation (2 files)
- `docs/guides/langchain4j.md` (375 lines) – "Replacement potential and delimitation"
- `docs/guides/langchain4j.md` (213 lines) – "Integration guide"
- **Overlap:** Both cover LangChain4j usage in QOC.
- **Recommendation:** Merge into `guides/langchain4j.md` (integration + delimitation).

### R2: Serverless/deployment modes (3 files)
- `docs/guides/deployment-modes.md` – Server/serverless modes (spec-only, superseded)
- `docs/guides/deployment-modes.md` – Server/serverless/worker (design)
- `docs/guides/Subagent-Delegation.md` – Worker pool, routing, cloud topologies (design)
- **Overlap:** All three describe deployment modes and cloud topologies.
- **Recommendation:** Merge into `guides/deployment-modes.md` (single file; remove server-serverless-modes.md or attach as appendix).

### R3: Learnings (5 files) — ✅ DONE
- `docs/analysis/learnings.md` (main file + index)
- `docs/analysis/learnings/` (nessy.md, oc-open.md, oh-my-pi.md, pi.md, deepseek-harness.md)
- **Action:** 5 learnings files moved to `analysis/learnings/`, index added to `learnings.md`.

### R4: Extensions (2 files) — ✅ DONE
- `docs/enterprise.md` (extended with OC-plus content)
- `docs/OC-plus.md` deleted, references updated.

### R5: Ideas/sketches (2 files) — ✅ DONE
- `docs/optimization.md` (extended with ideas from ideas.md)
- `docs/guides/ideas.md` deleted, references updated.

### R6: Spec-implementation-backlog (misnamed) — ✅ DONE
- `docs/spec/implementation-backlog.md` renamed to `docs/spec/learnings.md`.

### R7: refactor-v2.md (archival) — ✅ DONE
- `docs/analysis/refactor/` deleted (empty), references removed.

---

## 3. Potential TODOs (Spec-only / Partial)

| File | Status | Description |
|---|---|---|
| `spec/tools/08-remote-shell.md` | spec-only | Remote shell (SSH) – exec/read/write/list/stat implemented, download/upload missing |
| `spec/betrieb/04-oidc-auth0.md` | spec-only | Auth0 OIDC – claim mapping partially implemented |
| `deployment/05-aws-lambda-serverless.md` | spec-only | Lambda mode – scripts present, implementation pending |
| `guides/deployment-modes.md` | spec-only | Server/serverless modes (superseded by deployment-modes.md) |
| `guides/Subagent-Delegation.md` | spec-only | Worker pool, routing (not implemented) |
| `spec/qualitaet/` | partial | ArchUnit/JaCoCo gates |
| `plans/async-approval.md` | partial | Async approvals (phases 1+2+3 implemented) |
| `webhook.md` | partial | Webhook guardrail service |
| `guides/a2a.md` | implemented | A2A client/server |
| `context-build.md` | design draft | Context building, skill-permission gaps |
| `optimization.md` | design draft | Prompt minimization (has corruption markers) |

---

## 4. Consolidation Recommendation

### Immediate (high benefit, low risk)

1. **`guides/langchain4j.md` + `guides/langchain4j.md`** → `guides/langchain4j.md`
   - Integration guide + delimitation in one file.

2. **`guides/deployment-modes.md`** → Integrate into `guides/deployment-modes.md`
   - server-serverless-modes.md is marked as superseded.

3. **`optimization.md`** → Integrate into `optimization.md`
   - Ideas and optimizations in one file.

4. **`spec/learnings.md`** → Rename to `learnings.md` or move to `analysis/`
   - Misnamed; contains learnings.

### Medium term (moderate effort)

5. **Consolidate learnings:** `analysis/learnings/nessy.md`, `analysis/learnings/oc-open.md`, `analysis/learnings/oh-my-pi.md`, `analysis/learnings/pi.md`, `analysis/learnings/deepseek-harness.md` → `analysis/learnings/` folder or integrate into `analysis/learnings.md`.

6. **`enterprise.md` + `enterprise.md`** → Merge or attach `enterprise.md` as an appendix in `enterprise.md`.

7. **Fix `optimization.md`** (remove corruption markers `|`).

### Do not consolidate (original spec)

The entire `spec/` structure remains unchanged. It is the operative specification and source of truth. In particular:
- `spec/session-engine/`, `spec/tools/`, `spec/permissions/`, `spec/provider/`, `spec/persistence/`, `spec/betrieb/`, `spec/qualitaet/`, `spec/agents/`
- `spec/README.md`, `spec/module-plan.md`, `spec/java-packages.md`, `spec/CONVENTIONS.md`, `spec/INVARIANTS.md`

### Do not touch (reference/archive)

- `analysis/` (reference material, deliberately separate)
- `diagrams/` (architecture diagrams)
- `plans/` (time-limited implementation plans)
- `issues/` (working issue register)
- `tools/` (documentation tools)

---

## 5. Summary

| Category | Files | Consolidate? |
|---|---|---|
| Original spec (`spec/`) | ~50 files | ❌ No (source of truth) |
| Top-level operative | 7 files | No |
| Security & deployment | 10+ files | No (newly structured) |
| Guides | 8 files | ✅ Consolidated (R1, R2, R5) |
| Analysis & reference | 16 files | ✅ Consolidated (R3) |
| Diagrams | 12 files | No |
| Plans & issues | 3 files | No (time-limited) |
| Top-level extensions | 9 files | ✅ Consolidated (R4, R6) |

**Recommended immediate actions:** R1, R2, R5, R6 (merge/rename 4 files, low risk).
**Medium term:** R3, R4, R7 (consolidate learnings, merge extensions).
