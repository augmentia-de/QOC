# Documentation Index

> **Status:** 2026-11-04
> **Purpose:** Role-based entry into `docs`. The operative specification
> is under `spec/`; analysis and reference material is kept separate.

## By role

| I want to … | Start here |
|---|---|
| understand the architecture | `analysis/overview.md` -> `diagrams/` |
| implement the Java engine | `ROADMAP.md` -> `spec/module-plan.md` -> `spec/java-packages.md` |
| understand the session engine flow | `spec/session-engine/README.md` -> `spec/session-engine/04-algorithms.md` |
| build or check tools | `spec/tools/README.md` -> `spec/tools/05-contract-invariants.md` |
| understand rights and policies | `spec/permissions/README.md` -> `spec/permissions/02-evaluation.md` |
| integrate providers | `spec/provider/README.md` -> `spec/provider/02-event-stream.md` |
| understand persistence | `spec/persistence/README.md` -> `spec/persistence/01-jdbc-flyway.md` |
| operate QOC | `spec/betrieb/README.md` -> `spec/betrieb/01-http-streaming.md` |
| deploy QOC | `deployment/README.md` |
| understand server/serverless/worker modes | `guides/deployment-modes.md` |
| run QOC as an agent registry | `guides/Agent-Registry.md` |
| design subagent delegation and worker pools | `guides/Subagent-Delegation.md` |
| check quality and tests | `spec/qualitaet/README.md` -> `spec/qualitaet/01-tests.md` |
| prioritize optimizations | `optimization.md` |
| see open decisions | `open-questions.md` |
| see documentation status | `STATUS.md` |
| see planned / not-yet-implemented work | `FUTURE-WORK.md` |

## Top documents

1. `enterprise.md` - Erweiterungen gegenueber OpenCode
2. `enterprise.md` - Enterprise-Readiness (vier Saeulen)
3. `README.md` - entry point and documentation roles
4. `STATUS.md` - implemented / partial / spec-only
5. `ROADMAP.md` - M0-M14 + M15+
6. `FUTURE-WORK.md` - geplante und nicht implementierte Teile
7. `spec/README.md` - language-neutral specs
8. `spec/module-plan.md` - modules and ports
9. `spec/java-packages.md` - Java packages and ArchUnit
10. `spec/session-engine/README.md` - session engine
11. `spec/tools/README.md` - tool families
12. `open-questions.md` - decisions
13. `optimization.md` - optimization catalog
14. `optimization.md` - prompt minimization and context economy
15. `security.md` - Sicherheitsmodell und OAuth-Zielbild
16. `cicd.md` - CI/CD-Pipeline

## Spec map

| Area | Entry | Files | Status |
|---|---|---:|---|
| Session engine | `spec/session-engine/` | 14 | implemented |
| Tools | `spec/tools/` | 9 | implemented + 1 spec-only |
| Permissions | `spec/permissions/` | 6 | implemented |
| Provider | `spec/provider/` | 7 | implemented |
| Persistence | `spec/persistence/` | 3 | implemented |
| Operations | `spec/betrieb/` | 4 | implemented |
| Quality | `spec/qualitaet/` | 4 | partial |
| Agents | `spec/agents/` | 5 | implemented |
| OIDC/Auth0 | `spec/betrieb/04-oidc-auth0.md` | 1 | spec-only |

## Cross-cutting and tools

- `spec/CONVENTIONS.md` - writing rules
- `spec/INVARIANTS.md` - invariant register
- `diagrams/README.md` - diagram index and legend
- `tools/check-docs.mjs` - link, table and index check
- `tools/coupling-analysis.mjs` - coupling analysis

## Guides

QOC-specific implementation guides; the operative spec remains under `spec/`:

- `guides/langchain4j.md` - LangChain4j integration und Ersatzpotenzial
- `analysis/learnings/deepseek-harness.md` - Learnings aus deepseek-harness fuer QOC
- `guides/OS-based-security.md` - running under a dedicated OS user
- `guides/LoadAgentConfig.md` - reloading agent configuration
- `guides/a2a.md` - A2A client and server configuration
- `skill-loading.md` - skill discovery, filtering and per-agent configuration
- `optimization.md` - ideas and sketch catalog
- `guides/deployment-modes.md` - Server/Serverless-Modi (planned, spec-only)
- `guides/research-agent.md` - Research-Agent (async, parallel)

## Deployment

- `deployment/README.md` - Uebersicht aller Zielumgebungen
- `deployment/01-local-docker.md` - JVM/native Container
- `deployment/02-gcp-native.md` - GCP VM + nginx + systemd
- `deployment/03-gcp-auth0.md` - Auth0 OIDC auf GCP
- `deployment/04-aws-ecs-minimal.md` - ECS/Fargate/EFS/Neon
- `deployment/05-aws-lambda-serverless.md` - Lambda-spezifisch
- `deployment/06-security-hardening.md` - Mounts, Sandbox, Egress, Secrets
- `deployment/07-serverless-free-tier.md` - Free-Tier-Vergleich und Konfiguration

## Reference material

These documents are analysis and reference material, not an operative
implementation specification:

- `analysis/overview.md`
- `analysis/01-use-cases.md`
- `analysis/02-data-flow.md`
- `analysis/03-bounded-contexts.md`
- `analysis/04-change-impact.md`
- `analysis/flow-db-service/README.md`
- `memory-use.md` - Memory-Nutzung und DB-/File-Storage waehrend eines Tasks
