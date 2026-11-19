# MCP Tools in the UI — Integration (Quarkus-based)

**Status:** Plan (2026-11-11) · Reference: `Additional-Nodes.md`, `ReAct-Umsetzung.md`
**Assumption:** The workflow runner runs via Quarkus (`WorkflowResource`); `WorkflowRunnerMain` is no longer needed.

## Goal

Configured MCP tools should:
1. Appear in the UI as selectable tools (Agent-Node config, mcp_call-Node config)
2. Be dynamically reloadable/reinitializable (without restart)
3. Be directly callable as an `mcp_call` node

## Current State (Quarkus)

### Backend
- `Engine` (Quarkus) owns `McpRegistry` → `McpServerManager` (MCP client registry)
- MCP servers from `EngineConfig.mcpServers()` are loaded at startup (`mcp.setup()`)
- MCP tools are registered in `ToolRegistry` → `ToolSnapshot` → `ToolRuntime`
- `McpServerResource` (`/mcp`) is an **MCP server** (exposes QOC as MCP), not a client
- **No** REST endpoints for listing MCP servers/tools for the UI
- `Engine.mcp()` (McpRegistry) is public → `mcp.manager()` returns McpServerManager
- `Engine.qocEngine()` returns QocEngine for workflow execution

### Frontend (React)
- `api.ts`: `WorkflowApi`, `HealthApi`, `AgentApi` — **no** `ToolApi` or `McpApi`
- `PropertyPanel.tsx`: Agent node shows `tools` as a free-text array
- No tool picker, no MCP panel
- No dynamic reloading

### QocEngine (Workflow execution)
- Creates its own internal `ToolRegistry` + `ToolSnapshot` (no MCP tools)
- MCP tools are **not** in the QocEngine snapshot → Agent nodes cannot call them at runtime
- The `mcp_call` node can use `McpServerManager.client().callTool()` directly (bypasses QocEngine)

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Quarkus Engine                                             │
│                                                              │
│  ┌──────────────────┐                                        │
│  │ McpRegistry      │  ← Already present                     │
│  │  → McpServerMgr  │  ← MCP client registry                 │
│  │  → McpServerConfig│  ← From EngineConfig (application.yml) │
│  └────────┬─────────┘                                        │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────┐    ┌──────────────────┐                │
│  │ ToolRegistry     │    │ /mcp/* REST      │                │
│  │ (MCP tools       │    │ (NEW: for UI)    │                │
│  │  already in)     │    │                  │                │
│  └────────┬─────────┘    └────────┬─────────┘                │
│           │                       │                          │
│           ▼                       ▼                          │
│  ┌──────────────────────────────────────────┐               │
│  │              Frontend (React)              │               │
│  │  • ToolPicker (Agent-Node + mcp_call)     │               │
│  │  • McpServerPanel (Status, Reload, Add)   │               │
│  │  • Dynamic Refresh (Polling + Button)     │               │
│  └──────────────────────────────────────────┘               │
│                                                              │
│  ┌──────────────────┐                                        │
│  │ QocEngine        │  ← For workflow execution                │
│  │ (no MCP in       │  ← mcp_call nodes use                   │
│  │  snapshot)       │     McpServerManager directly            │
│  └──────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

## Status: Implemented

Option B (callback-based tool registration) has been implemented:

- `ToolDependencies` receives a `toolRegistrar` field (`Consumer<ToolRegistry>`, default no-op)
- `QocEngine.create()` invokes `toolRegistrar.accept(registry)` before the snapshot is taken
- Quarkus `Engine` passes a callback that registers MCP tools from `McpServerManager` into QocEngine's registry
- → Agent nodes in workflows can now invoke MCP tools at runtime

### What has been implemented

| Component | File | Status |
|---|---|---|
| `toolRegistrar` in `ToolDependencies` | `engine/.../wiring/ToolDependencies.java` | ✅ |
| Callback in `QocEngine.create()` | `engine/.../runtime/QocEngine.java` | ✅ |
| MCP tools in QocEngine callback | `quarkus/.../http/Engine.java` | ✅ |
| `mcp()` getter in Engine | `quarkus/.../http/Engine.java` | ✅ |
| `McpClientResource` (REST) | `quarkus/.../http/McpClientResource.java` | ✅ |
| `mcp_call` node type | `DefaultNodeDispatcher.java` | ✅ |
| `NodeData` extended (mcpServer, mcpTool, mcpArgs) | `WorkflowDefinition.java` | ✅ |
| `GraphCompiler` VALID_NODE_TYPES | `GraphCompiler.java` | ✅ |
| `remove()` in McpServerManager | `McpServerManager.java` | ✅ |
| Frontend: ToolPicker, McpServers, api.ts | `workflow-ui/` | ✅ |
| PropertyPanel: mcp_call section | `PropertyPanel.tsx` | ✅ |
| GraphEditor: mcp_call node | `GraphEditor.tsx` | ✅ |

## Backend Changes (Quarkus)

### 1. New REST resource: `McpClientResource`

New file: `quarkus/src/main/java/de/augmentia/oc/engine/http/McpClientResource.java`

```java
@Path("/api/mcp-client")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpClientResource {

    @Inject
    Engine engine;

    /** List of all MCP servers with status. */
    @GET
    @Path("/servers")
    public Response listServers() {
        McpServerManager mgr = engine.mcp().manager();
        List<Map<String, Object>> servers = new ArrayList<>();
        for (String name : mgr.serverNames()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("name", name);
            s.put("status", mgr.status(name).name());
            s.put("toolCount", mgr.discovered(name).size());
            s.put("error", mgr.error(name));
            servers.add(s);
        }
        return Response.ok(Map.of("servers", servers)).build();
    }

    /** Tools of a specific MCP server. */
    @GET
    @Path("/servers/{name}/tools")
    public Response serverTools(@PathParam("name") String name) {
        McpServerManager mgr = engine.mcp().manager();
        List<McpRegistrar.McpToolSpec> tools = mgr.discovered(name);
        List<Map<String, Object>> result = new ArrayList<>();
        for (McpRegistrar.McpToolSpec t : tools) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", t.name());
            tool.put("description", t.description());
            tool.put("inputSchema", t.inputSchema());
            result.add(tool);
        }
        return Response.ok(Map.of("server", name, "tools", result)).build();
    }

    /** Re-discover tools for a server. */
    @POST
    @Path("/servers/{name}/reload")
    public Response reloadServer(@PathParam("name") String name) {
        McpServerManager mgr = engine.mcp().manager();
        mgr.reload(name);
        List<McpRegistrar.McpToolSpec> tools = mgr.discovered(name);
        return Response.ok(Map.of(
            "server", name,
            "status", mgr.status(name).name(),
            "tools", tools.size()
        )).build();
    }

    /** Add a new MCP server. */
    @POST
    @Path("/servers")
    public Response addServer(Map<String, Object> config) {
        // Parse config → McpServerConfig
        // mcp.add(config)
        // Return status
    }

    /** Remove an MCP server. */
    @DELETE
    @Path("/servers/{name}")
    public Response removeServer(@PathParam("name") String name) {
        // mcp.remove(name) — if McpServerManager supports remove
        // Otherwise: mark as "disabled"
    }
}
```

### 2. `GET /api/tools` Endpoint

New resource or in `WorkflowResource`:

```java
@GET
@Path("/tools")
public Response listTools() {
    List<Map<String, Object>> tools = new ArrayList<>();

    // Built-in tools (from Engine's ToolRegistry or hardcoded)
    // MCP tools (from McpServerManager)
    McpServerManager mgr = engine.mcp().manager();
    for (String serverName : mgr.serverNames()) {
        if (mgr.status(serverName) != McpServerStatus.CONNECTED) continue;
        for (McpRegistrar.McpToolSpec t : mgr.discovered(serverName)) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", serverName + "/" + t.name());
            tool.put("description", t.description());
            tool.put("source", "mcp:" + serverName);
            tools.add(tool);
        }
    }

    // Built-in tools (from ToolRegistry snapshot or hardcoded list)
    // ...

    return Response.ok(Map.of("tools", tools)).build();
}
```

**Note:** Built-in tools (file, web, shell, etc.) live in `Engine`'s `ToolRegistry`. To list them, one could expose `engine.toolSnapshot()` or use a hardcoded list. Recommendation: use a hardcoded list of the known built-in tools, since `ToolSnapshot` is not public.

### 3. Adding MCP server config at runtime

`McpServerManager.add(McpServerConfig)` already exists. For dynamic addition via the UI:

```java
@POST
@Path("/servers")
public Response addServer(Map<String, Object> body) {
    String name = (String) body.get("name");
    String type = (String) body.get("type"); // "local" or "remote"
    McpServerConfig config;
    if ("local".equals(type)) {
        List<String> command = (List<String>) body.get("command");
        config = new McpServerConfig.Local(name, command, ...);
    } else {
        String url = (String) body.get("url");
        config = new McpServerConfig.Remote(name, url, ...);
    }
    engine.mcp().manager().add(config);
    return Response.ok(Map.of(
        "name", name,
        "status", engine.mcp().manager().status(name).name()
    )).build();
}
```

**Limitation:** `McpServerManager` has no `remove()` method. For removal: mark the server as "disabled" or manipulate the `servers` map (Reflection, not recommended). Recommendation: add a `remove()` method to `McpServerManager` (new method, no change to existing ones).

### 4. `mcp_call` node type

- Extend `NodeData`: `mcpServer`, `mcpTool`, `mcpArgs`
- Implement `McpCallNodeHandler` (uses `engine.mcp().manager()`)
- `GraphCompiler` VALID_NODE_TYPES: `mcp_call`

```java
public class McpCallNodeHandler implements NodeHandler {
    @Override
    public String type() { return "mcp_call"; }

    @Override
    public NodeResult handle(WorkflowDefinition.Node node, WorkflowDefinition def,
                            WorkflowContext ctx, String input, NodeHandlerContext context) {
        String server = node.data().mcpServer();
        String tool = node.data().mcpTool();
        Map<String, Object> args = resolveArgs(node.data().mcpArgs(), ctx);

        McpClient client = context.mcpManager().client(server);
        if (client == null) {
            return new NodeResult("MCP server not found: " + server, null);
        }
        Object result = client.callTool(tool, args);
        ctx.setNodeOutput(node.id(), result);
        return new NodeResult(result.toString(), result);
    }
}
```

Extend `NodeHandlerContext`: `McpServerManager mcpManager` (or `Engine engine`).

## Frontend Changes

### 1. Extend API service (api.ts)

```typescript
export interface ToolInfo {
  name: string;
  description: string;
  source: string; // "builtin" | "mcp:<server>"
}

export interface McpServerInfo {
  name: string;
  status: string;
  toolCount: number;
  error: string | null;
}

export interface McpToolInfo {
  name: string;
  description: string;
  inputSchema: Record<string, unknown>;
}

export const ToolApi = {
  list: () => api.get<{ tools: ToolInfo[] }>('/tools'),
};

export const McpClientApi = {
  listServers: () => api.get<{ servers: McpServerInfo[] }>('/mcp-client/servers'),
  getServerTools: (name: string) => api.get<{ server: string; tools: McpToolInfo[] }>(`/mcp-client/servers/${name}/tools`),
  reloadServer: (name: string) => api.post<{ server: string; status: string; tools: number }>(`/mcp-client/servers/${name}/reload`),
  addServer: (config: Record<string, unknown>) => api.post<{ name: string; status: string }>('/mcp-client/servers', config),
  removeServer: (name: string) => api.delete<{ deleted: boolean }>(`/mcp-client/servers/${name}`),
};
```

### 2. ToolPicker component

Multi-select dropdown for Agent-node tools and mcp_call-node tools:

- Loads tools via `ToolApi.list()`
- Groups by `source` (builtin / mcp:<server>)
- Checkboxes or tag-based selection
- Shows tool description as tooltip
- "Refresh" button

### 3. McpServerPanel (new page or settings tab)

- List of all MCP servers with status badge (green/red/grey)
- "Reload" button per server
- "Add Server" form (Name, Type: local/remote, Command/URL)
- "Remove" button
- Expandable: shows the server's tools (via `getServerTools`)

### 4. PropertyPanel changes

- **Agent node:** `tools` field → ToolPicker component instead of free text
  - Optional: show only built-in tools (MCP tools not for Agent nodes)
- **mcp_call node:** Server dropdown + Tool dropdown (from `/mcp-client/servers/:name/tools`)
  - `mcpArgs`: JSON editor with data-piping support

### 5. Dynamic refresh

- **Polling:** call `ToolApi.list()` every 30 seconds
- **Manual refresh:** "Refresh Tools" button in the PropertyPanel and in the McpServerPanel
- **After reload:** `McpClientApi.reloadServer()` → then refetch `ToolApi.list()`

## Dynamic Reinit — Flow

```
1. User clicks "Reload" on MCP server "github"
   → POST /api/mcp-client/servers/github/reload
   → McpServerManager.reload("github")
   → discover() → new tools
   → Response: updated tool list

2. Frontend:
   → McpClientApi.reloadServer("github") resolves
   → McpServerPanel updates tool list
   → ToolPicker (if open) refetches via ToolApi.list()
   → Agent-node tools are updated

3. Polling (30s):
   → ToolApi.list() → new tools appear automatically
```

## Implementation Steps

| # | What | File(s) | Effort |
|---|-----|-----------|---------|
| 1 | Create `McpClientResource` (Quarkus JAX-RS) | New file | Medium |
| 2 | `GET /api/tools` endpoint | WorkflowResource or new resource | Low |
| 3 | Add `McpServerManager.remove()` method (engine) | McpServerManager.java | Low |
| 4 | `POST /api/mcp-client/servers` (add) + `DELETE` (remove) | McpClientResource | Low |
| 5 | Extend `NodeHandlerContext`: `mcpManager` or `engine` | NodeHandlerContext | Low |
| 6 | Implement `McpCallNodeHandler` | New handler | Low |
| 7 | Extend `NodeData`: `mcpServer`, `mcpTool`, `mcpArgs` | WorkflowDefinition.java | Low |
| 8 | `GraphCompiler` VALID_NODE_TYPES: `mcp_call` | GraphCompiler.java | Low |
| 9 | `ToolApi` + `McpClientApi` in api.ts | api.ts | Low |
| 10 | ToolPicker component | ToolPicker.tsx (new) | Medium |
| 11 | McpServerPanel (page or settings tab) | McpServers.tsx (new) | Medium |
| 12 | PropertyPanel: Agent node → ToolPicker | PropertyPanel.tsx | Medium |
| 13 | PropertyPanel: mcp_call node → Server/Tool dropdowns | PropertyPanel.tsx | Medium |
| 14 | GraphEditor: register nodeTypes (mcp_call) | GraphEditor.tsx | Low |
| 15 | Dynamic refresh (polling + button) | ToolPicker, McpServerPanel | Low |
| 16 | Tests (backend + Playwright) | — | Medium |

## Native Deployment

- `McpServerManager` uses `JdkHttpTransport` + `JdkProcessRunner` → native-compatible ✅
- `McpClientResource` is JAX-RS → native-compatible ✅
- Quarkus Native Profile (`-Pnative`) already exists
- **Limitation:** MCP servers with stdio transport require an external process at runtime

## Open Questions

1. **Listing built-in tools:** Should `/api/tools` list only MCP tools or also built-in tools (file, web, shell)? Recommendation: both, grouped by source.
2. **ToolSnapshot for QocEngine:** Should MCP tools be in QocEngine's snapshot so Agent nodes can use them? Recommendation: yes, but requires QocEngine changes (new parameter or pre-discovery). Separate ticket.
3. **Polling interval:** 30s? 60s? Recommendation: 30s, configurable via `application.properties`.
4. **MCP server config at runtime:** Should servers added via the UI be persisted (in `application.yml` or DB)? Recommendation: in-memory for now, persistence later.