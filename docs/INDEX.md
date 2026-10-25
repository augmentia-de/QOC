# Documentation Index

> **Status:** 2026-10-25  
> **Purpose:** Role-based entry into `docs`. The operative specification
> is under [`spec/`](./spec/README.md); analysis and reference material is kept
> separate.

## By role

| I want to … | Start here |
|---|---|
| understand the architecture | [`overview.md`](./analysis/overview.md) → [`diagrams/`](./diagrams/README.md) |
| implement the Java engine | [`ROADMAP.md`](./ROADMAP.md) → [`spec/module-plan.md`](./spec/module-plan.md) → [`spec/java-packages.md`](./spec/java-packages.md) |
| understand the session engine flow | [`spec/session-engine/README.md`](./spec/session-engine/README.md) → [`spec/session-engine/04-algorithms.md`](./spec/session-engine/04-algorithms.md) |
| build or check tools | [`spec/tools/README.md`](./spec/tools/README.md) → [`spec/tools/05-contract-invariants.md`](./spec/tools/05-contract-invariants.md) |
| understand rights and policies | [`spec/permissions/README.md`](./spec/permissions/README.md) → [`spec/permissions/02-evaluation.md`](./spec/permissions/02-evaluation.md) |
| integrate providers | [`spec/provider/README.md`](./spec/provider/README.md) → [`spec/provider/02-event-stream.md`](./spec/provider/02-event-stream.md) |
| understand persistence | [`spec/persistence/README.md`](./spec/persistence/README.md) → [`spec/persistence/01-jdbc-flyway.md`](./spec/persistence/01-jdbc-flyway.md) |
| operate QOC | [`spec/betrieb/README.md`](./spec/betrieb/README.md) → [`spec/betrieb/01-http-streaming.md`](./spec/betrieb/01-http-streaming.md) |
| check quality and tests | [`spec/qualitaet/README.md`](./spec/qualitaet/README.md) → [`spec/qualitaet/01-tests.md`](./spec/qualitaet/01-tests.md) |
| prioritize optimizations | [`improve.md`](./improve.md) |
| see open decisions | [`open-questions.md`](./open-questions.md) |
| see documentation status | [`STATUS.md`](./STATUS.md) |

## Top documents

1. [`README.md`](./README.md) – entry point and documentation roles
2. [`STATUS.md`](./STATUS.md) – implemented / partial / spec-only
3. [`ROADMAP.md`](./ROADMAP.md) – M0–M14
4. [`spec/README.md`](./spec/README.md) – language-neutral specs
5. [`spec/module-plan.md`](./spec/module-plan.md) – modules and ports
6. [`spec/java-packages.md`](./spec/java-packages.md) – Java packages and ArchUnit
7. [`spec/session-engine/README.md`](./spec/session-engine/README.md) – session engine
8. [`spec/tools/README.md`](./spec/tools/README.md) – tool families
9. [`open-questions.md`](./open-questions.md) – decisions
10. [`improve.md`](./improve.md) – optimization catalog

## Spec map

| Area | Entry | Files | Status |
|---|---|---:|---|
| Session engine | [`spec/session-engine/`](./spec/session-engine/README.md) | 14 | implemented |
| Tools | [`spec/tools/`](./spec/tools/README.md) | 9 | implemented + 1 spec-only |
| Permissions | [`spec/permissions/`](./spec/permissions/README.md) | 6 | implemented |
| Provider | [`spec/provider/`](./spec/provider/README.md) | 7 | implemented |
| Persistence | [`spec/persistence/`](./spec/persistence/README.md) | 3 | implemented |
| Operations | [`spec/betrieb/`](./spec/betrieb/README.md) | 4 | implemented |
| Quality | [`spec/qualitaet/`](./spec/qualitaet/README.md) | 4 | partial |
| Agents | [`spec/agents/`](./spec/agents/README.md) | 5 | implemented |

## Cross-cutting and tools

- [`spec/CONVENTIONS.md`](./spec/CONVENTIONS.md) – writing rules
- [`spec/INVARIANTS.md`](./spec/INVARIANTS.md) – invariant register
- [`diagrams/README.md`](./diagrams/README.md) – diagram index and legend
- [`tools/check-docs.mjs`](./tools/check-docs.mjs) – link, table and index check
- [`tools/coupling-analysis.mjs`](./tools/coupling-analysis.mjs) – coupling analysis

## Guides

QOC-specific implementation guides; the operative spec remains under `spec/`:

- [`guides/l4j-use.md`](./guides/l4j-use.md) – LangChain4j integration
- [`guides/OS-based-security.md`](./guides/OS-based-security.md) – running under a dedicated OS user
- [`guides/LoadAgentConfig.md`](./guides/LoadAgentConfig.md) – reloading agent configuration
- [`guides/ideas.md`](./guides/ideas.md) – ideas and sketch catalog

## reference material

These documents are analysis and reference material, not an operative
implementation specification:

- [`overview.md`](./analysis/overview.md)
- [`01-use-cases.md`](./analysis/01-use-cases.md)
- [`02-data-flow.md`](./analysis/02-data-flow.md)
- [`03-bounded-contexts.md`](./analysis/03-bounded-contexts.md)
- [`04-change-impact.md`](./analysis/04-change-impact.md)
- [`flow-db-service/`](./analysis/flow-db-service/README.md)
- [`refactor/`](./analysis/refactor/refactor-v2.md)
