# open-questions.md – The open points

> **Status (2026-10-22): All points in §§1–7 are decided.** The
> decisions are in §10 (decision log); the spec is updated accordingly
> (rule from §10). The sections below remain as rationale and simple
> implementation.
>
> **What this file is:** a register of the questions the specification
> **deliberately left undecided**. For each point it states what it means,
> what a **simple implementation** looks like and how it is accepted.
>
> **What this file is not:** not a list of bugs. The 46 baseline-environment
> proofs stand at `open` because they have not run yet – that is something
> else (see §8).
>
> **Status:** 2026-10-22 · Repo state `dev` @ `3108505b2f`
> **Basis:** [`spec/`](./spec/README.md), [`ANALYSIS-ARCHITECTURE-JAVA.md`](./analysis/ANALYSIS-ARCHITECTURE-JAVA.md)

## Contents

- [Legend](#legend)
- [1. Summary](#1-summary)
- [2. The four big gaps](#2-the-four-big-gaps)
- [3. Behavioural decisions](#3-behavioural-decisions)
- [4. Prompt and product decisions](#4-prompt-and-product-decisions)
- [5. Detail decisions in closed areas](#5-detail-decisions-in-closed-areas)
- [6. Architecture and package decisions](#6-architecture-and-package-decisions)
- [7. Findings when porting (example Java port)](#7-findings-when-porting-example-java-port)
- [8. What "open" means for tests](#8-what-open-means-for-tests)
- [9. Recommended order](#9-recommended-order)
- [10. Decision log](#10-decision-log)

## Legend

| Symbol | Meaning |
|---|---|
| **A** | blocks a **replaceable installation** (not just a program) |
| **B** | changes visible **behaviour**; a decision is needed before tests are fixed |
| **C** | **convenience** or detail; the baseline environment works without a decision |
| **S / M / L** | effort: hours / one to two days / more than two days |
| ⭐ | Recommendation: decide first |

**Principle:** Every point gets **one** decision, a date and a test. An open
question that blocks nobody stays open – it is not implemented "somehow".

---

## 1. Summary

| # | Point | Type | Effort | Decision (2026-10-22) |
|---|---|---|---|---|
| **L-1** | Agent catalog | A | M | File-based (project overrides global) |
| **L-2** | Configuration schema | A | M | File-based, strict JSON |
| **O-01** | Retry count | B | S | 4 total, configurable 1–10 |
| **O-07** | `external_directory` | B | S | global working directory + allow-list (read/write) |
| **L-6** | Commands | B | S–M | Text macro via inbox |
| **L-7** | Catalog update | B | S | Snapshot + explicit update |
| **L-A** | Location semantics | B | M | One location per process (baseline) |
| **L-B** | Tool parallelism | C | S | 8 concurrent per step |
| **L-C** | Ordered publication | C | S | Sequencer with buffer |
| **L-D** | Inbox retention | C | S | No TTL |
| **P-1** | Where are credentials? | B | S | env only (baseline) |
| **PRM-A** | Provider templates | C | S | Drop |
| **PRM-B** | `AGT-SUMMARY` | C | S | Drop |
| **PRM-C** | Commands: engine or client? | B | S | Engine |

**The three decisions that really come first:** `O-01`, `O-07`
and `L-2`. The first two are decided in an hour; `L-2` is the
prerequisite for `L-1` and for any existing installation.

---

## 2. The four big gaps

### L-1 · Agent catalog

**Today.** The baseline environment knows **exactly one** agent: `build`, with
fixed rights and an optional `steps` limit (absent by default)
([`betrieb/README.md`](./spec/betrieb/README.md) §3).
That suffices for the first run, but not to replace an existing installation.

**What is open.**

1. Where do agents come from: file, plugin or built-in?
2. Which built-in agents exist?
3. Which is the default, and how is a subagent selected?
4. How is an agent built from a file?

**What you need to understand.** An agent is **not** a program, but a bundle
of four things:

* a **system text** ("you are …"),
* **rights** (what it may do),
* **model and step budget** (with what and how long),
* a **visibility** (`primary` = selectable, `subagent` = only called,
  `hidden` = not in the list).

The catalog is the question of **where** this bundle comes from. Without an
answer, `waehle_agent(sitzung.agent)` cannot be implemented: the function
is named, the data source is missing.

**Simple implementation.**

One file per agent, body = system text, frontmatter = the rest:

```markdown
---
description: Reviews changes without editing them.
mode: primary            # primary | subagent | all
model: anthropic/claude-sonnet-4-5
steps: 16
hidden: false
permissions:
  - { action: "*",      resource: "*", effect: allow }
  - { action: "edit",   resource: "*", effect: ask }
  - { action: "shell",  resource: "*", effect: ask }
---
You are a reviewer. Read the change before judging.
Every finding cites `path:line` and the failure case.
```

Sources and precedence:

| Level | Location | Precedence |
|---|---|---|
| built-in | `build` (code) | 1 (lowest) |
| global | `~/.config/opencode/agent/<id>.md` | 2 |
| project | `<project>/agent/<id>.md` | 3 (wins) |

`<project>/agents/<id>.md` is an alias; the baseline environment needs no
more sources. The filename **is** the ID. If `description` is missing, the
agent is loaded but not listed (same rule as for skills, `SK-11`).

Build in pseudocode:

```text
load_agents(project, global):
  catalog = { "build": builtin_build() }
  for source in [global, project]:          # global first, project overrides
    for file in source/agent/*.md:
      text, frontmatter = read_markdown(file)
      catalog[filename] = build_agent(frontmatter, text)
  return catalog

build_agent(frontmatter, text):
  base = { mode: "primary", hidden: false, steps: none,
           permissions: default_permissions(), model: none }
  return base + frontmatter + { system: text }
```

Default is `build`. A session payload `agent: "reviewer"` selects the
agent; an unknown name is `400 InvalidRequestError` with field `agent`
(no silent fallback to `build`). For this the error list of
`POST /api/session` is extended by `InvalidRequestError`; that is a
deliberate small API change.

**Subagents** are loaded, but in the baseline environment **not** selected:
there is no `task` tool. A `mode: subagent` agent does not appear in
`GET /api/agent`; that is more honest than a dead button.

**Acceptance test.**

1. Without files, `GET /api/agent` lists exactly `build`.
2. A project file `agent/reviewer.md` appears in the list.
3. A session with `agent: "reviewer"` uses its system text and rights.
4. Same ID global and in project: project wins.
5. Broken frontmatter: file is skipped, startup does not fail;
   the warning names the path.
6. `agent: "nonexistent"` → `400` with field `agent`, no run with `build`.

**If undecided.** Exactly today's state: `build` fixed in code, no files.
That is the **baseline environment**, but not a replaceable installation.

**Evidence.** [`betrieb/README.md`](./spec/betrieb/README.md) §3,
[`qualitaet/03-implementability.md`](./spec/qualitaet/03-implementability.md)
§4 L-1, [`session-engine/01-types.md`](./spec/session-engine/01-types.md) §7.

---

### L-2 · Configuration schema

**Today.** There is **no** schema for configuration files. Persistence
rightly says configuration does not belong in the database
([`persistence/README.md`](./spec/persistence/README.md) §7), but nobody
says what the file looks like.

**What is open.** File format, fields, precedence, error behaviour on load.

**What you need to understand.** A configuration file answers three
questions: **What applies by default?** (model, agent, rights), **what is
allowed?** (rights, tools, skills) and **where is it?** (instructions,
shell). Precedence decides which answer wins when two files say the same
thing. Without it the same machine is configured differently in two places
– and nobody knows why.

**Simple implementation.**

Proposal: one file, strict JSON (no comments), two locations:

| Location | File | Precedence |
|---|---|---|
| global | `~/.config/opencode/settings.json` | 1 |
| project | `<project>/settings.json` | 2 |
| session | payload of `POST /api/session` | 3 |

Only the fields of the baseline environment:

```json
{
  "default_agent": "build",
  "model": "anthropic/claude-sonnet-4-5",
  "permissions": [
    { "action": "*",                  "resource": "*", "effect": "allow" },
    { "action": "shell",              "resource": "*", "effect": "ask" },
    { "action": "write",              "resource": "*", "effect": "ask" },
    { "action": "edit",               "resource": "*", "effect": "ask" },
    { "action": "external_directory", "resource": "*", "effect": "ask" }
  ],
  "skills": { "commit": true, "review": false },
  "instructions": ["AGENTS.md"],
  "shell": "/bin/bash",
  "agents": {
    "build": {}
  }
}
```

The `build` entry has no `steps` value, so no step limit applies. The turn
policy still bounds tool calls, tokens and wall time.

Rules:

* **Scalars** (`model`, `shell`, `default_agent`): the later level wins.
* **Rights**: lists are **concatenated**, not replaced; the last
  matching rule wins (as in [`permissions/`](./spec/permissions/README.md)).
* **`agents`**: objects are overridden field by field; the system text still
  comes from the file (L-1), not from JSON.
* **`skills`**: `false` removes the skill from the list; `true` changes
  nothing. (Proposal; the reference uses a path list here. The baseline
  environment may combine both: paths plus `disabled`.)
* **Unknown keys** are ignored with a warning (forward compatibility).
* **Wrong type** or invalid JSON: the location does **not** load, the error
  names file and JSON path (`400 InvalidRequestError`). A typo must not
  silently mean `allow`.

Loaded **once** when entering the location. Runtime changes only by
restarting the location; `POST /api/location/reload` is not among the 18
endpoints of the baseline environment. No file watcher in the simple
variant.

**Acceptance test.**

1. Without a file everything runs with built-in defaults.
2. Global `model: A`, project `model: B` → B wins.
3. Rights from both files are both effective; the later wins.
4. Broken JSON → `400`, the message contains file and position.
5. `"skills": { "commit": false }` → `commit` missing from skill list.
6. A restart of the location re-reads the file.

**If undecided.** Configuration only via session payload; no files. That is
today's baseline environment – but no takeover.

**Evidence.** [`betrieb/03-credentials.md`](./spec/betrieb/03-credentials.md) §6.4
(model without default), [`persistence/README.md`](./spec/persistence/README.md) §7,
[`permissions/02-evaluation.md`](./spec/permissions/02-evaluation.md) §2.

---

### L-6 · Commands

**Today.** The prompt texts `CMD-INIT` and `CMD-REVIEW` stand in the catalog
([`session-engine/13-internal-prompts.md`](./spec/session-engine/13-internal-prompts.md)),
the mechanics are missing: invocation, arguments, subagent, visibility.

**What you need to understand.** A command is **not** a new execution path.
It is a **text macro**: name plus arguments become a prompt, and this prompt
goes through the same inbox as any user input. Thus idempotency, order,
rights and events remain unchanged. Whoever builds a second path builds two
truths.

**Simple implementation.**

File `<project>/command/<id>.md`:

```markdown
---
description: Reviews the current change.
agent: build
subagent: false
---
Please review $ARGUMENTS.
Pay special attention to $1; the rest is context.
```

Flow:

1. Client calls `POST /api/session/:id/command` with `{ name, arguments }`.
   The endpoint exists in the protocol, but belongs **not** to the 18
   endpoints of the baseline environment; L-6 adds it to the set.
2. Unknown name → `404`.
3. Replace `$1…$9`; the **last** placeholder gets the rest;
   `$ARGUMENTS` gets the whole string.
4. Optionally switch `agent`/`model` – via the same path as a session
   switch, not via a special route.
5. Accept the finished text as a **normal input** (`Session.prompt`).
6. `subagent: true` in the baseline environment: `400` with a clear
   message (the error class is added). Subagents need the `task` tool,
   which the baseline environment does not have.

**Not** in the simple variant: shell interpolation (`!command`), file
replacement (`@file`), dynamic arguments. The model can read files itself;
everything else is a second attack surface.

**Acceptance test.**

1. `command/review.md` with `$ARGUMENTS`; invocation produces a user message
   with substituted text.
2. The message appears via `session.inbox.enqueued`, **before** the model
   answers.
3. Unknown command → `404`.
4. `subagent: true` → `400`.
5. A command without placeholders and with arguments appends the arguments
   (`packages/core/src/config/plugin/command.ts:209-213`).

**If undecided.** Clients may replace macros locally; the engine knows no
commands. That is cheaper, but makes every client different.

**Evidence.** [`session-engine/13-internal-prompts.md`](./spec/session-engine/13-internal-prompts.md)
§7, [`betrieb/01-http-streaming.md`](./spec/betrieb/01-http-streaming.md) §6
(`session.command` is not in the base set), `PRM-C` in §4.

---

### L-7 · Catalog update

**Today.** `ModelInfo` is complete
([`provider/04-catalog-response.md`](./spec/provider/04-catalog-response.md) §1),
but source and timing are open: shipped snapshot, query at startup or
background run?

**What you need to understand.** The model catalog is the list of models
the installation knows (context window, prices, capabilities). If it
changes **during** a session, a model can "disappear". The question is not
"current or not", but **when** a change takes effect.

**Simple implementation.**

1. A **snapshot** `models.json` sits next to the program. It is read
   synchronously at startup; **no network** is needed.
2. Update only **explicitly** (for example startup with
   `--refresh-catalog`), not in the background. A 10-second timeout,
   two retries, write to a temp file, then atomic rename. Error: keep
   old catalog, log a warning.
3. A step resolves its model **once** and keeps it to the end.
   Catalog changes apply from the **next** step.
4. If a model disappears, resolving the next step fails with a typed
   `model.unavailable`; **no** silent switch to another model.
5. The baseline environment has **no** update: only the snapshot counts.
   That is reproducible and suffices for operation.

**Acceptance test.**

1. Startup without network works; `GET /api/model` returns the snapshot.
2. Remove a model from the snapshot: a **running** session stays on the old
   model, the next resolution fails typed.
3. Update with a defective response: old catalog remains, warning in log.
4. Two catalog states: no model switch mid-step.

**If undecided.** Only the shipped snapshot. That is the simplest and
safest variant; currency is an operations matter.

**Evidence.** [`provider/04-catalog-response.md`](./spec/provider/04-catalog-response.md) §1,
[`session-engine/04-algorithms.md`](./spec/session-engine/04-algorithms.md) §E.

---

## 3. Behavioural decisions

### O-01 · How many retries?

**What is open.** The specification says **4**, the reference code allows
**10** ([`session-engine/08-porting.md`](./spec/session-engine/08-porting.md)
§4 O-01).

**What you need to understand.** "4" can mean "four attempts total" or
"four retries after the first". The difference is one more model call and
thus money and latency. Without a definition everyone tests something
different.

**Simple implementation.**

* `max_attempts = 4` **total** (first attempt + 3 retries). The older
  wording "4 retries" is thereby clarified; whoever means "4 retries **after**
  the first" must say `max_attempts = 5`.
* Configurable `retry.max_attempts`, hard-capped at **1–10**.
* Only retryable classes (`V-40`); provider `retryAfter` wins.
* Exponential backoff with jitter, cap **10 s** per gap, total deadline
  from the step budget.
* A non-retryable error is **never** retried, even with
  `max_attempts = 10`.

**Acceptance test.** Three failures then success → fourth attempt. Four
failures → final error. `401` → exactly one attempt.

**If undecided.** Follow the reference code (10) and document the deviation,
as done in [`session-engine/Java-Impl.md`](./spec/session-engine/Java-Impl.md)
§2. For the specification **4** is the more conservative choice.

**Evidence.** `specs/v2/session.md:84` ↔ `packages/core/src/model-retry.ts:25-28`,
[`provider/05-invariants.md`](./spec/provider/05-invariants.md) `V-40…V-47`.

---

### O-07 · Allow `external_directory` by default?

**What is open.** The reference code allows access outside the project by
default (`packages/schema/src/agent.ts:46-51`); the Java port proposes
**`ask`** ([`session-engine/Java-Impl.md`](./spec/session-engine/Java-Impl.md)
§2).

**What you need to understand.** "Outside" means: a tool wants to read
`/etc/passwd` or `~/other-file`. With `allow` that happens without asking.
With `ask` the user sees the path and decides. That is not a format
question but a security decision.

**Simple implementation.**

* Default rule **after** the general permission:
  `{ action: "external_directory", resource: "*", effect: "ask" }`.
* Check **before** `read`/`edit`/`shell`, resource `<directory>/*`
  ([`permissions/03-effect-matrix.md`](./spec/permissions/03-effect-matrix.md) §2).
* `always` stores the directory resource; afterwards the directory is
  usable without asking.
* A `deny` on `external_directory` blocks even when `read` would be allowed
  (order: boundary before action).

**Acceptance test.** `read /etc/passwd` → `permission.asked`; `always` →
second access without question; `reject` → tool error, no silent success.

**If undecided.** `allow` as in the reference. Then the decision must stand
as a conscious risk in the operations document.

**Evidence.** [`permissions/03-effect-matrix.md`](./spec/permissions/03-effect-matrix.md) §2,
[`tools/02-filesystem.md`](./spec/tools/02-filesystem.md) §2,
[`session-engine/08-porting.md`](./spec/session-engine/08-porting.md) §4 O-07.

---

### L-A · Location semantics

**Closed today.** The baseline environment has **one** process and **one**
location. The location is determined per request: `?location[directory]=`,
then `x-opencode-directory`, then `process.cwd()`
([`betrieb/01-http-streaming.md`](./spec/betrieb/01-http-streaming.md) §9).

**What is open.** **Multi-location life**: when is a location entered and
left? Which state belongs to the location? How does a move work? What
happens when two locations lie in the same process?

**What you need to understand.** A location is a **directory context**:
configuration, skills, tool registrations, provider resolution and
filesystem belong to it. A session belongs to **exactly one** location. The
danger is global state: then a session in location B sees the tools of
location A – a data leak, not a convenience bug.

**Simple implementation** (only if multi-location is needed):

```text
Location = { id, directory, projectID }

locations: Map<directory, LocationContext>

context(directory):
  if not present:
    check directory exists, else 404
    context = { config: load_config(directory),
                skills: load_skills(directory),
                tools: build_tools(directory),
                provider: resolved for directory }
    locations[directory] = context
  return locations[directory]

request(directory, sessionID):
  ctx = context(directory)
  session = sessionStore.get(sessionID)
  if session.locationID != ctx.id: 404 SessionNotFound
  work with ctx
```

Rules:

* Every session stores `location_id`; the assignment runs **via the
  session**, not via a global context.
* A location is created **on demand**; unloaded after 60 minutes of idle
  (`location_idle_timeout`), never during a running execution.
* A **move** is a control step at a delivery boundary: pause session,
  transfer target state, write `session.moved`,
  continue. No tool from location A runs in location B.
* The baseline environment may **reject** a second location (`400`) as long
  as multi-location is not built. That is more honest than a half context.

**Acceptance test.** Two locations in one process; session A is not readable
via location B (`404`); a tool from A does not run in B; a move at the
delivery boundary preserves the text and writes exactly one event.

**If undecided.** One location per process. That is the baseline
environment.

**Evidence.** [`session-engine/11-java-portability.md`](./spec/session-engine/11-java-portability.md)
§4 L-A, [`betrieb/01-http-streaming.md`](./spec/betrieb/01-http-streaming.md) §9,
[`session-engine/Java-Impl.md`](./spec/session-engine/Java-Impl.md) §3.

---

### L-B · Tool parallelism quota

**What is open.** `G-05` says "parallel" but names no limit. Without a
number the model can start 50 file operations at once.

**Simple implementation.** At most **8** concurrently per step, FIFO over a
semaphore; the rest waits. Configurable per agent
(`tools.max_parallel`, default 8, hard 1–32). Results are stored in
call order, not completion order.

**Acceptance test.** 10 calls, 8 run, 2 wait; never more than 8 concurrent
side effects; result list in call order.

**If undecided.** Execute sequentially. Slower, but safe.

**Evidence.** [`session-engine/04-algorithms.md`](./spec/session-engine/04-algorithms.md)
§G `G-05`, [`provider/02-event-stream.md`](./spec/provider/02-event-stream.md) §5.

---

### L-C · Ordered publication of parallel calls

**What is open.** Results run in parallel, publication should stay in call
order. The failure case is not regulated.

**Simple implementation.** A sequencer holds a queue of expected call IDs in
provider order. Finished results are buffered and released only when all
predecessors are released. If a call fails, a terminal error result is
published **at its position**; the successor moves up. **No gap** arises
in the event sequence, because the error is also an event.

**Acceptance test.** Call 1 fast, call 2 slow → publication 1, then 2.
Call 2 fails → error result at position 2, `seq` stays gapless.

**If undecided.** Execute sequentially; then order is trivial.

**Evidence.** [`session-engine/04-algorithms.md`](./spec/session-engine/04-algorithms.md)
§G, [`session-engine/07-invariants.md`](./spec/session-engine/07-invariants.md) `I-05-08`.

---

### L-D · Retention of waiting inbox items

**What is open.** An inbox without TTL grows; with TTL the question arises
whether a prompt expires while the user is typing.

**Simple implementation.** **No TTL.** A waiting input is a user intention
and does not expire. Delivered lines are deleted, cancelled ones too. There
is no delivery history; the visible message is the proof. Thus
`session_inbox` is always "only unfinished work".

**Acceptance test.** An entry waits 24 hours and is still delivered. After
delivery no inbox row exists. Cancelling deletes the row.

**If undecided.** No TTL – the simpler and safer variant.

**Evidence.** [`session-engine/01-types.md`](./spec/session-engine/01-types.md) §5,
[`session-engine/04-algorithms.md`](./spec/session-engine/04-algorithms.md) §A.

---

### P-1 · Where are credentials?

**Today.** The reference stores them in a table `credential`
([`betrieb/03-credentials.md`](./spec/betrieb/03-credentials.md) §3). The
persistence specification has **not** this table; it only says configuration
does not belong in the database
([`persistence/README.md`](./spec/persistence/README.md) §7).

**What you need to understand.** A credential is a **secret**, not session
state. It is installation-wide, survives restarts and must appear in no
event and no prompt. The location is an operations decision; the
**resolution order** is the contract.

**Simple implementation.** A file `<data-directory>/credentials.json` with
mode `0600`, written atomically, one line per credential:

```json
{
  "version": 1,
  "entries": [
    {
      "id": "cred_1",
      "integrationID": "anthropic",
      "label": "default",
      "active": true,
      "value": { "type": "key", "key": "…" }
    }
  ]
}
```

* Resolution: active stored → further stored (newest first) →
  `env` → none.
* Exactly **one** active connection per integration; activating deactivates
  the others in the same transaction (file write).
* `GET /api/credential` returns values **only** over loopback and Basic-Auth
  (or masked, see `CR-C`).
* No credential in `session.*` events, prompts, error messages or exports.

**If the database is to be used instead:** a table `credential`
(`id`, `integration_id`, `label`, `value` JSON, `active`, `time_created`)
without `project_id`; it is **installation-wide**, not location-scoped.

**Acceptance test.** Restart keeps the credential; file mode is `0600`; a
session export contains no key; two credentials of the same integration
have exactly one active row.

**If undecided.** Only `env` (variant A of the baseline environment). That
suffices for the first run, but not for rotation.

**Evidence.** [`betrieb/03-credentials.md`](./spec/betrieb/03-credentials.md) §3,
§5, §6; [`persistence/02-data-ownership.md`](./spec/persistence/02-data-ownership.md).

---

### Further O-xx and operations questions

These points stand partly in
[`session-engine/08-porting.md`](./spec/session-engine/08-porting.md) §4,
partly in [`session-engine/Java-Impl.md`](./spec/session-engine/Java-Impl.md) §16,
but are **not** part of the baseline environment. They are registered here
only so no question is lost.

| # | Question | Simple implementation | Recommendation |
|---|---|---|---|
| **O-02** | May an org-wide `tool.use` permission grant access? | **No.** It only lifts a broader denial; it never creates a permission that would not otherwise exist. | leave as is, document |
| **O-03** | Where do org-wide rules stand? | Top-level `policies`, not under `experimental`; belongs in the L-2 schema. | top-level |
| **O-04** | When does the file-content trace arrive? | Not in the baseline environment. If needed: `sha256` and size on the tool result, **no** file content. | later |
| **O-05** | What is the business meaning of statistics and cost metrics? | Treat as **derived view**, not durable truth; do not base decisions on them. | document |
| **O-06** | Foreign harness integrations? | Outside the engine and outside the baseline environment; do not specify. | exclude |
| **P-2** | Retention of `session_events` (PII)? | Default: unlimited for correctness; deletion only with product decision, export/redaction first. | open, non-blocking |
| **P-3** | Content filter: keep partial content or discard? | Keep partial content, mark as `truncated`, finish reason `content_filter`, **no** retry. The user sees what was streamed. | keep |
| **O-08** | Duplicate enqueue IDs after delivery? | **Resolved:** detect via the projected message, without enqueue history. | no action |

**Provider selection and fallback** are decided by the baseline environment:
exactly one provider, no fallback; an unavailable model is a typed error
([`betrieb/README.md`](./spec/betrieb/README.md) §3).

---

## 4. Prompt and product decisions

### PRM-A · Provider templates

**Question.** Do the provider-specific system-text templates stay in the port
or are they dropped?

**Simple implementation.** **One optional file** `provider/<id>.md`; if
missing, `SYS-BASE` applies. The text is inserted **before** the base
block, at most two blocks
([`session-engine/05-provider.md`](./spec/session-engine/05-provider.md)
`P-02`). No code per provider, only data.

**Recommendation.** Keep, but as data. Without them the model behaves
differently on GPT/Claude/Kimi; with them the adjustment is one file.

**Acceptance test.** Provider without file → base block; with file →
template + base; empty text → block dropped.

---

### PRM-B · `AGT-SUMMARY`

**Question.** Is the agent for manual summarisation needed?

**Simple implementation.** **Drop.** The session already has a title agent;
a summary is a client function ("summarise the history") and needs no own
agent. Less catalog, less rights, less prompt.

**Recommendation.** Drop as long as no product requires it.

**Acceptance test.** No `summary` agent in `GET /api/agent`; summarisation
is a normal prompt.

---

### PRM-C · Commands: engine or client?

**Question.** Do commands belong in the engine or the client?

**Simple implementation.** **Engine.** The endpoint exists, the inbox lies
there, and a command is only a text macro (L-6). The client sends name and
arguments; it knows neither template nor placeholders.

**Recommendation.** Engine; the client stays dumb
([`betrieb/01-http-streaming.md`](./spec/betrieb/01-http-streaming.md) §1 point 4).

**Acceptance test.** The same command works in TUI and Web without any
client implementing substitution.

---

## 5. Detail decisions in closed areas

These points block nothing. The column "simple implementation" is the
smallest variant that fits the rest of the specification.

| # | Question | Simple implementation | Recommendation |
|---|---|---|---|
| **HS-A** | `since` parameter for messages? | `GET /message?since=<id>` returns only newer; cursor remains for paging | later; snapshot suffices |
| **HS-B** | `GET /api/config` also as `{data}`? | **do not** change; the bare array remains the documented exception | leave as is |
| **HS-C** | `retry:` hint in the stream? | one line `retry: 3000` after `server.connected`; client may ignore it | optional |
| **HS-D** | `blockID` on deltas? | `blockID = assistantMessageID + ordinal`; client assigns parallel blocks with it | later |
| **SK-A** | Limits (3 skills, 4 KiB) hard? | **soft** warn, hard only for scan depth 6 and 10 files | leave as is |
| **SK-B** | URL catalog versioned? | Manifest `{url, sha256}`; cache content by hash, never execute unchecked | only if needed |
| **SK-C** | `autoinvoke` more than true/false? | **no**; "only on hit" is model behaviour, not a flag | leave as is |
| **SK-D** | Redact skill texts on session transfer? | **yes**, remove by default; baseline environment needs no transfer | on transfer |
| **CR-A** | Encryption at rest? | File `0600` + OS keychain; no own crypto | keychain |
| **CR-B** | Credentials per project? | `project_id` **nullable**; resolution: project first, then global | only with tenants |
| **CR-C** | Mask values in `GET /api/credential`? | List returns `{id, label, active, hasValue, last4}`; value only via `GET …/value` with audit | yes |
| **CR-D** | Report expiring OAuth credentials? | `expires` + `status` in `GET /api/integration`; client warns; refresh 5 min before | yes |
| **CR-E** | MCP credentials own layer? | **same** store, integration `mcp:<server>`; never to the model | leave as is |

**Why this table is so short:** Every row is a decision that can be taken
in one session. Whoever takes it writes it into the named spec and adds a
test – nothing more.

---

## 6. Architecture and package decisions

These points change no contract. The proposals are already the simple
implementation; they are adopted unless there is a concrete reason against.

| # | Question | Proposal | Effect |
|---|---|---|---|
| **M-A** | `Journal` as port? | **port** | session testable without DB |
| **M-B** | `Conversation` own fake? | **own fake**, contract test against SQL | fast and faithful |
| **M-C** | Projections in `wiring` or `adapter.sql`? | **`adapter.sql`**, list in constructor | one place |
| **M-D** | `shellscan` own module? | **own module** | scanner swappable |
| **M-E** | `execution` parallel? | **later**, first correct | no contract change |
| **J-A** | Share `engine.model`? | **one package**, up to 25 types | fewer imports |
| **J-B** | `adapter` inside `engine`? | **inside** | root stays together |
| **J-C** | Package per tool or family? | **per family** (`tool.fs`) | compact |
| **J-D** | `http` and `wiring` separate? | **separate** | sharper rule |
| **J-E** | Ports `sealed`? | **no** | adapters **and** fakes |

**Rationale for J-E:** `sealed` would forbid exactly the second
implementation that makes the port testable. A port is a boundary, not a
closed class.

**Evidence.** [`module-plan.md`](./spec/module-plan.md) §12,
[`java-packages.md`](./spec/java-packages.md) §12.

---

## 7. Findings when porting (example Java port)

The following labels `L-1…L-10` mean **defects in the example port**, not
gaps in the specification. For distinction:

| # | Finding | Simple fix |
|---|---|---|
| **L-1** | Input parsing swallows errors | `parseArguments` returns `Result`, not empty `Map.of()` |
| **L-2** | Rejection becomes visible as text | own channel `ToolResult.rejected`, end iteration |
| **L-3** | Silent data loss in persistence | propagate errors, do not swallow |
| **L-4** | Snapshot and events not atomic | one transaction for both writes |
| **L-5** | Global session lock | lock per session ID |
| **L-6** | Retryability by text pattern | typed errors instead of `getMessage().contains(...)` |
| **L-7** | Compaction discards wording | keep raw events, compaction as view |
| **L-8** | No restart after crash | claim column + resume on startup |
| **L-9** | Tool snapshot not frozen | freeze specifications and executors together |
| **L-10** | Partial content lost on `content-filter` | use streaming provider, secure partial content |

These findings are **not** part of the baseline environment; they appear
only when porting an example. Whoever builds the port works through the
table before measuring.

---

## 8. What "open" means for tests

The 46 rules of the baseline environment each have a row in
[`qualitaet/01-tests.md`](./spec/qualitaet/01-tests.md); all stand at
`open`. That does **not** mean the rule is undecided. It means:

* The test is **named**, but not yet written or not yet green.
* A rule **without** a row would be "not implemented", not "untested".
* After the first green run `open` becomes `green`; a red run stays
  red and is repaired, not repeated.

**Additionally open:** `P-03-01`
(`SavedApprovalBeatsDenyTest`) from the permissions specification. Thus
there are **47** open proofs.

---

## 9. Recommended order

| Step | What | Effort | Why first |
|---|---|---|---|
| 1 | Decide `O-01`, `O-07`, `J-E`, `HS-B`, `SK-C` | S | cost nothing, change tests |
| 2 | `L-2` configuration schema | M | prerequisite for L-1 and takeover |
| 3 | `L-1` agent catalog | M | builds on L-2 |
| 4 | `L-6` commands + `PRM-C` | S–M | small lever, big benefit |
| 5 | `L-7` snapshot | S | reproducibility |
| 6 | `L-A`, `L-B`, `L-C`, `L-D` | M | only if multi-location/parallelism needed |
| 7 | `PRM-A`, `PRM-B`, `SK-B`, `HS-A`, `HS-D` | S | convenience |
| 8 | `CR-A`, `CR-B`, `CR-C` | S–M | before production |
| 9 | Java findings L-1…L-10 | M | when porting |

**The baseline environment runs without steps 1–9.** It is just not
"replaceable" and not "production-safe". That is the difference marked by
column **A** in §1.

---

## 10. Decision log

When you decide a point, enter it here. A decision without date and test is
an opinion.

```text
### <ID> · <Title>
- Decided: <A/B/…>
- Date: YYYY-MM-DD
- Rationale: <two sentences>
- Test: <one sentence, which rule must go green>
- Follow-up changes: <files>
```

| # | Decision (short) | Date | Test |
|---|---|---|---|
| O-01 | 4 attempts total, configurable (1–10), hard-capped | 2026-10-22 | 3 failures + success = 4th attempt; 4 failures = final error; 401 = 1 attempt |
| O-07 | Global working directory (default cwd) + allow-list with read/write flags | 2026-10-22 | Access only to working dir + listed folders; read/write flag per entry |
| L-1 | File-based: agent/<id>.md (project) + ~/.config/opencode/agent/<id>.md (global) + built-in build; project overrides global | 2026-10-22 | Without files only build; project file appears; unknown name → 400 |
| L-2 | File-based, strict JSON: settings.json global+project, strict validation, project overrides global | 2026-10-22 | Without file defaults; broken JSON → 400 with file+position |
| L-6 | Text macro via inbox: command/<id>.md with $ARGUMENTS; finished text as normal input | 2026-10-22 | Invocation produces user message with substituted text; unknown → 404; subagent → 400 |
| L-7 | Snapshot models.json at startup + explicit update (--refresh-catalog) | 2026-10-22 | Startup without network OK; running session stays on old model |
| L-A | One location per process (baseline); multi-location later | 2026-10-22 | One location; second location → 400 until multi-location built |
| L-B | Max. 8 concurrent tools per step (FIFO semaphore, 1–32) | 2026-10-22 | 10 calls → 8 run, 2 wait; results in call order |
| L-C | Sequencer with buffer: release in call order; error at position, no gap | 2026-10-22 | Call 1 fast, 2 slow → 1 then 2; error → seq gapless |
| L-D | No TTL; waiting input does not expire; delivered/cancelled = deleted | 2026-10-22 | Entry waits 24 h and is delivered; after delivery no inbox row |
| P-1 | Only env (baseline); no credentials file/DB | 2026-10-22 | Restart keeps credential via env; no key in exports |
| PRM-A | Drop: no provider templates; base block only | 2026-10-22 | No provider/<id>.md; GET /api/agent returns base |
| PRM-B | Drop: no AGT-SUMMARY agent | 2026-10-22 | No summary agent in GET /api/agent |
| PRM-C | Engine: endpoint + inbox + text macro in engine; client dumb | 2026-10-22 | Same command in TUI and Web without client substitution |
| O-02 | No: org. tool.use permission only lifts denial, never grants | 2026-10-22 | Org permission alone → no access |
| O-03 | Top-level policies (not under experimental) | 2026-10-22 | policies field in L-2 schema |
| O-04 | Not in baseline; later sha256+size, no content | 2026-10-22 | No file-content trace in baseline |
| O-05 | Derived view; no decisions based on metrics | 2026-10-22 | Metrics documented as non-authoritative |
| O-06 | Exclude: no spec of foreign harness integrations | 2026-10-22 | No chapter on foreign harness |
| P-2 | Unlimited for correctness; deletion only with product decision | 2026-10-22 | session_events unlimited; export/redaction before deletion |
| P-3 | Keep partial content, mark truncated, no retry | 2026-10-22 | User sees streamed content on content_filter |
| HS-A | Later: since-parameter not in baseline | 2026-10-22 | Snapshot suffices now |
| HS-B | Do not change: GET /api/config stays bare array | 2026-10-22 | Documented exception remains |
| HS-C | Optional: retry: hint in stream, client may ignore | 2026-10-22 | Line retry: 3000 after server.connected |
| HS-D | Later: blockID on deltas | 2026-10-22 | blockID not in baseline |
| SK-A | Soft warn; hard only scan depth 6 and 10 files | 2026-10-22 | Warning instead of error on overflow |
| SK-B | Only if needed: manifest {url, sha256}, cache by hash | 2026-10-22 | No URL catalog versioning in baseline |
| SK-C | No: autoinvoke stays true/false | 2026-10-22 | No multi-level autoinvoke |
| SK-D | Yes: skill texts removed by default on transfer | 2026-10-22 | Transfer redacts skill texts |
| CR-A | File 0600 + OS keychain; no own crypto | 2026-10-22 | File mode 0600; keychain used |
| CR-B | Only with tenants: project_id nullable, resolution project→global | 2026-10-22 | Credentials global in baseline |
| CR-C | Yes: list masked (hasValue+last4); value only via /value with audit | 2026-10-22 | GET /api/credential returns no plaintext values |
| CR-D | Yes: expires+status in GET /api/integration; refresh 5 min before | 2026-10-22 | Expiry warning on OAuth credentials |
| CR-E | Same store: integration mcp:<server>, never to model | 2026-10-22 | MCP credentials in same store |
| M-A | Port: Journal as port | 2026-10-22 | Session testable without DB |
| M-B | Own fake: Conversation with contract test against SQL | 2026-10-22 | Fake + contract test |
| M-C | adapter.sql: projections there, list in constructor | 2026-10-22 | One place for projections |
| M-D | Own module: shellscan swappable | 2026-10-22 | Scanner swappable |
| M-E | Later: execution not parallel in baseline | 2026-10-22 | Sequential in baseline |
| J-A | One package: engine.model up to 25 types | 2026-10-22 | Fewer imports |
| J-B | Inside: adapter in engine | 2026-10-22 | Root stays together |
| J-C | Per family: tool.fs, tool.shell, tool.mcp | 2026-10-22 | Compact packages |
| J-D | Separate: http and wiring separate | 2026-10-22 | Sharper rule |
| J-E | No: ports not sealed (adapter + fakes testable) | 2026-10-22 | Second implementation allowed |

Note: O-08 was already resolved (detect via projected message, without
enqueue history) – no action.

**Rule:** A decision is updated **in the responsible spec**, not only here.
This file is the register, the spec is the contract.