# MCP-Tools in der UI — Integration (Quarkus-basiert)

**Status:** Plan (2026-11-11) · Bezug: `Additional-Nodes.md`, `ReAct-Umsetzung.md`
**Annahme:** Workflow-Runner läuft über Quarkus (`WorkflowResource`), `WorkflowRunnerMain` wird nicht mehr benötigt.

## Ziel

Konfigurierte MCP-Tools sollen:
1. In der UI als auswählbare Tools erscheinen (Agent-Node-Config, mcp_call-Node-Config)
2. Dynamisch nachgeladen/reinitialisiert werden können (ohne Neustart)
3. Als `mcp_call`-Node direkt aufrufbar sein

## Ist-Zustand (Quarkus)

### Backend
- `Engine` (Quarkus) besitzt `McpRegistry` → `McpServerManager` (MCP-Client-Registry)
- MCP-Server aus `EngineConfig.mcpServers()` werden beim Startup geladen (`mcp.setup()`)
- MCP-Tools werden in `ToolRegistry` registriert → `ToolSnapshot` → `ToolRuntime`
- `McpServerResource` (`/mcp`) ist ein **MCP-Server** (exponiert QOC als MCP), nicht Client
- **Keine** REST-Endpoints zum Auflisten von MCP-Servern/Tools für die UI
- `Engine.mcp()` (McpRegistry) ist public → `mcp.manager()` liefert McpServerManager
- `Engine.qocEngine()` liefert QocEngine für Workflow-Ausführung

### Frontend (React)
- `api.ts`: `WorkflowApi`, `HealthApi`, `AgentApi` — **kein** `ToolApi` oder `McpApi`
- `PropertyPanel.tsx`: Agent-Node zeigt `tools` als Freitext-Array
- Kein Tool-Picker, kein MCP-Panel
- Kein dynamisches Nachladen

### QocEngine (Workflow-Ausführung)
- Erstellt eigene interne `ToolRegistry` + `ToolSnapshot` (keine MCP-Tools)
- MCP-Tools sind **nicht** im QocEngine-Snapshot → Agent-Nodes können sie zur Laufzeit nicht aufrufen
- `mcp_call`-Node kann direkt `McpServerManager.client().callTool()` nutzen (umgeht QocEngine)

## Architektur

```
┌─────────────────────────────────────────────────────────────┐
│  Quarkus Engine                                             │
│                                                              │
│  ┌──────────────────┐                                        │
│  │ McpRegistry      │  ← Bereits vorhanden                   │
│  │  → McpServerMgr  │  ← MCP-Client-Registry                 │
│  │  → McpServerConfig│  ← Aus EngineConfig (application.yml)  │
│  └────────┬─────────┘                                        │
│           │                                                  │
│           ▼                                                  │
│  ┌──────────────────┐    ┌──────────────────┐                │
│  │ ToolRegistry     │    │ /mcp/* REST      │                │
│  │ (MCP-Tools       │    │ (NEU: für UI)    │                │
│  │  bereits drin)   │    │                  │                │
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
│  │ QocEngine        │  ← Für Workflow-Ausführung              │
│  │ (kein MCP im     │  ← mcp_call-Nodes nutzen               │
│  │  Snapshot)       │     McpServerManager direkt              │
│  └──────────────────┘                                        │
└─────────────────────────────────────────────────────────────┘
```

## Status: Implementiert

Option B (Callback-basierte Tool-Registrierung) wurde implementiert:

- `ToolDependencies` erhält ein `toolRegistrar`-Feld (`Consumer<ToolRegistry>`, Default no-op)
- `QocEngine.create()` ruft `toolRegistrar.accept(registry)` auf, bevor der Snapshot genommen wird
- Quarkus `Engine` übergibt einen Callback, der MCP-Tools aus `McpServerManager` in QocEngine's Registry registriert
- → Agent-Nodes in Workflows können jetzt MCP-Tools zur Laufzeit aufrufen

### Was implementiert wurde

| Komponente | Datei | Status |
|---|---|---|
| `toolRegistrar` in `ToolDependencies` | `engine/.../wiring/ToolDependencies.java` | ✅ |
| Callback in `QocEngine.create()` | `engine/.../runtime/QocEngine.java` | ✅ |
| MCP-Tools in QocEngine-Callback | `quarkus/.../http/Engine.java` | ✅ |
| `mcp()` Getter in Engine | `quarkus/.../http/Engine.java` | ✅ |
| `McpClientResource` (REST) | `quarkus/.../http/McpClientResource.java` | ✅ |
| `mcp_call` Node-Typ | `DefaultNodeDispatcher.java` | ✅ |
| `NodeData` erweitert (mcpServer, mcpTool, mcpArgs) | `WorkflowDefinition.java` | ✅ |
| `GraphCompiler` VALID_NODE_TYPES | `GraphCompiler.java` | ✅ |
| `remove()` in McpServerManager | `McpServerManager.java` | ✅ |
| Frontend: ToolPicker, McpServers, api.ts | `workflow-ui/` | ✅ |
| PropertyPanel: mcp_call Sektion | `PropertyPanel.tsx` | ✅ |
| GraphEditor: mcp_call Node | `GraphEditor.tsx` | ✅ |

## Backend-Änderungen (Quarkus)

### 1. Neuer REST-Resource: `McpClientResource`

Neue Datei: `quarkus/src/main/java/de/augmentia/oc/engine/http/McpClientResource.java`

```java
@Path("/api/mcp-client")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class McpClientResource {

    @Inject
    Engine engine;

    /** Liste aller MCP-Server mit Status. */
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

    /** Tools eines bestimmten MCP-Servers. */
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

    /** Re-Discover Tools für einen Server. */
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

    /** Neuen MCP-Server hinzufügen. */
    @POST
    @Path("/servers")
    public Response addServer(Map<String, Object> config) {
        // Parse config → McpServerConfig
        // mcp.add(config)
        // Return status
    }

    /** MCP-Server entfernen. */
    @DELETE
    @Path("/servers/{name}")
    public Response removeServer(@PathParam("name") String name) {
        // mcp.remove(name) — falls McpServerManager remove unterstützt
        // Sonst: als "disabled" markieren
    }
}
```

### 2. `GET /api/tools` Endpoint

Neuer Resource oder in `WorkflowResource`:

```java
@GET
@Path("/tools")
public Response listTools() {
    List<Map<String, Object>> tools = new ArrayList<>();

    // Built-in Tools (aus Engine's ToolRegistry oder hardcoded)
    // MCP-Tools (aus McpServerManager)
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

    // Built-in tools (aus ToolRegistry snapshot oder hardcoded Liste)
    // ...

    return Response.ok(Map.of("tools", tools)).build();
}
```

**Hinweis:** Built-in Tools (file, web, shell, etc.) sind in `Engine`'s `ToolRegistry`. Um sie zu listen, könnte man `engine.toolSnapshot()` exponieren oder eine hardcoded Liste nutzen. Empfehlung: hardcoded Liste der bekannten built-in Tools, da `ToolSnapshot` nicht public ist.

### 3. MCP-Server-Config zur Laufzeit hinzufügen

`McpServerManager.add(McpServerConfig)` existiert bereits. Für dynamisches Hinzufügen über die UI:

```java
@POST
@Path("/servers")
public Response addServer(Map<String, Object> body) {
    String name = (String) body.get("name");
    String type = (String) body.get("type"); // "local" oder "remote"
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

**Einschränkung:** `McpServerManager` hat kein `remove()`-Method. Für Entfernen: Server als "disabled" markieren oder `servers`-Map manipulieren (Reflection, nicht empfohlen). Empfehlung: `remove()`-Methode zu `McpServerManager` hinzufügen (neue Methode, keine Änderung bestehender).

### 4. `mcp_call`-Node-Typ

- `NodeData` erweitern: `mcpServer`, `mcpTool`, `mcpArgs`
- `McpCallNodeHandler` implementieren (nutzt `engine.mcp().manager()`)
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

`NodeHandlerContext` erweitern: `McpServerManager mcpManager` (oder `Engine engine`).

## Frontend-Änderungen

### 1. API-Service erweitern (api.ts)

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

### 2. ToolPicker-Komponente

Multi-Select-Dropdown für Agent-Node-Tools und mcp_call-Node-Tools:

- Lädt Tools via `ToolApi.list()`
- Gruppiert nach `source` (builtin / mcp:<server>)
- Checkboxen oder Tag-basierte Auswahl
- Zeigt Tool-Beschreibung als Tooltip
- "Refresh"-Button

### 3. McpServerPanel (neue Seite oder Settings-Tab)

- Liste aller MCP-Server mit Status-Badge (grün/rot/grau)
- "Reload"-Button pro Server
- "Add Server"-Formular (Name, Type: local/remote, Command/URL)
- "Remove"-Button
- Expandierbar: zeigt Tools des Servers (via `getServerTools`)

### 4. PropertyPanel-Änderungen

- **Agent-Node:** `tools` Feld → ToolPicker-Komponente statt Freitext
  - Optional: Nur built-in Tools anzeigen (MCP-Tools nicht für Agent-Nodes)
- **mcp_call-Node:** Server-Dropdown + Tool-Dropdown (aus `/mcp-client/servers/:name/tools`)
  - `mcpArgs`: JSON-Editor mit Data-Piping-Unterstützung

### 5. Dynamic Refresh

- **Polling:** Alle 30 Sekunden `ToolApi.list()` aufrufen
- **Manual Refresh:** "Refresh Tools"-Button im PropertyPanel und im McpServerPanel
- **Nach Reload:** `McpClientApi.reloadServer()` → dann `ToolApi.list()` refetchen

## Dynamic Reinit — Ablauf

```
1. Nutzer klickt "Reload" bei MCP-Server "github"
   → POST /api/mcp-client/servers/github/reload
   → McpServerManager.reload("github")
   → discover() → neue Tools
   → Response: aktualisierte Tool-Liste

2. Frontend:
   → McpClientApi.reloadServer("github") resolves
   → McpServerPanel aktualisiert Tool-Liste
   → ToolPicker (falls offen) refetcht via ToolApi.list()
   → Agent-Node-Tools werden aktualisiert

3. Polling (30s):
   → ToolApi.list() → neue Tools erscheinen automatisch
```

## Implementierungsschritte

| # | Was | Datei(en) | Aufwand |
|---|-----|-----------|---------|
| 1 | `McpClientResource` erstellen (Quarkus JAX-RS) | Neue Datei | Mittel |
| 2 | `GET /api/tools` Endpoint | WorkflowResource oder neue Resource | Gering |
| 3 | `McpServerManager.remove()` Methode hinzufügen (engine) | McpServerManager.java | Gering |
| 4 | `POST /api/mcp-client/servers` (add) + `DELETE` (remove) | McpClientResource | Gering |
| 5 | `NodeHandlerContext` erweitern: `mcpManager` oder `engine` | NodeHandlerContext | Gering |
| 6 | `McpCallNodeHandler` implementieren | Neuer Handler | Gering |
| 7 | `NodeData` erweitern: `mcpServer`, `mcpTool`, `mcpArgs` | WorkflowDefinition.java | Gering |
| 8 | `GraphCompiler` VALID_NODE_TYPES: `mcp_call` | GraphCompiler.java | Gering |
| 9 | `ToolApi` + `McpClientApi` in api.ts | api.ts | Gering |
| 10 | ToolPicker-Komponente | ToolPicker.tsx (neu) | Mittel |
| 11 | McpServerPanel (Seite oder Settings-Tab) | McpServers.tsx (neu) | Mittel |
| 12 | PropertyPanel: Agent-Node → ToolPicker | PropertyPanel.tsx | Mittel |
| 13 | PropertyPanel: mcp_call-Node → Server/Tool Dropdowns | PropertyPanel.tsx | Mittel |
| 14 | GraphEditor: nodeTypes registrieren (mcp_call) | GraphEditor.tsx | Gering |
| 15 | Dynamic Refresh (Polling + Button) | ToolPicker, McpServerPanel | Gering |
| 16 | Tests (Backend + Playwright) | — | Mittel |

## Native Deployment

- `McpServerManager` nutzt `JdkHttpTransport` + `JdkProcessRunner` → native-kompatibel ✅
- `McpClientResource` ist JAX-RS → native-kompatibel ✅
- Quarkus Native Profile (`-Pnative`) existiert bereits
- **Einschränkung:** MCP-Server mit stdio-Transport benötigt externen Prozess zur Laufzeit

## Offene Fragen

1. **Built-in Tools listen:** Soll `/api/tools` nur MCP-Tools oder auch built-in Tools (file, web, shell) listen? Empfehlung: Beide, gruppiert nach source.
2. **ToolSnapshot für QocEngine:** Sollen MCP-Tools in QocEngine's Snapshot sein, damit Agent-Nodes sie nutzen können? Empfehlung: Ja, aber erfordert QocEngine-Änderung (neuer Parameter oder Pre-Discovery). Separates Ticket.
3. **Polling-Intervall:** 30s? 60s? Empfehlung: 30s, konfigurierbar via `application.properties`.
4. **MCP-Server-Config zur Laufzeit:** Sollen über die UI hinzugefügte Server persistiert werden (in `application.yml` oder DB)? Empfehlung: In-Memory für jetzt, Persistierung später.