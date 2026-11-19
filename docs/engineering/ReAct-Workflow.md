# ReAct Workflow — Deterministic React Flow Agent Engine

**Status:** Draft (2026-11-11) · Source: `qoc/docs/ReAct.txt` (chat conversation on architecture)
**Scope:** Hybrid engine that combines the ReAct agent loop mechanics (Thought → Action → Observation) with a React Flow graph — 100% deterministic, with no LLM guessing for path control.

---

## 1. Goal

A workflow system that:

- uses the **visual designer** (React Flow, Nodes + Edges) as the primary definition,
- keeps the **ReAct loop** structurally (Thought → Action → Observation),
- but replaces the **LLM call (Thought)** with a deterministic `getNextStep` method,
- passes data between nodes through placeholders (`${node_1.output.field}`),
- uses Java `record`s as type-safe contracts between agent and workflow engine,
- optionally embeds LLM nodes for unclear states/summaries (hybrid).

---

## 2. Core Idea: ReAct → Deterministic Loop

### 2.1 Classic ReAct Loop (AI-driven)

```
┌─────────────────────────────────────────────────────────────┐
│  ReAct Agent (LLM-based)                                    │
│                                                             │
│   Thought   → LLM plans the next step                       │
│   Action    → LLM selects tool + parameters                 │
│   Observation → Tool result back into context               │
│   ... until done                                            │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Deterministic ReAct Loop (code-driven)

```
┌─────────────────────────────────────────────────────────────┐
│  Deterministic Engine                                       │
│                                                             │
│   getNextStep(context, graph) → "execute <nodeId>"          │
│   executeNodeAction(nodeId)   → execute tool                │
│   Observation                 → stored in AgentContext       │
│   ... until no outgoing edges remain ("DONE")               │
└─────────────────────────────────────────────────────────────┘
```

**Difference:** Only the "brain" (Thought) is swapped out. The structure (loop, tool execution, state update, observation) stays identical. If you later want to steer an LLM, only `getNextStep` needs to be adjusted.

**Advantages:**
- 100% reproducible, no hallucination risk, no infinite loops from wrong LLM interpretation
- No LLM cost/latency for path control
- Full logging/debugging of every step
- Type-safe contracts via Java records

**Disadvantage:** Rigid — cannot dynamically react to completely unforeseen situations for which no path was programmed.

---

## 3. Architecture

### 3.1 Data flow

```
┌────────────────────┐
│ React Flow JSON    │  { nodes, edges, agents }
│ (Frontend export)  │
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ getNextStep()      │  Reads graph + AgentContext
│                    │  → "execute <nodeId>"
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ Tool Dispatcher    │  executes node
│                    │  → structured output
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ AgentContext       │  stores nodeOutputs
│ (State + History)  │  → for next iteration
└────────────────────┘
```

### 3.2 Components

| Component | Responsibility |
|---|---|
| `FlowGraph` | React Flow data model (Nodes, Edges, Conditions) |
| `AgentContext` | Central state: chat history, `nodeOutputs`, `workflowState`, `currentNodeId` |
| `getNextStep` | Deterministic navigation in the graph |
| `ToolDispatcher` | Mapping `node.type` → tool execution |
| `resolveTemplate` | Data piping: placeholders → actual values |
| `WorkflowSchemas` | Java records as type-safe contracts |

---

## 4. Data Models

### 4.1 React Flow JSON (Input)

```json
{
  "nodes": [
    { "id": "node_1", "type": "subagent_node", "data": { "taskType": "DataExtraction", "payload": "Rohdaten" } },
    { "id": "node_2", "type": "api_node", "data": { "payload": "${node_1.output.data}" } }
  ],
  "edges": [
    { "id": "e1-2", "source": "node_1", "target": "node_2", "condition": "status == 'SUCCESS'" }
  ]
}
```

### 4.2 Java Data Models

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class DeterministicReactFlowEngine {

    // --- React Flow data models ---
    public static class FlowGraph {
        public List<FlowNode> nodes;
        public List<FlowEdge> edges;
    }

    public static class FlowNode {
        public String id;
        public String type;        // e.g. "subagent_node", "api_node", "db_node"
        public Map<String, Object> data;  // Configuration & parameters
    }

    public static class FlowEdge {
        public String id;
        public String source;
        public String target;
        public String condition;  // e.g. "status == 'SUCCESS'"
    }

    // --- Agent message structure (LLM standard) ---
    public interface Message { String getRole(); }

    public static class UserMessage implements Message {
        private final String content;
        public UserMessage(String content) { this.content = content; }
        public String getRole() { return "user"; }
        public String getContent() { return content; }
    }

    public static class ToolCall {
        private final String id, toolName;
        private final Map<String, Object> arguments;
        public ToolCall(String id, String toolName, Map<String, Object> arguments) {
            this.id = id; this.toolName = toolName; this.arguments = arguments;
        }
        public String getId() { return id; }
        public String getToolName() { return toolName; }
        public Map<String, Object> getArguments() { return arguments; }
    }

    public static class AssistantMessage implements Message {
        private final String thought;
        private final List<ToolCall> toolCalls;
        private final boolean isFinished;
        public AssistantMessage(String thought, List<ToolCall> toolCalls, boolean isFinished) {
            this.thought = thought; this.toolCalls = toolCalls; this.isFinished = isFinished;
        }
        public String getRole() { return "assistant"; }
        public String getThought() { return thought; }
        public List<ToolCall> getToolCalls() { return toolCalls; }
        public boolean isFinished() { return isFinished; }
    }

    public static class ToolMessage implements Message {
        private final String toolCallId, content;
        public ToolMessage(String toolCallId, String content) {
            this.toolCallId = toolCallId; this.content = content;
        }
        public String getRole() { return "tool"; }
        public String getContent() { return content; }
    }

    // --- Central agent context ---
    public static class AgentContext {
        private final List<Message> history = new ArrayList<>();
        private final Map<String, Object> workflowState = new HashMap<>();
        private final Map<String, Object> nodeOutputs = new HashMap<>();  // outputs per node ID
        private String currentNodeId;

        public void addMessage(Message msg) { history.add(msg); }
        public String getCurrentNodeId() { return currentNodeId; }
        public void setCurrentNodeId(String id) { this.currentNodeId = id; }

        public void setNodeOutput(String nodeId, Object output) { nodeOutputs.put(nodeId, output); }
        public Object getNodeOutput(String nodeId) { return nodeOutputs.get(nodeId); }

        public void setState(String key, Object val) { workflowState.put(key, val); }
        public Object getState(String key) { return workflowState.get(key); }
    }
}
```

---

## 5. The `getNextStep` Logic

The heart of it: reads the React Flow graph and determines the next node.

```java
public static AssistantMessage getNextStep(FlowGraph graph, AgentContext ctx) {
    String currentId = ctx.getCurrentNodeId();

    // Case A: Workflow start (no node set yet)
    if (currentId == null) {
        String startNodeId = findStartNode(graph);
        ctx.setCurrentNodeId(startNodeId);
        return buildToolCallForNode(startNodeId, graph, ctx);
    }

    // Case B: Find outgoing edges in React Flow
    List<FlowEdge> outgoingEdges = new ArrayList<>();
    for (FlowEdge edge : graph.edges) {
        if (edge.source.equals(currentId)) outgoingEdges.add(edge);
    }

    if (outgoingEdges.isEmpty()) {
        return new AssistantMessage("Workflow successfully completed.", Collections.emptyList(), true);
    }

    // Case C: Determine next node (with condition checks on branches)
    String nextNodeId = null;
    if (outgoingEdges.size() == 1) {
        nextNodeId = outgoingEdges.get(0).target;  // Linear path
    } else {
        for (FlowEdge edge : outgoingEdges) {
            if (evaluateCondition(edge.condition, ctx)) {
                nextNodeId = edge.target;
                break;
            }
        }
    }

    if (nextNodeId == null) {
        throw new IllegalStateException("No valid edge from node " + currentId + " satisfied.");
    }

    ctx.setCurrentNodeId(nextNodeId);
    return buildToolCallForNode(nextNodeId, graph, ctx);
}
```

### Helper methods

```java
private static String findStartNode(FlowGraph graph) {
    Set<String> targets = new HashSet<>();
    for (FlowEdge e : graph.edges) targets.add(e.target);
    for (FlowNode n : graph.nodes) {
        if (!targets.contains(n.id)) return n.id;
    }
    return graph.nodes.get(0).id;  // Fallback
}

private static boolean evaluateCondition(String condition, AgentContext ctx) {
    if (condition == null || condition.isBlank()) return true;
    // Example: checks the status in global state or in node outputs
    if (condition.equals("status == 'SUCCESS'")) {
        return "SUCCESS".equals(ctx.getState("lastStatus"));
    }
    if (condition.equals("status == 'ERROR'")) {
        return "ERROR".equals(ctx.getState("lastNodeStatus"));
    }
    return false;
}
```

### Node → ToolCall mapping

```java
private static AssistantMessage buildToolCallForNode(String nodeId, FlowGraph graph, AgentContext ctx) {
    FlowNode node = graph.nodes.stream()
            .filter(n -> n.id.equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Node " + nodeId + " not found"));

    Map<String, Object> resolvedArgs = new HashMap<>();

    if (node.data != null) {
        for (Map.Entry<String, Object> entry : node.data.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                resolvedArgs.put(entry.getKey(), resolveTemplate((String) value, ctx));
            } else {
                resolvedArgs.put(entry.getKey(), value);
            }
        }
    }

    // Dynamic mapping from node.type to tool name
    String toolName = switch (node.type != null ? node.type : "default") {
        case "subagent_node" -> "subagentTool";
        case "api_node"      -> "apiTool";
        case "db_node"       -> "dbTool";
        case "email_node"    -> "emailTool";
        case "llm_node"      -> "llmTool";   // Hybrid: LLM evaluation
        default              -> "defaultTool";
    };

    return new AssistantMessage(
        "Execute React Flow node [" + node.id + "].",
        List.of(new ToolCall("call_" + nodeId, toolName, resolvedArgs)),
        false
    );
}
```

---

## 6. Data Piping & Structured Outputs

### 6.1 Template resolver with nested access

```java
private static final Pattern TEMPLATE_PATTERN =
        Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\.output(\\.([a-zA-Z0-9_.]+))?\\}");

private static Object resolveTemplate(String template, AgentContext ctx) {
    Matcher matcher = TEMPLATE_PATTERN.matcher(template);

    if (matcher.find()) {
        String targetNodeId = matcher.group(1);
        String propertyPath = matcher.group(3);  // e.g. "status" or "user.email"
        Object previousOutput = ctx.getNodeOutput(targetNodeId);

        if (previousOutput == null) return null;

        if (propertyPath == null || propertyPath.isEmpty()) {
            return previousOutput;  // Whole output (${node_1.output})
        }

        // Resolve nested property
        return resolveNestedProperty(previousOutput, propertyPath);
    }
    return template;
}

private static Object resolveNestedProperty(Object target, String path) {
    String[] parts = path.split("\\.");
    Object current = target;
    for (String part : parts) {
        if (current instanceof Map) {
            current = ((Map<?, ?>) current).get(part);
            if (current == null) return null;  // Key not found → no crash
        } else {
            return null;
        }
    }
    return current;
}
```

### 6.2 Example: data piping across nodes

```json
{
  "nodes": [
    { "id": "node_1", "type": "subagent_node", "data": { "payload": "Rohdaten aus DB" } },
    { "id": "node_2", "type": "api_node", "data": { "payload": "${node_1.output.data}" } }
  ],
  "edges": [
    { "id": "e1-2", "source": "node_1", "target": "node_2" }
  ]
}
```

- `node_1` stores `{ "status": "SUCCESS", "data": "Verarbeitete Daten" }` in the context
- `node_2` receives `"Verarbeitete Daten"` as its `payload` parameter

### 6.3 Type-safe contracts via Java records

```java
import dev.langchain4j.model.output.structured.Description;

public record OrderAnalysisResult(
    @Description("Eindeutige Bestellnummer") String orderId,
    @Description("Klassifizierung: VIP_ORDER, STANDARD, FRAUD_SUSPECT") String classification,
    @Description("Konfidenz-Score 0.0–1.0") double confidenceScore,
    @Description("Additional extracted data") Map<String, Object> extractedData
) {}
```

LangChain4j generates a JSON schema from this, forces the LLM to comply, and deserializes/validates automatically.

---

## 7. The Main Loop

```java
public static void runWorkflowLoop(String reactFlowJson) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    FlowGraph graph = mapper.readValue(reactFlowJson, FlowGraph.class);

    AgentContext context = new AgentContext();
    context.addMessage(new UserMessage("Starte deterministischen Workflow."));

    System.out.println("=== Starte React-Flow Agenten-Loop ===\n");

    int maxSteps = 10;
    int step = 0;

    while (step < maxSteps) {
        step++;

        // 1. Deterministic next step (replaces LLM thought)
        AssistantMessage assistantMsg = getNextStep(graph, context);
        context.addMessage(assistantMsg);

        System.out.println("[Step " + step + "] " + assistantMsg.getThought());

        if (assistantMsg.isFinished() || assistantMsg.getToolCalls().isEmpty()) {
            System.out.println(" ✨ Workflow finished normally.");
            break;
        }

        // 2. Execute tool calls (action)
        for (ToolCall call : assistantMsg.getToolCalls()) {
            String nodeId = call.getId().replace("call_", "");

            Map<String, Object> observationMap = executeTool(
                    nodeId, call.getToolName(), call.getArguments(), context);

            String observationString = observationMap.toString();
            System.out.println(" 👁️  Observation: " + observationString);

            // 3. Write observation into history (ToolMessage)
            context.addMessage(new ToolMessage(call.getId(), observationString));
        }
        System.out.println("--------------------------------------------------");
    }
}
```

### Tool dispatcher

```java
public static Map<String, Object> executeTool(
        String nodeId, String toolName, Map<String, Object> arguments, AgentContext ctx) {

    System.out.println("   [Tool: " + toolName + "] Execution for node [" + nodeId + "]");

    Map<String, Object> structuredResult = new HashMap<>();

    switch (toolName) {
        case "subagentTool":
            structuredResult.put("status", "SUCCESS");
            structuredResult.put("data", "Verarbeitete Daten von Node " + nodeId);
            break;
        case "apiTool":
            structuredResult.put("status", "SUCCESS");
            structuredResult.put("responseCode", 200);
            break;
        case "dbTool":
            structuredResult.put("status", "SUCCESS");
            structuredResult.put("rowCount", 42);
            break;
        default:
            structuredResult.put("status", "UNKNOWN");
            break;
    }

    // Update global state (for edge conditions)
    ctx.setState("lastStatus", structuredResult.get("status"));

    // Store structured output for data piping
    ctx.setNodeOutput(nodeId, structuredResult);

    return structuredResult;
}
```

---

## 8. Hybrid Approach: Deterministic + LLM

### 8.1 LLM as node type

In the React Flow graph, an LLM step is simply another node type (`llm_eval_node`, `llm_summarize_node`).

- The engine executes the node by passing the entire `AgentContext` (chat history, previous `nodeOutputs`) to an LLM service.
- The LLM delivers a **structured output** (JSON) that is stored in the context and passed on via data piping.

### 8.2 Use case: clarifying unclear states

```
User: "I want to return the stuff, but I don't know where to."
  ↓
Deterministic edge fails → routing to llm_eval_node
  ↓
LLM classifies: { "intent": "REFUND", "confidence": 0.9 }
  ↓
Edge condition: ${llm_eval.output.intent} == 'REFUND' → process_refund_node
```

### 8.3 Use case: summarizing data

```json
{
  "id": "summarize_node",
  "type": "llm_summarize_node",
  "data": {
    "prompt": "Summarize the following raw data for the customer:",
    "sourceData1": "${node_db.output}",
    "sourceData2": "${node_api.output}"
  }
}
```

The LLM node feeds the data to the LLM; the result is stored as `ctx.setNodeOutput("summarize_node", summaryText)` and used by the subsequent `email_node`.

### 8.4 Arbitrary nesting

```
Macro (deterministic): React Flow graph steers the coarse flow
  → Meso (LLM/subagent): LLM starts its own sub-loop
    → Micro (deterministic): subagent uses fixed tools (API, DB)
```

For the engine this is transparent — each part delivers a structured output in the shared context.

---

## 9. Error Handling & Retry

### 9.1 Safe execution with fallback

```java
public static void executeLlmAgentNodeSafely(
        String nodeId, Map<String, Object> nodeData,
        WorkflowAgentService agentService, AgentContext ctx) {

    try {
        String rawInput = (String) nodeData.getOrDefault("inputData", "");
        OrderAnalysisResult result = agentService.runAgent(rawInput);
        ctx.setNodeOutput(nodeId, result);
        ctx.setState("lastNodeStatus", "SUCCESS");

    } catch (Exception e) {
        System.err.println(" ⚠️ Validierungsfehler im Node [" + nodeId + "]: " + e.getMessage());

        // Fallback object so the workflow doesn't crash
        OrderAnalysisResult errorResult = new OrderAnalysisResult(
            "UNKNOWN", "ERROR", 0.0, Map.of(), true, e.getMessage()
        );
        ctx.setNodeOutput(nodeId, errorResult);
        ctx.setState("lastNodeStatus", "ERROR");  // routes flow into error path
    }
}
```

### 9.2 Error edges in React Flow

```json
{
  "nodes": [
    { "id": "node_1", "type": "llm_agent_node", "data": { "inputData": "Bestelldaten..." } },
    { "id": "node_success_path", "type": "api_node", "data": {} },
    { "id": "node_error_fallback", "type": "email_node", "data": { "msg": "Fehler bei der automatischen Verarbeitung" } }
  ],
  "edges": [
    { "id": "e-success", "source": "node_1", "target": "node_success_path", "condition": "lastNodeStatus == 'SUCCESS'" },
    { "id": "e-error", "source": "node_1", "target": "node_error_fallback", "condition": "lastNodeStatus == 'ERROR'" }
  ]
}
```

### 9.3 Automatic retry

```java
public static OrderAnalysisResult runAgentWithRetry(
        WorkflowAgentService agentService, String rawInput, int maxRetries) {
    int attempts = 0;
    while (attempts < maxRetries) {
        try {
            attempts++;
            return agentService.runAgent(rawInput);
        } catch (Exception e) {
            if (attempts >= maxRetries) {
                throw new IllegalStateException("Agent nach " + maxRetries + " Versuchen fehlgeschlagen.", e);
            }
            System.out.println(" 🔄 Validierungsfehler, starte Retry " + attempts + "...");
        }
    }
    throw new IllegalStateException("Unbekannter Fehler bei Retry-Schleife.");
}
```

---

## 10. LangChain4j Integration (Structured Output)

### 10.1 Agent service with enforced output

```java
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.model.chat.ChatLanguageModel;

public class WorkflowAgentService {

    public interface OrderAnalyzerAgent {
        @UserMessage("Analysiere die folgende Bestellung und extrahiere die Daten strukturiert: {{it}}")
        OrderAnalysisResult analyze(String rawOrderText);
    }

    private final OrderAnalyzerAgent agent;

    public WorkflowAgentService(ChatLanguageModel chatModel) {
        this.agent = AiServices.create(OrderAnalyzerAgent.class, chatModel);
    }

    public OrderAnalysisResult runAgent(String rawInput) {
        // LangChain4j generates a JSON schema from the record,
        // forces the LLM to comply and validates automatically.
        return agent.analyze(rawInput);
    }
}
```

### 10.2 Integration into the engine

```java
public static OrderAnalysisResult executeLlmAgentNode(
        String nodeId, Map<String, Object> nodeData,
        WorkflowAgentService agentService, AgentContext ctx) {

    System.out.println("   [LLM Agent Node " + nodeId + "] Starting LLM execution with record validation...");

    String rawInput = (String) nodeData.getOrDefault("inputData", "Standard-Bestellung");

    // Automatic JSON schema generation, LLM enforcement & validation
    OrderAnalysisResult validatedResult = agentService.runAgent(rawInput);

    // Type-safe storage in context (no unsafe casting)
    ctx.setNodeOutput(nodeId, validatedResult);

    System.out.println(" 👁️  Validierter Structured Output: Klassifizierung = " + validatedResult.classification());
    return validatedResult;
}
```

---

## 11. React Flow Frontend Support

### 11.1 Static data passing

```javascript
const initialNodes = [
  {
    id: 'node_1',
    type: 'subagentNode',
    position: { x: 0, y: 0 },
    data: {
      label: 'Daten-Extraktion',
      payload: 'Rohdaten aus DB',
      config: { retries: 3 }
    },
  },
];
```

### 11.2 Dynamic data flow (React Flow hooks)

- **`useNodeConnections`** — finds out which nodes are connected to which handles
- **`useNodesData`** — fetches the current data of the connected source node
- **`updateNodeData`** — allows runtime updates (e.g. after a tool call) so that downstream nodes can access them

### 11.3 Edge data (conditions)

```javascript
const initialEdges = [
  {
    id: 'e1-2',
    source: 'node_1',
    target: 'node_2',
    data: { condition: 'status == SUCCESS' },
    label: 'Wenn Erfolg'
  }
];
```

---

## 12. Type-safe Edge Evaluation with Java Records

### 12.1 Shared schema as contract

```java
public record OrderAnalysisResult(
    String orderId,
    String classification,   // "VIP_ORDER", "STANDARD", "FRAUD_SUSPECT"
    double confidenceScore,
    Map<String, Object> extractedData,
    boolean hasError,
    String errorMessage
) {}
```

### 12.2 Evaluating an edge condition

```java
public static boolean evaluateCondition(String condition, AgentContext ctx) {
    if (condition == null || condition.isBlank()) return true;

    if (condition.startsWith("analysis.classification ==")) {
        String expectedValue = condition.split("==")[1].trim().replace("'", "");
        OrderAnalysisResult result = (OrderAnalysisResult) ctx.getNodeOutput("node_1");
        if (result != null) {
            return result.classification().equals(expectedValue);
        }
    }
    return false;
}
```

### 12.3 Data piping on structured fields

```java
public static Map<String, Object> resolveNodeParameters(FlowNode node, AgentContext ctx) {
    Map<String, Object> resolvedArgs = new HashMap<>();
    if (node.data == null) return resolvedArgs;

    for (Map.Entry<String, Object> entry : node.data.entrySet()) {
        Object val = entry.getValue();
        if (val instanceof String && ((String) val).startsWith("${node_1.output")) {
            OrderAnalysisResult prevResult = (OrderAnalysisResult) ctx.getNodeOutput("node_1");
            if (((String) val).contains("discountAllowed")) {
                resolvedArgs.put(entry.getKey(), prevResult.extractedData().get("discountAllowed"));
            } else {
                resolvedArgs.put(entry.getKey(), prevResult);
            }
        } else {
            resolvedArgs.put(entry.getKey(), val);
        }
    }
    return resolvedArgs;
}
```

---

## 13. Project Structure & Dependencies

### 13.1 Project structure

```
my-agent-engine/
├── build.gradle.kts  (or pom.xml)
└── src/
    ├── main/
    │   ├── java/com/example/engine/
    │   │   ├── CoreWorkflowEngine.java     # Main loop & getNextStep
    │   │   ├── AgentContext.java          # Chat history, state, node outputs
    │   │   ├── model/
    │   │   │   ├── FlowGraphDto.java      # React Flow JSON (Nodes, Edges)
    │   │   │   └── WorkflowSchemas.java   # Java records (structured outputs)
    │   │   ├── tools/
    │   │   │   ├── ToolDispatcher.java    # Dispatches tool calls to APIs, DBs, subagents
    │   │   │   └── ActionTools.java       # Concrete tool implementations
    │   │   └── agent/
    │   │       └── WorkflowAgentService.java  # LangChain4j AI service
    │   └── resources/
    │       └── workflow-graph.json        # Example React Flow export
    └── test/
        └── java/com/example/engine/
            └── WorkflowEngineTest.java
```

### 13.2 Maven dependencies

```xml
<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <langchain4j.version>1.23.0</langchain4j.version>
    <jackson.version>2.18.2</jackson.version>
</properties>

<dependencies>
    <!-- JSON parsing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
    </dependency>

    <!-- LangChain4j core & OpenAI -->
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>
    <dependency>
        <groupId>dev.langchain4j</groupId>
        <artifactId>langchain4j-open-ai</artifactId>
        <version>${langchain4j.version}</version>
    </dependency>

    <!-- Logging -->
    <dependency>
        <groupId>org.slf4j</groupId>
        <artifactId>slf4j-api</artifactId>
        <version>2.0.16</version>
    </dependency>
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.12</version>
    </dependency>

    <!-- Testing -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.11.3</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 14. Why this approach?

1. **Complete decoupling from LLMs:** The engine works purely deterministically on the basis of `FlowGraph` and `AgentContext`. The LLM is merely a specialized node type that can be hooked in.
2. **Standardized message history:** Through `UserMessage`, `AssistantMessage` (with `ToolCall`) and `ToolMessage`, the architecture is 100% compatible with AI agent frameworks. If you replace `getNextStep` with a real LLM call, the rest of the engine does not need to change.
3. **Structured outputs & safe data piping:** Tools return structured maps/records. The template resolver catches missing keys or `null` values so the workflow does not crash on data errors.
4. **Type safety end to end:** Java records as the single source of truth — both the agent (via LangChain4j) and the workflow engine (for edge conditions and data piping) use the same types. The compiler catches deviations immediately.

---

## 15. References

- Original conversation: `qoc/docs/ReAct.txt`
- React Flow format & Temporal integration: `qoc/docs/engineering/ReactFlow-temporal.md`
- QOC engine: `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`
- Workflow runner (React Flow format, backend): `qoc/workflow-runner/`
- Workflow UI (React Flow editor): `qoc/workflow-ui/`
- LangChain4j: https://docs.langchain4j.dev/
- React Flow: https://reactflow.dev/
