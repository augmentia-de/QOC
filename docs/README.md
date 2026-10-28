# QOC – Documentation

> **Status:** 2026-10-25  
> **Reference:** QOC Java engine (Java 21, Quarkus, LangChain4j).  
> **Role:** Entry point into the documentation. The operative specification
> is under [`spec/`](./spec/README.md); analysis and reference material is kept
> separate.

## Start here

| If you want to … | Start here |
|---|---|
| get an overview | [`INDEX.md`](./INDEX.md) |
| see the implementation status | [`STATUS.md`](./STATUS.md) |
| implement | [`ROADMAP.md`](./ROADMAP.md) → [`spec/README.md`](./spec/README.md) |
| understand the architecture | [`overview.md`](./analysis/overview.md) → [`diagrams/`](./diagrams/README.md) |
| look up decisions | [`open-questions.md`](./open-questions.md) |
| optimize | [`improve.md`](./improve.md) |

## Two roles

### Operative specification

Authoritative for the Java implementation:

| Document | Content |
|---|---|
| [`spec/README.md`](./spec/README.md) | Language-neutral specs, dependencies, reading order |
| [`spec/module-plan.md`](./spec/module-plan.md) | Modules, ports, topological order |
| [`spec/java-packages.md`](./spec/java-packages.md) | Java packages, visibility, ArchUnit |
| [`spec/session-engine/`](./spec/session-engine/README.md) | Session engine, events, algorithms, invariants |
| [`spec/tools/`](./spec/tools/README.md) | Tool contract, filesystem, Bash, MCP, Subagent, Remote Shell |
| [`spec/permissions/`](./spec/permissions/README.md) | Rule chain, wildcards, effects |
| [`spec/provider/`](./spec/provider/README.md) | Model call, stream, errors, budget, cache |
| [`spec/persistence/`](./spec/persistence/README.md) | Database, transactions, migrations |
| [`spec/betrieb/`](./spec/betrieb/README.md) | HTTP/SSE, skills, credentials |
| [`spec/qualitaet/`](./spec/qualitaet/README.md) | Tests, architecture gates, implementability |
| [`spec/agents/`](./spec/agents/README.md) | Agent profiles, tool restrictions, MCP scoping |
| [`ROADMAP.md`](./ROADMAP.md) | M0–M14 and the current hardening phase |
| [`open-questions.md`](./open-questions.md) | Decision register |
| [`improve.md`](./improve.md) | Optimization catalog (working state) |
| [`optimize_prompt.md`](./optimize_prompt.md) | Prompt minimization and context economy |
| [`STATUS.md`](./STATUS.md) | implemented / partial / spec-only |
| [`spec/CONVENTIONS.md`](./spec/CONVENTIONS.md) | Writing and structure rules |
| [`spec/INVARIANTS.md`](./spec/INVARIANTS.md) | Invariant register |

### QOC guides and working notes

QOC-specific implementation aids, but not normative spec:

| Document | Content |
|---|---|
| [`l4j-use.md`](./guides/l4j-use.md) | LangChain4j integration in the Java engine |
| [`OS-based-security.md`](./guides/OS-based-security.md) | Operation under a dedicated OS user, path permissions |
| [`LoadAgentConfig.md`](./guides/LoadAgentConfig.md) | Reloading agent configuration at runtime |
| [`ideas.md`](./guides/ideas.md) | Ideas and sketch catalog |

### Reference and analysis material

These documents are valuable for context, but **not** an operative
implementation specification:

| Document | Content |
|---|---|
| [`overview.md`](./analysis/overview.md) | Architecture and feature overview of OpenCode V2 |
| [`01-use-cases.md`](./analysis/01-use-cases.md) | Use cases, business rules, requirements |
| [`02-data-flow.md`](./analysis/02-data-flow.md) | Data flow, events, tables, lifecycles |
| [`03-bounded-contexts.md`](./analysis/03-bounded-contexts.md) | Module boundaries, coupling, strangler plan |
| [`04-change-impact.md`](./analysis/04-change-impact.md) | Hotspots, invariants, impact matrix |
| [`flow-db-service/`](./analysis/flow-db-service/README.md) | Deep data flow of a prompt |
| [`refactor/`](./analysis/refactor/refactor-v2.md) | Prompt and refactoring templates |
| [`ANALYSIS-ARCHITECTURE-JAVA.md`](./analysis/ANALYSIS-ARCHITECTURE-JAVA.md) | meta-analysis of the Java architecture |
| [`pi-learnings.md`](./analysis/pi-learnings.md) | Experiences from the pi coding agent |
| [`oc-open.md`](./analysis/oc-open.md) | Learnings from OpenCode for QOC |

## Documentation tools

| Tool | Purpose |
|---|---|
| [`tools/check-docs.mjs`](./tools/check-docs.mjs) | Link, table, marker and index check |
| [`tools/coupling-analysis.mjs`](./tools/coupling-analysis.mjs) | Coupling and hub analysis |
| [`diagrams/README.md`](./diagrams/README.md) | Diagram index and deterministic legend |

## Four analysis workflows (reference)

The analysis documents follow four workflows:

1. **Use Cases & Business Logic** – `01-use-cases.md`
2. **Data Flow & Lineage** – `02-data-flow.md`
3. **Bounded Contexts** – `03-bounded-contexts.md`
4. **Change Impact & Guardrails** – `04-change-impact.md`

They describe the investigated OpenCode reference. For the QOC implementation,
[`spec/`](./spec/README.md) is authoritative.

## Conventions

- Statements with source references; interpretation is marked as such.
- Diagrams as Mermaid in code blocks.
- Specs follow [`spec/CONVENTIONS.md`](./spec/CONVENTIONS.md).
- No changes to product code as part of pure documentation work.

## Verification

```bash
node docs/tools/check-docs.mjs
```

When code changes are involved, additionally:

```bash
mvn test
```

A commit is only permitted once `mvn test` is green and JaCoCo reaches at
least 80% instructions.
