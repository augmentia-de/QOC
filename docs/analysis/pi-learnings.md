# pi-learnings.md – What QOC can learn from the pi coding agent

> **Status:** Analysis · 2026-10-24
> **Reference:** `<pi-checkout>` (pi coding agent, `packages/coding-agent`)
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus / LangChain4j)

pi is a TypeScript coding agent (TUI + web UI) with a deliberately minimal core:
four built-in tools (`read`, `write`, `edit`, `bash`); everything else comes
through extensions, skills, prompt templates and pi-packages. QOC has a richer,
stricter engine (three-layer permissions, MCP, subagents, snapshots). The gap is
not capability but **discoverability and ergonomics**.

This document records the pi implementation details worth copying and maps them
to concrete QOC classes.

---

## Contents

- [1. Shared concepts already in parity](#1-shared-concepts-already-in-parity)
- [2. Adoptable features and pi implementation details](#2-adoptable-features-and-pi-implementation-details)
- [3. What does not fit](#3-what-does-not-fit)
- [4. Recommended order](#4-recommended-order)
- [5. Open decisions](#5-open-decisions)

## 1. Shared concepts already in parity

| Concept | pi | QOC |
|---|---|---|
| File tools | read/write/edit/patch/glob/grep | same, plus `external_directory` gate |
| Shell | bash with timeout/background | `shell` + scanner + HITL |
| Skills | Agent Skills standard, project/global paths | `skill` tool + `SkillCatalog` + 8 KiB limit |
| Web | via skill | `websearch` (Firecrawl), `webfetch` |
| Subagents | via extensions | `subagent` (depth 2, semaphore 4) |
| Compaction | manual + auto | auto on overflow + manual endpoint |
| Revert | git checkpointing | `SnapshotStore` + `revert` |
| Sessions | JSONL tree, resume | SQLite/Postgres, resume |
| Agents | extensions + prompts | `settings.json` agents, plan mode |
| Permissions | extension-based | OS + engine policy + shell scanner |
| Streaming | SSE | SSE |
| Cost/token | footer | `cost`/`tokens` in `SessionInfo` |
| Message delivery | STEER vs. follow-up | `Delivery.STEER` / `QUEUE` |
| MCP | via extensions | native `McpServerManager` |

---

## 2. Adoptable features and pi implementation details

### 2.1 Context files (`AGENTS.md`, `CLAUDE.md`, `SYSTEM.md`) — ✅ implemented (2026-10-24)

**Status:** implemented. `InstructionLoader` + `NioInstructionLoader`, wired in
`ContextBuilder.prepare`, enabled by default (`engine.instructions.enabled`).
The JVM compose mounts `deploy/AGENTS.jvm.md` at `/data/workspace/AGENTS.md`;
the native compose already mounts `AGENTS.native.md` there.

**pi reference:** `packages/coding-agent/src/core/resource-loader.ts`

- `loadContextFileFromDir(dir)` tries `AGENTS.md` first, then `CLAUDE.md`
  (first match per directory wins).
- `loadProjectContextFiles`:
  1. global file from the agent dir (`~/.pi/agent/AGENTS.md` or `CLAUDE.md`);
  2. walk from `cwd` up to `/`, collecting one file per directory and
     `unshift`-ing them so the final order is **root → … → cwd**;
  3. deduplicate by absolute path.
- `SYSTEM.md` discovery: `<cwd>/.pi/SYSTEM.md` first, then
  `~/.pi/agent/SYSTEM.md`. `APPEND_SYSTEM.md` uses the same order.
- `buildSystemPrompt` appends context files as:

```markdown
# Project Context

Project-specific instructions and guidelines:

## /abs/path/AGENTS.md

<content>
```

- Context files are appended **after** the base prompt and skills block, before
  the date/time and working directory lines.
- `resolvePromptInput(value)`: if `value` is an existing file path, read its
  content; otherwise treat it as literal prompt text (supports
  `--system-prompt "…"`).

**QOC implementation (done)**

- Port `engine/src/main/java/de/augmentia/oc/engine/core/port/world/InstructionLoader.java`:

```java
public interface InstructionLoader {
    List<InstructionFile> load(Path startDirectory);

    record InstructionFile(Path path, String text, Kind kind) {}

    enum Kind { SYSTEM, AGENTS, APPEND_SYSTEM }
}
```

- Adapter `engine/src/main/java/de/augmentia/oc/engine/adapter/fs/NioInstructionLoader.java`:
  - global `~/.config/opencode/AGENTS.md`/`CLAUDE.md` first;
  - every ancestor from `/` down to the working directory (AGENTS before
    CLAUDE per directory, dedupe by path);
  - `SYSTEM.md` / `APPEND_SYSTEM.md` from `<dir>/.opencode/`, `<dir>/`, then
    the global directory;
  - 32 KiB per-file cap, unreadable files are skipped with a log.
- Call site `ContextBuilder.prepare(...)`
  (`engine/src/main/java/de/augmentia/oc/engine/runtime/context/ContextBuilder.java`):
  `SYSTEM` clears and replaces the base block; `AGENTS` and `APPEND_SYSTEM`
  append before the skill block.
- Config: `engine.instructions.enabled` (default `true`,
  `ENGINE_INSTRUCTIONS_ENABLED`).
- Tests: `NioInstructionLoaderTest` (order, AGENTS-over-CLAUDE, size cap,
  missing files) and `ContextBuilderTest` (extend + SYSTEM replaces).
- Verified in the container: `AGENTS.jvm.md` is mounted as
  `/data/workspace/AGENTS.md` and appears in the system prompt trace.

### 2.2 Prompt templates

**pi reference:** `packages/coding-agent/src/core/prompt-templates.ts`

- Sources: `~/.pi/agent/prompts/*.md`, `<cwd>/.pi/prompts/*.md`, explicit
  `promptPaths` (file or directory). Non-recursive; only `*.md`.
- Name = filename without `.md`; description from frontmatter or the first
  non-empty line (truncated to 60 chars).
- Expansion in `agent-session.ts`: `/name args` is replaced **before** the text
  reaches the agent; only the first space splits name and args.
- Argument substitution (bash-like):
  - `$1`, `$2`, … positional;
  - `$@` and `$ARGUMENTS` all args joined by spaces;
  - `${@:N}` args from N; `${@:N:L}` L args from N;
  - quoted args parsed with `parseCommandArgs`.
- Collisions: first loaded template wins; later ones are diagnostics
  (`resource-loader.ts` `dedupePrompts`).

**QOC implementation**

- `CommandLoader` (same pattern as `InstructionLoader`) for
  `~/.config/opencode/command/*.md`, `<workspace>/command/*.md`,
  `<workspace>/.opencode/command/*.md`.
- `CommandTemplate(id, description, text, source)`; substitution helper with the
  same `$1/$@/$ARGUMENTS/${@:N}` syntax.
- In `Engine.command(...)`: expand and enqueue as a synthetic user message
  (L-6). Unknown name → 404 (already the spec decision).
- Tests: `CommandLoaderTest`, `SubstitutionTest`, `EngineHttpTest`.

### 2.3 Skills

**pi reference:** `packages/coding-agent/src/core/skills.ts`, `docs/skills.md`

- Discovery per root:
  - direct `*.md` children of the root (flat skills);
  - recursive `SKILL.md` under subdirectories (a directory with `SKILL.md` is
    not searched further);
  - hidden entries and `node_modules` are skipped;
  - `.gitignore`, `.ignore`, `.fdignore` are honoured per directory.
- Roots: `~/.pi/agent/skills/`, `~/.agents/skills/`, `.pi/skills/`,
  `.agents/skills/` from `cwd` up to the git root (or filesystem root), plus
  packages, settings `skills` and `--skill`.
- Validation (Agent Skills spec):
  - name ≤ 64 chars, `^[a-z0-9-]+$`, no leading/trailing `-`, no `--`,
    must equal the parent directory name;
  - description required and ≤ 1024 chars.
  - Violations are **warnings**, not hard failures; only a missing description
    drops the skill.
- `disable-model-invocation: true` hides the skill from the prompt; it can only
  be invoked explicitly (`/skill:name`).
- Prompt format includes the absolute `<location>` and tells the model to load
  the file with `read`:

```xml
<available_skills>
  <skill>
    <name>…</name>
    <description>…</description>
    <location>/abs/path/SKILL.md</location>
  </skill>
</available_skills>
```

- `/skill:name args` loads the skill and appends `User: <args>`.

**QOC mapping**

- `SkillCatalog`/`SkillScanner` already match the discovery rules (8 KiB body).
- `disable-model-invocation: true` is supported end-to-end: the skill stays in
  the catalog but is not listed in the prompt and remains loadable via
  `skill(id)`.
- `skill_list` (tool) and `GET /api/skill?all=true` expose hidden skills
  without putting them into the prompt. `GET /api/skill` still returns only the
  listed ones.
- Gap: root set is fixed to `<workspace>/skill(s)` + `.opencode/skills` +
  `skills.paths`; no `~/.agents/skills` or walk-up to the git root.
- Gap: no name/description validation warnings; `disable-model-invocation` is
  honoured but the parser does not warn about Agent-Skills name violations.
- Gap: `/skill:name` slash command.

### 2.4 Compaction and branch summarization

**pi reference:** `docs/compaction.md`, `src/core/compaction/compaction.ts`,
`src/core/compaction/utils.ts`

Trigger:

```text
contextTokens > contextWindow - reserveTokens
```

Defaults: `reserveTokens = 16384`, `keepRecentTokens = 20000`, enabled by
default. Manual: `/compact [instructions]`.

Algorithm:

1. Walk backwards from the newest message, accumulating token estimates until
   `keepRecentTokens` is reached → cut point.
2. Collect messages from the previous compaction (or the start) up to the cut.
3. If the cut falls mid-turn (a single turn exceeds the budget), produce a
   **history summary** and a **turn-prefix summary** and merge them.
4. Save a `CompactionEntry` with `summary`, `firstKeptEntryId`, `tokensBefore`
   and `details { readFiles, modifiedFiles }`.
5. Reload: the model sees the summary + messages from `firstKeptEntryId`.

Cut-point rules: user, assistant, bash-execution or custom messages are valid;
**never** cut at a tool result (it belongs to its tool call).

Summary format:

```markdown
## Goal
## Constraints & Preferences
## Progress
### Done
### In Progress
### Blocked
## Key Decisions
## Next Steps
## Critical Context
<read-files>…</read-files>
<modified-files>…</modified-files>
```

Message serialization before summarization (prevents the model from continuing
the conversation):

```text
[User]: …
[Assistant thinking]: …
[Assistant]: …
[Assistant tool calls]: read(path="foo.ts"); edit(path="bar.ts", …)
[Tool result]: …
```

File tracking is cumulative: previous compaction `details` are merged with tool
calls from the new messages.

Branch summarization (`/tree` navigation):

1. find the deepest common ancestor of old and new leaf;
2. collect entries from the old leaf back to the ancestor;
3. budget from newest to oldest;
4. save a `BranchSummaryEntry` with `summary`, `fromId` and details.

**QOC mapping**

- `CompactionService` already uses `keepMessages` instead of a token budget.
  Adopting pi's `reserveTokens`/`keepRecentTokens` would need token estimates
  per message (`ModelLimits.context` is available in `ModelInfo`).
- `CompactionService` already handles `CTX-SUMMARY`/`CTX-SUMMARY-UPDATE`
  prompts and an epoch.
- Gap: split-turn handling, cumulative file lists, branch summarization,
  manual custom instructions (the endpoint currently takes no prompt).
- `SessionForked`/`ForkBoundary` are the natural place for branch summaries.

### 2.5 Session tree and fork

**pi reference:** `docs/tree.md`, `docs/session.md`,
`src/core/session-manager.ts`

- Session = JSONL file; each entry has `id`/`parentId`; a `leaf` pointer marks
  the current position. Branching is in-place, no new file.
- `/tree` selection behavior:
  - user/custom message → leaf = **parent**, text placed in the editor;
  - non-user message → leaf = selected node;
  - root user message → leaf = `null` (empty conversation).
- `/fork`: opens a selector, copies the path up to the selected point into a
  new session file, puts the message in the editor. Never summarizes.
- Entry types: `message`, `compaction`, `branch_summary`, `custom_message`,
  `label`.

**QOC mapping**

- `SessionEvent.SessionForked(SessionId, SessionId parentId, ForkBoundary)` and
  `ForkBoundary` already exist.
- Gap: `session_message` has `seq`, but no `parent_id`/`leaf`; no
  `POST /api/session/{id}/fork`, no `GET /tree`, no UI.
- Gap: QOC's store is flat, not a tree. A pragmatic baseline: fork copies
  messages up to the boundary into a new session; the `/tree` view is a
  filtered user-message list (like pi's `/fork`).

### 2.6 Message queue (steer vs. follow-up)

**pi reference:** `src/core/agent-session.ts`, `docs/settings.md`

- While streaming, `prompt()` **requires** `streamingBehavior` (`steer` or
  `followUp`); otherwise it throws.
- Queues: `_steeringMessages`, `_followUpMessages`; modes `all` /
  `one-at-a-time`.
- Steering is delivered after the current tool execution and interrupts the
  remaining tools; follow-up is delivered only after the agent finishes.
- Escape aborts and restores queued messages to the editor; Alt+Up retrieves
  them.
- UI: Enter = steer, Alt+Enter = follow-up.

**QOC mapping**

- `Delivery.STEER`/`QUEUE` already exists, and `SessionService` handles
  admission/delivery.
- Gap: `PromptRequest` has no `delivery` field; the web UI blocks on one prompt.
- Implementation: add `delivery` to `PromptRequest`, map to `Delivery`, add the
  UI shortcut and a queue indicator.

### 2.7 Settings

**pi reference:** `docs/settings.md`, `src/core/settings-manager.ts`

- Global `~/.pi/agent/settings.json`, project `.pi/settings.json`; project
  overrides global per key.
- Relevant groups: `compaction.{enabled,reserveTokens,keepRecentTokens}`,
  `branchSummary.reserveTokens`, `retry.{enabled,maxRetries,baseDelayMs,
  maxDelayMs}`, `steeringMode`, `followUpMode`, `transport`,
  `images.{autoResize,blockImages}`, `shellPath`, `shellCommandPrefix`,
  `enabledModels`, `defaultProvider`, `defaultModel`, `defaultThinkingLevel`.
- Project settings are merged, not replaced.

**QOC mapping**

- `EngineConfig` already merges global + project field-by-field.
- Add: `compaction.reserveTokens`, `compaction.keepRecentTokens`,
  `retry.*` (QOC currently has fixed retry behavior), `delivery` defaults,
  `images.blockImages`, `shellCommandPrefix`.
- Retry defaults from pi: 3 retries, base 2 s, exponential (2/4/8 s), cap 60 s;
  a provider `retry-after` above the cap fails fast instead of waiting.

### 2.8 Slash commands and export

**pi reference:** `src/core/slash-commands.ts`, `docs/coding-agent` README

Built-ins: `settings`, `model`, `scoped-models`, `export`, `share`, `copy`,
`name`, `session`, `changelog`, `hotkeys`, `fork`, `tree`, `login`, `logout`,
`new`, `compact`, `resume`, `reload`, `quit`.

QOC has `/compact`, `/revert`, `/title`, `/new`, `/interrupt`.

Add (client-side unless noted):

| Command | Action |
|---|---|
| `/copy` | copy last assistant text |
| `/export [file]` | `GET /api/session/{id}/export?format=html` |
| `/session` | `GET /api/session/{id}` + cost/tokens |
| `/name <title>` | `POST /api/session/{id}/title` |
| `/reload` | `POST /api/reload` (server) |
| `/hotkeys`, `/changelog` | static client content |
| `/tree`, `/fork` | session tree endpoints |
| `/skill:<id>` | skill invocation |

### 2.9 Image attachments

**pi reference:** `src/utils/clipboard-image.ts`, `src/utils/image-convert.ts`,
`src/utils/image-resize.ts`, `docs/settings.md` (`images.*`).

- Paste/drag → base64 `ImageContent { data, mimeType }`.
- Auto-resize to max 2000×2000 by default; `blockImages` can disable images.

**QOC mapping**

- `ModelContent.Media` and `NioFileSystem.read` exist; the UI and `ReadTool`
  are text-oriented.
- Add a size/dimension limit and MIME allow-list before sending to the provider.

### 2.10 Extended tool parity (reference)

pi's four tools (`read`, `write`, `edit`, `bash`) plus `grep`, `find`, `ls`
are deliberately small. QOC already has a superset. The only pi tool behaviour
worth copying:

- `read`: line-range reading with a `limit` and `offset` (QOC has this).
- `edit`: exact-text replacement with uniqueness check (QOC has this).
- `bash`: timeout + background + output truncation (QOC has this).
- `grep`/`find`: ignore-file awareness (QOC's `grep`/`glob` should respect
  `.gitignore`; check `GrepTool`/`GlobTool`).

---

## 3. What does not fit

| pi feature | Reason |
|---|---|
| Extensions (TypeScript modules) | QOC is Java; needs a plugin SPI + classloader. Large architectural step; defer. |
| Pi Packages (npm/git) | No package manager in Java. Skills/paths are already configurable. |
| Themes, Doom, games | Client-only; the QOC web UI is a demo. |
| RPC mode (stdin/stdout JSON-RPC) | HTTP/SSE already covers it. |
| Web UI (`web-ui` package) | Separate product. |
| Mom (Slack bot), Pods (GPU deploy), LangGraph | Separate products. |
| Transport preference (sse/ws/auto) | HTTP/SSE is sufficient. |
| Branch labels/bookmarks | UI niche, high effort. |

---

## 4. Recommended order

1. ✅ **2.1** context files + system-prompt files — **done (2026-10-24)**.
2. **2.2** prompt templates (matches L-6).
3. **2.7 + 2.8** `/reload`, settings keys and extended slash commands.
4. **2.4** compaction details (reserve/keep tokens, custom instructions,
   cumulative file lists).
5. **2.8** export + cost/token footer.
6. **2.9** image paste.
7. **2.6** message queue UI.
8. **2.5 / 2.4** branching/fork + branch summarization.

---

## 5. Open decisions

- Should context-file loading be **on by default** (like pi) or opt-in?
- Should prompt templates live in `<workspace>/command/` (spec L-6) or
  `<workspace>/.opencode/prompts/` (pi-style)?
- Should `/reload` re-read `settings.json` field-by-field (current merge
  semantics) or replace it entirely?
- Do we adopt pi's token-budget compaction (`reserveTokens`/`keepRecentTokens`)
  or keep QOC's message-count `keepMessages`?
- Is image paste worth the MIME/binary handling in `ReadTool`/`WriteTool`?
- Do we want a real plugin SPI, or is the extension model out of scope for QOC?

---

*Created: 2026-10-24 · Updated with pi source-level details*
