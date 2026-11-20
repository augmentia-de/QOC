# Tool Configuration Plan

## Goal

Make the agent editor capable of managing 60+ tools (core, MCP, A2A, email, worktree, session, agent, todo) with clear permission visibility, while keeping least-privilege as the secure default.

## Problem

- `AgentConfigOverlay` hardcodes `TOOL_OPTIONS` (16 core tools). Dynamic tools (MCP, A2A, email, worktree, session, todo) are invisible.
- The "All" agent references 22+ tools but the UI can only show 16; the rest are silently dropped from the checkbox grid.
- Permissions are stored as a concrete `Rule[]` but there is no per-tool permission editor; users rely on presets.
- No feedback on which selected tools are currently denied by the active permission mode.

## Non-Goals

- No change to the permission engine or rule evaluation.
- No new backend endpoints beyond reusing `/api/tool` and `/api/agents/permission-check`.
- No drag-and-drop library dependency; use native HTML5 drag-and-drop.

## Design

### 1. Tool Discovery (dynamic)

Replace the hardcoded `TOOL_OPTIONS` with a fetch from `Catalog.tools()`:

```ts
const res = await Catalog.tools(); // { location, data: [{name, namespace}] }
```

- Fetch on overlay open (`useEffect`).
- Fallback to the 16 core tools if the fetch fails.
- Group tools by `namespace` (core, email, worktree, mcp, a2a, session, agent, todo).
- Sort within group alphabetically by name.

### 2. Selection UI (two-pane + search)

Layout:
```
┌─────────────────────────────────────────────────────┐
│ Search: [____________]  [Select all] [Clear]        │
├────────────────────┬────────────────────────────────┤
│ Available (47)     │ Selected (12)                  │
│ ┌──────────────┐  │ ┌──────────────┐              │
│ │ ▼ core (16)  │  │ │ read         │              │
│ │   read       │  │ │ write        │              │
│ │   write      │→ │ │ ...          │              │
│ │   edit       │  │ └──────────────┘              │
│ │ ▼ mcp (8)    │  │                                │
│ │   mcp.x      │  │                                │
│ └──────────────┘  │                                │
└────────────────────┴────────────────────────────────┘
```

- Native HTML5 drag-and-drop between panes.
- Add/Remove buttons for keyboard accessibility.
- Bulk: "select all in namespace", "clear selection".
- Counter badge: "12 of 47 selected".
- Stale tools (in agent.tools but not in available list) shown in a separate "Stale / unavailable" section, greyed out, with a remove button. Never silently dropped.

### 3. Permission Visibility

- Each selected tool chip shows a status dot:
  - green = ALLOW (resolved from permissions + global default)
  - yellow = ASK
  - red = DENY
- Resolution uses `/api/agents/permission-check` (already exists), called debounced (300ms) after tools/permissions change.
- Denied tools show a tooltip: "Denied by current permission mode (least-privilege). Use a preset or add a rule."

### 4. Per-Tool Permission Editor (expert mode)

- Collapsible "Permission Rules" section below the tool panes.
- Table of rules: action | resource | effect (dropdown).
- Add/remove rule rows.
- Preset selector remains the primary path; selecting a preset resets both tools and rules.
- Manual edits set the preset dropdown to "(custom)".

### 5. Preset Integration

- Preset dropdown at top of form.
- On select: set tools + permissionRules + permissionPreset id.
- On manual tool/rule change: set permissionPreset = '' (dirty).
- On load: match tools+rules to a preset; if no match, ''.

### 6. Invariants

- `tools` ⊆ available tools ∪ stale tools (never silently drop).
- Runtime filter (ContextBuilder) removes DENY tools from LLM snapshot.
- Save-time check warns on denied active tools.

## Implementation Steps

1. **Dynamic tool fetch** in `AgentConfigOverlay`: replace `TOOL_OPTIONS` with `Catalog.tools()`, group by namespace.
2. **Two-pane UI** with search, drag-and-drop, bulk actions, stale section.
3. **Permission status chips** via debounced permission-check.
4. **Per-tool rule editor** (collapsible table).
5. **Dirty-flag** for preset.
6. **Tests**: manual smoke test of 60-tool selection, permission-check response.

## Files to Change

- `reactflow/src/components/AgentConfigOverlay.tsx` — main UI rewrite.
- `reactflow/src/pages/Agents.tsx` — loadAvailable pattern (already partially).
- `reactflow/src/pages/agentPresets.ts` — already exists.
- `reactflow/src/services/qocApi.ts` / `shared/api.ts` — Catalog already exposed.

## Risks

- Large tool lists (60+) may cause render lag; use virtualization or pagination if needed. Start without; measure.
- Drag-and-drop accessibility; provide button fallback.
- MCP tools change between sessions; stale handling covers this.

## Acceptance Criteria

- Editor shows all tools from `/api/tool` grouped by namespace.
- User can select/deselect 60+ tools via search + drag-and-drop.
- Permission status visible per tool.
- Preset selection and custom editing both work.
- No tools silently dropped on save/load.
- Build green, host HTTP 200.
```

Now implement. Step 1: dynamic tool fetch + grouping in AgentConfigOverlay. Let me rewrite the overlay's tool section. This is a substantial UI change. Let me do it carefully.

I'll replace the TOOL_OPTIONS checkbox grid with a dynamic two-pane. Let me rewrite AgentConfigOverlay.tsx.<|end|><|start|><|start|>assistant to=write<|channel|>analysis <|constrain|>xml<|message|><rfl:parameter name="path">
/home/torsten/dev/my-projects/2oc/qoc/docs/plans/tool-configuration.md