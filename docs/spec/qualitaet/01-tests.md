# Tests – specification of the evidence

> **Purpose:** Not *how* to test (that is in
> [`../module-plan.md`](../module-plan.md) §7), but **which rule is proven by
> which test**, with which data and at which stage.
>
> **Principle:** A test is the evidence of **exactly one** rule. A test without
> a rule is documentation, a test with two rules is a debugging tool.
>
> **Status:** 2026-10-22

## 1. Five test kinds

| Kind | Stage | Proves | Infrastructure | Budget |
|---|---|---|---|---|
| **Pure test** | S0–S2 | computation rule, pattern, classification | none | < 1 min |
| **Contract test** | every port | fake **and** adapter behave identically | fake + PostgreSQL | < 5 min |
| **Module test** | S3–S7 | interaction of a module with fakes | fakes | < 10 min |
| **Integration test** | adapter, HTTP | transaction, projection, serialization | PostgreSQL, real server | < 15 min |
| **Acceptance test** | whole | the path user → effect → answer | server + stub provider | < 15 min |

The budgets are **goals for an individual run**, not averages. A test that
exceeds them is either too large or wrongly cut.

## 2. Which rule gets which kind

| Rule group | Kind | Rationale |
|---|---|---|
| pure functions (patterns, classification, budget, truncation) | pure | no state, no adapter |
| ports (`Journal`, `Conversation`, `ClaimStore`, …) | contract | only then is the fake worth something |
| state machines (`session`, `delivery`, `revert`) | module | needs fakes of the ports |
| projections and transactions | integration | only the real database shows atomicity |
| external contracts (`U`, `HS`, `SK`, `CR`) | pure, contract, integration | frames and resolution are pure; HTTP, watchers and rights need a real server |
| end-to-end (A-01…A-12) | acceptance | proves interaction, not the unit |

## 3. The evidence rule

**Q-1** Every rule with an ID (`I-`, `W-`, `P-`, `V-`, `U-`, `HS-`, `SK-`,
`CR-`) has at least one test that names it in its title.

**Q-2** Every test names the rule it proves – as an annotation, in the name
or in the first line. A test without a rule reference is not written.

**Q-3** No test sleeps. Time comes from `Clock`; repetitions are played
through, not waited for.

**Q-4** No test shares state: its own session ID, its own schema or its own
in-memory instance. Order must not matter.

**Q-5** Every contract test runs against **both** implementations. A contract
test that checks only the fake is an assertion.

**Q-6** A failing test names rule and expectation, not the method. "I-06-03
violated: saved approval overrode denial" is a message; "assertFalse failed at
line 42" is not.

**Q-7** Load, migration and recovery tests run **scheduled** (nightly), not on
every commit – but they run, and their result is a gate for release.

**Q-8** A test that goes red without a code change is flaky: it is repaired or
deleted, never talked green with retries.

### Evidence file

Every spec has a table `Evidence` with four columns:

| Rule | Test | Kind | Status |
|---|---|---|---|
| `I-02-03` | `AdmissionIdempotentTest` | Integration | green |
| `W-04-02` | `ToolOutputBoundTest` | pure | green |
| `P-03-01` | `SavedApprovalBeatsDenyTest` | Module | open |

**Rule:** A rule without a row is not "untested" but **not implemented**. The
file is the acceptance criterion for a module.

### Evidence file of the base environment

The 46 rules from [`../betrieb/`](../betrieb/README.md) have their own rows.
All stand on `open` until the first run shows them green.

| Rule | Test | Kind | Status |
|---|---|---|---|
| `U-01` | `OneProcessOneLocationTest` | Integration | open |
| `U-02` | `SingleModelTest` | Integration | open |
| `U-03` | `SingleAgentTest` | pure | open |
| `U-04` | `ClosedToolSetTest` | pure | open |
| `U-05` | `EffectfulToolsAskTest` | Integration | open |
| `U-06` | `SseOnlyTest` | Integration | open |
| `U-07` | `NoSkillsValidTest` | Integration | open |
| `U-08` | `OneCredentialTest` | Integration | open |
| `U-09` | `SingleStoreTest` | Integration | open |
| `U-10` | `ClientComposesNoPromptTest` | Contract | open |
| `HS-01` | `SseFrameParserTest` | pure | open |
| `HS-02` | `SseConnectedFirstTest` | Integration | open |
| `HS-03` | `SseHeartbeatTest` | Integration | open |
| `HS-04` | `SseOverflowTest` | Integration | open |
| `HS-05` | `ResumeFromSnapshotTest` | Integration | open |
| `HS-06` | `EnvelopeCreatedTest` | pure | open |
| `HS-07` | `DurableSeqTest` | pure | open |
| `HS-08` | `TextEndedIsTruthTest` | Integration | open |
| `HS-09` | `PromptAdmissionTest` | Integration | open |
| `HS-10` | `PromptIdempotencyTest` | Integration | open |
| `HS-11` | `ErrorMappingTest` | Contract | open |
| `HS-12` | `LocationResolutionTest` | Integration | open |
| `SK-01` | `SkillIsDataTest` | pure | open |
| `SK-02` | `SkillSourceTest` | pure | open |
| `SK-03` | `SkillScanDepthTest` | pure | open |
| `SK-04` | `SkillIdTest` | pure | open |
| `SK-05` | `SkillPrecedenceTest` | pure | open |
| `SK-06` | `SkillVisibilityTest` | pure | open |
| `SK-07` | `SkillInstructionDeltaTest` | Module | open |
| `SK-08` | `SkillPermissionTest` | Module | open |
| `SK-09` | `SkillContentTest` | pure | open |
| `SK-10` | `SkillPromptAttachmentTest` | Integration | open |
| `SK-11` | `SkillInvalidFrontmatterTest` | pure | open |
| `SK-12` | `SkillReloadTest` | Integration | open |
| `CR-01` | `CredentialNotSessionStateTest` | Integration | open |
| `CR-02` | `CredentialKindsTest` | pure | open |
| `CR-03` | `CredentialResolutionOrderTest` | pure | open |
| `CR-04` | `CredentialEnvShadowedTest` | pure | open |
| `CR-05` | `CredentialNoLeakTest` | Integration | open |
| `CR-06` | `CredentialAuthTest` | Contract | open |
| `CR-07` | `CredentialActivationTest` | Integration | open |
| `CR-08` | `OAuthRefreshTest` | pure | open |
| `CR-09` | `CredentialCacheInvalidationTest` | Integration | open |
| `CR-10` | `MissingCredentialTest` | Integration | open |
| `CR-11` | `BasicEnvironmentOneProviderTest` | Integration | open |
| `CR-12` | `CredentialRotationTest` | Integration | open |

## 4. The twelve acceptance cases and their data

A-01…A-12 are in
[`../session-engine/07-invariants.md`](../session-engine/07-invariants.md)
§I-12. Here only **what** they run with:

| Case | Kind | Data and tool |
|---|---|---|
| A-01 Admission before execution | Integration | PostgreSQL, fixed session ID, `CountingWake` |
| A-02 idempotent admission | Integration | same ID twice, then conflict ID |
| A-03 exactly one provider call per step | Acceptance | `ScriptedProvider` with one event stream |
| A-04 new call after tool result | Acceptance | `ScriptedProvider` with two streams |
| A-05 protocol before side effect | Module | `InMemoryJournal` + tool that queries its position |
| A-06 deny beats saved approval | Module | `FixedPolicy` + `InMemoryApprovals` |
| A-07 denial without reason | Module | `ScriptedApproval` with `denied` without text |
| A-08 denial with reason | Module | `ScriptedApproval` with text |
| A-09 defective event aborts transaction | Integration | intentionally invalid payload, then check row count |
| A-10 crash with open claim | Integration | stop process, restart, check counter |
| A-11 double terminal event | pure | two events, expect `Defect` |
| A-12 transcript survives compaction | Integration | long history, compact, read full history |

## 5. Test data

### Fakes

The table from [`../module-plan.md`](../module-plan.md) §7.3 applies. In
addition:

| Fake | Must be able to | Must not |
|---|---|---|
| `InMemoryJournal` | count sequences, separate per aggregate | fake transactions |
| `ScriptedProvider` | deliver event streams in fixed order, fail at selectable position | real time or network |
| `ScriptedApproval` | one answer per action, including "no answer" | keep state between tests |
| `FakeFileSystem` | derive directories from paths, hold binary data | resolve real paths |
| `FixedClock` | immediate `sleep`, fixed time, monotonic deadlines | fast-forward time without a call |

### Database

* **One schema per test**, built from the Flyway state – never from a dump.
* **Template database** for runtime; the schema is cloned, not re-migrated
  (except in the migration test itself).
* **No production data.** Session IDs and timestamps are invented.
* **Cleanup is part of the test**: the test leaves no schema behind.

## 6. Stages and gates

| Stage | Content | Trigger | Budget | Blocks merge |
|---|---|---|---|---|
| 1 | pure tests | every commit | 60 s | yes |
| 2 | contract tests (fake + SQL) | every commit | 5 min | yes |
| 3 | module tests S3–S7 | every commit | 10 min | yes |
| 4 | integration + acceptance A-01…A-12 | merge request | 15 min | yes |
| 5 | load, migration, recovery | nightly | 60 min | release |

**Rule:** Stages 1–3 run without network and without a database. Whoever needs
a database in stage 2 has not understood the contract.

## 7. What is checked beyond the twelve cases

| Check | Question | Kind |
|---|---|---|
| **Folding** | Does every event sequence produce the same history, regardless of the order in which projections run? | property test over random sequences |
| **Idempotency** | Does running the same projection twice change nothing? | property test |
| **Migration** | Does Flyway lead from empty **and** from every intermediate state to the same schema? | migration test |
| **Recovery** | Is an aborted run continued without duplicating an event? | integration with process abort |
| **Rights** | Does no call get past a `deny`, even via detours? | module + integration |
| **Load** | Does the event stream stay complete and in order with many connections? | load test |
| **Bounds** | Does an output stay under the limit **and** get stored completely? | pure + integration |

## 8. Definition of done for a module

A module is finished when all six points are met:

- [ ] every one of its rules has a row in the evidence file
- [ ] its ports have contract tests against fake **and** adapter
- [ ] its error cases are tested, not only the success case
- [ ] no test sleeps, none shares state
- [ ] the module test runs without the stages above it
- [ ] the evidence file is green, not "green except …"

> **Why this is strict:** The spec has 315 rules. Without this discipline a
> test suite emerges that covers 80 % of the lines and does not know 30 % of
> the rules – and both look the same in a report.