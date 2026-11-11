# Additional Nodes — Dynamische Node-Registrierung

**Status:** Plan (2026-11-11) · Bezug: `ReAct-Umsetzung.md`, `ReAct-Workflow.md`

## Problem

`DefaultNodeDispatcher` hat einen **monolithischen switch-Statement**. Jeder neue Node-Typ erfordert:
1. Änderung in `DefaultNodeDispatcher` (neuer case)
2. Änderung in `GraphCompiler` (VALID_NODE_TYPES)
3. Änderung im Frontend (Node-Komponente + PropertyPanel + types)

Das ist nicht skalierbar und verstößt gegen das Open/Closed-Prinzip.

## Lösung: NodeHandler-Registry (analog zu ToolRegistry)

```
┌─────────────────────────────────────────────────────┐
│  NodeHandlerRegistry                                 │
│  ├── Map<String, NodeHandler> handlers               │
│  ├── register(type, handler)                        │
│  ├── dispatch(node, def, ctx, input) → NodeResult    │
│  └── listTypes() → Set<String>                      │
│                                                      │
│  Built-in Handlers:                                  │
│  ├── AgentNodeHandler                                │
│  ├── DecisionNodeHandler                             │
│  ├── StartNodeHandler                                │
│  ├── EndNodeHandler                                  │
│  ├── LoopNodeHandler                                 │
│  ├── ForkNodeHandler                                 │
│  ├── JoinNodeHandler                                 │
│  ├── HitlNodeHandler                                 │
│  ├── SubworkflowNodeHandler                          │
│  ├── LlmEvalNodeHandler                              │
│  ├── LlmSummarizeNodeHandler                         │
│  ├── EmailSendNodeHandler                            │
│  ├── EmailFetchNodeHandler                           │
│  └── EmailReadNodeHandler                            │
│                                                      │
│  Custom Handlers (via SPI/ServiceLoader):            │
│  └── MyCustomNodeHandler (von Nutzer bereitgestellt) │
└─────────────────────────────────────────────────────┘
```

## Implementierung

### 1. NodeHandler-Interface

```java
public interface NodeHandler {
    /** Der Node-Typ, den dieser Handler verarbeitet. */
    String type();

    /** Führt den Node aus und gibt das Ergebnis zurück. */
    NodeResult handle(WorkflowDefinition.Node node, WorkflowDefinition def,
                      WorkflowContext ctx, String input, NodeHandlerContext context);

    /** Validiert die Node-Konfiguration. */
    default List<String> validate(WorkflowDefinition.Node node) {
        return List.of();
    }
}
```

### 2. NodeHandlerContext (Shared Dependencies)

```java
public record NodeHandlerContext(
    QocEngine engine,
    WorkflowStore store,
    SubWorkflowExpander expander,
    EmailClient emailClient,
    TemplateResolver templateResolver
) {}
```

### 3. NodeHandlerRegistry

```java
public class NodeHandlerRegistry {
    private final Map<String, NodeHandler> handlers = new LinkedHashMap<>();
    private final NodeHandlerContext context;

    public NodeHandlerRegistry(NodeHandlerContext context) {
        this.context = context;
        // Register built-in handlers
        register(new AgentNodeHandler());
        register(new DecisionNodeHandler());
        register(new StartNodeHandler());
        register(new EndNodeHandler());
        register(new LoopNodeHandler());
        register(new ForkNodeHandler());
        register(new JoinNodeHandler());
        register(new HitlNodeHandler());
        register(new SubworkflowNodeHandler());
        register(new LlmEvalNodeHandler());
        register(new LlmSummarizeNodeHandler());
        register(new EmailSendNodeHandler());
        register(new EmailFetchNodeHandler());
        register(new EmailReadNodeHandler());

        // Load custom handlers via ServiceLoader
        ServiceLoader.load(NodeHandler.class).forEach(this::register);
    }

    public void register(NodeHandler handler) {
        handlers.put(handler.type(), handler);
    }

    public NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        NodeHandler handler = handlers.get(node.type());
        if (handler == null) {
            ctx.setNodeOutput(node.id(), "unknown type: " + node.type());
            return new NodeResult("skipped (unknown: " + node.type() + ")", null);
        }
        return handler.handle(node, def, ctx, input, context);
    }

    public Set<String> registeredTypes() {
        return Set.copyOf(handlers.keySet());
    }

    public boolean isRegistered(String type) {
        return handlers.containsKey(type);
    }
}
```

### 4. Beispiel: AgentNodeHandler

```java
public class AgentNodeHandler implements NodeHandler {
    @Override
    public String type() { return "agent"; }

    @Override
    public NodeResult handle(WorkflowDefinition.Node node, WorkflowDefinition def,
                            WorkflowContext ctx, String input, NodeHandlerContext context) {
        String agentRef = node.data().agentRef();
        WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);
        String prefixedName = prefixedName(agentDef, agentRef);
        QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName);

        String nodeInput = input;
        List<String> incoming = TopologicalSort.incomingSources(def, node.id());
        if (!incoming.isEmpty() && incoming.size() == 1) {
            Object predOutput = ctx.getNodeOutput(incoming.get(0));
            if (predOutput != null && !predOutput.toString().isBlank()) {
                nodeInput = predOutput.toString();
            }
        }
        nodeInput = TemplateResolver.resolve(nodeInput, ctx);

        EngineResult result = context.engine().runPrompt(prefixedName, nodeInput, config);
        Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
        ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
        return new NodeResult(result.text(), structured);
    }

    @Override
    public List<String> validate(WorkflowDefinition.Node node) {
        List<String> errors = new ArrayList<>();
        if (node.data() == null || node.data().agentRef() == null || node.data().agentRef().isBlank()) {
            errors.add("Agent node " + node.id() + " requires agentRef");
        }
        return errors;
    }
}
```

### 5. Beispiel: Custom Node Handler (vom Nutzer)

```java
// In einem separaten Modul oder JAR
public class SlackNotifyNodeHandler implements NodeHandler {
    @Override
    public String type() { return "slack_notify"; }

    @Override
    public NodeResult handle(WorkflowDefinition.Node node, WorkflowDefinition def,
                            WorkflowContext ctx, String input, NodeHandlerContext context) {
        String channel = TemplateResolver.resolve(node.data().title(), ctx);
        String message = TemplateResolver.resolve(node.data().defaultTarget(), ctx);
        // Slack API call...
        ctx.setNodeOutput(node.id(), Map.of("status", "sent", "channel", channel));
        return new NodeResult("Slack message sent to " + channel, Map.of("status", "sent"));
    }
}
```

Registrierung via `META-INF/services/de.augmentia.qoc.workflowrunner.runner.NodeHandler`:
```
com.example.SlackNotifyNodeHandler
```

### Vorteile gegenüber switch-Statement

| Aspekt | switch-Statement | NodeHandler-Registry |
|---|---|---|
| **Open/Closed** | ❌ Muss geändert werden | ✅ Offen für Erweiterung |
| **Testbarkeit** | ❌ Monolithisch | ✅ Jeder Handler einzeln testbar |
| **Custom Nodes** | ❌ Nicht möglich | ✅ Via ServiceLoader |
| **Validierung** | ❌ Zentral in GraphCompiler | ✅ Pro Handler |
| **Wartbarkeit** | ❌ Wächst mit jedem Node-Typ | ✅ Jeder Handler ist klein |
| **Frontend-Kopplung** | ❌ Node-Typen hardcoded | ✅ Frontend kann Typen aus Registry laden |

### Frontend-Integration

Der Frontend kann die verfügbaren Node-Typen vom Backend laden:

```typescript
// GET /node-types → ["agent", "decision", "start", "end", ..., "slack_notify"]
const { data } = await api.get('/node-types');
```

PropertyPanel rendert dann dynamisch die Config-Felder basierend auf dem Node-Typ (via Schema/JSON-Schema).

### Implementierungsschritte

| Schritt | Was | Aufwand |
|---|---|---|
| 1 | `NodeHandler`-Interface + `NodeHandlerContext`-Record | Gering |
| 2 | `NodeHandlerRegistry` mit ServiceLoader | Gering |
| 3 | Built-in Handler extrahieren (Agent, Decision, Start, End, ...) | Mittel |
| 4 | `DefaultNodeDispatcher` → nutzt Registry statt switch | Gering |
| 5 | `GraphCompiler` → nutzt Registry für VALID_NODE_TYPES | Gering |
| 6 | `WorkflowRunnerMain` → Registry erstellen, an Dispatcher übergeben | Gering |
| 7 | Frontend: `/node-types` Endpoint + dynamische Node-Liste | Mittel |
| 8 | Tests | Mittel |

### Empfehlung

Die NodeHandler-Registry ist der richtige Ansatz. Sie:
- Entfernt den monolithischen switch-Statement
- Ermöglicht Custom Nodes ohne Code-Änderung
- Ist konsistent mit dem bestehenden `ToolRegistry`-Pattern in engine
- Macht jeden Node-Typ einzeln testbar

---

## Dynamische Tools via Webhooks — ohne Kern-Rebuild

### Kurze Antwort: Ja, mit Einschränkungen

Es gibt drei Stufen der Dynamik, die ohne Rebuild möglich sind:

### Stufe 1: Generischer `webhook`-Node (kein Rebuild nötig)

Ein **eingebauter Node-Typ** `webhook` (oder `http_request`), der beliebige URLs aufruft:

```json
{
  "id": "notify_slack",
  "type": "webhook",
  "data": {
    "title": "Slack Benachrichtigung",
    "url": "https://hooks.slack.com/services/xxx",
    "method": "POST",
    "headers": { "Content-Type": "application/json" },
    "body": "{\"text\": \"${node_1.output.summary}\"}",
    "timeout": 10
  }
}
```

**Vorteil:** Sofort nutzbar, keine Code-Änderung. Der NodeHandler ist im Kern eingebaut.
**Nachteil:** Nur HTTP-Calls, keine komplexe Logik.

**Implementierung:** Ein `WebhookNodeHandler` im Kern, der `java.net.http.HttpClient` nutzt (bereits in engine vorhanden via `HttpTransport`).

### Stufe 2: Dynamische Registrierung zur Laufzeit (ohne Rebuild)

Die `NodeHandlerRegistry` hat bereits `register(type, handler)`. Man kann zur Laufzeit neue Handler hinzufügen:

```java
// Runtime-Registrierung (z. B. beim Start oder via API)
registry.register(new SlackNotifyNodeHandler());
registry.register(new JiraCreateTicketNodeHandler());
```

**API-Endpoint (neu):**
```
POST /node-types
{
  "type": "slack_notify",
  "handler": "com.example.SlackNotifyNodeHandler",
  "jar": "/path/to/plugin.jar"  // optional
}
```

**Wie es funktioniert:**
1. JAR wird in einen `plugins/`-Ordner gelegt
2. `URLClassLoader` lädt die Klasse
3. `registry.register()` fügt den Handler hinzu
4. Frontend lädt verfügbare Typen via `GET /node-types`

**Vorteil:** Beliebige Java-Logik nachrüstbar.
**Nachteil:** JAR-Deployment, kein reines Webhook.

### Stufe 3: Webhook-basierte Tool-Registrierung (rein konfigurativ)

Externe Systeme können neue "Tools" (nicht Node-Typen) via Webhook registrieren:

```
POST /tools/register
{
  "name": "send_slack_message",
  "description": "Sendet eine Slack-Nachricht",
  "webhookUrl": "https://my-service.com/slack",
  "inputSchema": { "channel": "string", "message": "string" },
  "outputSchema": { "ok": "boolean", "ts": "string" }
}
```

Der Kern erstellt intern einen `WebhookToolHandler`, der:
1. Den Input als JSON an die Webhook-URL sendet
2. Das Ergebnis als `NodeResult` zurückgibt
3. Data-Piping (`${node.output.field}`) unterstützt

**Vorteil:** Kein Code, keine JARs — rein über API konfigurierbar.
**Nachteil:** Nur request/response, keine komplexe Logik.

### Vergleich

| Stufe | Was | Rebuild? | Komplexität | Flexibilität |
|---|---|---|---|---|
| **1. webhook-Node** | Eingebauter HTTP-Node | Nein | Gering | Mittel (nur HTTP) |
| **2. JAR-Plugin** | ServiceLoader + URLClassLoader | Nein | Mittel | Hoch (beliebige Java-Logik) |
| **3. Webhook-Tool** | API-registrierte HTTP-Tools | Nein | Gering | Mittel (request/response) |

### Empfohlener Ansatz: Stufe 1 + 3 kombiniert

1. **Eingebauter `webhook`-Node** (Stufe 1) für einfache HTTP-Calls
2. **Dynamische Tool-Registrierung** (Stufe 3) für wiederverwendbare HTTP-Tools
3. **ServiceLoader** (Stufe 2) als Fallback für komplexe Java-Logik

### Frontend-Integration

```
PropertyPanel → Node-Typ: [webhook ▼]
  ├── URL: [https://hooks.slack.com/...]
  ├── Method: [POST ▼]
  ├── Headers: [JSON-Editor]
  ├── Body Template: [textarea] (mit ${...} Data-Piping)
  └── Timeout: [10s]
```

Der Nutzer kann beliebige Webhooks konfigurieren, ohne den Kern zu ändern.

### Was bereits vorhanden ist

- `NodeHandlerRegistry.register()` — dynamische Registrierung zur Laufzeit ✅
- `ServiceLoader` — automatische Discovery von Custom Handlers ✅
- `HttpTransport` / `JdkHttpTransport` — HTTP-Calls in engine ✅
- `TemplateResolver` — Data-Piping (`${node.output.field}`) ✅
- `/node-types` Endpoint (geplant) — Frontend kann verfügbare Typen laden

### Fazit

**Ja, beliebige Tools können nachträglich via API oder Webhook hinzugefügt werden**, ohne den Kern neu zu bauen. Der Kern muss nur **einmal** den `webhook`-Node und die dynamische Registrierung implementieren. Danach können beliebige Tools via API oder Webhook hinzugefügt werden.

---

## Native Deployment (GraalVM)

### Kurzantwort

| Mechanismus | JVM-Modus | Native (GraalVM) |
|---|---|---|
| **Stufe 1: webhook-Node** (eingebauter HTTP-Handler) | ✅ | ✅ |
| **Stufe 2: JAR-Plugins** (ServiceLoader + URLClassLoader) | ✅ | ❌ |
| **Stufe 3: Webhook-Tool-Registrierung** (API, konfigurativ) | ✅ | ✅ (wenn nur konfiguriert, keine neuen Klassen) |

### Warum Stufe 2 in Native nicht funktioniert

GraalVM Native Image ist eine **Ahead-of-Time (AOT)**-Kompilierung:
- Alle Klassen müssen zur **Build-Zeit** bekannt sein
- Es gibt **keinen Bytecode-Interpreter** zur Laufzeit
- `URLClassLoader`, `Class.forName("com.example.X")` für unbekannte Klassen schlagen fehl
- `ServiceLoader` funktioniert nur für Klassen, die im Image enthalten sind

→ **Beliebige JARs zur Laufzeit laden ist in Native Image grundsätzlich nicht möglich.**

### Was in Native funktioniert

#### ✅ Stufe 1: webhook-Node
- Ist im Kern eingebaut (`WebhookNodeHandler` ist Teil des Images)
- Nutzt `java.net.http.HttpClient` (in GraalVM enthalten)
- Keine neuen Klassen zur Laufzeit → funktioniert

#### ✅ Stufe 3: Webhook-Tool-Registrierung (konfigurativ)
- Wenn `POST /tools/register` nur **Konfiguration** speichert (URL, Schema, Headers)
- und intern den **eingebauten** `WebhookNodeHandler` mit diesen Parametern nutzt
- → funktioniert in Native

#### ❌ Stufe 2: JAR-Plugins
- `URLClassLoader.loadClass()` → `ClassNotFoundException` in Native
- ServiceLoader findet nur Klassen im Image
- → **nicht möglich** in Native

### Empfehlung für Native-kompatibles Design

```
┌─────────────────────────────────────────────────────┐
│  Native-kompatible Architektur                       │
│                                                      │
│  ┌─────────────────┐    ┌──────────────────────┐    │
│  │ Eingebaute       │    │ Dynamische           │    │
│  │ Handler (im Image)│    │ Konfiguration        │    │
│  │                  │    │ (zur Laufzeit)       │    │
│  │ • webhook        │◄───│ • URL, Method,       │    │
│  │ • agent          │    │   Headers, Body      │    │
│  │ • decision       │    │ • Schema, Timeout    │    │
│  │ • email_send     │    │                      │    │
│  │ • email_fetch    │    │ → Keine neuen Klassen│    │
│  │ • email_read     │    │ → Native-kompatibel  │    │
│  └─────────────────┘    └──────────────────────┘    │
│                                                      │
│  JAR-Plugins (Stufe 2): NUR im JVM-Modus            │
│  → Für Native: Vorab als eingebaute Handler         │
│    kompilieren (Build-Zeit)                         │
└─────────────────────────────────────────────────────┘
```

### Konkrete Konsequenzen

1. **Für Native Builds:** Nur Stufe 1 + 3 nutzen. Alle Node-Typen müssen zur Build-Zeit im Image sein.
2. **Für JVM-Modus:** Alle drei Stufen verfügbar, inkl. dynamische JAR-Plugins.
3. **Hybrid-Ansatz:** Wenn Native benötigt wird, können Custom Nodes als **GraalVM Reachability Metadata** eingebunden werden (Build-Zeit), aber nicht zur Laufzeit hinzugefügt.
4. **Quarkus Native Profile** (`-Pnative`) existiert bereits im `quarkus/pom.xml` — das zeigt, dass Native ein explizites Ziel ist.

---

## MCP-Integration in Workflows

### Kurze Antwort: Ja, MCP-Client-Registry existiert bereits

Es gibt **keine** Notwendigkeit, eine neue Registry zu bauen. Die Infrastruktur ist vorhanden:

| Komponente | Ort | Rolle |
|---|---|---|
| `McpServerManager` | `engine/.../mcp/` | **MCP-Client-Registry** — verwaltet Server, entdeckt Tools, liefert `McpClient` pro Server |
| `McpRegistry` | `quarkus/.../http/` | Wrapper um `McpServerManager` + A2A-Integration |
| `McpRegistrar` | `engine/.../mcp/` | Registriert MCP-Tools in `ToolRegistry` |
| `McpClient` | `engine/.../mcp/` | Client-Interface (`listTools`, `callTool`, `listResources`, ...) |
| `McpServerConfig` | `engine/.../mcp/` | Konfiguration (local stdio + remote HTTP/SSE) |

### Wie MCP in Workflows genutzt werden kann

#### Option A: Über Agent-Nodes (bereits möglich)

MCP-Tools sind im `ToolRegistry` registriert. Agent-Nodes, die QocEngine nutzen, haben Zugriff auf diese Tools:

```json
{
  "id": "github_issue",
  "type": "agent",
  "data": {
    "agentRef": "github-agent:1",
    "tools": ["github/create_issue", "github/list_issues"]
  }
}
```

Der Agent ruft MCP-Tools über QocEngine's ToolRuntime auf — **kein neuer Node-Typ nötig**.

#### Option B: Dedizierter `mcp_call`-Node (direkter Aufruf)

Für Workflows, die MCP-Tools **ohne LLM** aufrufen wollen (deterministisch):

```json
{
  "id": "create_issue",
  "type": "mcp_call",
  "data": {
    "server": "github",
    "tool": "create_issue",
    "args": {
      "title": "${node_1.output.title}",
      "body": "${node_1.output.description}"
    }
  }
}
```

**Implementierung:** Ein `McpCallNodeHandler`, der `McpServerManager.client(server).callTool(tool, args)` aufruft.

```java
public class McpCallNodeHandler implements NodeHandler {
    private final McpServerManager mcpManager;

    @Override
    public String type() { return "mcp_call"; }

    @Override
    public NodeResult handle(WorkflowDefinition.Node node, WorkflowDefinition def,
                            WorkflowContext ctx, String input, NodeHandlerContext context) {
        String server = node.data().mcpServer();
        String tool = node.data().mcpTool();
        Map<String, Object> args = resolveArgs(node.data().mcpArgs(), ctx);

        McpClient client = mcpManager.client(server);
        if (client == null) {
            return new NodeResult("MCP server not found: " + server, null);
        }
        Object result = client.callTool(tool, args);
        ctx.setNodeOutput(node.id(), result);
        return new NodeResult(result.toString(), result);
    }
}
```

#### Option C: MCP über HTTP-Webhook (Streamable HTTP)

MCP unterstützt **Streamable HTTP** als Transport. Ein MCP-Server kann also über HTTP erreicht werden — aber MCP ist **kein einfacher Webhook**:

| Aspekt | Webhook (Stufe 1) | MCP (Streamable HTTP) |
|---|---|---|
| **Protokoll** | HTTP POST, beliebiges JSON | JSON-RPC 2.0 über HTTP |
| **Discovery** | Keine (URL bekannt) | `tools/list`, `resources/list` |
| **Auth** | Beliebig (Headers) | OAuth 2.0, API Keys |
| **Streaming** | Nein | SSE für Notifications |
| **Tool-Call** | HTTP POST mit Body | `tools/call` JSON-RPC |

→ Ein MCP-Server kann **nicht** einfach als Webhook-Node aufgerufen werden. Die JSON-RPC-Protokollschicht fehlt.

**Lösung:** `McpCallNodeHandler` (Option B) nutzt den bestehenden `McpClient`, der das Protokoll abstrahiert.

### Architektur: MCP in Workflows

```
┌─────────────────────────────────────────────────────┐
│  Workflow Engine                                     │
│                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────┐  │
│  │ Agent Node   │  │ mcp_call Node│  │ webhook  │  │
│  │ (LLM + Tools)│  │ (direkt,     │  │ Node     │  │
│  │              │  │  ohne LLM)   │  │ (HTTP)   │  │
│  └──────┬───────┘  └──────┬───────┘  └────┬─────┘  │
│         │                  │               │        │
│         ▼                  ▼               ▼        │
│  ┌──────────────────────────────────────────────┐   │
│  │ ToolRegistry / McpServerManager (Registry)    │   │
│  │  ├── GitHub MCP Server (stdio)               │   │
│  │  ├── Slack MCP Server (HTTP/SSE)             │   │
│  │  ├── Filesystem MCP Server (stdio)           │   │
│  │  └── Custom MCP Server (Streamable HTTP)     │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### Native Deployment

`McpServerManager` nutzt:
- `HttpTransport` (JdkHttpTransport) → native-kompatibel ✅
- `ProcessRunner` (JdkProcessRunner) → native-kompatibel ✅ (für stdio-Server)
- `CredentialStore` (optional) → native-kompatibel ✅

→ MCP-Integration funktioniert in Native Builds.

### Implementierungsschritte

| Schritt | Was | Aufwand |
|---|---|---|
| 1 | `McpCallNodeHandler` implementieren | Gering |
| 2 | `NodeData` erweitern: `mcpServer`, `mcpTool`, `mcpArgs` | Gering |
| 3 | `GraphCompiler` VALID_NODE_TYPES: `mcp_call` | Gering |
| 4 | `McpServerManager` an `NodeHandlerContext` übergeben | Gering |
| 5 | Frontend: `McpCallNode.tsx` + PropertyPanel-Sektion | Mittel |
| 6 | Tests | Mittel |

### Empfehlung

- **Agent-Nodes** (Option A) für LLM-gesteuerte MCP-Aufrufe — bereits möglich
- **`mcp_call`-Node** (Option B) für deterministische MCP-Aufrufe ohne LLM
- **Keine neue Registry** — `McpServerManager` existiert bereits als MCP-Client-Registry
- **MCP ≠ Webhook** — MCP ist ein Protokoll, kein einfacher HTTP-Call; daher separater Node-Typ nötig