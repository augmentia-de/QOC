# refactor-v2.md – General Prompts for Refactoring (V2)

> **Purpose:** The working list for a refactoring that earns the name –
> not just rebuilding, but **verifiable, reversible, measurable** rebuilding.
> V2 contains all 20 prompts from `refactor.md` (V1) and 23 supplementary
> prompts from refactoring practice.
>
> **Status:** 2026-10-22 · **Predecessor:** `refactor.md` (20 prompts, 30 sources)
> · **Result:** 43 prompts in 8 phases, 10 guidelines, 7 gates
>
> **Origin is marked separately:** `R-n` = derived from a real assignment
> (V1), `Praxis` = supplemented from the craft, `L-n` = closes a gap named
> in V1.

## Contents

- [1. What V2 Changes Compared to V1](#1-what-v2-changes-compared-to-v1)
- [2. Guidelines](#2-guidelines)
- [3. Phases, Prompts and Gates](#3-phases-prompts-and-gates)
- [4. Phase Gates](#4-phase-gates)
- [5. Evidence Chain](#5-evidence-chain)
- [6. Anti-Patterns and Which Prompt Prevents Them](#6-anti-patterns-and-which-prompt-prevents-them)
- [7. Logical Check](#7-logical-check)
- [8. Check Run (Reproducible)](#8-check-run-reproducible)

## 1. What V2 Changes Compared to V1

| Area | V1 | V2 |
|---|---|---|
| Scope | 20 prompts | **43 prompts** in 8 phases |
| Origin | only from 30 assignments | 20 substantiated + **23 from practice** |
| Gaps | 6 named, deliberately open | **all 6 closed** (L-1…L-6) |
| Order | dependency graph | Graph **plus phase gates** |
| Success | "Done, when" per prompt | additionally **baseline → remeasurement** |
| Risk | not modeled | risk register, way back, abort threshold |
| Behavior | not secured | **Freeze behavior** before the first rebuild |
| Evidence | evidence per statement | **Evidence chain** goal → rule → test → code |

Three additions are not prompts but rules that apply to **all**: the
guidelines (§2), the gates (§4) and the evidence chain (§5).

## 2. Guidelines

1. **Behavior before structure.** A rebuild changes the form, not the behavior.
   Every unintended behavior change is an error, not a side effect.
2. **One hat per change.** Either rebuild or change behavior – never both
   in the same step. Two hats, two commits, two checks.
3. **Small steps, each green.** A step is small enough when it can be delivered
   alone and rolled back alone.
4. **Measure instead of estimate.** Without a baseline there is no success,
   only an opinion.
5. **Walkable backwards.** Every change has a way back that is fixed before
   the step – not searched for afterwards.
6. **One source of truth.** Exactly one document is leading; everything else
   references it or is marked as superseded.
7. **Seams before the cut.** Define the port first, then rebuild behind it.
   Whoever cuts first cuts through dependencies.
8. **Tests are the contract.** A behavior without a test is not present
   during a rebuild – it only looks like it.
9. **Decisions with rationale.** A decision without alternative and consequence
   is an opinion with a date.
10. **No Big Bang.** A cut that cannot be delivered in steps is not a plan
    but a date.

## 3. Phases, Prompts and Gates

### Phase 0 – Frame and Baseline (6)

**V-01 · Secure Source and State**
> Name `<Source>` with commit, version and date and substantiate every later statement on it.

*Origin:* R-1 · *Closes:* – · *Done, when:* the state is substantiated and no statement remains without evidence.

**V-02 · Log the Assignment**
> Record original text, decision, result and result file for every assignment – one entry per assignment.

*Origin:* R-2 · *Closes:* – · *Done, when:* every assignment has an entry and no entry remains without a result.

**V-03 · Goal, Non-Goal, Success Criterion**
> Formulate for `<Undertaking>` the goal in one sentence, what expressly does **not** belong to it, and how success is measurable: metric, threshold, point in time.

*Origin:* Praxis · *Closes:* – · *Done, when:* three sentences stand and the success criterion names a number and a threshold.

**V-04 · Measure Baseline**
> Measure before the first change `<Metrics>` – build time, test coverage, coupling, error rate, latency – and record command, date and value per metric.

*Origin:* Praxis · *Closes:* – · *Done, when:* every metric is present with command and value; without a baseline nothing is called an improvement.

**V-05 · Establish Risk Register**
> List for `<Undertaking>` the risks with probability of occurrence, effect and countermeasure and mark the two that would force an abort.

*Origin:* Praxis · *Closes:* – · *Done, when:* every risk has a measure and the abort criteria are named.

**V-06 · Set Working Rules**
> Set: one hat per change, steps so small that each is green on its own, and nobody works on a red build.

*Origin:* Praxis · *Closes:* – · *Done, when:* the rules are recorded and the first planned step demonstrably fits into them.

### Phase 1 – Understanding (7)

**V-07 · Read the Stock**
> Read `<Document stock>` in full. Then state structure, core statements, gaps and contradictions – without changing anything.

*Origin:* R-3 · *Closes:* – · *Done, when:* every file is named and gaps and contradictions are listed.

**V-08 · Draw the Overall Picture**
> Explain `<System>` along fixed dimensions: purpose, building blocks, data flow, rights, operation, open points. One page, every statement with evidence.

*Origin:* R-4 · *Closes:* – · *Done, when:* all dimensions are present and every statement carries a path or measurement value.

**V-09 · Explain the Role**
> Explain `<Component>`: what it owns, what it calls, what it does not know – and how that shows in the code.

*Origin:* R-5 · *Closes:* – · *Done, when:* ownership, call and non-knowledge are each substantiated.

**V-10 · Show the Flow**
> Trace `<Request>` through all layers to its effect and back. Name input, output, error and transaction boundary at every boundary.

*Origin:* R-6 · *Closes:* – · *Done, when:* the chain is gapless and every boundary names its error case.

**V-11 · Stagger the Depth**
> Document `<Flow>` in `<N>` levels: coarse, calls, data, errors. One file per level, each file readable on its own.

*Origin:* R-7 · *Closes:* – · *Done, when:* `<N>` files exist, level *k* references *k+1* and no file presupposes the next.

**V-12 · Freeze Behavior**
> Write for `<Behavior>` tests that capture today's actual state – even where it looks wrong. Mark every spot you hold to be an error instead of correcting it.

*Origin:* Praxis · *Closes:* L-1 · *Done, when:* every public interface has at least one characterizing test and all run green against the **unchanged** state.

**V-13 · Clarify Terms**
> Establish a glossary: term, meaning in the system, differing meaning in the code, source. One term, one definition.

*Origin:* Praxis · *Closes:* L-4 · *Done, when:* every term in documentation and code is assigned to exactly one definition and deviations are marked.

### Phase 2 – Recording (6)

**V-14 · Inventory Unit by Unit**
> Describe each unit from `<Set>` individually: purpose, content, exports, dependencies. Measure every number instead of estimating it.

*Origin:* R-8 · *Closes:* – · *Done, when:* the unit-to-line table is complete and every number names a measurement command.

**V-15 · Assign the Place**
> Assign exactly one place (file, package, layer) to each task and mark every exception as an overreach.

*Origin:* R-9 · *Closes:* – · *Done, when:* every task has exactly one place and every exception is named.

**V-16 · Decompose**
> Decompose `<Assignment>` into files, each with one guiding question, one result and cross-references. One document answers exactly one question.

*Origin:* R-10 · *Closes:* – · *Done, when:* every file names guiding question and result and an index links all files.

**V-17 · Describe Storage**
> Describe `<Storage>`: tables, keys, relationships, transactions, indices, dead objects. Name the writer and the reader per table.

*Origin:* R-20 · *Closes:* – · *Done, when:* every table has writer and reader, every transaction names its boundary and dead objects are disclosed.

**V-18 · Inventory Contract Surface**
> List every externally visible interface – HTTP, CLI, SDK, file format, event, database schema – with version, users and change rule.

*Origin:* Praxis · *Closes:* – · *Done, when:* every surface names its users and its rule: backwards compatible, extensible, or only with a new version.

**V-19 · Formulate Language-Neutrally**
> Formulate `<Logic>` without a programming language: data types, state machine, pseudocode, numbered invariants. Every rule individually testable.

*Origin:* R-11 · *Closes:* – · *Done, when:* every rule has an identifier and a test assertion and no definition contains code.

### Phase 3 – Checking (7)

**V-20 · Check Against Reality**
> Check every statement in `<Document>` against `<Reality>` (code, data, runtime). Separate finding and interpretation, every finding with path and line.

*Origin:* R-12 · *Closes:* – · *Done, when:* every statement carries a verdict (confirmed, deviating, unverifiable) and every finding is substantiated.

**V-21 · Limit Sources**
> Check exclusively with `<permitted sources>`. At the end state what you could not check as a result.

*Origin:* R-13 · *Closes:* – · *Done, when:* no foreign source is cited and the uncheckable is stated explicitly.

**V-22 · Resolve a Contradiction**
> Name contradictory statements with both locations, decide in favor of the substantiated one, correct the presentation at the source and mark the superseded one.

*Origin:* R-14 · *Closes:* – · *Done, when:* the contradiction is named, one version is corrected and the other is marked as superseded.

**V-23 · Evaluate a Technology**
> Evaluate whether `<Technology>` can carry `<Specification>`: per rule feasible, feasible with change, or not feasible. Name every change as a rule, not as an opinion.

*Origin:* R-15 · *Closes:* – · *Done, when:* a matrix rule × technology exists and every "not feasible" cell names its reason.

**V-24 · Measure Coupling and Cycles**
> Measure for `<System>` fan-in, fan-out, instability and every cycle as a strongly connected component. Name per cycle the edge whose resolution has the greatest leverage.

*Origin:* Praxis · *Closes:* – · *Done, when:* every number is reproducible by a command and every cycle has a cut proposal.

**V-25 · Check Security and Data Protection**
> Check `<System>` for secrets in code, unchecked inputs at trust boundaries, rights bypass and personal data in logs. Per finding: location, exploitability, measure.

*Origin:* Praxis · *Closes:* L-5 · *Done, when:* every trust boundary is named and every finding has a severity.

**V-26 · Check Non-Functional Requirements**
> Record for `<System>` load, latency, availability, cost and operational limits and mark which of them the target structure could violate.

*Origin:* Praxis · *Closes:* L-5 · *Done, when:* every requirement carries a number or an express "unknown".

### Phase 4 – Designing (6)

**V-27 · Design Target Structure**
> Order `<Building blocks>` topologically by dependencies. Define one port per seam and the one permitted direction. Forbid back edges.

*Origin:* R-16 · *Closes:* – · *Done, when:* the graph is cycle-free, every edge has port and direction and the levels are numbered.

**V-28 · Plan Migration Path**
> Decompose the way from `<As-Is>` to `<To-Be>` into steps that can be delivered individually. Per step: what changes visibly (nothing), what internally, and how to roll it back. Choose between Expand/Contract, Branch by Abstraction and Strangler and justify the choice.

*Origin:* Praxis · *Closes:* L-3 · *Done, when:* every step is individually deliverable and individually reversible and no step changes behavior.

**V-29 · Write Implementation Template**
> Write a template that can be implemented without follow-up questions: types, schema, algorithms, order, acceptance criteria.

*Origin:* R-17 · *Closes:* – · *Done, when:* every rule from V-19 is mapped to exactly one location and every step of the order has a checkpoint.

**V-30 · Record Decisions**
> Record per decision: question, options, chosen option, rationale, consequences, date and who decided. Reference the location that implements it.

*Origin:* Praxis · *Closes:* L-2 · *Done, when:* no decision stands without alternatives, consequences and date.

**V-31 · Estimate Effort and Order**
> Estimate effort per step as a size class, risk and predecessor. Sort by value per risk, not by order in the code.

*Origin:* Praxis · *Closes:* L-6 · *Done, when:* every step has size, risk and predecessor and the sort is justified.

**V-32 · Plan the Way Back**
> Define for `<Change>` the recognition feature for a failure, the rollback time, the data rollback and who triggers it.

*Origin:* Praxis · *Closes:* L-3 · *Done, when:* the way back is tested or expressly marked as untested and every threshold is a number.

### Phase 5 – Implementing (5)

**V-33 · Change in Small Steps**
> Change exactly one thing per step: first structure without behavior change, then behavior with test. After every step: build green, tests green, commit small and descriptive.

*Origin:* Praxis · *Closes:* – · *Done, when:* every step is runnable on its own and no step mixes rebuild and feature.

**V-34 · Derive and Run Tests**
> Derive from every rule `<Rule>` at least one test – example, boundary, error case – and run it against old and new state.

*Origin:* Praxis · *Closes:* L-1 · *Done, when:* the rule-to-test matrix is complete and every test yields an unambiguous result on both states.

**V-35 · Check Contracts**
> Check every contract surface from V-18 against its users: same response, same errors, same order. Break a contract only with a new version and transition period.

*Origin:* Praxis · *Closes:* – · *Done, when:* a contract test runs per user and every break names version and period.

**V-36 · Observe**
> Observe during the switch the same metrics as in V-04, additionally error rate and latency per seam. Set the threshold at which you roll back **before** the step.

*Origin:* Praxis · *Closes:* – · *Done, when:* the metrics are visible and the rollback threshold is documented before the step.

**V-37 · Migrate and Switch Data**
> Migrate `<Data>` in four steps: write double, backfill, compare, switch reads. Compare by sample and in full before switching off the old source.

*Origin:* Praxis · *Closes:* L-3 · *Done, when:* the deviation rate is zero or every deviation is explained and the old path is switched off only after the comparison.

### Phase 6 – Securing (5)

**V-38 · Keep Evidence**
> Keep a matrix: goal → rule → test → code location → evidence. Every row without evidence is open.

*Origin:* Praxis · *Closes:* – · *Done, when:* every rule has test and code location and the open rows are counted.

**V-39 · Remeasure Against Baseline**
> Measure the same metrics as in V-04 with the same commands and set actual against baseline. Name every deterioration with cause or as open.

*Origin:* Praxis · *Closes:* – · *Done, when:* a comparison table per metric exists and no deterioration remains unexplained.

**V-40 · Remove Legacy**
> Remove dead code, dead tables, dead flags and dead documents – only after V-38 delivers the evidence that nobody uses them.

*Origin:* Praxis · *Closes:* – · *Done, when:* every removal has a usage evidence and nothing stays "just in case".

**V-41 · Enforce Decision**
> Declare `<Variant>` as binding, pull all dependent documents along and mark the superseded version as such.

*Origin:* R-18 · *Closes:* – · *Done, when:* exactly one document is leading, all references point to it and the old one is marked as superseded.

**V-42 · Catch Up Documentation**
> Catch up all documents: actual state, target structure, decisions, operation. Mark superseded versions and link the valid one.

*Origin:* Praxis · *Closes:* – · *Done, when:* every statement has exactly one valid source and no two versions claim the same thing.

### Phase 7 – Frame (1)

**V-43 · Close and Continue**
> Stop immediately on request, without open write operations, and state the status. Then continue at the last substantiated location.

*Origin:* R-19 · *Closes:* – · *Done, when:* the abort leaves no half-finished file and the continuation names its connection point.

### Edges Between the Prompts

`X → Y` means: **X must be decided before Y begins.** The edges are stored
as a data block so that §8 can check them instead of duplicating them in
prose.

```
V-01 → V-07
V-01 → V-14
V-03 → V-04
V-04 → V-39
V-05 → V-32
V-06 → V-33
V-07 → V-08
V-07 → V-12
V-07 → V-16
V-08 → V-09
V-08 → V-10
V-08 → V-19
V-10 → V-11
V-12 → V-33
V-13 → V-19
V-14 → V-15
V-14 → V-17
V-14 → V-18
V-18 → V-35
V-19 → V-20
V-19 → V-23
V-19 → V-27
V-19 → V-29
V-19 → V-34
V-20 → V-22
V-20 → V-24
V-20 → V-25
V-20 → V-26
V-21 → V-20
V-23 → V-27
V-24 → V-27
V-25 → V-27
V-26 → V-27
V-27 → V-28
V-27 → V-29
V-28 → V-31
V-28 → V-32
V-28 → V-37
V-30 → V-41
V-30 → V-42
V-31 → V-33
V-32 → V-36
V-33 → V-34
V-33 → V-35
V-33 → V-36
V-34 → V-38
V-35 → V-38
V-36 → V-39
V-37 → V-39
V-38 → V-40
V-39 → V-40
V-40 → V-42
V-41 → V-42
V-42 → V-43
```

V-02 stands outside: logging happens at any time.

## 4. Phase Gates

A gate is not a ceremony but the question: **may the next step become
risky?** Every gate names the prompts that deliver it.

| Gate | After Phase | Entry only when | Delivered by |
|---|---|---|---|
| **G0** | 0 | Stand, goal, baseline, risks are in place | V-01…V-06 |
| **G1** | 1 | Behavior is frozen and runs green | V-12, V-07…V-11, V-13 |
| **G2** | 2 | every unit, every place, every contract surface is known | V-14…V-19 |
| **G3** | 3 | every rule has a verdict, every cycle a cut | V-20…V-26 |
| **G4** | 4 | every step is deliverable **and** reversible | V-27…V-32 |
| **G5** | 5 | every step ran green, metrics stayed in band | V-33…V-37 |
| **G6** | 6 | Evidence is complete, baseline beaten or explained | V-38…V-42 |

**Rule:** No gate is skipped because "time is pressing". A skipped gate is
the cause, not the exception. Phase 7 has no gate: V-43 is the closing
itself, not a transition.

## 5. Evidence Chain

Every statement must be able to follow this chain; a link without a
predecessor is a gap:

```
Goal (V-03)
  → Baseline (V-04)
    → Rule (V-19)
      → Test (V-34)
        → Code location (V-29)
          → Evidence (V-38)
            → Measurement (V-39)
              → Operation (V-36)
```

Across it: **Decision (V-30)** justifies every deviation, **Way back
(V-32)** limits every step, **Contract (V-18/V-35)** protects the users.

## 6. Anti-Patterns and Which Prompt Prevents Them

| Anti-Pattern | Why it fails | Prevented by |
|---|---|---|
| **Big Bang** – everything at once | no way back, no intermediate state | V-28, V-32 |
| **Rebuild without tests** | behavior changes unnoticed | V-12, V-34 |
| **Rebuild and feature in the same commit** | nobody knows what caused the error | V-06, V-33 |
| **Structure before understanding** | cut through a dependency not yet known | V-07…V-11, Gate G1 |
| **Estimate without baseline** | "faster" is unsubstantiated | V-04, V-39 |
| **Docs at the end** | decisions are then no longer reconstructable | V-30, V-42 |
| **"Keep just in case"** | legacy survives every migration | V-40 |
| **Port without contract** | seam does not keep what it promises | V-18, V-35 |
| **Abort without way back** | half-finished state becomes permanent | V-32, V-43 |

## 7. Logical Check

### 7.1 V1 Coverage

All 20 prompts from `refactor.md` are contained, none was silently changed;
the mapping is verifiable in the `Origin` line:

| V1 | V2 |
|---|---|
| R-1 … R-20 | V-01, 02, 07, 08, 09, 10, 11, 14, 15, 16, 17, 19, 20, 21, 22, 23, 27, 29, 41, 43 |

### 7.2 Closed Gaps

| Gap from V1 | Closed by |
|---|---|
| L-1 Tests as an executable artifact | V-12 (freeze), V-34 (derive and run) |
| L-2 Decision register (ADR) | V-30 |
| L-3 Migration and teardown | V-28 (path), V-32 (way back), V-37 (data) |
| L-4 Glossary / ubiquitous language | V-13 |
| L-5 Non-functional requirements | V-25 (security), V-26 (operation) |
| L-6 Effort and order | V-31 |

### 7.3 What V2 Also Does Not Cover

V2 is more complete, but not complete. Deliberately left open:

| # | Gap | Why it stays open |
|---|---|---|
| M-1 | **Team and organization questions** (ownership, review culture, approvals) | not solvable by prompts but by agreement |
| M-2 | **Functional acceptance** (user tests the target state) | needs a human, not an assignment |
| M-3 | **Legal review** (licenses, data-protection impact assessment) | V-25 finds the spot but does not decide |
| M-4 | **Team budget and deadline** | V-31 estimates effort, not availability |

These four are the honest limit: a prompt can describe work, but cannot
take responsibility.

### 7.4 Checkable Properties

| Property | Check |
|---|---|
| Completeness of numbers | V-01…V-43 gapless, no duplication |
| Origin | every prompt names `R-n` or `Praxis` |
| V1 coverage | all 20 R-IDs occur in an origin |
| Gaps | all 6 L-IDs occur in a `Closes` statement |
| Done criterion | every prompt has one |
| Order | graph cycle-free, levels computed |
| Phases | sum of prompts per phase = 43 |

**Longest chain (15 prompts):** `V-01 → V-07 → V-08 → V-19 → V-20 → V-24 →
V-27 → V-28 → V-31 → V-33 → V-34 → V-38 → V-40 → V-42 → V-43`. It
determines the minimum duration with arbitrary parallelism – cuts must
start here, not on the short branches.

## 8. Check Run (Reproducible)

```bash
cd docs && python3 - <<'PY'
import io, re

s = io.open("refactor-v2.md", encoding="utf-8").read()
lines = s.splitlines()

# Definitions: "**V-nn · Title**"
defined = re.findall(r"^\*\*(V-\d{2}) · ", s, re.M)
nummern = [int(v[2:]) for v in defined]

# Origin and gaps per definition
teile = re.split(r"^\*\*(V-\d{2}) · ", s, flags=re.M)
herkunft, schliesst, fertig = {}, {}, {}
for k in range(1, len(teile), 2):
    vid, body = teile[k], teile[k + 1]
    h = re.search(r"\*Origin:\* ([^·]+) ·", body)
    z = re.search(r"\*Closes:\* ([^·]+) ·", body)
    f = re.search(r"\*Done, when:\*", body)
    herkunft[vid] = h.group(1).strip() if h else ""
    schliesst[vid] = z.group(1).strip() if z else ""
    fertig[vid] = bool(f)

# Edges
kanten = re.findall(r"^(V-\d{2}) → (V-\d{2})$", s, re.M)

# Phases: "### Phase n – Title (k)"
phasen = re.findall(r"^### Phase (\d+) [^\n]*\((\d+)\)$", s, re.M)
bloecke = re.split(r"^### Phase \d+ [^\n]*\(\d+\)$", s, flags=re.M)[1:]
ist = [len(re.findall(r"^\*\*V-\d{2} · ", b, re.M)) for b in bloecke]

fehler = []

# 1. Number range
if nummern != list(range(1, len(nummern) + 1)):
    fehler.append(f"Numbers not gapless: {nummern}")

# 2. Origin and done criterion
for v in defined:
    if herkunft[v] not in ("Praxis",) and not re.fullmatch(r"R-\d+", herkunft[v]):
        fehler.append(f"{v}: Origin missing or invalid ({herkunft[v]!r})")
    if not fertig[v]:
        fehler.append(f"{v}: Done criterion missing")

# 3. V1 coverage
r_ids = {h for h in herkunft.values() if h.startswith("R-")}
erwartet_r = {f"R-{i}" for i in range(1, 21)}
if r_ids != erwartet_r:
    fehler.append(f"V1 coverage: missing {sorted(erwartet_r - r_ids)}, extra {sorted(r_ids - erwartet_r)}")

# 4. Gaps
l_ids = {l for z in schliesst.values() for l in re.findall(r"L-\d+", z)}
erwartet_l = {f"L-{i}" for i in range(1, 7)}
if l_ids != erwartet_l:
    fehler.append(f"Gaps: missing {sorted(erwartet_l - l_ids)}, extra {sorted(l_ids - erwartet_l)}")

# 5. Edges and cycles
for a, b in kanten:
    for x in (a, b):
        if x not in defined:
            fehler.append(f"Edge names unknown {x}")
graph = {v: [] for v in defined}
grad = {v: 0 for v in defined}
for a, b in kanten:
    graph[a].append(b)
    grad[b] += 1
ebene = {v: 0 for v in defined if grad[v] == 0}
offen = list(ebene)
while offen:
    v = offen.pop(0)
    for n in graph[v]:
        grad[n] -= 1
        ebene[n] = max(ebene.get(n, 0), ebene[v] + 1)
        if grad[n] == 0:
            offen.append(n)
if len(ebene) != len(defined):
    fehler.append("Edge creates a cycle")

# 6. Phases
if ist != [int(k) for _, k in phasen]:
    fehler.append(f"Phase sums: announced {[k for _, k in phasen]}, counted {ist}")
if sum(ist) != len(defined):
    fehler.append(f"Phase sum {sum(ist)} != {len(defined)} prompts")

print(f"Definitions:   {len(defined)} (V-01…V-{nummern[-1]:02d}, gapless)")
print(f"Provenance:    {len(r_ids)} from V1, {sum(1 for h in herkunft.values() if h == 'Praxis')} from practice")
print(f"V1 coverage:   {len(r_ids)}/20")
print(f"Gaps:          {len(l_ids)}/6 closed")
print(f"Edges:         {len(kanten)}, Cycles: {'yes' if any('cycle' in f.lower() for f in fehler) else 'none'}, Levels: {max(ebene.values()) + 1}")
print(f"Phases:        {len(phasen)}, Sum {sum(ist)} = {len(defined)}")
print(f"Findings:      {fehler or 'none'}")
PY
```

**Result of the run:**

```
Definitions:   43 (V-01…V-43, gapless)
Provenance:    20 from V1, 23 from practice
V1 coverage:   20/20
Gaps:          6/6 closed
Edges:         54, Cycles: none, Levels: 15
Phases:        8, Sum 43 = 43
Findings:      none
```