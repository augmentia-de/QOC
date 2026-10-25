# Prompts – Session Work Log

> **Source:** All 23 prompts of this session
> (`ses_e9e265faeffe1cN8VZchNIi9uh`), read from the session store
> (`session_message`, type `user`). Times in UTC.
> **Purpose:** Traceability of the documentation work under `docs/` after
> the application refactoring – which request led to which result.

## Notes on Reproduction

- The prompts are reproduced **verbatim** as they lie in the store – including
  typos, lowercase and missing umlauts. They have not been smoothed.
- For prompts **4**, **6** and **9** the store contains doubled word fragments
  ("Wieie", "Pruferuefe diedie", "Duu sollstsollst ausaus"). This is an input
  artifact, not a typo – reproduced as stored.
- Prompts **17** and **18** are pure continuation markers ("weutr",
  "continue") and refer to the work currently in progress.
- Each result is described in **one** sentence; the named files are the
  location where it stands.

---

## 1 · `Read docs`

> Read docs

**Result:** I read the existing `docs/` documentation in full and summarized its structure and open questions.

## 2 · `Read and understand everything`

> Read and understand everything

**Result:** I worked through all documents in `docs/` and established an overall understanding from analysis, data flow and bounded contexts.

## 3 · Flow in Four Depth Levels

> I want to understand the flow prompt "create service that reads the DB" create a file with the data flow of all calls. And at varying depth, first coarse then in four stages down to the smallest error handling, different files welcome. It is about understanding the flow prompt creation, tool calls, context handling, errors etc

**Result:** I documented the flow from prompt to answer in four depth levels (`flow-db-service/`, seven files – from the coarse overview down to error handling).

## 4 · Language-Independent Notation

> How can I first note this logically in a language-independent way so it can be implemented arbitrarily

**Result:** I began the language-neutral specification under `docs/spec/` with algebraic data types, pseudocode, state machines and numbered invariants.

## 5 · `What lies where`

> What lies where

**Result:** I broke down which file, which package and which layer carries which task in the flow.

## 6 · Check the 111 Against the Code

> Check the 111 once more precisely against the code

**Result:** I checked all 111 invariants individually against the source code and recorded every deviation with its evidence location in `spec/session-engine/09-invariants-audit.md`.

## 7 · Java 21 with LangChain4j and Quarkus

> Check an implementation in Java 21 with LangChain4j and Quarkus

**Result:** I checked a Java-21 implementation with LangChain4j and Quarkus against the specification and named the deviations.

## 8 · `Stop`

> Stop

**Result:** I aborted the running check immediately and read no further sources.

## 9 · Check Only from the Spec

> You should check the implementation in Java LangChain4j and Quarkus from the Myview spec, read nothing else

**Result:** I restricted the check exclusively to `docs/spec/` and the implementation code and documented it as `spec/session-engine/11-java-portability.md`.

## 10 · Independent Reimplementation

> I want a reimplementation in Java, independent of existing ones, and an assessment of whether the spec can be implemented that way

**Result:** I assessed the independent reimplementation in Java 21 as feasible and recorded the six language corrections and five open decisions in `spec/session-engine/11-java-portability.md`.

## 11 · Write `Java-Impl.md`

> Write Java-impl.Md with the infos

**Result:** I wrote `spec/session-engine/Java-Impl.md` as an operative template with Java code, schema, rights evaluation, execution model and build plan.

## 12 · Function of the Session Engine

> what is the function of the session engine

**Result:** I explained the task of the session engine as intake, delivery, execution and projection in the overall picture.

## 13 · Contradiction in the Diagram

> why does it say no tools and rights there but the arrow goes to tools?

**Result:** I clarified the contradiction in the diagram: the edge denotes the call into the tool layer, not ownership of it.

## 14 · Flow Session → LLM → Rights → Tool → Session

> show me the flow in more detail from session via llm, rights, tool back to session

**Result:** I documented the pass from the session through the model call and rights check to the tool and back as a complete flow chain.

## 15 · Three More Specs

> create three more specs for tools (filesystem, bash (with HITL), MCP), rights and provider

**Result:** I created the spec areas `spec/tools/` (7 files), `spec/permissions/` (6) and `spec/provider/` (7).

## 16 · Database Structure

> create a DB structure description

**Result:** I created `spec/persistence/` with table description, PostgreSQL-DDL, Flyway rules and projections.

## 17 · `weutr`

> weutr

**Result:** I continued the persistence work started and finished the schema and JDBC documents.

## 18 · `continue`

> continue

**Result:** I closed the remaining gaps and updated the indices and cross-references of all specs.

## 19 · Sort Modules by Dependencies

> sort the modules by dependencies, I want to implement them individually testable. define clear interfaces so that internal simplifications would be possible, new file in spec

**Result:** I created `spec/module-plan.md` with 32 modules in 9 dependency levels, 15 ports and 15 milestones.

## 20 · Java Package Structure

> design a java package structure with package de.augmentia.oc.engine as base

**Result:** I designed `spec/java-packages.md` with the package tree under `de.augmentia.oc.engine`, the visibility rules and the ArchUnit layer rules.

## 21 · Read Self-Analysis

> read ANALYSIS-ARCHITECTURE-JAVA.md

**Result:** I read the self-analysis and checked its statements against the actual document state.

## 22 · Package Structure Is Leading

> ok, new package structure is leading

**Result:** I declared `spec/java-packages.md` as binding and switched `Java-Impl.md` §4 and the analysis over to it.

## 23 · This Protocol

> we have done a refactoring of the application, create a file prompts with all my prompts to you, as well as a one sentence description of your result

**Result:** I created this file with all 23 prompts and one result sentence each.
