# Implementability – what is missing to build

> **Question:** Can a developer build a working implementation from this
> specification alone – without the reference code?
>
> **Answer:** The **core** yes. The **base environment** (one client, one
> provider, HTTP/SSE, skills, credentials) as well. The **product surface**
> (configuration, agent catalog, commands, catalog update) not yet.
>
> **Status:** 2026-10-22 · checked against `spec/` (49 files)

## 1. Yardstick

A building block is implementable when four questions can be answered without
recourse to the reference code:

| # | Question | Example |
|---|---|---|
| 1 | **Behavior** – what does it do, in what order? | "Admission writes the inbox row before execution" |
| 2 | **Data** – which types, formats, fields? | `AgentInfo`, `EventDraft`, DDL |
| 3 | **Edge cases** – what happens on error, abort, repetition? | three error channels |
| 4 | **Evidence** – how do you recognize conformance? | rule with ID + test |

A building block that answers only the first two questions is a sketch. One
that answers none of them is a slogan.

## 2. Matrix

Legend: **✅** implementable · **◐** partial · **✗** missing

| # | Building block | Status | Evidence | What is missing to build |
|---|---|---|---|---|
| 1 | Values and types | ✅ | `session-engine/01-types.md` | – |
| 2 | Events and projections | ✅ | `session-engine/02-events.md` | – |
| 3 | State machines | ✅ | `session-engine/03-states.md` | – |
| 4 | Admission, delivery, revert | ✅ | `session-engine/04-algorithms.md` §A–E | – |
| 5 | Step and tool loop | ✅ | `04-algorithms.md` §G–H | – |
| 6 | Compaction | ✅ | `04-algorithms.md` §I, `13-internal-prompts.md` | – |
| 7 | Errors and resumption | ✅ | `session-engine/06-errors.md` | – |
| 8 | Tool contract | ✅ | `tools/01-types.md`, `05-contract-invariants.md` | – |
| 9 | File system, bash, MCP | ✅ | `tools/02`–`04` | – |
| 10 | Rights | ✅ | `permissions/` (6 files) | – |
| 11 | Provider protocol and errors | ✅ | `provider/01`–`03` | – |
| 12 | Catalog, budget, cache | ✅ | `provider/04-catalog-response.md` | – |
| 13 | Persistence and migration | ✅ | `persistence/` (3 files) | – |
| 14 | Modules and ports | ✅ | `module-plan.md` | – |
| 15 | Java packages and ArchUnit | ✅ | `java-packages.md`, `qualitaet/02` | – |
| 16 | Tests | ✅ | `qualitaet/01-tests.md` | – |
| 17 | **Internal prompts** | ✅ | `session-engine/13-internal-prompts.md` | was **✗** until 2026-10-22 |
| 18 | **Agent catalog** | ◐ | `01-types.md` §7 | origin, built-in set, default selection |
| 19 | **Configuration schema** | ✗ | – | file format, fields, precedence |
| 20 | **HTTP API** | ✅ | `betrieb/01-http-streaming.md` | – (base set; extensions open) |
| 21 | **Skills** | ✅ | `betrieb/02-skills.md` | – (base set; URL catalog open) |
| 22 | **Credentials and sign-in** | ✅ | `betrieb/03-credentials.md` | – (base set; encryption open) |
| 23 | **Commands** | ◐ | `13-internal-prompts.md` (`CMD-*`) | execution, arguments, subagent binding |
| 24 | **Catalog update** | ◐ | `provider/04` §1 (`ModelInfo`) | source, format, timing |

**20 of 24 building blocks are complete.** The remaining four concern the
product surface; none of them changes the core.

## 3. Verdict by build target

### 3.1 Core engine (S0–S7): **buildable**

Sessions, events, steps, tools, rights, providers, persistence and compaction
are fully described. A developer can build the engine **in process** and accept
it via tests (A-01…A-12).

The core needs **no configuration parser**: `AgentInfo` is a type
(`01-types.md` §7), `EngineConfig` is built in code
(`java-packages.md` §4, `wiring`). One agent `build` as default suffices for
the first runnable run.

### 3.2 Base environment (S8, client): **buildable**

`betrieb/01-http-streaming.md` fixes transport, frames, envelope, lifecycle,
the 18 core endpoints, error mapping, authentication, location and resumption.
`betrieb/02-skills.md` and `betrieb/03-credentials.md` close the other two
external contracts. A client can be built and tested without reading the
reference code.

Not included are the product extensions: `fork`, `revert`, `move`, commands,
forms, PTY, VCS. They do not change the base contract.

### 3.3 Product surface: **partly buildable**

Configuration file, agents as files and commands are still product functions
without a schema. Skills and sign-in are now described, but as a **base set**:
three skills, one provider connection. Whoever wants to fully replace a
compatible installation additionally needs L-1, L-2, L-6 and L-7.

## 4. The gaps in detail

### L-1 · Agent catalog ◐

**Present:** `AgentInfo` with `system`, `mode`, `steps`, `permissions`,
`hidden` (`01-types.md` §7); the resolution order for models.

**Decided for the base environment:** exactly one agent `build` with fixed
rights ([`../betrieb/README.md`](../betrieb/README.md) §3). This makes the
first run possible; the **catalog** remains open.

**Missing:**

* Where agents come from (file, plugin, built-in).
* Which built-in agents exist (`build`, `plan`, `explore`, `title`,
  `summary`).
* Which is the default, and how a `subagent` is selected against a `primary`.
* How `AgentInfo` is formed from a configuration file.

**Without these answers** `choose_agent(session.agent)` is not implementable –
the function is named, the data source is not.

### L-2 · Configuration schema ✗

`persistence/README.md` §7 correctly states that configuration does **not**
belong in the database. But there is no schema for the files: rights, agents,
providers, MCP servers, formatters, model choice.

**Consequence:** A port can configure the engine, but cannot take over an
existing installation. That is the difference between "runs" and "replaces".

### L-3 · HTTP API ✅

**Closed** by [`../betrieb/01-http-streaming.md`](../betrieb/01-http-streaming.md).
Fixed are:

* Transport: `GET /api/event`, `text/event-stream`, `data: <json>\n\n`,
  heartbeat every 15 s.
* Frames and envelope: `id`, `type`, `created`, `data`, `location?`,
  `metadata?`, `durable?`.
* Lifecycle: `server.connected` first; buffer 4096; overflow ends the stream;
  **no** replay.
* The 18 endpoints of the base set with success and error forms.
* Error → status (`400`, `401`, `404`, `409`, `500`, `503`).
* Location, authentication and resumption via the snapshot.
* 12 rules `HS-01…HS-12` with evidence.

**Remaining open** is only comfort: a `since` cursor for messages (HS-A), a
`retry:` hint (HS-C) and block IDs on deltas (HS-D). None of these questions
blocks a client.

### L-4 · Skills ✅

**Closed** by [`../betrieb/02-skills.md`](../betrieb/02-skills.md). Fixed are:

* Sources (`<project>/skill`, `<project>/skills`; configured paths and URLs
  remain an extension), search depth, flat skills and precedence with the same
  ID.
* Frontmatter (`name`, `description`, `metadata`, `disable-model-invocation`)
  and derivation of the ID.
* Visibility: `description` set, `autoinvoke != false`, right not `deny`.
* Insertion: The **list** stands in the instruction part of the system prompt
  (`core/skill-guidance`), the **content** comes as a tool result or as an
  attachment to the user message. With that **PRM-D** is decided.
* Three concrete skills (`commit`, `review`, `test`) and their limits.
* 12 rules `SK-01…SK-12` with evidence.

**Remaining open** is the URL catalog with versioning (SK-B) and the hard
interpretation of the size limits (SK-A).

### L-5 · Credentials ✅

**Closed** by [`../betrieb/03-credentials.md`](../betrieb/03-credentials.md).
Fixed are:

* Kinds (`key`, `oauth`, `external`) and the non-stored connection `env`.
* Store (reference: table `credential`; the location is an operational
  decision), activation, sorting and the resolution order: stored (active
  first) → `env` → none.
* Environment variables of the providers, OAuth renewal five minutes before
  expiry.
* The HTTP surface for credentials and bindings.
* What the base environment needs: **one** provider credential, one server
  password, one database role; otherwise nothing.
* 12 rules `CR-01…CR-12` with evidence.

**Remaining open** is encryption at rest (CR-A), project-scoped credentials
(CR-B) and masking in the API (CR-C).

### L-6 · Commands ◐

The prompt texts (`CMD-INIT`, `CMD-REVIEW`) now stand in the catalog
(`13-internal-prompts.md`). Open is the mechanics: how a command is invoked,
how arguments are substituted, whether it may start a subagent, how its result
becomes visible.

### L-7 · Catalog update ◐

`ModelInfo` is complete (`provider/04` §1). Unclear remains **where** the
catalog comes from and **when** it refreshes – a shipped snapshot, a query at
startup or a background run. That decides whether a model can "disappear"
while a session is running.

## 5. The smallest runnable cut

The base environment is now **fully described** and can be built without
recourse to the reference code:

| Part | Decision | Where |
|---|---|---|
| Engine S0–S7 | `EngineConfig` constructed directly | `java-packages.md` §4 |
| one provider | one adapter, credential from an environment variable | `betrieb/03` §6 |
| persistence | PostgreSQL, Flyway, one schema | `persistence/` |
| tools | `read`, `write`, `edit`, `glob`, `grep`, `shell`, `skill` | `tools/` |
| rights | one fixed `Ruleset` in code | `permissions/` |
| agents | exactly `build` as default | `betrieb/README.md` §3 |
| prompts | catalog from resources | `13-internal-prompts.md` |
| skills | three from the project directory | `betrieb/02` §6 |
| client | HTTP + SSE | `betrieb/01` |

**The order remains:** first the core with tests, then the outer skin. Whoever
starts with `httpapi` builds a shell around behavior they have not yet tested.
New is that the shell now has a contract.

## 6. Order of closure

| Step | Gap | Status |
|---|---|---|
| 1 | ~~Prompts~~ | **done** (`13-internal-prompts.md`) |
| 2 | ~~HTTP API~~ (L-3) | **done** (`betrieb/01`) |
| 3 | ~~Skills~~ (L-4) | **done** (`betrieb/02`) |
| 4 | ~~Credentials~~ (L-5) | **done** (`betrieb/03`) |
| 5 | Agents + configuration (L-1, L-2) | **open** – without them there is no installation, only a program |
| 6 | Commands, catalog update (L-6, L-7) | **open** – comfort and currency |

## 7. What is not needed

| Topic | Why not |
|---|---|
| TUI, app, desktop | clients, not engine |
| Plugin SDK | extension point, not core behavior |
| Session sharing, cloud sync | product function without influence on invariants |
| Telemetry | operational decision |
| Formatters, LSP | tools of the surface, not the engine |

## 8. Self-check of this verdict

The yardstick can be falsified: try to write three things **from the spec alone**.

| Attempt | Result | Evidence |
|---|---|---|
| implement an endpoint | **possible** – path, method, success, error, frame | `betrieb/01` |
| write an `opencode.json` | impossible – no schema | L-2 |
| load a skill | **possible** – sources, format, visibility, tool | `betrieb/02` |

If one of the two remaining attempts succeeds anyway, the matrix is too
pessimistic – then the finding belongs in this document. Until then:
**The core and the base environment are implementable; the full product surface
is not.**

> **The honest summary:** 315 rules describe the behavior of the engine and its
> three external contracts (HTTP, skills, credentials) completely enough to
> build. But they do not yet describe the **configuration** and the **agent
> catalog** – that is, the question of how an installation forms its roles,
> models and rights from files. That remains the boundary between base
> environment and product.