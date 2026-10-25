# OpenCode (V2) – Project Overview

> **Rolle:** reference analysis des OpenCode-V2-Checkouts, not an operative
> QOC-Spec. For the implementation, see [`spec/`](../spec/README.md).

> **Status:** First overview, dated 2026-10-21, repo state `dev` @ `3108505b2f`
> (Source: `https://github.com/anomalyco/opencode.git`, version `2.1.2`).
>
> **Method:** Analysis with the code knowledge graph (44,297 nodes / 213,584 edges)
> plus direct reading of the source code. Supported statements carry a file path,
> optionally with a line number. Statements without a path are interpretation and
> are marked as such.

---

## Contents

- [1. What is this?](#1-what-is-this)
- [2. Repo overview](#2-repo-overview)
- [3. Use cases (from the user's perspective)](#3-use-cases-from-the-users-perspective)
- [4. Data flow: a request from keystroke to file](#4-data-flow-a-request-from-keystroke-to-file)
- [5. Prompts & agents](#5-prompts-agents)
- [6. Tools](#6-tools)
- [7. Permission system](#7-permission-system)
- [8. Providers & models](#8-providers-models)
- [9. Interfaces](#9-interfaces)
- [10. Configuration](#10-configuration)
- [11. Documentation (in the repo)](#11-documentation-in-the-repo)
- [12. Development & operations](#12-development-operations)
- [13. Open points / next steps](#13-open-points-next-steps)
- [Appendix: evidence overview](#appendix-evidence-overview)

## 1. What is this?

OpenCode is an **open-source AI coding agent** ("The open source AI coding agent",
`README.md:20`). The user gives natural-language tasks in a project directory;
the agent analyses the code, calls tools (read/write files, shell, search, web, MCP)
and delivers a change plus explanation.

The product exists as a **monorepo** with several surfaces (terminal, web, desktop,
SDK/API) on a shared core. Version `2.1.2` – the codebase internally carries the
name **V2** (new session/execution architecture); a V1 legacy layer also exists,
kept only for migration (`packages/schema/AGENTS.md`: *"V1 coexistence is temporary"*).

The actual functional specification lives in **`specs/v2/`** – that is the
authoritative source, not the code:

| Document | Content |
|---|---|
| `specs/v2/session.md` | Prompt admission, execution, instructions, compaction, recovery |
| `specs/v2/tools.md` | Tool construction, registration, execution, outcome laws |
| `specs/v2/event-stream-architecture.md` | Why public events are *one* encoded feed with independent queues |
| `specs/v2/provider-policy.md` | Provider allow/deny, permission checks, console statements as final authority |
| `specs/v2/catalog-config-plugin-lifecycle.md` | Historical: option comparison → replayable location-scoped catalog transforms |

> **Caution, currency**: `provider-policy.md` shows `experimental.policies`;
> currently `policies` is **top-level**, `experimental.policies` is deprecated
> (see section 7.6). `catalog-config-plugin-lifecycle.md` is explicitly a
> **historical** protocol, not an API reference.

The authority/ownership rule stands in `specs/v2/README.md:9-14`:

| Concern | Owner |
|---|---|
| HTTP operations, transport errors | `packages/protocol` (endpoint definitions, assembled by Server `HttpApi`) |
| Public domain shapes, durable event payloads | `packages/schema` |
| Runtime behaviour, persistence | `packages/core` |
| Regression guardrails | Root `AGENTS.md` |

---

## 2. Repo overview

```
opencode/
├── packages/        34 packages (TS/Bun, Effect v4)
├── services/        www (Astro docs), changelog, files, update
├── sdks/            vscode
├── specs/v2/        functional specification (see above)
├── infra/           SST deployment (webapp, gateway …)
├── salesforce/      Salesforce integration (force-app)
├── github/          GitHub App / integrations
├── perf/            Benchmarks
├── script/          helper scripts (ast-grep rules, release)
└── .opencode/       repo-local agent configuration of the project itself
```

### 2.1 Packages and layers

Dependency direction per `AGENTS.md`: **Schema → Core & Protocol → Server**.
Client runtime may use Schema + Protocol, but **never Core or Server**.
`sdk` composes Client + Core + Server.

| Package | Name | Purpose |
|---|---|---|
| `packages/schema` | `@opencode/schema` | Browser-safe wire/storage contracts, IDs, public event payloads |
| `packages/protocol` | `@opencode/protocol` | `HttpApi` endpoint definitions (31 groups), middleware, errors |
| `packages/core` | `@opencode/core` | Domain logic: session, execution, tools, permission, provider, DB, plugins, MCP, shell, VCS |
| `packages/server` | `@opencode/server` | HTTP server, route assembly, auth, CORS, event feed |
| `packages/client` | `@opencode/client` | Generated HTTP client (Promise + Effect variants) |
| `packages/sdk` | `@opencode/sdk` | Composition of the three above |
| `packages/cli` | `@opencode/cli` | CLI entry point, background service, ACP (Zed), `run`, `serve` |
| `packages/tui` | `@opencode/tui` | Terminal UI (OpenTUI), V2 TUI + Mini TUI |
| `packages/app` | `@opencode/app` | Web/desktop UI (SolidJS-based), `packages/ui` = design system |
| `packages/session-ui` | `@opencode/session-ui` | Reusable session components (markdown stream etc.) |
| `packages/desktop` | `@opencode/desktop` | Electron desktop app (beta) |
| `packages/ai` | `@opencode/ai` | Provider protocols, message/tool types, cache policy, effort, image, voice |
| `packages/plugin` | `@opencode/plugin` | Public plugin/tool types (`Tool.make`, Effect plugin API) |
| `packages/codemode` | `@opencode/codemode` | "Code Mode": tools as JS API instead of native tool calls |
| `packages/merman` | `@opencode/merman` | Diagram/Mermaid rendering |
| `packages/function` | `@opencode/function` | Cloudflare worker functions (GitHub App token exchange, share) |
| `packages/gui-extensions` | `@opencode/gui-extensions` | Extension SDK + bundled desktop/web extensions (terminal, review, files, browser, SSH, WSL, updater) |
| `packages/plugin-browser` | – | Browser plugin of the desktop app |
| `packages/plugins/figma` | – | Figma MCP integration + official Figma skills |
| `packages/session-ui` | `@opencode/session-ui` | Session timeline, diff/review, line comments (`session-diff`, `session-review`, `line-comment`) |
| `packages/latex` / `packages/merman` | | LaTeX / Mermaid diagram rendering for markdown |
| `packages/ui` | `@opencode/ui` | Design system, icons, tokens, i18n |
| `packages/util` | `@opencode/util` | Utilities + Effect layer helpers |
| `packages/containers` | – | pre-built CI container images (base, bun-node, rust, tauri-linux) |
| `packages/http-recorder` | `@opencode/http-recorder` | Record/replay HTTP/WS traffic as deterministic cassettes |
| `packages/httpapi-codegen` | `@opencode/httpapi-codegen` | Build-time generator for Promise/Effect clients from `HttpApi` |
| `packages/simulation` | `@opencode/simulation` | Session recording/replay (backend, frontend, protocol, recording) |
| `packages/theme` | `@opencode/theme` | TUI themes (`./tui`, `./tui/v1`) |
| `packages/storybook` | – | Storybook wrapper for component stories |
| `packages/script` | `@opencode/script` | Script helper library (highlights) |
| `packages/web` | `@opencode/web` | Astro/Starlight documentation site |
| `packages/posts` | – | private Astro blog/casts |
| `packages/stats` | – | stats portal (`app`) + stats core + sync service |
| `packages/console` | – | SolidStart portal opencode.ai: Go plans, Zen gateway, enterprise form, downloads, stats proxy |
| `packages/identity` | – | brand assets (logos) for the identity page |
| `packages/effect-drizzle-sqlite` | – | Drizzle/SQLite binding for Effect v4 (directory contains only `examples/`, no `src` – *unconfirmed*) |

**Package boundaries from the graph** (`get_architecture`): `gui-extensions → app` (229 calls),
`app → session-ui` (186), `app → ui` (132), `app → ai` (82), `ui → app` (79).

**Dependency direction** (verified from `package.json` deps):

| Package | Depends on | Role |
|---|---|---|
| `@opencode/schema` | only `effect` | public forms + **durable** event definitions |
| `@opencode/protocol` | `schema` | `HttpApi` definition (routes/groups), no runtime logic (`packages/protocol/src/api.ts:155-186`) |
| `@opencode/core` | `schema`, `util`, `ai`, `plugin` | Execution, persistence, bus |
| `@opencode/server` | `core`, `protocol`, `schema` | HTTP handlers, layer graph, auth |
| `@opencode/client` | `schema`, `protocol` | generated Promise/Effect/Solid client (imports **neither** core nor server) |
| `@opencode/sdk` | `client`, `core`, `server`, `plugin` | In-process embed: `createEmbeddedRoutes` + `HttpEffect.toWebHandlerWith` (`packages/sdk/src/internal/host.ts:30-71`) |
| `@opencode/httpapi-codegen` | – | Generator engine (`packages/httpapi-codegen/src/index.ts`), used by `packages/client/script/build.ts` |

The server starts the API in `packages/server/src/routes.ts:178`
(`HttpApiBuilder.layer(Api, …)`), the service graph is in `:52-79`; the
Node process in `packages/server/src/process.ts:47-120`, CLI entry
`packages/cli/src/commands/handlers/serve.ts` → `packages/cli/src/server-process.ts:83`.
`packages/server/src/fetch.ts:41` provides a web-standard `fetch` handler for
workerd/Deno/Bun.

### 2.2 Binaries / processes

* **CLI** (`packages/cli`): binaries `opencode` (`packages/cli/bin/opencode.cjs`) and
  `opencode2` (`packages/cli/bin/opencode2.cjs`) – `packages/cli/package.json:7-10`.
  Commands (`packages/cli/src/index.ts:28-82`, `commands/commands.ts`):
  Default (TUI), `upgrade`, `uninstall`, `acp` (Agent Client Protocol), `api`,
  `debug {agents,config,paths}`, `auth {list,login,logout,export,import,switch}`,
  `mcp {list,add,auth,logout}`, `plugin {list,add,check,update,remove}`, `models`,
  `stats`, `mini`, `run`, `session {list,delete,export,import}`,
  `service {start,restart,status,stop,get,set,unset}`, `serve`, `pair`, `reload`.
* **Server**: `packages/server/src/routes.ts` assembles the `HttpApi` from
  `packages/protocol/src/api.ts` and wires ~26 global service nodes
  (`routes.ts:52-78`). `opencode serve` starts "the v2 API and web server"
  (`commands.ts:550-563`, modes `default|service|stdio`).
* **Desktop**: `packages/desktop` (Electron, `src/main/index.ts`), renderer is
  `@opencode/app`.
* **Web**: `packages/app` (product UI, Solid router: `/`, `/settings`,
  `/server/:serverKey/session/:id`, `/new-session` – `packages/app/src/shell/routes/routes.tsx:46-72`),
  `packages/web` (docs), `services/www` (Astro website + `/openapi.json`),
  `packages/console` (marketing/enterprise/billing).

---

## 3. Use cases (from the user's perspective)

| # | Use case | Surface | Evidence |
|---|---|---|---|
| 1 | **Interactive coding in the terminal** – agent edits code in the project directory | TUI (`packages/tui`) | `packages/tui/src/app.tsx` |
| 2 | **Switching agents** (Tab key): `build` (full access) ↔ `plan` (read-only) | TUI | `README.md:88-100`, `packages/core/src/plugin/plan.ts` |
| 3 | **One-off task non-interactively** (CI, scripts) – flags `--model`, `--agent`, `--file`, `--thinking`, `--yolo`, output `default\|json` | `opencode run` | `packages/cli/src/commands/commands.ts:376-417` |
| 3a | **Lean interaction** without full TUI | `opencode mini` | `commands.ts:340-375`, `packages/cli/src/mini.ts` |
| 4 | **Codebase analysis / search** as subagent (`@general`, `explore`) | TUI/tool `subagent` | `packages/core/src/plugin/agent.ts:63-94` |
| 4a | **Diff/review** of a turn (patch diff in TUI, review components, line comments) | TUI, web, `session-ui` | `packages/core/src/session/diff.ts`, `packages/tui/src/component/patch-diff.tsx`, `packages/session-ui` |
| 4b | **Fork / session transfer / revert** | CLI + API | `packages/core/src/session/transfer.ts`, `revert.ts` |
| 5 | **Session history**: list, rename, export, import, delete, share | CLI + app + API | `commands.ts:418-470`, `protocol/groups/session.ts` |
| 6 | **Project/workspace management**, create worktrees | App + API | `packages/core/src/worktree`, `protocol/groups/worktree.ts` |
| 7 | **Web/desktop UI**: chat, diff review, file browser, settings, provider management | `packages/app`, `packages/desktop` | `packages/app/src/*` |
| 8 | **Programmatic integration** (editor plugin, automation, ACP) | `@opencode/sdk`, `sdks/vscode`, `opencode acp` | `packages/sdk`, `packages/cli/src/acp` |
| 9 | **Connect MCP servers** (stdio/HTTP, OAuth) | Config + `opencode mcp add` | `packages/core/src/mcp/` |
| 10 | **Install/manage plugins** (incl. update check) | `opencode plugin …` | `packages/core/src/plugin/update.ts` |
| 11 | **Model/provider management, credentials, OAuth login** | `opencode auth …`, `opencode models` | `packages/core/src/credential.ts`, `packages/core/src/oauth/` |
| 12 | **Sharing a session** (link) | Config `share: manual\|auto\|disabled` | `packages/schema/src/config.ts:43-44` |
| 13 | **Enterprise**: shared sessions, console-managed policies, SSO | Console + ManagedPolicy | `packages/console`, `packages/core/src/managed-policy.ts` |
| 14 | **Terminal/PTY** (interactive shells in the agent, shared PTYs) | TUI + API | `packages/core/src/pty`, `persistent-pty`, `protocol/groups/pty.ts` |
| 15 | **Voice/multimodal input**, image/PDF processing, web search | Tools + voice group | `packages/core/src/voice.ts`, `protocol/groups/voice.ts` |
| 16 | **Usage statistics** (cost, tokens, activity) | `opencode stats` + portal | `commands.ts:309-339`, `packages/stats/*`, `infra/stats.ts` |
| 17 | **Marketing/billing/sales** (Go plans, Zen gateway, enterprise lead form with hCaptcha + Salesforce) | Console | `packages/console/app/src/routes/{go,zen,enterprise,download,stats,data}` |
| 18 | **GitHub integration** ("@opencode" in issues/PRs) | GitHub Action | `github/action.yml`, `github/README.md` |
| 19 | **Structured follow-up questions / forms** (clarifying requirements, auth prompts) | Tool `question` + Form API | `packages/core/src/tool/plugin/question.ts`, `packages/schema/src/form.ts` |
| 20 | **Enterprise console**: workspaces, members, SSO, SCIM, budgets, billing, provider gateway, inference API, web search, team-wide policies | Console web + `/console/api/v1/*` | `services/www/src/docs/content/console/*`, `packages/console` |
| 21 | **Connect other coding agents** (harnesses): Claude Code, Codex, Hermes, Pi, Raycast | Console API key | `services/www/src/docs/content/console/harnesses/*` |

---

## 4. Data flow: a request from keystroke to file

### 4.1 Overview

```
User (TUI/App/SDK)
   │  HTTP/WS to local server  (packages/server, HttpApi from packages/protocol)
   ▼
Middleware: Authorization → Location → Session-Location → Schema-Error
   ▼
Command/Handler  (packages/server/src/handlers)
   │  Session.prompt(...)  →  Core
   ▼
SessionInbox  ──►  Event "session.inbox.enqueued"  ──►  Projection: row in session_inbox
   │                                                      (input lies OUTSIDE model history)
   │  SessionExecution.wake(sessionID)   (only when resume ≠ false)
   ▼
SessionExecution (process-local, per session ID)
   ▼
LocationServiceMap → Location-scoped runner
   ▼
┌─ Step ───────────────────────────────────────────────────────────┐
│ 1. Reload history (projections)                                 │
│ 2. Resolve agent + model                                        │
│ 3. Gather instructions (delta hash)                             │
│ 4. Materialise tools (snapshot)                                 │
│ 5. llm.stream(...)  → provider                                  │
│ 6. Tool calls → Permission.assert → Tool.execute → durable events │
│ 7. Terminal step event (ended / failed)                         │
└───────────────────────────────────────────────────────────────────┘
   ▼
durable Session events → Event log (SQLite) → /api/event feed → clients
```

Evidence: `specs/v2/session.md:5-9` (admission), `:53-68` (execution), `:70-80`
(steps/tool calls), `:106-112` (durable events, `sessions.log({after, follow})`).

**Exact call path** (TUI/App → file):

```
TUI  packages/tui/src/component/prompt/index.tsx:1365      App  packages/app/src/composer/submit.ts:545
 │  client.api.session.prompt({sessionID,text,files})       │  data.session.prompt(admission)
 ▼                                                          ▼
packages/client/src/promise/generated/client.ts:729-733 → POST /api/session/:sessionID/prompt
 │   (generated from packages/protocol/src/client.ts:28 + httpapi-codegen)
 ▼
Protocol endpoint  packages/protocol/src/groups/session.ts:334-355
 │   Middleware: Location (packages/server/src/location.ts:71-99, header "x-opencode-directory")
 │               SessionLocation (packages/server/src/middleware/session-location.ts:16-30)
 ▼
Server handler     packages/server/src/handlers/session.ts:279-314
 ▼
Core facade        packages/core/src/session.ts:450 → Session.prompt
 ▼
packages/core/src/session/session.ts:155-188
   1) admission.reconcile (idempotency)      → session/inbox.ts:155-167
   2) SessionPrompt.prepare (hooks, files, skills) → session/prompt.ts:29-91
   3) SessionInbox.admit → publish session.inbox.enqueued → session/inbox.ts:169-202
   4) execution.wake(sessionID)              → session/execution.ts:169
 ▼
PROJECTION (transactional)  session/projector.ts:683-706 → INSERT session_inbox
 ▼
EXECUTION  session/execution.ts:84-145 → SessionRunCoordinator (run-coordinator.ts:44-54)
   → session/runner/llm.ts:56-193 (drain / advanceToStep)
       → SessionInbox.promote → publish session.inbox.delivered
         → projector.ts:651-682: DELETE session_inbox + INSERT session_message
       → SessionContext.load / SessionHistory (session/history.ts)
       → SessionModelRequest (session/model-request.ts:99-220)
       → SessionStep.attempt (session/runner/step.ts:73-289)
           → llm.stream (packages/ai) → createLLMEventPublisher (runner/publish-llm-event.ts:97+)
           → Tool execution: input.prepared.executeTool → core/src/tool.ts:113
           → SessionEvent.Step.Ended / Tool.Success / Tool.Failed
 ▼
BUS (core/src/bus.ts) → projections + live channel
 ▼
SSE  packages/server/src/handlers/event.ts:11-34 + event-feed.ts → GET /api/event
 ▼
Client (packages/client/src/solid/connection.ts:96-133) folds events
 → core/src/session/message-updater.ts (client-side projection) → UI
```

All state changes run as **durable bus events** (`session.*`,
`packages/schema/src/session-event.ts:200-800`), projected by `Bus.project(...)`
in `packages/core/src/session/projector.ts` **in the same DB transaction**.

**Code-level anchors** (file → role):

| File | Role in data flow |
|---|---|
| `packages/core/src/session/inbox.ts` | Prompt admission, `steer`/`queue`, pending input |
| `packages/core/src/session/execution.ts` | process-global `SessionExecution` |
| `packages/core/src/session/run-coordinator.ts` | local ownership rules (join/coalesce/parallel/interrupt) |
| `packages/core/src/session/runner/index.ts:30-40` | `SessionRunner.drain({sessionID, force, continuation, promotable})` → `DrainResult` (`Complete`/`Moved`/`Reloaded`) |
| `packages/core/src/session/runner/step.ts` | one step = one `llm.stream` |
| `packages/core/src/session/runner/llm.ts` | provider stream + retry |
| `packages/core/src/session/model-request.ts` | request assembly (history, instructions, tools) |
| `packages/core/src/session/projector.ts` | projections (30 KB): events → SQLite rows |
| `packages/core/src/session/message-updater.ts` | message/tool events written (`session.tool.started/success/failed/ended`) |
| `packages/core/src/session/compaction.ts` | rolling summary + context rebuild (42 KB) |
| `packages/core/src/bus.ts` | event bus (publish/subscribe, durable persistence) |

### 4.2 Prompt admission before execution

`Session.prompt(...)` publishes **one** durable fact `session.inbox.enqueued`;
its projection writes exactly one row into `session_inbox`, **before** execution is
scheduled. An inbox item stays outside model-visible history until
`session.inbox.delivered` consumes it and atomically inserts a visible user or
synthetic message (`specs/v2/session.md:7`).

* **Idempotency**: same session ID adopts the session; same item ID is idempotent
  (first admission wins); cross-session/cross-type fails (`session.md:9`).
* **`resume`** controls only scheduling, not durability:
  `true`/omitted → admit **and** `SessionExecution.wake(sessionID)`;
  `false` → admit only (`session.md:11-14`).
* **Delivery modes**: `steer` (default, admission order, delivery at the next safe
  step boundary, stop before compaction/move control items) and `queue` (stays
  pending while the session keeps running) (`session.md:16-19`).
* **Control items**: manual compaction and session movement use the same inbox
  with their own identity and delivery mode; they form a delivery boundary
  (`session.md:23`).
* Agent switch or a batch of steers resets the agent's **step allowance**
  (`session.md:21`).

### 4.3 Execution is process-local

`SessionExecution` is **process-global**, keyed only by session ID. On drain start
it loads the session, enters its `Location` through the `LocationServiceMap` and
calls the location-scoped runner; runner, model resolution, tools, permissions,
plugins and filesystem remain location-scoped (`session.md:53-55`).

Rules of the `SessionRunCoordinator` (`session.md:57-62`):

* explicit resumes join the active execution of the same session,
* repeated wakes are coalesced into **one** follow-up drain,
* different sessions run **in parallel**,
* interrupt stops locally owned execution, **without** deleting pending input.

**Recovery**: a write-ahead claim is written at execution start. Success/failure/
user interrupt resolve it; shutdown interrupt and unclean process death leave it
in place. At start, orphaned tool calls still projected as streaming/running are
failed before further model work begins (`session.md:68`, `:78`, `:114-116`).

### 4.4 One step = exactly one LLM call

* Before **every** step: reload history, resolve agent/model, prepare instructions,
  materialise tools. Usually **one** physical attempt; retry, continuation
  rejection, incomplete stream or overflow compaction can produce another attempt
  – **without** re-admission (`session.md:70-72`).
* **No in-memory tool loop**: after a local outcome the execution reloads the
  projected history and starts a **new** step (`session.md:80`).
* Every completed local tool call is **durable before** side effects begin;
  terminal outcome publication stays serialised (`session.md:74`).
* Tool calls belong to the assistant message; `callID` is unique only **within**
  a step; durable events also carry `assistantMessageID` (`session.md:76`).

**Model request assembly** (`packages/core/src/session/model-request.ts:236-314`):

1. `prepare(kind, input, shape)` builds the request frame: `system`, `messages`,
   `options` and tool definitions.
2. `options.maxTokens` is set only for `primary` and `compaction`
   (`outputLimit(model.limit, kind, inputTokens)`, `:260-262`); `title` and
   `generate` keep the provider default because their reasoning is hard to budget.
3. **Hook renaming** is reconciled by object identity: hooks may move/rename a tool
   definition; entries without a counterpart are discarded (`:246-274`).
4. `options` is split into **generation options** (`GENERATION_KEYS`) and
   **provider options** (`:275-277`).
5. Set among others: `promptCacheKey` (from session affinity), `x-opencode-session-id`,
   `x-session-affinity`, `x-opencode-project`, `User-Agent` (`:279-296`).
6. `messages` are filtered: `unsupportedParts(...)` (capabilities/provider) and
   `boundImages(...)` (`:297`).
7. The hook `session.model.request` may override the `baseURL` (`:305-313`).

### 4.5 Retry

Generic retry applies to rate limits, provider-internal errors, unsent/unclear
transport errors and provider output classified as "incomplete stream". Initial
request + **max. 4 retries**, jittered exponential backoff
(`session.md:84-86`). A content-filter finish fails the step; partially streamed
content stays visible (`session.md:88`).

### 4.6 Compaction

Before every step the runner estimates the complete model-visible request against
the context window of the chosen model (plus reserved output headroom). When
compaction is active, model limits are known and enough older history exists, a
**structured rolling summary + bounded recent context** is stored instead of an
over-budget request. The **full transcript stays durable**; provider-native
continuation state does not cross the compaction boundary
(`session.md:100-102`). An overflow before durable output may cause **one**
overflow compaction; a second overflow or an overflow after durable output is
terminal (`session.md:104`).

**User-facing behaviour** (`services/www/src/docs/content/compaction.mdx`):

* The summary request is made with the **full** conversation so the provider's
  prompt cache can be reused (`:21-23`).
* `compaction.keep.tokens` (default **15,000**) = recent context kept alongside the
  summary; `compaction.buffer` = 15% of the limit (min. 16k)
  (`config.mdx:83-89`).
* **Native provider compaction**: `providers.<id>.settings.compaction.type =
  "native"` (OpenAI Responses models only) – the provider returns an
  **encrypted checkpoint object**; a model change makes it unusable and the session
  continues with the original (`compaction.mdx:91-129`).
* **Manual**: `opencode api post /api/session/<id>/compact --data '{}'`; runs at
  the next safe point, repeated requests are **coalesced**, an `id` makes the call
  idempotent (`:42-66`).
* **Limits**: compaction needs older conversation – it does not help when the
  request consists almost entirely of fixed instructions and tool schemas
  (`:154-166`).

**Session warming** (`warming.mdx`): periodic, **transient** model requests to keep
provider-side prompt caches warm. Default **off**; `interval` default 4 min,
`duration` default 30 min. The request is **not** admitted to history, no tool
call is dispatched, the response is discarded – real requests that incur cost
(`:92-106`).

### 4.7 Instructions are value deltas

`session.instructions.updated { delta, text? }` maps each changed source key to a
SHA-256 content hash; absent sources are marked as literal `"removed"`. Canonical
JSON bodies live exactly once in the machine-local `instruction_blob` store
(`session.md:92`). Sources: built-ins, ambient discovery, agent-specific skill
guidance, references, MCP guidance, API-managed instruction entries. There is **no**
instruction registry (`session.md:92`).

An instruction epoch spans completed compactions; `session.compaction.ended`
moves the epoch start to the exact sequence (`session.md:96`).

### 4.8 Persistence (SQLite / Drizzle)

* **Engine**: SQLite via Drizzle, layer `packages/core/src/database/database.ts:67-81`.
  Pragma setup `:39-47`: **WAL**, `synchronous=NORMAL`, `foreign_keys=ON`;
  file mode `0600` (`:85-98`).
* **File path**: `Global.data` = `XDG_DATA_HOME/opencode` or
  `~/.local/share/opencode` (`packages/util/src/global-roots.ts:4-19`);
  DB file `~/.local/share/opencode/opencode.db` or `opencode-<channel>.db`,
  overridable via `OPENCODE_DB` (`packages/cli/src/database-path.ts:4-13`).
* **File snapshots**: not in the DB but as their own git repo under
  `~/.local/share/opencode/snapshot/<projectID>/<hash>`
  (`packages/core/src/snapshot.ts:92-103`).
* Drivers: `packages/core/src/database/sqlite.{bun,node,workerd}.ts`,
  schema/Drizzle definitions per domain in `packages/core/src/<domain>/sql.ts`,
  generated overall schema in `packages/core/src/database/schema.gen.ts`,
  migrations in `packages/core/src/database/migration/` (most recent:
  `20261010180456_project_time_archived.ts`), execution
  `database/migration.ts:22-52` (bootstrap) resp. `:54-148` (ongoing).
* Central tables (verified from the `sql.ts` files):

| Table | File | Purpose |
|---|---|---|
| `session_v2` | `core/src/session/sql.ts:22` | Session metadata, cost/token, `permission` ruleset, `revert`, `resume_attempts` |
| `session_message` | `session/sql.ts:79` | Messages (user/assistant/synthetic), `seq`, JSON `data` |
| `session_pending` | `session/sql.ts:100` | not-yet-delivered inbox items |
| `session_inbox` | `session/sql.ts:125` | Inbox items with `delivery` + `enqueued_seq` |
| `instruction_entry` / `instruction_blob` / `instruction_state` | `session/sql.ts:147-176` | Instruction deltas, content-hash blobs, epoch state |
| `project` | `core/src/project/sql.ts:12` | Projects (directory → project) |
| `workspace` | `core/src/workspace/sql.ts:5` | Workspaces |
| `worktree` | `core/src/worktree/sql.ts:6` | Worktree assignment per project |
| `permission` | `core/src/permission/sql.ts:7` | **saved** ("always") grants per project |
| `credential` | `core/src/credential/sql.ts:5` | Provider credentials |
| `account`, `account_state` | `core/src/account/sql.ts:5,15` | signed-in accounts/identity |
| `kv` | `core/src/kv/sql.ts:5` | Key-value store |
| `event`, `event_sequence` | `core/src/event/sql.ts:4,10` | **durable event log** with monotonic sequence per aggregate |
| `project_directory` | `schema.gen.ts` | deprecated |

* Path absoluteness is enforced as dedicated column types
  (`core/src/database/path.ts`: `absoluteColumn`, `directoryColumn`, `pathColumn`).
* V1→V2 migration: `core/src/database/v1-migration.bun.ts` (45 KB) plus
  `migration/20260910120000_clear_v1_session_permission.ts`.

### 4.9 Event and message model

* `packages/schema/src/session-event.ts` (19 KB) defines the public session events;
  `session-message.ts` the message structures.
* **V2 no longer has a separate `Part` object.** `SessionMessage.Info`
  (`packages/schema/src/session-message.ts:298-323`) is a union of
  `user | synthetic | system | skill | shell | assistant | compaction | idle |
  agent-switched | model-switched | location-switched`.
  The part-like elements live as a **`content` array inside** the assistant
  message: `AssistantContent = AssistantText | AssistantReasoning |
  AssistantTool` (`:164-206`); tool state `ToolState` =
  streaming/running/completed/error (`:129-162`).
* **V1 had real parts** with their own IDs `prt_…` and types `TextPart`, `ToolPart`,
  `FilePart`, `SnapshotPart` … (`packages/schema/src/v1/session.ts:23-27, 87-330`);
  conversion Part→Content in `packages/schema/src/session-legacy.ts:46-57`,
  DB migration in `database/v1-migration.bun.ts:271-330`.
* Three event classes: **durable** (replayable via `sessions.log({after, follow})`),
  **live** (not replayable: text, reasoning, tool-input and compaction deltas) and
  **ephemeral** (e.g. `permission.asked`, `permission.replied` –
  `packages/schema/src/permission.ts:50-58`).
* `sessions.log({sessionID, after?, follow?})` checks the session and reads durable
  events after an exclusive aggregate sequence; with `follow: true` it subscribes
  **before** replay and sends a synchronisation marker (`specs/v2/session.md:108`).
* There is deliberately **no** separate endpoint for "session history"; request/
  response clients use projections (messages, pending input, context), replay
  clients use the log (`session.md:112`).
* The **live channel** is an SSE feed: `GET /api/event`
  (`packages/protocol/src/groups/event.ts:44-54`,
  `packages/server/src/event-feed.ts`).

**Inbox projections** (verified in `packages/core/src/session/inbox.ts`):

| Projection | Line | Effect |
|---|---|---|
| `SessionInbox.projectAdmitted` | `:277` | Event `session.inbox.enqueued` → one row in `session_inbox` |
| `SessionInbox.projectDelivered` | `:324` | Event `session.inbox.delivered` → inbox row consumed, visible message inserted |
| `SessionInbox.projectCancelled` | `:338` | Item cancelled → row removed |
| `SessionInbox.projectDeliveryChanged` | `:377` | Delivery mode switched |
| `SessionInbox.nextPromotable` / `promote` | `:407` / `:496` | Selection of the next item to deliver |

Further building blocks: `SessionInbox.serialized` (`:62`) = shared serialisation
lock of the inbox, `SessionInbox.LifecycleConflict` (`:65`) = typed error on
ID/type conflicts.

### 4.10 Session state machine

Session state is **not persisted as an enum**, but **derived**:

| Signal | Source |
|---|---|
| "currently running in this process" | `SessionExecution.active` (`session/execution.ts:19`), endpoint `session.active` (`protocol/groups/session.ts:210-221`) |
| "idle" | `session_v2.time_idle` + `idle_outcome` (`session/sql.ts:60-62`), projected via `projectIdle` |
| "idle" (message) | message type `idle` (`session-message.ts:290-296`) |
| "has an open claim" | `session_v2.time_suspended` (execution claim) + `resume_attempts` (`session/store.ts:66-85`) |

* **Idle/busy**: state machine in `session/run-coordinator.ts:44-54` (the code
  contains an ASCII diagram) – one execution fibre per session; `wake` =
  "doorbell", `run` = join, `interrupt` = stop, `awaitIdle` = wait.
* **Steering**: `SessionInbox.steer` (`inbox.ts:240-249`) sets `delivery="steer"`;
  `promote` (`inbox.ts:496-531`) delivers steers at the step boundary, `queue`
  only at idle boundaries; compaction/move are control items and form a delivery
  boundary.
* **Interrupt**: `Session.interrupt` (`session/session.ts:325-328`) →
  `execution.interrupt` (`execution.ts:150-167`); optional `resume: true` resumes
  steer/control, parked queue prompts remain.
* **Recovery**: write-ahead claim + `resume_attempts`; startup recovery in
  `session/execution/restart.ts:65-232` (`resumeSuspendedSessions`, **max. 10
  attempts**, `:33`), triggered in `packages/server/src/process.ts` resp.
  `packages/server/src/fetch.ts:47`.
* **Compaction**: `Session.compact` (`session/session.ts:256-274`) → `admitCompaction`
  (inbox control) → `session/compaction.ts`; auto-compaction on overflow
  (`runner/step.ts:156-167`, `runner/llm.ts:210-217`). `session_v2.time_compacting`
  is now only a legacy column (`session/sql.ts:63`); the current state lives in
  `SessionMessage.Compaction` (running/completed/failed).
* **Retry**: `SessionRunnerRetry` (`runner/retry.ts`), event
  `session.retry.scheduled` (`session-event.ts:584-593`).

### 4.11 Project, worktree, workspace

* **Project = directory identity.** `Project.resolve`
  (`packages/core/src/project.ts:354-397`): searches `.git`/`.hg` upwards; the ID
  comes from the git remote (`Hash` over `host/path`) → cached `opencode` file →
  root commit → otherwise `Hash("directory:<dir>")`. Persisted in table `project`
  (column `worktree` = canonical directory); `session_v2.project_id` is FK to it.
* **Worktrees**: table `worktree` (`project_id` + `directory` + `strategy`);
  strategies in `packages/core/src/worktree/strategies.ts` (default: git, base
  directory `~/.local/share/opencode/worktree/<projectID[:6]>`, `:51`);
  HTTP routes in `packages/protocol/src/groups/worktree.ts` (list/create/remove/refresh).
* **Workspace**: table `workspace` (`provider`, `binding`) – reserved for future
  cluster/workspace identity; `Location.workspaceID` is optional
  (`packages/schema/src/location.ts`, `packages/core/src/location.ts:19-32`).

---

## 5. Prompts & agents

### 5.1 System prompt construction

* Base: `packages/core/src/session/runner/prompt/system.txt` (15 lines) –
  "You are an AI agent running in OpenCode, a coding agent harness…" with harness,
  communication and working-in-codebases rules.
* Tool-specific hints are inserted **dynamically**: `system.txt:7` contains the
  placeholder `${OPENCODE_TOOL_GUIDANCE}`, substituted in
  `packages/core/src/session/system-prompt.ts:9-27` – depending on whether `shell`,
  `write` or `edit` are in the tool snapshot (e.g. "Do not chain shell commands
  with separators …").
* **The central assembly** is `packages/core/src/session/model-request.ts:99-119`
  (`baseTranscript`):
  `system = [ agent.system ?? SessionSystemPrompt.make(toolNames), initial ]`,
  `messages = toLLMMessages(...)`.
  An agent with its own `system` **completely overrides** the harness template.
* **Provider-family prompts** exist as plugins:
  `packages/core/src/plugin/system-prompt/{anthropic,gpt,gpt-astra,kimi,meta,trinity}.txt`;
  selection in `packages/core/src/plugin/optimize.ts:16-62`, application `:72-91` –
  they replace `event.system[0]` **only** when the agent has no own `system` prompt
  (`:81`).
* **Further prompt sources**: plugin hooks (`packages/core/src/plugin/hooks.ts`),
  `packages/core/src/plugin/verbosity.ts`, instructions (sections 4.7/5.2),
  MCP guidance (`packages/core/src/mcp/instructions.ts`),
  reference directories (`packages/core/src/reference.ts`),
  skills (`packages/core/src/skill.ts`).

**Further prompt templates** (verified):

| File | Purpose |
|---|---|
| `packages/core/src/plugin/command/initialize.txt`, `review.txt` | Slash commands `/init` (create AGENTS.md), `/review` (`packages/core/src/plugin/command.ts:8-9, 28-54`) |
| `packages/core/src/session/compaction.ts:107-182` | `SUMMARY_TEMPLATE`, `SUMMARY_RULES`, `buildPrompt`, `NUDGE` for compaction/checkpoint |
| `packages/core/src/session/runner/max-steps.ts:1-16` | `MAX_STEPS_PROMPT` (tools are switched off), used in `runner/llm.ts:234-238` |
| `packages/core/src/tool/patch.txt`, `tool/todowrite.txt` | Tool descriptions |
| `packages/core/src/plugin/agent.ts:7-50` | `PROMPT_EXPLORE`, `PROMPT_TITLE`, `PROMPT_SUMMARY` |
| `packages/core/src/plugin/plan.ts:14-25` | Plan-mode `<system-reminder>` marker |
| `.opencode/command/*.md`, `.opencode/agent/triage.md` | project-specific command/agent markdown files |

### 5.2 Instruction sources (AGENTS.md & co.)

The **instruction sources** are separate from the system prompt and modelled as
value deltas (section 4.7). Discovery plugin:
`packages/core/src/config/plugin/instruction.ts` (247 lines).

| Source | Location | Behaviour |
|---|---|---|
| **Global** | `~/.opencode/AGENTS.md` (`global.config` + `AGENTS.md`, `:41`) | optional via `discovery.global` |
| **Project** | `AGENTS.md` in every **ancestor** directory from the location up to the project root (`:51-58`, `:87`) | optional via `discovery.project` |
| **Configured** | `instructions: [...]` in config – paths or **URLs** (`:112-138`) | Patterns are globbed upwards from the location dir to the project root (`:98-110`); URLs are fetched **only at config load** (5 s timeout, 4 parallel, `:143-171`) |

Order = render order: global → project (nearest-to-farthest) → configured
(`instruction-discovery.ts:33`, `:191`). Changes are detected via file watcher and
re-read with **100 ms debounce** (`:205-210`); a missing or unreadable file yields
`Instructions.unavailable`, but does not block the other sources (`:92`, `:220-222`).

**Order of instruction sources in request context**
(`packages/core/src/session/context.ts:152-163`, `Instructions.combine`):
**CodeMode → MCP → References → Skills → Discovery (AGENTS.md) → Builtins (date/env)
→ Session entries**. The comment there justifies the order as a cache-friendly
prefix.

**Runtime reload**: after a successful `read`, `AGENTS.md` files discovered upwards
are injected as a **durable synthetic message**
(`packages/core/src/tool/plugin/read.ts:82-107`,
`packages/core/src/session/instructions.ts:46-93`, dedup ledger `:99-112`).

**Claude compatibility**: `.claude` and `.agents` are read as **config sources**
(`packages/core/src/config/discovery.ts:69-70, 129-143`),
`.claude/skills` via `packages/core/src/config/plugin/compatibility.ts:42-43`.
As **instruction file**, V2 recognises **only `AGENTS.md`** – `CLAUDE.md` is **not**
a fallback (`services/www/src/docs/content/instructions.mdx:41-43`).

**Order of instructions in the request** (6 stages,
`services/www/src/docs/content/instructions.mdx:63-76`):

```
1. Agent or provider system prompt
2. Code-mode tool guidance (when enabled)
3. MCP-server, reference and skill guidance
4. Global and project AGENTS.md, then configured instructions
5. Built-in date and environment context
6. Session-specific instruction entries (via the API)
```

These sources are **combined**, not used as overrides. Nested `AGENTS.md` files
are loaded **nearest-first** and deduplicated as long as their instruction entry
remains in model history; after compaction or revert a later read may load them
again (`instructions.mdx:59`).

**Built-in instructions** (`packages/core/src/instructions/builtins.ts`):

* `core/date` – "Today's date: …", on change "Today's date is now: …" (`:24-32`)
* `core/environment` – `<env>` block with working directory, workspace root, git repo?
  and platform (`:33-53`)

Note: the `read` tool discovers **neighbouring `AGENTS.md`** files while walking
up to the location on a successful read (`tool/plugin/read.ts:82-84`).

### 5.3 Agents

Agents are a **transformable state** (`packages/core/src/agent.ts`) that plugins
modify at runtime (`editor.update(...)`). Schema:
`packages/schema/src/agent.ts:22-36` – fields: `id`, `name`, `model`, `request`,
`system`, `description`, `mode` (`primary` | `subagent` | `all`), `hidden`, `color`,
`steps`, `permissions`; defaults `:37-54`.

Built-ins in `packages/core/src/plugin/agent.ts` and `plugin/plan.ts`:

| Agent | Mode | Visible | Purpose / rights |
|---|---|---|---|
| `build` | primary | yes | Default agent, full tool rights per config; `question` = allow (`plugin/agent.ts:56-61`) |
| `plan` | primary | yes | Read-only agent; `edit` generally **deny**, exception `~/.opencode/plan/*`; `question` allow (`plugin/plan.ts:34-41`) |
| `general` | subagent | yes | Multi-step/parallel work; `question` + `subagent` = deny (`plugin/agent.ts:63-72`) |
| `explore` | subagent | yes | File search; `*` deny, whitelist for `shell, grep, glob, webfetch, websearch, read`; `*.env` = **ask** (`plugin/agent.ts:74-94`) |
| `compaction` | primary | hidden | Agent for compaction summary (`plugin/agent.ts:96-100`) |
| `title` | primary | hidden | assigns session title; own prompt, `*` deny (`plugin/agent.ts:102-108`) |
| `summary` | primary | hidden | PR-style summary; `*` deny (`plugin/agent.ts:110-116`) |

Plan mode is additionally **contextual**: on entering/leaving plan mode a
`<system-reminder>` is injected (`plugin/plan.ts:14-25`, reconciliation per
request `:57-73`, event-triggered `:75-96`).

**Configurable agents from markdown**
(`packages/core/src/config/plugin/agent.ts`):

* `{agent,agents}/**/*.md` → **subagent**, `{mode,modes}/*.md` → **primary**
  (`:21-26`)
* Frontmatter + body → `system`; model/system/permissions/steps are overridden
  (`:94-127`, `:177-212`)
* Selection per session via session field `agent`
  (`packages/schema/src/session.ts:40`); `agents.select(session.agent)` in
  `packages/core/src/session/context.ts:133-134`. Switching publishes
  `SessionEvent.AgentSelected` (`session/session.ts:101-104`, projection
  `session/projector.ts:577`).

**Subagent invocation** (tool `subagent`, `packages/core/src/tool/plugin/subagent.ts`):

* creates a **child session** (`:29-62`)
* Model priority (`:184`): explicit tool arg → agent model → parent model
* depth limit `experimental.subagent_depth` (`:129-133`)
* `mode === "primary"` is rejected (`:136-137`)

**Agent vs. session**: prompt = `agent.system` (overrides the template), tools =
permission ruleset (agent + session permissions → `registry.snapshot(permissions)`,
`packages/core/src/session/context.ts:136-139`), model = `agent.model` or session
model.

**Agent options** (complete, `services/www/src/docs/content/agents.mdx:179-342`):

| Option | Meaning |
|---|---|
| `description` | Purpose – **shown to the model** that selects the agent |
| `mode` | `primary` \| `subagent` \| `all`; default for new custom agents: `primary` |
| `model` | `provider/model` optionally with `#variant`; subagent otherwise inherits the session model |
| `system` | replaces the provider base prompt **for this agent** (project instructions, skills, references still added) |
| `permissions` | ordered rules; last match wins |
| `steps` | maximum number of model steps; in the **last** step OpenCode removes the tools and asks for a text summary; new user input resets the allowance |
| `hidden` | removes the agent from listings, interactive discovery and subagent catalogue – **visibility, not security** |
| `color` | UI colour as 6-digit hex |
| `disabled` | removes a built-in or custom agent at this point in config processing |
| `request` | header/body overlays – **the V2 runner receives them but does not send them yet** |

**Merging** (`agents.mdx:158-177`): agent definitions merge in config order – later
scalars replace earlier ones, request maps merge **per key**, **permission rules
are appended** (not replaced). Global `permissions` apply **before**
agent-specific rules.

**Agent IDs**: `.opencode/agents/team/reviewer.md` → ID `team/reviewer`
(`agents.mdx:41-45`).

**No `scout` agent in V2** (`agents.mdx:156`). Legacy fields no longer used in V2:
`temperature`, `top_p`, `prompt`, `permission`, `tools`, `disable`, `maxSteps`
(`agents.mdx:342`).

### 5.4 Prompt and tool hooks (plugin API)

The hook infrastructure lives in `packages/core/src/plugin/hooks.ts`. There are
**5 hook domains** (`:13-19`): `aisdk`, `session`, `permission`, `shell`, `tool`.
Only `tool.execute.before` may fail (`:23-30`); all other hooks are infallible.
Hooks can optionally be restricted to a `providerID` (`:35-40`, `:90`).

| Domain | Hooks | Source |
|---|---|---|
| `session` | `prompt`, `context`, `compaction`, `generate`, `title`, `model.request`, `http.request`, `http.response`, `experimental.ws.handshake`, `experimental.ws.send`, `experimental.ws.receive`, `retry` | `packages/plugin/src/effect/session.ts:139-152` |
| `tool` | `execute.before` (can fail → `Tool.Error`), `execute.after` (can **never** fail) | `packages/plugin/src/effect/tool.ts:20-52` |
| `permission` | `evaluate` (may override `effect`/`message`) | `packages/plugin/src/effect/permission.ts:10-33` |
| `aisdk` | `sdk`, `language` (override provider SDK/model) | `packages/plugin/src/effect/aisdk.ts:5-18` |
| `shell` | shell hooks | `packages/plugin/src/effect/shell.ts` |

Additionally the plugin API offers **transformations** (state, not hooks):
`agent.transform`, `tool.transform` (+ `tool.reload`), `command`, `provider`,
`skill`, `websearch`, `vcs`, `integration`, `model`, `mcp`, `worktree`,
`reference`, `storage` – implemented in `packages/core/src/plugin/host.ts`.

The permission hook `evaluate` is the only place where a plugin can **rewrite**
the effect decision of a permission check (`packages/core/src/permission.ts:179-188`).

---

## 6. Tools

### 6.1 One tool type for everything

V2 has **one** structural tool value (`specs/v2/tools.md:7-27`):

```ts
const read = Tool.make({
  description: "Read a file",
  input: Schema.Struct({ path: Schema.String }),
  output: Schema.Struct({ content: Schema.String }),
  execute: ({ path }, context) => /* ... */,
})
```

A tool response can carry three values:

* `output` – schema-validated, **ephemeral** machine value (for code mode),
* `content` – **model-facing** value (stored durably),
* `metadata` – compact JSON for tool-specific UI (`tools.md:19`).

`Tool.Tool` is a transparent value with exactly one `execute` function
(`tools.md:23`). Schema formats: Effect schema plus Standard Schema V1 and
Standard JSON Schema V1 (`tools.md:23,25`).

### 6.2 Call context and identity

Every local tool call gets the same context (`tools.md:31-41`):

```ts
interface Tool.Context {
  sessionID, agent, messageID, callID, progress
}
```

`messageID` is the durable ID of the assistant message, `callID` the same ID as
in the durable events (`tools.md:43`). Interruption is the abort mechanism; tools
may translate expected errors into `ToolFailure`, **not** interruption or defects
(`tools.md:47`).

### 6.3 Registry

`packages/core/src/tool.ts` owns registry, lookup, execution and normalisation.

* **Registration** via `Tool.Service.transform` → `editor.add({...})`
  (`packages/core/src/tool/AGENTS.md`, section "Registration").
* Names are normalised (invalid characters → `_`), `execute` is reserved for code
  mode (`tools.md:73`).
* **Scoped**: the newest active registration for a name wins; closing a
  registration removes only that one and reveals the previous one
  (`tools.md:77-83`).
* **Snapshot**: every model request captures the effective definitions **and**
  executors (`tools.md:129-144`). Later registration changes affect only later
  requests.
* **Execution chain** (`tools.md:131-138`): lookup → decode input → execute →
  encode output → normalise content → **truncate** content → `execute.after`
  hooks → one `ToolOutcome`.
* **Limits**: only text parts are measured; default = head+tail split with an
  omission marker in the middle; oversized content moves into **managed storage**
  and is replaced by a preview; if full retention fails, execution fails
  **operationally** (no lossy success) (`tools.md:152`).
* The **last** step keeps tool definitions with `toolChoice: "none"` so the cached
  prompt prefix stays warm (`tools.md:144`).
* Tool filtering is **catalogue visibility, not execution authorisation**
  (`packages/core/src/tool/AGENTS.md`, section "Permissions").

### 6.4 Built-ins

Verified in `packages/core/src/tool/plugin/`:

`read`, `write`, `edit`, `patch`, `grep`, `glob`, `shell`, `webfetch`, `websearch`,
`todowrite`, `question`, `skill`, `subagent` plus `file-diff`, `mcp-resource` and
`opencode` (meta-tools: `session_rename`, `session_move`, `worktree_create`,
`worktree_list`, `worktree_remove`, `models` – `opencode.ts:106-220`).
Registration of built-ins in `packages/core/src/plugin/internal.ts:228-256`.

`edit`, `write`, `patch` share the permission action **`edit`**
(`packages/core/src/tool/AGENTS.md`).

**Tool options** (`packages/schema/src/tool.ts:44-51`): `namespace`, `permission`,
`codemode`, `pinned`. `codemode: false` keeps the tool on the provider's native
tool list.

**Tool schema** (`packages/schema/src/tool.ts:87-105`): `name`, `input`,
`description`, `execute`, `output`, `options`. Schema → JSON schema in
`packages/core/src/tool/runtime.ts:22-28, 157-167`; execution (decode → error
hardening → output encode) in `runtime.ts:30-62`; effective name with namespace
`<namespace>_<tool>` (`runtime.ts:274-279`).

Example `read` (`tool/plugin/read.ts:29-70`): registered via
`ctx.tool.transform(editor => editor.add({...}))`, path authorisation via
`FileAccess.authorizeRead`, size/offset paging (default and cap 2000 lines),
image/PDF support, fallback to alternative paths.

### 6.5 MCP

`packages/core/src/mcp/` (`client.ts`, `index.ts`, `instructions.ts`, `oauth.ts`,
`stdio.ts`). MCP tools use the **same** tool structure with runtime JSON schema
(`specs/v2/tools.md:19`). MCP owns **one** stable tool transform that reads its
last-discovered tools; tool-list changes update the source and reload state
instead of re-registering at the end of the transform order
(`packages/core/src/tool/AGENTS.md`, section "Registration").

**Config schema** (`packages/schema/src/mcp.ts`):

| Type | Fields | Line |
|---|---|---|
| `LocalConfig` | `command`, `cwd`, `environment`, `codemode`, `timeout`, `protocol` | `:26-39` |
| `OAuthConfig` | – | `:41-51` |
| `RemoteConfig` | `url`, `headers`, `oauth` | `:53-64` |
| `Protocol` | `legacy \| auto \| 2026-07-28` | `:20-24` |

**Transports** (`packages/core/src/mcp/client.ts:145-283`): local → own
`McpStdio` spawn through the location `Environment` (`mcp/stdio.ts:45-115`, so MCP
servers run in the workspace), remote → `StreamableHTTPClientTransport`
(`:269-283`, with OAuth `loggedFetch` `:263-264`).

**Namespace & rights** (`packages/core/src/tool/mcp.ts`):
`namespace(server)` + `name(server, tool)` → `<server>_<tool>` (`:20-21`);
registration with `options: { namespace, codemode }` (`:46-119`);
`permission.assert` with `action = <server>_<tool>`, `resources: ["*"]` (`:56-68`).

**MCP instructions in the prompt** (`packages/core/src/mcp/instructions.ts`):
`<mcp_instructions>` block (`:17-29`), delta rendering on changes (`:31-60`);
visible only for servers whose tools are not `deny` (`:90-103`).

### 6.6 Skills

Skills are `SKILL.md`-based instruction packages (`packages/core/src/skill.ts`):

* Sources: `DirectorySource`, `UrlSource`, `EmbeddedSource` (`skill.ts:12-29`).
* **Availability is permission-gated**:
  `Skill.available(skills, permissions)` filters skills for which
  `Permission.evaluate("skill", skill.id, permissions).effect === "deny"`
  (`skill.ts:33-34`).
* An invoked skill is injected into model context as a `<skill_content>` block
  with content, base directory and sampled file list (`skill.ts:36-53`).
* The `skill` tool invokes skills; additionally there is the session operation
  `session.skill` (`packages/client/src/effect/api/api.ts:1331`).

### 6.7 Forms & follow-up questions (`question`)

The agent can ask **structured follow-up questions** mid-execution
(`packages/core/src/tool/plugin/question.ts`):

* `questions: Question.Prompt[]` (non-empty), answers as `answers`
  (`:23-31`)
* "Type your own answer" is **automatically** added; `multiple: true` allows
  multiple selection; a recommendation belongs as the first option with
  "(Recommended)" (`:12-21`)
* Cancellation by the user ⇒ `QuestionTool.CancelledError` (`:33-36`)

The general **Form API** (`packages/schema/src/form.ts`, `packages/core/src/form.ts`)
is more powerful: typed fields (`string` with `email|uri|date|date-time`, numbers,
boolean, select fields), `required`/`hidden`, conditional visibility via `when`
(AND-joined conditions on earlier fields, `form.ts:34-42`) plus **hidden fields**
for interactive authentication prompts (`:52-57`).
Retention: 10 minutes (`packages/core/src/form.ts:9`).

### 6.8 Tool limits (from the docs)

`services/www/src/docs/content/tools.mdx` lists the practical limits:

| Tool | Limit |
|---|---|
| `read` | 2,000 lines and 50 KiB per page; lines > 2,000 chars truncated; PNG/JPEG/GIF/WebP/PDF up to 20 MiB; other binaries fail |
| `glob` | default 100 files, timeout 30 s |
| `grep` | default 100 result lines, timeout 30 s |
| `shell` | default timeout 2 min (foreground); `background: true` ⇒ no timeout, notification on completion; large output truncated, full output in a managed file |
| `webfetch` | timeout up to 120 s; only HTTP/HTTPS, only textual responses |
| `patch` | only available for **supported GPT models**; other models get `edit` + `write` |

**Code mode (`execute`)**: the runtime has **no** direct filesystem access, **no**
imports and **no** timers; it provides only `fetch` – everything else goes through
tools in the catalogue (`tools.mdx:216-217`). A `deny` on `execute` with resource
`*` removes code mode **and** all tools reachable only through it (including MCP
tools from servers without `codemode: false`).

**`browser` namespace** (desktop app, code mode): tabs/navigation, snapshots,
search, click/form input, screenshots, file transfer, console/network inspection,
performance traces, heap inspection, Lighthouse audits. Upload paths are
**server-side**, file transfer capped at 5 MiB. Browser content is **untrusted
data**, not agent instructions; a `browser` deny with resource `*` removes the
catalogue without individual prompts (`tools.mdx:258-276`).

### 6.9 Code mode

`packages/core/src/codemode/` – instead of native tool calls the model gets a JS
API over the tools (`packages/core/src/codemode/tool.ts:245`). The outer code-mode
tool is built **per snapshot** and therefore is not registered
(`packages/core/src/plugin/tool-input-repair.ts:26`).

---

## 7. Permission system

### 7.1 Data model

`packages/schema/src/permission.ts`:

```ts
Rule    = { action: string, resource: string, effect: "allow" | "deny" | "ask" }
Request = { id, sessionID, action, resources[], save[]?, metadata?, source?, message? }
Reply   = "once" | "always" | "reject"
```

`action` is a **free string** (not a closed enum) – `action: Schema.String`
(`packages/schema/src/permission.ts:61`). `resource` is a wildcard pattern.
`evaluate` wins via `findLast` – the **last matching rule decides**
(`packages/core/src/permission.ts:87-97`); with no match the default is `ask`.

### 7.2 Wildcard syntax

`packages/core/src/util/wildcard.ts:3-13` – **not a glob**, but regex conversion:

| Pattern | Meaning |
|---|---|
| `*` | `.*` (anything) |
| `?` | `.` (one char) |
| all other chars | literal, escaped |
| ` *` at the end | `( .*)?` → matches the command **without** arguments too (`:11`) |
| backslashes | normalised to `/` (input **and** pattern) |
| case | case-insensitive **only on Windows** |
| matching | **anchored** (`^…$`) – matches the whole value |

Home expansion (`~`, `~/`, `$HOME`, `$HOME/`) applies only to path-based actions
`external_directory`, `read`, `edit` (`packages/core/src/config/plugin/agent.ts:141-162`);
shell resources stay raw command text.

### 7.3 Evaluation

`packages/core/src/permission.ts:158-189`:

1. Rules = `agent.permissions` **++** `session.permissions`
   (both missing → `[{action:"*",resource:"*",effect:"deny"}]`, `:19`)
2. **Deny short-circuit**: if any resource is `deny` → `deny` (`:165-167`)
3. Then: all rules **plus** saved grants (`:176`)
4. Effect = `ask` if any resource yields `ask`, otherwise `allow`
5. **Plugin hook** `permission.evaluate` may override effect and message
   (`:179-188`)

Flow in `assert` (`:235-267`):

* `deny` → `Permission.BlockedError` (with the relevant rules, `:242-247`)
* `allow` → OK immediately
* `ask` → create request, publish `permission.asked`, wait on `Deferred`
  (`:250-263`)

A **user reject** is deliberately tunnelled as a *defect* (`:252-257`) so it is
not turned into a model-facing tool error message; it reappears as a typed error in
`SessionModelRequest.executeTool`. A reject **with feedback**
(`CorrectedError`) stays typed and becomes `ToolFailure` – the model can then
continue.

### 7.4 Replies

`reply` (`permission.ts:269-329`):

* **`once`** → only this call is released
* **`always`** → stored in `permission` table **per project** (`project_id`,
  `action`, `resources` from `request.save`) and afterwards acts as an `allow`
  rule (`:303-309`, `:148-156`)
* **`reject`** → all **parallel** requests of the same session are rejected
  (batch feedback) (`:280-300`)

After `always`, all other pending requests are re-evaluated and – if now `allow` –
automatically released (`:312-326`).

> **Behaviour change vs. V1**: the V1 docs
> (`packages/web/src/content/docs/permissions.mdx:200`) say "for the rest of the
> current OpenCode session"; in V2 `always` is **project-wide persistent**
> (V2 docs `services/www/src/docs/content/permissions.mdx:272-292`).

**HTTP API** (`packages/protocol/src/groups/permission.ts:29-158`):
`session.permission.create|list|get|reply`, `permission.request.list`,
`permission.saved.list|update|remove`; handlers in
`packages/server/src/handlers/permission.ts`.

### 7.5 Permission sources (order/authority)

**Chain of rule sets** (order decides, since `findLast` wins):

1. **Agent defaults** (`packages/schema/src/agent.ts:46-51`)
2. **Global config** `permissions` (`packages/schema/src/config.ts:55-57`)
3. **Agent-specific** `permissions` (`packages/schema/src/config/agent.ts:21`,
   `config/plugin/agent.ts:84-92, 121-123`)
4. **Session** `session.permissions` (JSON column in `session_v2`,
   `session/sql.ts:52`, set via `session.setPermissions`, `session/session.ts:92-97`)
5. **Saved grants** (table `permission`, project-scoped)
6. **Enterprise policy** (`packages/core/src/managed-policy.ts`) – statements compiled
   by the **connected OpenCode console** for the authenticated user; they have
   **final authority** (`specs/v2/provider-policy.md`, `managed-policy.ts:32-60`)
7. **Plugin hook** `permission.evaluate` may override the result

**Deny takes precedence**: the deny check runs **before** saved evaluation
(`permission.ts:175`), i.e. a saved grant overrides a config `ask`, but **never**
a config `deny`. For multi-resource operations a single `deny` blocks; otherwise
`ask` wins over `allow` (`permission.ts:177-178`).

**"Always allow" pattern**: the `save` array comes from the **tool**, not the user –
`read`/`edit`/`webfetch`/`skill`/`subagent` suggest `["*"]`, `shell` suggests
concrete command prefixes (`packages/core/src/shell/parse.ts:197-198, 271-284`).
Stored is `resources: request.save` (`permission.ts:307`).

**Order of permission rules** (docs, `permissions.mdx:87-89`):
"Lower-priority configuration is loaded first, global rules are appended next, and
agent rules are appended last." – this matches the chain in section 7.5.

**`external_directory` is `allow` by default** – accesses outside the location and
project worktree produce **no** prompt as long as no rule changes that
(`permissions.mdx:142-145, 238-241`). This is an important detail: the sandbox
boundary is not active, only the rights check.

**`execute` action**: a `deny` on `execute` with resource `*` removes code mode
and tools reachable only through it; nested tools keep their own rules
(`permissions.mdx:125`).

**Action → resource mapping** (docs, `permissions.mdx:109-125`):

| Action | Resource |
|---|---|
| `read` | location-relative internal path or canonical absolute external path |
| `edit` | target path for `edit`, `write`, `patch` |
| `glob` | requested glob pattern |
| `grep` | the regex – **not** the search path |
| `shell` | scanner-generated command string; chained commands may yield several |
| `subagent` | target agent ID |
| `skill` | skill ID |
| `question` | `*` |
| `webfetch` | requested URL |
| `websearch` | search query |
| `external_directory` | canonical external directory boundary, usually ending in `/*` |
| `<server>_<tool>` | `*` for MCP tools; invalid chars in both names → `_` |
| `execute` | `*` |

Legacy V1 config (`permission`, singular, object syntax) and V2 (`permissions`,
array) are **migrated and merged** in `packages/core/src/config/normalize.ts:179-183`
(order `tools` → `permission` → `permissions`).

**Config locations** (`packages/core/src/config/discovery.ts`):

| Scope | Location |
|---|---|
| System | `/etc/opencode`, `/Library/Application Support/opencode`, `%ProgramData%\opencode` (`:28-32`) |
| macOS MDM | preferences `ai.opencode.managed` (`:35-40`) |
| Global | `~/.config/opencode` (`:68`) |
| Project | upwards `./settings.json(c)`, `.opencode/`, `.claude/`, `.agents/` (`:81-144`) |

**Agent definitions from markdown**: `{agent,agents}/**/*.md` → subagent,
`{mode,modes}/*.md` → primary (`packages/core/src/config/plugin/agent.ts:21-26`);
frontmatter `permissions:` (V2) resp. `permission:` (V1), migration in
`v1/config/migrate.ts:35-57`.

**Environment variables**: there is **no** env var for permissions themselves.
Relevant are `OPENCODE_PASSWORD` / `OPENCODE_SERVER_PASSWORD` (server auth,
`packages/cli/src/env.ts:10-13`) and the CLI flags `--auto` resp. (V1)
`--dangerously-skip-permissions`.

### 7.6 Enterprise / provider policy

**Policy statements** (`packages/schema/src/config/policy.ts`,
`packages/schema/src/config.ts:58-60`) – an ordered list:

```ts
interface PolicyInfo {
  effect: "allow" | "deny"
  action: "provider.use" | "tool.use" | "integration.use"
  resource: string        // wildcard pattern
}
```

**Resource syntax** (`specs/v2/provider-policy.md:9-21`):

| Action | Resource | Meaning |
|---|---|---|
| `provider.use` | `openai`, `company-ai` | provider ID |
| `tool.use` | `<permission-action>:<resource>`, e.g. `shell:sudo *`, `edit:*.env` | a permission check |
| `integration.use` | `mcp:<server-id>`, `plugin:<package or absolute path>`, `hosted:<integration-id>`, `skill:<skill-id>` | integration |

**Location in config**: **top-level `policies`**. `experimental.policies` is
**deprecated**, but still merged (`packages/schema/src/config/experimental.ts:23-25`,
`packages/core/src/config/normalize.ts:428-445`). Merge order within a document:
`[...generated, ...experimental, ...native]` – later win (`:445`). V1 lists
`enabled_providers`/`disabled_providers` are automatically translated into
`provider.use` statements (`:409-427`).

> **Note**: `specs/v2/provider-policy.md` still shows `experimental.policies` in
> its JSON examples – that is the **old** location. The spec is out of date here
> compared to the schema.

**Evaluation**:

* `action` is a **closed set**; invalid statements are discarded with a diagnostic
  at normalisation (`specs/v2/provider-policy.md:70`).
* **Order**: matching statements are applied in order, **last match wins**
  (`:37`, `:87-106`). No pattern-specific precedence – a specific resource does
  **not** automatically beat a wildcard (`:85`).
* **Precedence** (6 levels, `services/www/src/docs/content/policies.mdx:178-191`):

| # | Source |
|---|---|
| 1 (highest) | Connected OpenCode console workspace |
| 2 | macOS managed preferences (`/Library/Managed Preferences/ai.opencode.managed.plist`) |
| 3 | System `settings.json(c)` (`/Library/Application Support/opencode`, `/etc/opencode`, `%ProgramData%\opencode`) |
| 4 | Global `~/.config/opencode/settings.json(c)` |
| 5 | Direct `settings.json(c)`; **outer** directories beat inner |
| 6 (lowest) | `.opencode/settings.json(c)`; outer beat inner |

Within a file the written order remains; **within a document** merge is
`[...generated, ...experimental, ...native]`
(`packages/core/src/config/normalize.ts:445`).
* A `tool.use` statement **never grants** access – permissions and saved grants
  still decide between `allow` and `ask` (`:44`, `:252`). A statement `deny`
  **overrides** instead, even saved "allow always" grants (`:263-265`).
* `provider.use` is interpreted **after** all other catalogue transforms,
  `tool.use` **after** every other permission-evaluation hook (`:70`).
* Denial texts: `Blocked by configuration policy` (authored) resp.
  `Blocked by <organization>'s policy` / `Blocked by your organization's policy`
  (org-managed) – `packages/core/src/managed-policy.ts:56-62`,
  `specs/v2/provider-policy.md:240`.

**The policy plugin** (`packages/core/src/config/plugin/policy.ts`, ID
`opencode.config.policy`) has exactly four intervention points:

| Place | Effect | Line |
|---|---|---|
| `ctx.provider.transform` | Providers with `provider.use` deny are **removed** (debug log only for explicitly enabled) | `:28-41` |
| `ctx.mcp.transform` | MCP servers with `integration.use`/`mcp:<name>` deny are removed | `:42-47` |
| `ctx.skill.transform` | Skills with `integration.use`/`skill:<id>` deny are removed | `:48-53` |
| `ctx.permission.hook("evaluate")` | `tool.use` against `${action}:${resource}` → `effect = "deny"` + message | `:54-69` |

Reload on config change and on `managed.changes()` (`:16-23`).
Helper: `ManagedPolicy.decision(policies, action, resource)` – default `allow`
(`managed-policy.ts:66-75`).

**Console delivery** (`specs/v2/provider-policy.md:202-248`):

* The console compiles the workspace's provider/tool policies and delivers them
  for the authenticated caller via `GET /api/v2/config` **together with** the
  managed providers.
* `experimental` is omitted when the caller has no statements; order is
  significant; the client **never sorts, deduplicates or normalises**
  (`:227-228`).
* Every console request carries the `User-Agent` `opencode/<channel>/<version>/<app>`
  (`:229`).
* `ManagedPolicy` (`packages/core/src/managed-policy.ts`) is the **process-global**
  store; the console plugin (`opencode.provider.opencode`) writes it on every
  snapshot (`:230`).
* Statements follow the **connection**: credential change replaces them, disconnect
  or 404 clears them; statements from different connections are never merged
  (`:231`). Freshness = snapshot freshness (next poll ~1 minute or credential
  change) (`:232`).
* **Failure case**: a failed fetch/refresh **keeps** the last config (`:236`) –
  deliberately "fail open" for policy, because personal credentials would keep
  working while organisation restrictions disappear. There is **no** persistent
  offline cache.
* **Protection**: plugins may **not** add, remove or override policy statements;
  config `remove` operations ignore `opencode.config.policy` and
  `opencode.provider.opencode` (`:242-246`).
* **Boundary**: provider policy is **not** a sandbox for executable plugins
  (`:248`, `packages/core/src/plugin/internal.ts:277-279`).

### 7.7 Action names (evidence from code)

Verified built-in actions: `read`, `edit` (also for `write`/`patch` and
`worktree_remove`), `glob`, `grep`, `shell`, `subagent`, `skill`, `question`,
`webfetch`, `websearch`, `external_directory`, `execute` (code mode),
`opencode_list_mcp_resources` / `opencode_read_mcp_resource` plus MCP tools as
`<server>_<tool>`.

* `external_directory`: `packages/core/src/file-access.ts:25-31, 116-121, 135-141`
* `execute`: `packages/core/src/tool.ts:239`
* Tool names = action names: `packages/core/src/tool/plugin/*.ts` (`export const name`)

**V1→V2 rename** (`packages/core/src/v1/config/migrate.ts:28-33`):
`write|patch → edit`, `task → subagent`, `bash → shell`.
`doom_loop` and `lsp` are **no longer V2 core actions** – they exist only in
legacy config (`packages/core/src/v1/config/permission.ts:27`).

**Default rules per agent** (`packages/schema/src/agent.ts:46-51`):
`*:*=allow`, `read *.env=ask`, `read *.env.*=ask`, `read *.env.example=allow`.

### 7.8 Further security aspects

> **Important:** there is **no sandbox**. `SECURITY.md:15-19`:
> *"OpenCode does **not** sandbox the agent. The permission system exists as a UX
> feature … not designed to provide security isolation."*

* **Path sandboxing**: "internal" = inside `location.directory` **or** the
  non-root project worktree (`packages/core/src/file-access.ts:93-123`).
  Everything else is "external" ⇒ the `external_directory` check runs **before**
  the `read`/`edit` check (`:152-159`). The directory checkout is formed **per
  directory**: `resource = <dir>/*`, `save = <project root>/*` (`:116-121`).
* **Shell**: checks cwd + directories inferred by the scanner
  (`packages/core/src/tool/plugin/shell.ts:130-136`).
* **Secrets/env**: `.env` files are `ask` by default (base rule). Credentials
  live in the DB (`packages/core/src/credential.ts`), are redacted when the model
  is shared (`packages/core/src/model-resolver.ts:224, 261-262`).
  Session transfer redacts content (`packages/core/src/session/transfer.ts:171-330`).
  The server password is **never** inherited by tools
  (`packages/cli/src/server-process.ts:86-89`).
* **Network/fetch**: `webfetch` checks only `http:`/`https:`
  (`tool/plugin/webfetch.ts:71-73, 111-114`) plus permission check on the URL
  (`:116-124`) – **no** domain allowlist, no egress filter.
* **Plugin/MCP trust**: MCP tools get permission action `<server>_<tool>` with
  `resources: ["*"]` (`packages/core/src/tool/mcp.ts:56-68`).
  `SECURITY.md:32`: *"External MCP servers you configure are outside our trust
  boundary."* Repo config may **not** disable policy enforcement and the console
  connection (`guarded` set in `packages/core/src/plugin/internal.ts:277-279`).
* **Server auth** (`packages/server/src/auth.ts`): HTTP basic, user always `opencode`
  (`:20`), session token = `expires.HMAC-SHA256(password)` (`:39-55`,
  `timingSafeEqual`), TTL 30 days (`:16`), cookie name with port (`:57-61`).
  Middleware `packages/server/src/middleware/authorization.ts:58-100`: basic auth
  or `?auth_token=`; session cookie same-origin only (`:64-71`); PTY-ticket and
  pairing paths are exempt (`:86-91`). Starting without a password ⇒ warning, the
  password is randomly generated (`packages/cli/src/server-process.ts:90-96`).
* **Audit**: there is **no dedicated permission audit log** – `permission.asked`
  and `permission.replied` are `ephemeral` and do not land in the durable event
  log. Only `PermissionSaved` rows are stored.

### 7.9 Permission UI

| Client | Location |
|---|---|
| **TUI (main)** | `packages/tui/src/routes/session/permission.tsx` – `PermissionPrompt`: header "Permission required", diff view for `edit`, options once/always/reject; `RejectPrompt` allows feedback |
| **TUI (mini)** | `packages/tui/src/mini/footer.permission.tsx` (state machine `permission → always → reject`), logic `packages/tui/src/mini/permission.shared.ts:6-46` |
| **TUI (auto mode)** | `packages/tui/src/context/permission.tsx:5-17` (`"prompt" \| "autoaccept"`, CLI `--auto` overrides config), auto-reply `packages/tui/src/routes/session/index.tsx:266-283` |
| **TUI (display)** | `packages/tui/src/util/permission.ts:23-168` – `permissionPresentation` per action, `permissionAlwaysLines`, `permissionOptionLabel` |
| **App (web)** | `packages/app/src/session/requests/session-permission-dock.tsx` – dock prompt with deny / allow always / allow once; auto-approve client-local per project (`packages/app/src/session/requests/auto-approve.ts`, `settings/workspaces/project.tsx:209-232`) |
| **Desktop** | no own dialog, uses the web UI; renderer browser permissions hard-limited (`packages/desktop/src/main/windows/security.ts:10-16`) |
| **ACP (editor)** | `packages/cli/src/acp/permission.ts` – maps to ACP `requestPermission` with `once/always/reject` (`:39-43`), diff previews (`:83-167`) |
| **CLI (non-interactive)** | `packages/cli/src/run/noninteractive.ts:158-186` – without `--auto` automatically `reject` (with feedback so the model continues), with `--auto` `once` |

### 7.10 OpenCode console (enterprise cloud)

`packages/console` (SolidStart) + `services/www/src/docs/content/console/*`:

| Area | Content |
|---|---|
| **Workspaces** | Personal workspace (created automatically on registration) or team workspace; holds members, credits, budgets, providers, policies, service accounts |
| **Members** | Invite, roles (`owner`/`member`), workspace switch |
| **SSO / SCIM** | Enterprise login (SAML/OIDC) and user provisioning |
| **Budgets / billing** | Spend limits per member, credit top-up, invoices |
| **Providers** | Connect own provider accounts via the console gateway (`/inference/custom/...`) |
| **Inference** | proprietary **and** open-source models (`/inference/...`) |
| **Policies** | Provider/tool rules → delivered as `policy` statements to every client, **final authority**, effective within ~1 minute |
| **Web search** | Console's own web search provider |
| **Go / Go Plus** | **$10** / **$40** monthly subscription for open-source models |
| **API** | `https://opencode.ai/console/api/v1/...`; auth via bearer token with **personal API key** (`oc_sk_…`) or **service-account key**; endpoints: budgets, inference, providers |
| **Harnesses** | Claude Code (Anthropic messages models), Codex (OpenAI responses models), Hermes, Pi, Raycast (all models) – usage billed to the workspace |

The client delivers its statements via `GET /api/v2/config` together with the
managed providers (section 7.6).

---

## 8. Providers & models

* `packages/ai` (2,202 graph nodes) contains the **protocol layer**:
  `src/llm.ts:39-192` (`LLM.request/generate/stream`, `generateObject`),
  `src/schema/messages.ts:288-359` (`ToolDefinition`, `ToolNamespace`, `ToolEntry`),
  `src/route/` (client, auth, executor, HTTP+WebSocket transport),
  `src/protocols/` (anthropic-messages, openai-*, gemini, bedrock-converse, …),
  `src/providers/` (concrete provider entrypoints),
  `src/provider-error.ts` (`isContextOverflow`, `isRetryable`),
  plus `cache-policy.ts`, `effort-updates.ts`, `generation.ts`, `image.ts`.
* **Core orchestrates**: `packages/core/src/provider.ts` (provider registry
  `:48-180`, built-in packages `@opencode/ai/providers/*` + provider plugins in
  `packages/core/src/plugin/provider/`), `model.ts` (model registry `:74-80, 181-235`),
  `model-resolver.ts` (variants/overlays `:137-273`),
  `models-dev.ts` + `plugin/models-dev.ts` (catalogue snapshot → providers+models,
  deprecated/profile filters `:11-31, 86-107`).
* **Resolution per step** (`packages/core/src/session/runner/model.ts:70-96`):
  no session model → `resolver.resolve()` (global default), otherwise
  `ModelNotSelectedError` (`:78-80`); session model not in catalogue →
  `ModelUnavailableError` (`:85-89`); otherwise `resolver.resolveModel(selected, variant)` (`:90`).
* **No model rotation "on failure"** – fallbacks concern title/helper models
  (small model, `packages/core/src/session/context.ts:94-124, 191-203`) and the
  output budget (`model-request.ts:87-97`).
* Retry policy: `packages/core/src/model-retry.ts`; step logic:
  `packages/core/src/session/runner/step.ts`, `runner/llm.ts` (17 KB),
  `runner/model.ts`, `runner/retry.ts`.
* The runner produces **one** `llm.stream` call per step; no in-memory tool loop
  (`AGENTS.md`, section "Session Core").

---

## 9. Interfaces

### 9.1 HTTP API

Assembled in `packages/protocol/src/api.ts` from **31 endpoint groups**
(`packages/protocol/src/groups/`): `session` (34 KB!), `message`, `permission`,
`agent`, `model`, `provider`, `mcp`, `project`, `worktree`, `vcs`, `fs`, `shell`,
`pty`, `persistent-pty`, `plugin`, `skill`, `command`, `reference`, `config`,
`location`, `form`, `websearch`, `integration`, `credential`, `event`, `generate`,
`voice`, `rpc`, `server`, `debug`, `migration`.

**Scope**: the current `packages/protocol/openapi.json` contains **146 operations**
over 121 paths (30 tags, counted by hand). `V2_HTTP_API_AUDIT.md:3-5` still
says "139" (as of 2026-09-13) – the audit document has not been updated. The audit
is a checklist with dispositions Keep/Change/Remove/Experimental-only
(`V2_HTTP_API_AUDIT.md:16-27`) and treats the API in **10 groups**
(`:29-48`); among others `POST /api/plugin/await-activation` was removed
(`:44-47`), `/api/health` + `/api/server` merged into `GET /api/info`
(`:57-62`), location wrappers reduced to `{ directory }` (`:50-53`) and
names normalised (`session.message.list`, `session.inbox.update`, `continue` →
`resume`, `permission.reply` → `decision`).

* Two middleware levels: **location** middleware (project/directory context) and
  **session-location** middleware; the permission group uses both
  (`protocol/src/api.ts:38-106`).
* Public route examples (from graph + filesystem):
  `/api/session/{}/permission`, `/api/permission/request`, `/api/permission/saved`,
  `/api/agent`, `/api/project`, `/api/worktree`, `/api/mcp/*`, `/api/plugin`,
  `/api/provider`, `/api/location`, `/api/form`, `/api/skill`, `/api/websearch`,
  `/api/pty*`, `/api/share/:shareID`, `/api/experimental/*`, `/session/:sessionID`,
  `/session/:sessionID/interrupt`, `/session/:sessionID/configure`,
  `/api/rpc/:rpcID/:method`, `/openapi.json`.
* The generated client lives in `packages/client` (Promise and Effect variants);
  run `bun run generate` in `packages/client` after protocol changes (`AGENTS.md`).
* `packages/server/src/routes.ts:144-193` shows the build: build layer graph →
  `HttpApiBuilder.layer(Api, {openapiPath:"/openapi.json"})` → middleware
  (authorization, schema error, form location, session location) → `HttpRouter`.
* `packages/core/src/rpc.ts` additionally provides an **RPC** model
  (`/api/rpc/:rpcID/:method`) with its own event pub/sub.

### 9.2 Session API (public operations)

The generated client interface lists the session operations completely
(`packages/client/src/effect/api/api.ts:1315-1369`):

| Group | Operations |
|---|---|
| Base | `list`, `stats`, `create`, `import`, `export`, `active`, `get`, `remove`, `fork` |
| Selection | `switchAgent`, `switchModel`, `update`, `move` |
| Prompt | `prompt`, `command`, `skill`, `synthetic`, `shell` |
| Lifecycle | `compact`, `wait`, `interrupt`, `background`, `view` |
| Revert | `revert.stage`, `revert.clear`, `revert.commit` |
| Inbox | `inbox.list`, `inbox.cancel`, `inbox.update` |
| Instructions | `instructions.entry.list/put/remove` |
| Read | `context`, `diff`, `log`, `message.get`, `environment` |
| Forms | `form.list/create/get/reply/cancel` |

**Message types** (`api.ts:1377-1388`): `agent-switched`, `model-switched`,
`location-switched`, `user`, `synthetic`, `system`, `skill`, `shell`, `assistant`,
`compaction`, `idle`.

### 9.3 Event feed (SSE)

Architecture decision in `specs/v2/event-stream-architecture.md`:

```
Core EventV2.listen()
          │  one global subscription
          ▼
Server EventFeed
  public filter → schema encode → JSON encode → SSE frame once
          │  nonblocking offer of one shared immutable string
          ▼
  Queue A   Queue B   Queue C
    │         │         │
 HTTP A    HTTP B    HTTP C
```

* **Ownership**: Core owns event semantics, publication, persistence, replay and
  transactional projection. Server owns public event selection, wire encoding and
  bounded delivery. Protocol owns the `OpenCodeEvent` SSE contract
  (`event-stream-architecture.md:25-29`).
* **Goal**: "encode once" – schema/JSON/SSE encoding runs **once** per event, not
  once per connection. Previously it ran N times (`:31-45`).
* **Delivery law**: every connection has its **own finite lag budget**;
  exceeding it ends **only that** connection, publication and healthy connections
  continue (`:47-51`).
* **Capacity**: `Queue.dropping` with 4,096 accepted public frames per connection
  (`:53`). On overflow: queue removed from the registry immediately, failed with
  `SubscriberOverflowError`, the frame still goes to all others (`:55-62`).
* Internal core events, `server.connected` and heartbeats consume **no** capacity
  (`:64`).
* The feed is **global, not location-bound**: the endpoint sits outside the
  location middleware; the TUI uses event location metadata to serve multiple
  locations (`:45`).
* **Encoding errors**: log ID/type/cause → **all** current queues fail with
  `EncodingError` → skip the defective event → feed stays available for later
  connections (`:157-166`).
* **Lifecycle**: `EventFeed.layer` is built once with the server handler graph,
  registers **one** global core listener **synchronously** before releasing the
  service (`:126-134`). Per connection: allocate queue → register synchronously →
  `Stream.fromQueue` → remove on request-scope close (`:136-143`). With no HTTP
  subscribers the observer returns **before** encoding – idle servers pay no
  serialisation cost (`:134`).
* **Heartbeat**: 15 s, connection-local, outside the feed (`:155, 179`).
* **Benchmark** (Bun 1.3.14, Apple Silicon, 8 KiB event): 1 client +0.7%,
  10 clients −89.3%, 50 clients −97.8% median time (`:203-210`).
* **No wire change**: method, path, schema and wire representation stay the same
  ⇒ no OpenAPI change, no client regeneration, TUI behaviour unchanged
  (`:168-191`).

Implementation: `packages/server/src/event-feed.ts` +
`packages/server/src/handlers/event.ts`, core bus `packages/core/src/bus.ts`
(39.6 KB, durable event persistence). `Bus.configured({persist})` controls
whether events are stored durably (`packages/server/src/routes.ts:119`).

### 9.4 Catalog/config/plugin lifecycle (historical decision)

`specs/v2/catalog-config-plugin-lifecycle.md` is a **historical decision
protocol**: option B – *replayable, location-scoped catalogue transforms* – was
chosen and implemented (`:3`).

**Option A (rejected)**: config transforms that may mutate **every** config
field. Consequence: no granular reload possible – every change would have to
reload **all** config-dependent services (`Policy`, `Catalog`, `Agent`, `MCP`, …)
(`:18-51`, `:186`).

**Option B (chosen)**: plugins register replayable **catalogue** transforms;
`Catalog` materialises visible records from the active transforms anew
(`:191-210`). Rebuild flow:

```
transform(update) → apply active transforms in registration order
                   → apply policy (last)
                   → commit diff → Catalog.Event.Updated → Frontend.refetchCatalog()
```

* The **mutable draft** is catalogue state, not config state (`:319`).
* **Auth need not be modelled as config** (`:317`).
* Policy is applied **last** on every rebuild (`:310`).
* A rebuild produces **at most one** `Catalog.Event.Updated` (`:322`).
* Delayed plugin activation does not block location readiness (`:214-240`).

Current behaviour belongs to core per the spec (`packages/core/src/state.ts`,
`packages/core/src/plugin/internal.ts`, `packages/core/src/config/plugin/*`).

### 9.5 SDK

`packages/sdk` composes client + core + server; `sdks/vscode` is the editor
integration; `packages/cli/src/acp/` implements the **Agent Client Protocol**
(Zed integration).

---

## 10. Configuration

`packages/schema/src/config.ts:26-114` defines the config schema (`Config.Info`):

`shell`, `model`, `default_agent`, `update` (`disable|notify|auto`), `share`
(`manual|auto|disabled`), `enterprise.url`, `username`, **`permissions`** (ordered
rules), **`policies`** (provider/tool/integration access), `agents`, `snapshots`,
`watcher`, `formatter`, `lsp`, `media`, `tool_output` (truncation thresholds), `mcp`,
`compaction`, `skills`, `commands`, `instructions`, `references`, `websearch`,
`plugins`, `worktree`, `warming`, `providers`, `experimental`.

**`experimental`** (`packages/schema/src/config/experimental.ts`):

| Field | Default | Meaning |
|---|---|---|
| `portable_shell_scanner` | `false` | portable shell permission scanner (`packages/core/src/tool/plugin/shell.ts:127`) |
| `todo` | `false` | **the `todowrite` tool is opt-in** – only registered when `experimental.todo === true` (`packages/core/src/tool/plugin/todo.ts:50-55`) |
| `subagent_depth` | `1` | maximum subagent nesting |
| `location_idle_timeout` | `"60 minutes"` | idle timeout before location services are unloaded (`false` = never) – evaluated in `packages/core/src/location-activity.ts:45-47` |
| `policies` | – | **deprecated**, merged into top-level `policies` |

**Config locations and precedence** (`services/www/src/docs/content/config.mdx:28-109`):

| Level | Location |
|---|---|
| macOS managed preferences | `/Library/Managed Preferences/[<user>/]ai.opencode.managed.plist` |
| System | `/Library/Application Support/opencode`, `/etc/opencode`, `%ProgramData%\opencode` |
| Global | `~/.config/opencode/settings.json(c)` |
| Project (direct) | `settings.json(c)` upwards to filesystem root |
| Project (`.opencode/`) | `.opencode/settings.json(c)` upwards |

Merge rule: **first** all direct `settings.json(c)` files **from outside in**,
**then** all `.opencode/` files in the same order – "every discovered `.opencode`
config overrides every direct config" (`config.mdx:78-82`). Precedence example
(`:98-107`): `~/.config/opencode` < `acme/settings.json` <
`acme/packages/web/settings.json` < `/etc/opencode`.

The system folder additionally loads `agents/`, `commands/`, `skills/`, `plugins/`
for all users of the machine (`:42-48`). Managed preferences have the **highest**
precedence (`:61-63`).

**Variable substitution** (`config.mdx:560-608`): `{env:VARIABLE}` and
`{file:path}` (relative to the config file, absolute or `~/`). Unset variables
become **empty string**.

**Further settings** (selection, defaults per docs):

| Field | Default |
|---|---|
| `update` | `"notify"` (`"disable" \| "notify" \| "auto"`); auto-install does not restart the server |
| `username` | – (accepted but not displayed) |
| `snapshots` | on (filesystem snapshots for undo/revert) |
| `media.image` | `auto_resize`, `max_width/height: 2000`, `max_base64_bytes: 5242880` |
| `tool_output` | `max_lines: 2000`, `max_bytes: 51200` |
| `compaction` | `auto: true`, `keep.tokens: 15000`, `buffer: 15% of limit (min. 16k)` |
| `warming` | **off**; object form: `interval: "4 minutes"`, `duration: "30 minutes"` |
| `worktree.directory` | server data directory `worktree/<projectID[:6]>` |
| `websearch.provider` | – (`"random"` selects automatically) |

**Env variables** (not in config, but relevant):
`OPENCODE_DISABLE_PROJECT_CONFIG=1` (skips project `AGENTS.md` discovery, but not
the global file), `OPENCODE_DB`, `OPENCODE_PASSWORD` /
`OPENCODE_SERVER_PASSWORD`.

Project-local extensions in the repo itself: `.opencode/tool/*.ts` (custom tools),
`.opencode/command/*.md` (slash commands), `.opencode/themes/*.json`,
`.opencode/settings.jsonc`, `.opencode/plugins/`.

---

## 11. Documentation (in the repo)

The **user-facing documentation** lives in `services/www/src/docs/`
(Astro/Starlight, ~640 KB MDX) and is organised into **5 sections**
(`services/www/src/docs/lib/navigation.ts:18-177`):

| Section | Content |
|---|---|
| **Docs** (21 pages) | Intro, config, migrate from V1, troubleshooting, agents, models, skills, themes, commands, plugins, providers, websearch, network, snapshots, compaction, formatters, references, attachments, tools, MCP servers, permissions, policies, instructions, warming |
| **CLI** (10) | Intro, TUI, settings, web, providers, commands, ACP, theme, plugins, keybinds |
| **Build** (9) | Intro, plugins (Effect + RPC + CLI + migrate V1), client (JS + Effect), SDK (JS + Effect + Cloudflare) |
| **API** | OpenAPI reference (`HttpApiSchema.StreamSse({ data: OpenCodeEvent })`) |
| **Console** (18) | Intro, models, providers, web search, Go, workspaces, members, SSO, SCIM, budgets, billing, API (overview/inference/providers/budgets), harnesses (Claude Code, Codex, Hermes, Pi, Raycast) |

The docs are the **best non-code source** for behaviour. Individual reference
pages: `permissions.mdx`, `policies.mdx`, `instructions.mdx`, `agents.mdx`,
`tools.mdx`, `config.mdx`, `compaction.mdx`, `mcp-servers.mdx`, `skills.mdx`,
`commands.mdx`, `providers.mdx`, `models.mdx`, `snapshots.mdx`, `warming.mdx`,
`network.mdx`, `references.mdx`, `attachments.mdx`, `formatters.mdx`, `themes.mdx`,
`plugins.mdx`, `websearch.mdx`, `migrate-v1.mdx` (19 KB), `troubleshooting.mdx`.

Rules for this directory (`services/www/src/docs/AGENTS.md`): `lib/navigation.ts`
is the source of truth for order; short sections each with one example; run
`bun typecheck` + `bun run build` from `services/www` after changes.

Additionally: `SECURITY.md` (trust boundaries, no sandbox),
`V2_HTTP_API_AUDIT.md` (API checklist), `specs/v2/*` (functional contracts),
`packages/*/AGENTS.md` (package guides), `packages/core/src/tool/AGENTS.md`,
`packages/schema/AGENTS.md`, `packages/plugin/AGENTS.md`.

## 12. Development & operations

* **Checks**: `bun run check` in repo root (canonical lint + typecheck),
  `bun typecheck` in the respective package; `bun run lint`, `bun run lint:effect-patterns`.
* **Tests**: not from repo root (`do-not-run-tests-from-root`), but per package,
  e.g. `packages/core`. Skills: `.opencode/skills/{unit-tests,tui-tests,app-e2e-tests,component-tests}`.
* **Dev**: `bun run dev`, `bun run dev:live` (TUI against the running
  `opencode` service), `dev:vite`, `dev:desktop`, `dev:web`, `dev:console`, `dev:stats`,
  `dev:www`, `dev:storybook`.
* **Branches**: max. 3 words, no slashes/prefixes; base `origin/dev`, PR target
  `dev` (`AGENTS.md`).
* **Commits**: Conventional Commits `type(scope): summary`.
* **No changesets** in this repo.
* **Deployment**: SST (`sst.config.ts`, `infra/`), `services/www` on Cloudflare
  (wrangler), `packages/console` as enterprise console.
* **Release**: `script/` + GitHub workflows (`.github/workflows/publish.yml`).

---

## 13. Open points / next steps

This overview is a **first** version (as of 2026-10-21). Still to deepen:

1. **Code-mode details** (interpreter, catalogue, sandbox) – `packages/codemode`
2. **Shell/PTY semantics** in detail (pty vs. persistent-pty, `packages/core/src/pty`)
3. **Enterprise path** (`packages/console`, `managed-policy.ts`, console config fetch
   `/api/v2/config`, SSO flow) – enterprise logic lives in core, not the console
4. **Simulation/test harness** (`packages/simulation`, `packages/http-recorder`)
5. **V1→V2 migration path** in detail (`v1-migration.bun.ts`, `migrate-v1.mdx`)
6. **Quantitative metrics**: tool-call distribution, token cost per session
   (`packages/core/src/session/stats.ts`, `packages/stats`)
7. **Known legacy / loose ends** (from the analysis):
   * `session_pending` looks like a legacy item – the active path is `session_inbox`
     (migration `20260812181746_session_inbox.ts`)
   * `SessionStatusEvent` (`packages/schema/src/session-status-event.ts`) is
     registered in the manifest but **not** published by core
   * There is **no UI** to view or revoke saved project grants
     (`permission.saved.*`) – only API/DB
   * `packages/effect-drizzle-sqlite` contains no `src/` (only `examples/`)

---

## Appendix: evidence overview

| Topic | Primary source |
|---|---|
| Session contract | `specs/v2/session.md` |
| Tool contract | `specs/v2/tools.md` |
| Provider/policy decision | `specs/v2/provider-policy.md` |
| Event architecture decision | `specs/v2/event-stream-architecture.md` |
| Agents & rights | `packages/core/src/plugin/agent.ts`, `plugin/plan.ts` |
| Permission engine | `packages/core/src/permission.ts`, `packages/schema/src/permission.ts` |
| Policy (enterprise) | `packages/core/src/managed-policy.ts`, `specs/v2/provider-policy.md` |
| Tool registry | `packages/core/src/tool.ts`, `packages/core/src/tool/AGENTS.md` |
| DB schema | `packages/core/src/*/sql.ts`, `packages/core/src/database/` |
| HTTP API | `packages/protocol/src/api.ts`, `packages/server/src/routes.ts`, `V2_HTTP_API_AUDIT.md` |
| System prompt | `packages/core/src/session/runner/prompt/system.txt`, `session/system-prompt.ts` |
| Instruction discovery | `packages/core/src/config/plugin/instruction.ts`, `core/src/instructions/` |
| Hooks/plugin API | `packages/core/src/plugin/hooks.ts`, `packages/plugin/src/effect/*` |
| Configuration | `packages/schema/src/config.ts`, `packages/core/src/config/normalize.ts` |
| Security boundaries | `SECURITY.md` |
| User docs | `services/www/src/docs/content/*.mdx` |