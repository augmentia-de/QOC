# Analysis: Two different "agent" systems in QOC

> Status: Analysis (2026-11-13)
> Supplements: `Workflow.md`, `Workflow-OIDC.md`, `CONFIG.md`

## 1. Overview

QOC has **two separate agent systems** that are not connected to each other.
A workflow can currently **not** use QOC-defined agents (e.g. `build`, `plan`,
Markdown agents) – only workflow-own agents or global workflow agents.

---

## 2. System 1: QOC agents (engine level)

### 2.1 Sources

| Source | Path / mechanism | Description |
|---|---|---|
| **Config (JSON)** | `settings.json` → `agents[]` / `application.properties` `agents` | `EngineConfig.load()` parses `agents[].name`, `system`, `mode`, `model`, `tools`, `permissions`, `rolesAllowed`, `mcpServers`, `hidden` |
| **Markdown (global)** | `~/.config/opencode/agents/` | `AgentMarkdownLoader.loadDirectory()` |
| **Markdown (project)** | `<workspace>/agents/` (when `workspaceSettingsEnabled`) | Only when workspace settings are active |
| **Runtime-defined** | `engine.define(name, content)` | In memory only, lost on restart (unless persisted) |
| **Baseline** | `defaultBuildAgent()`, `defaultPlanAgent()` | Fallback when no agents are configured |

### 2.2 Data model

`EngineConfig.AgentConfig` (Record, `EngineConfig.java:719`):
- `name`, `system` (system prompt), `hidden`, `mode` (primary/subagent/all),
  `model`, `maxSteps`, `toolAllow`, `toolDeny`, `permissions` (Rules),
  `mcpServers`, `rolesAllowed`.

### 2.3 Usage

- **Quarkus Engine** (`Engine.java`): `agentCatalogue.config(name)` →
  `EngineConfig.AgentConfig` → `QocEngine.AgentConfig` → `runPrompt`.
- **Subagents**: `engine.isSubagent(name)`, `engine.agentAllowed(name, roles)`.
- **Sessions**: `createSession(agentName, ...)` → `AgentId` from `AgentCatalogue`.
- **Skills**: `agentSkills(agentName)` → JIT skill selection.

### 2.4 Endpoints

- `GET /api/agent` → `AgentCatalogue.AgentDto {id, name, hidden}` (filtered
  by `agentAllowed`).
- `GET /api/agent/{name}` → Content + Config + Hash.
- `POST/PUT/DELETE /api/agent/{name}` → Authoring (Admin, not serverless).

### 2.5 Lifecycle

- Loaded at startup (`@PostConstruct load()`).
- Reload via `agentCatalogue.reload()` (on workspace change).
- Runtime-defined agents survive reloads (`runtimeConfigs`).
- Markdown agents: global always, project only when `workspaceSettingsEnabled`.

---

## 3. System 2: Workflow agents (workflow runner level)

### 3.1 Sources

| Source | Path / mechanism | Description |
|---|---|---|
| **Embedded** | `WorkflowDefinition.agents` (JSON array in the workflow definition) | Agents that are part of a specific workflow definition |
| **Global (file store)** | `wf-data/agents/*.json` via `AgentStore` | Reusable agents, referenced via `agentRef` |

### 3.2 Data model

`WorkflowDefinition.Agent` (Record, `WorkflowDefinition.java:125`):
- `id`, `name`, `version`, `category`, `model`, `temperature`, `maxTokens`,
  `topP`, `tools`, `guardrailsInput`, `guardrailsOutput`, `hooks`,
  `jsonOutput`, `active`, `systemPrompt`, `responseSchema`,
  `ownerId`, `rolesAllowed`.

### 3.3 Usage

- **Workflow node** (`type="agent"`): `agentRef` → `findAgent(def, agentRef)`
  → `WorkflowDefinition.Agent` → `toAgentConfig()` → `QocEngine.AgentConfig`
  → `engine.runPrompt(childSession, prefixedName, prompt, config, ...)`.
- **Prefixed name**: `wf:<Name>:<Version>` (name-collision avoidance).
- **Child session**: Each agent node creates its own session.

### 3.4 Endpoints

- `GET /api/agents` → `AgentStore.listAll()` (global agents).
- `GET /api/agents/{id}` → single agent.
- `POST /api/agents` → create.
- `PUT /api/agents/{id}` → save (rename possible).
- `DELETE /api/agents/{id}` → delete.

### 3.5 Lifecycle

- Loaded from `wf-data/agents/*.json` (file-based).
- Cache in `AgentStore` (`ConcurrentHashMap`).
- CRUD via REST API (UI).
- RBAC: `ownerId` + `rolesAllowed` (newly added).

---

## 4. Comparison

| Aspect | QOC agents | Workflow agents |
|---|---|---|
| **Storage location** | settings.json / Markdown files | JSON files (`wf-data/agents/`) or embedded |
| **Format** | Markdown (OpenCode style) or JSON config | JSON (Jackson) |
| **Model config** | `EngineConfig.AgentConfig` | `WorkflowDefinition.Agent` |
| **Tools** | `toolAllow`/`toolDeny` (patterns) | `tools` (list of tool names) |
| **Guardrails** | `permissions` (Rules) | `guardrailsInput`/`guardrailsOutput` (lists) |
| **Skills** | JIT skill selection (`agentSkills`) | not supported |
| **Mode** | `primary`/`subagent`/`all` | not present |
| **Roles** | `rolesAllowed` (QOC side) | `rolesAllowed` (newly added) |
| **Owner** | not present | `ownerId` (newly added) |
| **Hidden** | `hidden` | `active` (inverted) |
| **Structured output** | not present | `responseSchema` (JSON Schema) |
| **Versioning** | not present | `version` field |
| **Category** | not present | `category` field |
| **Endpoint** | `/api/agent` (singular) | `/api/agents` (plural) |
| **UI** | Agent catalogue (limited) | `Agents.tsx`, `PropertyPanel.tsx` |

---

## 5. Problem: no bridge

### 5.1 Current state

`DefaultNodeDispatcher.findAgent()` (`DefaultNodeDispatcher.java:282`):

```java
private WorkflowDefinition.Agent findAgent(WorkflowDefinition def, String agentRef) {
    if (agentRef == null || agentRef.isBlank()) return null;
    for (WorkflowDefinition.Agent a : def.agents()) {
        if (a.id().equals(agentRef)) return a;
    }
    // Option B: agents are stored globally and referenced by id.
    return agentStore == null ? null : agentStore.get(agentRef);
}
```

**No fallback to QOC agents.** When `agentRef = "build"` (a QOC agent),
`findAgent` returns `null`. Then:
- `toAgentConfig(null, ...)` produces an `AgentConfig` with `model=null`,
  `systemPrompt=null`, empty tools/guardrails.
- `runPrompt` uses `buildModelInfo` → falls back to the engine default model
  (because `config.model()` is blank).
- The QOC agent loses its system prompt, tools, guardrails, and skills.
- The agent name in the registry becomes `wf:build:1.0.0` instead of `build`.

→ **QOC agents are de facto unusable in workflows** (only a name collision
with degraded behavior).

### 5.2 UI

- The agent-ref dropdown in `PropertyPanel.tsx` is populated from the `agents`
  prop, which comes from `AgentApi.list()` → `/api/agents` (workflow store).
- QOC agents (build, plan, Markdown agents) **do not** appear in this list.
- The `Agents.tsx` page manages exclusively workflow agents (CRUD via
  `/api/agents`).
- The GraphEditor default agent "build" is a workflow agent, not the QOC
  build agent.

---

## 6. Solution options

### Option A – Import endpoint (recommended)

New endpoint `POST /api/agents/import-from-qoc` (or `/api/agents/import`),
which reads `engine.agents()` / `engine.agentConfig(name)` /
`engine.agentContent(name)` and converts it into a `WorkflowDefinition.Agent`,
then writes it to the `AgentStore`. After that the agent appears in
`/api/agents` and the UI dropdown.

**Advantages:** No duplication at runtime; agents are editable in the UI.
**Disadvantages:** Manual import; QOC changes are not synchronized
automatically.

### Option B – Fallback in `findAgent`

If `agentRef` is found neither in `def.agents()` nor in the `AgentStore`,
query `engine.agentConfig(agentRef)` / `engine.agentContent(agentRef)` and
convert on the fly into a `WorkflowDefinition.Agent`.

**Advantages:** No duplication; always up to date.
**Disadvantages:** Conversion on every run; QOC agents do not appear in the
UI list.

### Option C – Merge into `/api/agents`

`AgentStoreResource.list()` adds QOC agents (marked with `source: "qoc"`) to
the response, so the UI shows and selects them. Saving would then trigger
Option A.

**Advantages:** UI shows all agents.
**Disadvantages:** More complex UI logic (read-only for QOC agents).

---

## 7. Conversion mapping (QOC → WorkflowDefinition.Agent)

| QOC (`EngineConfig.AgentConfig`) | Workflow (`WorkflowDefinition.Agent`) |
|---|---|
| `name` | `name` / `id` |
| `model` | `model` |
| `system` (systemPrompt) | `systemPrompt` |
| `tools` | `tools` |
| `guardrailsInput/Output` | `guardrailsInput/Output` |
| `hooks` | `hooks` |
| `mode` (primary/subagent/all) | (new: `mode` field?) |
| `skills` | (new or embedded in systemPrompt) |
| `hidden` | `active` (inverted) |
| `jsonOutput` | `jsonOutput` |
| `rolesAllowed` | `rolesAllowed` |
| `maxSteps` | (new or in `maxTokens`?) |

---

## 8. Limitations / things to consider

1. **Tools**: QOC agents can reference QOC-internal tools (Shell, Files, Web,
   MCP, subagents). The workflow runner uses the same `QocEngine` instance,
   so the same tools are available – but the `ToolSnapshot` in workflow mode
   is empty (`toolSnapshot` = null). Check whether the agent's `tools` list is
   respected in workflow mode (`toAgentConfig` passes `a.tools()` to
   `AgentConfig.tools`).

2. **Permissions**: Workflow runner uses a no-op PermissionGate; QOC agents
   with `least-privilege` would be allowed everything in a workflow. For parity
   the gate would have to be carried over.

3. **Skills**: QOC skills (JIT) are not loaded in workflow mode (no
   `skillSelection` call in `DefaultNodeDispatcher`). Would need to be added.

4. **Subagents**: QOC agents can call subagents; in workflow mode this works
   via the same `SubagentTool`, but recursion-depth limits apply.

5. **Markdown agents**: `AgentMarkdownLoader` parses `.md` files. These could
   serve as an import source (scanning the global directory).

6. **RBAC**: QOC agents have `rolesAllowed`. Workflow agents now also have
   `rolesAllowed` (new). The workflow runner does not currently check
   `rolesAllowed` (only `AgentAccess` in the Quarkus engine). Would need to be
   added in `DefaultNodeDispatcher` or the workflow runner.

---

## 9. SSOT unification (implemented)

Agents now have a **single source of truth**:

- **AgentStorePort** (interface) with implementations: `AgentStore` (File),
  `InMemoryAgentStore`, `SqliteAgentStore`, `PostgresAgentStore`.
- **AgentStoreFactory** selects the backend analogous to
  `WorkflowStoreFactory` (auto/memory/sqlite/postgres/file).
- **DB schema**: `agent_definition` table (V11 migration) stores agents as
  JSON.
- **WorkflowBeans** now uses `AgentStoreFactory` instead of
  `new AgentStore(dir)`.
- **AgentStoreResource** and **WorkflowResource** use `AgentStorePort`.

### Format

- **On-disk (OpenCode compatible)**: Markdown files with YAML frontmatter
  (`~/.config/opencode/agents/`, `<workspace>/agents/`).
- **In DB/file (SSOT)**: JSON (`WorkflowDefinition.Agent` format with
  `ownerId`, `rolesAllowed`, `responseSchema`).
- **Workflows**: JSON in DB (already present via `WorkflowStoreFactory`).

### Implemented (SSOT infrastructure)

- `AgentStorePort` (interface) with implementations: `AgentStore` (File),
  `InMemoryAgentStore`, `SqliteAgentStore`, `PostgresAgentStore`.
- `AgentStoreFactory` selects the backend analogous to
  `WorkflowStoreFactory`.
- DB schema: `agent_definition` table (V11 migration).
- `WorkflowBeans` uses `AgentStoreFactory` instead of `new AgentStore(dir)`.
- `AgentStoreResource`, `WorkflowResource` use `AgentStorePort`.
- `QocAgentConverter.fromQoc()` converts `EngineConfig.AgentConfig` →
  `WorkflowDefinition.Agent`.
- `WorkflowRecovery.importQocAgents()` imports QOC agents (config +
  global Markdown) into the unified store at startup.

### Implemented (SSOT integration)

- `Engine.config()` (Quarkus) now carries `system`, `tools` (from
  `toolAllow`) into the runtime `QocEngine.AgentConfig` (previously lost).
- `DefaultNodeDispatcher.findAgent()` (Option B): QOC agents not in the
  store are converted on the fly from the engine's `AgentResolver`
  (`fromQocRuntime`) and written to the store (caching).
- `WorkflowRecovery.importQocAgents()` imports at startup (and on
  `Engine.reloadConfig()`):
  1. Config agents (settings.json / application.properties).
  2. Markdown agents (global: `~/.config/opencode/agents/`).
  3. Markdown agents (project-level, when `workspaceSettingsEnabled`).
- Project-level Markdown agents (`projectAgentDirs`) are now imported.

### Remaining work (QOC side)

- The QOC engine still loads agents from `EngineConfig` (settings.json/Markdown)
  in addition to the store. For full SSOT: EngineConfig should load agents
  from `AgentStorePort` (or populate `EngineConfig.agents()` from the store).
- Markdown → JSON import: implemented (`WorkflowRecovery.importQocAgents`).
- JSON → Markdown export: implemented (`AgentMarkdownExporter`, best-effort
  on create/save in `AgentStoreResource`).
- Remaining: `EngineConfig.agents()` still reads from settings.json/Markdown;
  for full SSOT, EngineConfig should load agents from `AgentStorePort` (or
  populate `agents()` from the store). The order (store population before
  EngineConfig load) must be adjusted.
- RBAC (`rolesAllowed`) is now checked in the workflow runner
  (`DefaultNodeDispatcher.agentAllowed`); '*' = all, admin/system always allowed.

## 10. Conclusion

Currently **no** use of QOC agents in workflows is possible (except for a
name collision with loss of configuration). For real usage an **import or
fallback mechanism** (Option A/B) plus **conversion**
`EngineConfig.AgentConfig → WorkflowDefinition.Agent` is needed, along with
handling tools/skills/permissions in workflow mode.

The SSOT infrastructure (DB backend, factory, migration) is now in place.
The next step is the QOC-side integration (loading agents from the store or
importing Markdown).