# ReAct-Umsetzung — Schrittweiser Implementierungsweg

**Status:** Plan (2026-11-11) · Bezug: `ReAct-Workflow.md` (Architektur), `ReAct.txt` (Original-Konversation)
**Ziel:** Hybrid-Engine in QOC schrittweise umsetzen — deterministische Pfadsteuerung + LLM-basierte Agent-Nodes, testbar in isolierten Schritten.

---

## Prinzip

Jeder Schritt muss **isolierbar implementierbar und testbar** sein, ohne dass die nachfolgenden Schritte bereits existieren. Die Reihenfolge folgt den Datenabhängigkeiten: wer Daten liefert, muss zuerst existieren.

---

## Schritt 0 — Voraussetzung: Bestehende Tests grün halten

**Was:** `mvn test` im `workflow-runner` muss grün sein (TopologicalSort, ConditionEvaluator, WorkflowExecutor, WorkflowStore).

**Test:** Bestehende Unit-Tests + E2E-Script `scripts/e2e-workflow-test.sh`.

**Warum zuerst:** Jeder nachfolgende Schritt baut darauf auf; Regressionen müssen sofort sichtbar sein.

---

## Schritt 1 — `WorkflowDefinition.Edge` um `condition` erweitern

**Was:** Optionales `condition`-Feld in `Edge`-Record (analog zu `NodeData.branches`, aber auf Edge-Ebene).

```java
public record Edge(String id, String source, String target, String type, String condition) {
    public Edge {
        if (id == null) id = "";
        if (source == null) source = "";
        if (target == null) target = "";
        if (type == null) type = "default";
        if (condition == null) condition = "";
    }
}
```

**Warum zuerst:** Reine Datenmodell-Änderung, keine Logik. Backend liest/schreibt das Feld. Frontend kann es später nutzen. Keine Abhängigkeit zu anderen Schritten.

**Testbar:** Unit-Test: JSON mit `condition`-Feld deserialisieren, prüfen dass Feld gesetzt ist. Round-Trip: serialisieren → deserialisieren → vergleichen.

**Aufwand:** Sehr gering. **Risiko:** Minimal (additives Feld, `@JsonIgnoreProperties`).

---

## Schritt 2 — `WorkflowContext` einführen (ohne Logik-Änderung)

**Was:** Neues Class `WorkflowContext` im `workflowrunner.runner`-Paket:

```java
public class WorkflowContext {
    // Strukturierte Outputs pro Node-ID (für Data-Piping und Conditions)
    private final Map<String, Object> nodeOutputs = new HashMap<>();
    // Globale State-Variablen (z. B. lastStatus, lastResult, forkResults)
    private final Map<String, Object> workflowState = new HashMap<>();
    // Aktuelle Position im React-Flow-Graphen
    private String currentNodeId;

    public void setNodeOutput(String nodeId, Object output) { nodeOutputs.put(nodeId, output); }
    public Object getNodeOutput(String nodeId) { return nodeOutputs.get(nodeId); }
    public void setState(String key, Object val) { workflowState.put(key, val); }
    public Object getState(String key) { return workflowState.get(key); }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String id) { this.currentNodeId = id; }
    public Map<String, Object> getNodeOutputs() { return nodeOutputs; }
}
```

**Hinweis zur Chat-Historie:** `WorkflowContext` enthält **keine** Chat-Historie (`List<Message>`). Der Grund: Jeder Agent-Node ruft `QocEngine.runPrompt()` auf, der intern seine eigene `ConversationStore` (DB-backed) und `ContextBuilder` nutzt. Die Chat-Historie wird dort verwaltet, nicht im Workflow-Kontext. `WorkflowContext` hält nur Workflow-spezifischen State (Node-Outputs, globale Variablen, Graph-Position).

**Warum jetzt:** Wird von Schritt 3–7 genutzt. Muss existieren, bevor `nodeResults` umgestellt wird.

**Testbar:** Unit-Test: Outputs setzen/lesen, State setzen/lesen, `null`-Handling. Keine Abhängigkeit zu WorkflowExecutor.

**Aufwand:** Gering. **Risiko:** Minimal.

---

## Schritt 3 — `EngineResult.structured()` nutzen (bereits vorhanden)

**Was:** `QocEngine.runPrompt()` liefert bereits `EngineResult` mit einem `structured`-Feld (`Option<JsonValue>`). Dieses Feld muss in `NodeDispatcher` genutzt werden, um strukturierte Outputs an `WorkflowContext` weiterzugeben. Es muss **nicht** neu implementiert werden — nur genutzt.

```java
// EngineResult.java (bereits vorhanden in engine-Modul)
public record EngineResult(
    String text,
    String finish,
    long inputTokens,
    long outputTokens,
    boolean failed,
    String errorMessage,
    boolean retryable,
    boolean paused,
    String approvalId,
    Option<JsonValue> structured   // BEREITS VORHANDEN
) { ... }
```

`JsonValue` ist ein sealed interface: `Obj(Map<String, JsonValue>)`, `Arr(List<JsonValue>)`, `Str(String)`, `Num(BigDecimal)`, `Bool(boolean)`, `Null()`.

Wenn `AgentConfig.jsonOutput == true` und das LLM eine valide JSON-Antwort liefert, wird diese als `JsonValue.Obj` in `structured` gesetzt. Sonst `Option.none()`.

**Konvertierung zu Map:** Im `NodeDispatcher` muss `Option<JsonValue>` zu `Map<String, Object>` konvertiert werden:

```java
private Map<String, Object> toMap(Option<JsonValue> structured) {
    if (structured.isEmpty()) return null;
    JsonValue val = structured.orElseThrow();
    if (val instanceof JsonValue.Obj obj) {
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, JsonValue> e : obj.values().entrySet()) {
            map.put(e.getKey(), toJavaObject(e.getValue()));
        }
        return map;
    }
    return null;
}

private Object toJavaObject(JsonValue val) {
    return switch (val) {
        case JsonValue.Str s -> s.value();
        case JsonValue.Num n -> n.value();
        case JsonValue.Bool b -> b.value();
        case JsonValue.Null n -> null;
        case JsonValue.Arr a -> a.values().stream().map(this::toJavaObject).toList();
        case JsonValue.Obj o -> {
            Map<String, Object> m = new HashMap<>();
            for (Map.Entry<String, JsonValue> e : o.values().entrySet()) {
                m.put(e.getKey(), toJavaObject(e.getValue()));
            }
            yield m;
        }
    };
}
```

**Warum jetzt:** Voraussetzung für Schritt 4 (Agent-Node liefert strukturierten Output). Kann unabhängig vom WorkflowExecutor implementiert und getestet werden.

**Testbar:**
- Unit-Test mit Mock-Provider: LLM liefert `{"status":"SUCCESS","data":"..."}` → `structured()` enthält `JsonValue.Obj`, Konvertierung liefert Map.
- LLM liefert invalides JSON → `structured()` ist `Option.none()`, `.text()` enthält Rohantwort.
- `jsonOutput=false` → `structured()` ist `Option.none()`.

**Aufwand:** Mittel. **Risiko:** Mittel (Provider-Adapter muss JSON-Mode unterstützen; Fallback bei Parse-Fehler).

**Abhängigkeit:** Keine (QocEngine ist unabhängig vom WorkflowExecutor).

---

## Schritt 4 — `WorkflowExecutor` auf `WorkflowContext` umstellen (String → Object)

**Was:** `nodeResults: Map<String, String>` → `nodeResults: Map<String, Object>` (oder `Map<String, WorkflowNodeResult>`). Agent-Node speichert nicht nur `.text()` sondern auch `.structured()` (via `toMap()`) im Kontext.

```java
// Neues Record
public record NodeResult(String text, Map<String, Object> structured) {
    public String text() { return text; }
    public Object get(String key) { return structured != null ? structured.get(key) : null; }
}
```

Agent-Node:
```java
EngineResult result = engine.runPrompt(prefixedName, nodeInput, config);
Map<String, Object> structured = toMap(result.structured());
NodeResult nodeResult = new NodeResult(result.text(), structured);
nodeResults.put(nodeId, nodeResult);
ctx.setNodeOutput(nodeId, structured != null ? structured : result.text());
```

**Warum jetzt:** Baut auf Schritt 2 + 3. Macht strukturierte Outputs im Workflow verfügbar.

**Testbar:**
- Bestehender E2E-Test muss weiterhin grün sein (Text-Pfad unverändert).
- Neuer Unit-Test: Agent-Node mit `jsonOutput=true` → `nodeResults.get(nodeId)` ist `NodeResult` mit `.structured()` Map.
- Neuer Unit-Test: Agent-Node mit `jsonOutput=false` → `.structured()` ist `null`, `.text()` enthält Antwort.

**Aufwand:** Mittel. **Risiko:** Mittel (bestehende Tests müssen angepasst werden, da `nodeResults` jetzt `Object` statt `String` ist).

**Abhängigkeit:** Schritt 2 (WorkflowContext), Schritt 3 (`EngineResult.structured()` nutzen).

---

## Schritt 5 — Template-Resolver (Data-Piping) im WorkflowExecutor

**Was:** Vor Ausführung eines Agent-Nodes: `nodeInput` und Parameter durch `resolveTemplate()` auflösen.

```java
private Object resolveTemplate(String template, WorkflowContext ctx) {
    // Pattern: ${node_id.output} oder ${node_id.output.field} oder ${node_id.output.nested.path}
    Pattern p = Pattern.compile("\\$\\{([a-zA-Z0-9_]+)\\.output(\\.([a-zA-Z0-9_.]+))?\\}");
    Matcher m = p.matcher(template);
    if (m.find()) {
        String targetNodeId = m.group(1);
        String propertyPath = m.group(3);
        Object previousOutput = ctx.getNodeOutput(targetNodeId);
        if (previousOutput == null) return null;
        if (propertyPath == null || propertyPath.isEmpty()) return previousOutput;
        return resolveNestedProperty(previousOutput, propertyPath);
    }
    return template;
}

private Object resolveNestedProperty(Object target, String path) {
    String[] parts = path.split("\\.");
    Object current = target;
    for (String part : parts) {
        if (current instanceof Map) {
            current = ((Map<?, ?>) current).get(part);
            if (current == null) return null;
        } else {
            return null;
        }
    }
    return current;
}
```

**Warum jetzt:** Baut auf Schritt 4 (Node-Outputs im Kontext). Kann als eigenständige Utility-Klasse implementiert und getestet werden, bevor der WorkflowExecutor sie nutzt.

**Testbar:**
- Unit-Test `TemplateResolverTest`: `${node_1.output}` → ganzer Output, `${node_1.output.status}` → Feld, `${node_1.output.user.email}` → verschachtelt, fehlender Node → `null`, kein Platzhalter → Original-String.
- Integration: Workflow mit 2 Agent-Nodes, wobei Node 2 `${node_1.output.data}` als Input nutzt.

**Aufwand:** Gering–Mittel. **Risiko:** Gering (reine String-Verarbeitung, isolierbar).

**Abhängigkeit:** Schritt 4 (WorkflowContext mit nodeOutputs).

---

## Schritt 6 — `ConditionEvaluator` auf `WorkflowContext` umstellen

**Was:** `ConditionEvaluator.matches(String condition, String context)` → `ConditionEvaluator.matches(String condition, WorkflowContext ctx)`.

Neue Bedingungssyntax:
- `contains:arg` → wie bisher (auf String-Kontext)
- `status == 'SUCCESS'` → prüft `ctx.getState("lastStatus")` oder `ctx.getNodeOutput(nodeId)`
- `node_1.output.classification == 'VIP_ORDER'` → prüft strukturierten Output

**Warum jetzt:** Baut auf Schritt 2 + 4 (WorkflowContext + Node-Outputs). Kann parallel zu Schritt 5 entwickelt werden (keine Abhängigkeit).

**Testbar:**
- Unit-Test: `status == 'SUCCESS'` mit `ctx.setState("lastStatus", "SUCCESS")` → true.
- Unit-Test: `node_1.output.classification == 'VIP_ORDER'` mit `ctx.setNodeOutput("node_1", Map.of("classification", "VIP_ORDER"))` → true.
- Rückwärtskompatibilität: `contains:foo` mit String-Kontext → true (Fallback).

**Aufwand:** Mittel. **Risiko:** Mittel (bestehende ConditionEvaluator-Tests müssen angepasst werden).

**Abhängigkeit:** Schritt 2 (WorkflowContext).

---

## Schritt 7 — LLM-Node-Typen (`llm_eval`, `llm_summarize`)

**Was:** Neue Node-Typen im `WorkflowExecutor`-Switch:

```java
case "llm_eval" -> {
    String prompt = resolveTemplate(node.data().title(), ctx); // oder node.data().exitCondition()
    // ... prompt aus node.data
    EngineResult result = engine.runPrompt(prefixedName, prompt, config);
    // Strukturiertes Ergebnis parsen
    Map<String, Object> structured = toMap(result.structured());
    ctx.setNodeOutput(nodeId, structured != null ? structured : result.text());
    ctx.setState("lastStatus", structured != null ? structured.get("status") : "SUCCESS");
    nodeResults.put(nodeId, new NodeResult(result.text(), structured));
}
case "llm_summarize" -> {
    // Sammle Daten aus Vorgängern via ${...}
    String inputData = resolveTemplate(node.data().defaultTarget(), ctx); // oder spezifisches Feld
    String prompt = "Fasse zusammen: " + inputData;
    EngineResult result = engine.runPrompt(prefixedName, prompt, config);
    ctx.setNodeOutput(nodeId, result.text());
    nodeResults.put(nodeId, new NodeResult(result.text(), null));
}
```

**Warum jetzt:** Baut auf Schritt 3–6 (EngineResult.structured, WorkflowContext, Template-Resolver, ConditionEvaluator). Kann erst implementiert werden, wenn die vorherigen Schritte stehen.

**Testbar:**
- Unit-Test mit Mock-Engine: `llm_eval`-Node → `ctx.getNodeOutput(nodeId)` enthält Map.
- Integration: Workflow mit `llm_eval`-Node gefolgt von Decision-Node mit Edge-Bedingung auf `node_eval.output.classification`.

**Aufwand:** Gering–Mittel. **Risiko:** Gering (nutzt bestehende Infrastruktur).

**Abhängigkeit:** Schritt 3, 4, 5, 6.

---

## Schritt 8 — Fork/Join implementieren

**Was:**
- **Fork**: Parallele Ausführung mehrerer Branches. In synchronem Modus: sequentiell mit separaten Kontexten, Ergebnisse sammeln.
- **Join**: Warten auf alle eingehenden Branches, Ergebnisse mergen (z. B. in eine Map oder Liste).

```java
case "fork" -> {
    List<String> targets = TopologicalSort.outgoingTargets(def, nodeId);
    Map<String, Object> forkResults = new HashMap<>();
    for (String target : targets) {
        forkResults.put(target, "forked:" + target);
    }
    ctx.setState("forkResults", forkResults);
    nodeResults.put(nodeId, new NodeResult("forked", forkResults));
}
case "join" -> {
    List<String> sources = TopologicalSort.incomingSources(def, nodeId);
    Map<String, Object> merged = new HashMap<>();
    for (String src : sources) {
        merged.put(src, ctx.getNodeOutput(src));
    }
    ctx.setNodeOutput(nodeId, merged);
    nodeResults.put(nodeId, new NodeResult("joined", merged));
}
```

**Warum jetzt:** Baut auf Schritt 4 (WorkflowContext). Kann parallel zu Schritt 7 entwickelt werden.

**Testbar:**
- Unit-Test: Fork mit 2 Branches → beide ausgeführt, Ergebnisse in `forkResults`.
- Unit-Test: Join mit 2 eingehenden Nodes → merged Map enthält beide.

**Aufwand:** Mittel–Hoch. **Risiko:** Mittel (Parallelisierung in synchronem Modus ist vereinfacht; in Temporal echte Parallelität).

**Abhängigkeit:** Schritt 4 (WorkflowContext).

---

## Schritt 9 — Loop implementieren (Body-Steps)

**Was:** Loop-Node führt `bodySteps` (Liste von Node-IDs) wiederholt aus, bis `exitCondition` erfüllt ist oder `maxIterations` erreicht.

```java
case "loop" -> {
    int maxIter = node.data().maxIterations();
    String exitCond = node.data().exitCondition();
    List<String> body = node.data().bodySteps();
    for (int i = 0; i < maxIter; i++) {
        for (String bodyNodeId : body) {
            // Ausführen des Body-Nodes (rekursiv oder inline)
        }
        if (exitCond != null && !exitCond.isBlank() && ConditionEvaluator.matches(exitCond, ctx)) {
            break;
        }
    }
    nodeResults.put(nodeId, new NodeResult("loop completed", null));
}
```

**Warum jetzt:** Baut auf Schritt 6 (ConditionEvaluator auf WorkflowContext) und Schritt 8 (Dispatch-Refactoring für rekursive Ausführung).

**Testbar:**
- Unit-Test: Loop mit 3 Iterationen, Exit-Condition nach 2. Iteration → bricht nach 2 ab.
- Unit-Test: Loop ohne Exit-Condition → läuft maxIterations durch.

**Aufwand:** Mittel. **Risiko:** Mittel (rekursive Ausführung kann komplex werden).

**Abhängigkeit:** Schritt 4, 6, 8.

---

## Schritt 10 — Subworkflow-Expansion

**Was:** `SubWorkflowExpander` expandiert Subworkflow-Nodes vor der Ausführung in den Hauptgraphen. Agent-Configs werden gemergt.

```java
public class SubWorkflowExpander {
    public static WorkflowDefinition expand(WorkflowDefinition def, WorkflowStore store) {
        // Für jeden subworkflow-Node: lade referenzierten Workflow,
        // merge Nodes/Edges/Agents in den Hauptgraphen
    }
}
```

**Warum jetzt:** Baut auf Schritt 1 (Edge-Condition) und Schritt 4 (WorkflowContext). Sollte nach den Kern-Features implementiert werden.

**Testbar:**
- Unit-Test: Workflow mit Subworkflow-Node → expandiert zu flachem Graphen mit allen Nodes.
- Unit-Test: Agent-Configs des Subworkflows werden in `agents[]` gemergt.

**Aufwand:** Mittel–Hoch. **Risiko:** Mittel.

**Abhängigkeit:** Schritt 1, 4.

---

## Schritt 12 — Frontend: Edge-Conditions + Data-Piping-UI

**Was:**
- `PropertyPanel`: Edge-Condition-Editor (z. B. Dropdown für Operatoren + Wert).
- `GraphEditor`: Data-Piping-Hints in Node-Properties (z. B. `${node_1.output.status}`).
- `toBackend()`/`fromBackend()`: `condition`-Feld auf Edges serialisieren.

**Warum jetzt:** Baut auf Schritt 1 (Edge.condition im Datenmodell). Kann parallel zu Backend-Schritten entwickelt werden.

**Testbar:**
- Playwright E2E: Edge mit Condition erstellen, speichern, neu laden → Condition erhalten.
- Playwright E2E: Data-Piping-Platzhalter eingeben, speichern, neu laden → erhalten.

**Aufwand:** Mittel. **Risiko:** Gering.

**Abhängigkeit:** Schritt 1.

---

## Abhängigkeitsgraph

```
Schritt 0 (Tests grün)
  │
  ├── Schritt 1 (Edge.condition) ──────────────────────┐
  │                                                    │
  ├── Schritt 2 (WorkflowContext) ──┐                  │
  │                                 │                  │
  ├── Schritt 3 (EngineResult.structured nutzen) ──┐    │
  │                                               │    │
  │   ┌───────────────────────────────────────────┘    │
  │   │                                                │
  │   ├── Schritt 4 (Executor → WorkflowContext) ──┐   │
  │   │                                            │   │
  │   ├── Schritt 5 (Template-Resolver) ──────────┤   │
  │   │                                            │   │
  │   └── Schritt 6 (ConditionEvaluator → State) ─┤   │
  │                                                │   │
  │   ┌────────────────────────────────────────────┘   │
  │   │                                                │
  │   ├── Schritt 7 (LLM-Node-Typen) ──────────────┤   │
  │   │                                            │   │
  │   ├── Schritt 8 (Fork/Join) ──────────────────┤   │
  │   │                                            │   │
  │   └── Schritt 9 (Loop Body-Steps) ────────────┤   │
  │                                                │   │
  │   ┌────────────────────────────────────────────┘   │
  │   │                                                │
  │   └── Schritt 10 (Subworkflow-Expansion) ──────┘   │
  │                                                    │
  └── Schritt 12 (Frontend: Edge-Cond + Data-Piping) ──┘
```

---

## Empfohlene Sprint-Planung

| Sprint | Schritte | Ziel |
|---|---|---|
| **Sprint 1** | 0, 1, 2, 3 | Datenmodell + Kontext + Structured Output (isoliert testbar) |
| **Sprint 2** | 4, 5, 6 | Executor auf State umstellen + Data-Piping + Conditions (Kern-Integration) |
| **Sprint 3** | 7, 8, 9 | LLM-Nodes + Fork/Join + Loop (Workflow-Semantik vervollständigen) |
| **Sprint 4** | 10, 12 | Subworkflow + Frontend (End-to-End) |

---

## Minimaler "Quick Win" (1–2 Tage)

Wenn nur ein kleiner Schritt möglich ist, empfehle ich **Schritt 1 + 5**:
- `Edge.condition` hinzufügen (Datenmodell)
- Template-Resolver als Utility-Klasse implementieren + Unit-Tests

Das bringt sofort Data-Piping-Fähigkeit (`${node_1.output}`) in den WorkflowExecutor, ohne die bestehende String-basierte Ausführung zu brechen (der Resolver gibt bei fehlendem Platzhalter den Original-String zurück).

---

## Teststrategie pro Schritt

| Schritt | Testart | Testdatei (neu/erweitert) |
|---|---|---|
| 0 | Regression | Bestehende Tests |
| 1 | Unit | `WorkflowDefinitionTest` |
| 2 | Unit | `WorkflowContextTest` |
| 3 | Unit + Mock | `QocEngineStructuredOutputTest` |
| 4 | Unit + Integration | `WorkflowExecutorTest` |
| 5 | Unit | `TemplateResolverTest` |
| 6 | Unit | `ConditionEvaluatorTest` |
| 7 | Unit + Integration | `WorkflowExecutorLlmNodeTest` |
| 8 | Unit | `ForkJoinTest` |
| 9 | Unit | `LoopNodeTest` |
| 10 | Unit | `SubWorkflowExpanderTest` |
| 12 | E2E (Playwright) | `e2e-workflow-hybrid.spec.ts` |

---

---

## TODO — Temporal-bezogene Aufgaben (später, als optionale Schicht)

Die folgenden Aufgaben wurden aus dem Implementierungsweg entfernt und werden
in einem späteren Schritt (nach stabiler synchroner Ausführung) hinzugefügt.
Sie können über DB-Tracing und eigene Komponenten implementiert werden.

- **DB-Tracing / Event-History:** Workflow-Events (Node gestartet, Node beendet, Fehler) in die Datenbank schreiben, um Ausführungen nachvollziehbar zu machen und Crash-Recovery zu ermöglichen.
- **Crash-Recovery / Checkpoint-Resume:** Letzten Checkpoint aus DB laden und Workflow ab dort fortsetzen (ohne Temporal-Replay).
- **HITL-Signals:** Asynchrones Warten auf Human-Input — Node pausiert, Run-Status = `paused`, UI zeigt Approval-Dialog, bei Approval → Resume.
- **Saga / Kompensation:** Rollback-Kette bei Fehlern — Kompensations-Logik in Nodes definieren (z. B. `compensate`-Callback).
- **Durable Execution Wrapper:** Temporal (oder eigene Queue) als optionale Ausführungs-Schicht um die Engine wickeln — Engine bleibt Temporal-frei, Temporal übernimmt nur Durability.
- **Multi-Node-Skalierung:** Verteilte Ausführung über mehrere Worker (nur bei Bedarf).
- **Temporal-UI-Integration:** Run-History und Event-Inspection in der UI (optional, kann durch eigene Run-Views ersetzt werden).

---

## Referenzen

- Architektur-Dokument: `qoc/docs/engineering/ReAct-Workflow.md`
- Original-Konversation: `qoc/docs/ReAct.txt`
- QocEngine: `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`
- WorkflowExecutor: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowExecutor.java`
- WorkflowDefinition: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/model/WorkflowDefinition.java`
- TopologicalSort: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/TopologicalSort.java`
- ConditionEvaluator: `qoc/workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/ConditionEvaluator.java`


---

## Runner → getNextStep Transformation (Analyse)

### Welche Runner-Funktionen sind noch nötig?

| Komponente | Noch nötig? | Begründung |
|---|---|---|
| **TopologicalSort** | ⚠️ Teilweise | `sort()` wird durch getNextStep ersetzt. Aber `incomingSources()` und `outgoingTargets()` werden von getNextStep genutzt. Cycle-Detection bleibt für Validierung. |
| **ConditionEvaluator** | ✅ Ja, aber erweitern | Aktuell String-basiert (`contains:`, `equals:`). Muss auf `WorkflowContext` umgestellt werden (State-basierte Conditions wie `status == 'SUCCESS'`). |
| **SubWorkflowExpander** | ✅ Ja | Flattet Sub-Workflows vor der Ausführung. Unabhängig von der Execution-Strategie. |
| **GraphCompiler** | ✅ Ja | Validierung (Node-Typen, Edges, Agent-Refs, Cycles). Unabhängig. |
| **WorkflowStore** | ✅ Ja | JSON-Persistenz. Unabhängig. |
| **WorkflowExecutor** | ❌ Ersetzen | Wird durch `NextStepProvider` + `WorkflowLoop` ersetzt. |
| **RunAgentActivity** | ❌ Entfernt | Temporal entfernt. |
| **ReactFlowWorkflowImpl** | ❌ Entfernt | Temporal entfernt. |

### Warum TopologicalSort nicht ausreicht

Der aktuelle `WorkflowExecutor` nutzt `TopologicalSort.sort()`:

```java
List<String> order = TopologicalSort.sort(def);  // Linear, DAG-only
for (String nodeId : order) { ... }  // Sequentiell, keine Verzweigung
```

**Probleme:**
1. **Keine Verzweigung:** TopologicalSort liefert eine lineare Reihenfolge. Bei Decision-Nodes (mehrere ausgehende Edges) werden alle Nachfolger in die Reihenfolge aufgenommen — auch die, die nicht gewählt werden.
2. **Keine Loops:** TopologicalSort erkennt Cycles als Fehler. Aber Loop-Nodes (bodySteps + exitCondition) sind explizit Cycles.
3. **Keine dynamische Pfadwahl:** Der Kontext (z. B. Ergebnis eines Agent-Nodes) beeinflusst, welcher Pfad genommen wird. TopologicalSort berücksichtigt das nicht.

### NextStepProvider — Die neue Kernklasse

`NextStepProvider` ersetzt `WorkflowExecutor` und `TopologicalSort.sort()`. Er liest den Workflow-Graphen und den `WorkflowContext` und bestimmt den nächsten Node.

#### Interface

```java
public interface NextStepProvider {
    String getNextNodeId(WorkflowDefinition def, WorkflowContext ctx);
    List<String> getOutgoingTargets(WorkflowDefinition def, String nodeId);
    List<String> getIncomingSources(WorkflowDefinition def, String nodeId);
}
```

#### Implementierung (GraphNextStepProvider)

```java
public class GraphNextStepProvider implements NextStepProvider {

    @Override
    public String getNextNodeId(WorkflowDefinition def, WorkflowContext ctx) {
        String currentId = ctx.getCurrentNodeId();

        // Fall 1: Workflow-Start
        if (currentId == null) {
            String startNodeId = findStartNode(def);
            ctx.setCurrentNodeId(startNodeId);
            return startNodeId;
        }

        // Fall 2: Finde ausgehende Edges
        List<WorkflowDefinition.Edge> outgoing = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(currentId)) outgoing.add(e);
        }

        if (outgoing.isEmpty()) return "DONE";

        // Fall 3: Linearer Pfad
        if (outgoing.size() == 1) {
            String target = outgoing.get(0).target();
            ctx.setCurrentNodeId(target);
            return target;
        }

        // Fall 4: Verzweigung — prüfe Edge-Conditions
        for (WorkflowDefinition.Edge e : outgoing) {
            if (e.condition() != null && !e.condition().isBlank()
                    && ConditionEvaluator.matches(e.condition(), ctx)) {
                ctx.setCurrentNodeId(e.target());
                return e.target();
            }
        }

        // Fallback: defaultTarget des Nodes oder erste Edge
        WorkflowDefinition.Node currentNode = findNode(def, currentId);
        if (currentNode != null && currentNode.data() != null
                && currentNode.data().defaultTarget() != null
                && !currentNode.data().defaultTarget().isBlank()) {
            String target = currentNode.data().defaultTarget();
            ctx.setCurrentNodeId(target);
            return target;
        }

        String target = outgoing.get(0).target();
        ctx.setCurrentNodeId(target);
        return target;
    }

    @Override
    public List<String> getOutgoingTargets(WorkflowDefinition def, String nodeId) {
        List<String> targets = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.source().equals(nodeId)) targets.add(e.target());
        }
        return targets;
    }

    @Override
    public List<String> getIncomingSources(WorkflowDefinition def, String nodeId) {
        List<String> sources = new ArrayList<>();
        for (WorkflowDefinition.Edge e : def.edges()) {
            if (e.target().equals(nodeId)) sources.add(e.source());
        }
        return sources;
    }

    private String findStartNode(WorkflowDefinition def) {
        Set<String> targets = new HashSet<>();
        for (WorkflowDefinition.Edge e : def.edges()) targets.add(e.target());
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (!targets.contains(n.id())) return n.id();
        }
        return def.nodes().isEmpty() ? null : def.nodes().get(0).id();
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}
```

#### Was NextStepProvider aus dem Runner übernimmt

| Runner-Funktion | NextStepProvider-Äquivalent |
|---|---|
| `TopologicalSort.sort()` | `getNextNodeId()` — navigiert dynamisch statt linear |
| `TopologicalSort.incomingSources()` | `getIncomingSources()` — für Join-Nodes |
| `TopologicalSort.outgoingTargets()` | `getOutgoingTargets()` — für Fork-Nodes |
| `ConditionEvaluator.matches()` | Erweitert für `WorkflowContext` |
| `WorkflowExecutor` Dispatch-Logik | Bleibt im `WorkflowLoop` (Node-Ausführung) |

### Ansatz: QocEngine-Loop erweitern (kein separater WorkflowLoop)

**Entscheidung:** Statt eines separaten `WorkflowLoop` wird der bestehende `QocEngine.runPrompt()`-Loop erweitert. Der Loop wird **session-typabhängig**:
- `sessionType == "agent"` → LLM-Calls (bestehend, unverändert)
- `sessionType == "workflow"` → deterministische `getNextStep()`-Aufrufe

**Warum:** QocEngine hat bereits einen vollständigen Agent-Loop (while-Schleife, Retry, Loop-Detection, Compaction, Turn-Policy, Resilience). Diesen neu zu implementieren würde Duplikation und Inkonsistenz erzeugen.

#### Erweiterung von QocEngine.runPrompt()

Neue optionale Parameter: `WorkflowDefinition workflowDef` und `WorkflowContext workflowCtx`. Wenn beide nicht null sind, läuft der Loop im **Workflow-Mode**.

Im Workflow-Mode:
1. `getNextStep(workflowDef, workflowCtx)` wird aufgerufen statt `step.run()`
2. getNextStep liefert eine `AssistantMessage` mit `ToolCall(s)` (subagentTool)
3. Die Tool-Calls werden wie bisher ausgeführt (ToolRuntime oder NodeDispatcher)
4. Observations werden in den WorkflowContext geschrieben
5. Bei `isFinished == true` bricht der Loop ab

#### getNextStep als Methode

```java
public AssistantMessage getNextStep(WorkflowDefinition def, WorkflowContext ctx) {
    String currentId = ctx.getCurrentNodeId();

    if (currentId == null) {
        String startNodeId = findStartNode(def);
        ctx.setCurrentNodeId(startNodeId);
        return createSubagentToolCall(startNodeId, def, ctx);
    }

    List<Edge> outgoing = findOutgoingEdges(def, currentId);
    if (outgoing.isEmpty()) {
        return new AssistantMessage("Workflow beendet.", List.of(), true);
    }

    if (outgoing.size() == 1) {
        String target = outgoing.get(0).target();
        ctx.setCurrentNodeId(target);
        return createSubagentToolCall(target, def, ctx);
    }

    for (Edge e : outgoing) {
        if (e.condition() != null && !e.condition().isBlank()
                && ConditionEvaluator.matches(e.condition(), ctx)) {
            ctx.setCurrentNodeId(e.target());
            return createSubagentToolCall(e.target(), def, ctx);
        }
    }

    String target = outgoing.get(0).target();
    ctx.setCurrentNodeId(target);
    return createSubagentToolCall(target, def, ctx);
}
```

#### createSubagentToolCall

```java
private AssistantMessage createSubagentToolCall(String nodeId, WorkflowDefinition def, WorkflowContext ctx) {
    WorkflowDefinition.Node node = findNode(def, nodeId);
    Map<String, Object> args = new HashMap<>();
    if (node.data() != null) {
        for (Map.Entry<String, Object> e : node.data().entrySet()) {
            if (e.getValue() instanceof String) {
                args.put(e.getKey(), TemplateResolver.resolve((String) e.getValue(), ctx));
            } else {
                args.put(e.getKey(), e.getValue());
            }
        }
    }
    args.put("nodeType", node.type());
    args.put("nodeId", nodeId);

    return new AssistantMessage(
        "Führe Workflow-Node [" + nodeId + "] aus (Typ: " + node.type() + ")",
        List.of(new ToolCall("call_" + nodeId, "subagentTool", args)),
        false
    );
}
```

#### NodeDispatcher als subagentTool-Handler

Der `NodeDispatcher` wird als Handler für das `subagentTool` registriert. Wenn der Loop einen `subagentTool`-Call ausführt, delegiert er an den NodeDispatcher, der basierend auf `nodeType` die richtige Logik ausführt.

#### Vorteile

1. **Keine Duplikation:** Bestehende Resilience (Retry, Loop-Detection, Compaction, Turn-Policy, Preflight, Webhook) gilt für beide Modi.
2. **Einheitlicher Loop:** Ein Loop für Agent-Mode und Workflow-Mode.
3. **ReAct-kompatibel:** getNextStep gibt AssistantMessage (mit ToolCall) zurück — exakt wie im ReAct-Pattern.
4. **SubagentTool als universeller Ausführer:** Jeder Node wird als subagentTool-Call ausgeführt.

#### QocEngine-Änderungen

| Änderung | Beschreibung |
|---|---|
| `runPrompt()` Signatur | Neue optionale Parameter: `WorkflowDefinition workflowDef`, `WorkflowContext workflowCtx` |
| Loop-Branch | `if (workflowDef != null)` → getNextStep-Pfad, sonst LLM-Pfad |
| getNextStep() | Neue Methode in QocEngine (oder als Callback/Strategy) |
| WorkflowContext | Muss an QocEngine übergeben werden |
| NodeDispatcher | Wird als Tool-Handler für subagentTool genutzt |

```java
public class WorkflowLoop {

    private final NextStepProvider nextStep;
    private final NodeDispatcher dispatcher;
    private final WorkflowContext ctx;
    private final WorkflowDefinition def;

    public WorkflowLoop(WorkflowDefinition def, WorkflowContext ctx,
                        NextStepProvider nextStep, NodeDispatcher dispatcher) {
        this.def = def;
        this.ctx = ctx;
        this.nextStep = nextStep;
        this.dispatcher = dispatcher;
    }

    public NodeResult run(String input) {
        ctx.setCurrentNodeId(null);
        String lastResult = input == null ? "" : input;

        int maxSteps = 100;
        int step = 0;

        while (step < maxSteps) {
            step++;
            String nodeId = nextStep.getNextNodeId(def, ctx);

            if ("DONE".equals(nodeId)) break;

            WorkflowDefinition.Node node = findNode(def, nodeId);
            if (node == null) throw new IllegalStateException("Node not found: " + nodeId);

            NodeResult result = dispatcher.dispatch(node, def, ctx, lastResult);
            ctx.setNodeOutput(nodeId, result.structured() != null ? result.structured() : result.text());
            lastResult = result.text();

            ctx.setState("lastResult", lastResult);
            if (result.structured() != null && result.structured().containsKey("status")) {
                ctx.setState("lastStatus", result.structured().get("status"));
            }

            // Loop-Node: bodySteps abarbeiten
            if ("loop".equals(node.type())) {
                int maxIter = node.data().maxIterations();
                String exitCond = node.data().exitCondition();
                List<String> body = node.data().bodySteps();
                for (int i = 0; i < maxIter; i++) {
                    for (String bodyNodeId : body) {
                        WorkflowDefinition.Node bodyNode = findNode(def, bodyNodeId);
                        if (bodyNode != null) {
                            NodeResult bodyResult = dispatcher.dispatch(bodyNode, def, ctx, lastResult);
                            ctx.setNodeOutput(bodyNodeId, bodyResult.structured() != null ? bodyResult.structured() : bodyResult.text());
                            lastResult = bodyResult.text();
                        }
                    }
                    if (exitCond != null && !exitCond.isBlank()
                            && ConditionEvaluator.matches(exitCond, ctx)) {
                        break;
                    }
                }
            }

            // Fork-Node: parallele Branches
            if ("fork".equals(node.type())) {
                List<String> targets = nextStep.getOutgoingTargets(def, nodeId);
                Map<String, Object> forkResults = new HashMap<>();
                for (String target : targets) {
                    WorkflowDefinition.Node forkNode = findNode(def, target);
                    if (forkNode != null) {
                        NodeResult forkResult = dispatcher.dispatch(forkNode, def, ctx, lastResult);
                        forkResults.put(target, forkResult.structured() != null ? forkResult.structured() : forkResult.text());
                    }
                }
                ctx.setState("forkResults", forkResults);
            }
        }

        if (step >= maxSteps) {
            throw new IllegalStateException("Workflow exceeded max steps (" + maxSteps + ")");
        }

        String finalResult = lastResult;
        if (finalResult.isBlank()) {
            finalResult = ctx.getNodeOutputs().isEmpty() ? "(no result)"
                    : ctx.getNodeOutputs().values().stream()
                            .reduce((a, b) -> b).map(Object::toString).orElse("(no result)");
        }
        return new NodeResult(finalResult, null);
    }

    private WorkflowDefinition.Node findNode(WorkflowDefinition def, String id) {
        for (WorkflowDefinition.Node n : def.nodes()) {
            if (n.id().equals(id)) return n;
        }
        return null;
    }
}
```

### NodeDispatcher — Node-Ausführung (extrahiert aus WorkflowExecutor)

```java
public interface NodeDispatcher {
    NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                       WorkflowContext ctx, String input);
}

public class DefaultNodeDispatcher implements NodeDispatcher {

    private final QocEngine engine;
    private final WorkflowStore store;
    private final SubWorkflowExpander expander;

    public DefaultNodeDispatcher(QocEngine engine, WorkflowStore store) {
        this.engine = engine;
        this.store = store;
        this.expander = new SubWorkflowExpander(store);
    }

    @Override
    public NodeResult dispatch(WorkflowDefinition.Node node, WorkflowDefinition def,
                              WorkflowContext ctx, String input) {
        String type = node.type();
        switch (type) {
            case "agent" -> {
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

                QocEngine.EngineResult result = engine.runPrompt(prefixedName, nodeInput, config);
                Map<String, Object> structured = toMap(result.structured());
                return new NodeResult(result.text(), structured);
            }
            case "decision" -> {
                String ctxStr = ctx.getState("lastResult") != null
                        ? ctx.getState("lastResult").toString() : input;
                List<WorkflowDefinition.Branch> branches = node.data().branches();
                String target = node.data().defaultTarget();
                for (WorkflowDefinition.Branch b : branches) {
                    if (ConditionEvaluator.matches(b.condition(), ctx)) {
                        target = b.target();
                        break;
                    }
                }
                ctx.setNodeOutput(node.id(), "decision -> " + target);
                return new NodeResult("decision -> " + target, Map.of("target", target));
            }
            case "start" -> {
                ctx.setNodeOutput(node.id(), "started");
                return new NodeResult("started", null);
            }
            case "hitl" -> {
                ctx.setNodeOutput(node.id(), "approved");
                return new NodeResult("approved", null);
            }
            case "subworkflow" -> {
                String ref = node.data().workflowRef();
                WorkflowDefinition subWf = store.get(ref);
                if (subWf == null) {
                    return new NodeResult("subworkflow not found: " + ref, null);
                }
                WorkflowDefinition expanded = expander.expand(subWf);
                WorkflowContext subCtx = new WorkflowContext();
                WorkflowLoop subLoop = new WorkflowLoop(expanded, subCtx, new GraphNextStepProvider(), this);
                NodeResult subResult = subLoop.run(input);
                ctx.setNodeOutput(node.id(), subResult.text());
                return new NodeResult("subworkflow:" + ref + " → " + subResult.text(), subResult.structured());
            }
            case "end" -> {
                ctx.setNodeOutput(node.id(), "done");
                return new NodeResult("done", null);
            }
            default -> {
                ctx.setNodeOutput(node.id(), "skipped (" + type + ")");
                return new NodeResult("skipped (" + type + ")", null);
            }
        }
    }

    // ... prefixedName, toAgentConfig, findAgent ...
}
```

### ConditionEvaluator — Erweitert für WorkflowContext

```java
public final class ConditionEvaluator {

    private ConditionEvaluator() {}

    public static boolean matches(String condition, WorkflowContext ctx) {
        if (condition == null || condition.isBlank()) return true;

        String c = condition.trim();

        // State-basierte Conditions: "status == 'SUCCESS'"
        if (c.contains("==")) {
            String[] parts = c.split("==");
            String left = parts[0].trim();
            String right = parts[1].trim().replace("'", "").replace("\"", "");

            if (left.equals("status") || left.equals("lastStatus")) {
                Object val = ctx.getState("lastStatus");
                return right.equals(String.valueOf(val));
            }

            // Node-Output-basiert: "node_1.output.classification == 'VIP_ORDER'"
            if (left.startsWith("node_") && left.contains(".output")) {
                String[] pathParts = left.split("\\.");
                String nodeId = pathParts[0];
                String field = pathParts.length > 2 ? pathParts[2] : null;

                Object output = ctx.getNodeOutput(nodeId);
                if (output instanceof Map) {
                    Object val = field != null ? ((Map<?, ?>) output).get(field) : output;
                    return right.equals(String.valueOf(val));
                }
                return right.equals(String.valueOf(output));
            }
        }

        // Legacy String-basierte Conditions (Fallback)
        String ctxStr = ctx.getState("lastResult") != null
                ? ctx.getState("lastResult").toString() : "";
        int colon = c.indexOf(':');
        String op = (colon < 0 ? c : c.substring(0, colon)).trim().toLowerCase();
        String arg = colon < 0 ? "" : c.substring(colon + 1);
        return switch (op) {
            case "contains" -> ctxStr.toLowerCase().contains(arg.toLowerCase());
            case "notcontains" -> !ctxStr.toLowerCase().contains(arg.toLowerCase());
            case "starts" -> ctxStr.trim().toLowerCase().startsWith(arg.trim().toLowerCase());
            case "equals" -> ctxStr.trim().equalsIgnoreCase(arg.trim());
            case "truthy" -> ctxStr.toLowerCase().contains("true");
            case "falsy" -> ctxStr.isBlank() || ctxStr.toLowerCase().contains("false");
            default -> false;
        };
    }
}
```

### WorkflowLoop vs. WorkflowExecutor — Vergleich

| Aspekt | WorkflowExecutor (alt) | WorkflowLoop (neu) |
|---|---|---|
| Navigation | TopologicalSort (linear, DAG) | NextStepProvider (dynamisch, Graph-basiert) |
| Verzweigung | Decision-Nodes nur (branches in NodeData) | Alle Nodes mit mehreren ausgehenden Edges |
| Loops | Loop-Node mit bodySteps (Stub) | Loop-Node mit echter Iteration + Exit-Condition |
| Fork/Join | Stubs | Echte parallele Ausführung + Merge |
| Sub-Workflows | Stub | Rekursive Expansion + Ausführung |
| Data-Piping | Nein (nur String-Kontext) | Template-Resolver (`${node_X.output.field}`) |
| State | `Map<String, String>` | `WorkflowContext` (structured outputs) |
| Conditions | String-basiert | State-basiert (strukturierte Outputs) |

### Migrationspfad

```
Phase 1: NextStepProvider + WorkflowContext + ConditionEvaluator (State-basiert)
  → Unit-Tests: Navigation, Conditions, State
Phase 2: WorkflowLoop + NodeDispatcher (portiert aus WorkflowExecutor)
  → Integration-Tests: einfache Workflows (start → agent → end)
Phase 3: Template-Resolver + Data-Piping
  → Integration-Tests: Workflows mit Data-Piping
Phase 4: LLM-Node-Typen (llm_eval, llm_summarize)
  → Integration-Tests: Hybrid-Workflows
Phase 5: Fork/Join + Loop + Sub-Workflow (echte Implementierung)
  → Integration-Tests: Komplexe Workflows
Phase 6: HTTP-Server + Checkpoint (Crash-Recovery)
  → E2E-Tests
```

### Zusammenfassung

**Welche Runner-Funktionen bleiben:**
- `TopologicalSort` → `incomingSources()`, `outgoingTargets()` (als Hilfsmethoden in NextStepProvider)
- `ConditionEvaluator` → erweitert für `WorkflowContext`
- `SubWorkflowExpander` → unverändert
- `GraphCompiler` → unverändert (ggf. `llm_eval`/`llm_summarize` zu VALID_NODE_TYPES hinzufügen)
- `WorkflowStore` → unverändert

**Was ersetzt wird:**
- `WorkflowExecutor` → `NextStepProvider` + `WorkflowLoop` + `NodeDispatcher`
- `TopologicalSort.sort()` → `NextStepProvider.getNextNodeId()`

**Kernprinzip:** Statt linearer Topological-Sort-Navigation wird der Graph dynamisch durch `getNextNodeId()` navigiert. Der `WorkflowContext` hält den State. `ConditionEvaluator` prüft gegen strukturierte Outputs. `NodeDispatcher` führt jeden Node aus. Der Loop iteriert bis `getNextNodeId()` "DONE" liefert.
