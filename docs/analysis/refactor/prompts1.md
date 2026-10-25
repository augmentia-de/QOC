# Prompt Log & Result

> **Purpose:** Traceability of all instructions to the assistant in this
> working phase – original text, decisions, result.
>
> **Period:** 2026-10-21 (start) – 2026-10-22 (completion)
> **Repository:** `<oc-checkout>` · OpenCode V2 Monorepo · Branch `dev` @ `3108505b2f`
> **Result files:** `docs/` (7 Markdown documents + 1 tool), **untracked**, nothing committed

---

## 1. One-Sentence Result

> **Result:** Out of an empty directory grew a complete, source-verified orientation
> map for the OpenCode V2 monorepo: **4,564 lines of documentation in
> seven files** (`overview`, `packages`, `01-use-cases`, `02-data-flow`,
> `03-bounded-contexts`, `04-change-impact`, `README`) plus a reproducible
> coupling-analysis script – with 19 use cases as Gherkin, 69 business rules,
> 40 invariants, 53 event-to-table lineages, 13 bounded contexts and a
> change-impact matrix that describes what breaks where when something changes.

---

## 2. Prompt Log (Original Text, Chronological)

### P1 – Clone the Repo

**Prompt (Original):**

```
Load the repo
```

*Guess (typo-distorted):* "Load the repo … (download)".

**Follow-up to the user:**

> What should I do? (The working directory `oc` is empty and was created today.)
> – *Clone GitHub repo* / *Report Opencode bug* / *Load repo from local path* / *Fetch submodule/dependency*

**Answer:** `Clone GitHub repo`
**Follow-up:** Which repository should I clone? – *anomalyco/opencode* / *sst/opencode (Upstream)* / *I will name the URL*

**Answer:** `anomalyco/opencode`

**Result:** `git clone https://github.com/anomalyco/opencode.git .` in
`<oc-checkout>` – 742 MB, 2248 remote branches, HEAD `3108505b2f`,
version 2.1.2. Additionally: Codebase-Memory-Graph indexed (44,297 nodes /
213,584 edges, mode `fast`).

---

### P2 – First Overall Analysis

**Prompt (Original):**

```
Analyze the project use cases, data flow, prompts, permission system etc write a first overview.md
```

*Guess:* "Analyze the project – use cases, data flow, prompts, permission system etc. Write a first overview.md".

**Approach:** Graph index → 4 parallel deep analyses (architecture/use cases,
data flow/persistence, prompts/agents, permissions/security) + own
first-hand reads of core files + comparison with `specs/v2/*`.

**Result:** `docs/overview.md` – 13 chapters: architecture, package structure,
19 use cases, end-to-end data flow with ASCII diagram, persistence (SQLite/Drizzle,
14 tables), event/message model, prompts & 7 agents, tools & MCP, permission system,
providers/models, HTTP API, configuration, development & operation, open points.

---

### P3 – Read the Functional Specifications

**Prompt (Original):**

```
read files in <oc-checkout>/specs/v2
```

**Approach:** All 6 files read (`README.md`, `session.md`, `tools.md`,
`provider-policy.md`, `event-stream-architecture.md`, `catalog-config-plugin-lifecycle.md`).

**Result:** Four corrections/enrichments to `overview.md`:
* `policies` is **top-level**, `experimental.policies` is deprecated (the spec
  still shows the old location) – with merge order and V1 migration
* New section **9.3 Event Feed (SSE)**: "encode once", 4,096 frames per
  connection, overflow/encoding-error semantics, benchmark (10 clients −89.3%,
  50 clients −97.8%)
* New section **9.4 Catalog/Config/Plugin Lifecycle** (historical decision)
* `experimental` table: `todo` (default **off**), `subagent_depth` (1),
  `location_idle_timeout` (60 min), `portable_shell_scanner`

---

### P4 – Read the User Documentation

**Prompt (Original):**

```
read <oc-checkout>/services/www/src/docs
```

**Approach:** Structure via `lib/navigation.ts` (5 sections, 58 pages), then the
top-level `content/*.mdx` in full and the CLI/build/console pages structurally
captured.

**Result:** Concrete corrections to `overview.md`:
* **Config precedence** exactly: direct `settings.json(c)` files from outside in,
  *then* the `.opencode/` files; system folder and macOS managed preferences
  override everything
* **Policy precedence** has **6 levels** (Console → macOS-managed → System → Global →
  direct → `.opencode/`)
* **`external_directory` is `allow` by default** – external access produces
  no prompt
* **V2 knows only `AGENTS.md`**, `CLAUDE.md` is not a fallback
* **No `scout` agent in V2**; `request` overlays are kept by the runner but
  **not sent**
* Tool limits (`read` 2,000 lines/50 KiB/20 MiB, `shell` 2 min, `webfetch` 120 s,
  `patch` only for GPT models), code-mode runtime limits, `browser` namespace
* New section **7.10 OpenCode Console**: workspaces, SSO/SCIM, budgets,
  Go/Go Plus ($10/$40), API keys, harnesses (Claude Code, Codex, Hermes, Pi, Raycast)

---

### P5 – Package Inventory

**Prompt (Original):**

```
I have moved overview into the docs folder. now read step by step all folders in <oc-checkout>/packages create a packages.md that briefly describes what is to be found in the respective folder
```

**Supplied with it – the methodological framework (original text):**

```
Here are four concrete, agent-based workflows for use case extraction, data flow mapping and system decomposition that have proven themselves in practice.
Workflow 1: Automated Use-Case & Business-Logic Extraction

Goal: Transfer the unwritten domain code buried in the legacy system into clear, readable requirements (e.g. Gherkin/BDD scenarios).

[Legacy Code] ➔ [AST Parser / Chunking] ➔ [LLM (Domain Expert Persona)] ➔ [Gherkin / Functional Specs]

Steps:

    Structured preprocessing (AST Parser):
    Do not read the code raw. Use simple parsers (e.g. Tree-sitter) to prepare classes, methods and function calls as structured syntax trees (AST) or compact file lists.

    LLM prompting with domain-role focus:
    Feed the LLM module by module with the following system prompt:

        "You are an experienced Domain-Driven Design (DDD) expert and business analyst. Analyse the following legacy module. Ignore purely technical boilerplate code. Extract all implicit business logic, validation rules, edge cases and user workflows. Format the result as a structured functional specification (user stories with acceptance criteria in Gherkin format: Given/When/Then)."

    Aggregation step:
    A second agent consolidates the generated partial specifications into a consistent domain documentation (Functional Requirements Document).

Workflow 2: Data Flow & Data Lineage Mapping
Goal: Understand how data flows in the legacy system, which tables are modified and where side effects lurk.
[Codebase & DB Schema] ➔ [Static Analysis / grep] ➔ [LLM Data-Flow-Agent] ➔ [Mermaid.js Diagrams & Data Dictionary]
Steps:
    Identify entry points:
    Have the AI search for entry points (API endpoints, controllers, cron jobs, SQL triggers).

    Data-flow tracing via LLM:
    Pass the LLM the code of a specific workflow together with the database schema and ask:

        "Trace the lifecycle of the entity 'Order'. Which tables are read, which are modified? Where do business-logic transformations take place? Document the data flow as a step-by-step list."

    Automatic diagram generation:
    Have the AI generate Mermaid.js sequence diagrams or data-flow diagrams directly, which you can render in Markdown:

        "Create a Mermaid.js sequence diagram from the analysed data flow that shows the interaction between frontend, legacy service and database."

Workflow 3: Modular Decomposition (Finding Bounded Contexts)
Goal: Split the monolith into clean, self-contained domains in order to prepare the Strangler Fig approach.
Steps:
    Build a dependency matrix:
    Generate an import or call diagram of the legacy system (e.g. via code metric tools).

    AI-supported cluster analysis:
    Feed the LLM the list of all modules and their mutual dependencies:

        "Here is the module dependency and call table of our legacy system. Propose a split into self-contained 'Bounded Contexts' according to Domain-Driven Design. Justify the boundaries on the basis of cohesion (high internal coupling) and coupling (low external dependencies). Identify circular dependencies that must be resolved first."
```

**Approach:** Each of the 35 folders checked individually (`package.json`: name, version,
`private`, exports, scripts, workspace deps; directory structure; `src/`; READMEs).
Counts verified against reality at the end (48 protocols, 52 providers,
16 built-in tools, 77 schema modules, 47 UI components).

**Result:** `docs/packages.md` (596 lines) + `docs/tools/coupling-analysis.mjs`
(reproducible measurement script).

---

### P6 – The Four Workflow Documents

**Prompt (Original):**

```
create more files I want to understand everything for changes proceed roughly like this, step by step, document in suitable files:
```

*(followed by the same workflow block as in P5, with the addition
"…system decomposition that has proven itself in practice.")*

**Approach:** Workflows 1–3 were implemented as files 01–03, workflow 4
(change impact) interpreted as a logical continuation – justified in the file
header and in `README.md`. Three analysis agents in parallel for data flow,
decomposition and change impact; own measurements with the coupling script.

**Result:**

| File | Content |
|---|---|
| `docs/01-use-cases.md` | 19 use cases (37 Gherkin scenarios), 69 business rules, validation rules, 13 FR + 5 NFR |
| `docs/02-data-flow.md` | 146 HTTP ops, 53 events (47 durable/6 ephemeral) → projection → table, data dictionary (18 tables, 4 unused), 9 entity lifecycles, 20 side effects, 4 Mermaid diagrams |
| `docs/03-bounded-contexts.md` | dependency matrix, 12 SCCs, 13 bounded contexts, 16 boundary violations, 12-step strangler plan, 2 Mermaid diagrams |
| `docs/04-change-impact.md` | hotspots, 40 invariants (IF/THEN), change-impact matrix, test strategy, 22 pitfalls |
| `docs/README.md` | index + the four workflows as applied here |

**Cross-check and corrections** (delivered by the analysis agents and
incorporated):

| # | Correction |
|---|---|
| 1 | **146 HTTP operations**, not 139 – `V2_HTTP_API_AUDIT.md` is from 2026-09-13 |
| 2 | **Core tests run via `bun run script/test.ts`**, not `bun test` |
| 3 | **`session.tool.input.delta` is never emitted** (schema-only) |
| 4 | **Four tables completely unused**: `session_pending`, `project_directory`, `account`, `account_state`/`control_account` |
| 5 | **File mutations are not durably logged** (own TODO in the code) |
| 6 | Instruction files run via `SessionEvent.Synthetic` (otherwise layer cycle) |
| 7 | There are **18** `AGENTS.md` files; `packages/core/AGENTS.md` does **not** exist |
| 8 | The only **real** structural cycle is a **44-file SCC** |

---

### P7 – Closing

**Prompt (Original):**

```
uit
```

**Result:** Closing overview, no further changes.

---

## 3. Prompt Patterns (Derived)

| Pattern | Observation | Consequence |
|---|---|---|
| **Typo-robust** | Three of seven prompts were mangled (`Ladeade dasdas reporepo funte`, `Analysierenalysiere dasdas Projekt`) | Interpretation + **explicit follow-up** instead of guessing – in 2 of 3 cases the follow-up hit the intent |
| **One sentence + one artifact** | Each prompt names exactly **one** target artifact (`overview.md`, `packages.md`, "more files") | Delivery as **one file per goal**, not as a collective dump |
| **Methodology supplied** | From P5 on the methodological framework (4 workflows) is supplied | The documents follow the supplied framework rather than an own structure |
| **Step by step** | P5 and P6 emphasize the step-by-step approach | Per step: first measure, then write, then verify |
| **Read before write** | P3/P4 are pure reading assignments | Result was **correction/deepening** of existing statements, not new files |
| **Verification built in** | Notably: the users never asked about correctness | Quality assurance ran via source citations + coverage checks + cross-check by subagents |
| **Directory change** | `overview.md` was moved to `docs/` independently | Own folder for personal analysis documents, separate from the product |

---

## 4. Produced Artifacts (Complete)

| Path | Lines | Size | Status |
|---|---:|---:|---|
| `docs/README.md` | 101 | 4.6 KB | untracked |
| `docs/overview.md` | 1,672 | 95 KB | untracked (moved from repo root) |
| `docs/packages.md` | 596 | 29 KB | untracked |
| `docs/01-use-cases.md` | 705 | 41 KB | untracked |
| `docs/02-data-flow.md` | 586 | 28 KB | untracked |
| `docs/03-bounded-contexts.md` | 557 | 29 KB | untracked |
| `docs/04-change-impact.md` | 347 | 22 KB | untracked |
| `docs/tools/coupling-analysis.mjs` | 184 | 8 KB | untracked |
| `overview.md` (repo root) | 1,672 | 95 KB | untracked – **byte-identical** to `docs/overview.md`, presumably leftover original |

**Untouched:** no product code, no configuration, no migration, no commit.

---

## 5. Recommendation for the Next Session

1. **Delete `overview.md` in the repo root** (duplicate of `docs/overview.md`).
2. **Create a project-level skill** that encapsulates the core findings in
   `.opencode/skills/opencode-v2-orientation/` – then the orientation data is
   also available in *other* sessions of this repo.
3. **Work off the open knowledge gaps** (listed in `04-change-impact.md` §8):
   `variant.ts` (691 lines), `plugin/provider/opencode.ts` (812 lines),
   `shell/scan.ts` (2,410 lines), `github-copilot/` (4,315 lines), `session/stats.ts`,
   plus the **console harnesses** (Claude Code, Codex, Hermes, Pi, Raycast) –
   those have **no** functional specification in the repo.
4. **Recommendation from `03`**: `packages/core/src/shell/scan.ts` (2,410 lines) and
   `packages/core/src/github-copilot/` (4,315 lines) are the two
   lowest-risk extraction candidates; `instance.ts` is the actual lever.
