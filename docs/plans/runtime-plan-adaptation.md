# Runtime Plan Adaptation — Konzept (Supervisor / Orchestrator)

> **Status:** Konzept (2026-11-17), nicht implementiert.  
> **Scope:** `workflow-runner` (Plan, Dispatch, Context) und `engine` (Run-Loop, Journal, Claims, Resilience).  
> **Ausgangspunkt:** eine Node anpassen, wenn ein Eingabe-Requirement nicht vorhanden ist.

---

## 1. Analyse der Ist-Architektur

### 1.1 Rollen

| Komponente | Verantwortung | Datei |
|---|---|---|
| `WorkflowExecutor` | startet/resumed einen Run, snapshot-tet die expandierte Definition | `workflow-runner/.../WorkflowExecutor.java` |
| `WorkflowStepProviderImpl` | deterministische Navigation (`getNextNodeId`), Dispatch, Circuit-Breaker, Events | `.../WorkflowStepProviderImpl.java` |
| `DefaultNodeDispatcher` | ausführende Node-Typen, Output-Contracts, Template-Resolution | `.../DefaultNodeDispatcher.java` |
| `WorkflowContext` | `nodeOutputs`, `workflowState`, `currentNodeId`, `nextNode`-Override | `.../WorkflowContext.java` |
| `WorkflowDefinition` | immutable Plan (Nodes, Edges, Agents, Schemas) | `.../model/WorkflowDefinition.java` |
| `QocEngine.runPrompt` | Step-Loop, Paused/Failed/WorkflowDone, Retry-Policy | `engine/.../QocEngine.java` |
| `WorkflowRunJournal` | Replay aus Journal, Snapshot, Resume | `.../WorkflowRunJournal.java` |

### 1.2 Was bereits vorhanden ist

1. **Dynamischer Override:** `WorkflowContext.state("nextNode")` erlaubt `WorkflowStepProviderImpl`, die nächste Node zu überschreiben (State-Machine). Genutzt von `loop`, `guard`, `applyPostGuard`.
2. **Circuit-Breaker:** `recordNodeExecution` + `maxNodeRepetitions` verhindert Endlos-Loops.
3. **Output-Contracts:** `NodeContractValidator` + `validateOutputContract` (strict → Exception, non-strict → Warnung).
4. **Resume:** Journal replay → `WorkflowContext.restore`, `loadDefinition` via version/hash.
5. **Resilience:** `ErrorClassifier`, `RetryPolicy`, `TriageService` (LLM), `LoopDetector`, `WebhookAlerter`.
6. **SubWorkflowExpander:** statische Expansion von Sub-Workflows vor dem Run.
7. **Input-Schema:** `WorkflowDefinition.inputSchema()` wird beim Start validiert.
8. **Child-Sessions:** Jede Agent-Node persistiert ihre Child-Session-ID im Journal (`WorkflowNodeCompleted.childSessionId`, `WorkflowRunSnapshot.nodeSessions`). Das ermöglicht Session-Requery als Reparaturstrategie.

### 1.3 Lücke

- Ein Run arbeitet auf **einem** immutable Snapshot. Es gibt keinen Mechanismus, während des Runs einen abgeleiteten Plan zu erzeugen, zu validieren und darauf umzuschalten.
- Bei Node-Failure propagiert `WorkflowStepProviderImpl` `StepResult.Failed` → `QocEngine` retry-t dieselbe Node bis `maxAttempts`, dann Run-Abbruch. Es gibt kein plan-level Recovery.
- "Input-Requirement fehlt" wird erst beim Dispatch erkannt (strict Template-Resolution wirft `UnresolvedPlaceholderException` → Failed). Keine Preflight-Prüfung, keine alternative Route.
- Der `nextNode`-Override ist ein flaches Signal, kein stack- bzw. graph-basiertes Konstrukt; Wiedereintritt in den Originalplan ist nur manuell möglich.
- Resume lädt die ursprüngliche Definition; ein zur Laufzeit angepasster Plan wäre nach Neustart verloren.

---

## 2. Ziel & Invarianten

**Ziel:** Ein Workflow hat einen Originalplan (immutable, versioniert). Im Fehlerfall übernimmt ein Supervisor, erzeugt einen **abgeleiteten Plan** für die nächsten Schritte und führt den Run zurück auf den Originalplan (Rejoin). Der Originalplan wird nie mutiert.

**Invarianten (hard):**

1. `I-RPA-1` Originalplan-Immutabilität: `basePlanVersion` ändert sich nie; abgeleitete Pläne sind neue Versionen mit Lineage.
2. `I-RPA-2` Determinismus: Navigation bleibt deterministisch; Supervisor-Entscheidungen werden als Events journaled und sind replaybar.
3. `I-RPA-3` Boundedness: jede Adaptation hat ein Budget (max. Nodes, Steps, Tokens, Zeit); Überschreitung → Abbruch/Eskalation.
4. `I-RPA-4` No silent skip: ein erforderliches Input-Requirement darf nicht stillschweigend zu `null` werden (strict).
5. `I-RPA-5` Completed-node protection: bereits abgeschlossene Nodes dürfen nicht neu definiert oder übersprungen werden.
6. `I-RPA-6` Rejoin-Pflicht: jeder Adaptationsplan muss einen definierten Rejoin-Anker enthalten; ohne Rejoin wird nicht ausgeführt.
7. `I-RPA-7` Idempotenz: Resume nach Crash führt den zuletzt akzeptierten Plan fort (kein Zurückfallen auf ältere Version).
8. `I-RPA-8` Validierung vor Ausführung: abgeleitete Pläne durchlaufen denselben `GraphCompiler` wie Originalpläne.
9. `I-RPA-9` Blast-Radius vor Reparatur: vor jeder Reparatur wird der betroffene Downstream-Bereich (statische Abhängigkeitsanalyse) berechnet und journaled; eine Reparatur ohne Blast-Radius-Berechnung wird abgelehnt.
10. `I-RPA-10` Kein stilles Auto-Replay side-effecting Nodes: Nodes mit Seiteneffekten (mcp_call, rest_call, agent mit Tools) werden nur dann automatisch re-ausgeführt, wenn Idempotenz oder Kompensation deklariert ist; sonst HITL.

---

## 3. Kernabstraktionen

### 3.1 Plan & Lineage

```java
public record PlanRef(
    String planId,          // = workflowId
    String version,         // "1.0.0", "1.0.0+adapt-3"
    String parentVersion,   // null für Base
    PlanKind kind,          // BASE | ADAPTED
    String rejoinAnchor,    // Node-ID, an der zurückgekehrt wird (null bei BASE)
    Instant createdAt
) {}

public enum PlanKind { BASE, ADAPTED }
```

- `WorkflowDefinition` bleibt unverändert; die Versionierung liegt im `PlanRef` + Journal.
- Ein Adapted Plan ist eine **Kopie** der Base-Definition plus Insertions/Patches, validiert durch `GraphCompiler`. Die Base bleibt referenzierbar.
- Lineage wird im Journal als `PlanVersionChanged`-Event festgehalten.

### 3.2 Requirement-Modell

```java
public record InputRequirement(
    String id,              // z.B. "req:nodeX:customer.id"
    String consumerNodeId,
    String ref,             // "${state.customer.id}", "initial.id", "upstream.output.id"
    RequirementKind kind,    // NODE_OUTPUT | STATE | INITIAL | LAST_RESULT | ARTIFACT
    boolean required,
    String expectedType,    // optional JSON-Schema-Typ
    String producerNodeId   // optional: Node, die das liefern sollte
) {}

public enum RequirementKind { NODE_OUTPUT, STATE, INITIAL, LAST_RESULT, ARTIFACT }

public record RequirementGap(
    InputRequirement requirement,
    GapReason reason,
    GapSeverity severity,    // ERROR (required) | INFO (optional, nicht an Supervisor)
    String evidence          // z.B. "node 'fetch-customer' not executed in this branch"
) {}

public enum GapSeverity { ERROR, INFO }

public enum GapReason {
    PRODUCER_NOT_EXECUTED,   // Producer existiert, aber nicht im Pfad
    OUTPUT_MISSING,          // Producer lief, aber kein Output
    FIELD_MISSING,           // Output vorhanden, Feld fehlt
    TYPE_MISMATCH,           // Feld vorhanden, Typ falsch
    CONTRACT_VIOLATION,      // Output-Contract verletzt
    UNKNOWN_REFERENCE        // Ref zeigt auf nicht existierende Node
}
```

### 3.3 Adaptationsplan

```java
public sealed interface PlanChange permits
    InsertNode, PatchNode, InsertEdge, RemoveEdge, SkipNode, Rejoin,
    RequerySession, ReplayFrom {}

public record InsertNode(String nodeId, WorkflowDefinition.Node node, String insertBefore) implements PlanChange {}
public record PatchNode(String nodeId, WorkflowDefinition.Node replacement, List<String> changedFields) implements PlanChange {}
public record InsertEdge(String edgeId, WorkflowDefinition.Edge edge) implements PlanChange {}
public record RemoveEdge(String edgeId) implements PlanChange {}
public record SkipNode(String nodeId, String reason) implements PlanChange {}
public record Rejoin(String anchorNodeId) implements PlanChange {}
public record RequerySession(
    String producerNodeId,       // Node, deren Session nachgefragt wird
    String childSessionId,      // persistierte Child-Session-ID
    String followupPrompt,      // Nachfrage-Prompt
    String targetRequirementId, // welches Requirement damit befriedigt wird
    String resultKey            // unter welchem Key das Folge-Ergebnis gespeichert wird
) implements PlanChange {}
public record ReplayFrom(
    String originNodeId,        // erste Node, die den fehlenden Wert transitiv konsumiert hat
    List<String> nodeIds,       // zu invalidierende/re-auszuführende Nodes (topologisch sortiert)
    String reason               // z.B. "stale downstream output after filling X"
) implements PlanChange {}

public record AdaptationPlan(
    String adaptationId,
    String triggerEvent,     // NodeFailed | RequirementGap | ContractViolation | ...
    String baseVersion,
    List<PlanChange> changes,
    String rejoinAnchor,     // Pflicht (I-RPA-6)
    AdaptationBudget budget,
    String rationale,
    AdaptationSource source  // RULE | LLM_TRIAGE | HUMAN
) {}

public record AdaptationBudget(
    int maxInsertedNodes,
    int maxAdditionalSteps,
    int maxAdditionalTokens,
    java.time.Duration maxAdditionalTime
) {}
```

### 3.4 Supervisor

```java
public interface WorkflowSupervisor {
    /**
     * Liefert einen Adaptationsplan oder "no adaptation" (Optional.empty()).
     * Muss deterministisch/validierbar sein; LLM-Vorschläge werden als
     * AdaptationPlan normalisiert und dann validiert.
     */
    Optional<AdaptationPlan> propose(AdaptationContext ctx);
}

public record AdaptationContext(
    String runId,
    String workflowId,
    String currentPlanVersion,
    String blockedNodeId,
    GapReason gapReason,
    List<RequirementGap> gaps,
    WorkflowContext workflowContext,
    WorkflowDefinition currentPlan,
    Map<String, String> availableChildSessions,   // nodeId -> childSessionId
    List<String> completedNodeIds,
    Throwable failure,
    int adaptationCountSoFar
) {}
```

Implementierungen:
- `RuleBasedSupervisor`: deterministische Regeln (z. B. "Requirement fehlt → Producer-Node nachholen, falls vorhanden").
- `LlmSupervisor`: nutzt `TriageService`-ähnlichen Aufruf, liefert JSON-`AdaptationPlan`.
- `HumanSupervisor`: pausiert den Run (HITL) und wartet auf manuellen Plan.

### 3.5 Runtime Plan Container

```java
public final class RuntimePlan {
    private final WorkflowDefinition definition;   // aktuell aktiver Plan
    private final PlanRef ref;
    private final Set<String> injectedNodeIds;
    private final Set<String> patchedNodeIds;
    private final String rejoinAnchor;
    // ...
    public WorkflowDefinition definition() { return definition; }
    public PlanRef ref() { return ref; }
}
```

`WorkflowStepProviderImpl` hält statt nur `def` ein `RuntimePlan` (initial Base). Navigation nutzt `runtimePlan.definition()`. Adaptation tauscht den `RuntimePlan` atomar (volatile + Claim-Schutz).

---

## 4. Runtime-Flow

### 4.1 Normalpfad (unverändert)

```
getNextNodeId(basePlan, ctx) → dispatch node → success → setCurrentNodeId → ...
```

### 4.2 Preflight vor Dispatch (neu)

Vor `dispatcher.dispatch(node, ...)` prüft `WorkflowStepProviderImpl`:

1. `RequirementChecker.check(node, def, ctx)` → `List<RequirementGap>`.
2. Wenn keine Gaps → dispatch wie bisher.
3. Wenn Gaps und Node ist `required`:
   - `AdaptationContext` bauen.
   - `supervisor.propose(ctx)` aufrufen.
   - Falls Plan vorhanden und valid → Adaptation anwenden → `nextNode` auf ersten eingefügten Node setzen (oder Rejoin, falls nichts einzufügen) → journaln → weiter.
   - Falls kein Plan / invalid → `StepResult.Failed` (mit Gap-Details) → Engine-Retry/Eskalation.
4. Wenn Gaps und Node ist `optional` (non-required): markiere `state("degraded:<node>") = gaps`, dispatch trotzdem (legacy-Passthrough, aber geloggt).

### 4.3 Anwendung einer Adaptation

```
apply(plan):
  1. Validierung (GraphCompiler + Invarianten I-RPA-3/5/6/8)
  2. Neuen RuntimePlan bauen (derived version)
  3. Journal: PlanVersionChanged(base→derived), AdaptationApplied(adaptationId, changes)
  4. ctx.state("nextNode") = firstInsertedNode (oder rejoinAnchor)
  5. ctx.state("_activeAdaptation") = adaptationId
  6. ctx.state("_adaptationBudget") = remaining
```

Navigation in der Adaptation nutzt die derived Definition. Nach Erreichen des `rejoinAnchor`:
- `ctx.state("nextNode")` wird auf `null` gesetzt (normaler Pfad).
- Journal: `AdaptationRejoined(adaptationId, anchorNodeId)`.
- `_activeAdaptation` wird entfernt.
- Weiter auf Originalplan ab Anker.

### 4.4 Rückkehr zum Originalplan

Der Rejoin-Anker ist eine Node-ID, die in **beiden** Plänen existiert (Completed-node protection). Beim Erreichen:
- `currentPlanVersion` wird im Journal als "rejoined" markiert.
- `RuntimePlan` wird auf die Base-Version zurückgesetzt (oder bleibt derived, aber ohne aktive Adaptation; derived Definition behält die Base-Nodes, daher ist der Pfad identisch). Empfehlung: RuntimePlan bleibt derived (für Audit), aber `_activeAdaptation=null`; Navigation nutzt weiter die derived Definition, die Base als Subgraph enthält. Das vereinfacht Resume.

### 4.5 Budget & Abbruch

- Jede Adaptation zählt gegen `adaptationCountSoFar`. Max. `MAX_ADAPTATIONS_PER_RUN` (z. B. 5).
- Budget-Überschreitung → `StepResult.Failed` mit `FailureKind` + Webhook/HITL.
- Circuit-Breaker (`maxNodeRepetitions`) bleibt aktiv; Adaptationen dürfen ihn nicht umgehen.

---

## 5. Erster Slice: "Input-Requirement fehlt"

### 5.0 Requiredness: wann ist ein fehlender Wert ein Gap?

**Grundsatz:** Nicht jeder `null`-Wert ist ein Fehler. Viele Inputs sind optional, und `null` ist ein legitimes Ergebnis. Der Supervisor darf nur bei **explizit erforderlichen** Inputs eingreifen, sonst erzeugt er Rauschen und unnötige Reparaturen.

**Regeln:**

1. **Default = optional.** `InputRequirement.required` ist standardmäßig `false`. Ein fehlender optionaler Wert erzeugt **keinen** Gap, sondern wird als `null` durchgereicht (mit `debug`-Log).
2. **Explizite Deklaration hat Vorrang.** `NodeData.inputRequirements` kann `required: true/false` pro Ref setzen. Nur `required=true` wird zum Gap.
3. **Suffix-Konvention in Templates:** `${node.output.field!}` (mit `!`) markiert ein Feld als required; ohne `!` ist es optional. Analog für `contextRefs`: `"node.field!"`.
4. **Schema-basierte Ableitung (optional):** Wenn ein Upstream-Node einen `outputContract` mit `required: [...]` deklariert und der Downstream genau dieses Feld konsumiert, wird es als required behandelt. Ohne Output-Contract → optional.
5. **Node-Level-Strictness:** `NodeData.strict == true` macht **alle** referenzierten Inputs des Nodes required (analog zum bestehenden strict-Verhalten bei Output-Contracts). Non-strict (Default) → optional.
6. **Severity:** Ein required Gap ist `ERROR` (blockiert Dispatch); ein optional fehlender Wert ist `INFO` (Dispatch läuft, Wert ist `null`).

**Konsequenz für RequirementChecker:**

```java
for (InputRequirement r : requirements) {
    if (!r.required()) {
        if (!isSatisfied(r, ctx)) log.debug("optional input missing: {}", r.ref());
        continue; // kein Gap
    }
    if (!isSatisfied(r, ctx)) gaps.add(new RequirementGap(r, ..., GapSeverity.ERROR));
}
```

Optional fehlende Werte werden **nicht** an den Supervisor übergeben.

### 5.1 Ausgangssituation

Node `analyze-order` hat Prompt:
```
Analysiere Bestellung ${order-fetch.output.orderId} ...
```
oder `contextRefs: ["order-fetch.orderId"]`.

`order-fetch` wurde in einem anderen Branch nicht ausgeführt (z. B. Bedingung `status=='VIP'` war false) → Output fehlt → strict Template-Resolution wirft `UnresolvedPlaceholderException` → Node failed.

### 5.2 Erkennung (RequirementChecker)

```java
public final class RequirementChecker {
    public static List<RequirementGap> check(WorkflowDefinition.Node node,
                                            WorkflowDefinition def,
                                            WorkflowContext ctx) {
        List<RequirementGap> gaps = new ArrayList<>();
        // 1. Explizite Requirements (neues Feld NodeData.inputRequirements)
        for (InputRequirement r : node.data().inputRequirements()) {
            if (!isSatisfied(r, ctx)) gaps.add(new RequirementGap(r, reason(r, ctx), evidence(r, ctx)));
        }
        // 2. Implizite: Template-Placeholders in title/defaultTarget/mcpArgs
        for (String ref : extractRefs(node)) {
            // ... wie oben, mit required=true
        }
        // 3. contextRefs
        for (String cr : node.data().contextRefs()) {
            if (ContextRefResolver.resolve(ctx, cr) == null) gaps.add(...);
        }
        return gaps;
    }
}
```

### 5.3 Supervisor-Strategien (regelbasiert, in dieser Reihenfolge)

0. **Session-Requery (neu, bevorzugt):** Wenn ein Producer-Node existiert **und** eine persistierte Child-Session-ID für ihn vorliegt (`WorkflowRunSnapshot.nodeSessions` / Metadaten im Kontext), und der Gap `FIELD_MISSING`, `OUTPUT_MISSING`, `CONTRACT_VIOLATION` oder `TYPE_MISMATCH` ist:
   - Der Supervisor erzeugt einen `RequerySession`-Change: die bestehende Child-Session wird mit einem Nachfrage-Prompt wiederaufgenommen (z. B. *"Deine vorherige Ausgabe war: … . Es fehlt Feld X. Bitte liefere X als JSON."*).
   - Das Folge-Ergebnis wird unter `resultKey` (z. B. `producerNodeId + ".followup"`) abgelegt; die blockierte Node wird darauf umverdrahtet (`contextRefs`/Template auf `resultKey`).
   - **Vorteile:** kein Neustart des Producers, Kontext (Reasoning) bleibt erhalten, minimaler Token-Verbrauch, keine Graph-Mutation.
   - **Einschränkung:** nur sinnvoll, wenn die Child-Session noch resumable ist und der Producer-Agent den Wert liefern kann; bei `PRODUCER_NOT_EXECUTED` (Producer nie gelaufen) nicht anwendbar → Fallback auf Strategie 1.
   - **Journal:** `SessionFollowupRequested`, `SessionFollowupCompleted` (mit Child-Session-ID und Tokens).

1. **Producer nachholen (Reroute):** Wenn `producerNodeId` bekannt und in der Definition vorhanden, aber nicht in `completedNodeIds`:
   - Adaptationsplan: `InsertEdge`? Nein — stattdessen `ctx.state("nextNode") = producerNodeId`, und nach dessen Erfolg zurück zur blockierten Node. Implementiert als Mini-Plan mit zwei virtuellen "Guard"-Nodes? Einfacher: Supervisor setzt `nextNode = producerNodeId` und merkt `state("_pendingNode") = blockedNodeId`. `WorkflowStepProviderImpl` prüft `_pendingNode` nach Abschluss einer Node: wenn gesetzt und Node == producer → `nextNode = _pendingNode`, `_pendingNode = null`. Das nutzt den vorhandenen Override-Mechanismus, keine Graph-Mutation.
   - Vorteil: minimaler Eingriff, kein neuer Plan nötig.
   - Nachteil: nur linear; keine komplexen Reparaturen.

2. **Adapted Plan (InsertNode):** Wenn kein Producer existiert, aber ein MCP-Tool/Agent den Wert liefern kann:
   - Supervisor erzeugt einen `fetch-*`-Node (z. B. `mcp_call` oder `agent`) und fügt ihn vor der blockierten Node ein. Rejoin-Anker = blockierte Node.
   - Validierung durch GraphCompiler.

3. **Node anpassen (PatchNode):** Wenn das Requirement optional gemacht werden kann:
   - Prompt so ändern, dass die fehlende Information explizit als "nicht verfügbar" behandelt wird (z. B. `"... (falls orderId fehlt, frage nach)"`).
   - Oder `contextRefs` auf verfügbare Quellen umstellen.
   - Nur erlaubt, wenn `required=false` oder Supervisor klassifiziert als `DEGRADABLE`.

4. **HITL (HumanSupervisor):** Wenn keine automatische Reparatur möglich oder Budget erschöpft → `NodeResult.paused(approvalId, ...)` mit Diagnose; Resume nach menschlicher Eingabe.

### 5.4 Beispiel-Adaptationsplan (Reroute + InsertNode)

```json
{
  "adaptationId": "adapt-1",
  "triggerEvent": "RequirementGap:PRODUCER_NOT_EXECUTED",
  "baseVersion": "1.0.0",
  "changes": [
    { "type": "InsertNode", "nodeId": "fetch-order", "insertBefore": "analyze-order" },
    { "type": "InsertEdge", "edge": { "source": "fetch-order", "target": "analyze-order" } }
  ],
  "rejoinAnchor": "analyze-order",
  "budget": { "maxInsertedNodes": 1, "maxAdditionalSteps": 3, "maxAdditionalTokens": 4000, "maxAdditionalTime": "PT2M" },
  "rationale": "order-fetch wurde im VIP-Branch übersprungen; nachträglich ausführen",
  "source": "RULE"
}
```

---

### 5.5 Divergenz, Blast Radius und deterministische Reparatur

**Problem:** Fehlende Daten müssen nicht an der Stelle auftreten, an der sie erzeugt werden. Ein Feld, das 5 Schritte früher fehlte, kann in den Zwischenschritten zu divergierenden Ergebnissen geführt haben. Ein bloßes "Feld nachfüllen" an der Quelle lässt diese Divergenz bestehen.

**Deterministische Vorarbeit (kein LLM):**

1. **Statische Abhängigkeitsanalyse (Compile-Zeit):** Für jede Node wird berechnet, welche Upstream-Felder sie konsumiert (aus Templates `${...}`, `contextRefs`, `mcpArgs`). Ergebnis: `DependencyGraph` (consumer → set of (producer, field)).
   - Implementierung: statische Auswertung analog `TemplateResolver.findProblems` / `ContextRefResolver.findProblems`, persistiert im Plan-Snapshot.
2. **Blast-Radius-Berechnung (Laufzeit):** Wenn an Node `F` ein Gap für `(producer P, field X)` erkannt wird, ist der Blast Radius = alle Nodes, die transitiv von `(P, X)` abhängen und bereits ausgeführt wurden (topologisch sortiert, P eingeschlossen). Dies ist deterministisch.
3. **Reparatur-Modus (Policy, deterministisch aus Metadaten):**
   - `PATCH_ONLY`: Wenn keine Downstream-Node das fehlende Feld tatsächlich konsumiert hat (Blast Radius = {P}), reicht das Nachfüllen an P. Downstream bleibt unverändert.
   - `REPLAY_FROM(origin)`: Wenn Downstream-Nodes das Feld konsumiert haben, werden alle Nodes im Blast Radius (außer P, dessen Output gepatcht wird) invalidiert und in topologischer Reihenfolge neu ausgeführt (`ReplayFrom`-Change). Origin = erste Downstream-Node, die das Feld konsumiert.
   - `COMPENSATE_AND_REPLAY`: Wenn Nodes im Blast Radius Seiteneffekte haben und nicht idempotent sind, werden zuerst ihre Kompensationen ausgeführt (SAGA, `docs/engineering/SAGA.md`), dann Replay.
   - `ESCALATE`: Wenn Kompensation nicht deklariert oder Budget erschöpft → HITL.

**Determinismus der Reparatur:**

- Gap-Erkennung, Blast-Radius und Moduswahl sind deterministisch (kein LLM).
- Die *Inhalte* eines Session-Requery oder InsertNode sind LLM-generiert, aber der *Schritt* (welche Node, welcher Prompt-Template, welcher Output-Contract) ist durch den Adaptationsplan festgelegt und validiert.
- Re-Execution-Reihenfolge ist topologisch deterministisch.
- Journaling macht jeden Schritt replaybar: Original-Completion-Events bleiben; Re-Execution erzeugt `NodeInvalidated` + `NodeReexecuted`-Events mit neuem Output. Resume nutzt den letzten Output pro Node.

**Idempotenz/Side-Effects:**

- `NodeData` erhält optional `idempotencyKey` (Template, z. B. `${node.output.id}`) und `compensation` (Agent/Prompt oder MCP-Tool).
- Der Supervisor prüft vor Auto-Replay: `idempotent || compensation != null`; sonst `ESCALATE`.
- Reine Nodes (`llm_eval`, `llm_summarize`, `decision`, `compact`) sind standardmäßig idempotent.

**Konsistenz nach Reparatur:**

- Nach `REPLAY_FROM` wird der Rejoin-Anker auf die letzte Node des Blast Radius gesetzt, nicht auf die ursprünglich blockierte Node, damit keine stale Downstream-Outputs stehen bleiben.
- Der Originalplan bleibt unverändert; die derived Version enthält die invalidierten/re-ausgeführten Nodes als neue Execution-Generation (markiert via `executionGeneration` in `WorkflowContext`).

## 6. Journal-Events (neu)

```java
sealed interface SessionEvent permits ...,
    PlanVersionChanged, AdaptationProposed, AdaptationApplied,
    AdaptationRejoined, AdaptationAbandoned, RequirementGapDetected {}

record PlanVersionChanged(SessionId session, String runId, String fromVersion, String toVersion,
                          String reason, Instant time) implements SessionEvent {}
record AdaptationProposed(SessionId session, String runId, String adaptationId,
                         String trigger, String source, String rationale, Instant time) {}
record AdaptationApplied(SessionId session, String runId, String adaptationId,
                        List<String> changedNodeIds, String rejoinAnchor, Instant time) {}
record AdaptationRejoined(SessionId session, String runId, String adaptationId,
                          String anchorNodeId, Instant time) {}
record AdaptationAbandoned(SessionId session, String runId, String adaptationId,
                           String reason, Instant time) {}
record RequirementGapDetected(SessionId session, String runId, String nodeId,
                             List<String> gapRefs, String severity, Instant time) {}
record SessionFollowupRequested(SessionId session, String runId, String adaptationId,
                               String producerNodeId, String childSessionId,
                               String followupPrompt, Instant time) {}
record SessionFollowupCompleted(SessionId session, String runId, String adaptationId,
                               String producerNodeId, String childSessionId,
                               String resultKey, Object result, Instant time) {}
record NodeInvalidated(SessionId session, String runId, String adaptationId,
                      String nodeId, String reason, int executionGeneration, Instant time) {}
record NodeReexecuted(SessionId session, String runId, String adaptationId,
                     String nodeId, Object newOutput, int executionGeneration, Instant time) {}
```

Resume nutzt die letzte `PlanVersionChanged`-Version; ohne solche Events → Base-Version (kompatibel).

---

## 7. Validierung & Safety-Gates

Vor Anwendung eines `AdaptationPlan`:

1. `GraphCompiler.validate(derivedDefinition)` (Syntax, DAG, Invarianten).
2. `PlanValidator` (neu) prüft:
   - `rejoinAnchor` existiert in Base und Derived.
   - Keine completed Node mutiert/entfernt.
   - `maxInsertedNodes`/`budget` eingehalten.
   - Keine Zyklen durch Insertions.
   - Keine neuen Agent-Refs ohne RBAC-Prüfung (`agentAllowed`).
   - Keine neuen MCP-Server/Tools ohne Allowlist.
3. Bei Fehler → `AdaptationAbandoned` + `StepResult.Failed` (kein teilweiser Apply).

---

## 8. Resume & Replay

- `WorkflowRunJournal.load` liest zusätzlich die letzte aktive Plan-Version und `_activeAdaptation`.
- `WorkflowExecutor.resume` lädt die derived Definition (`store.version`/`findByHash` wie heute) — funktioniert, weil Adaptationen als neue Versionen gesnapshot-tet werden.
- `WorkflowContext.restore` wird um `_activeAdaptation`, `_pendingNode`, `_adaptationBudget` erweitert (analog `_nodeExecutionCounts`).

---

## 9. Verhältnis zu Engine-Resilience

| Ebene | Zuständig für | Mechanismus |
|---|---|---|
| Engine (`QocEngine`) | transiente Provider-Fehler, Timeouts, Loops | RetryPolicy, ErrorClassifier |
| Workflow-Supervisor | plan-level Fehler, fehlende Inputs, Vertragsverletzungen | AdaptationPlan, Rejoin |

Integration: `WorkflowStepProviderImpl` fängt Node-Failure ab. Wenn `ErrorClassifier` den Fehler als `CORRECTABLE` einstuft und der Supervisor einen Plan liefert → Adaptation statt Engine-Retry. Sonst → `StepResult.Failed` → Engine-Retry wie bisher. Nach Erschöpfung der Engine-Retries → Supervisor-Eskalation (HITL/Webhook).

---

## 10. API & UI

- `WorkflowExecution` erhält `planVersion`, `adaptationCount`, `lastAdaptationId`.
- HTTP: `GET /run/{id}/plan` (aktuelle + Base-Version + Lineage), `POST /run/{id}/adapt` (manueller Adaptationsplan, nur mit Approval).
- UI: Badge "Adapted (n)" am Run; Diff-View Base vs. Derived; Rejoin-Marker.

---

## 11. Testplan (erster Slice)

1. `RequirementCheckerTest`: fehlender Producer, fehlendes Feld, Typ-Mismatch, Contract-Violation.
2. `DependencyGraphTest`: statische Extraktion von konsumierten Feldern; transitive Blast-Radius-Berechnung.
3. `SessionRequerySupervisorTest`: vorhandene Child-Session → Requery-Plan; kein Producer → Fallback.
4. `RuleBasedSupervisorTest`: Reroute bei fehlendem Producer; InsertNode bei fehlendem MCP-Wert; HITL bei unbekanntem Ref.
5. `PlanValidatorTest`: completed-node mutation abgelehnt; Zyklus abgelehnt; fehlender Rejoin abgelehnt.
6. `WorkflowStepProviderImplAdaptationTest`: Preflight → Adaptation → Ausführung eingefügter Node → Rejoin → Fortsetzung Originalplan; Resume nach simuliertem Crash.
7. `WorkflowRunJournalTest`: PlanVersionChanged/Adaptation*-Events replay → korrekter State.

---

## 12. Phasen (Implementierung)

- **Phase A (minimal, 2-3 Tage):** RequirementChecker + **Session-Requery** (bevorzugt, wenn Child-Session vorhanden) + Reroute-Fallback. Journal-Events für Gap, Requery, Reroute. Tests.
- **Phase B:** `AdaptationPlan` + `RuntimePlan` + `PlanValidator` + InsertNode/PatchNode. Journal-Versionierung. Resume.
- **Phase C:** LlmSupervisor via TriageService; HITL-Integration; UI/API.
- **Phase D:** Budgets, Metriken, Hardening.

---

## 13. Nicht-Ziele (vorerst)

- Keine Änderung des Originalplans zur Laufzeit (nur derived Versions).
- Kein automatisches Löschen von Nodes.
- Keine parallelen Adaptationen (seriell, claim-geschützt).
- Keine UI-Editor-Mutation während des Runs (nur Read-View).

---

## 14. Offene Entscheidungen

1. Soll `inputRequirements` explizit in `NodeData` (neues Feld) oder rein implizit aus Templates abgeleitet werden? Empfehlung: beides, explizit hat Vorrang.
2. Reroute vs. InsertNode als Default bei fehlendem Producer: Empfehlung Reroute (minimaler Eingriff).
3. Wie viele Adaptationen pro Run? Empfehlung 5, konfigurierbar.
4. Soll der LlmSupervisor denselben Provider wie der Run nutzen oder einen schnelleren? Empfehlung: schnellerer Triage-Provider (`ENGINE_TRIAGE_MODEL`).

---

## 15. Referenzen

- `docs/engineering/ReAct-Workflow.md` (statische Navigation, Nachteil "starr")
- `docs/resilience.md` (Phasen 0-4, Triage)
- `docs/engineering/Task-Orchestration.md` (DAG, Tiers)
- `docs/engineering/SAGA.md` (Kompensationen, Journal)
- `docs/engineering/Workflow-SDLC.md` (Orchestrator als WorkflowDefinition)
- `engine/runtime/resilience/*` (ErrorClassifier, TriageService, RetryPolicy)
- `workflow-runner/runner/WorkflowStepProviderImpl.java` (`nextNode`-Override, Circuit-Breaker)
- `workflow-runner/runner/DefaultNodeDispatcher.java` (`validateOutputContract`, strict)
- `workflow-runner/runner/TemplateResolver.java` (strict Resolution)
