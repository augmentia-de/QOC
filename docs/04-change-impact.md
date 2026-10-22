# 04 – Change Impact, Invariants & Guardrails

> **Workflow 4:** Change-impact analysis
> `[Hotspots + Invariants] ➔ [LLM impact analysis] ➔ [Impact matrix + test plan]`
>
> **Note:** This workflow was truncated in the original spec. From the numbering
> and the goal ("understand everything **for changes**") it was interpreted as
> *"what happens when I change X?"*. Adjust if needed.
>
> **As of:** 2026-10-21, Repo `dev` @ `3108505b2f`
> **Method:** Fan-in from file-import counting in `packages/core/src` and from
> the Codebase-Memory graph (44,297 nodes). Invariants from `specs/v2/*`, the
> `AGENTS.md` files and code comments.

---

## 1. Hotspots – where a change takes the most with it

### 1.1 Core hubs (fan-in, file level)

| Fan-in | File | What it does | Who uses it | What breaks on a change |
|---:|---|---|---|---|
| 64 | `core/src/bus.ts` | Event bus, publish/subscribe, transactional projection | almost all domains | **every** event production and projection |
| 54 | `core/src/database/migration.ts` | Schema migrations | `database.ts` | migration run, all versions |
| 49 | `core/src/location.ts` | Location context | Tool, Permission, Session, Shell | tool calls, path authorisation |
| 43 | `core/src/schema.ts` | Schema helpers (AbsolutePath …) | broadly | path validation in all domains |
| 36 | `core/src/session/schema.ts` | Session ID/schema | Session, DB, Events | all session events |
| 34 | `core/src/config.ts` | Config service | Config plugins, Provider, Agent | all config sources |
| 32 | `core/src/provider.ts` | Provider registry | Session, Model, Policy | provider resolution, models |
| 31 | `core/src/permission.ts` | Permission engine | Tool, Skill, Plugin, API | **all** permission checks |
| 28 | `core/src/database/database.ts` | DB service + layer | all persisting areas | every transaction |
| 27 | `core/src/model.ts` | Model registry | Provider, model resolver | model selection, variants |
| 27 | `core/src/session/message.ts` | Message types | Projector, History, UI | all messages |
| 25 | `core/src/state.ts` | Replayable transform state | Tool, Agent, Config, Catalog | plugin/config registration |
| 22 | `core/src/integration.ts` | Integrations | Plugin, MCP | external connections |
| 21 | `core/src/credential.ts` | Credentials | Provider, Auth | sign-in, model access |
| 20 | `core/src/environment/index.ts` | Process environment | Shell, MCP, Tool | command execution |

### 1.2 Fan-out hubs (importers)

| Local imports | File | Risk |
|---:|---|---|
| 96 | `core/src/plugin/internal.ts` | every new plugin type touches this file |
| 54 | `core/src/instance.ts` | bootstrap knows almost everything |
| 51 | `core/src/database/migration.gen.ts` | generated – **do not** hand-edit |
| 34 | `core/src/session.ts` | session facade |
| 33 | `core/src/plugin/provider.ts` | provider plugins |
| 30 | `core/src/plugin/host.ts` | plugin host contract |
| 27 | `core/src/session/runner/llm.ts` | model stream |
| 23 | `core/src/session/context.ts` | request context |
| 19 | `core/src/session/compaction.ts` | compaction |
| 17 | `core/src/session/runner/step.ts` | step logic |
| 15 | `core/src/session/projector.ts` | all event projections |

### 1.3 Largest files (change friction)

| Lines | File | Area |
|---:|---|---|
| 2,410 | `core/src/shell/scan.ts` | shell scanner |
| 1,637 | `core/src/github-copilot/responses/openai-responses-language-model.ts` | provider special case |
| 1,424 | `core/src/git.ts` | Git logic |
| 1,156 | `core/src/database/v1-migration.bun.ts` | V1→V2 migration |
| 1,069 | `core/src/aisdk.ts` | AI SDK |
| 931 | `core/src/session/compaction.ts` | compaction |
| 915 | `core/src/bus.ts` | event bus |
| 887 | `core/src/integration.ts` | integrations |
| 862 | `core/src/config/normalize.ts` | config normalisation |
| 847 | `core/src/mcp/index.ts` | MCP |
| 844 | `core/src/session/projector.ts` | projections |
| 812 | `core/src/plugin/provider/opencode.ts` | console/provider/SSO |
| 753 | `core/src/plugin/vcs/git.ts` | VCS plugin |
| 704 | `core/src/plugin/host.ts` | plugin host |
| 691 | `core/src/persistent-pty/daemon.ts` | PTY daemon |
| 691 | `core/src/variant.ts` | model variants |
| 672 | `core/src/session/runner/publish-llm-event.ts` | LLM event publication |

---

## 2. Invariants as testable rules

The binding rules from `specs/v2/tools.md` (section "Laws"), `specs/v2/session.md`
and the `AGENTS.md` files, formulated as **IF/THEN**.

### 2.1 Tool laws (`specs/v2/tools.md:167-177`)

| ID | Rule |
|---|---|
| L-T-01 | IF `Tool.make(config)` is created, THEN only `config.execute` can be executed |
| L-T-02 | IF a tool is observed, THEN it sees **decoded** input; IF Code Mode is observed, THEN it sees **validated, encoded** output |
| L-T-03 | IF a call succeeds, THEN there is **exactly one** stored model representation |
| L-T-04 | IF a call fails, THEN there is **exactly one** error plus **at most one** final partial snapshot |
| L-T-05 | IF response metadata is missing, THEN the metadata is **absent** – never a copied output structure |
| L-T-06 | IF a tool call runs, THEN it uses exactly the session, agent, message and call IDs of the runner |
| L-T-07 | IF a registration is closed, THEN **only this** registration disappears and the previously active one becomes visible again |
| L-T-08 | IF a call is executed, THEN the registered tool that was **advertised in the model request** runs |
| L-T-09 | IF a call is unavailable, THEN **only this** call fails – not others |
| L-T-10 | IF the domain output is produced, THEN it does **not** change through model-output bounding or retention policy |

### 2.2 Session invariants (`specs/v2/session.md`)

| ID | Rule |
|---|---|
| L-S-01 | IF an input is admitted, THEN exactly one `session_inbox` row exists **before** execution begins |
| L-S-02 | IF `resume` is missing or `true`, THEN `SessionExecution.wake` is additionally triggered |
| L-S-03 | IF a step runs, THEN it is **exactly one** `llm.stream` call |
| L-S-04 | IF a local tool call starts, THEN it is durable **before** the side effect begins |
| L-S-05 | IF a tool call is published, THEN it carries `assistantMessageID` in addition to `callID` |
| L-S-06 | IF a step ends, THEN there is **exactly one** terminal event (ended or failed) |
| L-S-07 | IF instructions change, THEN **one** delta is admitted atomically – before input delivery |
| L-S-08 | IF compaction ends, THEN the epoch start is set to the exact sequence |
| L-S-09 | IF a session is idle/settled/not locally owned, THEN interrupt is a no-op |
| L-S-10 | IF a session log is subscribed with `follow`, THEN subscription happens **before** replay and a sync marker is sent |

### 2.3 Architecture invariants (root `AGENTS.md`)

| ID | Rule |
|---|---|
| L-A-01 | IF the public `Protocol`/`HttpApi` is changed, THEN `bun run generate` runs in `packages/client` |
| L-A-02 | IF generated client files are to be changed, THEN **don't** – they are generated |
| L-A-03 | Runtime dependencies run Schema → Core & Protocol → Server; the client may **never** import Core or Server |
| L-A-04 | IF a changeset is created, THEN **no** – this repo uses no changesets |
| L-A-05 | IF a branch is created, THEN from `origin/dev`; the PR target is `dev` |
| L-A-06 | IF a new file is created in `src/config`, THEN it follows the self-export pattern (`export * as ConfigX from "./x.js"`) |
| L-A-07 | IF an Effect generator uses service methods, THEN the service is bound to a named variable |
| L-A-08 | IF a tool wraps `execute`, THEN **no** `catchCause` is used – interruption and defects must survive |
| L-A-09 | IF types are exported, THEN no `any`, no star import, no alias import |
| L-A-10 | IF tests run, THEN **not** from the repo root (guard `do-not-run-tests-from-root`) |

---

### 2.4 Bus, projection and instruction invariants

| ID | Rule | Reference |
|---|---|---|
| L-E-01 | IF a stored event is decoded, THEN the payload **always** runs through `SessionLegacy.event(...)` – old shapes are upgraded in **one** place | `packages/core/src/bus.ts:74-76` |
| L-E-02 | IF an unknown event type comes from the DB, THEN decoding throws `InvalidDurableEventError` – **no** silent drops | `packages/core/src/bus.ts:60-66, 78-90` |
| L-E-03 | IF a session is moved, THEN the move event reaches **both** locations (old and new) without changing the event; route application happens **only after** the projection transaction commits | `packages/core/src/bus.ts:246-259` |
| L-E-04 | IF an event is published live, THEN observation runs **sequentially** – no fork per event – so all subscribers see the same order | `specs/v2/event-stream-architecture.md:132` |
| L-E-05 | IF session events are projected, THEN terminal events of active projections stay on the parent (a fork copies only settled history); a newer step supersedes stale incomplete rows (never continue an older assistant); `time_viewed` is a monotonic watermark | `packages/core/src/session/projector.ts:203-206, 326, 449-450, 623-625` |
| L-I-01 | IF two instruction sources carry the same key, THEN `Instructions.combine` throws a `DuplicateKeyError` | `packages/core/src/instructions/index.ts:124-132` |
| L-I-02 | IF a source renders no text, THEN that is an **error** (`requireText`) – empty instruction text is not a silent skip | `packages/core/src/instructions/index.ts:253-255` |
| L-I-03 | IF an instruction hash is formed, THEN it is **always** SHA-256 hex (64 characters) – enforced in the schema | `packages/schema/src/instruction.ts:11-15` |
| L-I-04 | IF a file (AGENTS.md) is injected as an instruction, THEN that is published via **`SessionEvent.Synthetic`**, **not** via `Session.synthetic` – otherwise a layer cycle arises through `LocationServiceMap` | `packages/core/src/session/instructions.ts:70-79` |
| L-I-05 | IF an instruction source changes a value, THEN identical values must render **identical bytes** | `packages/core/src/instructions/index.ts:33-56` |

---

## 3. Change-Impact Matrix

| Desired change | To change | Affected contracts | Generators | Tests | Migration |
|---|---|---|---|---|---|
| **New permission action** | `packages/schema/src/permission.ts` (action is a free string), tool implementation, possibly `packages/schema/src/agent.ts` (defaults) | `Permission.Rule` | – | `packages/core` (permission tests), `packages/plugin` (permission domain) | no |
| **New tool** | `packages/core/src/tool/plugin/<name>.ts`, registration in `packages/core/src/plugin/internal.ts`, permission assert, possibly `packages/schema/src/tool.ts` | Tool API, agent permissions | – | `packages/core`, read `packages/core/src/tool/AGENTS.md` | no |
| **Add provider** | `packages/ai/src/providers/<id>.ts`, possibly `packages/ai/src/protocols/`, `packages/core/src/plugin/provider/` | `packages/schema/src/provider.ts` | – | `packages/ai`, `packages/core` | no |
| **Change/add session event** | `packages/schema/src/session-event.ts`, `packages/core/src/session/projector.ts`, possibly `packages/schema/src/durable-event-manifest.ts` | public event log, SDK | `bun run generate` in `packages/client` **and** `packages/protocol` | `packages/core` (projector/event tests) | possibly `packages/core/src/database/migration/` |
| **Change schema field** | `packages/schema/src/<domain>.ts` | all dependent packages | `packages/client` + `packages/protocol` (`check:generated`) | package by package | **yes** – new file in `packages/core/src/database/migration/` + `migration.gen.ts` |
| **Change API endpoint** | `packages/protocol/src/groups/<group>.ts`, possibly `packages/server/src/handlers/<x>.ts` | `HttpApi`, OpenAPI | `packages/protocol` + `packages/client` | `packages/server`, `packages/protocol` | no |
| **Add instruction source** | `packages/core/src/config/plugin/instruction.ts`, `packages/core/src/instruction-discovery.ts`, `packages/core/src/instructions/` | instruction order | – | `packages/core` | no |
| **Add agent** | `packages/core/src/plugin/agent.ts` or Markdown in `.opencode/agents/` | `packages/schema/src/agent.ts` | – | `packages/core` | no |
| **MCP server support** | `packages/core/src/mcp/`, `packages/core/src/tool/mcp.ts`, `packages/schema/src/mcp.ts` | MCP schema | – | `packages/core` | no |
| **Change tool output bounding** | `packages/core/src/tool-output.ts`, `packages/core/src/tool.ts` | tool outcome shape | – | `packages/core` | no |
| **Change compaction behaviour** | `packages/core/src/session/compaction.ts`, `packages/core/src/session/runner/` | `SessionMessage.Compaction` | – | `packages/core` | possibly |
| **Extend policy rule** | `packages/schema/src/config/policy.ts`, `packages/core/src/config/plugin/policy.ts`, `packages/core/src/managed-policy.ts` | `ConfigPolicy.Info` (closed set!) | – | `packages/core` | no |
| **Change session flow** | `packages/core/src/session/{session,inbox,execution,run-coordinator}.ts` | Session API | `packages/client` | `packages/core` | possibly `resume_attempts` semantics |
| **Change TUI/surface** | `packages/tui/src/`, `packages/app/src/`, `packages/ui/src/` | – | – | `packages/tui` (`tui-tests`), `packages/app` (`app-e2e-tests`, `component-tests`) | no |
| **Console policy delivery** | `packages/core/src/plugin/provider/opencode.ts`, `packages/core/src/managed-policy.ts` | `GET /api/v2/config` | – | `packages/core` | no |

### 3.1 Order for schema/API changes

```
1. packages/schema/src/<domain>.ts        (contract)
2. bun run generate  (packages/protocol)  → openapi.json
3. bun run generate  (packages/client)    → Promise/Effect/Solid client
4. packages/core/src/...                  (implementation)
5. packages/server/src/handlers/...       (only if endpoint affected)
6. bun run check                          (root: lint + typecheck)
7. tests package by package
8. possibly migration in packages/core/src/database/migration/
```

---

## 4. Test and verification strategy

### 4.1 Canonical checks

| Purpose | Command | Location |
|---|---|---|
| Lint + typecheck (canonical) | `bun run check` | repo root |
| Typecheck a package | `bun typecheck` | in the package, e.g. `packages/core` |
| TypeScript directly | **never** `tsc` | – |
| Lint | `bun run lint`, `bun run lint:effect-patterns`, `bun run lint:effect-simplifications` | root |
| Generated files check | `bun run check:generated` | `packages/client`, `packages/protocol`, `services/www` |

### 4.2 Test kinds per package

| Package | Test type | Location | Skill |
|---|---|---|---|
| `core` | `bun test` (unit + integration) via `bun run script/test.ts` | `packages/core/test` | `unit-tests` |
| `cli` | `bun test` | `packages/cli/test` | `unit-tests` |
| `ai` | `bun test` | `packages/ai/test` | `unit-tests` |
| `schema` | `bun test` | `packages/schema/test` | `unit-tests` |
| `protocol` | `bun test` | `packages/protocol/test` | `unit-tests` |
| `server` | `bun test` (incl. SSE/worktree integration) | `packages/server/test` | `unit-tests` |
| `client` | `bun test` + `check:generated` | `packages/client/test` | `unit-tests` |
| `tui` | OpenTUI renderer tests | `packages/tui/test` | `tui-tests` |
| `app` | Playwright E2E + Bun browser tests | `packages/app/e2e`, `test-browser` | `app-e2e-tests` |
| `ui`, `session-ui` | Storybook Playwright component tests | `component-tests/` | `component-tests` |
| `desktop` | `bun test` + electron-builder config tests | `packages/desktop` | – |
| `codemode` | `bun test` | `packages/codemode/test` | `unit-tests` |
| `http-recorder` | `bun test` | `packages/http-recorder/test` | `unit-tests` |
| `httpapi-codegen` | `bun test` (**= specification of the generator**) | `packages/httpapi-codegen/test` | – |
| `simulation` | `bun test` | `packages/simulation/test` | – |
| `merman`, `latex` | `bun test` + layout audit | package-internal | – |

### 4.3 Test rules

* **Never** test from the repo root – `bunfig.toml:7-8` sets
  `[test] root = "./do-not-run-tests-from-root"`, `package.json:36` makes the
  root `test` script fail hard.
* **Core tests** always via `bun run script/test.ts` from `packages/core`
  (isolates HOME/TMPDIR, 30 s timeout) – never `bun test` directly
  (`packages/core/script/test.ts:5-44`).
* Before writing/changing tests, read the matching skill:
  `.opencode/skills/unit-tests`, `tui-tests`, `app-e2e-tests`, `component-tests`.
* **No** `--isolate`/`--parallel` in test scripts; **no** `mock.module`; do not
  assert on wall-clock speed; if something goes wrong, do not react with
  sleeps/polls/timeout increases – remove the wait instead.
  (`.opencode/skills/unit-tests/SKILL.md:30, 72-73, 105-111`)
* **TUI**: always pass `width`/`height` to the renderer, `kittyKeyboard: true`,
  never hard-code ports. Frame-budget errors are **not** timeout problems – do
  not paper over them with larger `maxPasses`.
* **e2e**: never `waitForTimeout`/sleeps for synchronisation; every product area
  has **one** keeper suite in `regression/`; extend the harness in `utils/`.
* **A package with test files but no `test` script never runs in CI** – check
  `bun turbo test --dry=json`.
* **Tests never read `dist`** – workspace packages export sources.
* Windows-specific timeouts and CI flakes are documented in the `unit-tests`
  skill – read before "fixing".

---

## 5. Guardrails (from the 19 `AGENTS.md` files)

| Topic | Rule | Source |
|---|---|---|
| Architecture | Schema ← Protocol ← Server; client never Core/Server | Root `AGENTS.md` |
| Generated | `bun run generate` in `packages/client` after protocol/server changes; do not edit generated files | Root `AGENTS.md` |
| Releases | **no** `.changeset` files | Root `AGENTS.md` |
| Branches | Base `origin/dev`, PR target `dev`, branch name ≤ 3 words, no slashes/prefixes | Root `AGENTS.md` |
| Commits | Conventional Commits `type(scope): summary`; types: feat, fix, docs, chore, refactor, test | Root `AGENTS.md` |
| Session core | one step = one `llm.stream`; **no** in-memory tool loop; execution process-local | Root `AGENTS.md` |
| Session core | prompt admission ≠ execution; inbox items stay outside model history until delivery | Root `AGENTS.md` |
| Session core | Session-ID reuse is adopted; item ID idempotent; cross-session/type fails | Root `AGENTS.md` |
| Session core | Delivery vocabulary: `steer` (default) vs. `queue`; control items are boundaries | Root `AGENTS.md` |
| Instructions | Instructions are value deltas; no instruction registry | Root `AGENTS.md` |
| Tool architecture | Registry has **no** permission dependency; filtering = catalog visibility | `core/src/tool/AGENTS.md` |
| Tool architecture | Leaves own resolution, permission and side-effect ordering; no `catchCause` | `core/src/tool/AGENTS.md` |
| Tool architecture | `edit`/`write`/`patch` share the action `edit` | `core/src/tool/AGENTS.md` |
| Tool output | Bounding after execution; producer limits ≠ registry bounding | `core/src/tool/AGENTS.md` |
| Schema package | `Schema ← Protocol ← Server`; browser-safe contracts, no runtime logic | `packages/schema/AGENTS.md` |
| Schema package | V1 only under `src/v1/`; `V2` must not be kept as a permanent namespace | `packages/schema/AGENTS.md` |
| Schema package | `optional(...)` helper for optional fields; no `Schema.Any`; IDs validated with prefix | `packages/schema/AGENTS.md` |
| Schema package | V1-only events (`message.updated`, `message.part.*`) not in the current protocol/SDK surface | `packages/schema/AGENTS.md` |
| Plugin package | Two versions (Effect + Promise); Effect domains extend the Effect API client | `packages/plugin/AGENTS.md` |
| Codemode | `codemode: false` keeps a tool on the native list | `core/src/tool/AGENTS.md` |
| TUI | `bun run dev:live` for tests against the real service; Storybook fixtures for TUI stories | Root `AGENTS.md` |
| TUI themes | Choose tokens by **semantic role**; no raw `theme.hue` values | Root `AGENTS.md` |
| App/E2E | Mocked-server harness; suite-wide service-worker block; 30-s budget | `.opencode/skills/app-e2e-tests` |
| Stats | Own portal, separate from the console | `packages/stats/AGENTS.md` |
| Website | Only `base` in `astro.config.ts` knows the subpath; `openapi.json` is generated | `services/www/AGENTS.md` |
| Docs style | Short sections, one example per subsection, `lib/navigation.ts` is the truth | `services/www/src/docs/AGENTS.md` |
| i18n | **Never** hard-code user-visible English strings – always an i18n key (placeholders, labels, tooltips, menus, dialogs, toasts, empty states, errors) | `packages/app/AGENTS.md:48`, `packages/ui/AGENTS.md:3` |
| i18n | Feature work adds **only** English source strings; other locales stay empty (runtime fallback); existing English text is **never** changed for translation purposes | `packages/app/AGENTS.md:49, 52` |
| i18n | Plurals via `language.plural(baseKey, count, params)` – **never** `.zero/.one/.two` on `t(...)`; locale complexity stays in the shared typed-i18n APIs | `packages/app/AGENTS.md:50, 53` |
| Typography | `--line-height-compact` (16 px) for 13-px text, `--line-height-base` (20 px) for body text; **no** `leading-none` at 13 px | `packages/app/AGENTS.md:42-44`, `packages/ui/AGENTS.md:17-19` |
| Solid | `createStore` instead of several `createSignal`; never `createEffect`/`createRenderEffect` outside `src/sdk/` in GUI extensions; do not destructure props; `<Show>`/`<For>` instead of `&&`/`.map` | `packages/app/AGENTS.md:20`, `packages/gui-extensions/AGENTS.md:71, 77` |
| Solid suspense | Guard every render-time read on `state` (`ready`/`refreshing`), otherwise the UI blanks | `packages/app/AGENTS.md:24-38` |
| App process | **NEVER** restart the app or server process; before session/timeline changes capture a production benchmark baseline | `packages/app/AGENTS.md:4, 8` |
| Tests (app) | no mocking what is asserted, no un-awaited `rejects`/`resolves`, no production seams for tests (no `now`/`delay`, no `data-*`) | `packages/app/AGENTS.md:70-71`, `packages/desktop/AGENTS.md:7` |
| Tests (desktop) | `bun typecheck` covers **no** test files → `bun test <file>` per touched test file | `packages/desktop/AGENTS.md:11` |
| ast-grep | `no-import-alias`, `no-star-import`, `no-nested-effect-service-yield`, `no-drizzle-column-name`, `no-json-parse-cast`, `no-effect-die-string` – mechanically enforced | `script/ast-grep/rules/*.yml` |
| GitHub workflow | CI unit job: `bun turbo test --affected --concurrency=3` – needs a `test` script **and** a Turbo task entry | `.github/workflows/test.yml`, `turbo.json:45-48` |

---

## 6. Pitfalls

| # | Pitfall | Why | Where |
|---|---|---|---|
| 1 | A `catchCause`/`catch` around `execute` | swallows interruption and defects – the runner can no longer abort the call | `core/src/tool/AGENTS.md` |
| 2 | Editing client files by hand | they are generated; the next `generate` run overwrites them | Root `AGENTS.md` |
| 3 | `packages/core` importing `packages/client` | violates the layer direction; the client is an outer layer | Root `AGENTS.md` |
| 4 | Creating a `.changeset` | this repo uses the existing release workflow, no changesets | Root `AGENTS.md` |
| 5 | Reusing V1 names | `write`/`patch` → `edit`, `task` → `subagent`, `bash` → `shell` | `core/src/v1/config/migrate.ts:28-33` |
| 6 | Using `doom_loop` as a V2 action | exists only in legacy config (action-only) | `packages/core/src/v1/config/permission.ts:27, 38` |
| 7 | Using `experimental.policies` as a new location | deprecated; top-level `policies` is the place | `packages/schema/src/config/experimental.ts:23-25` |
| 8 | Running tests from the repo root | guard `do-not-run-tests-from-root` aborts | Root `AGENTS.md` |
| 9 | Running `bun run check` alongside the dev server | Vite cache collision in `services/www` | `services/www/AGENTS.md` |
| 10 | Continuing to use `session_pending` | legacy table; active is `session_inbox` | `packages/core/src/session/sql.ts:100-123` |
| 11 | Expecting agent `request` overlays | the V2 runner receives them but **does not send** them | `agents.mdx:337-340` |
| 12 | Using `CLAUDE.md` as an instruction file | V2 knows only `AGENTS.md` | `instructions.mdx:41-43` |
| 13 | Expecting `todowrite` as an existing tool | only registered when `experimental.todo === true` | `core/src/tool/plugin/todo.ts:50-55` |
| 14 | `Schema.Any` in public contracts | forbidden; use `Schema.Json`/`Schema.Unknown` | `packages/schema/AGENTS.md` |
| 15 | Importing `schema` → `protocol` → `core` | violates layer direction | `packages/schema/AGENTS.md` |
| 16 | Injecting instruction files via `Session.synthetic` | creates a layer cycle through `LocationServiceMap` – use `SessionEvent.Synthetic` | `packages/core/src/session/instructions.ts:70-79` |
| 17 | A tool with `output` but no `output` in the result (or vice versa) | produces a defect or `Tool.Error` | `packages/core/src/tool/runtime.ts:47-55` |
| 18 | A plugin throwing a foreign failure | normalised at the untrusted boundary into `Tool.Error`, otherwise the call stays unmoved | `packages/core/src/tool/runtime.ts:30-46` |
| 19 | Starting core tests with `bun test` instead of `bun run script/test.ts` | bypasses HOME/TMPDIR isolation and the 30-s timeout | `packages/core/script/test.ts:5-44` |
| 20 | Using ports ≥ 49152 on Windows | 49152–65535 are reserved | `.opencode/skills/app-e2e-tests/SKILL.md:108` |
| 21 | Taking `bun run check` as proof of test-file coverage | `tsc -b` only includes test files with a matching `tsconfig.tests.json` | `packages/core/package.json` |
| 22 | Regenerating OpenAPI only in `packages/protocol` | `services/www/{openapi.json,public/openapi.json}` are **copies** and must be regenerated too | `services/www/script/generate-openapi.ts:3-6` |

---

## 7. Change checklist (short form)

Before submitting:

```
[ ] bun run check                     (root)
[ ] bun typecheck                     (affected package)
[ ] bun run generate                  (packages/protocol + packages/client, if API affected)
[ ] bun run check:generated           (packages/client, packages/protocol)
[ ] Tests in the affected package     (bun test <pkg>)
[ ] No .changeset, no V2 namespace, no alias/star imports
[ ] Branch: ≤ 3 words, base origin/dev, PR target dev
[ ] Commit: type(scope): summary
[ ] On schema change: migration in packages/core/src/database/migration/
[ ] New public shape: optional(...) instead of Schema.optional, no Schema.Any
[ ] Tool change: core/src/tool/AGENTS.md read
```

---

## 8. Still-open knowledge gaps

1. `packages/core/src/variant.ts` (691 lines) – model variants and overlays;
   marked as open question in `01-use-cases.md`.
2. `packages/core/src/plugin/provider/opencode.ts` (812 lines) – console provider,
   SSO, policy delivery in one file.
3. `packages/core/src/session/stats.ts` (14 KB) – domain relevance of the metrics.
4. `packages/core/src/shell/scan.ts` (2,410 lines) – both scanners (tree-sitter
   and portable) in detail.
5. `packages/core/src/github-copilot/` – special-case provider, 4,315 lines.
6. The console harnesses (Claude Code, Codex, Hermes, Pi, Raycast) have **no
   domain specification** in the repo.