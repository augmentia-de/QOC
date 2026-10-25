# 11 – Feasibility: is the spec implementable in Java 21?

> **Question:** Can the Session Engine spec (this directory) be reimplemented in
> **Java 21** – independently of OpenCode?
> **Status:** 2026-10-22.
> **Method:** Compare the neutral primitives in
> [`README.md`](./README.md) and [`04-algorithms.md`](./04-algorithms.md) with
> the language and library realities of Java 21 (JDK: virtual threads,
> pattern matching, `sealed`, JDBC/JTA, `Flow`) and the practical runtime
> environment (Quarkus 3.x, PostgreSQL, Flyway).

## Contents

- [1. Short verdict](#1-short-verdict)
- [2. Mapping: neutral primitives → Java 21](#2-mapping-neutral-primitives-java-21)
- [3. Six places where the spec assumes a functional language](#3-six-places-where-the-spec-assumes-a-functional-language)
- [4. Five domain gaps that apply regardless of language](#4-five-domain-gaps-that-apply-regardless-of-language)
- [5. What is easier in Java](#5-what-is-easier-in-java)
- [6. What is harder in Java](#6-what-is-harder-in-java)
- [7. Recommended changes to the spec before implementation start](#7-recommended-changes-to-the-spec-before-implementation-start)
- [8. Compact build plan (if Java 21 + Quarkus)](#8-compact-build-plan-if-java-21-quarkus)
- [9. What must be decided before starting](#9-what-must-be-decided-before-starting)
- [10. Conclusion](#10-conclusion)

## 1. Short verdict

> **Yes – about 90 % directly implementable, with six small but blocking
> refinements of the spec and two design decisions that must be left to the
> implementation.**

The core of the spec – event log, two projection layers,
delivery model, tool chain, write-ahead claim, recovery – is
**explicitly data-model- and flow-oriented** and maps cleanly onto Java.
What needs adjustment are **six places where the spec implicitly assumes a
functional language**, plus **five domain gaps that apply regardless of
language** and block an independent reimplementation.

| Area | Suitability | Note |
|---|---|---|
| Data model (types, ADTs) | **very good** | `sealed interface` + `record` + pattern matching is even **stronger** than the TS original |
| Event log & projections | **very good** | JDBC/JTA makes the transaction boundary a library guarantee |
| Delivery model (inbox, `steer`/`queue`) | **good** | pure data structure + state machine |
| Tool chain & hooks | **good** | tool registry + `InvocationHandler`/annotations |
| Write-ahead claim & recovery | **good** | one table, one sweep at start |
| Error channel | **medium** | "errors as values" is unidiomatic in Java → rewording needed |
| Abort masking | **weak** | Java knows **no** abort masking → rewording + replacement strategy needed |
| Location semantics | **missing** | not specified in the spec → **biggest open construction site** |
| Live feed | **medium** | half specified |
| Provider integration | **good** | described as an interface, selection/credentials not |

## 2. Mapping: neutral primitives → Java 21

| Spec primitive | Java-21 idiom | Risk | Note |
|---|---|---|---|
| `atomisch { … }` | `Connection.setAutoCommit(false)` + `commit()` / `rollback()`; in Quarkus `@Transactional` (Narayana JTA) | **low** | transaction must **not** cross a thread boundary – tool calls run in their own threads (see §5.4) |
| `publish(e)` | `INSERT INTO event …` + call of all projection functions **within** the same transaction | low | core of the whole architecture; enforced by `commit()` in Java |
| `fail(Fehler)` | typed exception (`sealed` hierarchy) or `Result` value | **medium** | spec says "no exceptions" → refinement needed (S-5) |
| `ensure { … }` | `try { … } finally { … }` | none | 1:1 |
| `maskierend { … }` | `ReentrantLock.lock()` (uninterruptible, unlike `lockInterruptibly()`) + publish terminal events before interrupt check | **high** | Java knows no abort masking (S-2) |
| `wiederherstellbar { … }` | block with `catch (InterruptedException e) { Thread.currentThread().interrupt(); … }` | medium | `Thread.interrupt()` is cooperative; the spec must name the replacement |
| `task { … }` | `Thread.ofVirtual()` / `Executors.newVirtualThreadPerTaskExecutor()` | **none** | Java 21: virtual threads final (JEP 444) – "one fiber per session" costs nothing |
| `abbruch(t)` | `Future.cancel(true)` → target throws `InterruptedException` | medium | `CompletableFuture.cancel` does **not** reliably abort: target code must also check the interrupt |
| `warte_alle([…])` | `ExecutorService.invokeAll` / `CompletableFuture.allOf` | low | `StructuredTaskScope` is **preview** in Java 21 (JEP 453) → plan B needed, do not use as basis |
| `parallel { … }` | `CompletableFuture.allOf` | medium | `allOf` aborts on the first error, siblings keep running → refine semantics (S-3) |
| `serialized_for(sessionID)` | `Map<SessionID, ReentrantLock>` + `lock()` | none | per session, not global (avoid global locks) |
| opaque identifiers | `record` with private constructor + static factory | none | prevents conversion between identifier types (I-01) |
| Sum types (`Event`, `SessionError`, `Message`) | `sealed interface` + `record` + `switch` with pattern matching | **none** | pattern-matching switch is final in 21 (JEP 441) – the folding from `message-updater.ts` becomes **total** (compiler checks completeness) |
| `Option<T>` | own `sealed interface Option<T>` | medium | `java.util.Optional` is unusual for fields/parameters and cannot express "present but null" |
| Live feed | `java.util.concurrent.Flow.Publisher` (JDK 9) or `ArrayBlockingQueue` per connection | none | "encode once" is uncritical in Java: `String` is built once and shared |
| Token estimate | own heuristic (chars/4) | none | 1:1 from `05-provider.md` §2 |

## 3. Six places where the spec assumes a functional language

| # | Place | Problem | Proposed change |
|---|---|---|---|
| **S-1** | `README.md:65` "`fail(Fehler)` … **no exceptions**"; `:81` "three-way model, **not** exceptions" | In Java the error channel is almost always an exception. A separate `Result` type hierarchy would be possible but costs considerable code (mappers, generators, generic helpers) without domain gain. | Change wording to: *"Errors are **typed, declared and closed**; the concrete representation (exception or value) is left to the implementation. What matters is that the three channels stay **separate**."* |
| **S-2** | `README.md:66-68` `maskierend` / `wiederherstellbar` | Java has **no** abort masking. `Thread.interrupt()` is cooperative; `InterruptedException` is thrown at the next check point. The assurance "terminal events are never aborted" cannot be kept like that. | Refine: *"During publication of a terminal event the abort is **deferred**, not cancelled."* Name the Java implementation: `ReentrantLock.lock()` (uninterruptible) instead of `lockInterruptibly()`, restore interrupt flag in `finally`, publish terminal event before every interrupt check. |
| **S-3** | `README.md:73` `parallel { … }` – "error ⇒ abort" | `CompletableFuture.allOf` aborts on the **first** error, siblings keep running and their results are lost – that contradicts the spec reading "all successful". | Refine: *"An error ends the **gathering operation**; sibling tasks keep running and their results are – if present – still added to the event sequence."* |
| **S-4** | `01-types.md` § "Type flow rules": "Errors are values, not exceptions" | Like S-1, second place. | Same wording as S-1. |
| **S-5** | `01-types.md` §6 `LogEntry` | The event has **no version field**, although I-07-04 demands a central upgrade of old forms and the actual code has one (`durable.version`). Without a version field the upgrade is not implementable. | Add `version: Int` to `LogEntry` and anchor in I-07-04/07 the rule "events carry a version; upgrade happens at **one** place". |
| **S-6** | `07-invariants.md` I-05-06 | "IF an extension module throws a foreign error, THEN it is normalized to a tool error at the tool boundary" – in Java `catch (Exception e)` also catches unchecked defects and would swallow exactly what I-06/06-04 forbids. | Refine: *"At the tool boundary only the **declared error family** is caught. A `catch (Exception)`/`catch (Throwable)` is forbidden, because it turns defects into errors."* |

> **Note:** S-1 to S-6 are **not** criticism of the spec but the observation
> that it uses a functional expression at four places that Java does not know.
> The substantive assurances remain valid unchanged.

## 4. Five domain gaps that apply regardless of language

These block an **independent** reimplementation more than the language
questions, because the spec does not answer them:

| # | Gap | Why blocking | Where the spec stands today |
|---|---|---|---|
| **L-A** | **Location is undefined.** The type exists (`01-types.md` §2), but no rule and no algorithm says when a location is entered/left, what location-scoped state is, how a move runs, how several locations live in one process. | In the actual code this is load-bearing: `context.ts:129-130` aborts when the session does not belong to the current location. A reimplementation without this rule builds either a global state (wrong) or a multi-location problem (unresolved). | `01-types.md` §2, `03-states.md` (no location automaton), `04-algorithms.md` §D (only `standort_geschlossen()`) |
| **L-B** | **No concurrency quota** for tool calls. G-05 says "parallel" but names no limit. | Unbounded parallelism is a DoS vector (the model can start 50 file operations at once). Needs a number, otherwise everyone implements differently. | `04-algorithms.md` G-05 |
| **L-C** | **Ordered publication** of parallel tool calls (G-05: "publication stays in call order") names neither the order nor the failure case. | In Java this needs a **sequencer**: compute results in parallel, publish in provider order. If a call fails, it must be decided whether the gap stays or the sequence advances. | `04-algorithms.md` G-05, `07-invariants.md` I-05-08 |
| **L-D** | **No retention rule** for waiting inbox items. | An inbox without TTL grows without bound; with TTL the question arises whether a prompt expires while the user is typing. | `01-types.md` §5, `04-algorithms.md` §A |
| **L-E** | **The live feed is half specified:** budget yes, but not encoding errors, frame order, heartbeat interval, reconnection behavior. | Exactly the part that visibly fails for a web/TUI surface. | `02-events.md` §7, `07-invariants.md` I-07-09…I-07-11 |

> **Further, milder gap:** The spec describes the provider *interface* well,
> but not the selection of provider/model (credentials, catalog,
> context window per model, cost, fallback) beyond `05-provider.md` §8.
> An independent implementation with several providers lacks that.

## 5. What is easier in Java

| # | Point |
|---|---|
| 1 | **Sum types become total.** `sealed interface Event` + pattern-matching switch enforces completeness **at compile time**. In the spec formulation "A-02: both projection layers must know every event" this is secured by the compiler: a new `record` variant breaks every non-adapted `switch` – **that is the answer to the convention gap marked in I-07-02!** |
| 2 | **Immutability is default.** `record` + `List.copyOf` structurally cover the spec convention "events are values". |
| 3 | **Virtual threads** make "one execution per session" and "one task per tool call" free (JEP 444, final in 21). |
| 4 | **Transactions are a library guarantee.** `setAutoCommit(false)` + `commit()` enforce I-07-01 without discipline. |
| 5 | **Pattern matching instead of text comparison.** Provider error classification (I-10-05) is cleanly solvable in Java via typed exceptions – what the actual code enforces via `getMessage().contains("401")` is **easy** to do right in Java and unpleasant in TypeScript. |
| 6 | **Immutable identifiers** as `record` with private constructor solve I-01-01…03 structurally. |

## 6. What is harder in Java

| # | Point | Why | Way out |
|---|---|---|---|
| 1 | **Abort masking** (S-2) | Java does not know it; `InterruptedException` is thrown at the next check point. | Publish terminal event before interrupt check; `ReentrantLock.lock()`; restore interrupt flag in `finally`. |
| 2 | **Three error channels** (S-1/S-6) | `catch (Exception)` catches unchecked defects too → the separation "defect ≠ error" becomes a discipline question. | A `sealed` error hierarchy; at the tool boundary catch **only** the declared family; forbid `catch (Exception)` (matches `core/src/tool/AGENTS.md`). |
| 3 | **Transaction × thread boundary** | Tool execution runs in its own task; a JTA transaction may not reach into it. But the spec requires "durable **before** the side effect". | Solvable and must be **explicit** in the spec: *Step 1: write event in transaction A and commit. Step 2: run tool in task T. Step 3: write result in transaction B.* → add as its own rule. |
| 4 | **`StructuredTaskScope` is preview** (JEP 453) | Must not be the basis. | `ExecutorService` (virtual threads) + `CompletableFuture`; build error aggregation yourself (the spec requires "exactly one terminal event" per call anyway). |
| 5 | **`Optional` ≠ `Option<T>`** | `java.util.Optional` is unusual for fields/parameters and cannot express a "present but empty" value; `null` is allowed everywhere in Java. | Own `sealed interface Option<T>` (30 lines) – the spec requires it explicitly anyway. |

## 7. Recommended changes to the spec before implementation start

**Blocking (6):**

1. `01-types.md` §6: add `version: Int` to `LogEntry`. *(S-5)*
2. `README.md:65` and `:81`: reword error rule – "typed, declared, closed" instead of "no exceptions". *(S-1)*
3. `01-types.md` type flow rules: same rewording. *(S-4)*
4. `README.md:66-68`: refine abort semantics + name Java replacement. *(S-2)*
5. `README.md:73`: refine `parallel` semantics (gathering operation ≠ sibling tasks). *(S-3)*
6. `07-invariants.md` I-05-06: "catch only the declared error family, `catch (Exception)` forbidden". *(S-6)*

**Domain-necessary (5):**

7. **Location semantics** as its own section (entry/exit/move/multi-location process). *(L-A)*
8. Tool concurrency quota as invariant + value. *(L-B)*
9. Sequencer rule: publication order and failure case. *(L-C)*
10. Retention/TTL of the inbox. *(L-D)*
11. Live feed: encoding errors, frame order, heartbeat, reconnection. *(L-E)*

**Domain-valuable (1):**

12. The rule "event and projections in **one** transaction, tool in its **own**
    task, result in a **further** transaction" explicitly added (Java-specific but
    generally useful).

## 8. Compact build plan (if Java 21 + Quarkus)

```
Module structure
  spec-core        types (records/sealed), event definitions, protocol
  spec-engine      state machines, tool chain, permissions, compaction
  spec-persistence JDBC adapter: publish(), projections, replay
  spec-provider    provider interface + one adapter (e.g. OpenAI-compatible)
  spec-http        Quarkus REST + SSE feed            ← per spec "non-goal",
                                                         needed for a product
  spec-app         TUI/CLI or web surface             ← ditto

Order (identical to recommendation in 08-porting.md)
  1  Types + log + projections   (without that no test is meaningful)
  2  Admission + delivery
  3  Execution + claim + resume
  4  Step + context + tool
  5  Provider
  6  Compaction + live feed + hardening

Test strategy
  · JUnit 5 + Testcontainers (PostgreSQL) for the integration level
  · The event channel is the test surface: a test reads the event sequence
    and checks invariants I-01…I-11 against it – that makes 111 rules
    testable without testing the engine from the inside
  · A generic test over the event manifest (I-07-02) falls out by itself in
    Java: pattern-matching switch enforces completeness
```

## 9. What must be decided before starting

| Question | Why it blocks | Reference |
|---|---|---|
| How many retries? | Determines abort and cost behavior; spec currently follows code (10), older spec says 4. | O-01 |
| `external_directory` allowed by default? | Security-relevant and anchored in tests. | O-07 |
| Location semantics | See L-A. | L-A |
| Error channel: exception or value? | Determines the whole API style. | S-1 |
| Tool concurrency quota | Determines resource requirements. | L-B |

## 10. Conclusion

The spec is **usable as an implementation template**. It describes
events, states and guarantees instead of mechanics, which is exactly the
right abstraction for an independent reimplementation. In Java 21 the
language part is smaller than in the original TypeScript version, because
ADTs, immutability and transactions are first-class there.

The **six blocking refinements** are one or two sentences each in the
spec – no rebuild. The **five domain gaps** are real gaps and need a
domain decision, not a text correction.

The two observations strongest independent of language from the porting
check apply here too:

* The spec convention "errors are **typed**" is *easier* to keep in Java
  with `sealed` hierarchies than in TypeScript – provided the channel is
  read as "typed and closed" instead of "not exceptions".
* The I-07-02 convention error ("both projection layers") **disappears by
  itself in the Java design** once the event ADT is `sealed`: the
  compiler prevents the forgotten `switch` branch.
