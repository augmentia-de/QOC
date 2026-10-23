# Roadmap – Java implementation of the Session Engine

> **Purpose:** Step-by-step build plan for the Java 21 implementation
> under `de.augmentia.oc.engine`. Each step is individually testable and ends
> with a commit. Do not parallelise – M0 before M1, …, M14 last.
>
> **Basis:** `spec/module-plan.md` §6–§9, `spec/java-packages.md`,
> `spec/session-engine/Java-Impl.md` §13–§14, `open-questions.md` (all points decided).
>
> **Status:** 2026-10-22 · Repo `dev` @ `3108505b2f`.

## 0. Roadmap vs. reference

**Roadmap (work through now, in this order):**
1. `README.md` (docs) → `spec/README.md` → `spec/module-plan.md` → `spec/java-packages.md`
2. `spec/session-engine/README.md` → `01` → `02` → `03` → `04` → `06` → `07`
3. `spec/session-engine/Java-Impl.md` (absorbs `11`/`12`)
4. `spec/tools/01`→`02/03/04`→`05`; `spec/permissions/01`→`02`→`03`→`04`; `spec/provider/01`→`02`→`03`→`04`→`05`
5. `spec/persistence/README`→`02-data-ownership`→`01-jdbc-flyway`; `spec/betrieb/README`→`01`→`02`→`03`
6. `spec/qualitaet/01-tests`, `02-architecture-pipeline`, `03-implementability`
7. `open-questions.md` §10 (decision log)

**Reference (only for a concrete question):**
- `overview.md`, `packages.md` (orientation in the TS repo)
- `01-use-cases.md` (business rules, Gherkin, acceptance tests)
- `02-data-flow.md` (entry points, events, tables)
- `03-bounded-contexts.md` (module boundaries, coupling)
- `04-change-impact.md` (pitfalls, guardrails)
- `flow-db-service/*` (real-code evidence when porting/debugging)
- `spec/session-engine/08-porting.md` (TS mapping), `09-invariants-audit.md` (evidence)
- `spec/session-engine/13-internal-prompts.md` (prompt composition)
- `ANALYSIS-ARCHITECTURE-JAVA.md` (meta-analysis)

**ARCHIVAL (deferred):** `prompts.md`, `prompts1.md`, `refactor.md`,
`session-engine/10` (deleted), `11`/`12` (absorbed into Java-Impl).

## 1. Prerequisites (once)

- Java 21 (virtual threads final, pattern-matching switch final)
- One artifact with ArchUnit gates (root `de.augmentia.oc.engine`, stages S0–S8 + `adapter.*`)
- PostgreSQL + Flyway
- Quarkus (HTTP/SSE) – only for S8
- Decisions from `open-questions.md` §10 are all taken; no open questions
  remain before the first commit.

**Commit 0:** `chore(java): scaffold de.augmentia.oc.engine with ArchUnit gates`

## 2. Step by step (M0–M14)

Each step: read spec → implement → tests green → commit.
Tests run without real infrastructure (fakes from M3).

### 2.0 Overview

```
M0 values/json/wildcard/shellscan  →  M1 contract types  →  M2 pure evaluators
        │
        ▼
M3 ports + fakes  →  M4 chain/registry  →  M5 credentials/provider
        │
        ▼
M6 tool families  →  M7 tool runtime  →  M8 step  →  M9 session/delivery
        │
        ▼
M10 compaction  →  M11 revert  →  M12 execution  →  M13 SQL adapter  →  M14 outer skin
```

Stage mapping: S0 (M0, M3) · S1 (M1) · S2 (M2) · S3 (M4, M5) ·
S4 (M6, M7) · S5 (M8, M10) · S6 (M9, M10, M11) · S7 (M12) · S8 (M14).
M13 proves the fakes (M3) are faithful.

| # | Stage | Modules | Key deliverable | Done when | Invariants |
|---|---|---|---|---|---|
| **M0** | S0 | `core` (values, json, wildcard, shellscan) | opake IDs, JSON codec, wildcard, shell scan | round-trip + pattern tests | A-03, A-04 |
| **M1** | S1 | `core` (event types, contracts, message) | sealed ADTs, contract types | all types serialisable | — |
| **M2** | S2 | `core` (permission.rule, provider.classify/budget/affinity, tool.output) | rule chain, classification, budget, affinity, bounding | P-*/V-* green | P-01…P-04, V-30…V-56 |
| **M3** | S0 | `core` (ports + fakes) | 16 ports + 13 fakes | contract tests on fakes | — |
| **M4** | S3 | `runtime` (tool.registry, skill, permission.engine) | registry, skills, permission engine | deny-before-approval, channels | I-06 |
| **M5** | S3 | `runtime` (credential, provider.client) | env-only creds, HTTP/SSE adapter | V-20…V-29, V-33…V-36 | V-20…V-29, V-33…V-36, CR-03/04 |
| **M6** | S4 | `runtime` (tool.fs, tool.shell, tool.skill, tool.mcp) | read/write/edit/patch/glob/grep, bash, MCP | all tool invariants green | W-30…W-80, SK-09/11 |
| **M7** | S4 | `runtime` (tool.runtime) | decode→permission→execute→encode chain | I-04, I-05 as properties | I-04, I-05 |
| **M8** | S5 | `runtime` (context, step) | ModelRequest, sequencer (L-C), bounding | I-04, I-05 as properties | I-04, I-05 |
| **M9** | S6 | `runtime` (session, delivery) | admission, idempotency, delivery txn | I-01… I-03, I-02… I-11 | I-01… I-03, I-02… I-11 |
| **M10** | S5/S6 | `runtime` (compaction) | exactly one compaction, epoch move | I-07, I-09 as properties | I-07, I-09 |
| **M11** | S6 | `runtime` (revert) | snapshot boundary, conditional delete | revert invariants green | — |
| **M12** | S7 | `runtime` (execution) | claim, doorbell, abort, resume | I-03, I-10-01 as properties | I-03, I-10-01 |
| **M13** | — | `adapter.sql` (all ports) | PostgreSQL schema + all adapters | M3 contract tests green on Postgres | — |
| **M14** | S8 | `http` (httpapi, wiring) | HTTP/SSE, wiring, agent catalogue, config | end-to-end prompt runs | — |

### 2.1 Details (per step)

**M0** — Package: `core`. Spec: `module-plan.md` §4.1;
`permissions/02-evaluation.md` §2; `tools/02-filesystem.md` §1. Opake IDs,
money/token/time, JSON encode/decode against declarative schemata, Wildcard
(`*`, `?`), shell scan.
Commit: `feat(engine): M0 values, json, wildcard, shellscan`

**M1** — Package: `core`. Spec: `session-engine/01-types.md`; `tools/01-types.md`;
`permissions/01-types.md`; `provider/01-types.md`. Algebraic types as
`sealed` + `record`: Event ADT, Tool contract, Permission types, Provider
types, Message. Commit: `feat(engine): M1 contract types`

**M2** — Package: `core`. Spec: `permissions/02-evaluation.md`; `provider/03-classification.md`;
`provider/04-catalog-response.md` §5; `tools/02-filesystem.md`. Rule chain,
Wildcard semantics, error classification, output budget, cache affinity,
bounding (two levels). Commit: `feat(engine): M2 pure evaluators`

**M3** — Package: `core`. Spec: `module-plan.md` §3, §7.3. All 16 ports as interfaces; 13
fakes as real implementations (no mocking framework).
Commit: `feat(engine): M3 ports and fakes`

**M4** — Package: `runtime`. Spec: `tools/01-types.md`, `tools/02-filesystem.md` §1;
`permissions/02-evaluation.md`, `03-effect-matrix.md`; `betrieb/02-skills.md`.
Frontmatter/visibility, registration/close, epoch, deny-before-approval, channel
separation. Commit: `feat(engine): M4 toolregistry, skills, instructions, permissionengine`

**M5** — Package: `runtime`. Spec: `betrieb/03-credentials.md`; `provider/02-event-stream.md`,
`03-classification.md`. Env-only (P-1), resolution order, HTTP transport,
event-stream reading, classification.
Commit: `feat(engine): M5 credentials and provider adapter`

**M6** — Package: `runtime`. Spec: `tools/02-filesystem.md`, `03-bash-hitl.md`, `04-mcp.md`,
`05-contract-invariants.md`. read/write/edit/patch/glob/grep, Bash (scanner,
HITL, PTY), MCP (namespace, discovery, OAuth), skill activation.
Commit: `feat(engine): M6 tool families (fs, shell, skill, mcp)`

**M7** — Package: `runtime`. Spec: `tools/01-types.md` §contract;
`session-engine/04-algorithms.md` §G. Chain decode→permission→execute→encode,
hooks, bounding, defect vs. error, snapshot freezing.
Commit: `feat(engine): M7 tool runtime`

**M8** — Package: `runtime`. Spec: `session-engine/04-algorithms.md` §D–§G;
`session-engine/05-provider.md`. Context loading, ModelRequest, tool chain,
sequencer (L-C), bounding, skill list in instruction part, overflow compacts
once. Commit: `feat(engine): M8 context and step`

**M9** — Package: `runtime`. Spec: `session-engine/02-events.md`; `04-algorithms.md` §A–§C.
Admission, idempotency, delivery as transaction, modes, `session_inbox`.
Commit: `feat(engine): M9 session and delivery`

**M10** — Package: `runtime`. Spec: `session-engine/04-algorithms.md` (compaction);
`13-internal-prompts.md` (CTX-SUMMARY). Exactly one compaction, epoch
movement, raw events preserved (compaction as view).
Commit: `feat(engine): M10 compaction`

**M11** — Package: `runtime`. Spec: `session-engine/04-algorithms.md` (revert);
`tools/02-filesystem.md` (SnapshotStore). Snapshot boundary, conditional
deletion of instructions, `SnapshotStore`.
Commit: `feat(engine): M11 revert`

**M12** — Package: `runtime`. Spec: `session-engine/03-states.md` (execution automaton);
`12-execution-model.md`; `Java-Impl.md` §9. Claim (write-ahead), doorbell,
abort, resume, one run per session, startup recovery with durable counter.
Commit: `feat(engine): M12 execution, claim, resume`

**M13** — Package: `adapter.sql`. Spec: `persistence/README.md`, `01-jdbc-flyway.md`,
`02-data-ownership.md`; `module-plan.md` §8 (M13). PostgreSQL schema (Flyway)
+ all adapters. **Same** contract tests as M3, unchanged, against PostgreSQL
(Testcontainers). Commit: `feat(engine): M13 sql adapters (contract tests green on postgres)`

**M14** — Package: `http`. Spec: `betrieb/01-http-streaming.md`, `02-skills.md`,
`03-credentials.md`; `session-engine/13-internal-prompts.md`. HTTP/SSE
contract (18 + command endpoint), wiring, provider adapter, live feed, agent
catalogue (L-1), config (L-2).
Commit: `feat(engine): M14 httpapi and wiring (end-to-end prompt)`

### 2.2 Hints (not mandatory)

- **First LLM test with a skill:** possible from **M8** onward (step-level,
  with M5 provider + M6 `tool.skill` + M7 runtime green); session-level from
  **M9**; end-to-end over HTTP/SSE from **M14**.
- **Compaction / revert / MCP / skills** are optional for a first green run:
  they live in `runtime` but can be stubbed until the core path (M0–M9, M12)
  is green.
- **ArchUnit** can only be added once the affected packages exist; add the
  rules incrementally, not all at once.
- **Pure-Java boundary:** `engine` must not depend on Quarkus/CDI/HTTP. If a
  new dependency seems to require Quarkus, it belongs in `quarkus`, not in
  `engine`.

## 3. Java-Impl phases (coarse milestones)

From `Java-Impl.md` §13 (functional chain, not parallelisable):

| Phase | Content | Done when |
|---|---|---|
| 1 | `model`, `error`, `json`, `event`, `port.store`, `adapter.sql`: types, events, three error channels, schema + Flyway, `Journal.append` | An event lies in `event` **and** in both projections; transaction atomic |
| 2 | Inbox: admission, delivery, modes, idempotency | I-01-01…I-03, I-02-01…I-11 as properties |
| 3 | Execution: claim, doorbell, abort, resume | A crash mid-run is resumed on next start (I-03, I-10-01) |
| 4 | Step: context, ModelRequest, tool chain, sequencer, bounding | I-04, I-05 as properties |
| 5 | Permissions: rule model, prompt, saved approvals, rejection as abort | I-06 complete; rejection produces **no** model-side text |
| 6 | Compaction, live feed, provider adapter | I-07, I-09, I-10 |

**Do not parallelise:** Phase 1 is the basis for everything. Without protocol
and projections no test is meaningful.

## 4. Acceptance checklist (before "done")

From `Java-Impl.md` §14:

- [ ] `mvn -q verify` green (Unit + Integration + Container)
- [ ] All 111 invariants referenced as tests or justified as "not applicable"
- [ ] `sealed` event type enforces completeness of both projections (I-07-02)
- [ ] Rejection case produces **no** model-side tool text (I-06-09)
- [ ] No `catch (Exception)` at a tool boundary (S-6)
- [ ] No error path swallows a persistence error (I-07-03)
- [ ] Claim released only by a terminal event; shutdown leaves it (I-03-03, I-03-04)
- [ ] Crash with open claim resumes the run, with durable counter (I-10-01)
- [ ] `session_claim` never deleted by shutdown
- [ ] Live feed encodes **once** per event, kills only the overflowing connection (I-07-09)
- [ ] Tool calls run in parallel, publication ordered (L-C)
- [ ] Compaction **replaces** nothing – transcript stays complete (I-09-01)

## 5. Test strategy (short)

- Three test kinds: pure test (S0–S2), contract test (every port),
  module test (S3–S7) — see `module-plan.md` §7.
- Contract tests are the core: one test class against fake **and** real adapter.
- No mocking framework; fakes are real implementations.
- Tests must not run from the repo root (guard `do-not-run-tests-from-root`).

## 6. Commit convention

`type(scope): summary` — Types: `feat`, `fix`, `docs`, `chore`, `refactor`,
`test`. Scope: `engine`, `core`, `tui`, `app`, `sdk`, `plugin`, …

Examples:
- `feat(engine): M0 values, json, wildcard, shellscan`
- `test(engine): M3 port contract tests green on fakes`
- `feat(engine): M13 sql adapters (contract tests green on postgres)`

Every milestone M0–M14 = one commit (or more if sensible), but **green before
the next**.

## 7. Prohibitions (short)

From `module-plan.md` §9: no imports of higher stages; `provider` knows no
session/tool/permission; tools write no events; `session` calls no provider/step;
clock only via `Clock`; ports not implemented by the consumer; no real DB for
rules.

---

> **Note:** This roadmap is the **sequential reading list**. Everything else
> (`overview`, `packages`, `01-use-cases`, `02-data-flow`, `03-bounded-contexts`,
> `04-change-impact`, `flow-db-service`, `08`/`09`, `13`) is reference and is read
> only when a concrete question arises.
## 3. Future milestones (M15+)

| # | Stage | Modules | Key deliverable | Done when |
|---|---|---|---|---|
| **M15** | S8 | `http`, `runtime` | Agent-specific config (tools, permissions, MCP, model, maxSteps) | agent with deny cannot call tool |
| **M16** | S4/S8 | `runtime` (tool.runtime), `http` | Agent-scoped tool snapshot and permission rules | filtered tools in prompt request |
| **M17** | S3/S8 | `runtime` (tool.mcp), `http` | MCP server subsets per agent | MCP tools filtered by agent |
| **M18** | S7/S8 | `runtime` (execution), `http` | Multi-agent workflow handoff | workflow runs plan → build → review |

These milestones extend M0–M14 without changing their invariants.
