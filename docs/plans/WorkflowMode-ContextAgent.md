# Dynamic State-Driven Workflow & Context Hygiene — Plan

> **Status:** Plan, 2026-11-13  
> **Bezug:** `docs/plans/Hybrid.md`, `docs/engineering/Additional-Nodes.md`,
> `docs/engineering/Workflow.md`, `docs/engineering/Two-Agent-Systems.md`  
> **Ziel:** Den Workflow von einem statischen, vollständig kontexttragenden
> Graphen zu einem dynamischen, zustandsgetriebenen System mit
> Kontext-Hygiene umbauen. Nach jedem Agenten-Lauf erfolgt eine
> deterministische Auswertung; Daten werden über Referenzen/Links statt
> Volltext in den Kontext injiziert.

---

## 0. Korrektur der bisherigen Annahmen

### 0.1 Klarstellung: WorkflowMode und JSON-Integration

Der Workflow läuft im `workflowMode`. Features werden bewusst über JSON-Strukturen
integriert (`mcpArgs`, strukturierte Agent-Ergebnisse, `stateUpdates`,
`injectRefs`, `postGuard`). Der `context_agent_executor` ist ein spezieller
Workflow-Node, der über `runAgent` ein LLM in einer Kind-Session aufruft. Er
ist kein `SubagentTool`-Aufruf und erhält keinen Shared-Workflow-Kontext über
den Subagenten-Pfad.

Die vorherige Planung ging von drei Annahmen aus, die für große,
dynamische Workflows problematisch sind:

1. **Statischer Kontext:** `buildFullContext` sendet alle bisherigen
   Node-Outputs in fester `def.nodes()`-Reihenfolge. Bei großen Daten oder
   langen Läufen explodiert das Context-Window.
2. **Immer voller Kontext:** Der `context_agent_executor` erhält den gesamten
   bisherigen Verlauf. Das ist für Synthese sinnvoll, aber nicht für jeden
   Schritt; viele Agenten brauchen nur einen minimalen, fokussierten Kontext.
3. **Statischer Graphfluss:** Die Navigation folgt im Wesentlichen den
   Kanten. Dynamische Rücksprünge mit neu injizierten Daten sind nur über
   `loop` und `decision` möglich, aber nicht als First-Class-Mechanismus.

Die Korrektur:

- **Deterministische Auswertung nach jedem Agenten-Lauf** steuert den Fluss.
- **Externe Daten/Artefakte** werden im Speicher gehalten und nur als
  Referenzen/Links in Prompts injiziert.
- **Der Workflow ist eine dynamische State-Machine**, die sich nach jedem
  Agenten-Schritt neu kalibriert.

---

## 1. Kernkonzept

### 1.1 Deterministische Prüfung nach dem Agent-Run

Der Agent liefert ein strukturiertes Ergebnis (JSON Schema). Ein
deterministischer Guard wertet harte Bedingungen aus:

- `score < 0.8`
- `missing_fields == true`
- `status == "retry"`

Der Guard steuert den Graph-Fluss:

- weiter zum nächsten Schritt,
- Rücksprung (Loop) mit zusätzlichen Parametern,
- Aufruf eines anderen Agenten,
- Injektion zusätzlicher Daten/Links.

Das LLM entscheidet nicht über den Workflow; es liefert nur das Ergebnis.

### 1.2 Context-Window-Hygiene

- Der LLM/Subagent erhält **niemals** den gesamten historischen Ballast oder
  riesige Rohdaten.
- Externe Daten, Artefakte und Links werden in einem `ArtifactStore`
  gehalten.
- Der deterministische Kontroll-Layer injiziert bei Übergängen oder
  Rücksprüngen gezielt neue Daten-Pointer oder Links in den Prompt.
- Der Agent arbeitet immer mit einem minimalen, fokussierten Kontext.

### 1.3 Verschmelzung von Agent-Loop und Workflow

- Der Agent-Loop ist nur das Ausführungswerkzeug für eine Teilaufgabe.
- Der Workflow ist keine starre, vorausberechnete Pfadliste
  (`def.nodes()` in fixer Reihenfolge), sondern eine dynamische
  State-Machine.
- Nach jedem Agenten-Schritt wird der Zustand neu kalibriert.

---

## 2. Zielarchitektur

```text
WorkflowExecutor
 └─ QocEngine.runPrompt(workflowSession, WorkflowStepProvider)
     └─ WorkflowStepProviderImpl
         ├─ getNextNodeId(def, ctx)   // dynamische State-Machine
         ├─ DefaultNodeDispatcher.dispatch(node, def, ctx, input)
         │    ├─ Agent-Node
         │    │    ├─ ContextAssembler.assemble(ctx, policy, task)
         │    │    ├─ runAgent(..., compactionEnabled=false)
         │    │    └─ optionaler Post-Guard
         │    ├─ Guard-Node
         │    │    ├─ Bedingungen auswerten
         │    │    ├─ State setzen
         │    │    └─ Inject-Refs / nextNode setzen
         │    ├─ Inject-Node
         │    │    └─ Artifact-Refs in ctx.contextInclude
         │    ├─ Decision / MCP / Compact / HITL
         │    └─ ...
         └─ JournalWorkflowEventSink
```

---

## 3. Datenmodell

### 3.1 `ArtifactRef` und `ArtifactStore`

**Neue Datei:** `workflow-runner/.../runner/ArtifactRef.java`

```java
public record ArtifactRef(
        String id,
        String type,        // json, text, link, file, mcp-result
        String uri,         // storage key oder URL
        String summary,     // kurze Beschreibung für Prompt
        long sizeBytes,
        java.time.Instant createdAt
) {}
```

**Neue Datei:** `workflow-runner/.../runner/ArtifactStore.java`

```java
public interface ArtifactStore {
    String put(ArtifactRef ref, Object payload);
    java.util.Optional<Object> get(String id);
    java.util.List<ArtifactRef> list();
    boolean remove(String id);
}
```

**Neue Datei:** `workflow-runner/.../runner/InMemoryArtifactStore.java`

- Erste Implementierung für JVM-Modus.
- JDBC-Implementierung später analog zu `AgentStore`.

### 3.2 `WorkflowContext`-Erweiterungen

**Datei:** `workflow-runner/.../runner/WorkflowContext.java`

Neue Felder:

```java
private final Map<String, ArtifactRef> artifacts = new HashMap<>();
private final List<String> contextInclude = new ArrayList<>();
private final List<String> contextExclude = new ArrayList<>();
private Map<String, Object> lastStructuredResult = Map.of();
```

Neue Methoden:

```java
public void addArtifact(ArtifactRef ref) { artifacts.put(ref.id(), ref); }
public Map<String, ArtifactRef> getArtifacts() { return Map.copyOf(artifacts); }
public void includeContext(String ref) { if (!contextInclude.contains(ref)) contextInclude.add(ref); }
public void excludeContext(String ref) { contextExclude.add(ref); }
public List<String> getContextInclude() { return List.copyOf(contextInclude); }
public List<String> getContextExclude() { return List.copyOf(contextExclude); }
public void setLastStructuredResult(Map<String, Object> result) { ... }
public Map<String, Object> getLastStructuredResult() { ... }
```

Persistenz (pragmatisch, ohne Journal-Schemaänderung):

- `artifacts`, `contextInclude`, `contextExclude` und `lastStructuredResult`
  werden unter den Schlüsseln `_artifacts`, `_contextInclude`,
  `_contextExclude` und `_lastStructuredResult` in `workflowState`
  gespeichert.
- Beim Resume werden sie aus `workflowState` wiederhergestellt.

### 3.3 `ContextAssembler`

**Neue Datei:** `workflow-runner/.../runner/ContextAssembler.java`

Ersetzt `buildFullContext` und unterstützt Policies:

```java
public enum ContextPolicy { FULL, FOCUSED, ARTIFACTS, CUSTOM }

public static String assemble(WorkflowDefinition def, WorkflowContext ctx,
                             ContextPolicy policy, String task, List<String> customRefs)
```

Verhalten:

- `FULL`: bisheriges `buildFullContext` (Legacy/Backward-Compat).
- `FOCUSED`: nur `ctx.getContextInclude()` (Node-IDs/Artifact-IDs), plus
  Initial-Prompt und Task.
- `ARTIFACTS`: nur Artifact-Refs/Links/Summaries.
- `CUSTOM`: nur `customRefs`.

Determinismus:

- feste Reihenfolge,
- kanonische JSON-Serialisierung,
- keine Zeitstempel,
- keine Rohdaten bei `FOCUSED`/`ARTIFACTS`.

### 3.4 `NodeData`-Erweiterungen

Pragmatisch über das bestehende `mcpArgs`-Options-Bag (wie bei `compact`),
um Schema-Migrationen zu vermeiden. Langfristig können dedizierte Felder
folgen.

Neue Optionen:

```json
{
  "contextMode": "focused",
  "contextRefs": ["nodeA", "artifact:123"],
  "postGuard": {
    "condition": "score < 0.8",
    "stateUpdates": { "retry": true, "missingFields": true },
    "injectRefs": ["artifact:retry-data"],
    "nextNode": "agent_retry"
  },
  "injectRefs": ["artifact:input-data"]
}
```

Bedeutung:

- `contextMode`: `full`, `focused`, `artifacts`, `custom`.
- `contextRefs`: explizite Referenzen für `CUSTOM`.
- `postGuard`: deterministische Auswertung nach einem Agenten-Lauf.
- `injectRefs`: Artefakt-Refs, die in `contextInclude` aufgenommen werden.

### 3.5 `guard`- und `inject`-Knoten

Neue Node-Typen:

- `guard`: deterministische Prüfung mit `condition`, `onTrue`, `onFalse`,
  `stateUpdates`, `injectRefs`, `nextNode`.
- `inject`: nimmt `injectRefs` und fügt sie zu `contextInclude` hinzu.

`GraphCompiler.VALID_NODE_TYPES` wird um `guard` und `inject` erweitert.

### 3.6 Dynamische Sprünge

`WorkflowStepProviderImpl.getNextNodeId` wird erweitert:

```java
String override = (String) ctx.getState("nextNode");
if (override != null && !override.isBlank()) {
    ctx.setState("nextNode", null); // consume
    return override;
}
```

Damit können `guard`/`inject`/`decision` einen dynamischen Sprung setzen, ohne
dass eine Kante im Graphen existiert. Das schließt die Lücke zwischen
statischem Graph und dynamischer State-Machine.

Loop-Schutz: `LoopDetector` und `attempts` bleiben aktiv. Ein Sprung, der
denselben Node ohne Zustandsänderung wiederholt, wird als Loop erkannt.

---

## 4. Implementierung

### Phase 0 — Kontext- und Artefaktmodell

1. `ArtifactRef`, `ArtifactStore`, `InMemoryArtifactStore` anlegen.
2. `WorkflowContext` um Artefakte, `contextInclude`, `contextExclude`,
   `lastStructuredResult` erweitern.
3. Persistenz über `workflowState`-Schlüssel.
4. `WorkflowContextTest` erweitern.

### Phase 1 — Dynamische Kontextassemblierung

5. `ContextAssembler` mit Policies `FULL`, `FOCUSED`, `ARTIFACTS`, `CUSTOM`.
6. `DefaultNodeDispatcher.buildFullContext` durch `ContextAssembler.assemble`
   ersetzen.
7. `context_agent_executor` nutzt `contextMode` aus `mcpArgs` (Default:
   `FOCUSED`, falls `contextInclude` nicht leer, sonst `FULL`).
8. `ContextAssemblerTest` mit Determinismus und Hygiene.

### Phase 2 — Post-Guard und dynamische Sprünge

9. `guard`- und `inject`-Node-Typen in `GraphCompiler` und
   `DefaultNodeDispatcher`.
10. `getNextNodeId` um `nextNode`-Override erweitern.
11. Optionaler `postGuard` in Agent-Nodes:
    - nach `runAgent` strukturiertes Ergebnis in `lastStructuredResult`
      speichern,
    - Bedingung auswerten,
    - State-Updates setzen,
    - `injectRefs` in `contextInclude` aufnehmen,
    - `nextNode` setzen.
12. `ConditionEvaluator` für Guard-Bedingungen wiederverwenden.

### Phase 3 — Kontext-Hygiene erzwingen

13. `ContextBudgetGate` auf fokussierten Kontext anwenden.
14. `mcp_call` kann große Ergebnisse als Artefakt speichern und nur die
    `ArtifactRef` zurückgeben.
15. `compact` kann `contextInclude` reduzieren.
16. UI: Artefaktliste, Kontext-Vorschau, Guard-Editor.

### Phase 4 — Tests und Coverage

17. Unit-, Integrations- und E2E-Tests (siehe Abschnitt 6).
18. `mvn test` und `mvn -pl engine verify` (≥ 80 %).

---

## 5. Implementierungsdetails

### 5.1 `ContextAssembler`

```java
public final class ContextAssembler {
    private ContextAssembler() {}

    public static String assemble(WorkflowDefinition def, WorkflowContext ctx,
                                 ContextPolicy policy, String task,
                                 List<String> customRefs) {
        // FULL: bisherige Logik
        // FOCUSED: nur ctx.getContextInclude()
        // ARTIFACTS: nur Artifact-Refs/Links/Summaries
        // CUSTOM: nur customRefs
        // immer: Initial-Prompt + Task
    }
}
```

Regeln:

- `FULL` behält die bisherige Reihenfolge und Serialisierung.
- `FOCUSED` enthält nur referenzierte Node-Outputs/Artefakte.
- `ARTIFACTS` enthält keine Rohdaten, nur `id`, `type`, `summary`, `uri`.
- Wenn `contextInclude` leer ist, fällt `FOCUSED` auf den letzten Node-Output
  plus Initial-Prompt zurück (kein harter Fehler).
- Keine stillen Kürzungen; bei Budget-Überschreitung wirft
  `ContextBudgetGate`.

### 5.2 `guard`-Node

```java
case "guard" -> {
    Map<String, Object> opts = node.data().mcpArgs() == null ? Map.of() : node.data().mcpArgs();
    String condition = str(opts.get("condition"), "");
    boolean matched = condition.isBlank() || ConditionEvaluator.matches(condition, ctx);

    Map<String, Object> updates = map(opts.get("stateUpdates"));
    updates.forEach(ctx::setState);

    List<String> inject = list(opts.get("injectRefs"));
    inject.forEach(ctx::includeContext);

    String next = matched
            ? str(opts.get("onTrue"), node.data().defaultTarget())
            : str(opts.get("onFalse"), node.data().defaultTarget());
    if (next != null && !next.isBlank()) ctx.setState("nextNode", next);

    ctx.setNodeOutput(node.id(), matched ? "true" : "false");
    return new NodeResult(matched ? "true" : "false", Map.of("matched", matched));
}
```

### 5.3 `inject`-Node

```java
case "inject" -> {
    List<String> refs = list(node.data().mcpArgs() == null ? null : node.data().mcpArgs().get("injectRefs"));
    refs.forEach(ctx::includeContext);
    ctx.setNodeOutput(node.id(), "injected:" + refs);
    return new NodeResult("injected:" + refs, Map.of("refs", refs));
}
```

### 5.4 Post-Guard in Agent-Nodes

Nach `runAgent`:

```java
Map<String, Object> guard = map(node.data().mcpArgs() == null ? null : node.data().mcpArgs().get("postGuard"));
if (!guard.isEmpty()) {
    ctx.setLastStructuredResult(structured != null ? structured : Map.of());
    String condition = str(guard.get("condition"), "");
    if (condition.isBlank() || ConditionEvaluator.matches(condition, ctx)) {
        map(guard.get("stateUpdates")).forEach(ctx::setState);
        list(guard.get("injectRefs")).forEach(ctx::includeContext);
        String next = str(guard.get("nextNode"), null);
        if (next != null && !next.isBlank()) ctx.setState("nextNode", next);
    }
}
```

Damit wird „nach jedem Agenten-Lauf deterministisch auswerten“ explizit und
nachvollziehbar, ohne implizite Magie.

### 5.5 `ContextBudgetGate` (fokussiert)

Das bestehende Budget-Gate wird auf den fokussierten Kontext angewendet.
Bei Überschreitung:

```text
WorkflowBudgetExceeded(nodeId, estimatedTokens, maxInputTokens,
  "Reduce contextInclude or insert a compact step.")
```

Keine stille Truncation.

---

## 6. Tests

### 6.1 Unit-Tests

**Neue Datei:** `workflow-runner/.../runner/ArtifactStoreTest.java`

| Test | Erwartung |
|---|---|
| `putAndGetRoundTrips` | Payload identisch |
| `listReturnsRefs` | Refs vorhanden |
| `removeDeletes` | nicht mehr abrufbar |

**Neue Datei:** `workflow-runner/.../runner/WorkflowContextSelectionTest.java`

| Test | Erwartung |
|---|---|
| `includeAndExcludeAreDeterministic` | Reihenfolge stabil |
| `artifactsAreCopied` | keine externe Mutation |
| `resumeRestoresSelection` | aus `workflowState` wiederhergestellt |

**Neue Datei:** `workflow-runner/.../runner/ContextAssemblerFocusedTest.java`

| Test | Erwartung |
|---|---|
| `focusedIncludesOnlySelectedRefs` | nicht selektierte Outputs fehlen |
| `artifactsModeIncludesOnlyRefs` | keine Rohdaten |
| `customUsesCustomRefs` | nur customRefs |
| `fullRemainsBackwardCompatible` | bisheriges Verhalten unverändert |
| `focusedFallsBackWhenEmpty` | letzter Output + Initial-Prompt |
| `focusedIsDeterministic` | zweimal bauen → identisch |

**Neue Datei:** `workflow-runner/.../runner/GuardNodeTest.java`

| Test | Erwartung |
|---|---|
| `trueBranchSetsStateAndNextNode` | State + `nextNode` gesetzt |
| `falseBranchWorks` | `onFalse` |
| `injectRefsAdded` | `contextInclude` erweitert |
| `conditionBlankMatches` | Default true |

**Neue Datei:** `workflow-runner/.../runner/InjectNodeTest.java`

| Test | Erwartung |
|---|---|
| `injectAddsRefs` | `contextInclude` enthält Refs |
| `injectIsDeterministic` | Output stabil |

**Neue Datei:** `workflow-runner/.../runner/DynamicJumpTest.java`

| Test | Erwartung |
|---|---|
| `nextNodeOverrideIsConsumed` | nächster Node aus Override, dann null |
| `loopDetectionPreventsInfiniteJump` | Loop erkannt |

### 6.2 Integrations-Tests

**Neue Datei:** `workflow-runner/.../runner/ContextHygieneIntegrationTest.java`

| Test | Erwartung |
|---|---|
| `largeRawDataNotInPrompt` | Prompt enthält nur Artifact-Ref/Summary |
| `focusedContextSmallerThanFull` | Token-Schätzung kleiner |
| `guardDrivesBranch` | strukturiertes Ergebnis steuert Fluss |

**Neue Datei:** `workflow-runner/.../runner/PostGuardIntegrationTest.java`

| Test | Erwartung |
|---|---|
| `postGuardOnLowScoreRetries` | `retry=true`, `nextNode=agent_retry` |
| `postGuardInjectsRefs` | Refs im `contextInclude` |
| `postGuardSkippedWhenConditionFalse` | keine State-Änderung |

### 6.3 E2E-Tests

**Neue Datei:** `quarkus/.../http/DynamicWorkflowE2ETest.java`

| Test | Erwartung |
|---|---|
| `agentLowScoreLoopsWithInjectedArtifact` | zweiter Agent erhält fokussierten Kontext mit neuem Ref |
| `contextAgentUsesFocusedContext` | Request enthält nur selektierte Refs |
| `artifactNotLeakedAsRaw` | großer Payload nicht im Prompt |
| `guardBranchesToDifferentAgent` | anderer Agent wird aufgerufen |
| `dynamicJumpWorks` | `nextNode`-Override wird befolgt |

### 6.4 Bestehende Tests

- `DefaultNodeDispatcherContextTest` (anpassen an `ContextAssembler`)
- `WorkflowContextTest`
- `WorkflowRunJournalTest`
- `ParallelToolExecutionTest`, `SubagentParallelTest`, `ParallelSubagentE2ETest`

### 6.5 Coverage

```bash
cd qoc
mvn -pl engine test
mvn -pl workflow-runner test
mvn -pl quarkus test
mvn test
mvn -pl engine verify
```

Akzeptanz: alle Tests grün, Coverage ≥ 80 %.

---

## 7. Implementierungsreihenfolge

### P0 — Modell

1. `ArtifactRef`, `ArtifactStore`, `InMemoryArtifactStore`.
2. `WorkflowContext`-Erweiterungen.
3. Persistenz über `workflowState`.
4. Tests für Artefakte und Selektion.

### P1 — Dynamische Kontextassemblierung

5. `ContextAssembler` mit Policies.
6. `DefaultNodeDispatcher` nutzt `ContextAssembler`.
7. `context_agent_executor` mit `contextMode`.
8. `ContextAssemblerTest`.

### P2 — Guards und dynamische Sprünge

9. `guard`- und `inject`-Knoten.
10. `getNextNodeId`-Override.
11. `postGuard` in Agent-Nodes.
12. Tests.

### P3 — Kontext-Hygiene erzwingen

13. `ContextBudgetGate` auf fokussierten Kontext.
14. `mcp_call` speichert große Ergebnisse als Artefakt.
15. `compact` reduziert `contextInclude`.
16. UI: Artefakte, Vorschau, Guard-Editor.

### P4 — Tests und Coverage

17. Unit/Integration/E2E.
18. Coverage-Gate.

---

## 8. Akzeptanzkriterien

- Nach jedem Agenten-Lauf kann ein deterministischer Guard Fluss und
  Zustand steuern.
- Große Rohdaten landen nicht im Prompt; nur Referenzen/Links/Summaries.
- `ContextAssembler` unterstützt `FULL`, `FOCUSED`, `ARTIFACTS`, `CUSTOM`.
- Dynamische Sprünge über `nextNode` sind möglich und loop-geschützt.
- `context_agent_executor` ist nicht mehr zwangsläufig voller Kontext.
- Budget-Gate gilt für den fokussierten Kontext.
- Alle Tests grün, Coverage ≥ 80 %.

---

## 9. Risiken

| Risiko | Wirkung | Gegenmaßnahme |
|---|---|---|
| Zu viele Optionen im Options-Bag | Unübersichtlich | später dedizierte NodeData-Felder |
| Dynamische Sprünge erzeugen Loops | Endlosschleife | `attempts`, `LoopDetector`, Max-Steps |
| Artefakt-Speicher wächst | Memory/DB | TTL, Cleanup, Größenlimit |
| Fokussierter Kontext verliert Information | schlechtere Ergebnisse | `FULL`-Modus als Fallback, Tests |
| UI-Komplexität | schwer bedienbar | Defaults, Vorschau, Validierung |

---

## 10. Fazit

Die Korrektur verschiebt den Schwerpunkt von „immer voller Kontext“ zu
„deterministisch gesteuerter, fokussierter Kontext“. Der Agent bleibt
Ausführungswerkzeug; der Workflow wird zur dynamischen State-Machine.
Artefakte und Links ersetzen Volltext im Prompt. Das schließt die Lücke
zwischen starren Graphen und unkontrollierten Agenten, ohne einen dritten
Loop-Typ einzuführen.
