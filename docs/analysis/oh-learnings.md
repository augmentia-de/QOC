# oh-learnings.md – What QOC can learn from oh-my-pi

> **Status:** Analysis · 2026-10-26
> **Reference:** `oh-my-pi` at `0b5c27a` (2026-10-22), a fork of
> Mario Zechner's pi: TypeScript/Bun + Rust native crates
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus / LangChain4j),
> `qoc` at `24f0273`
> **Source scope:** this document is based only on the cloned `oh-my-pi`
> repository and a comparison with the QOC implementation.

oh-my-pi is not a minimal agent. It keeps pi's tool loop and session model and
adds an IDE-grade surface: anchored edits, LSP, DAP, persistent Python/Bun
kernels, native search, batch subagents, memory, TTSR stream rules, an advisor
model, internal URL schemes, and a large provider catalog. QOC does not need to
copy that breadth. The useful lessons are the mechanisms behind it: how
oh-my-pi makes edits reliable, keeps context small, keeps long sessions usable,
and turns tool output into something the model can revisit instead of losing.

This document deliberately does not repeat `pi-learnings.md`. Where oh-my-pi
still behaves like pi, the pi analysis remains the reference. The focus here is
the delta.

---

## Contents

- [1. Shared concepts already in parity](#1-shared-concepts-already-in-parity)
- [2. Adoptable features and oh-my-pi implementation details](#2-adoptable-features-and-oh-my-pi-implementation-details)
  - [2.1 Context discovery, sticky rules and prompt blocks](#21-context-discovery-sticky-rules-and-prompt-blocks)
  - [2.2 Hashline edits and anchored reads](#22-hashline-edits-and-anchored-reads)
  - [2.3 Read, grep, glob and the scan cache](#23-read-grep-glob-and-the-scan-cache)
  - [2.4 LSP as a first-class tool](#24-lsp-as-a-first-class-tool)
  - [2.5 Task batches, async subagents and isolation](#25-task-batches-async-subagents-and-isolation)
  - [2.6 Approval tiers, policies and modes](#26-approval-tiers-policies-and-modes)
  - [2.7 Compaction, branch summaries and handoff](#27-compaction-branch-summaries-and-handoff)
  - [2.8 Session tree, fork, export and checkpoint](#28-session-tree-fork-export-and-checkpoint)
  - [2.9 Internal URLs, blobs and artifacts](#29-internal-urls-blobs-and-artifacts)
  - [2.10 Bash runtime and managed jobs](#210-bash-runtime-and-managed-jobs)
  - [2.11 Hooks and extensions](#211-hooks-and-extensions)
  - [2.12 Memory, advisor and TTSR](#212-memory-advisor-and-ttsr)
  - [2.13 Model roles and per-model policy](#213-model-roles-and-per-model-policy)
- [3. What does not fit](#3-what-does-not-fit)
- [4. Recommended order](#4-recommended-order)
- [5. Open decisions](#5-open-decisions)

---

## 1. Shared concepts already in parity

| Concept | oh-my-pi | QOC |
|---|---|---|
| Agent loop | Streaming tool loop, retries, multiple providers | Streaming tool loop, one OpenAI adapter |
| File tools | `read`, `write`, `edit`, `patch`/`apply_patch` | `read`, `write`, `edit`, `patch` |
| Search | Native `grep`, `glob`, `find`, AST variants | `grep`, `glob`, JVM `Files.walk` |
| Shell | `bash` with timeout, background jobs, PTY and services | `shell` with timeout, background start, scanner, HITL |
| Subagents | `task`, batch, async, typed output, worktrees | `subagent`, depth 2, text output, background flag |
| Skills | Agent Skills, discovery providers, managed skills | `SkillCatalog` + `SkillScanner`, project/global roots |
| MCP | Native client/server manager, tool naming, resources | `McpServerManager`, stdio/HTTP, resources |
| Web | `web_search` with many providers, universal read | `websearch` (Firecrawl), `webfetch` |
| Human input | `ask` option picker | `question` tool + pending-question API |
| Todos | `todo` tool and reminders | `todo` tool and API |
| Context files | `AGENTS.md`, `CLAUDE.md`, providers, imports, sticky rules | `NioInstructionLoader`, `SYSTEM.md`, `APPEND_SYSTEM.md` |
| System prompt | Ordered provider blocks and cache breakpoints | Ordered `List<String>` system blocks |
| Permissions | `read`/`write`/`exec` tiers, modes, user/tool policies | rule chain, saved approvals, HITL, shell scanner |
| Compaction | Token budget, pruning, shake, snapcompact, branch summary | Message-count compaction, summary blob, epoch |
| Sessions | JSONL session tree, fork, export, resume, share | SQL event log + message table, resume, fork event only |
| Revert | Edit snapshots, checkpoint/rewind | Pre-turn workspace snapshot, message deletion |
| Output bounding | 50 KiB inline, artifact spill, `artifact://` | 50 KiB inline, `outputPath`, no URI |
| Hooks | Full extension runner with session/agent/tool events | `Hooks` before/after tool and permission only |
| Memory | Five backends, background extraction, memory URLs | none |
| IDE integration | LSP (14 actions), DAP (28 ops), AST tools | none |
| Model policy | Roles, fallback chains, compact/capability policy | Per-agent model id, no roles |

Two asymmetries are important when reading the rest:

- QOC is stronger in **strict layering and durable state**. `engine.core` is pure
  Java, the event journal is append-only, and SQLite/Postgres persistence is a
  first-class boundary. oh-my-pi accepts a looser, JSONL/session-file model to
  make branching and export easy.
- oh-my-pi is stronger in **tool ergonomics and context economy**. Most of the
  mechanisms below exist to reduce retries, context bloat and lost work. That is
  where QOC has the most to gain.

---

## 2. Adoptable features and oh-my-pi implementation details

### 2.1 Context discovery, sticky rules and prompt blocks

**oh-my-pi reference:** `docs/context-files.md`,
`docs/system-prompt-customization.md`,
`packages/coding-agent/src/system-prompt.ts`,
`packages/coding-agent/src/discovery/*`

oh-my-pi replaces pi's small context loader with a provider-based discovery
system:

- Roughly twenty providers know where other tools keep instructions: native
  `.omp/`, Claude, Codex, Gemini, OpenCode, GitHub Copilot, Cursor, Windsurf,
  Cline, VS Code, plugin package formats, and standalone `AGENTS.md` /
  `CLAUDE.md`.
- The native provider uses the **nearest non-empty `.omp/` directory** found
  while walking from the working directory toward the repository root. Once
  found, a missing `AGENTS.md` does not make discovery continue upward.
- One user context file and one project context file per depth survive.
  Higher-priority providers shadow lower-priority ones at the same depth;
  different depths coexist. `@` imports are expanded before shadowing, and a
  farther ancestor is dropped when a closer file contains its full paragraph
  sequence contiguously.
- `RULES.md` is separate from context files. Its body becomes an
  **always-apply rule carried on every request**, so it survives a long
  conversation and compaction.
- `@path` imports resolve from the importing file, support `~/` and absolute
  paths, recurse five hops, skip cycles, and leave missing targets literal.
  Imports inside fenced code and inline code are not expanded.
- Discovered files are injected as one `<repo-rules>` block inside a trailing
  `<project-context>` footer. Static prompt blocks come first; all
  working-directory-derived content sits in the footer.
- On Anthropic, the cache breakpoint lands on the last static block before the
  footer. Worktrees and subagent spawns in different directories therefore
  share the static prompt cache prefix.
- `SYSTEM.md`, `SYSTEM_TEMPLATE.md`, `APPEND_SYSTEM.md`, and CLI flags have a
  documented precedence. `SYSTEM.md` replaces the default instruction template
  but keeps generated surfaces (skills, rules, project context, append text).
  `SYSTEM_TEMPLATE.md` is a Handlebars template and can intentionally omit
  generated sections.

**QOC mapping**

- `NioInstructionLoader` already implements the pi-style baseline:
  - global `AGENTS.md` / `CLAUDE.md`;
  - every ancestor from the filesystem root to the working directory, with
    `AGENTS.md` before `CLAUDE.md` per directory;
  - `SYSTEM.md` and `APPEND_SYSTEM.md` from `<cwd>/.opencode`, `<cwd>`, then
    the global directory;
  - 32 KiB per-file cap and unreadable files skipped.
- Missing pieces:
  - no `.opencode/` project context file (only `SYSTEM.md` uses it);
  - no nearest-config-directory rule and no provider priority;
  - no `RULES.md` sticky content;
  - no `@path` imports, containment shadowing or path labels;
  - no per-depth shadowing beyond “one file per directory”;
  - `ContextBuilder` appends every instruction as another `String` in
    `system`, and `OpenAiRequestEncoder` joins all system strings with
    `\n\n`. Block boundaries and cache breakpoints are therefore invisible to
    the provider.
- `CacheAffinity` already gives a stable prompt cache key and headers, so the
  missing part is structural, not transport-level.

**Implementation direction**

Add a small, explicit model instead of copying all providers:

```java
public interface InstructionLoader {
    List<InstructionFile> load(Path startDirectory);

    record InstructionFile(Path path, String text, Kind kind, String provider) {}

    enum Kind { SYSTEM, RULES, AGENTS, APPEND_SYSTEM }
}
```

- Add `RULES.md` as `Kind.RULES`; keep it in every `ContextBuilder.prepare`
  call rather than only at session start.
- Add `@path` expansion inside the loader. Keep the five-hop, code-fence and
  missing-target rules exactly; they are cheap and predictable.
- Add `provider` and `depth` metadata so a future provider registry can shadow
  by depth. Do not add twenty providers now. Native `.opencode` plus
  `.claude`/`AGENTS.md` is enough for QOC.
- Group project-derived instructions into one trailing context block. Keep the
  base prompt, skill catalogue and sticky rules in static blocks before it.
- If the provider API later gains cache-control blocks, use the same split.
  For OpenAI today, the stable order is still useful because prompt caching is
  prefix-based.
- Add tests for nearest-directory ownership, import cycles, code-fence
  immunity, RULES persistence across compaction, and deterministic order.

### 2.2 Hashline edits and anchored reads

**oh-my-pi reference:** `docs/tools/edit.md`,
`packages/coding-agent/src/edit/*`,
`packages/coding-agent/src/tools/index.ts`,
`crates/pi-edit/src/modes/hashline/`,
`crates/pi-edit/grammars/`, `crates/pi-edit/prompts/`

oh-my-pi's default edit mode is **hashline**, a line-anchored patch language
with a content-hash tag. It is the single highest-leverage tool idea in the
repository.

- `read`, `grep` and a successful `edit` return a header
  `[PATH#TAG]`, where `TAG` is four uppercase hexadecimal characters derived
  from the whole normalized file. The tag is recorded in a session snapshot
  store.
- An edit copies the tag from the latest anchored result. Untagged, placeholder
  (`#AUTO`, `#????`) and stale-tag edits are rejected or recovered through the
  stored snapshot chain.
- Input is one or more sections in a strict grammar (optional
  `*** Begin Patch` / `*** End Patch` envelope). Each section edits one file.
- Operations address the **original tagged snapshot**, so line numbers do not
  shift between hunks:
  - `PUT N.=M:` replace inclusive lines;
  - `PUT N*:` replace a syntactic block beginning at line `N`;
  - `PUT <N:` / `PUT >N:` insert before/after a line;
  - `PUT >$:` append at file tail;
  - `CUT N.=M` / `CUT N*` delete and capture a register;
  - `PUT ... @name` paste a named register;
  - `REM` delete; `MV DEST` write then delete.
- Body rows are final content (`+TEXT`), never a unified-diff before/after pair.
  Named registers persist for the session; the anonymous register is
  batch-local.
- Block forms use tree-sitter to resolve the construct from its opener. Leading
  decorators and doc comments are handled only when the parser groups them with
  the declaration.
- With `edit.enforceSeenLines` on by default, lines outside the read/displayed
  ranges cannot be edited without re-reading them. Mismatch replies include
  current context and the current tag.
- The tool prepares all sections before writing, merges same-path sections,
  rejects overlapping ranges, and returns a fresh tag plus a compact preview
  and optional diagnostics. It has limited parser recovery for model slips and
  warns when it repairs input.
- Modes are selected per model (`replace`, `patch`, `apply_patch`, `hashline`,
  `sloppy`). Hashline also has constrained-decoding grammars for providers that
  support them, and a compact prompt variant for smaller models.

**Why it matters**

`old_string` replacement is brittle for real edits: indentation drift, repeated
snippets, large functions, multi-file refactors, and stale context all cause
retries. Hashline makes the anchor explicit, detects stale reads, lets one call
cover several hunks and files, and gives the model a cheap way to cite an
exact line range.

**QOC mapping**

- `EditTool` is exact-anchor replacement:
  - `path`, required `old`, optional `new`;
  - `content.indexOf(old)`;
  - fails if the anchor is absent or appears more than once;
  - reads and rewrites the whole file, with no diff, tag, seen-range check or
    diagnostics.
- `PatchTool` applies a small unified diff to one file. It is closer to
  `apply_patch`, but still has no file content tag and no multi-file safety.
- `ReadTool` returns `N: text` lines with page limits, not a file header tag.
  `GrepTool` returns flat `path:line: text` lines, not per-file anchored
  sections.
- `FileSystem` has `read`, `write`, `stat`, `list`, `glob`, `search` and
  `realPath`; it has no per-session snapshot store for edits.

**Implementation direction**

Do not switch the default immediately. Add edit modes and make the anchor
pipeline a mode:

1. **`EditStore` (session-scoped, in-memory first).**
   - `Map<Path, Snapshot>` with normalized content hash, byte snapshot, and
     seen line ranges.
   - On read/grep, record the file and emitted ranges.
   - On resume, either re-read and recalculate the tag, or drop seen ranges and
     require a fresh read.
2. **Tag format.**
   - Use eight hex characters internally if collision resistance matters; keep
     four for model familiarity only if the store, not the tag alone, proves
     identity. Four hex has only 65,536 values and is not a content address.
   - Resolve a tag through the stored snapshot; never accept a tag as proof of
     content by itself.
3. **Parser.**
   - Start with `PUT N.=M`, `PUT <N`, `PUT >N`, `PUT >$`, `CUT N.=M`, `REM`,
     `MV`, and named registers. Skip `N*` block resolution until a Java parser
     is available; a failed block anchor should produce a clear error, not a
     guess.
   - Preserve the original-line coordinate rule and prepare all sections
     before writing.
   - Reject unified-diff contamination (`@@`, `-old` rows) with actionable
     text.
4. **Read/grep integration.**
   - Add `[path#tag]` headers and bare line numbers when hashline is enabled.
   - Keep the existing `offset`/`limit` paging, but record the exact emitted
     ranges.
   - `grep` groups matches by file and includes the tag only for mutable local
     files.
5. **Write safety.**
   - Use `FileAccess.resolve(..., false)` and preserve the external-directory
     gate.
   - Reject binary, non-regular, generated and symlinked-outside-workspace
     targets consistently.
   - After a successful write, capture a fresh tag and optionally attach LSP
     diagnostics (see 2.4).
6. **Compatibility.**
   - Keep `replace` and `patch` available and configurable per agent/model.
   - Add an `edit.mode` setting with `replace` as the conservative default
     until the anchor tests and prompt guidance are stable.

### 2.3 Read, grep, glob and the scan cache

**oh-my-pi reference:** `docs/tools/read.md`, `docs/tools/grep.md`,
`docs/tools/glob.md`, `docs/fs-scan-cache-architecture.md`,
`crates/pi-walker/src/cache.rs`, `crates/pi-natives/src/grep.rs`

The three filesystem tools are much more than thin wrappers around a walk.

**`read`**

- One `path` string carries a trailing selector:
  - `:N`, `:A-B`, `:A+C`, `:-N`, comma lists such as `:5-16,960-973`;
  - `:raw` disables summaries and line prefixes;
  - `:img` rasterizes local SVG for vision;
  - `:conflicts` indexes unresolved Git conflict regions.
- Reads can be files, directories, archives, SQLite databases, PDFs, images,
  videos, profiler reports, web URLs, and internal URLs.
- Large parseable files get a structural summary (selected declarations with
  elided spans and a “re-read these ranges” footer) instead of a raw dump.
  Raw mode bypasses this.
- Directory listing is a recency-sorted tree with depth and per-directory
  limits.
- File reads record snapshots and seen lines for hashline.

**`grep`**

- Accepts files, directories, globs, semicolon-delimited path lists, archive
  members, web/internal URLs, and line-range selectors.
- Defaults: case-sensitive, `.gitignore` enabled, one line before and three
  after context, hidden files included.
- Native search tries Rust regex, then PCRE2, then a literal fallback.
- Pagination is by file page (20 files), with 20 matches per file for
  multi-file scopes and 200 for a single file. Match lines are capped at 512
  UTF-8 bytes with a window around the first match.
- A 30-second timeout and a 4 MiB per-file window bound the scan.
- Output is grouped by file; in hashline mode the header carries the file tag
  and emitted lines are recorded as seen.

**`glob`**

- Parses the base path and glob separately; bare globs are made recursive
  (`*.ts` becomes `**/*.ts`), explicit `dir/**/*.ts` stays recursive.
- Defaults: hidden files included, `.gitignore` enabled, 200 results, sorted
  by modification time, 5-second timeout.
- Multi-root calls run concurrently; a timed-out scan returns partial results
  and explicitly says the result is not proof of absence.
- Uses the native walker and `InternalUrlFilesystem` for URL roots.

**Scan cache**

- `pi-walker` caches **directory walk entries**, not final grep or glob results.
- Key: canonical root plus the complete effective walk options.
- Defaults: 1-second TTL, 200 ms empty-result recheck threshold, 16 entries,
  64 MiB payload budget. Expiration is lazy, not a background thread.
- `glob`, fuzzy find and AST discovery can opt in; `grep` explicitly disables
  the cache.
- Every successful write, delete and rename made through the process
  invalidates the affected roots. The cache is process-local, so writes made
  by another process are bounded only by the TTL.

**QOC mapping**

- `ReadTool`:
  - `path`, `offset`, `limit`; 2,000 lines / 50 KiB / 2,000-character line
    caps; NUL-byte binary check; directory listing.
  - It has no selector grammar, summary, archive/SQLite/PDF support, conflicts,
    or tags.
- `GrepTool`:
  - one regex and optional path; `MAX_HITS = 100` globally;
  - no case flag, context lines, `.gitignore`, hidden policy, per-file cap,
    file paging, timeout or binary/size precheck;
  - `NioFileSystem.search` uses `Files.walk`, reads **every line of every
    regular file** with `Files.readAllLines`, and skips files that throw.
- `GlobTool`:
  - `pattern` and optional path; `MAX_HITS = 100`;
  - `NioFileSystem.glob` uses `Files.walk` and a single `PathMatcher`; no
    ignore, hidden, depth, symlink or timeout policy.
- There is no scan cache. A `grep` followed by a `glob` in the same turn
  walks the tree twice.

This is a concrete reliability gap: a 100-hit global cap can hide the
important match, a binary file can be decoded as text, and a large generated
directory can dominate a call.

**Implementation direction**

- Extend `FileSystem` rather than putting walk logic in each tool:

```java
record WalkOptions(boolean hidden, boolean gitignore, int maxDepth,
                   long maxFileBytes, Duration timeout, boolean followLinks) {}

record SearchOptions(WalkOptions walk, boolean caseSensitive,
                     int contextBefore, int contextAfter,
                     int maxPerFile, int maxFiles, int skipFiles) {}

List<SearchMatch> search(Path root, Pattern pattern, SearchOptions options);
List<Path> glob(Path root, String pattern, WalkOptions options);
```

- Implement ignore matching in the adapter. A small `.gitignore` parser plus
  built-in exclusions (`.git`, `node_modules`, `target`, `.worktrees`, build
  output) is enough; do not shell out to Git for every call.
- Detect binary by NUL and UTF-8 decoding, cap file size before reading, and
  return explicit skipped/truncated metadata.
- Add tool arguments `case`, `gitignore`, `hidden`, `context`, `limit`, `skip`
  and `timeout`. Replace the flat global `MAX_HITS` with a file page plus
  per-file cap.
- Add a `CachedFileSystem` decorator keyed by root + options, with a short TTL
  and invalidation from `write`/`edit`/`patch`. Shell-created files are outside
  the decorator’s knowledge, so keep the TTL short (1 second, as oh-my-pi does)
  and document the limitation.
- For `read`, add an explicit range/selector field first. A path-suffix grammar
  is convenient but collides with filenames containing `:`; QOC can avoid that
  ambiguity without losing the model ergonomics.
- Add a cheap line-based summary for large files before attempting structural
  summaries. Full AST summaries require a parser dependency and should not be
  the first step.
- Preserve the existing 50 KiB output bound, but let the tool report paging
  metadata instead of silently truncating to 100 hits.

### 2.4 LSP as a first-class tool

**oh-my-pi reference:** `docs/tools/lsp.md`,
`packages/coding-agent/src/lsp/*`, `docs/lsp-config.md`

oh-my-pi exposes language servers through one `lsp` tool with fourteen
actions: `diagnostics`, `definition`, `references`, `hover`, `symbols`,
`rename`, `rename_file`, `code_actions`, `type_definition`, `implementation`,
`status`, `reload`, `capabilities`, and raw `request`.

Important behavior:

- Server config is merged from built-in defaults and project/user/plugin
  overrides, filtered by root markers and executable availability, then
  selected per file type.
- Clients are cached by spawn command, cwd, arguments, initialization options
  and settings. Shared mux transport is optional; a private process is the
  fallback.
- Diagnostics handle push and pull models, wait budgets, project-load
  progress, document reconciliation and deduplication. Workspace mode can run
  `cargo check`, `tsc`, `pyright` or `go build`.
- `rename_file` sends `workspace/willRenameFiles` to every matching server,
  collects the returned `WorkspaceEdit`s, applies them atomically with the
  filesystem move, and sends `workspace/didRenameFiles`. A failed move rolls
  the reference edits back.
- `rename` returns a `WorkspaceEdit`; apply or preview is explicit.
- `code_actions` can resolve a code action, apply its edit, and execute a
  command.
- Read-only actions request read approval; rename/reload/raw request write
  approval. Empty navigation results are marked `useless` so compaction can
  elide them.

**QOC mapping**

QOC has no LSP client, language-server process, diagnostics model or symbol
navigation. `EditTool` writes text; `PatchTool` applies a diff; `GrepTool` finds
strings. There is no way to answer “who calls this method”, “is this type
correct”, or “rename this symbol across the workspace”.

**Implementation direction**

- Add a neutral core contract, not an LSP-specific one:

```java
public interface LanguageServer {
    Diagnostics diagnostics(Path file);
    List<Location> definition(Path file, Position position);
    List<Location> references(Path file, Position position);
    WorkspaceEdit rename(Path file, Position position, String newName);
    List<CodeAction> codeActions(Path file, Position position);
    // lifecycle, symbols, hover, status, reload
}
```

- Put the implementation in `engine.adapter.lsp`, using either LSP4J or a small
  JSON-RPC client. A new dependency is justified here because writing the
  protocol, capability negotiation and document synchronization by hand is
  more fragile than the dependency.
- Start with a single `lsp` tool and a bounded action set:
  `diagnostics`, `definition`, `references`, `symbols`, `hover`, `rename`,
  `rename_file`, `code_actions`, `status`, `reload`.
- Configure servers in `.opencode/lsp.json` or a new `lsp` section in
  `EngineConfig`. QOC’s Java stack makes Java/Kotlin support the obvious first
  target, but the tool is language-agnostic.
- Integrate **diagnostics after edit**: return a warning list in the edit
  result and optionally run a bounded diagnostics wait. Do not roll an edit
  back merely because diagnostics appear; oh-my-pi treats a new syntax error
  as a warning (with optional auto-repair), not an automatic revert.
- If hashline `MV`/`rename_file` is adopted, implement the
  `workspace/willRenameFiles` flow. This is the difference between a rename and
  a broken import graph.
- Keep approval tiers: read-only navigation is read; rename, reload and code
  action application are write.
- Test against a fake LSP server first. Real servers are environment-dependent
  and must not make `mvn test` flaky.

### 2.5 Task batches, async subagents and isolation

**oh-my-pi reference:** `docs/tools/task.md`, `docs/task-agent-discovery.md`,
`packages/coding-agent/src/task/*`, `packages/coding-agent/src/sdk.ts`

oh-my-pi's `task` tool is a small orchestration runtime:

- Batch shape is `{ context, tasks: [...] }`; one subagent is spawned per item.
  Each item carries `name`, `agent`, `task`, `solutionSpace`, optional `effort`,
  `outputSchema`, `schemaMode`, `tools`, and `isolated`.
- `context` is shared background rendered into every child system prompt.
- Results are schema-validated objects, not prose. Child output is also written
  to an agent output file and addressable as `agent://<id>`.
- `async: true` returns a job handle; `wait` retrieves completion. Agent Hub can
  inspect live transcripts, steer, revive or kill workers.
- `isolated` creates a per-task git worktree, so sibling edits do not collide.
  The parent sees the result and can inspect or merge the worktree.
- Children can receive an explicit tool subset and their own model/effort.
- The parent task approval is the authorization boundary. A headless child
  cannot answer a per-tool prompt; user denies remain absolute.

**QOC mapping**

- `SubagentTool` accepts `agent`, `prompt`, optional `description` and
  `background`.
- `SubagentRunner` returns `{ text, usage, childSession }`. `DEFAULT_MAX_DEPTH`
  is 2.
- Foreground runs to completion. Background starts a daemon thread and stores
  the result in a **static** `ConcurrentHashMap<SessionId, String>`; a failure is
  caught and discarded. There is no task id, status, wait, cancel or result
  schema.
- `WorktreeTool` can create/list/remove git worktrees, but the subagent runner
  has no cwd or isolation parameter. Worktrees are a manual tool, not a task
  policy.
- QOC already has structured output via the synthetic `submit_result` tool.
  That mechanism can be reused for typed child results, but the subagent
  plumbing currently only returns text.

**Implementation direction**

Phase 1 — typed batches:

- Add `tasks[]` to `SubagentTool` with per-item `agent`, `task`, optional
  `name`, `outputSchema` and `tools`.
- Extend `SubagentRunner` to accept a request object and return an optional
  structured value:

```java
record SubagentRequest(SessionId parent, String agent, String prompt,
                       int depth, Option<JsonValue> outputSchema,
                       Option<List<ToolName>> tools, Option<Path> cwd) {}

record SubagentResult(String text, Option<JsonValue> structured,
                      TokenCount usage, SessionId childSession) {}
```

- Reuse `SubmitResult.withTool` for the child, reserve the tool name, and
  return the structured value in the parent `ToolResult.output`.
- Run independent items on virtual threads with a bounded semaphore. Preserve
  depth accounting so a child cannot bypass `DEFAULT_MAX_DEPTH`.

Phase 2 — job registry:

- Replace the static result map with a session-scoped `TaskRegistry` holding
  task id, state (`queued`, `running`, `succeeded`, `failed`, `cancelled`),
  output, usage, child session and timestamps.
- Add `wait`/`status`/`cancel` tools or one `task` control action. Do not make
  the model poll through a blocking parent prompt.
- Surface background failures instead of dropping them.

Phase 3 — isolation:

- Add `isolated: true` only when the workspace is a Git repository. Create a
  worktree, run the child with that cwd, then report the worktree path and diff.
- Do not auto-merge. Let the parent inspect and apply. A merge tool is a
  separate, high-risk feature.

### 2.6 Approval tiers, policies and modes

**oh-my-pi reference:** `docs/approval-mode.md`,
`packages/coding-agent/src/tools/approval.ts`

oh-my-pi separates three inputs:

1. **Tool tier** — `read`, `write`, `exec`. Unknown tools and invalid decisions
   default to `exec`; MCP tools default to `write`.
2. **Tool policy** — object-form approval can force `allow`, `deny` or `prompt`,
   optionally with a reason, an override flag and a policy key.
3. **User policy** — `tools.approval.<tool>` overrides the mode, but cannot
   bypass a tool deny/prompt or a safety override.

Modes:

| Mode | Auto-approves | Prompts |
|---|---|---|
| `always-ask` | read | write, exec |
| `always-ask-cached` | read without file targets, granted files/folders | file reads, write, exec |
| `write` | read, write | exec |
| `yolo` (default) | read, write, exec | none |

Additional behavior worth copying:

- Grants record the capability tier; a write grant covers reads of the same
  target, a read grant never covers write or exec.
- File reads are keyed by resolved path and selector, with symlink and case
  normalization. A recursive search needs a tree grant.
- `bash` uses object-form approval for critical patterns such as `rm -rf /`,
  fork bombs, remote-fetch-then-execute and shutdown commands. `bash.patterns`
  can also allow/deny/prompt simple commands; compound-chain approval is
  opt-in.
- Approval is resolved again after a tool-call hook revises the input.
- A call that still needs a prompt fails closed when no interactive UI exists.

**QOC mapping**

- `PermissionRules` is a pure chain: build agent/session rules, denial
  pre-check, add saved approvals, ask dominates allow, then run the extension
  hook. Default with no rules is deny; default with rules but no match is ask.
- `PermissionEngine` performs deny-before-approval, resolves via
  `ApprovalSource`, stores grants in `ApprovalStore`, and keeps two channels.
- `ToolOptions.permission` is a free-form label (`"edit"`, `"worktree"`,
  etc.), not a read/write/exec tier. `EngineConfig` has per-agent permissions
  but no global mode and no `tools.approval` map.
- `SavedApproval` is `(project, action, resource)`; there is no target list,
  folder grant, tier hierarchy, or session-vs-persistent lifetime choice.
- QOC’s shell scanner already extracts commands and external directories, and
  the permission gate checks external paths. It does not implement
  command-pattern allow/deny/prompt policy.

**Implementation direction**

- Add `ApprovalTier { READ, WRITE, EXEC }` to `ToolDefinition` or
  `ToolOptions`, and assign it to every built-in:
  - read: read, list, grep, glob, lsp navigation, diagnostics;
  - write: edit, patch, write, rename, worktree create/remove;
  - exec: shell, subagent/task, web fetch that executes, MCP tools by default.
- Add `ApprovalMode { ALWAYS_ASK, ALWAYS_ASK_CACHED, WRITE, YOLO }` in
  `EngineConfig`, but keep `ALWAYS_ASK` as QOC’s default. oh-my-pi’s `yolo`
  default is appropriate for its local TUI, not for a server engine.
- Keep `PermissionRules` as the final authority. User policy should be
  consulted before the mode, and a tool deny must remain absolute.
- Extend `SavedApproval` with tier and target scope. Preserve the existing
  `(action, resource)` record for compatibility and add a new table or columns
  in a migration.
- Add target extraction to read/write tools. For shell, keep the scanner and
  add explicit command-pattern policy.
- Re-resolve approval after a hook modifies input, and fail closed without an
  interactive channel.

### 2.7 Compaction, branch summaries and handoff

**oh-my-pi reference:** `docs/compaction.md`,
`packages/agent/src/compaction/*`,
`packages/coding-agent/src/session/session-context.ts`,
`docs/handoff-generation-pipeline.md`

oh-my-pi treats compaction as a family of strategies, not one “summarize old
messages” prompt:

- **Context-full compaction** rewrites old history into a summary on the
  current branch. It uses token budgets and reserve/keep settings, keeps a
  recent tail, and records the summary as a session entry.
- **`firstKeptEntryId`** is the durable boundary. Rebuilding the model context
  folds entries through a summary and resumes from the first kept entry.
- **Pruning** drops stale tool results, and **shake** mechanically elides large
  content without an LLM call.
- **Snapcompact** archives history as dense bitmap images. This is a
  provider-vision strategy, not a universal one.
- **Branch summaries** capture abandoned branch context during tree navigation.
  They are persisted like compaction entries and converted back into
  `branchSummary` messages on context rebuild.
- **Handoff** generates a document from the current context, commits it as a
  compaction entry, and gives a new session a compact carrier for prior work.
- Provider-native streaming compaction and OpenAI-specific paths exist, but the
  invariant is the same: the raw log is not rewritten; compaction is a view.
- A compaction entry can be displayed, resumed, forked and exported.

**QOC mapping**

- `CompactionService` keeps the last `keepMessages` (default 8) messages and
  sends everything before them to `CTX-SUMMARY`. `CTX-SUMMARY-UPDATE` is used
  when a predecessor exists.
- Auto-compaction is triggered only after a provider overflow
  (`StepResult.NeedsCompaction`) or manually via the HTTP endpoint. There is no
  proactive token threshold based on the model’s context limit.
- `CompactionWindow.split` counts messages, not tokens or turns. It can keep
  several tiny messages while dropping a large logical turn, or keep a huge
  tool result because it happens to be among the last eight.
- The summary is stored as a blob; the instruction state stores
  `compaction.summary -> Integer.toHexString(summary.hashCode())`. A 32-bit
  Java `hashCode` is not a content hash and can collide.
- The update prompt inserts `prev.currentValues()` into the body. That map
  contains the summary hash, not the previous summary text, so the model is
  asked to update a summary it cannot read.
- There is no branch summary, pruning, shake, snapcompact or handoff. A
  compaction message is folded into a `<conversation-checkpoint>` view.
- QOC’s raw event log is append-only, so “compaction is a view” is already the
  right architecture. The missing piece is a better policy and boundary model.

**Implementation direction**

1. **Token budget before failure.**
   - Use `ModelInfo.limits().context`, the last step’s reported usage, and a
     configurable reserve (e.g. 20% or 8k tokens) to decide before a step.
   - Keep a recent token tail, not a fixed message count. Always keep the last
     user turn and its complete tool exchange.
   - Store the resulting `firstKeptSeq` in `InstructionState` so rebuilding is
     deterministic.
2. **Fix summary persistence.**
   - Use SHA-256 or a UUID for the blob key.
   - Store the summary text and retrieve it for `CTX-SUMMARY-UPDATE`; test that
     the second compaction actually sees the first summary.
3. **Prune before summarizing.**
   - Replace old large tool results with `artifact://` references and a short
     headline.
   - Elide repetitive logs and file dumps mechanically (`shake`) before paying
     for a model call.
   - Preserve errors, user instructions, decisions and the current plan.
4. **Branch summary after session tree.**
   - Add a `branchSummary` message or entry type only when tree navigation
     exists. It should carry the abandoned branch’s goal, decisions, files and
     open questions.
5. **Handoff.**
   - Add a manual `handoff` operation that generates a document and commits it
     as a compaction entry. Reuse the result to start a fresh session.
   - Do not attempt snapcompact until QOC has vision-capable providers and a
     clear cost model.

### 2.8 Session tree, fork, export and checkpoint

**oh-my-pi reference:** `docs/tree.md`,
`docs/session-operations-export-share-fork-resume.md`,
`docs/tools/checkpoint.md`, `docs/tools/rewind.md`,
`packages/coding-agent/src/session/*`

oh-my-pi sessions are a JSONL tree with a leaf pointer:

- `/tree` builds a navigable tree from session entries and moves the leaf. It
  can optionally summarize the abandoned branch.
- `/fork` creates a new session with a new id and a `parentSession` link. It
  copies artifacts and switches the active session. Forking at an entry keeps
  the entry and everything before it.
- `/export` produces HTML with the session graph, system prompt, tool
  descriptions and subagent transcripts. `--export` does the same offline.
- `/resume` switches to an existing session or imports a foreign transcript.
- `checkpoint` marks the current conversation state; `rewind` prunes
  exploratory context and injects a retained report. This is conversation
  pruning, not file restoration.
- `clear`, `fresh`, `new` and `delete` have deliberately different semantics:
  keep identity and history, rotate provider state, create a new identity, or
  delete the old session.
- A share path exists, but it is an encrypted relay product with redaction and
  size limits.

**QOC mapping**

- `SessionEvent` has `SessionForked(sessionId, parentId, boundary)` and a
  `ForkBoundary(messageId)`, but nothing constructs it. There is no fork API.
- `session_message` has `id`, `session_id`, `seq`, `type`, `data`, `created_at`
  and `unique(session_id, seq)`. It has no parent pointer and no branch leaf.
- `SessionService` only creates, admits and delivers. Rename/move/archive are
  wired elsewhere, but there is no tree, fork, export or checkpoint service.
- `HttpApi` exposes prompt, command, events, messages, title, todos, question,
  revert and config. It does not expose tree/fork/export.
- `Revert` restores a pre-turn workspace snapshot and deletes the conversation
  from a boundary. That is stronger than oh-my-pi’s conversation-only rewind
  for files, but it is not a branch: it does not preserve the abandoned path.

**Implementation direction**

- Add to `session_message`:
  - `parent_id` (nullable for the first message; default previous message in a
    linear session);
  - an index on `(session_id, parent_id)`;
  - a `session.leaf_message_id` column, or a `session_leaf` table.
- Keep the append-only event log as the audit source. The tree is a projection,
  not a second source of truth.
- Implement `SessionService.fork(parent, boundary, location, agent)`:
  1. create a new session;
  2. copy messages up to the boundary with the same content and parent
     structure (or share immutable blobs);
  3. append `SessionForked`;
  4. copy artifact references, not files;
  5. set the new leaf.
- Add HTTP endpoints:
  - `GET /api/session/{id}/tree` — entries, parents, leaf, labels;
  - `POST /api/session/{id}/fork` — boundary and destination location;
  - `GET /api/session/{id}/export?format=markdown|json|html` — server-side
    export with secret redaction.
- Start with Markdown/JSON export. HTML export can reuse the web UI later.
- Add a `checkpoint` tool only if the product needs conversation-only pruning.
  QOC’s existing snapshot revert already covers “undo the files”; do not
  duplicate that semantics under a new name.
- Do not implement share relay, encrypted links or cross-process collaboration
  as part of this milestone.

### 2.9 Internal URLs, blobs and artifacts

**oh-my-pi reference:** `docs/blob-artifact-architecture.md`,
`packages/coding-agent/src/internal-urls/router.ts`,
`packages/coding-agent/src/session/artifacts.ts`,
`packages/coding-agent/src/session/blob-store.ts`

oh-my-pi separates two storage shapes:

- **Content-addressed blobs** — `blob:sha256:<hash>` in persisted session
  entries; used for images, frames and provider data URLs. Global, deduplicated,
  rehydrated on load.
- **Session-scoped artifacts** — files under
  `<sessionFile-without-.jsonl>/`; used for full tool output and subagent
  output. Numeric ids are local to a session.

The internal URL router provides a process-global registry of scheme handlers:

- `artifact://<id>` — truncated tool output;
- `agent://<id>` — subagent output, with `/key/index` JSON extraction;
- `skill://`, `rule://`, `memory://`, `local://`, `history://`, `proc://`,
  `cfg://`, `vault://`, `xd://` and others;
- `read`, `grep`, `glob` and `bash` route these URLs through a filesystem
  adapter, while `write` can target a small set of handler-owned devices.

The important design points are not the twenty schemes. They are:

- artifact ids are session-scoped, so a numeric id cannot silently resolve to
  another session’s file;
- artifact retrieval can locate the backing file without materializing 8 MiB
  into the model context;
- content-addressed blobs deduplicate images across sessions;
- tool results retain a stable reference after truncation, so the model can
  re-read, search or cite the full output.

**QOC mapping**

- `ToolOutputStore` has `save(suffix, bytes)`, `load(path)` and `sweep(age)`.
  `SqliteToolOutput` stores `tool_output(path, content, created_at)` in the
  database. The returned `Path` is a path-shaped key, not a file that the
  filesystem tools can read. `ToolRuntime.bound` saves only when output exceeds
  `OutputBounding` limits.
- `OutputBounding` returns a visible prefix plus `metadata.outputPath` and
  `metadata.truncated`. The path is neither a URI nor guaranteed to be readable
  by `ReadTool`.
- There is no session id in the artifact key, no content hash, no media type,
  no `load` tool integration, and no scheme router. `ReadTool` cannot read
  `artifact://...`; it only takes a filesystem path.
- `ModelContent.Media` exists, and the OpenAI encoder supports image data URLs,
  but there is no ingestion/fetch path that externalizes images as blobs.
- `ToolOutputStore` is a good base. The missing layer is identity and access,
  not storage.

**Implementation direction**

- Introduce an `ArtifactRef`:

```java
record ArtifactRef(SessionId session, String id, String mediaType,
                   String sha256, long size, Path path) {}
```

- Change `ToolOutputStore.save` to return `ArtifactRef`. Keep a compatibility
  adapter for the old `Path` API.
- Add `artifact://<session>/<id>` and `skill://<name>` first. A small
  `ResourceResolver` in `engine.core` with adapter implementations is enough;
  do not import the whole oh-my-pi scheme list.
- Make `read` and `grep` resolve `artifact://` through the same pipeline as
  files. `edit` and `write` must reject read-only schemes.
- Add `artifact` metadata to `ToolResult` so the model sees a stable id, not an
  absolute path that may leak workspace layout.
- Use SHA-256 for `BlobStore` references when image ingestion is added. Do not
  use four-hex tags or Java `hashCode` for content identity.
- Add a sweep policy based on session age and artifact size. Session-scoped ids
  must remain valid after fork by copying or referencing the parent’s
  artifacts explicitly.

### 2.10 Bash runtime and managed jobs

**oh-my-pi reference:** `docs/bash-tool-runtime.md`,
`crates/pi-natives/src/shell*`, `packages/coding-agent/src/exec/*`

oh-my-pi’s bash tool is a process runtime:

- A persistent shell session per session id preserves variables, functions and
  shell options between calls. Concurrent calls create one-shot shells; a
  session’s shell is never shared by overlapping commands.
- Foreground calls can auto-background after a threshold; `async` returns a job
  and `wait` retrieves it. Managed services get a name, readiness probe, PTY
  and session-scoped logs (`proc://<name>`).
- PTY mode is available only with a UI and a native PTY. Non-PTY execution uses
  a non-interactive environment (pagers, editors, prompts, CI flags).
- Output streams through `OutputSink`: 50 KiB inline, head/tail windows, a
  column cap, and an artifact spill. The full sanitized stream can be read back
  with `artifact://<id>`.
- `direnv`/`devenv` snapshots, shell rc snapshots, command interception,
  critical-command policy and shell-output minimization are layered on top.
- Cancellation propagates to process groups (TERM, then KILL on POSIX).

**QOC mapping**

- `ShellTool` runs `sh -c` (or `cmd /c`) per call, 2-minute default timeout,
  `background` starts a process and returns a status string.
- `JdkProcessRunner.run` captures stdout and stderr separately, waits, and
  force-destroys on timeout. `start` returns a `Handle` with output, kill,
  close and exit code.
- `ShellTool` discards the `Handle` on background. The returned `shellId` is
  only `handle.hashCode()`, with no registry to resolve it. There is no output
  retrieval, wait or kill API, and a background failure is not observable.
- The tool truncates stdout to 50 KiB **before** `ToolRuntime` sees it. Because
  the result is already marked truncated, `OutputBounding` does not save the
  full output. The full command output is lost.
- On a non-zero exit, the tool returns `command exited with N: stderr`; stdout
  is discarded. Stderr is not included on success.
- `pty` is a one-off `script(1)` wrapper, not a persistent PTY.

This is a good example of a feature that looks present in the tool schema but
is not actually usable as a runtime.

**Implementation direction**

1. **Stop pre-truncating in the tool.** Return combined stdout/stderr and let
   `ToolRuntime`/`OutputBounding` spill the full text. Mark the result only
   after the sink has captured it.
2. **Add a process registry.**
   - `ProcessRegistry` keyed by `(session, processId)` holds `Handle`, a ring
     buffer, status and exit code.
   - `shell` with `background` returns the id.
   - Add `process_output`, `process_wait`, `process_kill` (or one `process`
     tool) that operates on that id.
   - Persist the registry metadata in the session event log if the process may
     outlive a prompt; otherwise document it as in-memory.
3. **Stream output.**
   - Extend `ProcessRunner` with a callback or `OutputSink` abstraction.
   - Bound the inline view and mirror the full stream to an artifact.
4. **Timeout semantics.**
   - Keep a default, allow `0` to disable, clamp to a maximum, and return a
   clear “moved to background” result instead of killing a long command
     silently.
5. **Defer persistent shells and managed services.**
   - A server-side web UI does not have a terminal, and a persistent shell
     complicates isolation, cancellation and reproducibility. Add job
     management first; persistent shells only if the product needs them.
6. **Keep the scanner and approval boundary.** Add critical-command checks and
   shell-pattern policies when approval tiers land.

### 2.11 Hooks and extensions

**oh-my-pi reference:** `docs/hooks.md`, `docs/extensions.md`,
`packages/coding-agent/src/extensibility/*`

oh-my-pi has a full extension runner. Its hook surface includes:

- session lifecycle: start, switch, before/after branch, before/after compact,
  tree navigation, shutdown;
- agent/context: context transform, before/after agent, turn start/end,
  auto-compaction, retry, TTSR, todo reminders;
- tool hooks: `tool_call` (block, replace input, add context) and
  `tool_result` (replace content/details, add context);
- registration of commands, message renderers, providers, tools and UI.

The current runtime uses extensions rather than the legacy hook wrapper; the
event contracts are shared. A tool hook can rewrite input, and approval is
re-evaluated against the rewritten arguments.

**QOC mapping**

- `Hooks` has exactly three methods: `beforeToolCall`, `afterToolCall` and
  `evaluatePermission`.
- `ToolRuntime` calls them around execution. `PermissionRules` runs the
  permission hook.
- There is no session lifecycle bus, context hook, compaction hook, command
  registration or custom renderer.
- QOC’s event journal is a natural source for observers, but it is not exposed
  as an extension point.

**Implementation direction**

- Do not port the TypeScript extension runtime. Add a Java SPI:
  - `ToolHook` (existing, refined);
  - `SessionLifecycleHook` for created/forked/archived;
  - `ContextHook` with a narrow, explicit ability to add a block (not arbitrary
    mutation of messages);
  - `CompactionHook` before/after;
  - `StepObserver` for read-only metrics/logging.
- Keep interfaces in `engine.core`; implementations belong in the Quarkus
  adapter, loaded with CDI or `ServiceLoader`.
- Make hooks fail closed for security-sensitive steps. A hook that throws must
  not silently allow a tool.
- Re-evaluate approval after a tool-call hook changes input.
- Expose a read-only event stream for telemetry; do not let an observer mutate
  the journal.
- Leave package/marketplace installation out. QOC does not have an npm-style
  distribution and does not need one.

### 2.12 Memory, advisor and TTSR

**oh-my-pi reference:** `docs/memory.md`, `docs/advisor-watchdog.md`,
`docs/rulebook-matching-pipeline.md`,
`docs/ttsr-injection-lifecycle.md`

These are the longest bets, but they show what oh-my-pi considers necessary for
long-running work.

**Memory**

- Five backends: `off`, `local`, `hindsight`, `mnemopi`, `sharpshooter`.
- The local backend extracts durable decisions from past sessions, consolidates
  them, and injects a token-capped memory summary plus learned lessons at
  session start.
- `learn` captures explicit lessons; `recall`/`retain`/`reflect` support remote
  or SQLite backends; `memory://` exposes artifacts.
- The pipeline has age/idle/scan limits, leases, heartbeats, retry delays and
  secret redaction. It is intentionally heuristic, not authoritative.

**Advisor**

- One or more reviewer models read a delta of the primary transcript, inspect
  the workspace with an isolated tool session, and inject `nit`/`concern`/
  `blocker` notes.
- The reviewer has its own context and model role, optional catch-up policy,
  deduplication/emission guard, cooldown and fallback chain.
- It cannot approve actions or mutate primary state directly.

**TTSR**

- Rules are discovered from rule files and a rulebook. A regex match aborts the
  stream mid-token, injects the rule as a system reminder, and retries.
- Injection survives compaction. The rule body is dormant until it fires, so it
  avoids paying context tax on every turn.

**QOC mapping**

- No memory backend, no reviewer role, no rulebook or stream-rule engine.
- QOC has subagents, background execution, an event journal and a strict
  permission model, which are the raw materials for all three.
- The closest cheap feature is a sticky `RULES.md` (2.1). That gives durable
  instructions without a memory pipeline.

**Recommendation**

- Do not build autonomous memory or TTSR now. They are large, provider-sensitive
  and easy to make unsafe.
- A reviewer subagent is a plausible later feature: run it with a separate
  context, read-only tools and an `advise`-style note channel. It still needs a
  second model role and cost controls.
- TTSR needs a provider abstraction that can abort and retry a stream at a
  boundary, plus a rule language and an audit trail. Revisit after compaction
  and session branching are stable.

### 2.13 Model roles and per-model policy

**oh-my-pi reference:** `docs/models.md`, `docs/providers.md`,
`docs/provider-compat-reference.md`,
`docs/tools/edit.md` (model variants), `packages/catalog/*`

oh-my-pi’s catalog and role system are a large part of its “works with any
model” claim:

- `modelRoles` maps semantic roles (`default`, `smol`, `slow`, `plan`, `advisor`,
  `tiny`, `web`, `memory`, `speech`, `dictation`, `judge`, `image`) to concrete
  selectors with optional thinking levels.
- `retry.fallbackChains` provides ordered alternatives per role.
- A compiled KDL policy layer describes provider quirks, tool support, thinking
  levels, context limits, pricing and wire behavior.
- Per-model edit mode, compact hashline prompt, read-summary policy and tool
  availability adapt the harness to the model instead of assuming one ideal
  model.
- Capability gating prevents unsupported tools or modalities from being sent.

**QOC mapping**

- `EngineConfig` has a global `model.provider`/`model.id` and per-agent `model`.
- `ModelInfo` already carries `variants`, `limits`, `cost`, capabilities,
  compaction mode and transport. It is a good neutral basis.
- There is one OpenAI adapter and no role map, fallback chain, capability
  registry, per-model prompt variant or model-specific edit mode.

**Implementation direction**

- Add a small `ModelRole` map only when a second adapter exists. A role system
  with one provider is configuration without benefit.
- Extend `ModelCapabilities` with explicit booleans for tools, strict tool
  schemas, vision, reasoning and streaming; use these to shape the tool
  snapshot and prompts.
- Per-agent model selection already works. Add a session-level `smol` role for
  titles, summaries and compaction when useful.
- Keep policy in JSON/YAML, not KDL. The KDL corpus is valuable as a reference,
  not as a Java dependency.
- Make edit mode and prompt variants explicit settings, not hidden model-name
  heuristics.

---

## 3. What does not fit

| oh-my-pi feature | Why QOC should not copy it (now) |
|---|---|
| 60+ provider catalog and KDL compatibility corpus | QOC has one OpenAI adapter. The corpus is a product in itself; add adapters and capability flags incrementally. |
| Rust native crates (`pi-natives`, `pi-walker`, `pi-edit`) | QOC is a JVM/Quarkus engine. Native search and tree-sitter edits are attractive but would add a second build and distribution model. Java implementations first. |
| Persistent Python/Bun eval kernels | Security, packaging and lifecycle are large. QOC already has shell; a sandboxed eval is a separate product decision. |
| DAP debugger, browser/computer control, IDA, GitHub and security-scan tools | Product surface, credentials and sandboxing. Not core to the engine milestone. |
| Full `read` universe (archives, SQLite, PDF, video, profiler) | Useful, but each format needs a parser and limits. Add selectors and the common text paths first. |
| AST edit / ast-grep | Valuable for large refactors, but requires a parser per language. Hashline plus LSP covers most of the benefit with less surface. |
| Snapcompact (history as images) | Vision-only, opaque, costly and provider-dependent. Revisit only with a vision model and a proven need. |
| TTSR stream interruption/retry | Needs a provider-level abort/retry contract and a rule language. High complexity and risk of loops. |
| Autonomous memory pipelines | Background model extraction, leases and cross-session data retention are a product/security decision, not a compaction detail. |
| Advisor reviewer model | Requires a second model role, cost controls and a safe note channel. Later. |
| Encrypted share relay / collaboration | Networked product with key management and privacy obligations. Export is enough for QOC. |
| TUI, themes, Doom, magic keywords, Agent Hub | Client/UX features. QOC’s web UI is a demo and the engine is the product. |
| TypeScript extension marketplace | No JS runtime or package manager. A Java SPI and MCP cover integrations. |
| `xd://` device protocol for deferred tools | QOC has structured tool calls and `ToolResult.output`; the device indirection solves a different UI problem. |
| KDL model policy | Use JSON/YAML in Java; keep KDL only as a reference when adding providers. |

---

## 4. Recommended order

The order favors mechanisms that make the next mechanisms easier.

1. **Search/read ergonomics and output spill (2.3 + 2.9).**
   - Add `.gitignore`/hidden policy, binary and size checks, timeouts, file
     paging and per-file caps.
   - Add artifact refs and stop losing shell/grep output.
   - This is low-risk, improves every later tool, and gives hashline a stable
     paging model.

2. **Hashline edit mode (2.2).**
   - Add `EditStore`, tags, multi-section parsing and a conservative
     `replace` default.
   - Integrate read/grep anchors only when the mode is enabled.
   - Add LSP diagnostics later; do not block the first version on LSP.

3. **Compaction policy and correctness (2.7).**
   - Token budget, reserve/keep settings, full tool-turn retention.
   - Fix the 32-bit summary hash and the broken predecessor-summary prompt.
   - Add mechanical pruning/shake and artifact references.

4. **Session tree, fork and export (2.8).**
   - Add `parent_id`/leaf projection, `SessionForked` emission, fork/export
     endpoints, and Markdown/JSON export.
   - Branch summaries can follow once the tree exists.

5. **LSP tool (2.4).**
   - Diagnostics and navigation first; rename and code actions second.
   - Wire `workspace/willRenameFiles` into hashline `MV` or a rename tool.

6. **Task batches and typed results (2.5).**
   - Batch plus `outputSchema`; then job registry and wait/cancel; worktree
     isolation last.

7. **Approval tiers and policies (2.6).**
   - Add tiers and modes, then target-aware grants and command patterns.
   - Keep QOC’s ask/deny behavior as the safe default.

8. **Context discovery and prompt layout (2.1).**
   - Add `RULES.md` and `@` imports, then group project context into a trailing
     block.
   - Provider expansion and Handlebars templates are optional.

9. **Shell job management (2.10).**
   - Stop pre-truncating, add a process registry and streaming artifact sink.
   - Persistent shells/services only if the product needs them.

10. **Hooks, memory, advisor and TTSR (2.11 + 2.12).**
    - Add lifecycle/context hooks when there is a concrete integration need.
    - Treat memory, advisor and TTSR as later product decisions, not refactors.

---

## 5. Open decisions

- **Hashline default.** Should `replace` remain the default until the anchor
  tests and model prompts are stable, or should hashline be opt-in per agent
  from the start? A hybrid (`replace` for small edits, hashline for large) is
  attractive but doubles the prompt surface.
- **Tag width.** Four hex is familiar but weak. Do we use eight hex internally
  and teach the model an eight-character tag, or keep four and rely entirely on
  the snapshot store for identity?
- **Seen-line persistence.** Should `EditStore` survive resume in SQL, or is a
  fresh read required after restart? Persisting ranges improves UX but adds
  another projection that can go stale.
- **Read selector syntax.** A path suffix (`file.ts:10-20`) is what
  oh-my-pi models know, but it collides with real filenames. Do we add a
  `range`/`raw` field or keep suffix parsing with a literal-path preference?
- **LSP dependency.** Is LSP4J acceptable, or should the adapter implement a
  minimal JSON-RPC client to avoid a large dependency? LSP4J is safer; a
  minimal client is smaller and easier to audit.
- **Diagnostics policy.** Should every edit run diagnostics synchronously, run
  them only when the file type has a configured server, or expose diagnostics
  as a separate tool call? Synchronous diagnostics are useful but slow.
- **Session tree storage.** Add `parent_id` to `session_message`, or create a
  separate immutable `session_entry` table and keep messages as a projection?
  The latter is cleaner but a larger migration.
- **Fork copy semantics.** Copy message content and artifacts, or share
  immutable rows/blobs with the parent? Sharing saves space but makes deletion
  and privacy rules harder.
- **Approval default.** QOC should keep `always-ask`, but does that break
  existing headless flows? A `legacy` mode that preserves current rule-chain
  behavior may be needed during migration.
- **Worktree isolation.** Should isolated subagents merge automatically, or
  always return a diff for parent review? Automatic merging is convenient and
  dangerous; review is safer but adds a step.
- **Extension SPI scope.** Is a Java `ServiceLoader` SPI enough, or should
  external integrations stay MCP-only? MCP is already a clean boundary; an SPI
  is for in-process hooks, not third-party packages.
- **Memory.** Is a sticky `RULES.md` plus a manually curated project memory file
  sufficient, or does QOC need an autonomous extraction pipeline? Start with
  the former.

---

*Created: 2026-10-26 · Based on oh-my-pi `0b5c27a` and QOC `24f0273`.*
