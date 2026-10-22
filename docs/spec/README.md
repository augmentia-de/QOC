# docs/spec – language-neutral specifications

> Four core specifications describe the agent runtime; persistence, operations
> and quality assurance are added. Each is **independent** of any programming
> language and of any existing implementation.

| Spec | Answers | Scope |
|---|---|---|
| [`session-engine/`](./session-engine/README.md) | How does an input become a sequence of model calls, and how does state stay robust? | 14 files, 111 invariants |
| [`tools/`](./tools/README.md) | How are tools built and how do file system, bash and MCP run? | 7 files |
| [`permissions/`](./permissions/README.md) | How is it decided whether something is allowed? | 6 files |
| [`provider/`](./provider/README.md) | What does a model call look like, and how are errors classified? | 7 files |
| [`persistence/`](./persistence/README.md) | What does the database structure look like? Tables, transactions, indexes, migrations | 3 files |
| [`betrieb/`](./betrieb/README.md) | What does the smallest runnable environment look like? HTTP/SSE, skills, credentials | 4 files |
| [`qualitaet/`](./qualitaet/README.md) | How does a rule become evidence? Tests, architecture gates, check matrix, implementability | 4 files |

**Cross-cutting:** [`module-plan.md`](./module-plan.md) sorts all modules
topologically by dependencies, defines the interfaces between them and describes
what an implementation may simplify internally – so that every module stays
individually testable. The physical implementation in Java packages under
`de.augmentia.oc.engine` including visibility and ArchUnit rules is in
[`java-packages.md`](./java-packages.md). How a rule becomes **evidence** and
which boundary breaks the build is in
[`qualitaet/`](./qualitaet/README.md). The questions this specification
**deliberately leaves open** are collected in
[`../open-questions.md`](../open-questions.md) – with meaning, simple
implementation and acceptance test.

## Dependencies

```
provider       ← knows only: one model call
permissions    ← knows only: rules, requests, replies
tools          ← knows: tool contract + the three families
session-engine ← uses provider, permissions, tools
betrieb        ← uses the engine and describes its external contracts
```

| Spec | Must **not** know |
|---|---|
| `provider` | sessions, tools, rights, orchestration |
| `permissions` | tool implementations, model calls |
| `tools` | session state, event log, provider protocol |
| `betrieb` | engine internals; it describes only the external contracts |
| `session-engine` | details of tools, rights, providers |

## Common conventions

* **Algebraic data types** – `type X = A | B`, records with `{}`.
* **Pseudocode** in a small, neutral vocabulary (`atomic`, `publish`, `fail`,
  `masking`, `task`, `wait_all`, `parallel`, `if`, `else`, `for`, `return`).
* **Invariants** with `I-xx-yy` (session), `W-xx` (tools), `P-xx` (rights),
  `V-xx` (provider) – each as IF/THEN with a check kind.
* **Java 21 implementation** at the end of each spec (`06-java.md` or
  `05-java.md`): `sealed` + `record`, virtual threads, JDBC/JTA,
  pattern matching.
* Source references `path:line` refer to the **reference checkout** (OpenCode
  V2, `dev` @ `3108505b2f`) and attest the **semantics**, not an adoption.

## Reading order for the curious

| If you … | read first |
|---|---|
| want the big picture | `session-engine/README.md` → `session-engine/04-algorithms.md` |
| want to build tools | `tools/README.md` → `tools/05-contract-invariants.md` |
| want to build rights | `permissions/README.md` → `permissions/02-evaluation.md` |
| want to connect a provider | `provider/README.md` → `provider/02-event-stream.md` |
| want to **start something** | `betrieb/README.md` → `betrieb/01-http-streaming.md` |
| want to **implement** | `module-plan.md` → the stage you are at |
| want Java code | the respective `*-java.md` |

## Audit reports

| File | Content |
|---|---|
| `session-engine/09-invariants-audit.md` | All 111 invariants checked against the reference code: 102 confirmed, 4 refined, 2 corrected, 2 notes, 1 convention |
| `session-engine/11-java-portability.md` | Whether and how the spec is independently implementable in Java 21: 6 corrections, 5 domain gaps |<|end|><|start|>write to=assistant<|channel|>analysis<|message|>Wrote file successfully: spec/README.md