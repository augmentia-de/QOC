# Analysis: Understandability of the Architecture and Feasibility in Java

> **Rolle:** meta-analysis zur Java-Architektur; reference material, keine
> operative spec. For the contract, see [`spec/`](../spec/README.md).

> **File:** `docs/ANALYSIS-ARCHITECTURE-JAVA.md`
> **Status:** 2026-10-22 · Repo state `dev` @ `3108505b2f` (v2.1.2)
> **Source:** Entire `docs/` documentation (69 Markdown files, ~21,500 lines)
> **Addendum:** 2026-10-22 (later) – see §6. The **package structure is authoritative**;
> `Java-Impl.md` §4 was aligned to it.
> **Purpose:** Answers the two questions (1) how understandable the architecture is
> documented and (2) how feasible implementing the application in Java is.

---

## 1. What `docs/` is

Pure **analysis documentation**, no product code. It is now part of the `qoc`
repository under `docs/`; the previous `myview` Git history was not carried
over. Four analysed workflows
(Use-Cases → Data-Flow → Bounded Contexts → Change-Impact) plus a
deep trace of a single prompt (`flow-db-service/`, 7 files) plus a
**language-neutral specification** (`spec/`, 49 files, 315 numbered rules)
and two build-plan documents (`spec/module-plan.md`, `spec/java-packages.md`).

---

## 2. Understandability of the architecture

### Verdict: **Very good for "where do I change what", medium for "understand the system from scratch"**

#### Strengths (evidenced)

- **Sources are real, not decorative.** Eight quotes spot-checked against
  the code — all exact: `specs/v2/session.md:118`,
  `handlers/session.ts:229-237`, `:536-538`, `schema/AGENTS.md:18`,
  `core/src/shell/scan.ts` = 2,409 lines, `tui/.../session/index.tsx` =
  138,735 bytes, `core` = 758 files / 176,986 lines, `handlers/session.ts`
  = 43 `.handle(` calls.
- **Reproducible measurement.** `coupling-analysis.mjs` reproduces all 30
  top edges and the 37-line folder table byte-identically
  (510 `core→schema`, 499 `core→util`, 375 `app→ui` …).
- **Measurement vs. interpretation separated.** Tables contain only numbers;
  judgements live in columns `Bewertung`/`Schwere`/`Warum zuerst` and
  `(Vorschlag)` headings.
- **End-to-end trace** (`flow-db-service/`) with transaction boundaries, 8
  key findings, error paths.
- **Positive findings** (§5.3 of the BC analysis): client isolation
  "contractually secured" via test, SDK composition deliberate, MCP forced
  lazy — prevents the document from reading like a complaint.

#### Open structural weaknesses

1. **`packages.md` ASCII diagram asserts a forbidden edge**
   (`server ← client`), contradicted by AGENTS.md, the bullets two lines
   below and `client/package.json` (deps: only `schema`, `protocol`). It is
   the first diagram a newcomer sees — and it is wrong.
2. **Arrow direction flips** between §1.1 (importer → imported) and §2.7
   Mermaid (base → dependent) without a legend.
3. **BC-10 "Owns: all tables", BC-4 and BC-12 both claim**
   `credential`/`account`** — table ownership (in DDD the
   exclusivity mechanism) is contradictory.
4. **The 13 BCs are not a partition.** ~10 `core/src` folders have no
   owner (`config`, `codemode`, `skill`, `formatter`, `effect`, `util`,
   `modal`, `ripgrep`, `image`, `wellknown`, root files) — maybe 70–75 %
   coverage, never stated anywhere.
5. **Two overlapping migration plans** (§5 Strangler 12 steps vs. §6
   recommendations 10 steps) with different decomposition names.
6. **Severity rubric missing** (`kritisch/hoch/mittel/niedrig` 16× without
   criteria); `simulation → core` missing from the rule table;
   `core/src/session/` = 42 (document) vs. 49 (reality) in `packages.md`.
7. **SCC/Tarjan not reproducible** with the supplied tool (no cycle
   detection in it); default path `<oc-checkout>`
   is outdated.
8. **No entry point** for newcomers beyond the README table;
   11,700 lines / 37 files.

#### Gap status: closed vs. open

**Closed since the first review (2026-10-22, 10:47–11:10):**
- `spec/provider/` (7 files) — previously not present at all, now
  complete (README, types, event stream, classification, catalog response,
  invariants, Java).
- `spec/permissions/` completed to 6 files (03-effect-matrix,
  04-invariants, 05-java were new).
- `spec/persistence/` new: database structure + JDBC/Flyway (2 files).
- `spec/module-plan.md` new: 35 modules topologically, 16 ports,
  simplification right, 15 milestones.
- `spec/java-packages.md` new: **authoritative** Java package structure under
  `de.augmentia.oc.engine`, visibilities, ArchUnit layer rules.
- `spec/session-engine/Java-Impl.md` §4 switched to the package structure; the
  Maven multi-module sketch there is **outdated**.
- `spec/README.md` and top-level `README.md` updated — all areas
  (session-engine 13, tools 7, permissions 6, provider 7, persistence 2,
  plus 2 build-plan documents) counted consistently.
- **0 broken links** (previously 7).

**Still open:** the structural points 1–8 above; none of the
architecture documents (`packages.md`, `03-bounded-contexts.md`,
`04-change-impact.md`) has been touched since 2026-10-21.

---

## 3. Feasibility in Java

### Verdict: **Engine ~90 % directly implementable; the overall product is a different scale**

#### Engine (Session-Engine + Provider + Tools + Permissions + Persistence)

- `11-java-portability.md`: "**Yes – roughly 90 % directly implementable**, with
  six small but blocking spec clarifications and two design decisions that
  must be left to the implementation."
- `Java-Impl.md` (now ~830 lines) is an **operative template**, not a sketch:
  **package structure** `de.augmentia.oc.engine` in nine stages (authoritative:
  `spec/java-packages.md`; the earlier Maven multi-module sketch is outdated),
  Java code for opaque IDs, `sealed` event hierarchy, three
  error channels, `Option<T>`, `EventStore.publish` in one transaction with
  both projections, `PermissionEngine` with `findLast` + wildcard,
  `ToolExecutor` with fixed order, `SessionExecution` with
  write-ahead claim + resume, provider interface as
  `Flow.Publisher<ModelEvent>`, SSE feed, test strategy (event channel as
  test surface → 339 rules testable), 6-phase build plan,
  acceptance checklist, 5 most common errors.
- **Java 21 is even stronger than TS** for the spec core: `sealed interface`
  + pattern-matching switch (JEP 441) enforces completeness at
  compile time — that resolves the I-07-02 convention error ("both
  projection layers"), which TS cannot enforce. Virtual threads
  (JEP 444) make "one execution per session" free. JDBC/JTA makes the
  transaction boundary a library guarantee.
- **Pain points (solvable, but design decisions):** no cancellation
  masking (→ `ReentrantLock.lock()` + terminal event before
  interrupt check), three error channels vs. `catch(Exception)` habit
  (→ `catch (ToolFailure)` only), transaction × thread boundary (tool runs
  in its own task → three transactions: Event A → tool in task T →
  result B), `StructuredTaskScope` is preview (JEP 453) → do not use as
  a foundation, `java.util.Optional` ≠ `Option<T>` → own sealed
  interface.

#### Overall product (TUI, Web, Desktop, CLI, MCP, Plugin, SDK, Console)

- The specs explicitly exclude transport and surfaces ("Non-goals:
  HTTP transport, surfaces, administration, billing"). A full port of the
  product is therefore much more than the engine.
- **But: the HTTP seam is stable.** 146 operations / 31 groups,
  generated client, OpenAPI document (582 KB). Pragmatic path:
  keep the existing SolidJS TUI/Web app, replace only the backend — no
  UI rewrite needed.

#### Blockers before implementation start (from `11-java-portability.md` §7/§9)

- **6 spec corrections (S-1…S-6):** error-channel wording,
  cancellation semantics, `parallel` semantics, `LogEntry.version`,
  `catch(Exception)` prohibition.
- **5 functional gaps (L-A…L-E):** location semantics,
  tool parallelism quota, sequencer for ordered publication,
  inbox retention, live-feed completion.
- **5 open decisions:** retry count (10),
  `external_directory` default (ask, stricter than current code),
  location semantics, error channel (exception vs. value → exception chosen),
  parallelism quota (proposal: 8).

---

## 4. Conclusion

| Question | Answer |
|---|---|
| Is the architecture documented understandably? | **Yes, with caveats.** Excellent for change-impact (source references verified exactly, reproducible coupling measurement). Overwhelming for newcomers (11,700 lines, no entry point, contradictory diagram, non-partitioning BCs). |
| Is the application implementable in Java? | **Engine: yes, ~90 %, template exists.** Product (UI/transport): not covered by the specs, but the HTTP seam (146 ops) allows backend replacement without UI rewrite. |
| How much effort? | **My estimate (not from the docs):** ~3.5–4.5 months for an experienced Java dev until green invariant test suite (engine core, 6 phases), plus provider adapters + HTTP/SSE layer. Full product with UI rewrite: significantly more, but not necessary. |

---

## 5. Recommended next steps

The specification itself is now complete (all links valid, all specs
present). The remaining open points lie in the
**architecture documents** (`packages.md`, `03-bounded-contexts.md`) and in the
**coupling tool**:

1. Fix `packages.md` diagram (forbidden edge), arrow legend,
   outdated numbers (42→49, 60→80, 50→84).
2. Resolve BC table ownership (BC-4/BC-10/BC-12), document BC coverage
   (~10 ownerless folders), publish severity rubric, merge the
   two migration plans.
3. Fix `coupling-analysis.mjs` default path and add Tarjan SCC detection
   (so §2.3/§2.4 become reproducible).
4. Work the 6 S- and 5 L-corrections into the specs, then take the 5 open
   product decisions.

---

## 6. Addendum – Decision of 2026-10-22 (later)

### 6.1 The package structure is authoritative

| Question | Decision |
|---|---|
| Build form | **Two modules**: `engine` (pure Java) + `quarkus` (outer skin), root `de.augmentia.oc.engine` |
| Ordering | **Nine stages `S0…S8`** + `adapter.*` (outside the stages), all inside `engine` |
| Enforcement | **ArchUnit** in the build (inside `engine`), plus the Maven module boundary `engine` ↔ `quarkus` |
| Binding file | [`spec/java-packages.md`](../spec/java-packages.md) |
| Outdated | The Maven multi-module sketch `engine-core`/`-runtime`/… in `Java-Impl.md` §4 |

`Java-Impl.md` §4 was reworked: one table maps each stage to its
packages and mentions the earlier module name only as origin. The
build plan in §13 now starts with `core`, `runtime`, `http`,
`adapter.sql` instead of "module `engine-core`".

**Rationale for the two-module split (decided 2026-10-22):** `engine` stays
pure Java (no Quarkus/CDI/HTTP) so it builds and tests without the Quarkus
runtime; `quarkus` is the outer skin and depends on `engine`. The stage
ordering remains an architecture rule enforced by ArchUnit inside `engine`;
Maven only separates pure Java from the Quarkus runtime. A finer split
remains possible **without moving a package boundary**.

### 6.2 What changes relative to §3 of this analysis

| Statement in §3 | Today |
|---|---|
| "Module structure `engine-core`/…" | **outdated**; one artifact, nine stages |
| "Test strategy: event channel as test surface" | extended: **16 ports** + contract tests against fakes **and** SQL, 15 milestones `M0–M14` |
| "~90 % directly implementable" | unchanged; the build form is now concretised, not re-evaluated |
| "339 invariants / 35 files" | **315 rules** (111 I + 56 W + 55 P + 47 V + 46 U/HS/SK/CR) / **49 files** |

### 6.3 New finding from packaging

Stage `S6` (session) may use only `S0`, `S1` and `S2`
per the ArchUnit rule. Thus the session is fully testable with exactly
**three fakes**: `Journal`, `SessionReader`, `SessionWake`. That is the
strongest testability gain of the new structure — and the reason the
trigger runs via the `SessionWake` port rather than a direct call to
`step`.

### 6.4 Still open (prioritised)

| # | Point | Why first |
|---|---|---|
| 1 | `packages.md`: wrong edge `server ← client`, missing arrow legend, numbers (42→49, 60→80, 50→84) | first diagram a newcomer sees — and it is wrong |
| 2 | BC ownership (BC-4/BC-10/BC-12) and coverage (~10 ownerless folders) | table ownership is the exclusivity mechanism in DDD |
| 3 | `coupling-analysis.mjs`: default path, Tarjan SCC | without it §2.3/§2.4 are not reproducible |
| 4 | `S-1…S-6` and `L-A…L-E` worked into the **base specs** | in `Java-Impl.md` §2/§3 they are worked in, not marked as changes in the specs themselves |

### 6.5 Baseline environment as fourth external contract

New is `spec/betrieb/` (4 files, 46 rules). It closes the three gaps
that §3 of this analysis marked as "not buildable" — but only for the
**baseline environment**:

| Contract | File | Rules | Closes |
|---|---|---|---|
| HTTP + SSE | [`spec/betrieb/01-http-streaming.md`](../spec/betrieb/01-http-streaming.md) | `HS-01…HS-12` | L-3 |
| Skills | [`spec/betrieb/02-skills.md`](../spec/betrieb/02-skills.md) | `SK-01…SK-12` | L-4, PRM-D |
| Credentials | [`spec/betrieb/03-credentials.md`](../spec/betrieb/03-credentials.md) | `CR-01…CR-12` | L-5 |
| Environment | [`spec/betrieb/README.md`](../spec/betrieb/README.md) | `U-01…U-10` | – |

Thus the verdict shifts: **core and baseline environment are buildable**,
the full product surface is not. Remaining open: the agent catalog (L-1),
the configuration schema (L-2), commands (L-6) and catalog updates (L-7).
The new rules change none of the 111 session invariants; they only describe
how a client reaches them from outside.