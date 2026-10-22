# docs – personal analysis documentation on OpenCode

> Working notes for orientation in the repository. **Not an official project
> document** – the authoritative sources are `AGENTS.md`, `specs/v2/*`,
> `SECURITY.md` and `services/www/src/docs/content/`.
>
> As of: 2026-10-21 · repo state `dev` @ `3108505b2f` (version 2.1.2)

## Purpose

This series answers the question: **"What happens when I change X?"** – from
user behaviour down to the database row. It is deliberately organised around
the four agentic analysis workflows that have proven useful for working with an
unknown large project.

## Documents

| File | Workflow | Result | Scope |
|---|---|---|---|
| [`overview.md`](./overview.md) | Entry | Architecture, use cases, data flow, prompts, rights, providers, interfaces, configuration | 13 chapters, 1,671 lines |
| [`packages.md`](./packages.md) | Inventory | What lives in each folder under `packages/`, dependency direction, reading recommendation | 35 folders, 596 lines |
| [`01-use-cases.md`](./01-use-cases.md) | **1 – Use-case extraction** | 19 use cases as Gherkin (37 scenarios), 69 business rules, validation rules, 13 FR + 5 NFR | 705 lines |
| [`02-data-flow.md`](./02-data-flow.md) | **2 – Data flow & lineage** | Entry points (146 HTTP ops), 53 events (47 durable/6 ephemeral) with projection target, data dictionary (18 tables, 4 unused), entity lifecycles, side effects | 4 Mermaid diagrams, 586 lines |
| [`03-bounded-contexts.md`](./03-bounded-contexts.md) | **3 – Modular decomposition** | Dependency matrix, 12 SCCs, 13 bounded contexts, 16 boundary violations, 12-step strangler plan | 2 Mermaid diagrams, 557 lines |
| [`04-change-impact.md`](./04-change-impact.md) | **4 – Change impact & guardrails** | Hotspots, 40 invariants as IF/THEN rules, change-impact matrix, test strategy, 22 pitfalls | 347 lines |
| [`tools/coupling-analysis.mjs`](./tools/coupling-analysis.mjs) | Tool | reproducible coupling and hub analysis | `node docs/tools/coupling-analysis.mjs` |
| [`flow-db-service/`](./flow-db-service/README.md) | Flow depth | complete data flow of a prompt: coarse → 4 stages → error handling, scenario "create service of the DB reads" | 7 files |
| [`spec/session-engine/`](./spec/session-engine/README.md) | Language-neutral spec | Session engine as algebraic types, pseudocode, state machines, 111 verified invariants + 2 audit reports + Java implementation template + execution model + prompt catalogue | 14 files |
| [`spec/tools/`](./spec/tools/README.md) | Language-neutral spec | Tool contract, filesystem, bash with HITL, MCP, 56 invariants, Java implementation | 7 files |
| [`spec/permissions/`](./spec/permissions/README.md) | Language-neutral spec | Rule chain, wildcard semantics, three answers, catalogue vs. authorisation, 55 invariants | 6 files |
| [`spec/provider/`](./spec/provider/README.md) | Language-neutral spec | Model call, event stream, error classification, retry, catalogue, budget, cache, 47 invariants | 7 files |
| [`spec/persistence/`](./spec/persistence/README.md) | Language-neutral spec | Database structure: tables, transactions, indexes, dead tables, PostgreSQL/Flyway, data ownership per bounded context | 3 files |
| [`spec/betrieb/`](./spec/betrieb/README.md) | Language-neutral spec | Base environment: HTTP/SSE contract, skills, credentials, startup order, 46 operational rules | 4 files |
| [`spec/qualitaet/`](./spec/qualitaet/README.md) | Check | Reconciliation of quality points; test specification, ArchUnit pipeline with CI gate, implementability audit | 4 files |
| [`spec/module-plan.md`](./spec/module-plan.md) | Blueprint | 35 modules topologically by dependency, 16 ports, simplification right, 15 milestones | 1 file |
| [`spec/java-packages.md`](./spec/java-packages.md) | Blueprint | Java packages under `de.augmentia.oc.engine`, visibilities, ArchUnit layer rules, test packages | 1 file |
| [`prompts.md`](./prompts.md) | Work log | All 23 prompts of this session verbatim, one sentence per result | 23 entries |
| [`refactor.md`](./refactor.md) | Template | 20 generally usable prompts derived from 30 prompts, with derivation matrix and machine-checked logic | 20 prompts, 6 groups |
| [`refactor-v2.md`](./refactor-v2.md) | Working template | 43 prompts in 8 phases with guidelines, gates, rollback and evidence chain; V1 coverage and gaps machine-checked | 43 prompts, 7 gates |
| [`open-questions.md`](./open-questions.md) | Register | Deliberately open points: what they mean, simple implementation, acceptance test, recommended order | 14 main points, 20 detailed decisions, 10 architecture proposals |

## The four workflows (as applied here)

### Workflow 1 – Use-case & business-logic extraction

```
[Code] ➔ [structure parsing] ➔ [LLM with domain-role focus] ➔ [Gherkin/BDD + FR document]
```

*Structure parsing* instead of raw reading: codebase-memory graph (44,297 nodes,
213,584 edges) for symbol and edge resolution, domain boundaries from
`schema`/`protocol`/`core` as chunking, `specs/v2/*.md` as scaffolding.
*Domain-role focus*: DDD expert + business analyst → implicit business logic,
validation rules, edge cases instead of boilerplate.
*Aggregation*: use cases → business rules → functional requirements.
→ `01-use-cases.md`

### Workflow 2 – Data flow & data lineage mapping

```
[Codebase & DB schema] ➔ [static analysis] ➔ [LLM data-flow agent] ➔ [Mermaid + data dictionary]
```

*Entry points* from `protocol/groups`, `cli/commands`, app routes, MCP/plugin/RPC.
*Tracing* per entity through event → projection → table → reader.
*Diagrams* as Mermaid source (renderable in markdown).
→ `02-data-flow.md`

### Workflow 3 – Modular decomposition (bounded contexts)

```
[Import/call diagram] ➔ [LLM cluster analysis] ➔ [bounded contexts + migration plan]
```

*Dependency matrix* from `package.json` (package level) and the graph (module level).
*Cluster analysis* by cohesion/coupling, including cyclic dependencies.
*Result*: contexts, boundary violations, strangler-fig order.
→ `03-bounded-contexts.md`

### Workflow 4 – Change impact & guardrails

> This workflow was truncated in the specification; the numbering and the goal
> ("understand everything **for changes**") suggest: what are the effects of a
> change, which invariants must it satisfy, and how do you verify them?
> The file is structured accordingly – adjust if needed.

```
[Hotspots + invariants] ➔ [LLM impact analysis] ➔ [impact matrix + verification plan]
```

*Hotspots* via fan-in from the graph. *Invariants* from `specs/v2/*`,
`AGENTS.md` and code comments as testable IF/THEN rules.
*Impact matrix*: typical change requests → affected files, contracts,
generators, tests, migrations.
→ `04-change-impact.md`

## Conventions in this series

* Every statement carries a **source reference** (path + line or measurement).
* Unconfirmed material is marked as *(unconfirmed)*, not omitted.
* Interpretation is labelled as such and separated from verified facts.
* Diagrams as Mermaid in code blocks so they render in markdown/GitHub.
* No changes to product code – analysis only.

## Verification of these notes

```
bun install                     # dependencies
bun run check                   # lint + typecheck in root
bun typecheck                   # in the respective package
```

Tests do **not** run from the repo root (guard `do-not-run-tests-from-root`),
but per package, e.g. `packages/core`. Skills: `.opencode/skills/{unit-tests,
tui-tests,app-e2e-tests,component-tests}`.