# Packages – Overview of `packages/`

> **As of:** 2026-10-21, repo state `dev` @ `3108505b2f`
> **Method:** Each folder under `packages/` was checked individually: `package.json`
> (name, version, public/private, exports, scripts, workspace deps), directory
> structure, `src/` contents and any READMEs.
> **Conventions:** `✅` = publicly publishable, `🔒` = `private: true`,
> `📦` = container folder without its own `package.json` (contains several packages).

---

## At a glance

| Folder | Package name | Type | In one sentence |
|---|---|---|---|
| `ai` | `@opencode/ai` | ✅ | Schema-based Effect APIs for text, image, video, speech and transcription + all provider protocols |
| `app` | `@opencode/app` | ✅ | SolidJS web/PWA surface (product UI) with composer, session timeline, settings, workspaces |
| `cli` | `@opencode/cli` | ✅ | CLI binaries `opencode`/`opencode2`, TUI start, background service, `run`, `serve`, ACP |
| `client` | `@opencode/client` | ✅ | Generated HTTP clients (Promise, Effect, Solid, Service) from the `HttpApi` |
| `codemode` | `@opencode/codemode` | ✅ | Restricted JS-like interpreter over which the agent combines tools |
| `console` | – | 📦 | Enterprise console: SolidStart app (`app/`) + Cloudflare resources (`resource/`) |
| `containers` | – | 📦 | Pre-built CI container images for GitHub Actions |
| `core` | `@opencode/core` | ✅ | The domain logic: sessions, execution, tools, permissions, provider, DB, MCP, shell, VCS |
| `desktop` | `@opencode/desktop` | 🔒 | Electron desktop app (beta) |
| `effect-drizzle-sqlite` | – | – | Remnants of a Drizzle/SQLite binding for Effect v4 (only `examples/`) |
| `function` | `@opencode/function` | 🔒 | Cloudflare worker functions (GitHub App token exchange, share) |
| `gui-extensions` | `@opencode/gui-extensions` | 🔒 | Extension SDK + bundled desktop/web extensions |
| `http-recorder` | `@opencode/http-recorder` | ✅ | Record and replay Effect HTTP/WS traffic as cassettes |
| `httpapi-codegen` | `@opencode/httpapi-codegen` | 🔒 | Build-time generator for Promise/Effect clients from `HttpApi` |
| `identity` | – | – | Brand assets only (logos, favicons) |
| `latex` | `@opencode/latex` | 🔒 | LaTeX rendering (markdown → PDF) as agent plugin |
| `merman` | `@opencode/merman` | 🔒 | Diagram rendering (flowchart, Gantt, sequence, gitgraph, timeline) |
| `plugin` | `@opencode/plugin` | ✅ | Author APIs for plugins (Promise and Effect variants), host and loader support |
| `plugin-browser` | `@opencode/plugin-browser` | ✅ | Desktop browser as code-mode plugin (CDP, tunnel, tools) |
| `plugins` | – | 📦 | Container for official, individually installable plugins (currently `figma`) |
| `posts` | `@opencode/posts` | 🔒 | Private Astro blog / casts |
| `protocol` | `@opencode/protocol` | ✅ | `HttpApi` endpoint definitions (31 groups) + `openapi.json` |
| `schema` | `@opencode/schema` | ✅ | Public wire and storage contracts, IDs, durable event payloads |
| `script` | `@opencode/script` | ✅ | Script helper library (syntax highlighting) |
| `sdk` | `@opencode/sdk` | ✅ | In-process host: embed OpenCode in memory (Promise, Effect, workerd) |
| `server` | `@opencode/server` | ✅ | HTTP server: route assembly, 34 handlers, auth, CORS, SSE feed |
| `session-ui` | `@opencode/session-ui` | 🔒 | Reusable session components (timeline, diff, review, comments) |
| `simulation` | `@opencode/simulation` | ✅ | Session recording and replay (backend, frontend, protocol) |
| `stats` | – | 📦 | Stats portal: `app/` (SolidStart), `core/` (runtime/DB), `server/` (lambdas) |
| `storybook` | `@opencode/storybook` | 🔒 | Storybook wrapper for component stories |
| `theme` | `@opencode/theme` | ✅ | TUI themes (`./tui`, `./tui/v1`) |
| `tui` | `@opencode/tui` | 🔒 | Terminal UI on OpenTUI + Solid (main TUI and mini TUI) |
| `ui` | `@opencode/ui` | ✅ | Design system: components, icons, tokens, i18n, Tailwind setup |
| `util` | `@opencode/util` | ✅ | Cross-cutting utilities (paths, FS, hash, patch, Effect layer helpers) |
| `web` | `@opencode/web` | ✅ | Astro/Starlight documentation and marketing website |

---

## Details per folder

### `ai` – `@opencode/ai` ✅

*Schema-first Effect APIs for text, image, video, speech and transcription* –
deliberately session-free: one call = one request, no orchestration.

| Area | Content |
|---|---|
| `src/llm.ts` | `LLM.request` / `generate` / `stream`, `generateObject` |
| `src/schema/` | `ToolDefinition`, `ToolNamespace`, `ToolEntry`, Messages, Options, Events, Errors |
| `src/protocols/` | **48 wire protocols**: `anthropic-messages` (68 KB), `openai-chat` (52 KB), `open-responses` (59 KB), `gemini` (38 KB), `bedrock-converse` (33 KB), `mistral-chat`, `google-interactions`, `openai-responses-channel/continuation` plus image/video/speech/transcription protocols |
| `src/providers/` | **52 provider entrypoints** in 14 provider families (`alibaba/`, `amazon-bedrock/`, `azure/`, `cohere/`, `google/`, `google-vertex/`, `meta/`, `minimax/`, `moonshot/`, `openai/`, `openai-compatible/`, `vercel-ai-gateway/`, `zai/`, `zai-coding-plan/`): `anthropic`, `openai`, `openrouter`, `vercel-ai-gateway`, `cloudflare-ai-gateway`, `groq`, `deepseek`, `zai`, `xai`, `mistral`, `opencode`, … |
| `src/route/` | HTTP/WS transport: `client.ts` (28 KB), `executor.ts`, `auth.ts`, `endpoint.ts`, `framing.ts`, `media.ts` |
| `src/cache-policy.ts`, `effort-updates.ts` | Prompt cache policy and reasoning-effort adjustment per provider |
| `src/image.ts`, `video.ts`, `speech.ts`, `transcription.ts`, `media.ts` | Multimodal APIs |
| `src/provider-error.ts` | `isContextOverflow`, `isRetryable` – the system's retry classification |

Exports: `.`, `./promise`, `./testing`, `./*`.
Depends on: `@opencode/schema`, `@opencode/http-recorder`.

---

### `app` – `@opencode/app` ✅

*SolidJS web/PWA surface* – the product UI, shared by the desktop shell and the
web deployment.

| Folder | Content |
|---|---|
| `src/shell/` | App shell: `layout/`, `routes/`, `tabs/`, `titlebar/`, `commands/`, `notifications/`, `state/`, `errors/` |
| `src/composer/` | Input bar (prompt, attachments, agent/model selection) |
| `src/session/` | Session timeline, diffs, permission dock, requests |
| `src/runtime/` | Runtime platform: `server/` (backend connection), `extension/` (extension host), `platform/`, `persistence/`, `i18n/` |
| `src/home/`, `src/new-session/`, `src/providers/`, `src/servers/`, `src/settings/`, `src/workspaces/` | Main areas of the app |
| `e2e/` | Playwright E2E: `user-story/`, `regression/`, `reproduction/`, `performance/`, `service-worker/`, `screenshots/` |
| `component-tests/` | Storybook-based Playwright component tests |
| `test-browser/` | `bun test` tests in browser environment (happy-dom) |
| `vite.config.ts`, `vite.pwa.ts`, `vite.icons.ts` | Build, PWA, icon generation; Sentry plugin |

Depends on: `client`, `schema`, `gui-extensions`, `session-ui`, `ui`, `util`.

---

### `cli` – `@opencode/cli` ✅

*Binaries `opencode` and `opencode2`* – the entry point for users and automation.

| Location | Content |
|---|---|
| `bin/` | `opencode.cjs`, `opencode2.cjs` |
| `src/commands/` | Command definitions (default/TUI, `run`, `serve`, `session`, `service`, `auth`, `mcp`, `plugin`, `models`, `stats`, `mini`, `api`, `acp`, `debug`, `pair`, `upgrade`) + `handlers/` |
| `src/framework/` | Small spec/runtime framework for command definition (`spec.ts`, `runtime.ts`) |
| `src/run/` | `opencode run` (non-interactive), incl. V1 compatibility |
| `src/acp/` | Agent Client Protocol (Zed integration), incl. permission mapping |
| `src/mini.ts`, `src/mini-host.ts` | Mini TUI |
| `src/server-process.ts` | Start/management of the background service, password handling |
| `src/services/`, `src/node/`, `src/ui/`, `src/util/`, `src/config/` | Service management, Node-specific parts, UI helpers, config |
| `src/cpu-profile.ts`, `src/heap.ts` | Diagnostics tools |
| `dev/`, `script/`, `Dockerfile` | Development harness and container image |

Depends on: `client`, `plugin`, `schema`, `server`, `tui`, `util`, `script`, `protocol`.

---

### `client` – `@opencode/client` ✅

*Generated clients* from the authoritative Effect `HttpApi` – no hand-written code.

| Export | Content |
|---|---|
| `./promise` | Promise-based API (`src/promise/`) |
| `./effect` / `./effect/api` | Effect-based API (`src/effect/`, `src/effect/api/api.ts` with all session, permission, message, form operations) |
| `./solid` | Solid bindings (`src/solid/connection.ts` etc.) |
| `./service` | Service discovery/management |
| `src/contract.ts` | shared types |
| `src/rpc-runtime.ts`, `src/shared-events.ts` | RPC runtime and shared event types |
| `src/service-contender.ts`, `-probe.ts`, `-timing.ts` | selection and health check of the right service |

Scripts: `generate` (from `HttpApi`), `check:generated`, `build`.
Depends on: `schema`, `protocol`, `httpapi-codegen`.

---

### `codemode` – `@opencode/codemode` ✅

*Restricted, pure interpreter* for a JS-like language, built around tool calls.
Instead of native tool calls the model gets an API.

| Location | Content |
|---|---|
| `src/interpreter/` | the interpreter itself |
| `src/namespace.ts`, `src/tools.ts` | namespaces and tool catalogue |
| `src/stdlib/` | standard library (deliberately no FS/imports/timers) |
| `src/tool-schema.ts`, `src/tool-runtime.ts`, `src/tool-error.ts` | tool description and execution |
| `src/openapi/` | OpenAPI bridge |
| `interpreter-support.md` | support checklist of language constructs |

Depends on: no workspace package (standalone).

---

### `console` – 📦 container

*The enterprise console* – separate from the product, deployed via SST.

| Subfolder | Content |
|---|---|
| `app/` | SolidStart app (`@opencode/console-app`): Go plans, Zen, enterprise form, downloads, stats proxy, data routes |
| `resource/` | `@opencode/console-resource`: Cloudflare resources (`resource.cloudflare.ts`, `resource.node.ts`) |
| `app/.opencode/` | the console's own OpenCode configuration |

---

### `containers` – 📦 container

*Pre-built CI images* that bake large, slow installations into GitHub Actions
(`job.container`).

`base/`, `bun-node/`, `rust/`, `tauri-linux/`, `publish/` – each a `Dockerfile`
plus `script/build.ts`; `tsconfig.json` without its own `package.json`.

---

### `core` – `@opencode/core` ✅

*The domain logic* – largest and most important package. Behaviour lives here,
not shapes.

| Area | Content |
|---|---|
| `src/session/` | **42 files**: `session.ts` (ID-bound sessions), `inbox.ts` (admission), `execution.ts`, `run-coordinator.ts`, `runner/` (step, LLM, model, retry, prompt), `projector.ts` (30 KB), `compaction.ts` (42 KB), `model-request.ts`, `history.ts`, `context.ts`, `instructions.ts`, `revert.ts`, `transfer.ts`, `diff.ts`, `title.ts`, `stats.ts`, `message-updater.ts`, `move.ts` |
| `src/tool.ts`, `src/tool/` | Tool registry plus 16 built-in plugins (`read`, `write`, `edit`, `patch`, `grep`, `glob`, `shell`, `webfetch`, `websearch`, `todowrite`, `question`, `skill`, `subagent`, `mcp-resource`, `file-diff`, `opencode`) |
| `src/permission.ts`, `src/permission/` | Permission engine (`assert`, `ask`, `reply`), saved grants, SQL |
| `src/plugin.ts`, `src/plugin/` | Plugin host, hooks, supervisor, agents, plan mode, provider, skills, commands |
| `src/provider.ts`, `src/model.ts`, `src/model-resolver.ts`, `src/models-dev/` | Provider registry, model resolution, variants, catalogue |
| `src/mcp/` | MCP client, stdio transport, OAuth, instructions |
| `src/database/` | SQLite via Drizzle: schema, migrations, V1→V2 migration, Bun/Node/workerd drivers |
| `src/config.ts`, `src/config/` | Config normalisation (V1→V2) and config plugins |
| `src/bus.ts` (39 KB) | Event bus with durable persistence |
| `src/shell.ts`, `src/pty/`, `src/persistent-pty/` | Shell and terminal execution |
| `src/git.ts`, `src/vcs.ts`, `src/worktree.ts`, `src/project.ts`, `src/workspace.ts` | Version control and workspaces |
| `src/location*.ts` | Location-scoped service resolution and idle lifecycle |
| `src/variant.ts`, `src/aisdk.ts`, `src/aisdk-native.ts` | Variants and AI SDK integration |
| `src/state.ts` | Transformable, replayable state (basis for tool/agent/catalogue reviews) |
| `src/v1/` | Legacy configuration and migration |

Scripts: `db`, `migration`, `update-models-snapshot`, `benchmark:location`.
Depends on: `ai`, `codemode`, `plugin`, `plugin-browser`, `schema`, `util`, `http-recorder`.

---

### `desktop` – `@opencode/desktop` 🔒

*Electron shell* (beta) around `@opencode/app`.

| Location | Content |
|---|---|
| `src/main/` | Electron main process: windows, IPC, storage (`src/main/storage/schema.ts`), security (`src/main/windows/security.ts`) |
| `src/preload/` | Preload bridge |
| `src/renderer/` | Renderer entry (uses `@opencode/app`) |
| `src/shared/` | shared types |
| `electron-builder.config.ts` | Packaging (mac/Win/Linux) |
| `electron.vite.config.ts`, `icons/`, `resources/`, `scripts/` | Build configuration and assets |

Scripts: `dev`, `build`, `package:{mac,win,linux}`, `bench:startup`, `migration`.

---

### `effect-drizzle-sqlite` – 📦 without `package.json`

*Apparently an abandoned binding* of Drizzle/SQLite for Effect v4.
Contains only `examples/migrations/20240101000000_create_users/migration.sql` and
`sst-env.d.ts` – **no `src/`**, no `package.json`. It does not appear as a
workspace in the root `package.json`. Probably a leftover; if the binding is needed
again, `packages/core/src/database/drizzle/effect-sqlite/` is the actual
implementation.

---

### `function` – `@opencode/function` 🔒

*Cloudflare worker functions* for the website.

* `src/github.ts` – GitHub App token exchange
* `src/share.ts` – shared session access (comment says discontinued)
* `src/api.ts` – API entry point

---

### `gui-extensions` – `@opencode/gui-extensions` 🔒

*Everything in desktop and web app that is not core shell is an extension.*
Each extension builds only on the SDK in `src/sdk`.

| Extension | Content |
|---|---|
| `src/sdk/` | typed SDK: window SDK (`index.ts`), main-process SDK (`main.ts`), bridge |
| `src/terminal/` | terminal pane |
| `src/review/` | review pane |
| `src/file/` | file browser |
| `src/browser/` | browser pane |
| `src/ssh/` | SSH connections |
| `src/wsl/` | WSL support |
| `src/pairing/` | coupling between CLI and app (reference example) |
| `src/updater/` | update mechanism |
| `src/microsoft-office/` | Office integration |
| `src/btw/`, `src/debug/`, `src/details/` | smaller pane helpers |

---

### `http-recorder` – `@opencode/http-recorder` ✅

*Record & replay* of Effect HTTP and WebSocket traffic as deterministic JSON
cassettes. Instead of hand-written HTTP mocks – for provider integrations,
retries, polling and multi-step flows.

`src/cassette/`, `src/websocket/`, `src/http/`, `src/redaction/` (secret
redaction), `src/replay.ts`, `src/options.ts`.

---

### `httpapi-codegen` – `@opencode/httpapi-codegen` 🔒

*Build-time source code generator* for domain-oriented Promise and Effect APIs
from `HttpApi` + Effect Schema. Deliberately **independent of core**; the tests
are the executable specification.

`src/index.ts` – the entire generator engine; used by
`packages/client/script/build.ts`.

---

### `identity` – 📦 without `package.json`

*Brand assets only*: `mark.svg`, `mark-light.svg`, `mark-96x192.png` (icons/favicons
for the identity page). No code.

---

### `latex` – `@opencode/latex` 🔒

*LaTeX rendering* as an agent plugin: markdown → PDF for produced documents.

`src/parser.ts`, `src/render.ts`, `src/layout.ts`, `src/limits.ts`, `src/symbols.ts`,
`src/markdown.ts`, `src/plugin.ts`. Exports: `./markdown`, `./plugin`.

---

### `merman` – `@opencode/merman` 🔒

*Diagram rendering* for markdown – Mermaid-like, but with its own layout.

`src/flowchart/`, `src/gantt/`, `src/sequence/`, `src/gitgraph/`, `src/timeline/`,
`src/layout-audit.test.ts` (layout assertions), `src/palette.ts`, `src/markdown.ts`,
`src/plugin.ts`. Exports: `./markdown`, `./palette`, `./plugin`.

---

### `plugin` – `@opencode/plugin` ✅

*Author APIs for OpenCode V2 plugins* – Promise **and** Effect variant.

| Location | Content |
|---|---|
| `src/README.md` | Promise plugin API |
| `src/effect/` | Effect plugin API: `agent`, `tool`, `session`, `permission`, `shell`, `aisdk`, `command`, `provider`, `skill`, `integration`, `mcp`, `model`, `reference`, `rpc`, `storage`, `vcs`, `websearch`, `worktree` |
| `src/host.ts` | plugin host contract |
| `src/source.ts`, `source.bun.ts`, `source.node.ts`, `source.package.ts` | loaders for package, Bun and Node sources |
| `src/runtime.ts`, `src/runtime-modules.ts` | plugin runtime and module resolution |
| `src/tui/` | TUI extension points |
| `src/options.ts`, `src/permission.ts`, `src/app.ts`, `src/storage.ts`, `src/rpc.ts`, `src/worktree.ts` | side APIs |

---

### `plugin-browser` – `@opencode/plugin-browser` ✅

*The desktop browser as a code-mode plugin.* The server owns tools, call scope
and permissions; the desktop owns tabs, CDP, traffic capture, evaluations and
capture files. Core registers only the plugin – no page imports the implementation
of the other.

`src/connection.ts`, `src/tools.ts`, `src/rpc.ts`, `src/proxy.ts`, `src/tunnel.ts`,
`src/files.ts`.

---

### `plugins` – 📦 container

*Official, individually installable plugins* – not bundled with OpenCode and not
loaded automatically. Currently installed via Git URLs, not via npm.

* `figma/` – Figma MCP integration + official Figma skills (`skills/figma-generate-design`,
  `figma-design-to-code`, `figma-generate-diagram`, `figma-generate-library`,
  `figma-generate-slides`, `figma-implement-motion`, `figma-create-new-file`,
  `figma-swiftui`, `figma-generative-plugins`)
* `README.md` – installation notes, explicit warning: do not add to core
  dependencies or the built-in registry

---

### `posts` – `@opencode/posts` 🔒

*Private Astro blog / casts* – `src/content/`, `src/components/`, `src/layouts/`,
`src/pages/`. Not part of the product.

---

### `protocol` – `@opencode/protocol` ✅

*The HTTP contracts.* Here it is defined which operations exist – the implementation
lives in the server.

| Location | Content |
|---|---|
| `src/api.ts` | assembly of all groups into the `HttpApi` (9.6 KB) |
| `src/groups/` | **31 groups**: `session` (34 KB), `permission`, `message`, `agent`, `model`, `provider`, `mcp`, `integration`, `project`, `worktree`, `vcs`, `fs`, `shell`, `pty`, `persistent-pty`, `plugin`, `skill`, `command`, `reference`, `config`, `location`, `form`, `websearch`, `credential`, `event`, `generate`, `voice`, `rpc`, `server`, `debug`, `migration` |
| `src/middleware/` | `authorization`, `schema-error` |
| `src/errors.ts` | public error taxonomy |
| `src/client.ts` | client contract for codegen |
| `src/simulation.ts` (26 KB) | simulation contract |
| `openapi.json` (582 KB) | generated OpenAPI |

Scripts: `generate`, `check:generated`.

---

### `schema` – `@opencode/schema` ✅

*Public wire and storage contracts* – browser-safe, no runtime logic.
77 top-level modules, 19 config modules, 4 V1 modules.

| Area | Modules |
|---|---|
| Session | `session.ts`, `session-event.ts` (19 KB), `session-message.ts`, `session-inbox.ts`, `session-id.ts`, `session-revert.ts`, `session-transfer.ts`, `session-fork.ts`, `session-stats.ts`, `session-metadata.ts`, `session-status-event.ts`, `session-error.ts`, `session-legacy.ts` |
| Permissions | `permission.ts`, `permission-v1.ts`, `permission-saved.ts` |
| Agents/models | `agent.ts`, `model.ts`, `model-compatibility.ts`, `models-dev.ts`, `provider.ts` |
| Config | `config.ts` + `config/` (agent, command, compaction, experimental, formatter, lsp, mcp, media, model, plugin, policy, provider, reference, shell, tool-output, warming, watcher, websearch, worktree) |
| Tools/integrations | `tool.ts`, `mcp.ts`, `skill.ts`, `plugin.ts`, `integration.ts`, `websearch.ts`, `reference.ts` |
| Infrastructure | `event.ts`, `event-manifest.ts`, `durable-event-manifest.ts`, `event-log.ts`, `location.ts`, `workspace.ts`, `project.ts`, `worktree.ts`, `pty.ts`, `persistent-pty.ts`, `pty-ticket.ts`, `shell.ts`, `rpc.ts`, `command.ts`, `question.ts`, `form.ts`, `prompt.ts`, `prompt-input.ts`, `instruction.ts`, `instruction-entry.ts`, `token-usage.ts`, `money.ts`, `filesystem.ts`, `file-diff.ts`, `snapshot.ts` |
| Legacy | `v1/` (session, permission, filesystem), `*-v1.ts` |

---

### `script` – `@opencode/script` ✅

*Script helper library*: `src/highlights.ts` (syntax highlighting for terminal
output), `src/index.ts`. Exports `.`, `./highlights`.

---

### `sdk` – `@opencode/sdk` ✅

*Embed OpenCode in process* – the server router runs **in memory**, without a
listener and without a network hop.

| Export | Content |
|---|---|
| `.` / `./effect` | Promise and Effect hosts (`src/opencode.ts`, `src/effect.ts`, `src/promise.ts`) |
| `./workerd` | workerd variant (`src/workerd.ts`) |
| `src/internal/host.ts` | `createEmbeddedRoutes` + `HttpEffect.toWebHandlerWith` |
| `src/contracts.ts`, `src/tool.ts`, `src/logging.ts` | types, tool helpers, logging |

Depends on almost everything: `client`, `core`, `server`, `protocol`, `plugin`, `ai`, `util`.

---

### `server` – `@opencode/server` ✅

*The HTTP server* – assembles the routes and holds the infrastructure.

| Location | Content |
|---|---|
| `src/routes.ts` | service graph (26 nodes) + `HttpApiBuilder.layer(Api, {openapiPath})` |
| `src/handlers/` | **34 handlers**: `session` (26 KB), `persistent-pty` (9 KB), `pty` (8 KB), `integration` (10 KB), `fs`, `permission`, `plugin`, `vcs`, `shell`, `credential`, `event`, `form`, `voice`, `websearch`, `worktree`, … |
| `src/middleware/` | `authorization`, `schema-error`, `form-location`, `session-location` |
| `src/auth.ts` | basic auth, session token (HMAC-SHA256), 30-day TTL |
| `src/pairing.ts` | one-time codes for web connection |
| `src/event-feed.ts` | SSE feed with "encode once" and 4,096 frames per connection |
| `src/fetch.ts`, `src/workerd.ts` | web-standard `fetch` handler for workerd/Deno/Bun |
| `src/process.ts` | server process, startup recovery |
| `src/cors.ts`, `src/location.ts`, `src/options.ts`, `src/server-info.ts`, `src/service-status.ts`, `src/request-tracing.ts` | environment, location middleware, options, `/api/info` |

---

### `session-ui` – `@opencode/session-ui` 🔒

*Reusable session components* – independent of the TUI, shared by web and desktop.
39 export paths.

| Area | Content |
|---|---|
| `src/timeline/` | `projection`, `detail`, `row`, generated progress indicators |
| `src/components/` | message, tool, diff and comment components |
| `src/message-part/`, `src/message/` | message rendering, markdown stream |
| `src/session-diff/`, `src/session-review/`, `src/line-comment/` | diff view, review, line comments |
| `src/dock-prompt/`, `src/basic-tool/` | permission/question docks, tool display |
| `src/pierre/` | layout/diff engine |
| `src/context/` | context/budget display |
| `src/v2/` | next UI generation + CSS |
| `component-tests/` | Storybook Playwright component tests |
| `performance/` | performance measurements |

---

### `simulation` – `@opencode/simulation` ✅

*Record and replay sessions* – deterministic tests without real providers.

| Location | Content |
|---|---|
| `src/backend/` | backend implementation of the simulation (replaces service parts) |
| `src/frontend/` | frontend bindings |
| `src/protocol/` | recording/replay protocol |
| `src/recording.ts`, `src/manifest.ts`, `src/control-server.ts` | recording, manifest, control server |

Used by `server` and `tui` as an optional runtime (`routes.ts:153-161`).

---

### `stats` – 📦 container

*Stats portal* – a separate site, separate from the console.

| Subfolder | Content |
|---|---|
| `app/` | SolidStart website (`@opencode/stats-app`) |
| `core/` | runtime, database and domain services (`@opencode/stats-core`) incl. `migrations/` |
| `server/` | deployable lambda entrypoints (`@opencode/stats-server`) + `Dockerfile` |

`infra/stats.ts` (SST) provisions PlanetScale + worker + sync service.

---

### `storybook` – `@opencode/storybook` 🔒

*Storybook wrapper* for the component stories of `ui` and `session-ui`.
Scripts: `storybook`, `build`.

---

### `theme` – `@opencode/theme` ✅

*TUI themes* – `src/tui/` (V2) and `src/tui/v1`. Exports `./tui`, `./tui/v1`.
The repo itself contains an example under `.opencode/themes/mytheme.json`.

---

### `tui` – `@opencode/tui` 🔒

*The terminal UI* on OpenTUI + Solid.

| Location | Content |
|---|---|
| `src/app.tsx` (52 KB) | main UI of the V2 TUI |
| `src/mini/` | lean TUI (`opencode mini`) |
| `src/routes/` | session, settings, diff routes |
| `src/component/` | dialogs, prompt, session tabs, patch diff |
| `src/feature-plugins/` | modular features incl. Storybook stories |
| `src/theme/`, `src/config/` | theme and keybinds/settings |
| `src/prompt/`, `src/editor.ts` | prompt input, Zed editor integration (SQLite variant) |
| `src/devtools/`, `src/simulation/`, `src/runtime.tsx` | dev tools, simulation client |
| `src/attention*.ts`, `src/audio.ts`, `src/clipboard.ts` | notifications, sound, clipboard |

---

### `ui` – `@opencode/ui` ✅

*The design system* of the web/desktop UI – Solid + Tailwind.

| Area | Content |
|---|---|
| `src/components/` | 47 components with CSS + stories: `list`, `text-reveal`, `scroll-view`, `context-menu`, `popover`, `card`, `resize-handle`, `file-icon`, `thinking-heading` (28 KB), … |
| `src/styles/` | design tokens, Tailwind setup, themes |
| `src/theme/` | theme context and variants |
| `src/icons/` | app, provider and file-type icons (catalogue) |
| `src/i18n/` | translations |
| `src/forms/`, `src/overlays/`, `src/navigation/`, `src/feedback/`, `src/typography/`, `src/layout/` | forms, overlays, navigation, toasts, typography |
| `src/storybook/` | scaffold and fixtures for stories |

Around 55 export paths (components, tokens, icons, i18n, hooks).

---

### `util` – `@opencode/util` ✅

*Cross-cutting utilities* without domain logic.

| Area | Content |
|---|---|
| `src/effect/` | `layer-node.ts` (13.8 KB – layer graph helpers), `app-node.ts`, `app-node-platform.ts`, `runtime.ts`, `memo-map.ts`, `service-use.ts` |
| `src/fs-util.ts`, `src/glob.ts` | filesystem and glob access |
| `src/path.ts`, `src/global.ts`, `src/global-roots.ts` | path normalisation, global roots (`XDG_DATA_HOME`) |
| `src/hash.ts`, `src/base64.ts`, `src/binary.ts`, `src/bom.ts`, `src/encode.ts` | encoding/hashing |
| `src/patch.ts` | patch apply/generate |
| `src/retry.ts`, `src/flock.ts`, `src/cross-spawn-spawner.ts`, `src/process.ts` | processes, retries, file locks |
| `src/open.ts`, `src/npm.ts`, `src/npm-config.ts` | open browser/editor, npm configuration |
| `src/observability/` | telemetry/tracing |

`src/effect/layer-node.ts` is used by `core`, `server` and `cli` for the
location/service graph.

---

### `web` – `@opencode/web` ✅

*Astro/Starlight website* with marketing landing page and documentation.

`src/content/` (docs), `src/pages/`, `src/components/`, `src/i18n/`,
`src/content.config.ts` (Astro content collection), `src/middleware.ts`.
Scripts: `dev`, `dev:remote`, `build`, `astro`.

---

## Dependency direction (short form)

```
schema  ←  protocol  ←  server  ←  client  ←  sdk
   ↑          ↑           ↑          ↑
   └──────────┴───────────┴──────────┴────→ app / tui / desktop
                       core  ←────────────┘
                        ↑
             ai, plugin, util, codemode
```

* `schema` knows only `effect` – no domain logic.
* `protocol` knows only `schema`.
* `client` imports **neither** `core` nor `server`.
* `sdk` is the only place that composes `client` + `core` + `server`.
* `core` is the only package that embeds `plugin` **and** `plugin-browser`.

## Reading recommendation

| If you want to understand … | Read first |
|---|---|
| the session machine | `core/src/session/` + `specs/v2/session.md` |
| the API surface | `protocol/src/groups/` + `server/src/handlers/` |
| the rights | `core/src/permission.ts` + `core/src/managed-policy.ts` + `docs/content/permissions.mdx` |
| the surfaces | `tui/src/app.tsx`, `app/src/shell/`, `ui/src/components/` |
| the provider world | `ai/src/protocols/` + `ai/src/providers/` + `core/src/provider.ts` |