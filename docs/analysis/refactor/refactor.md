# refactor.md – General Prompts for Analysis and Rebuild

> **Purpose:** Derive the **reusable** prompts from 30 concrete assignments
> (`prompts1.md`: 7, `prompts.md`: 23) – without system names, without
> file paths, without technology. The list is transferable to another
> repository, another language and another domain.
>
> **Status:** 2026-10-22 · **Sources:** `prompts1.md` (Q1–Q7),
> `prompts.md` (Q8–Q30) · **Result:** 20 prompts in 6 groups,
> logically checked in §4.

## Contents

- [1. Derivation Rule](#1-derivation-rule)
- [2. The 20 Prompts](#2-the-20-prompts)
- [3. Derivation Matrix (30 Sources → 20 Prompts)](#3-derivation-matrix-30-sources-20-prompts)
- [4. Logical Check of the List](#4-logical-check-of-the-list)
- [5. Check Run (Reproducible)](#5-check-run-reproducible)

## 1. Derivation Rule

Every general prompt arises in three steps:

1. **Extract the operation** – what is to be done (read, order, check,
   design, decide)? Only the verb and its object remain.
2. **Replace proper names** – system, file, language, technology become
   placeholders `<…>`. What remains must no longer betray any domain.
3. **Define completion** – every prompt gets a testable "Done,
   when". Without this criterion the prompt is not general, only short.

Assignments that only steer the flow (clone, abort, continue) are **not**
derived – they are merged into one common prompt (R-19), because they
produce no result but only set the frame.

## 2. The 20 Prompts

### Group A – Foundation

**R-1 · Secure the Source**
> Name `<Source>` with commit, version and date and substantiate every later statement on it.

*From:* Q1 · *Done, when:* the state is substantiated and no statement remains without evidence.

**R-2 · Keep a Log**
> Record original text, decision, result and result file for every assignment – one entry per assignment.

*From:* Q30 · *Done, when:* every assignment has an entry and no entry remains without a result.

### Group B – Understanding

**R-3 · Read the Stock**
> Read `<Document stock>` in full. Then state structure, core statements, gaps and contradictions – without changing anything.

*From:* Q3, Q4, Q8, Q9 · *Done, when:* every file is named and gaps and contradictions are listed.

**R-4 · Draw the Overall Picture**
> Explain `<System>` along fixed dimensions: purpose, building blocks, data flow, rights, operation, open points. One page, every statement with evidence.

*From:* Q2 · *Done, when:* all dimensions are present and every statement carries a path or measurement value.

**R-5 · Explain the Role**
> Explain `<Component>`: what it owns, what it calls, what it does not know – and how that shows in the code.

*From:* Q19 · *Done, when:* ownership, call and non-knowledge are each substantiated.

**R-6 · Show the Flow**
> Trace `<Request>` through all layers to its effect and back. Name input, output, error and transaction boundary at every boundary.

*From:* Q21 · *Done, when:* the chain is gapless and every boundary names its error case.

**R-7 · Stagger the Depth**
> Document `<Flow>` in `<N>` levels: coarse, calls, data, errors. One file per level, each file readable on its own.

*From:* Q10 · *Done, when:* `<N>` files exist, level *k* references *k+1* and no file presupposes the next.

### Group C – Recording

**R-8 · Inventory Unit by Unit**
> Describe each unit from `<Set>` individually: purpose, content, exports, dependencies. Measure every number instead of estimating it.

*From:* Q5 · *Done, when:* the unit-to-line table is complete and every number names a measurement command.

**R-9 · Assign the Place**
> Assign exactly one place (file, package, layer) to each task and mark every exception as an overreach.

*From:* Q12 · *Done, when:* every task has exactly one place and every exception is named.

**R-10 · Decompose**
> Decompose `<Assignment>` into files, each with one guiding question, one result and cross-references. One document answers exactly one question.

*From:* Q6, Q22 · *Done, when:* every file names guiding question and result and an index links all files.

**R-11 · Formulate Language-Neutrally**
> Formulate `<Logic>` without a programming language: data types, state machine, pseudocode, numbered invariants. Every rule individually testable.

*From:* Q11, Q22 · *Done, when:* every rule has an identifier and a test assertion and no definition contains code.

**R-20 · Describe Storage**
> Describe `<Storage>`: tables, keys, relationships, transactions, indices, dead objects. Name the writer and the reader per table.

*From:* Q23 · *Done, when:* every table has writer and reader, every transaction names its boundary and dead objects are disclosed.

### Group D – Checking

**R-12 · Check Against Reality**
> Check every statement in `<Document>` against `<Reality>` (code, data, runtime). Separate finding and interpretation, every finding with path and line.

*From:* Q13, Q28 · *Done, when:* every statement carries a verdict (confirmed, deviating, unverifiable) and every finding is substantiated.

**R-13 · Limit Sources**
> Check exclusively with `<permitted sources>`. At the end state what you could not check as a result.

*From:* Q16 · *Done, when:* no foreign source is cited and the uncheckable is stated explicitly.

**R-14 · Resolve a Contradiction**
> Name contradictory statements with both locations, decide in favor of the substantiated one, correct the presentation at the source and mark the superseded one.

*From:* Q20, Q28 · *Done, when:* the contradiction is named, one version is corrected and the other is marked as superseded.

**R-15 · Evaluate a Technology**
> Evaluate whether `<Technology>` can carry `<Specification>`: per rule feasible, feasible with change, or not feasible. Name every change as a rule, not as an opinion.

*From:* Q14, Q17 · *Done, when:* a matrix rule × technology exists and every "not feasible" cell names its reason.

### Group E – Designing

**R-16 · Design a Structure**
> Order `<Building blocks>` topologically by dependencies. Define one port per seam and the one permitted direction. Forbid back edges.

*From:* Q26, Q27 · *Done, when:* the graph is cycle-free, every edge has port and direction and the levels are numbered.

**R-17 · Write an Implementation Template**
> Write a template that can be implemented without follow-up questions: types, schema, algorithms, order, acceptance criteria.

*From:* Q18 · *Done, when:* every rule from R-11 is mapped to exactly one location and every step of the order has a checkpoint.

**R-18 · Enforce a Decision**
> Declare `<Variant>` as binding, pull all dependent documents along and mark the superseded version as such.

*From:* Q29 · *Done, when:* exactly one document is leading, all references point to it and the old one is marked as superseded.

### Group F – Frame

**R-19 · Close and Continue**
> Stop immediately on request, without open write operations, and state the status. Then continue at the last substantiated location.

*From:* Q7, Q15, Q24, Q25 · *Done, when:* the abort leaves no half-finished file and the continuation names its connection point.

## 3. Derivation Matrix (30 Sources → 20 Prompts)

| Source | Short Form of Original | Target |
|---|---|---|
| Q1 | Clone repo | R-1 |
| Q2 | Analyze project, `overview.md` | R-4 |
| Q3 | Normative specs read | R-3 |
| Q4 | User documentation read | R-3 |
| Q5 | Package inventory + workflow framework | R-8 |
| Q6 | More files, "understand everything" | R-10 |
| Q7 | Abort ("uit") | R-19 |
| Q8 | Read `docs` stock | R-3 |
| Q9 | Read and understand everything | R-3 |
| Q10 | Flow in four depth levels | R-7 |
| Q11 | Note language-independently | R-11 |
| Q12 | "What lies where" | R-9 |
| Q13 | 111 rules against the code | R-12 |
| Q14 | Java 21 + LangChain4j + Quarkus check | R-15 |
| Q15 | Stop | R-19 |
| Q16 | Check only from the spec | R-13 |
| Q17 | Evaluate independent reimplementation | R-15 |
| Q18 | Write `Java-Impl.md` | R-17 |
| Q19 | Function of the session engine | R-5 |
| Q20 | Contradiction in diagram | R-14 |
| Q21 | Flow Session → LLM → Rights → Tool | R-6 |
| Q22 | Three more specs | R-10, R-11 |
| Q23 | Describe database structure | R-20 |
| Q24 | "weutr" | R-19 |
| Q25 | "weiter" | R-19 |
| Q26 | Sort modules by dependencies | R-16 |
| Q27 | Design Java package structure | R-16 |
| Q28 | Read self-analysis | R-12, R-14 |
| Q29 | Package structure is leading | R-18 |
| Q30 | Create prompt protocol | R-2 |

## 4. Logical Check of the List

### 4.1 Edges (Order)

The edges are stored as a data block so that §5 can check them instead of
duplicating them in prose. `X → Y` means: **X must be decided before Y
meaningfully begins.**

```
R-1 → R-3
R-1 → R-8
R-3 → R-4
R-3 → R-10
R-4 → R-5
R-4 → R-6
R-4 → R-11
R-6 → R-7
R-8 → R-9
R-8 → R-20
R-11 → R-12
R-11 → R-15
R-11 → R-16
R-12 → R-14
R-13 → R-12
R-16 → R-17
R-17 → R-18
```

R-2 and R-19 depend on nothing: logging and closing happen at any time.

### 4.2 Coverage

**Claim:** Every one of the 30 sources is assigned exactly once, and every one
of the 20 prompts is reached at least once. **Check:** §5, the result of the run there.

### 4.3 Delimitation (No Duplicates)

Five pairs look similar; they differ in exactly one trait:

| Pair | Commonality | Difference |
|---|---|---|
| R-4 / R-5 / R-6 | all explain | Whole / one component / one flow |
| R-9 / R-10 | both order | Place in stock / cut of documents |
| R-12 / R-15 | both check | Actual state against code / fitness of a technology for a spec |
| R-14 / R-18 | both decide | Decision in dispute / decision between variants |
| R-11 / R-17 | both write | Neutral rule / concrete implementation of a rule |

### 4.4 Contradictions and Their Resolution

| Apparent Contradiction | Resolution |
|---|---|
| R-12 "check against the code" vs. R-13 "only permitted sources" | R-13 is not a second instruction but a **boundary condition on R-12**: it sets the source set. With the set "spec only", R-12 checks the spec against itself. |
| R-11 "language-neutral" vs. R-17 "Java code" | Two layers, no competition: R-11 is the invariant version, R-17 a **projection** of it. R-17 may not introduce a rule that is missing in R-11. |
| R-10 "decompose" vs. "one artifact per assignment" | The reference point is the **question**, not the session: one document per question, several documents per assignment. |
| R-2 "log" vs. R-12 "check" | R-2 writes about the assignment, R-12 about the result. The log is not a source but an index. |

### 4.5 Derivation Fidelity (Nothing Invented)

Every prompt must be covered by its sources. Two clauses are **additions** –
they stem from the documentation conventions (`docs/README.md`), not from a
prompt, and are therefore marked as supplements:

| Prompt | Addition | Why permissible |
|---|---|---|
| R-12 | "Separate finding and interpretation" | Without this separation the result is not checkable; the convention stood in `docs/README.md` |
| R-13 | "Name what you could not check" | Otherwise the source limitation creates the false impression of completeness |

All other clauses are substantiated by the sources: "with path and line" from
Q2/Q13, "numbered invariants" from Q13 ("the 111"), "acceptance criteria"
from Q18, "dead objects" from Q23.

### 4.6 Gaps in the List

The list is **total over the sample, not over the topic**. What was never
demanded in the 30 prompts is also missing here – that is not an error of the
derivation but a property of the template:

| # | Gap | Severity | Consequence if it remains |
|---|---|---|---|
| L-1 | **Tests as an executable artifact** (only as text in R-17) | high | The template describes acceptance criteria, but nobody writes them |
| L-2 | **Decision register (ADR)**: rationale, alternatives, consequences | high | R-18 enforces decisions but does not record them – the next session decides anew |
| L-3 | **Migration and teardown** (strangler plan arose as content, never as an assignment) | medium | Cut without order and without a way back |
| L-4 | **Glossary / ubiquitous language** | medium | R-11 defines types, but no terms |
| L-5 | **Non-functional requirements** (load, security, cost) | medium | R-15 checks feasibility, not operation |
| L-6 | **Effort and order of implementation** | low | R-16 orders dependencies, not work |

L-1 and L-2 are the only ones that directly endanger the refactoring's
success; they should be added as **R-21 (derive tests)** and **R-22 (record
decisions)** as soon as a source prompt demands them – until then they stay
deliberately outside the list.

### 4.7 What the Check Changed

| # | Finding | Correction |
|---|---|---|
| F1 | Q23 ("describe DB structure") had no target in the first version | **R-20** added; the list grew from 19 to 20 |
| F2 | Q6 ("more files, understand everything") fit no prompt | **R-10** added; Q6 and Q22 share the target |
| F3 | R-13 was formulated as an own checking prompt and competed with R-12 | Reformulated as a **boundary condition** and entered as edge `R-13 → R-12` |

### 4.8 Limits of This Check

- Checked are **totality, acyclicity, delimitation and derivation fidelity** –
  not the content quality of the prompts.
- Orthogonality is **argued, not proven**: a formal proof that two prompts
  never produce the same result is impossible with natural language.
- The gaps in §4.6 are **relative to the sample**. A different assignment
  (e.g. operation instead of rebuild) would have different gaps.

## 5. Check Run (Reproducible)

The script reads the derivation matrix (§3), the definitions (§2) and the
edges (§4.1) from this file and checks eight properties: totality of sources,
validity of every target, no orphans, no duplicate definitions, valid edge
endpoints, done criterion per prompt, acyclicity and agreement between the
`From:` statement and the matrix.

```bash
cd docs && python3 - <<'PY'
import io, re

s = io.open("refactor.md", encoding="utf-8").read()

# 1. Definitions: "**R-n · Title**"
defined = re.findall(r"^\*\*(R-\d+) · ", s, re.M)

# 2. Matrix: | Qn | ... | R-n, R-m |
rows = re.findall(r"^\| (Q\d+) \| [^|]+ \| ([^|]+) \|$", s, re.M)
mapping = {q: re.findall(r"R-\d+", ziel) for q, ziel in rows}

# 3. Edges from the data block
kanten = re.findall(r"^(R-\d+) → (R-\d+)$", s, re.M)

# 4. Done criterion per definition
fertig = len(re.findall(r"^\*From:\* .*?· \*Done, when:\*", s, re.M))

# 5. Symmetry: "Aus:" in the definition vs. target in the matrix
teile = re.split(r"^\*\*(R-\d+) · ", s, flags=re.M)
aus = {}
for k in range(1, len(teile), 2):
    treffer = re.search(r"^\*From:\* ([^·]+) ·", teile[k + 1], re.M)
    aus[teile[k]] = re.findall(r"Q\d+", treffer.group(1)) if treffer else []
rueck = {}
for q, ziele in mapping.items():
    for z in ziele:
        rueck.setdefault(z, set()).add(q)
symmetrie = [r for r in defined if set(aus.get(r, [])) != rueck.get(r, set())]

fehler = []
quellen = {f"Q{i}" for i in range(1, 31)}
if set(mapping) != quellen:
    fehler.append(f"Sources not total: missing {sorted(quellen - set(mapping))}, extra {sorted(set(mapping) - quellen)}")
for q, ziele in mapping.items():
    if not ziele:
        fehler.append(f"{q} without target")
    for z in ziele:
        if z not in defined:
            fehler.append(f"{q} points to unknown {z}")
for r in defined:
    if r not in {z for zs in mapping.values() for z in zs}:
        fehler.append(f"{r} is an orphan")
if len(defined) != len(set(defined)):
    fehler.append("doppelte Definition")
for a, b in kanten:
    for x in (a, b):
        if x not in defined:
            fehler.append(f"Kante nennt unbekanntes {x}")
if fertig != len(defined):
    fehler.append(f"Done criterion missing for {len(defined) - fertig} prompts")
for r in symmetrie:
    fehler.append(f"{r}: From {sorted(aus.get(r, []))} ≠ Matrix {sorted(rueck.get(r, set()))}")

# Cycle check + levels (topological sort)
graph = {r: [] for r in defined}
for a, b in kanten:
    graph[a].append(b)
grad = {r: 0 for r in defined}
for a, b in kanten:
    grad[b] += 1
ebene, offen = {}, [r for r in defined if grad[r] == 0]
for r in offen:
    ebene[r] = 0
while offen:
    r = offen.pop(0)
    for n in graph[r]:
        grad[n] -= 1
        ebene[n] = max(ebene.get(n, 0), ebene[r] + 1)
        if grad[n] == 0:
            offen.append(n)
if len(ebene) != len(defined):
    fehler.append("Edge creates a cycle")

print(f"Sources:        {len(mapping)}/30 assigned")
print(f"Prompts:        {len(defined)} defined, {len(set(z for zs in mapping.values() for z in zs))} reached")
print(f"Edges:          {len(kanten)}, Cycles: {'yes' if fehler and 'cycle' in ' '.join(fehler).lower() else 'none'}")
print(f"Done criteria:  {fertig}/{len(defined)}")
print(f"Symmetry:       {len(defined) - len(symmetrie)}/{len(defined)} (Definition ↔ Matrix)")
print(f"Levels:         {max(ebene.values()) + 1}")
print(f"Findings:       {fehler or 'none'}")
PY
```

**Result of the run:**

```
Sources:        30/30 assigned
Prompts:        20 defined, 20 reached
Edges:          17, Cycles: none
Done criteria:  20/20
Symmetry:       20/20 (Definition ↔ Matrix)
Levels:         7
Findings:       none
```