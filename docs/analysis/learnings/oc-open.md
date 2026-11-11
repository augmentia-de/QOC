# oc-open.md – What QOC can learn from OpenCode (oc)

> **Status:** Analysis · 2026-10-24
> **Reference:** `../oc` (OpenCode)
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus / LangChain4j)
>
> This document is the OpenCode counterpart of `pi-learnings.md`. pi and oc are
> separate projects; pi notes are kept separately.

---

## 1. Shared concepts already in parity

| Concept | oc | QOC |
|---|---|---|
| File tools | read/write/edit/patch/glob/grep | same, plus `external_directory` gate |
| Shell | bash with timeout/background | `shell` + scanner + HITL |
| Skills | Agent Skills standard, project/global paths | `skill` tool + `SkillCatalog` + 8 KiB limit |
| Web | `webfetch` + `websearch` | `websearch` (Firecrawl), `webfetch` |
| Subagents | via plugins | `subagent` (depth 2, semaphore 4) |
| Compaction | manual + auto | auto on overflow + manual endpoint |
| Revert | snapshot + message deletion | `SnapshotStore` + `revert` |
| Sessions | JSONL tree, resume | SQLite/Postgres, resume |
| Agents | plugin/file-based | `settings.json` agents, plan mode |
| Permissions | allow/ask/deny + saved approvals | OS + engine policy + shell scanner |
| Streaming | SSE | SSE |
| MCP | tools + resources (OAuth) | tools + resources |
| Cost/token | footer | `cost`/`tokens` in `SessionInfo` |
| Message delivery | STEER vs. follow-up | `Delivery.STEER` / `QUEUE` |

---

## 2. Adoptable features, with oc implementation details

### 2.1 Context files (AGENTS.md / SYSTEM.md) — ✅ implemented

**oc reference**

- `packages/core/src/config/plugin/instruction.ts`:
  - global file `~/.config/opencode/AGENTS.md`;
  - project: ancestor walk from the location directory up to the project root
    (or `$HOME` when the location is under home), collecting `AGENTS.md`;
  - every candidate is watched (`Watcher.Service`), so edits re-load;
  - configured `instructions` entries may add files, globs and URLs.
- `packages/core/src/instruction-discovery.ts` renders loaded files as
  `Instructions from: <path>\n<content>` and supports *updates*: a changed file
  produces a diff or a replacement block, removed files produce
  “The instructions from <path> no longer apply.”
- `packages/core/src/session/instructions.ts` injects them as **synthetic user
  messages** with metadata `{instruction: {paths: [...]}}`. The metadata is the
  dedupe ledger; after compaction/revert the synthetic disappears from
  model-visible history, so the path is re-discovered and re-injected.
- `SYSTEM.md` replaces the base system prompt (`APPEND_SYSTEM.md` appends).

**QOC implementation (done)**

- `InstructionLoader` port + `NioInstructionLoader` adapter:
  - global `~/.config/opencode/AGENTS.md` (or `CLAUDE.md`);
  - every ancestor `/` → cwd (AGENTS before CLAUDE, dedupe by path);
  - `SYSTEM.md` / `APPEND_SYSTEM.md` from `<dir>/.opencode/`, `<dir>/`, global;
  - 32 KiB cap.
- `ContextBuilder.prepare`: SYSTEM replaces the base block; AGENTS/APPEND append
  before the skill block.
- `engine.instructions.enabled` (default true).
- Compose: JVM mounts `deploy/AGENTS.jvm.md` → `/data/workspace/AGENTS.md`;
  native mounts `deploy/AGENTS.native.md`.
- Tests: `NioInstructionLoaderTest`, `ContextBuilderTest`.

**Gap vs. oc**

- oc uses synthetic user messages with a metadata ledger; QOC uses system
  blocks. Consequence: after compaction/revert QOC does **not** re-inject the
  instructions.
- oc watches the files and emits update diffs; QOC reads them at each prompt
  (no watcher, no incremental diff).
- oc supports configured extra instruction sources (files/globs/URLs); QOC does
  not.
- oc appends date/time + cwd to the prompt; QOC does not.

### 2.2 Prompt templates / commands

**oc reference:** `packages/core/src/plugin/command.ts`
(`command/*.txt`, `command/<id>.md`-style text macros, `/command` expansion).

- Commands are text macros; arguments are substituted and the result is
  delivered as normal input.
- The built-in `/initialize` command creates or updates `AGENTS.md`; `/review`
  checks conventions files.

**QOC mapping**

- L-6 already decides text macros via inbox; the HTTP command endpoint only
  forwards `/name args` as a prompt.
- Implementation: `CommandLoader` for `~/.config/opencode/command/*.md` and
  `<workspace>/command/*.md` (plus `.opencode/command/`), `$ARGUMENTS`
  substitution, enqueue as synthetic user message.
- Tests: `CommandLoaderTest`, `SubstitutionTest`, `EngineHttpTest`.

### 2.3 Skills

**oc reference:** `packages/core/src/skill/` (`discovery.ts`, `instructions.ts`),
`packages/core/src/session/skill.ts`,
`packages/core/src/plugin/` (skill plugin).

- Roots: global and project skill directories (`.opencode/skills`,
  `~/.config/opencode/skills`), plus configured/package sources.
- Discovery: direct child skills + recursive `SKILL.md`; a directory with
  `SKILL.md` is a skill.
- Prompt: `<available_skills>` with name, description and absolute
  `<location>`; the model loads the file with `read` (progressive disclosure).
- `disable-model-invocation` hides a skill from the prompt; explicit invocation
  only.
- `/skill:<id>` is a slash command.
- The skill catalog is a service (`Skill.Service`) with reload support.

**QOC mapping**

- Already: `SkillCatalog`/`SkillScanner`, 8 KiB body, `.opencode/skills`,
  `skills.paths`, `allow`/`deny`/`max`, `disable-model-invocation`, `skill` and
  `skill_list` tools, `GET /api/skill?all=true`.
- Gaps: no walk-up to the git root, no `~/.agents/skills`,
  `.gitignore`/`.ignore`/`.fdignore`, no name/description validation, no
  file watcher, no `/skill:<id>` slash command.

### 2.4 Tools

**oc reference:** `packages/core/src/tool/` + `packages/core/src/tool/plugin/`.

- Tools are registered by plugins (`ctx.tool.transform`), so a plugin can add,
  rename or remove tools.
- Permission: action/resource/effect rules, `ask` prompts the user, `deny`
  blocks; saved approvals persist.
- Tool output can be large; the reference keeps a preview and stores the full
  output in managed storage.
- `webfetch`: bounded 5 MiB, textual MIME only, HTML→Markdown.
- `websearch`, `question`, `todowrite`, `subagent`, `skill`, MCP tools and MCP
  resources are all tools.

**QOC mapping**

- QOC registers tools statically in `Engine.wire()`; the set is comparable
  (read/write/edit/patch/glob/grep/shell/skill/subagent/todowrite/question/
  webfetch/websearch, MCP, worktrees).
- Permission chain: `PermissionEngine` + `PermissionRules` (deny before
  approval, ask dominates allow, last match wins) + per-tool gate +
  `external_directory` leaf check.
- Gaps: no plugin transforms, no managed-storage preview for large outputs,
  no `opencode`/`mcp-resource` tools (the latter partially via MCP resources).

### 2.5 Compaction

**oc reference:** `packages/core/src/session/compaction.ts`,
`docs/compaction.md` in the reference tree.

- Trigger: context tokens exceed context window minus a reserve.
- Manual `/compact [instructions]`; automatic on overflow.
- Cut at turn boundaries; never at tool results.
- Summaries are structured (Goal, Constraints, Progress, Decisions, Next
  Steps, Critical Context) and track read/modified files cumulatively.
- The event log stays append-only; the compacted view is derived.

**QOC mapping**

- `CompactionService` has `CTX-SUMMARY`/`CTX-SUMMARY-UPDATE` and an epoch, uses
  `keepMessages` instead of a token budget.
- Gaps: token-budget split (reserve/keep), split turns, cumulative file lists,
  custom instructions in the manual endpoint, branch summarization.

### 2.6 Session tree / fork

**oc reference:** session store + projector; entries have `id`/`parentId`, a
leaf pointer, `/tree` navigation, `/fork` into a new session.

**QOC mapping**

- `SessionEvent.SessionForked` + `ForkBoundary` exist; no endpoint, projection
  or UI.
- QOC’s store is flat (`seq` only). Baseline: fork copies messages up to the
  boundary into a new session; `/tree` is a filtered user-message list.

### 2.7 Message queue (steer vs. follow-up)

oc: while streaming, a prompt must choose `steer` or `followUp`; steer
interrupts remaining tools, follow-up runs after completion. QOC already has
`Delivery.STEER`/`QUEUE`; only the HTTP `delivery` field and UI shortcuts are
missing.

### 2.8 Settings

oc: global `~/.config/opencode/settings.json`, project `.opencode/settings.json`
(or project `settings.json`), merged field by field. QOC already merges
global + project. Missing keys: compaction reserve/keep, retry policy, delivery
defaults, image limits, shell command prefix.

### 2.9 Slash commands / export

oc built-ins: `settings`, `model`, `export`, `share`, `copy`, `name`, `session`,
`changelog`, `hotkeys`, `fork`, `tree`, `new`, `compact`, `resume`, `reload`,
`quit`. QOC has `/compact`, `/revert`, `/title`, `/new`, `/interrupt`.
Add `/copy`, `/export`, `/session`, `/name`, `/reload`, `/tree`, `/fork`,
`/skill:<id>`.

### 2.10 Images / attachments

oc: paste/drag → base64 image content; resize/limit; `blockImages` setting.
QOC has `ModelContent.Media`; UI and `ReadTool`/`WriteTool` are text-oriented.

### 2.11 Watchers / reload

oc watches instruction and skill files; changes produce update diffs and reload
the catalog. QOC has `SkillCatalog.reload()` but no watcher and no `/reload`
endpoint.

---

## 3. Detailed handling comparison: oc vs. QOC

### 3.1 Prompt assembly

| Aspect | oc | QOC |
|---|---|---|
| Base prompt | `SessionSystemPrompt.make(tools)` renders `system.txt`; `${OPENCODE_TOOL_GUIDANCE}` is replaced by per-tool hints (shell/write/edit). | `ContextBuilder.basePrompt(tools)` from `base-prompt` + `sys-guide`. |
| Context files | `ConfigInstructionPlugin` discovers global + root→cwd `AGENTS.md`, watches them; `InstructionDiscovery` renders `Instructions from: …`. | `NioInstructionLoader` discovers the same order, reads at prompt time, appends as system blocks. ✅ |
| System override | `SYSTEM.md` replaces, `APPEND_SYSTEM.md` appends. | `.opencode/SYSTEM.md` or `SYSTEM.md` replaces, `APPEND_SYSTEM.md` appends. ✅ |
| Injection | **Synthetic user messages** with metadata `{instruction.paths}`; dedupe ledger survives compaction/revert. | System blocks; no ledger, no re-injection. |
| Skills | `<available_skills>` with absolute `<location>`. | Same shape. |
| Tool hints | Conditional per tool. | shell/write/edit only. |
| Date/time + cwd | Appended last. | Missing. |
| Compaction summary | Structured summary; `firstKeptEntryId`; cumulative file lists. | `CompactionService` with epoch; no file lists. |

### 3.2 Tool handling

| Aspect | oc | QOC |
|---|---|---|
| Registry | Plugin transforms; add/rename/remove tools. | Static `Engine.wire()`. |
| Permission | `Permission.Service` with action/resource/effect + saved approvals. | `PermissionEngine` + `PermissionRules` + per-tool gate + `external_directory`. |
| Parallel | Sequential tool calls inside one step. | `ExecutorService` max 8, results in call order. |
| Output bounding | 5 MiB fetch; managed output store. | Shell 50 KiB; webfetch 5 MiB; `ToolOutputStore`. |
| MCP | Tools + resources + OAuth. | `McpServerManager` tools + resources. |
| Guidance | Per-tool hints. | `sys-guide` for shell/write/edit. |

### 3.3 Skill handling

| Aspect | oc | QOC |
|---|---|---|
| Roots | Global + project + configured/packages. | `<workspace>/skill(s)`, `.opencode/skills`, `skills.paths`. |
| Discovery | direct + recursive `SKILL.md`; ignore files; git-root walk. | direct + recursive; no ignore files, no git-root walk. |
| Validation | name ≤64 `[a-z0-9-]`, description ≤1024. | none (parser only). |
| Body | no hard scanner cap. | 8 KiB. |
| Visibility | `disable-model-invocation`; `/skill:<id>`. | same + `skill_list` + `?all=true`; no slash. |
| Reload | watcher + reload. | `SkillCatalog.reload()` unused. |

### 3.4 AGENTS.md handling

| Aspect | oc | QOC |
|---|---|---|
| Global | `~/.config/opencode/AGENTS.md`. | same. ✅ |
| Project | ancestor walk to project root/home, watches files. | ancestor walk to `/`; reads at prompt time. ✅ |
| Priority | `AGENTS.md` only (no CLAUDE in oc core? config supports `AGENTS.md`). | `AGENTS.md` before `CLAUDE.md` (extra). |
| Dedupe | metadata ledger in synthetic messages. | path dedupe at load time only. |
| Re-injection | yes, after compaction/revert. | no. |
| Updates | watcher emits diff/updated block. | re-read each prompt; no diff. |
| Size | no explicit cap. | 32 KiB. |

---

## 4. What does not fit

| oc feature | Reason |
|---|---|
| Plugin transforms (TS/Effect) | QOC is Java; a plugin SPI + classloader is a large architectural step. |
| Managed output previews | QOC has `ToolOutputStore`; a preview layer is UI work. |
| T UI / desktop / web UI | Separate products. |
| RPC / SDK / client packages | HTTP/SSE covers QOC’s needs. |
| Themes, TUI components | Client-side. |
| Packs/packages (npm/git) | No package manager in Java. |

---

## 5. Recommended order

1. ✅ Context files + system-prompt files — implemented 2026-10-24.
2. Instruction injection ledger + re-injection after compaction/revert.
3. Prompt templates / command loader (L-6).
4. `/reload` + watcher for skills/instructions.
5. Compaction details (reserve/keep tokens, split turns, file lists, manual
   instructions).
6. Skill validation + ignore-file + git-root discovery.
7. `/skill:<id>`, extended slash commands, export/copy.
8. Image paste.
9. Message queue UI.
10. Fork/tree + branch summarization.

---

## 6. Open decisions

- Re-inject instruction files as synthetic messages (oc-style) or keep system
  blocks?
- Should skill discovery walk up to the git root and honour ignore files?
- Should the scanner validate Agent-Skills names/descriptions (warnings)?
- Token-budget compaction vs. QOC’s `keepMessages`?
- Plugin SPI: out of scope or a later milestone?
- Watchers: poll or OS-level file watch (WatchService)?
- Image attachments: MIME/size policy?

---

*Created: 2026-10-24*
