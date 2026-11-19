# UI Improvements – Plan for Further Review

**Status:** Plan (2026-11-15)
**Reference:** `docs/engineering/Unified-Storage.md` §8, `docs/plans/mobile-workflow-ui.md`

This document collects the known UI improvement items and open architecture
questions that are not yet implemented.

---

## 1. Real File Journal for Runtime (Offline Crash Recovery)

**Problem:** In `file` mode (`engine.storage=file`) the engine runtime is
in-memory — no crash recovery. On restart, sessions, journal, and snapshots
are lost.

**Current state:**
- `memory` mode: everything ephemeral (by design)
- `file` mode: Workflows/Agents from files, runtime in-memory
- `sqlite`/`postgres` mode: full persistence + crash recovery

**Possible implementation:**
- File-based journal (`wf-data/journal/`) persisting sessions, events, and
  snapshots as JSON files
- Analogous to `SqliteAdapters` / `SqlAdapters`, but with a file backend
- Crash recovery: on startup, load open claims (session_claim) and
  resume workflows

**Effort:** 3–5 days
**Dependency:** `ClaimStore`, `Journal`, `SessionStore`, `SnapshotStore` ports
**Risk:** High effort, low benefit (sqlite is already lightweight)

**Recommendation:** Low priority. SQLite already provides offline persistence
with low overhead. Implement the file journal only if offline use without
SQLite is explicitly desired.

---

## 2. UI-based Configuration for Agents, Permissions, and MCP Servers

**Problem:** Agents, permissions, and MCP servers are currently configured via
JSON files (`settings.json`, `application.properties`) or environment
variables. There is no UI for this configuration.

**Current state:**
- Agents: UI available (`/workflow/agents`) with CRUD, but no
  permission configuration
- MCP servers: UI available (`/workflow/mcp-servers`) with Add/Remove/Reload
- Permissions: only via `application.properties` or agent frontmatter

**Possible implementation:**

### 2.1 Agent Permissions in the Agents UI
- `PropertyPanel` / `Agents.tsx`: field for `rolesAllowed` (multi-select)
- Field for `toolAllow` / `toolDeny` (tool picker, already present)
- Guardrail configuration (input/output filters)

### 2.2 Permissions Dashboard
- New page `/permissions` with an overview of all agent permission rules
- Matrix view: Agent × Tool → Allow/Deny/Ask
- Export/import as JSON

### 2.3 MCP Server Configuration in the UI
- Already partially present (`/workflow/mcp-servers`)
- Extension: OAuth configuration, timeout settings, header config
- Health status with automatic retry

### 2.4 Settings UI
- Extend `Settings.tsx`: provider, model, permissions default
- Live reload of configuration without restart

**Effort:** 5–8 days
**Dependency:** Backend endpoints for config CRUD (partially present)
**Risk:** Large scope, many edge cases

**Recommendation:** Medium priority. The Agents UI (phase 2.1) is the biggest
quick win, since `rolesAllowed` and `toolAllow`/`toolDeny` already exist in
the data model and only need to be exposed in the UI.

---

## 3. MCP Server DB Persistence (Migration V12)

**Problem:** MCP servers are stored in `mcp-servers.json`, not in the DB.
With postgres/sqlite mode, DB persistence would be more consistent.

**Current state:** Implemented (commit `ca40412`):
- `SqlMcpServerStore` (DB-backed, uses `DataSource` when available)
- `McpServerPersistence` delegates to `SqlMcpServerStore` when a DataSource is set
- `Engine.wire()` sets `McpServerPersistence.setDataSource(dataSource)`
- V12 migration (postgres + sqlite) for the `mcp_server` table

**Remaining work:**
- Tests for `SqlMcpServerStore`
- Verification in DB mode (postgres/sqlite)
- Fallback behavior on DB error (currently: silent errors, no fallback to file)

**Effort:** 0.5 days (tests + verification)

---

## 4. Further UI Improvements

### 4.1 Mobile Optimization (see mobile-workflow-ui.md)
- Phases 1–3 implemented (responsive layout, NodeListView, BottomSheet)
- Phase 4 (tests & polish) still open
- Card layouts instead of tables for Workflows/Runs/Agents/McpServers

### 4.2 Workflow-Generate UI
- `Generate.tsx` exists, but the backend endpoint is now implemented
- UI could open the generated workflow directly in the editor

### 4.3 Context Picker (see node-data-contracts plan)
- PropertyPanel tab "Context" for explicit node selection
- Token budget display

### 4.4 Dark/Light Theme Toggle
- Currently dark mode only
- Tailwind `dark:` prefix already present

### 4.5 Keyboard Shortcuts
- GraphEditor: Delete, Copy/Paste, Undo/Redo
- Global shortcuts for navigation

---

## Prioritization

| Priority | Item | Effort | Benefit |
|---|---|---|---|
| 1 | 2.1 Agent permissions in Agents UI | 1 day | High |
| 2 | 3.1 SqlMcpServerStore tests | 0.5 day | Medium |
| 3 | 4.1 Mobile card layouts | 2 days | Medium |
| 4 | 2.2 Permissions dashboard | 3 days | Medium |
| 5 | 4.5 Keyboard shortcuts | 2 days | Medium |
| 6 | 1. File journal for runtime | 3–5 days | Low |
| 7 | 2.3 MCP server OAuth UI | 2 days | Low |
| 8 | 4.4 Theme toggle | 1 day | Low |
