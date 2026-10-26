# QOC Findings – Current Problems

> **Status:** Working issue register · 2026-10-26
> **Scope:** only current defects and concrete gaps in QOC
> **Source:** [`../analysis/oh-learnings.md`](../analysis/oh-learnings.md)
> **Target:** `de.augmentia.oc.engine` (Java 21 / Quarkus)

This file is deliberately narrow. It contains **only current QOC problems**
that can be shown in the present implementation.

It does **not** contain adoptable oh-my-pi features. Hashline edits, read
selectors, LSP, approval tiers, typed task batches, `RULES.md`, imports, prompt
blocks, pruning, shell streaming, hooks, memory, advisor and model roles remain
in [`oh-learnings.md`](../analysis/oh-learnings.md). Those are proposals, not
current bugs.

The distinction is intentional:

- `oh-learnings.md` answers: *what can QOC learn from oh-my-pi?*
- this file answers: *what is concretely broken or incomplete in QOC today?*

---

## Contents

- [1. How to use this file](#1-how-to-use-this-file)
- [2. Status and priority](#2-status-and-priority)
- [3. Overview](#3-overview)
- [4. Problem details](#4-problem-details)
- [5. Decisions required for the fixes](#5-decisions-required-for-the-fixes)
- [6. Deliberately not in this file](#6-deliberately-not-in-this-file)

---

## 1. How to use this file

1. Pick an `OPEN` problem. Change it to `IN_PROGRESS`.
2. Add a log entry with date, scope and branch (if any).
3. Fix the code and add a regression test that fails on the old behavior.
4. Run `mvn test` in `qoc/`. A problem is not `DONE` while tests are red.
5. Update the affected `docs/spec/` document if the fix changes a contract.
6. Add the commit id, test result and coverage result to the log.
7. Tick acceptance boxes only when the evidence exists.

**Commit rule:** no commit without an explicit instruction. If a commit is
authorized, `mvn test` must be green and JaCoCo must report at least 80 %
instruction coverage.

**Rule for this file:** do not turn a missing feature into a bug. If the
problem is a proposal rather than a current defect, write it in
`oh-learnings.md` and link it from here only when implementation starts.

---

## 2. Status and priority

### Status

| Status | Meaning |
|---|---|
| `OPEN` | Confirmed, not fixed |
| `IN_PROGRESS` | Being fixed; log entry required |
| `BLOCKED` | Waiting for a decision or another fix |
| `DONE` | Fixed, tested and documented |
| `WONTFIX` | Deliberately not changed; reason recorded |

### Priority

| Priority | Meaning |
|---|---|
| `P0` | Data loss, wrong behavior or silently lost errors |
| `P1` | Severe reliability or usability gap in current behavior |
| `P2` | Concrete but lower-impact defect |

### Current snapshot

| Metric | Value |
|---|---|
| Current problems | 13 |
| Done | 9 |
| In progress | 0 |
| Open | 4 |
| Blocked | 0 |

**Final verification:** `mvn test` — engine 650, quarkus 51, 0 failures/errors,
3 skipped; JaCoCo engine 81.46 % instructions. Work is uncommitted.

---

## 3. Overview

| ID | Problem | Priority | Status | Main evidence |
|---|---|---|---|---|
| QOC-001 | Shell loses output and cannot control background processes | P0 | DONE | `ShellTool` |
| QOC-002 | Compaction summary cannot be safely updated | P0 | DONE | `CompactionService` |
| QOC-003 | Background subagent failures disappear | P0 | DONE | `SubagentTool` |
| QOC-004 | Search/glob silently degrade on real trees | P1 | DONE | `NioFileSystem`, `GrepTool`, `GlobTool` |
| QOC-005 | Stored output cannot be resolved as an artifact | P1 | DONE | `ToolOutputStore`, `OutputBounding` |
| QOC-006 | Session fork model is dead and cannot represent branches | P1 | DONE | `SessionEvent`, `session_message` |
| QOC-007 | Tool catalog filter leaks after overflow compaction | P0 | DONE | `Engine`, `ContextBuilder` |
| QOC-008 | PermissionDenied escapes ToolRuntime and becomes HTTP 500 | P0 | DONE | `ToolRuntime`, `HttpApi` |
| QOC-009 | Open approval requests are ephemeral | P1 | OPEN | `PendingApprovals` |
| QOC-010 | Compaction can split a turn | P1 | DONE | `CompactionService`, `CompactionWindow` |
| QOC-011 | Tool calls have no idempotency key | P1 | OPEN | `ToolContext` |
| QOC-012 | Compaction checkpoint is written at message index 0 | P2 | OPEN | `ContextBuilder` |
| QOC-013 | Prompt API has no outcome algebra for Busy/Refused/Terminated | P2 | OPEN | `Engine`, `HttpApi` |

---

## 4. Problem details

### QOC-001 — Shell loses output and cannot control background processes

- **Status:** `DONE`
- **Priority:** `P0`
- **Source:** [oh-learnings 2.10](../analysis/oh-learnings.md#210-bash-runtime-and-managed-jobs)
- **Finding:**
  - `ShellTool` truncates stdout to 50 KiB **before** `ToolRuntime` sees the
    result.
  - It then marks the result as truncated, so `OutputBounding` deliberately
    does not save the full output. The full command output is unrecoverable.
  - On a non-zero exit, the tool returns `command exited with N: stderr` and
    discards stdout.
  - On success, stderr is not returned at all.
  - The `background` path starts a process, throws away the
    `ProcessRunner.Handle`, and returns `handle.hashCode()` as `shellId`.
    There is no registry, output retrieval, wait, kill or exit status.
  - A background process is not reaped or closed by the tool.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/shell/ShellTool.java`
  - `engine/src/main/java/de/augmentia/oc/engine/adapter/process/JdkProcessRunner.java`
  - `engine/src/main/java/de/augmentia/oc/engine/core/eval/OutputBounding.java`
- **Impact:** commands that produce useful output above 50 KiB lose it; failed
  commands hide stdout; background commands are fire-and-forget.
- **Work:**
  - [x] Remove the 50 KiB pre-truncation from `ShellTool`.
  - [x] Return stdout and stderr with explicit channel markers, or preserve
        both streams in a structured result while keeping the visible text.
  - [x] Let `ToolRuntime`/`OutputBounding` perform the single bounding step.
  - [x] Keep the exit code in metadata for success and failure.
  - [x] Introduce a session-scoped process registry for background commands.
  - [x] Return a stable process id, not `hashCode()`.
  - [x] Add output/wait/kill operations and reap completed processes.
  - [x] Close or kill the handle on session cleanup.
- **Acceptance:**
  - [x] A command with more than 50 KiB output yields a retrievable full result.
  - [x] A failing command exposes stdout **and** stderr.
  - [x] A successful command does not silently discard stderr.
  - [x] A background command can be observed to completion or killed.
- **Tests:** large stdout, large stderr, both streams, non-zero exit, UTF-8
  boundary, background wait, background kill, session cleanup.
- **Resolved decision:** D-01.
- **Implementation:**
  - `ShellTool` returns the complete streams. `combined()` marks
    `[stdout]`/`[stderr]` only when both are non-empty.
  - `OutputBounding` is now the only truncation and spill step; the full text
    lands in `ToolOutputStore`.
  - `ProcessRegistry` assigns `sh_<uuid>` ids, observes exits, reaps handles
    and scopes entries to a session.
  - `process` tool exposes `list`, `status`, `output`, `wait` and `kill`.
  - `Engine` closes the registry per session and on shutdown.
- **Log:**
  - 2026-10-26 — Created from the current-code review.
  - 2026-10-26 — Implemented and tested. Full suite green; JaCoCo engine
    81.46 % instructions. Uncommitted.

---

### QOC-002 — Compaction summary cannot be safely updated

- **Status:** `DONE`
- **Priority:** `P0`
- **Source:** [oh-learnings 2.7](../analysis/oh-learnings.md#27-compaction-branch-summaries-and-handoff)
- **Finding:**
  - The summary blob key is
    `Integer.toHexString(summary.hashCode())`. This is a 32-bit Java hash, not
    a content address, and can collide.
  - When a previous compaction exists, `CompactionService` inserts
    `prev.currentValues()` into the update prompt. That map contains the
    summary hash, not the previous summary text.
  - The model is therefore asked to update a summary it cannot read.
  - A missing or corrupt previous blob is not handled explicitly.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/compaction/CompactionService.java`
  - `engine/src/main/java/de/augmentia/oc/engine/core/model/InstructionState.java`
  - `engine/src/main/java/de/augmentia/oc/engine/core/port/store/InstructionStore.java`
- **Impact:** the second and later compactions can lose prior context or
  silently overwrite unrelated content if two summaries collide.
- **Work:**
  - [x] Replace the 32-bit hash with SHA-256 or a collision-safe generated id.
  - [x] Store the summary text and retrieve it for `CTX-SUMMARY-UPDATE`.
  - [x] Insert the previous summary text, not `currentValues()`.
  - [x] Handle missing/corrupt predecessor blobs with a typed result.
  - [x] Keep the raw event log immutable.
- **Acceptance:**
  - [x] Two consecutive compactions show the first summary in the second
        update prompt.
  - [x] No summary identity is derived from a 32-bit hash.
  - [x] A missing predecessor blob produces an explicit error, not a prompt
        containing a hash.
- **Tests:** two compactions with captured requests; summary-key round trip;
  missing blob; collision-safe identity; encoding special cases.
- **Resolved decision:** D-02 (SHA-256).
- **Implementation:**
  - `CompactionService.summaryKey()` uses lowercase SHA-256 hex.
  - The blob is written before the instruction state, so a state never points
    at a missing blob.
  - `previousState()` reads the predecessor key and inserts
    `instructions.blob(key)` into `CTX-SUMMARY-UPDATE`.
  - Missing predecessor: `CompactionResult.PredecessorMissing`; HTTP returns
    422 `CompactionError`.
  - Legacy 32-bit keys remain readable because the stored key is used for
    lookup; the next successful compaction writes a SHA-256 key.
- **Log:**
  - 2026-10-26 — Created from the current-code review.
  - 2026-10-26 — Implemented and tested. Two-compaction request capture,
    missing-blob typed result and full suite green. Uncommitted.

---

### QOC-003 — Background subagent failures disappear

- **Status:** `DONE`
- **Priority:** `P0`
- **Source:** [oh-learnings 2.5](../analysis/oh-learnings.md#25-task-batches-async-subagents-and-isolation)
- **Finding:**
  - `SubagentTool.executeBackground` catches every `RuntimeException` and
    discards it.
  - Only successful text is stored, in a static
    `ConcurrentHashMap<SessionId, String>`.
  - There is no task id, state, error, wait or cancel.
  - The static map is never cleaned up; completed results and failed child
    sessions can accumulate for the JVM lifetime.
  - The parent cannot distinguish running, completed, failed and cancelled.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/subagent/SubagentTool.java`
  - `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java`
- **Impact:** a failing background worker looks like a worker that never
  finished; the parent may retry forever or lose the failure reason.
- **Work:**
  - [x] Store a terminal result record: state, text, error, usage, child
        session and timestamps.
  - [x] Do not swallow `RuntimeException`; record it.
  - [x] Add a status/result query that does not require a new model prompt.
  - [x] Remove completed entries on read or session cleanup.
  - [x] Replace the static map with a session-scoped registry.
- **Acceptance:**
  - [x] A failing background subagent is observable from its parent.
  - [x] Failure and success have distinct states.
  - [x] Results are cleaned up when the session ends.
- **Tests:** runner throws after start; empty output; slow completion; polling
  before/after completion; cleanup.
- **Resolved decision:** D-05 (in-memory, session-scoped).
- **Implementation:**
  - `BackgroundTasks` holds `task_<uuid>` ids, state
    `RUNNING|SUCCEEDED|FAILED|CANCELLED`, text, error, child session, usage,
    timestamps and a completion latch.
  - `SubagentTool.executeBackground` records `Throwable` instead of swallowing
    it; a late result cannot overwrite a cancellation.
  - `task` tool exposes `list`, `status`, `wait` and `cancel`.
  - `Engine` exposes `/api/session/{id}/tasks` and
    `/api/session/{id}/tasks/{taskId}/cancel`; deletion and shutdown clean up.
- **Log:**
  - 2026-10-26 — Created from the current-code review.
  - 2026-10-26 — Implemented and tested. Success, thrown runner, session
    isolation, cancel, wait and cleanup covered. Uncommitted.

---

### QOC-004 — Search/glob silently degrade on real trees

- **Status:** `DONE`
- **Priority:** `P1`
- **Source:** [oh-learnings 2.3](../analysis/oh-learnings.md#23-read-grep-glob-and-the-scan-cache)
- **Finding:**
  - `NioFileSystem.search` walks every path, reads every line of every regular
    file with `Files.readAllLines`, and catches `IOException` by silently
    skipping the file.
  - It has no `.gitignore`, hidden, depth, size, binary or timeout policy. A
    binary file with valid UTF-8 can be decoded as text; a generated directory
    can dominate the call.
  - `GrepTool` caps matches at a global `MAX_HITS = 100`; there is no case
    flag, context, per-file cap, file paging or timeout.
  - `GlobTool` also caps at 100 and cannot distinguish “no matches” from
    “scan incomplete”.
  - The `NioFileSystem(Path root)` constructor accepts a root but does not use
    it. The instance is not rooted, which makes the API misleading.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/adapter/fs/NioFileSystem.java`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/fs/GrepTool.java`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/fs/GlobTool.java`
- **Impact:** searches can be slow, miss relevant hits beyond the first 100,
  or present partial results as complete.
- **Work:**
  - [x] Add walk/search options: ignore, hidden, depth, size, timeout, binary.
  - [x] Report skipped files and truncation metadata.
  - [x] Replace the global 100-hit cap with per-file caps and paging.
  - [x] Add case and context arguments to `grep`.
  - [x] Add timeout and partial-result reporting to `glob`.
  - [x] Either use the constructor root or remove the parameter and update all
        callers.
- **Acceptance:**
  - [x] A generated directory does not dominate a search.
  - [x] A partial or timed-out scan cannot look like “no matches”.
  - [x] More than 100 matches can be retrieved without silently losing files.
  - [x] The `NioFileSystem` root contract is honest.
- **Tests:** ignored tree, hidden files, binary file, oversized file, timeout,
  paging, partial glob and root-relative behavior.
- **Resolved decision:** D-03 (option-aware default methods on `FileSystem`).
- **Implementation:**
  - `FileSystem.SearchOptions` and `GlobOptions` carry case, context, depth,
    hidden/ignore, size, per-file and page limits plus a deadline.
  - `NioFileSystem` streams with `Files.walk`, honors `.gitignore`/`.ignore`
    and common defaults, skips hidden/binary/oversized files, and counts
    skipped files.
  - Results are kept in a bounded sorted candidate set, so paging is
    deterministic without buffering the whole tree.
  - `grep`/`glob` expose `caseSensitive`, `context`, `maxResults`,
    `maxPerFile`, `cursor`, `timeout`, `hidden`, `ignore` and `depth`;
    metadata reports `truncated`, `nextCursor`, `total`, `timedOut` and
    `skipped`.
  - `NioFileSystem(Path root)` now resolves relative paths against the root.
- **Log:**
  - 2026-10-26 — Created from the current-code review.
  - 2026-10-26 — Implemented and tested. Ignore/hidden/binary/size/depth,
    per-file caps, paging, context and root-relative paths covered. Uncommitted.

---

### QOC-005 — Stored output cannot be resolved as an artifact

- **Status:** `DONE`
- **Priority:** `P1`
- **Source:** [oh-learnings 2.9](../analysis/oh-learnings.md#29-internal-urls-blobs-and-artifacts)
- **Finding:**
  - `ToolOutputStore.save` returns a `Path`.
  - `SqliteToolOutput` stores the path string and content in the
    `tool_output` table; the returned path is a key, not a file the filesystem
    tools can read.
  - `OutputBounding` exposes the key as `metadata.outputPath`.
  - `ReadTool` only accepts filesystem paths; it cannot resolve the key.
  - There is no session id, content hash, media type or URI in the stored
    record, and no tool-side `load`.
  - `ShellTool` bypasses the store entirely by pre-truncating (QOC-001).
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/core/port/store/ToolOutputStore.java`
  - `engine/src/main/java/de/augmentia/oc/engine/adapter/sqlite/SqliteToolOutput.java`
  - `engine/src/main/java/de/augmentia/oc/engine/core/eval/OutputBounding.java`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java`
- **Impact:** the model is told an output path that it usually cannot read
  back. Full output is effectively lost even when the store contains it.
- **Work:**
  - [x] Introduce an `ArtifactRef` with session, id, media type, hash, size and
        optional backing path.
  - [x] Decide session-local or content-addressed identity (D-04).
  - [x] Add a resolver for `artifact://<session>/<id>`.
  - [x] Let `read` and `grep` resolve artifacts through the same pipeline as
        files.
  - [x] Migrate all call sites to the session-scoped store API (the internal
        `Path` API was removed rather than kept).
  - [x] Define sweep and fork behavior.
- **Acceptance:**
  - [x] A truncated result carries a stable artifact reference.
  - [x] `read artifact://...` returns the stored content in the same session.
  - [x] Another session cannot accidentally resolve the id.
  - [x] Sweep and fork leave references valid or explicitly missing.
- **Tests:** save/load, session isolation, missing artifact, large artifact,
  resolver read, sweep and fork.
- **Resolved decision:** D-04 (session-local id plus SHA-256 metadata; copy on
  fork).
- **Implementation:**
  - `ArtifactRef` carries session, id, media type, SHA-256, size and
    `artifact://<session>/<id>`.
  - `ToolOutputStore` is session-scoped (`save`, `find`, `load`, `copy`,
    `sweep`); SQL/SQLite use the new V2 columns.
  - `OutputBounding` spills full text and exposes the artifact URI as
    `metadata.outputPath`; the visible hint also uses the URI.
  - `read` and `grep` resolve `artifact://` only inside the active session;
    binary artifacts are rejected; `write`/`edit` reject artifact paths.
  - Fork copies artifacts into the child session and rewrites tool-card and
    file-item references.
- **Log:**
  - 2026-10-26 — Created from the current-code review.
  - 2026-10-26 — Implemented and tested. Save/load, isolation, read/grep
    resolution, copy-on-fork and write rejection covered. Uncommitted.

---

### QOC-006 — Session fork model is dead and cannot represent branches

- **Status:** `DONE`
- **Priority:** `P1`
- **Source:** [oh-learnings 2.8](../analysis/oh-learnings.md#28-session-tree-fork-export-and-checkpoint)
- **Finding:**
  - `SessionEvent` defines `SessionForked` and `ForkBoundary`, but nothing
    constructs or appends `SessionForked`.
  - `session_message` has `id`, `session_id`, `seq`, `type`, `data` and
    `created_at`, but no `parent_id` and no branch leaf.
  - `SessionService` only creates, admits and delivers; there is no fork
    operation.
  - The model therefore has a fork event that cannot occur and no data shape
    for a branched conversation.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/core/model/SessionEvent.java`
  - `engine/src/main/java/de/augmentia/oc/engine/core/model/ForkBoundary.java`
  - `engine/src/main/resources/db/migration/V1__engine.sql`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/session/core/SessionService.java`
- **Impact:** sessions are permanently linear; tree navigation, fork,
  branch summaries and graph export cannot be implemented without changing the
  persistence model. The unused event variant also suggests a capability that
  does not exist.
- **Work:**
  - [x] Decide message parent column versus separate entry table (D-06).
  - [x] Add parent/leaf projection and indexes.
  - [x] Backfill existing sessions as linear (tree falls back to the previous
        message when `parent_id` is null).
  - [x] Implement fork (wired as `Engine.fork`; it uses `SessionService.create`
        and the conversation store).
  - [x] Emit `SessionForked` with a valid boundary.
  - [x] Add a tree read endpoint.
  - [x] Preserve or explicitly copy artifact references.
- **Acceptance:**
  - [x] An existing session projects as a linear tree.
  - [x] A forked session has a distinct branch from the boundary.
  - [x] Parent and child can diverge without mutating each other.
  - [x] No dead fork event remains in the model.
- **Tests:** migration/backfill, linear tree, fork at first/middle/last
  message, parallel divergence, invalid boundary and artifact access.
- **Resolved decision:** D-06 (`parent_id` on `session_message`; leaf is the
  last message; no separate entry table).
- **Implementation:**
  - V2 adds `session_message.parent_id` and its index; linear inserts infer
    the previous message as parent.
  - `Engine.fork(parent, boundary, directory, agent)` creates a child session,
    copies messages through the boundary with fresh ids/parent links, copies
    referenced artifacts, and appends `SessionForked`.
  - `Engine.tree` returns `{session, parentSession, leaf, entries[]}` with
    `id`, `parentId`, `seq`, `type` and `text`.
  - HTTP: `GET /api/session/{id}/tree`, `POST /api/session/{id}/fork`.
- **Log:**
  - 2026-10-26 — Created from the current-code review.
  - 2026-10-26 — Implemented and tested. Tree projection, fork boundary,
    parent link, copy-on-fork and invalid boundary covered. Uncommitted.

---

### QOC-007 — Tool catalog filter leaks after overflow compaction

- **Status:** `DONE`
- **Priority:** `P0`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - The first context build in a step uses `agentToolSnapshot`, so denied
    tools are removed from the model request.
  - After an automatic overflow compaction, `Engine` rebuilds the context with
    the global `runSnapshot` instead of `agentToolSnapshot`.
  - In that path, `tools.allow` and `tools.deny` are no longer applied.
  - This violates tool restriction TR-01 and can re-expose `shell`, `glob`,
    MCP tools or other denied tools to the model.
- **Evidence:**
  - `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:1093`
  - `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:1109`
  - `docs/spec/agents/02-tool-restrictions.md`
- **Impact:** A restricted agent can regain tools after context overflow; the
  restriction depends on whether compaction happened.
- **Work:**
  - [ ] Use `agentToolSnapshot` in both context builds of the step.
  - [ ] Add a regression test that forces overflow compaction for an
        agent with `tools.allow: ["read", "write"]` and asserts that `shell`
        and `glob` are absent from the second request.
  - [ ] Verify `runStep` uses the same filtered snapshot.
- **Acceptance:**
  - [ ] Denied tools are absent before and after overflow compaction.
  - [ ] The global registry is unchanged.
- **Tests:** agent with read/write only; forced `NeedsCompaction`; request
  tool list assertion for both builds.

---

### QOC-008 — PermissionDenied escapes ToolRuntime and becomes HTTP 500

- **Status:** `DONE`
- **Priority:** `P0`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - `ToolRuntime.execute` catches `ToolFailure`, but not `PermissionDenied`.
  - A permission denial therefore propagates as an unchecked
    `RuntimeException`.
  - `HttpApi.runPrompt` catches `IllegalArgumentException` and
    `IllegalStateException`, but not `PermissionDenied`, so the user sees
    HTTP 500.
  - The spec says a permission denial is a tool error that the model can
    read, not an internal server error.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/tool/runtime/ToolRuntime.java:77`
  - `quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java:664`
  - `engine/src/main/java/de/augmentia/oc/engine/core/contract/permission/PermissionDenied.java`
  - `docs/spec/session-engine/06-errors.md`
- **Impact:** A denied tool call aborts the whole prompt instead of becoming
  a model-facing tool result; clients receive 500.
- **Work:**
  - [ ] Catch `PermissionDenied` in `ToolRuntime` and return
        `ToolOutcome.Failed` with a tool error.
  - [ ] Keep `Aborted` as a separate abort channel.
  - [ ] Map remaining permission errors explicitly in `HttpApi`.
  - [ ] Add regression tests for denied rule and denied user reply.
- **Acceptance:**
  - [ ] A denied tool call produces a tool result and lets the model
        continue.
  - [ ] No HTTP 500 is returned for a permission denial.
- **Tests:** rule denial, user rejection with feedback, user rejection
  without feedback (abort), scoped agent permissions.

---

### QOC-009 — Open approval requests are ephemeral

- **Status:** `OPEN`
- **Priority:** `P1`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - `PendingApprovals` stores open permission requests only in memory.
  - Its own documentation says a restart loses them.
  - A process restart during an open approval can lose the request while the
    execution claim remains.
  - Nessy models approval as a durable, addressable request with a deadline
    and deferral.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/permission/engine/PendingApprovals.java:16`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/permission/engine/PermissionEngine.java`
- **Impact:** Approval UI and pending requests can become inconsistent after
  restart; long-running approvals cannot survive process loss.
- **Work:**
  - [ ] Design a durable approval store with request, deadline, facts and
        decision metadata.
  - [ ] Reconcile pending approvals during recovery.
  - [ ] Decide whether approval waiting should park instead of blocking the
        execution thread.
- **Acceptance:**
  - [ ] A pending approval is visible after restart.
  - [ ] Its deadline and request metadata survive restart.
- **Tests:** restart with pending approval; deadline expiry; duplicate reply.

---

### QOC-010 — Compaction can split a turn

- **Status:** `DONE`
- **Priority:** `P1`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - `CompactionWindow.split` cuts history by message count, not by turn
    boundary.
  - A compaction can therefore separate an assistant tool call from its
    result or retain only part of a turn.
  - Nessy explicitly keeps whole turns in the tail and cuts only between
    turns.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/compaction/CompactionWindow.java`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/compaction/CompactionService.java:100`
  - `docs/spec/session-engine/04-algorithms.md`
- **Impact:** The model can see incomplete tool interactions after compaction.
- **Work:**
  - [ ] Define compaction boundaries in terms of delivered turns or message
        groups.
  - [ ] Keep at least one complete user/assistant/tool turn in the tail.
  - [ ] Add a regression test for a tool call at the split point.
- **Acceptance:**
  - [ ] No compaction splits a tool call from its result.
  - [ ] The last retained messages form a complete turn.
- **Tests:** history with tool call at boundary; repeated compaction.

---

### QOC-011 — Tool calls have no idempotency key

- **Status:** `OPEN`
- **Priority:** `P1`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - `ToolContext` contains session, agent, message and call ids, but no
    stable idempotency key.
  - QOC guarantees at-least-once execution after a crash, but tools have no
    standard key for deduplication.
  - Nessy carries an `IdempotencyKey` on every tool and approval request.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/core/contract/tool/ToolContext.java`
  - `docs/spec/session-engine/06-errors.md`
- **Impact:** Side-effecting tools may execute twice after recovery.
- **Work:**
  - [ ] Add an idempotency key to `ToolContext` and tool events.
  - [ ] Document the contract for tool authors.
  - [ ] Expose it to approvals and subagents where applicable.
- **Acceptance:**
  - [ ] A recovered tool call can be identified by a stable key.
  - [ ] Side-effecting tools can deduplicate on that key.
- **Tests:** recovery scenario; duplicate delivery; key stability.

---

### QOC-012 — Compaction checkpoint is written at message index 0

- **Status:** `OPEN`
- **Priority:** `P2`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - `ContextBuilder` inserts the compaction checkpoint at index 0 of the
    message list.
  - Every compaction therefore rewrites the beginning of the cached prompt
    prefix.
  - Nessy keeps earlier strata stable and appends summaries as closed
    chapters.
- **Evidence:**
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/context/ContextBuilder.java:124`
  - `engine/src/main/java/de/augmentia/oc/engine/runtime/compaction/CompactionService.java`
- **Impact:** Cache hits may drop after compaction even when the recent tail
  is unchanged.
- **Work:**
  - [ ] Evaluate placing the checkpoint after stable system/instruction
        content.
  - [ ] Measure cache impact before changing prompt layout.
- **Acceptance:**
  - [ ] Cache behavior is documented and tested.
- **Tests:** cache affinity tests; compaction request capture.

---

### QOC-013 — Prompt API has no outcome algebra

- **Status:** `OPEN`
- **Priority:** `P2`
- **Source:** Nessy comparison, 2026-10-26
- **Finding:**
  - `PromptResult` exposes only `finish`, token counts, text and structured
    output.
  - There is no explicit `Busy`, `Refused`, `Failed` or `Terminated` outcome.
  - Clients cannot distinguish "no turn happened" from "turn failed".
  - Nessy uses sealed `AskOutcome`/`TellOutcome` for this distinction.
- **Evidence:**
  - `quarkus/src/main/java/de/augmentia/oc/engine/http/Engine.java:1667`
  - `quarkus/src/main/java/de/augmentia/oc/engine/http/HttpApi.java:664`
- **Impact:** Client error handling and retries are ambiguous.
- **Work:**
  - [ ] Design a sealed prompt/turn outcome.
  - [ ] Map it explicitly in `HttpApi`.
  - [ ] Keep usage absent for no-turn outcomes.
- **Acceptance:**
  - [ ] Busy/refused/failed/terminated are distinguishable in the API.
- **Tests:** busy session, terminated session, refused model response.

---


## 5. Decisions required for the fixes

These decisions are part of the current problems, not a general feature
roadmap.

| ID | Decision | Issue | Status | Resolution |
|---|---|---|---|---|
| D-01 | Should shell output be two marked channels or one combined stream? | QOC-001 | RESOLVED | One result text; `[stdout]`/`[stderr]` markers only when both are non-empty. |
| D-02 | Summary key: SHA-256 or generated UUID? | QOC-002 | RESOLVED | SHA-256 hex of the UTF-8 summary. |
| D-03 | Search API: evolve `FileSystem` or add a separate search port? | QOC-004 | RESOLVED | Option-aware default methods on `FileSystem`; `NioFileSystem` overrides the bounded walk. |
| D-04 | Artifact id: session-local or content-addressed? | QOC-005 | RESOLVED | Session-local `art_<call-id>` with SHA-256 metadata; copy on fork. |
| D-05 | Background task registry: in-memory or persisted across resume? | QOC-003 | RESOLVED | In-memory and session-scoped; documented as not surviving an engine restart. |
| D-06 | Branch storage: `parent_id` on messages or separate entry table? | QOC-006 | RESOLVED | `parent_id` on `session_message`; leaf is the last message. |

---

## 6. Deliberately not in this file

The following are **not** current QOC problems. They are proposals in
[`oh-learnings.md`](../analysis/oh-learnings.md) and must not be turned into
issues here unless a decision explicitly starts implementation:

- hashline edit mode, read selectors and content tags
- read summaries and archive/SQLite/PDF support
- scan cache
- token-aware compaction policy, pruning, shake and handoff
- typed subagent batches, task registry features beyond QOC-003, worktree
  isolation
- LSP and DAP integration
- approval tiers, modes, target-aware grants and shell patterns
- `.opencode` context discovery, `RULES.md`, `@path` imports and prompt blocks
- shell streaming, persistent shells and managed services
- lifecycle/context hooks
- model roles and capability matrix
- memory, advisor and TTSR
- session export and checkpoint/rewind

Those belong to the learning and design document. This file stays focused on
what is verifiably wrong or incomplete in the current QOC implementation.

---

*Created: 2026-10-26 · Current-problem register derived from
`docs/analysis/oh-learnings.md`.*
