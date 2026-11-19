# Per-Task Directory-Scoping (only certain directories writable)

> Status: Plan (2026-11-14)
> Reference: `Two-Agent-Systems.md`, `Workflow.md`, `CONFIG.md`

## 1. Goal

For a task (workflow node), code changes should only be allowed in certain
directories; all other paths are **read-only**. The agent must **know** about
this restriction (awareness), must not work around it (no code duplication, no
wrappers/abstractions, no evading), and the restriction must be **adjustable
per task** or resettable via `reloadConfig`.

## 2. Problem

- OpenCode permissions are **static per agent ID**, not session- or
  task-dependent. A session inherits the permissions of its agent.
- Workflow agents receive no permission rules in `Engine.runPrompt`, because
  `agentCatalogue.config(prefixedName)` returns null → `agentRules =
  ContextBuilder.defaults(...)` (everything allowed). `sessionRules` is empty.
- If the agent only gets a generic "permission denied", it typically tries to
  work around it (duplicate code, build wrappers, evade into allowed paths) —
  exactly what should be prevented.

## 3. Data model

### 3.1 `WorkflowDefinition.Agent`

New field:

- `allowedWritePaths: List<String>` — Default `List.of()` = unrestricted.
  Non-empty = allowlist (only these paths writable).

### 3.2 `WorkflowDefinition.NodeData`

New field:

- `allowedWritePaths: List<String>` — Default `null` = inherits from agent.
  Non-null = override for this node (adjustable per task).

### 3.3 `QocEngine.AgentConfig`

New field:

- `allowedWritePaths: List<String>` — passed to the permission system at
  runtime.

## 4. Agent awareness (system prompt injection)

In `DefaultNodeDispatcher` (during `runAgent`/`toAgentConfig`):

- Effective paths = `node.data().allowedWritePaths()` if non-null, otherwise
  `agent.allowedWritePaths()`.
- If non-empty: append to the system prompt:

> ## Writable directories
> You may only modify files under: <paths>. All other paths are read-only.
> IMPORTANT: Do not duplicate code into writable directories, do not create
> wrapper/abstraction layers to bypass this restriction, and do not rename/move
> files to work around it. If a required change falls in a read-only path, stop
> and report it to the user instead of working around it.

This way the agent plans from the start only within the allowed paths.

## 5. Enforcement

### 5.1 Problem

Workflow agents receive no permission rules (see section 2).

### 5.2 Solution (Option A — recommended)

Extend `Engine.createSession(...)` with a `List<Rule> permissions` parameter
(or attach rules to `SessionInfo` afterwards). In `runAgent`, translate the
effective `allowedWritePaths` into rules:

- `deny edit *`
- `allow edit <path>/**` for each path

and attach them to the session. The existing `permissionGate` then checks
`sessionRules` automatically (last match wins, deny overrides stored
approvals).

Advantages:
- Uses the existing permission infrastructure (sessionRules, deny-override,
  audit).
- No special handling in the Engine.
- Rules are per session, hence per task; a new run automatically uses the
  current values.

### 5.3 Deny text (instructive)

When a write attempt falls outside `allowedWritePaths`, the permission check
should not just return "denied" but an explicit instruction:

> Write denied: `<path>` is read-only. Allowed write paths: `<paths>`.
> Do NOT duplicate code, create wrappers, or work around this restriction.
> If the required change is in a read-only path, stop and report the limitation
> to the user.

### 5.4 Deny vs. Ask

- **Recommendation: `deny` (hard)**, not `ask`. Reason: `ask` would prompt the
  user, and the agent could still try to build workarounds upon approval. A
  hard `deny` with a clear message makes it unambiguous to the agent that the
  path is off-limits.
- The user can adjust `allowedWritePaths` for the task and restart (or
  `reloadConfig`) as needed.

### 5.5 Shell protection

The shell scanner checks inferred directories, but `echo > file` or `sed -i`
in a read-only path could slip through. Therefore:

- If `allowedWritePaths` is non-empty: set `shell` to `ask` by default (or
  `deny` for known write commands).
- Alternatively: mark shell commands that write to read-only paths as `deny`
  in the scanner.

### 5.6 Symlinks / traversal

Canonicalize paths (like `external_directory`), otherwise bypass.

## 6. Per-task adjustability + reload

- `NodeData.allowedWritePaths` is defined per node → different tasks (nodes)
  can have different scopes.
- `Agent.allowedWritePaths` as default for all nodes using this agent.
- Override in the UI (PropertyPanel) per node.
- On change: `reloadConfig()` or a new workflow run uses the new values (no
  cache problem, since recomputed per run).

## 7. Failure case: agent behavior on deny

- The agent should **not** duplicate code, build wrappers, or evade into
  allowed paths.
- On a deny: **skip** the affected part and report it in the result instead
  of improvising.
- Record NodeResult with a warning so the user sees:
  - Which path was blocked,
  - Which `allowedWritePaths` were active,
  - That the agent stopped/reported.
- The workflow run should **continue** (not fail immediately), but record the
  warning in the log.

## 8. Import/Export (SSOT)

- `QocAgentConverter.fromQoc` / `fromQocRuntime`: adopt `allowedWritePaths`
  (default empty).
- `AgentMarkdownExporter.toMarkdown`: write `allowedWritePaths` as a
  frontmatter list (`allowedWritePaths:`).
- `AgentMarkdownLoader`: parse it (analogous to the `toolAllow` block).

## 9. UI

- `workflow-ui`: `PropertyPanel.tsx` — field "Writable directories" (list of
  paths) for agent nodes.
- `Agents.tsx` — field for global agents.
- Extend TypeScript types (`workflow.ts`).

## 10. Tests

- Unit: `DefaultNodeDispatcher` — `allowedWritePaths` from node overrides
  agent; empty = unrestricted.
- Enforcement: permission check denies on write attempt outside paths
  (integration with PermissionGate).
- Round-trip: Markdown export/import of paths.
- Behavior: agent receives instructive deny text and system prompt hint.

## 11. Implementation order

1. Data model (Agent, NodeData, QocEngine.AgentConfig) — compiles.
2. System prompt injection (awareness) — low risk.
3. Enforcement via session rules (Option A) — core.
4. Import/Export + UI.
5. Tests.

## 12. Risks / open questions

- **Shell tools:** The shell scanner checks inferred directories;
  `allowedWritePaths` as `edit` rules cover `write`/`edit`/`patch`, but not
  shell-based write operations (e.g. `echo > file`). For those, additional
  `shell` rules or the scanner would be needed.
- **Symlinks / traversal:** Canonicalize paths (like `external_directory`),
  otherwise bypass.
- **Empty = unrestricted vs. read-only everywhere:** Default should be `[]` =
  unrestricted (backwards compatibility). If "read-only everywhere" is desired,
  explicitly `[]` with a `deny edit *` rule — but that's a different
  semantics. Recommendation: `[]` = unrestricted, non-empty = allowlist.
- **Partial restriction:** If the agent needs a change that touches both
  allowed and read-only paths, it should make the change in the allowed paths
  where possible and stop and report for the read-only parts — not try to
  "relocate" the functionality into allowed paths.
