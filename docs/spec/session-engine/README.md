# Session Engine — language-independent specification

> **Purpose:** Capture the semantics of the OpenCode Session Engine so that it can be
> implemented in **any** programming language — without reference to
> Effect, TypeScript, Bun, SQLite, or the existing repository.
>
> **Status:** 2026-10-22. Derived from the verified analysis in
> `docs/flow-db-service/` (code state `dev` @ `3108505b2f`, v2.1.2).
> **Relation to `docs/flow-db-service/`:** The Flow documentation describes
> *how it looks in the code today*. This series describes *what the system
> must do* — regardless of the language it is implemented in.
> Contradictions are listed in [`08-porting.md`](./08-porting.md).
>
> **Scope:** Session Engine core **plus** provider integration.
> Not included: HTTP transport, surfaces, administration, billing.

## Reading order

| File | Answers |
|---|---|
| [`01-types.md`](./01-types.md) | What values exist? (Algebraic data types) |
| [`02-events.md`](./02-events.md) | What is logged, how is it projected? |
| [`03-states.md`](./03-states.md) | What state machines exist? |
| [`04-algorithms.md`](./04-algorithms.md) | How do the procedures run? (Pseudocode) |
| [`05-provider.md`](./05-provider.md) | What does a provider look like? (neutral interface) |
| [`06-errors.md`](./06-errors.md) | What errors exist, what happens to them? |
| [`07-invariants.md`](./07-invariants.md) | What rules always hold? (testable) |
| [`08-porting.md`](./08-porting.md) | How do you map this to a target language? |
| [`09-invariants-audit.md`](./09-invariants-audit.md) | Audit report: all 111 invariants verified against the actual code |
| [`11-java-portability.md`](./11-java-portability.md) | Feasibility: can the spec be implemented in Java 21 (standalone)? |
| [`12-execution-model.md`](./12-execution-model.md) | Runtime: one execution per session, virtual threads, limits, shutdown |
| [`13-internal-prompts.md`](./13-internal-prompts.md) | Prompts: catalog, composition order, rules, critical text |
| [`Java-Impl.md`](./Java-Impl.md) | **Implementation template**: module structure, Java types, SQL, algorithms, tests, acceptance checklist |

## Notation

### Data types

Algebraic data types, sum types with `|`, records with `{}`:

```
type Delivery = Steer | Queue

type InboxItem =
  | UserItem      { text: Text, files: List<Attachment>, agents: List<AgentID>, skills: List<SkillID> }
  | SyntheticItem { text: Text, description: Option<Text>, metadata: Map }
  | CompactionItem{ reason: CompactionReason }
  | MoveItem      { directory: Path }
```

Conventions:

* `Option<T>` = `None | Some(T)`, `List<T>` = finite sequence, `Map` = key→value.
* `Opaque` = value whose origin the implementation hides (identifier, timestamp).
* `Text`, `Path`, `Bytes` = language-neutral terms, not concrete types.
* **Nominal vs. structural:** identifiers (`SessionID`, `MessageID`, `CallID`) are
  opaque and are **not** convertible into one another. This is an error source
  that should be kept deliberately.

### Pseudocode

A deliberately small vocabulary mappable to any language:

| Construct | Meaning | Note |
|---|---|---|
| `atomisch { … }` | Transaction; all or nothing | Nestable only as the outermost level |
| `publish(e)` | Append event durably **and** apply all projections | one transaction, see [`02-events.md`](./02-events.md) |
| `fail(Fehler)` | Typed error that aborts the current operation | no exceptions |
| `ensure { … }` | Always executed | Abort-safe |
| `maskierend { … }` | Section that cannot be aborted | for terminal events |
| `wiederherstellbar { … }` | Section inside `maskierend` where abort is allowed | only abort point |
| `task { … }` | Concurrent execution unit | result via `warte_alle` |
| `abbruch(t)` | Cancel task | result is discarded |
| `warte_alle([t…])` | Wait for all tasks to finish and collect their results | errors are bundled |
| `hole(… )` / `speichere(…)` | Read / write state | usually inside `atomisch` |
| `parallel { … }` | Indeterminate order, all successful | error ⇒ abort |

Every procedure in [`04-algorithms.md`](./04-algorithms.md) carries:

* **Precondition** – what holds on entry.
* **Postcondition** – what holds on success.
* **Error cases** – which typed errors can arise.

Error handling follows a **three-way model**, not exceptions:

```
Ergebnis = Ok(Wert) | Fehler(Fehlertyp)
```

An error propagates outward until someone handles it. Abort
(interrupts) is a **third** channel and is never converted into an error:

```
Abbruch(Grund)
```

## Core ideas in one sentence

> **A session is a durable event log; an execution is a
> process-local state machine that produces events; a step is
> exactly one model call; a tool call is logged before its side effect.**

## Ownership: who owns what

The Engine **owns** state and **calls** what it does not own. This
separation is the most common misunderstanding – hence explicit here.

```
┌──────────────── owns the Engine ────────────────────────────────┐
│ Session state · Inbox · Event log · Execution/Claim             │
│ Tool SNAPSHOT (what is offered) · Tool CALL (order, context,    │
│ normalization, truncation) · Permission CALL                   │
└───────────────────────────────────────────────────────────────────┘
                              │  calls
        ┌─────────────────────┼─────────────────────┐
        ▼                     ▼                     ▼
  Tool leaves           Permission engine         Provider
  (filesystem, shell,   (rules, stored            (exactly ONE
   MCP, …) – only leaf  approvals)               model call)
   logic
        │                     │                     │
        └─────────────────────┴─────────────────────┘
                              │  know neither
                     Session, tools, permissions
```

| Thing | Who owns it | Who **calls** it |
|---|---|---|
| Session state, inbox, log, claim | **Engine** | – |
| Tool **implementation** (leaf logic) | Tool author | Engine (at execution) |
| Tool **offer** (which tools, which snapshot) | **Engine** | – |
| Tool **call** (order, context, bounding) | **Engine** | – |
| Permission **rules** and stored approvals | Permission engine (config/DB) | Engine (checks) |
| Permission **check** (evaluation, inquiry, answer) | **Engine** (calls `assert`) | – |
| Model call, error classification, stream | **Engine** | Provider adapter (translates only) |
| Provider, protocol, transport, credentials | Provider | Engine (adapter) |

**Key takeaway:** The Engine **does not decide what is allowed** (that comes from
configuration), and it **implements no tools** (that comes from
tool authors). It decides **when** a tool runs, **whether** it may
run, **in what order**, and **what the result means**.

> The provider knows neither session nor tools nor permissions – it knows
> exactly one model call. That is why a provider adapter is interchangeable
> in any language.

## Non-goals

* No specifications for transport, protocol, UI, database product.
* No specifications for the target language's concurrency model – only the
  observable ordering and abort guarantees.
* No performance specifications except those that are invariants
  (e.g. one step = one model call).