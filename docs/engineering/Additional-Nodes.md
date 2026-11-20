# Additional Nodes — Dynamic Node Registration

**Status:** Plan (2026-11-11) · Reference: `ReAct-Implementation.md`, `ReAct-Workflow.md`

## Problem

`DefaultNodeDispatcher` has a **monolithic switch statement**. Every new node type requires:
1. A change in `DefaultNodeDispatcher` (new case)
2. A change in `GraphCompiler` (VALID_NODE_TYPES)
3. A change in the frontend (node component + PropertyPanel + types)

This is not scalable and violates the Open/Closed principle.

## Solution: NodeHandler Registry (analogous to ToolRegistry)

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
│  └── MyCustomNodeHandler (provided by the user)     │
└─────────────────────────────────────────────────────┘
```

## Implementation

### 1. NodeHandler Interface

```java
public interface NodeHandler {
    /** The node type handled by this handler. */
    String type();

    /** Executes the node and returns the result. */
    NodeResult handle(WorkflowDefinition.Node node, WorkflowDefinition def,
                      WorkflowContext ctx, String input, NodeHandlerContext context);

    /** Validates the node configuration. */
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

### 4. Example: AgentNodeHandler

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

### 5. Example: Custom Node Handler (provided by the user)

```java
// In a separate module or JAR
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

Registration via `META-INF/services/de.augmentia.qoc.workflowrunner.runner.NodeHandler`:
```
com.example.SlackNotifyNodeHandler
```

### Advantages over the switch statement

| Aspect | switch statement | NodeHandler Registry |
|---|---|---|
| **Open/Closed** | ❌ Must be modified | ✅ Open for extension |
| **Testability** | ❌ Monolithic | ✅ Each handler individually testable |
| **Custom Nodes** | ❌ Not possible | ✅ Via ServiceLoader |
| **Validation** | ❌ Centralized in GraphCompiler | ✅ Per handler |
| **Maintainability** | ❌ Grows with every node type | ✅ Each handler is small |
| **Frontend coupling** | ❌ Node types hardcoded | ✅ Frontend can load types from the registry |

### Frontend integration

The frontend can load the available node types from the backend:

```typescript
// GET /node-types → ["agent", "decision", "start", "end", ..., "slack_notify"]
const { data } = await api.get('/node-types');
```

The PropertyPanel then renders the config fields dynamically based on the node type (via schema/JSON-Schema).

### Implementation steps

| Step | What | Effort |
|---|---|---|
| 1 | `NodeHandler` interface + `NodeHandlerContext` record | Low |
| 2 | `NodeHandlerRegistry` with ServiceLoader | Low |
| 3 | Extract built-in handlers (Agent, Decision, Start, End, ...) | Medium |
| 4 | `DefaultNodeDispatcher` → uses registry instead of switch | Low |
| 5 | `GraphCompiler` → uses registry for VALID_NODE_TYPES | Low |
| 6 | `WorkflowRunnerMain` → create registry, pass to dispatcher | Low |
| 7 | Frontend: `/node-types` endpoint + dynamic node list | Medium |
| 8 | Tests | Medium |

### Recommendation

The NodeHandler registry is the right approach. It:
- Removes the monolithic switch statement
- Enables custom nodes without code changes
- Is consistent with the existing `ToolRegistry` pattern in engine
- Makes every node type individually testable

---

## Dynamic Tools via Webhooks — without a core rebuild

### Short answer: Yes, with limitations

There are three levels of dynamism achievable without a rebuild:

### Level 1: Generic `webhook` node (no rebuild needed)

A **built-in node type** `webhook` (or `http_request`) that calls arbitrary URLs:

```json
{
  "id": "notify_slack",
  "type": "webhook",
  "data": {
    "title": "Slack notification",
    "url": "https://hooks.slack.com/services/xxx",
    "method": "POST",
    "headers": { "Content-Type": "application/json" },
    "body": "{\"text\": \"${node_1.output.summary}\"}",
    "timeout": 10
  }
}
```

**Advantage:** Immediately usable, no code changes. The handler is built into the core.
**Disadvantage:** HTTP calls only, no complex logic.

**Implementation:** A `WebhookNodeHandler` in the core that uses `java.net.http.HttpClient` (already present in engine via `HttpTransport`).

### Level 2: Dynamic registration at runtime (without rebuild)

The `NodeHandlerRegistry` already has `register(type, handler)`. New handlers can be added at runtime:

```java
// Runtime registration (e.g. at startup or via API)
registry.register(new SlackNotifyNodeHandler());
registry.register(new JiraCreateTicketNodeHandler());
```

**API endpoint (new):**
```
POST /node-types
{
  "type": "slack_notify",
  "handler": "com.example.SlackNotifyNodeHandler",
  "jar": "/path/to/plugin.jar"  // optional
}
```

**How it works:**
1. The JAR is placed in a `plugins/` folder
2. `URLClassLoader` loads the class
3. `registry.register()` adds the handler
4. The frontend loads available types via `GET /node-types`

**Advantage:** Arbitrary Java logic can be retrofitted.
**Disadvantage:** JAR deployment, not a pure webhook.

### Level 3: Webhook-based tool registration (purely configurative)

External systems can register new "tools" (not node types) via webhook:

```
POST /tools/register
{
  "name": "send_slack_message",
  "description": "Sends a Slack message",
  "webhookUrl": "https://my-service.com/slack",
  "inputSchema": { "channel": "string", "message": "string" },
  "outputSchema": { "ok": "boolean", "ts": "string" }
}
```

The core internally creates a `WebhookToolHandler` that:
1. Sends the input as JSON to the webhook URL
2. Returns the result as a `NodeResult`
3. Supports data piping (`${node.output.field}`)

**Advantage:** No code, no JARs — purely configurable via API.
**Disadvantage:** Only request/response, no complex logic.

### Comparison

| Level | What | Rebuild? | Complexity | Flexibility |
|---|---|---|---|---|
| **1. webhook node** | Built-in HTTP node | No | Low | Medium (HTTP only) |
| **2. JAR plugin** | ServiceLoader + URLClassLoader | No | Medium | High (arbitrary Java logic) |
| **3. Webhook tool** | API-registered HTTP tools | No | Low | Medium (request/response) |

### Recommended approach: Level 1 + 3 combined

1. **Built-in `webhook` node** (Level 1) for simple HTTP calls
2. **Dynamic tool registration** (Level 3) for reusable HTTP tools
3. **ServiceLoader** (Level 2) as a fallback for complex Java logic

### Frontend integration

```
PropertyPanel → Node type: [webhook ▼]
  ├── URL: [https://hooks.slack.com/...]
  ├── Method: [POST ▼]
  ├── Headers: [JSON editor]
  ├── Body Template: [textarea] (with ${...} data piping)
  └── Timeout: [10s]
```

The user can configure arbitrary webhooks without changing the core.

### What already exists

- `NodeHandlerRegistry.register()` — dynamic registration at runtime ✅
- `ServiceLoader` — automatic discovery of custom handlers ✅
- `HttpTransport` / `JdkHttpTransport` — HTTP calls in engine ✅
- `TemplateResolver` — data piping (`${node.output.field}`) ✅
- `/node-types` endpoint (planned) — frontend can load available types

### Conclusion

**Yes, arbitrary tools can be added later via API or webhook** without rebuilding the core. The core only needs to implement the `webhook` node and dynamic registration **once**. After that, arbitrary tools can be added via API or webhook.

---

## Native Deployment (GraalVM)

### Short answer

| Mechanism | JVM mode | Native (GraalVM) |
|---|---|---|
| **Level 1: webhook node** (built-in HTTP handler) | ✅ | ✅ |
| **Level 2: JAR plugins** (ServiceLoader + URLClassLoader) | ✅ | ❌ |
| **Level 3: Webhook tool registration** (API, configurative) | ✅ | ✅ (when only configured, no new classes) |

### Why Level 2 does not work in Native

GraalVM Native Image is an **Ahead-of-Time (AOT)** compilation:
- All classes must be known at **build time**
- There is **no bytecode interpreter** at runtime
- `URLClassLoader`, `Class.forName("com.example.X")` for unknown classes fail
- `ServiceLoader` only works for classes contained in the image

→ **Loading arbitrary JARs at runtime is fundamentally impossible in Native Image.**

### What works in Native

#### ✅ Level 1: webhook node
- Built into the core (`WebhookNodeHandler` is part of the image)
- Uses `java.net.http.HttpClient` (included in GraalVM)
- No new classes at runtime → works

#### ✅ Level 3: Webhook tool registration (configurative)
- When `POST /tools/register` only stores **configuration** (URL, schema, headers)
- and internally uses the **built-in** `WebhookNodeHandler` with those parameters
- → works in Native

#### ❌ Level 2: JAR plugins
- `URLClassLoader.loadClass()` → `ClassNotFoundException` in Native
- ServiceLoader only finds classes in the image
- → **not possible** in Native

### Recommendation for a Native-compatible design

```
┌─────────────────────────────────────────────────────┐
│  Native-compatible architecture                      │
│                                                      │
│  ┌─────────────────┐    ┌──────────────────────┐    │
│  │ Built-in         │    │ Dynamic              │    │
│  │ handlers         │    │ configuration        │    │
│  │ (in image)       │    │ (at runtime)         │    │
│  │                  │    │                      │    │
│  │ • webhook        │◄───│ • URL, Method,       │    │
│  │ • agent          │    │   Headers, Body      │    │
│  │ • decision       │    │ • Schema, Timeout    │    │
│  │ • email_send     │    │                      │    │
│  │ • email_fetch    │    │ → No new classes     │    │
│  │ • email_read     │    │ → Native-compatible  │    │
│  └─────────────────┘    └──────────────────────┘    │
│                                                      │
│  JAR plugins (Level 2): JVM mode only               │
│  → For Native: compile as built-in handlers          │
│    ahead of time (build time)                        │
└─────────────────────────────────────────────────────┘
```

### Concrete consequences

1. **For Native builds:** Use only Level 1 + 3. All node types must be in the image at build time.
2. **For JVM mode:** All three levels available, including dynamic JAR plugins.
3. **Hybrid approach:** If Native is needed, custom nodes can be embedded as **GraalVM Reachability Metadata** (build time), but not added at runtime.
4. **Quarkus Native Profile** (`-Pnative`) already exists in `quarkus/pom.xml` — showing that Native is an explicit goal.

---

## MCP Integration in Workflows

### Short answer: Yes, the MCP client registry already exists

There is **no** need to build a new registry. The infrastructure is present:

| Component | Location | Role |
|---|---|---|
| `McpServerManager` | `engine/.../mcp/` | **MCP client registry** — manages servers, discovers tools, provides `McpClient` per server |
| `McpRegistry` | `quarkus/.../http/` | Wrapper around `McpServerManager` + A2A integration |
| `McpRegistrar` | `engine/.../mcp/` | Registers MCP tools in `ToolRegistry` |
| `McpClient` | `engine/.../mcp/` | Client interface (`listTools`, `callTool`, `listResources`, ...) |
| `McpServerConfig` | `engine/.../mcp/` | Configuration (local stdio + remote HTTP/SSE) |

### How MCP can be used in workflows

#### Option A: Via agent nodes (already possible)

MCP tools are registered in `ToolRegistry`. Agent nodes using QocEngine have access to these tools:

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

The agent calls MCP tools via QocEngine's ToolRuntime — **no new node type needed**.

#### Option B: Dedicated `mcp_call` node (direct invocation)

For workflows that want to call MCP tools **without an LLM** (deterministic):

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

**Implementation:** An `McpCallNodeHandler` that calls `McpServerManager.client(server).callTool(tool, args)`.

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

#### Option C: MCP via HTTP webhook (Streamable HTTP)

MCP supports **Streamable HTTP** as a transport. An MCP server can therefore be reached over HTTP — but MCP is **not a simple webhook**:

| Aspect | Webhook (Level 1) | MCP (Streamable HTTP) |
|---|---|---|
| **Protocol** | HTTP POST, arbitrary JSON | JSON-RPC 2.0 over HTTP |
| **Discovery** | None (URL known) | `tools/list`, `resources/list` |
| **Auth** | Arbitrary (headers) | OAuth 2.0, API keys |
| **Streaming** | No | SSE for notifications |
| **Tool call** | HTTP POST with body | `tools/call` JSON-RPC |

→ An MCP server **cannot** simply be called as a webhook node. The JSON-RPC protocol layer is missing.

**Solution:** `McpCallNodeHandler` (Option B) uses the existing `McpClient`, which abstracts the protocol.

### Architecture: MCP in workflows

```
┌─────────────────────────────────────────────────────┐
│  Workflow Engine                                     │
│                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────┐  │
│  │ Agent Node   │  │ mcp_call Node│  │ webhook  │  │
│  │ (LLM + Tools)│  │ (direct,     │  │ Node     │  │
│  │              │  │  without LLM)│  │ (HTTP)   │  │
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

### Native deployment

`McpServerManager` uses:
- `HttpTransport` (JdkHttpTransport) → native-compatible ✅
- `ProcessRunner` (JdkProcessRunner) → native-compatible ✅ (for stdio servers)
- `CredentialStore` (optional) → native-compatible ✅

→ MCP integration works in Native builds.

### Implementation steps

| Step | What | Effort |
|---|---|---|
| 1 | Implement `McpCallNodeHandler` | Low |
| 2 | Extend `NodeData`: `mcpServer`, `mcpTool`, `mcpArgs` | Low |
| 3 | `GraphCompiler` VALID_NODE_TYPES: `mcp_call` | Low |
| 4 | Pass `McpServerManager` to `NodeHandlerContext` | Low |
| 5 | Frontend: `McpCallNode.tsx` + PropertyPanel section | Medium |
| 6 | Tests | Medium |

### Recommendation

- **Agent nodes** (Option A) for LLM-driven MCP calls — already possible
- **`mcp_call` node** (Option B) for deterministic MCP calls without an LLM
- **No new registry** — `McpServerManager` already exists as the MCP client registry
- **MCP ≠ webhook** — MCP is a protocol, not a simple HTTP call; therefore a separate node type is needed