# Hybrid Workflow/Agent-Ausführung — Implementierungsplan

Stand: 2026-11-12  
Status: Plan (keine Implementierung)  
Bezug: `docs/engineering/ReAct-Workflow.md`, `docs/engineering/Additional-Nodes.md`

## 1. Ziel und Abgrenzung

Der Workflow-Runner soll einen hybriden Modus unterstützen:

- **Deterministische Knoten** (`start`, `decision`, `fork`, `join`, `loop`, `end`,
  `mcp_call`, …) bleiben unverändert und laufen ohne LLM.
- **Normale `agent`-Knoten** behalten ihr bisheriges Verhalten: eigener
  Agent-Systemprompt, minimaler Input (Vorgänger-Output).
- **Neuer Knoten `context_agent_executor`** führt einen Agenten mit dem
  **gesamten bisherigen Workflow-Kontext** aus:
  - optional gemeinsamer, byte-identischer Workflow-Systemprompt
    (`WorkflowDefinition.systemPrompt`) für Prompt-Caching,
  - sonst der Agent-Systemprompt,
  - danach der vollständige Workflow-Kontext als User-Nachricht,
  - danach eine kleine aktuelle Aufgabe („Task“).
- Nach dem vollständigen Agent-Loop (inkl. aller Tool-Calls) wird **nur das
  Agent-Ergebnis** in den Workflow-Kontext übernommen. Der intermediäre
  Kind-Session-Kontext (Tool-Calls, Zwischennachrichten) wird verworfen.
- **Kein Summarize-Knoten** und **kein Runtime-Compaction** im
  `context_agent_executor`-Lauf. Die Kontextgröße wird vorab geschätzt; reicht
  sie nicht, muss der Workflow-Verfasser vorher einen Compact-Schritt
  (z. B. `llm_summarize`) einbauen.

Nicht Teil dieses Plans:

- Änderungen an `SubagentTool`: über `SubagentTool` aufgerufene Agenten
  erhalten weiterhin **nicht** den Shared-Kontext und laufen isoliert.
- Ein separates Phasenfeld: die Phase wird im Initial-Prompt / Kontext
  mitgeführt.
- Ein `contextTemplate`: es wird immer der volle Kontext übergeben.

## 2. Ablauf des neuen Knotens

```
Workflow-Kontext (WorkflowContext)
  ├─ initialPrompt            (Workflow-Eingabe, enthält Phase A/B/C)
  ├─ nodeOutputs              (deterministisch in def.nodes()-Reihenfolge)
  └─ workflowState            (sortierte Schlüssel)
            │
            ▼
  buildFullContext(def, ctx)  →  fullContext (String)
            │
            ▼
  systemPrompt = sharedWorkflowPrompt.isBlank()
                   ? agent.systemPrompt
                   : sharedWorkflowPrompt
            │
            ▼
  userPrompt = "## Previous context\n" + fullContext
             + "\n\n## Current task\n" + task
            │
            ▼
  childSession = engine.createSession(...)
  engine.runPrompt(childSession, name, userPrompt, config, true, null, null,
                   principal, overrides(compactionEnabled=false))
            │
            ▼
  ctx.setNodeOutput(node.id(), structured != null ? structured : result.text())
```

Wichtig: `systemPrompt` bleibt in beiden Fällen stabil. Der Gesamtkontext
liegt immer in der User-Nachricht, nie im Systemprompt. Dadurch ist der
Systemprompt cachefähig (shared) bzw. ebenfalls stabil (Agent-Systemprompt).

## 3. Datenmodell

### 3.1 `WorkflowDefinition.systemPrompt`

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/model/WorkflowDefinition.java`

Neues letztes Record-Feld `String systemPrompt` und Default im
Compact-Constructor:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowDefinition(
        String id,
        String name,
        String version,
        String status,
        List<Node> nodes,
        List<Edge> edges,
        List<Agent> agents,
        String ownerId,
        String systemPrompt
) {
    public WorkflowDefinition {
        // ... bestehende Defaults ...
        if (systemPrompt == null) systemPrompt = "";
    }
}
```

- Jackson: alte JSON-Dateien ohne `systemPrompt` bleiben ladbar
  (`@JsonIgnoreProperties(ignoreUnknown = true)`, fehlendes Feld → `null` →
  `""`).
- `NodeData` bleibt **unverändert**. Insbesondere gibt es kein
  `contextTemplate`, kein `phase`-Feld und keine neuen Pflichtfelder.

### 3.2 `WorkflowContext.initialPrompt`

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowContext.java`

```java
/** Ursprüngliche Workflow-Eingabe; enthält die Phaseninformation. */
private String initialPrompt;

public String getInitialPrompt() {
    return initialPrompt;
}

public void setInitialPrompt(String initialPrompt) {
    this.initialPrompt = initialPrompt;
}

/** Bestehender Restore-Pfad bleibt erhalten. */
public void restore(Map<String, Object> nodeOutputs,
                    Map<String, Object> workflowState,
                    String currentNodeId) {
    // unverändert
}

/** Neuer Restore-Pfad inkl. Initial-Prompt. */
public void restore(Map<String, Object> nodeOutputs,
                    Map<String, Object> workflowState,
                    String currentNodeId,
                    String initialPrompt) {
    restore(nodeOutputs, workflowState, currentNodeId);
    this.initialPrompt = initialPrompt;
}
```

Die maßgebliche Persistenz ist bereits vorhanden: `WorkflowExecutor` schreibt
die Eingabe über `sink.runStarted(..., context)` ins Journal, und
`WorkflowRunSnapshot.input()` liefert sie beim Resume. `CheckpointStore` ist
im aktuellen Runner nicht verdrahtet; die Erweiterung dort ist optional
(siehe 3.4).

### 3.3 `ExecutionOverrides.compactionEnabled`

**Datei:** `engine/src/main/java/de/augmentia/oc/engine/runtime/QocEngine.java`

Record um ein Feld erweitern und einen rückwärtskompatiblen 6-Arg-Konstruktor
beibehalten:

```java
public record ExecutionOverrides(
        AgentProfile agentProfile,
        ModelInfo modelInfo,
        Step step,
        ToolSnapshot toolSnapshot,
        ContextBuilder contextBuilder,
        SkillSelection skillSelection,
        boolean compactionEnabled
) {
    /** Rückwärtskompatibel: Compaction standardmäßig aktiv. */
    public ExecutionOverrides(AgentProfile agentProfile,
                              ModelInfo modelInfo,
                              Step step,
                              ToolSnapshot toolSnapshot,
                              ContextBuilder contextBuilder,
                              SkillSelection skillSelection) {
        this(agentProfile, modelInfo, step, toolSnapshot, contextBuilder,
             skillSelection, true);
    }
}
```

Compaction-Gate in `runPrompt` (aktuell Zeile 817):

```java
if (!workflowMode
        && (overrides == null || overrides.compactionEnabled())
        && result instanceof de.augmentia.oc.engine.runtime.step.StepResult.NeedsCompaction) {
    // bestehender Compact-and-Retry-Block
}
```

Damit bleibt das Verhalten aller bestehenden Aufrufer unverändert
(`overrides == null` ⇒ Compaction aktiv), und der neue Knoten kann sie
gezielt abschalten.

### 3.4 `CheckpointStore.initialPrompt` (optional, Konsistenz)

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/store/CheckpointStore.java`

- `save(...)`: `data.put("initialPrompt", ctx.getInitialPrompt());`
- `load(...)`: `ctx.setInitialPrompt((String) data.get("initialPrompt"));`

Auch wenn der Runner den Store derzeit nicht nutzt, bleibt das
Checkpoint-Format damit vollständig.

## 4. Dispatcher: neuer Knoten `context_agent_executor`

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/DefaultNodeDispatcher.java`

### 4.1 Neuer `switch`-Case

Direkt nach dem `case "agent"`-Block (aktuell Zeile 82) einfügen:

```java
case "context_agent_executor" -> {
    String agentRef = node.data().agentRef();
    WorkflowDefinition.Agent agentDef = findAgent(def, agentRef);

    // Eigener Registry-Name pro Knoten, damit der Systemprompt dieses
    // Knotens nicht durch eine bereits registrierte Agent-Config
    // (resolveAgentConfig: first registration wins) überschrieben wird.
    String prefixedName = "wf:ctx:" + node.id();

    String fullContext = buildFullContext(def, ctx);

    String shared = def.systemPrompt();
    String agentSp = agentDef == null ? "" : agentDef.systemPrompt();
    String systemPrompt = (shared == null || shared.isBlank()) ? agentSp : shared;

    String task = node.data().title();
    if (task == null || task.isBlank()) task = node.data().defaultTarget();
    if (task == null || task.isBlank()) task = "Continue with the next step.";

    String userPrompt = "## Previous context\n" + fullContext
            + "\n\n## Current task\n" + task;

    QocEngine.AgentConfig config = toAgentConfig(agentDef, prefixedName, systemPrompt);

    // Kein Runtime-Compaction in diesem Lauf.
    QocEngine.ExecutionOverrides overrides = new QocEngine.ExecutionOverrides(
            null, null, null, null, null, null, false);

    return runAgent(node, prefixedName, userPrompt, config, ctx, principal, overrides);
}
```

Begründung für `wf:ctx:<nodeId>`:

- `QocEngine.resolveAgentConfig(name, config)` gibt eine bereits registrierte
  Config zurück und ignoriert die übergebene. Würde der Knoten denselben
  Namen wie ein normaler `agent`-Knoten verwenden, könnte der Shared-Prompt
  verloren gehen.
- Ein eigener Name pro Knoten hält Modell/Tools/Systemprompt dieses Knotens
  stabil und isoliert.

### 4.2 `runAgent`-Overload

Bestehende `runAgent`-Signatur bleibt; ein Overload mit `ExecutionOverrides`
kommt hinzu:

```java
private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName,
                            String prompt, QocEngine.AgentConfig config,
                            WorkflowContext ctx,
                            de.augmentia.oc.engine.core.model.Principal principal) {
    return runAgent(node, prefixedName, prompt, config, ctx, principal, null);
}

private NodeResult runAgent(WorkflowDefinition.Node node, String prefixedName,
                            String prompt, QocEngine.AgentConfig config,
                            WorkflowContext ctx,
                            de.augmentia.oc.engine.core.model.Principal principal,
                            QocEngine.ExecutionOverrides overrides) {
    if (principal == null) principal = Principal.anonymous();
    SessionId child = engine.createSession(prefixedName, principal.id(), "workspace");
    EngineResult result = engine.runPrompt(
            child, prefixedName, prompt, config, true, null, null, principal, overrides);
    // ... bestehende Fehler-/Pause-Prüfung ...
    Map<String, Object> structured = StructuredOutputConverter.toMap(result.structured());
    ctx.setNodeOutput(node.id(), structured != null ? structured : result.text());
    return new NodeResult(result.text(), structured, child.value());
}
```

Wichtig: Nur `result.text()` / `result.structured()` werden in den
Workflow-Kontext geschrieben. Tool-Calls und Zwischennachrichten der
Kind-Session werden nicht übernommen.

### 4.3 `toAgentConfig`-Overload

**Datei:** gleiche Datei, aktuell Zeile 266.

```java
private QocEngine.AgentConfig toAgentConfig(WorkflowDefinition.Agent a,
                                            String prefixedName,
                                            String systemPromptOverride) {
    QocEngine.AgentConfig base = toAgentConfig(a, prefixedName);
    return new QocEngine.AgentConfig(
            base.name(), base.model(), base.temperature(), base.maxTokens(),
            base.topP(), systemPromptOverride, base.tools(),
            base.guardrailsInput(), base.guardrailsOutput(), base.hooks(),
            base.jsonOutput(), base.responseSchema());
}
```

### 4.4 `buildFullContext`

Package-private `static`, damit direkt testbar:

```java
static String buildFullContext(WorkflowDefinition def, WorkflowContext ctx) {
    StringBuilder sb = new StringBuilder();

    sb.append("## Initial prompt\n");
    String initial = ctx.getInitialPrompt();
    sb.append(initial == null || initial.isBlank() ? "(none)" : initial).append("\n\n");

    sb.append("## Node outputs\n");
    for (WorkflowDefinition.Node n : def.nodes()) {
        Object out = ctx.getNodeOutput(n.id());
        if (out == null) continue;
        sb.append("### ").append(n.id()).append(" (").append(n.type()).append(")\n");
        sb.append(renderValue(out)).append("\n\n");
    }

    sb.append("## Workflow state\n");
    ctx.getWorkflowState().entrySet().stream()
            .filter(e -> !"lastResult".equals(e.getKey())) // steht bereits in den Node-Outputs
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> sb.append("- ").append(e.getKey()).append(": ")
                    .append(renderValue(e.getValue())).append('\n'));

    return sb.toString().trim();
}

private static String renderValue(Object value) {
    if (value == null) return "";
    if (value instanceof String s) return s;
    try {
        return WorkflowJson.mapper().writeValueAsString(value);
    } catch (Exception e) {
        return String.valueOf(value);
    }
}
```

- **Determinismus:** Node-Outputs in `def.nodes()`-Reihenfolge (nicht in
  `HashMap`-Iterationsreihenfolge), State-Schlüssel sortiert. Das hält den
  Prompt über Läufe hinweg stabil (Cache).
- `lastResult` wird aus dem State weggelassen, weil der letzte Node-Output
  bereits enthalten ist.
- Import ergänzen: `de.augmentia.qoc.workflowrunner.store.WorkflowJson`.

### 4.5 Größen-Schätzung (kein Compaction im Lauf)

Vor dem `runPrompt` schätzen und bei Überschreitung nur warnen:

```java
private static final int CHARS_PER_TOKEN = 4;
private static final int CONTEXT_BUDGET_TOKENS = 96_000;

private static int estimateTokens(String text) {
    return text == null ? 0 : text.length() / CHARS_PER_TOKEN;
}
```

Im Case:

```java
int estimated = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
if (estimated > CONTEXT_BUDGET_TOKENS) {
    LOG.warn("context_agent_executor {}: estimated {} tokens exceed budget {}. "
            + "Insert a compact step before this node if the run fails.",
            node.id(), estimated, CONTEXT_BUDGET_TOKENS);
}
```

Bewusst **keine** automatische Compaction: `compactionEnabled=false` schaltet
den Runtime-Pfad ab; die Vorverdichtung ist Aufgabe des Workflow-Autors
(z. B. `llm_summarize`-Knoten davor). Später kann ein optionaler
`compactContext(...)`-Helfer ergänzt werden, ohne die Semantik dieses Knotens
zu ändern.

## 5. Executor, Expander, Resource, Defaults

### 5.1 `WorkflowExecutor`

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/WorkflowExecutor.java`

- Owner-Stempelung (Zeile 93): `def.systemPrompt()` als letztes Argument.
- `run(...)` (Zeile 182) setzt den Initial-Prompt zentral für Start und
  Resume:

```java
private WorkflowExecution run(SessionId session, String runId,
                              WorkflowDefinition definition, WorkflowContext ctx,
                              String input, WorkflowEventSink sink,
                              boolean storePrompt,
                              Principal principal) {
    // ...
    ctx.setInitialPrompt(input == null ? "" : input);
    // ... engine.runPrompt(...)
}
```

Damit ist `ctx.getInitialPrompt()` nach `executeRun` und nach `resume`
(snapshot.input()) gefüllt.

### 5.2 `SubWorkflowExpander`

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/runner/SubWorkflowExpander.java`, Zeile 53

```java
return new WorkflowDefinition(
        wf.id(), wf.name(), wf.version(), wf.status(),
        flatNodes, rewrittenEdges, agents, wf.ownerId(), wf.systemPrompt()
);
```

Hinweis: Der Shared-Prompt des **Eltern**-Workflows bleibt maßgeblich.
Sub-Workflows bringen ihren eigenen Prompt nur mit, wenn sie eigenständig
ausgeführt werden; beim Inlining wird der Eltern-Prompt verwendet. Das ist
gewollt, weil der Kontext des Eltern-Workflows weiterläuft.

### 5.3 `WorkflowDefaults`

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/store/WorkflowDefaults.java`

- `create(...)` (Zeile 24): `..., ""`
- `withId(...)` (Zeile 33): `..., definition.systemPrompt()`

### 5.4 `WorkflowResource`

**Datei:** `quarkus/src/main/java/de/augmentia/oc/engine/http/WorkflowResource.java`

- Zeile 71 (`createWorkflow`): `..., def.systemPrompt()`
- Zeile 99 (`saveWorkflow`): `..., def.systemPrompt()`

Beim Speichern eines bestehenden Workflows bleibt der Shared-Prompt damit
erhalten; beim Anlegen ist er `""`.

## 6. Validierung

**Datei:** `workflow-runner/src/main/java/de/augmentia/qoc/workflowrunner/validation/GraphCompiler.java`

- `VALID_NODE_TYPES` (Zeile 14) um `"context_agent_executor"` erweitern.
- Agent-Pflicht-Ref auch für den neuen Typ prüfen. Empfehlung: Menge
  einführen:

```java
private static final Set<String> AGENT_NODE_TYPES =
        Set.of("agent", "context_agent_executor");
```

und die beiden `"agent".equals(n.type())`-Prüfungen (Zeilen 56 und 80) auf
`AGENT_NODE_TYPES.contains(n.type())` umstellen. Damit gilt: ein
`context_agent_executor` ohne `agentRef` ist ungültig, und die `agentRef` muss
in `agents[]` oder im externen Agent-Store existieren.

## 7. UI

### 7.1 Typen

**Datei:** `workflow-ui/src/types/workflow.ts`

```ts
export interface WorkflowDefinition {
  id: string;
  name: string;
  version: string;
  status: string;
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  agents: Agent[];
  systemPrompt?: string;   // NEU
}
```

`NodeData` bleibt unverändert (kein Template, kein Phasenfeld).

### 7.2 Graph-Handles und Verbindungsregeln

**Datei:** `workflow-ui/src/lib/workflowGraph.ts`

- `SINGLE_SOURCE_HANDLES`: `context_agent_executor: 'out'`
- `SINGLE_TARGET_HANDLES`: `context_agent_executor: 'in'`

**Datei:** `workflow-ui/src/lib/connectionRules.ts`

- `SINGLE_INCOMING`: `'context_agent_executor'` ergänzen
- `SINGLE_OUTGOING`: `'context_agent_executor'` ergänzen

### 7.3 Node-Komponente

**Neu:** `workflow-ui/src/components/ContextAgentExecutorNode.tsx`

Modell: `AgentNode.tsx`. Eigene Farbe (z. B. `bg-cyan-700 border-cyan-400`),
Titel `data.title || 'Context Agent'`, Untertitel `data.agentRef || 'no agent'`,
Ports `in`/`out`, optionaler Hinweis „full context“.

**Datei:** `workflow-ui/src/pages/GraphEditor.tsx`

- Import ergänzen.
- `nodeTypes` (Zeile 43): `context_agent_executor: ContextAgentExecutorNode`
- Palette (Zeile 362 ff.):

```ts
{ label: '+ Context Agent', type: 'context_agent_executor',
  color: 'bg-cyan-700', data: { title: 'Context Agent', agentRef: '' } },
```

### 7.4 Property-Panel

**Datei:** `workflow-ui/src/components/PropertyPanel.tsx`

Neuen Block analog zum `agent`-Block (Zeile 649) ergänzen:

```tsx
{selectedNode.type === 'context_agent_executor' && (
  <div className="mb-4 border-t border-gray-700 pt-4">
    <h4 className="mb-2 text-sm font-semibold">Context Agent Executor</h4>
    <div className="mb-3">
      <FieldLabel>Agent-Ref</FieldLabel>
      <select
        value={agentRef}
        onChange={(event) => loadAgent(event.target.value)}
        className="flex-1 rounded bg-gray-700 px-2 py-1 text-sm"
      >
        <option value="">-- neu --</option>
        {agents.map((agent) => (
          <option key={agent.id} value={agent.id}>
            {agent.id} ({agent.name})
          </option>
        ))}
      </select>
    </div>
    <p className="text-xs text-gray-500">
      Übergibt den vollständigen bisherigen Workflow-Kontext als User-Nachricht.
      Der Shared-Systemprompt wird aus den Workflow-Einstellungen verwendet.
    </p>
  </div>
)}
```

Die bestehende `applyBuildAgent`-/Copy-Logik ist an `type === 'agent'`
gebunden; für den neuen Typ nicht nötig.

### 7.5 Workflow-Systemprompt editieren

**Datei:** `workflow-ui/src/pages/WorkflowDetail.tsx`

Der Shared-Prompt ist eine Workflow-Eigenschaft, kein Knotenfeld. Oberhalb des
Graphs (oder als einklappbarer Block) ein Textarea ergänzen:

```tsx
<div className="rounded border border-gray-700 bg-gray-800 p-3">
  <label className="mb-1 block text-xs font-semibold text-gray-400">
    Workflow-Systemprompt (shared, cache-stabil)
  </label>
  <textarea
    value={workflow.systemPrompt ?? ''}
    onChange={(e) => {
      const updated = { ...workflow, systemPrompt: e.target.value };
      setWorkflow(updated);
      setJsonText(JSON.stringify(updated, null, 2));
    }}
    className="h-24 w-full rounded bg-gray-900 p-2 font-mono text-xs"
    placeholder="Optional: gemeinsamer Systemprompt für alle context_agent_executor-Knoten"
  />
</div>
```

`save()` übernimmt `workflow.systemPrompt` bereits, weil das gesamte
Workflow-Objekt gespeichert wird.

## 8. Tests

### 8.1 Neue/angepasste Java-Tests

| Test | Datei | Inhalt |
|---|---|---|
| `buildFullContext` | neu: `workflow-runner/src/test/java/de/augmentia/qoc/workflowrunner/runner/DefaultNodeDispatcherContextTest.java` | Initial-Prompt, Node-Outputs in `def.nodes()`-Reihenfolge, sortierte State-Keys, `lastResult` nicht doppelt, Map/List als JSON |
| Shared-Prompt | dito | `resolveSystemPrompt` liefert den Workflow-Prompt; User-Prompt enthält `## Previous context` und `## Current task` |
| Fallback | dito | ohne Shared-Prompt: Agent-Systemprompt bleibt System; Kontext in User-Nachricht |
| Determinismus | dito | gleicher Kontext ⇒ identischer String (Cache-Stabilität) |
| `initialPrompt` | neu/erweitert: `WorkflowContextTest` | set/get, `restore(..., initialPrompt)` |
| JSON-Kompatibilität | neu/erweitert: `WorkflowDefinitionJsonTest` | altes JSON ohne `systemPrompt` ⇒ `""` |
| `ExecutionOverrides` | `engine/src/test/java/de/augmentia/oc/engine/runtime/QocEngineTest.java` | 6-Arg-Konstruktor ⇒ `compactionEnabled() == true`; 7-Arg mit `false` |
| GraphCompiler | neu/erweitert: `GraphCompilerTest` | `context_agent_executor` gültig; ohne `agentRef` ungültig |
| Store-Contract | `WorkflowStoreContractTest.java`, Zeile 83 | Konstruktor um `""` erweitern |

Hinweis zu Dispatcher-Tests: `DefaultNodeDispatcher` benötigt eine
`QocEngine`. Für reine Kontext-/Prompt-Tests die Helfer
`buildFullContext(...)` und `resolveSystemPrompt(...)` package-private
statisch machen und direkt testen. Ein vollständiger Dispatch-Test kann mit
einer schlanken `QocEngine`-Testinstanz (In-Memory-Stores, Fake-Provider)
ergänzt werden; er ist nicht Voraussetzung für die erste Umsetzung.

### 8.2 UI

- TypeScript-Build (`npm run build` bzw. vorhandener Lint/Build-Task).
- Manuell: Knoten hinzufügen, Agent wählen, speichern, neu laden; JSON
  enthält `systemPrompt` und `type: "context_agent_executor"`.

## 9. Rückwärtskompatibilität und Migration

- `WorkflowDefinition` ist ein Jackson-Record mit
  `@JsonIgnoreProperties(ignoreUnknown = true)`. Alte Definitionen ohne
  `systemPrompt` laden weiter (`""`).
- Alle bestehenden Workflows behalten das Verhalten normaler `agent`-Knoten;
  der neue Knoten wird nur bei expliziter Verwendung aktiv.
- `ExecutionOverrides` behält den 6-Arg-Konstruktor; bestehende Aufrufer und
  Tests bleiben kompilierbar.
- `NodeData` bleibt unverändert; keine Migrationslogik nötig.

## 10. Risiken und Gegenmaßnahmen

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Kontext zu groß | Provider-Fehler, Lauf scheitert | Größe vorab schätzen und warnen; Workflow-Autor baut `llm_summarize` davor; `compactionEnabled=false` verhindert stillen Runtime-Compact |
| Cache wird nicht geteilt | Shared-Prompt bringt keinen Cache-Vorteil | Systemprompt ist byte-identisch; Cache-Affinity ist session-basiert (`CacheAffinity`). Für echte Prefix-Wiederverwendung müsste die Kind-Session die Parent-Affinity erben — als Folgeoptimierung dokumentiert, nicht Teil dieses Plans |
| Registry-Kollision | Falscher Systemprompt | Eigener Name `wf:ctx:<nodeId>` pro Knoten |
| Kind-Session-Kontext wächst | Speicher/Journal | Nur das Ergebnis wird übernommen; Kind-Session bleibt für Audit erhalten, wird aber nicht in Folgeprompts verwendet |
| Sub-Workflow-Shared-Prompt | Falscher Prompt | Beim Inlining gewinnt der Eltern-Prompt (`wf.systemPrompt()` wird propagiert) |
| Resume ohne Initial-Prompt | Kontext unvollständig | `run(...)` setzt `initialPrompt` aus `input`; `snapshot.input()` liefert ihn beim Resume |
| Kontext-Reihenfolge nicht deterministisch | Cache-Miss | `def.nodes()`-Reihenfolge + sortierte State-Keys |

## 11. Umsetzungsreihenfolge

1. **Modell:** `WorkflowDefinition.systemPrompt` + alle 6 Konstruktor-Aufrufer
   (`WorkflowResource` 71/99, `WorkflowDefaults` 24/33, `SubWorkflowExpander`
   53, `WorkflowExecutor` 93, `WorkflowStoreContractTest` 83).
2. **Kontext:** `WorkflowContext.initialPrompt` + `WorkflowExecutor.run(...)`
   setzt ihn; optional `CheckpointStore`.
3. **Engine:** `ExecutionOverrides.compactionEnabled` + Compaction-Gate +
   Test.
4. **Dispatcher:** neuer Case, `runAgent`-Overload, `toAgentConfig`-Overload,
   `buildFullContext`, `estimateTokens`, `renderValue`.
5. **Validierung:** `GraphCompiler`.
6. **UI:** Typen, Handles, Knotenkomponente, Palette, Property-Panel,
   Workflow-Systemprompt-Editor.
7. **Tests:** Helfer-Tests, Kontext-/JSON-/Overrides-/Compiler-Tests,
   Store-Contract.
8. **Build:** `cd qoc && mvn test` (bzw. `mvn -pl workflow-runner -am test`),
   danach UI-Build.

## 12. Offene Punkte (bewusst entschieden)

| Frage | Entscheidung |
|---|---|
| Phasenfeld? | Nein. Phase steckt im `initialPrompt` und damit im Full-Context. |
| `contextTemplate`? | Nein. Immer voller Kontext. |
| Summarize-Knoten? | Nein. Kein automatisches Summarize; optionaler vorgelagerter `llm_summarize`. |
| Compaction im Lauf? | Nein. `compactionEnabled=false`; Größe vorab schätzen. |
| Subagent-Shared-Kontext? | Nein. `SubagentTool` bleibt isoliert. |
| Shared-Prompt Fallback? | Agent-Systemprompt bleibt System; Kontext als User-Nachricht. |
| Ergebnis-Persistenz? | Nur `result.text()`/`result.structured()` als `nodeOutput`. |
