# Quality – checking the quality points

> **Question:** Does our specification cover tests, architecture boundaries,
> data ownership, sealed classes, virtual threads, transactions and the error
> taxonomy?
>
> **Answer:** Three were complete, three partial, two missing. "Tests" exist as
> a **strategy**, but not as a **specification**.
>
> **Status:** 2026-10-22 · **Basis:** `spec/` (49 files)

## 1. Short answer

| Point | Status | What was missing |
|---|---|---|
| Tests | **partial** | test strategy yes, but no test cases, no fixtures, no CI stages, no evidence per rule |
| Architecture & boundaries (ArchUnit) | **partial** | rules yes, but no build wiring, no CI gate, no exception process, `catch (Exception)` unchecked |
| Data ownership (DDD) | **missing** | no assignment table → bounded context, no "one writer" rule |
| Sealed classes | **complete** | – |
| Virtual threads | **partial** | used in the Java example, not described in the neutral model |
| Transactions | **complete** | – |
| Error taxonomy | **complete** | – |
| Internal prompts | **missing** | no catalog, no texts, no composition rules |

## 2. Check matrix

### [x] Architecture & boundaries (ArchUnit)

**Present:** nine stages, enforced dependency direction, cycle freedom,
`java.sql` only in the adapter – as runnable `@ArchTest` rules in
[`../java-packages.md`](../java-packages.md) §7.1/§7.2, including the honest
list of what ArchUnit **cannot** check (§7.3).

**Missing:** the wiring. A rule that does not run in the build is a proposal.
Also missing: a gate in CI, an exception process with an expiry date and the
check "no `catch (Exception)`" – according to §7.3 the most important rule of
all.

→ [`02-architecture-pipeline.md`](./02-architecture-pipeline.md)

### [x] Data ownership (DDD)

**Present:** the aggregate principle – "one transaction, one aggregate"
([`../persistence/README.md`](../persistence/README.md) §1) – and the nine
write transactions (§10).

**Missing:** the question of **who** a table belongs to. Who may write
`session_v2` was derivable from the text but not fixed. Additionally,
`project`/`worktree` has no module in the module plan.

→ [`../persistence/02-data-ownership.md`](../persistence/02-data-ownership.md)

### [x] Sealed classes

**Present and sufficient:** the event hierarchy as a `sealed interface` with
`record` variants, every projection as a `switch`, so the compiler enforces
completeness ([`../session-engine/Java-Impl.md`](../session-engine/Java-Impl.md)
§5.2); the same technique for `SessionError` (§5.3); the rationale in
[`../session-engine/11-java-portability.md`](../session-engine/11-java-portability.md)
§5.

**No addition needed.** Message folding is not its own sum type – it folds over
`Message`, and `Message` is already a sum type
([`../session-engine/01-types.md`](../session-engine/01-types.md) §4). An
additional section would only repeat.

### [x] Virtual threads

**Present:** the code – `Executors.newVirtualThreadPerTaskExecutor()` and "one
execution per session"
([`../session-engine/Java-Impl.md`](../session-engine/Java-Impl.md) §9); the
mapping `task { … }` → virtual thread
([`../session-engine/11-java-portability.md`](../session-engine/11-java-portability.md)
§5).

**Missing:** the **model**. The neutral specification says *that* one execution
runs per session, but not *how* it runs: no thread lifecycle, no limits, no
pinning, no shutdown behavior. That is the difference between a code example
and a prescription.

→ [`../session-engine/12-execution-model.md`](../session-engine/12-execution-model.md)

### [x] Transactions

**Present and sufficient:** nine named transactions with tables and triggers
([`../persistence/README.md`](../persistence/README.md) §10), the rule "no
event without projection in the same block", `READ COMMITTED` with row lock
([`../persistence/01-jdbc-flyway.md`](../persistence/01-jdbc-flyway.md) §2),
acceptance criterion A-09 ("a defective event aborts the transaction
completely") and the Java path in
[`../session-engine/Java-Impl.md`](../session-engine/Java-Impl.md) §6.2.

**No addition needed.** Open remains only whether `publish` retries on
serialization failures – with `READ COMMITTED` and row lock that is moot.

### [x] Error taxonomy

**Present and sufficient:** the three channels
([`../session-engine/06-errors.md`](../session-engine/06-errors.md) §1), the
taxonomy with `type` keys (§2), the assignment **error per layer** (§3) and
per spec its own classification
([`../tools/`](../tools/README.md), [`../permissions/`](../permissions/README.md),
[`../provider/03-classification.md`](../provider/03-classification.md)).

**No addition needed.** The boundary "denial without reason must not become
visible model-side" is formulated more sharply than in most implementations.

### [x] Internal prompts

**Present:** the composition rule – at most two system blocks, a dedicated
agent prompt replaces the base
([`../session-engine/05-provider.md`](../session-engine/05-provider.md) P-02) –
and the placeholder `base_prompt(tool_names)`
([`../session-engine/04-algorithms.md`](../session-engine/04-algorithms.md) §G).

**Missing:** everything else. Which prompts exist, what is in them, in what
order they act, where they live. `base_prompt` was a name without text – the
core was not runnable with it.

→ [`../session-engine/13-internal-prompts.md`](../session-engine/13-internal-prompts.md)

### [ ] Tests

**Present:** three test kinds and their responsibilities
([`../module-plan.md`](../module-plan.md) §7.1), contract tests against fake
**and** SQL adapter (§7.2), the fake table (§7.3), "what is not tested"
(§7.4), twelve acceptance criteria A-01…A-12
([`../session-engine/07-invariants.md`](../session-engine/07-invariants.md)
§I-12) and checkpoints per milestone (module plan §8).

**Missing:** the **specification**. There are no test cases (Given/When/Then),
no test data, no mapping "this rule → this test", no CI stages with time
budgets and no tests for recovery, load and migration. Twelve acceptance
criteria do not cover 315 rules – they are a sample, not evidence.

→ [`01-tests.md`](./01-tests.md)

## 3. When a point is "complete"

A topic counts as specified only when all three stages are reached:

| Stage | Question | Example |
|---|---|---|
| **Named** | Is there a section that states the rule? | "one execution per session" |
| **Checkable** | Is there a criterion that is fulfilled or violated without interpretation? | "two calls of the same session never produce two claims" |
| **Wired** | Does the check run in build or CI and does it block? | `ArchitectureTest` breaks the build |

The three complete points reach all three stages. The four incomplete ones fail
at the third – or, for data ownership and prompts, already at the first.

## 4. The new documents

| File | Answers | Scope |
|---|---|---|
| [`01-tests.md`](./01-tests.md) | Which test proves which rule, with which data, at which stage? | 5 test kinds, evidence rule, CI gates |
| [`02-architecture-pipeline.md`](./02-architecture-pipeline.md) | Which rule breaks the build, and how is it bypassed? | 9 rules, Maven/CI, exceptions |
| [`03-implementability.md`](./03-implementability.md) | Is the spec enough to build – and what is missing? | 24 building blocks, 4 gaps, MVP cut |
| [`../betrieb/`](../betrieb/README.md) | What does the smallest runnable environment look like? | HTTP/SSE, 3 skills, credentials, 46 rules |
| [`../persistence/02-data-ownership.md`](../persistence/02-data-ownership.md) | Who owns which table, and who may write it? | 12 tables, 10 rules |
| [`../session-engine/12-execution-model.md`](../session-engine/12-execution-model.md) | How does an execution run, and where are its limits? | 8 invariants, shutdown, pinning |
| [`../session-engine/13-internal-prompts.md`](../session-engine/13-internal-prompts.md) | Which prompts act when, in what order, with what text? | catalog, 13 rules, critical texts |

## 5. What is deliberately not here

| Topic | Why not |
|---|---|
| Tool choice (JUnit, Testcontainers, ArchUnit version) | implementation detail, not specification – the documents give examples, not prescriptions |
| Team process (review obligation, four-eyes principle) | not enforceable by a spec |
| Performance numbers | belong to operations, not correctness – tests check thresholds, not absolute values |
| Security audit | own topic; the error and rights taxonomy covers only the runtime side |<|end|><|start|>write to=assistant<|channel|>analysis<|message|>Wrote file successfully: spec/qualitaet/README.md