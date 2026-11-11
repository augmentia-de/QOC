# ReAct-Workflow — Deterministische React-Flow-Agenten-Engine

**Status:** Draft (2026-11-11) · Quelle: `qoc/docs/ReAct.txt` (Chat-Konversation zur Architektur)
**Scope:** Hybride Engine, die ReAct-Agenten-Loop-Mechanik (Thought → Action → Observation) mit einem React-Flow-Graphen kombiniert — 100 % deterministisch, ohne LLM-Raten für Pfadsteuerung.

---

## 1. Ziel

Ein Workflow-System, das:

- den **visuellen Designer** (React Flow, Nodes + Edges) als primäre Definition nutzt,
- den **ReAct-Loop** strukturell (Thought → Action → Observation) beibehält,
- aber den **LLM-Call (Thought)** durch eine deterministische `getNextStep`-Methode ersetzt,
- Daten über Platzhalter (`${node_1.output.field}`) zwischen Nodes durchreicht,
- Java-`record`s als typsichere Verträge zwischen Agent und Workflow-Engine nutzt,
- optional LLM-Nodes für unklare Zustände/Zusammenfassungen einbindet (Hybrid).

---

## 2. Grundidee: ReAct → Deterministischer Loop

### 2.1 Klassischer ReAct-Loop (KI-gesteuert)

```
┌─────────────────────────────────────────────────────────────┐
│  ReAct-Agent (LLM-basiert)                                  │
│                                                             │
│   Thought   → LLM plant den nächsten Schritt                │
│   Action    → LLM wählt Tool + Parameter                    │
│   Observation → Tool-Ergebnis zurück in Kontext             │
│   ... bis fertig                                            │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Deterministischer ReAct-Loop (Code-gesteuert)

```
┌─────────────────────────────────────────────────────────────┐
│  Deterministische Engine                                    │
│                                                             │
│   getNextStep(context, graph) → "execute <nodeId>"          │
│   executeNodeAction(nodeId)   → Tool ausführen              │
│   Observation                 → in AgentContext speichern    │
│   ... bis keine ausgehenden Edges mehr ("DONE")             │
└─────────────────────────────────────────────────────────────┘
```

**Unterschied:** Nur das "Gehirn" (Thought) wird ausgetauscht. Die Struktur (Loop, Tool-Execution, State-Update, Observation) bleibt identisch. Wenn man später ein LLM einsteuern will, muss nur `getNextStep` angepasst werden.

**Vorteile:**
- 100 % reproduzierbar, kein Halluzinationsrisiko, keine Endlos-Loops durch falsche LLM-Interpretation
- Keine LLM-Kosten/Latenz für Pfadsteuerung
- Volles Logging/Debugging jedes Schritts
- Typsichere Verträge via Java-Records

**Nachteil:** Starr — kann nicht dynamisch auf völlig unvorhergesehene Situationen reagieren, für die kein Pfad programmiert wurde.

---

## 3. Architektur

### 3.1 Datenfluss

```
┌────────────────────┐
│ React Flow JSON    │  { nodes, edges, agents }
│ (Frontend-Export)  │
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ getNextStep()      │  Liest Graph + AgentContext
│                    │  → "execute <nodeId>"
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ Tool Dispatcher    │  führt Node aus
│                    │  → structured Output
└────────┬───────────┘
         │
         ▼
┌────────────────────┐
│ AgentContext       │  speichert nodeOutputs
│ (State + History)  │  → für nächste Iteration
└────────────────────┘
```

### 3.2 Komponenten

| Komponente | Verantwortung |
|---|---|
| `FlowGraph` | React-Flow-Datenmodell (Nodes, Edges, Conditions) |
| `AgentContext` | Zentraler State: Chat-Historie, `nodeOutputs`, `workflowState`, `currentNodeId` |
| `getNextStep` | Deterministische Navigation im Graphen |
| `ToolDispatcher` | Mapping `node.type` → Tool-Ausführung |
| `resolveTemplate` | Data-Piping: Platzhalter → echte Werte |
| `WorkflowSchemas` | Java-Records als typsichere Verträge |

---

## 4. Datenmodelle

### 4.1 React-Flow-JSON (Input)

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

### 4.2 Java-Datenmodelle

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class DeterministicReactFlowEngine {

    // --- React-Flow Datenmodelle ---
    public static class FlowGraph {
        public List<FlowNode> nodes;
        public List<FlowEdge> edges;
    }

    public static class FlowNode {
        public String id;
        public String type;        // z.B. "subagent_node", "api_node", "db_node"
        public Map<String, Object> data;  // Konfiguration & Parameter
    }

    public static class FlowEdge {
        public String id;
        public String source;
        public String target;
        public String condition;  // z.B. "status == 'SUCCESS'"
    }

    // --- Agenten-Message-Struktur (LLM-Standard) ---
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

    // --- Zentraler Agenten-Kontext ---
    public static class AgentContext {
        private final List<Message> history = new ArrayList<>();
        private final Map<String, Object> workflowState = new HashMap<>();
        private final Map<String, Object> nodeOutputs = new HashMap<>();  // Outputs per Node-ID
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

## 5. Die `getNextStep`-Logik

Das Herzstück: Liest den React-Flow-Graphen und bestimmt den nächsten Node.

```java
public static AssistantMessage getNextStep(FlowGraph graph, AgentContext ctx) {
    String currentId = ctx.getCurrentNodeId();

    // Fall A: Workflow-Start (noch kein Node gesetzt)
    if (currentId == null) {
        String startNodeId = findStartNode(graph);
        ctx.setCurrentNodeId(startNodeId);
        return buildToolCallForNode(startNodeId, graph, ctx);
    }

    // Fall B: Finde ausgehende Edges im React-Flow
    List<FlowEdge> outgoingEdges = new ArrayList<>();
    for (FlowEdge edge : graph.edges) {
        if (edge.source.equals(currentId)) outgoingEdges.add(edge);
    }

    if (outgoingEdges.isEmpty()) {
        return new AssistantMessage("Workflow erfolgreich beendet.", Collections.emptyList(), true);
    }

    // Fall C: Nächsten Node ermitteln (mit Bedingungsprüfung bei Verzweigungen)
    String nextNodeId = null;
    if (outgoingEdges.size() == 1) {
        nextNodeId = outgoingEdges.get(0).target;  // Linearer Pfad
    } else {
        for (FlowEdge edge : outgoingEdges) {
            if (evaluateCondition(edge.condition, ctx)) {
                nextNodeId = edge.target;
                break;
            }
        }
    }

    if (nextNodeId == null) {
        throw new IllegalStateException("Keine gültige Kante ab Node " + currentId + " erfüllt.");
    }

    ctx.setCurrentNodeId(nextNodeId);
    return buildToolCallForNode(nextNodeId, graph, ctx);
}
```

### Hilfsmethoden

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
    // Beispiel: Prüft den Status im globalen State oder in Node-Outputs
    if (condition.equals("status == 'SUCCESS'")) {
        return "SUCCESS".equals(ctx.getState("lastStatus"));
    }
    if (condition.equals("status == 'ERROR'")) {
        return "ERROR".equals(ctx.getState("lastNodeStatus"));
    }
    return false;
}
```

### Node → ToolCall Mapping

```java
private static AssistantMessage buildToolCallForNode(String nodeId, FlowGraph graph, AgentContext ctx) {
    FlowNode node = graph.nodes.stream()
            .filter(n -> n.id.equals(nodeId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Node " + nodeId + " nicht gefunden"));

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

    // Dynamisches Mapping von node.type auf Tool-Namen
    String toolName = switch (node.type != null ? node.type : "default") {
        case "subagent_node" -> "subagentTool";
        case "api_node"      -> "apiTool";
        case "db_node"       -> "dbTool";
        case "email_node"    -> "emailTool";
        case "llm_node"      -> "llmTool";   // Hybrid: LLM-Evaluierung
        default              -> "defaultTool";
    };

    return new AssistantMessage(
        "Führe React-Flow Node [" + node.id + "] aus.",
        List.of(new ToolCall("call_" + nodeId, toolName, resolvedArgs)),
        false
    );
}
```

---

## 6. Data-Piping & Structured Outputs

### 6.1 Template-Resolver mit verschachteltem Zugriff

```java
private static final Pattern TEMPLATE_PATTERN =
        Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\.output(\\.([a-zA-Z0-9_.]+))?\\}");

private static Object resolveTemplate(String template, AgentContext ctx) {
    Matcher matcher = TEMPLATE_PATTERN.matcher(template);

    if (matcher.find()) {
        String targetNodeId = matcher.group(1);
        String propertyPath = matcher.group(3);  // z.B. "status" oder "user.email"
        Object previousOutput = ctx.getNodeOutput(targetNodeId);

        if (previousOutput == null) return null;

        if (propertyPath == null || propertyPath.isEmpty()) {
            return previousOutput;  // Ganzer Output (${node_1.output})
        }

        // Verschachtelte Eigenschaft auflösen
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
            if (current == null) return null;  // Key nicht gefunden → kein Absturz
        } else {
            return null;
        }
    }
    return current;
}
```

### 6.2 Beispiel: Data-Piping über Nodes

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

- `node_1` speichert `{ "status": "SUCCESS", "data": "Verarbeitete Daten" }` im Kontext
- `node_2` erhält `"Verarbeitete Daten"` als `payload`-Parameter

### 6.3 Typsichere Verträge via Java-Records

```java
import dev.langchain4j.model.output.structured.Description;

public record OrderAnalysisResult(
    @Description("Eindeutige Bestellnummer") String orderId,
    @Description("Klassifizierung: VIP_ORDER, STANDARD, FRAUD_SUSPECT") String classification,
    @Description("Konfidenz-Score 0.0–1.0") double confidenceScore,
    @Description("Zusätzliche extrahierte Daten") Map<String, Object> extractedData
) {}
```

LangChain4j generiert daraus ein JSON-Schema, zwingt das LLM zur Einhaltung und deserialisiert/validiert automatisch.

---

## 7. Der Haupt-Loop

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

        // 1. Deterministischer Next-Step (ersetzt LLM-Thought)
        AssistantMessage assistantMsg = getNextStep(graph, context);
        context.addMessage(assistantMsg);

        System.out.println("[Step " + step + "] " + assistantMsg.getThought());

        if (assistantMsg.isFinished() || assistantMsg.getToolCalls().isEmpty()) {
            System.out.println(" ✨ Workflow regulär beendet.");
            break;
        }

        // 2. Tool-Aufrufe ausführen (Action)
        for (ToolCall call : assistantMsg.getToolCalls()) {
            String nodeId = call.getId().replace("call_", "");

            Map<String, Object> observationMap = executeTool(
                    nodeId, call.getToolName(), call.getArguments(), context);

            String observationString = observationMap.toString();
            System.out.println(" 👁️  Observation: " + observationString);

            // 3. Observation in Historie schreiben (ToolMessage)
            context.addMessage(new ToolMessage(call.getId(), observationString));
        }
        System.out.println("--------------------------------------------------");
    }
}
```

### Tool-Dispatcher

```java
public static Map<String, Object> executeTool(
        String nodeId, String toolName, Map<String, Object> arguments, AgentContext ctx) {

    System.out.println("   [Tool: " + toolName + "] Ausführung für Node [" + nodeId + "]");

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

    // Globalen State aktualisieren (für Edge-Bedingungen)
    ctx.setState("lastStatus", structuredResult.get("status"));

    // Structured Output für Data-Piping abspeichern
    ctx.setNodeOutput(nodeId, structuredResult);

    return structuredResult;
}
```

---

## 8. Hybrid-Ansatz: Deterministisch + LLM

### 8.1 LLM als Node-Typ

Im React-Flow-Graphen ist ein LLM-Schritt einfach ein weiterer Node-Typ (`llm_eval_node`, `llm_summarize_node`).

- Die Engine führt den Node aus, indem sie den gesamten `AgentContext` (Chat-Historie, bisherige `nodeOutputs`) an einen LLM-Service übergibt.
- Das LLM liefert ein **Structured Output** (JSON), das im Kontext gespeichert und per Data-Piping weitergegeben wird.

### 8.2 Anwendungsfall: Unklare Zustände klären

```
User: "Ich will das Zeug zurückschicken, aber weiß nicht wohin."
  ↓
Deterministische Edge schlägt fehl → Routing zu llm_eval_node
  ↓
LLM klassifiziert: { "intent": "REFUND", "confidence": 0.9 }
  ↓
Edge-Bedingung: ${llm_eval.output.intent} == 'REFUND' → process_refund_node
```

### 8.3 Anwendungsfall: Daten zusammenfassen

```json
{
  "id": "summarize_node",
  "type": "llm_summarize_node",
  "data": {
    "prompt": "Fasse die folgenden Rohdaten für den Kunden zusammen:",
    "sourceData1": "${node_db.output}",
    "sourceData2": "${node_api.output}"
  }
}
```

Der LLM-Node füttert das LLM mit den Daten, das Ergebnis wird als `ctx.setNodeOutput("summarize_node", summaryText)` abgelegt und vom nachfolgenden `email_node` verwendet.

### 8.4 Beliebige Verschachtelung

```
Makro (Deterministisch): React-Flow-Graph steuert groben Ablauf
  → Meso (LLM/Subagent): LLM startet eigenen Sub-Loop
    → Mikro (Deterministisch): Subagent nutzt feste Tools (API, DB)
```

Für die Engine ist das transparent — jedes Teilstück liefert einen strukturierten Output im gemeinsamen Kontext.

---

## 9. Fehlerbehandlung & Retry

### 9.1 Sichere Ausführung mit Fallback

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

        // Fallback-Objekt, damit der Workflow nicht crasht
        OrderAnalysisResult errorResult = new OrderAnalysisResult(
            "UNKNOWN", "ERROR", 0.0, Map.of(), true, e.getMessage()
        );
        ctx.setNodeOutput(nodeId, errorResult);
        ctx.setState("lastNodeStatus", "ERROR");  // Leitet Flow in Fehlerpfad
    }
}
```

### 9.2 Error-Edges im React-Flow

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

### 9.3 Automatischer Retry

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

## 10. LangChain4j-Integration (Structured Output)

### 10.1 Agent-Service mit erzwungenem Output

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
        // LangChain4j generiert JSON-Schema aus dem Record,
        // zwingt das LLM zur Einhaltung und validiert automatisch.
        return agent.analyze(rawInput);
    }
}
```

### 10.2 Integration in die Engine

```java
public static OrderAnalysisResult executeLlmAgentNode(
        String nodeId, Map<String, Object> nodeData,
        WorkflowAgentService agentService, AgentContext ctx) {

    System.out.println("   [LLM Agent Node " + nodeId + "] Starte LLM-Ausführung mit Record-Validierung...");

    String rawInput = (String) nodeData.getOrDefault("inputData", "Standard-Bestellung");

    // Automatische JSON-Schema-Generierung, LLM-Zwang & Validierung
    OrderAnalysisResult validatedResult = agentService.runAgent(rawInput);

    // Typsicher im Kontext hinterlegen (kein unsicheres Casting)
    ctx.setNodeOutput(nodeId, validatedResult);

    System.out.println(" 👁️  Validierter Structured Output: Klassifizierung = " + validatedResult.classification());
    return validatedResult;
}
```

---

## 11. React Flow Frontend-Unterstützung

### 11.1 Statische Datenübergabe

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

### 11.2 Dynamischer Datenfluss (React Flow Hooks)

- **`useNodeConnections`** — Findet heraus, welche Nodes an welchen Handles verknüpft sind
- **`useNodesData`** — Holt die aktuellen Daten des verbundenen Quell-Nodes
- **`updateNodeData`** — Erlaubt Laufzeit-Updates (z. B. nach Tool-Call), sodass nachfolgende Nodes darauf zugreifen können

### 11.3 Edge-Daten (Bedingungen)

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

## 12. Typsichere Edge-Auswertung mit Java-Records

### 12.1 Gemeinsames Schema als Vertrag

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

### 12.2 Edge-Bedingung auswerten

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

### 12.3 Data-Piping auf strukturierte Felder

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

## 13. Projektstruktur & Dependencies

### 13.1 Projektstruktur

```
my-agent-engine/
├── build.gradle.kts  (oder pom.xml)
└── src/
    ├── main/
    │   ├── java/com/example/engine/
    │   │   ├── CoreWorkflowEngine.java     # Hauptschleife & getNextStep
    │   │   ├── AgentContext.java          # Chat-Historie, State, Node-Outputs
    │   │   ├── model/
    │   │   │   ├── FlowGraphDto.java      # React-Flow JSON (Nodes, Edges)
    │   │   │   └── WorkflowSchemas.java   # Java Records (Structured Outputs)
    │   │   ├── tools/
    │   │   │   ├── ToolDispatcher.java    # Verteilt ToolCalls an API, DB, Subagenten
    │   │   │   └── ActionTools.java       # Konkrete Tool-Implementierungen
    │   │   └── agent/
    │   │       └── WorkflowAgentService.java  # LangChain4j AI Service
    │   └── resources/
    │       └── workflow-graph.json        # Beispielhafter React-Flow-Export
    └── test/
        └── java/com/example/engine/
            └── WorkflowEngineTest.java
```

### 13.2 Maven-Dependencies

```xml
<properties>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <langchain4j.version>1.23.0</langchain4j.version>
    <jackson.version>2.18.2</jackson.version>
</properties>

<dependencies>
    <!-- JSON Parsing -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>${jackson.version}</version>
    </dependency>

    <!-- LangChain4j Core & OpenAI -->
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

## 14. Warum dieser Ansatz?

1. **Vollständige Entkopplung von LLMs:** Die Engine arbeitet rein deterministisch auf Basis von `FlowGraph` und `AgentContext`. Das LLM ist lediglich ein spezialisierter Node-Typ, der eingeklinkt werden kann.
2. **Standardisierte Message-Historie:** Durch `UserMessage`, `AssistantMessage` (mit `ToolCall`) und `ToolMessage` ist die Architektur zu 100 % kompatibel mit KI-Agenten-Frameworks. Wenn man `getNextStep` durch einen echten LLM-Aufruf ersetzt, muss der Rest der Engine nicht geändert werden.
3. **Structured Outputs & Sicheres Data-Piping:** Tools liefern strukturierte Maps/Records zurück. Der Template-Resolver fängt fehlende Schlüssel oder `null`-Werte ab, sodass der Workflow bei Datenfehlern nicht abstürzt.
4. **Typsicherheit durchgängig:** Java-Records als Single Source of Truth — sowohl Agent (via LangChain4j) als auch Workflow-Engine (für Edge-Bedingungen und Data-Piping) nutzen dieselben Typen. Der Compiler deckt Abweichungen sofort auf.

---

## 15. Referenzen

- Original-Konversation: `qoc/docs/ReAct.txt`
- ReactFlow-Format & Temporal-Integration: `qoc/docs/engineering/ReactFlow-temporal.md`
- QOC Engine: `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`
- Workflow-Runner (ReactFlow-Format, Backend): `qoc/workflow-runner/`
- Workflow-UI (ReactFlow-Editor): `qoc/workflow-ui/`
- LangChain4j: https://docs.langchain4j.dev/
- React Flow: https://reactflow.dev/