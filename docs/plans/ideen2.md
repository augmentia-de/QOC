# Ideen2 – Struktur, Nutzen und Umsetzungsreihenfolge

> **Status:** Strukturierungs- und Planungsdokument (2026-11-18).  
> **Grundlage:** `docs/plans/next/runtime-plan-adaptation.md`, `docs/plans/next/goal-driven-planning-comparison.md`, `docs/plans/next/ideen-summary.md`, `docs/plans/next/ideen.txt`.  
> **Ziel:** Bestand, Nutzen, Prioritäten, Reihenfolge und kleine Tests an einer Stelle bündeln.

---

## 1. Was wir haben (Bestand im Code)

| Baustein | Status | Bedeutung für die Ideen |
|---|---|---|
| `WorkflowDefinition` (immutable Nodes/Edges, `inputSchema`) | vorhanden | Basis-Plan, den eine Adaptation nie mutieren darf |
| `WorkflowStepProviderImpl` | vorhanden | deterministische Navigation, `nextNode`-Override, Circuit-Breaker |
| `WorkflowContext` | vorhanden | `nodeOutputs`, `workflowState`, `currentNodeId`, `lastResult` |
| `WorkflowRunJournal` | vorhanden | Replay/Resume, `nodeSessions` (Child-Session-IDs) |
| `DefaultNodeDispatcher` | vorhanden | Node-Ausführung, Output-Contracts, Template-Resolution |
| `TemplateResolver` / `ContextRefResolver` | vorhanden | `findProblems` für statische Referenzprüfung |
| `GraphCompiler` | vorhanden | Validierung von Plänen (DAG, Referenzen) |
| `NodeContractValidator` | vorhanden | Output-Contract-Prüfung |
| `ErrorClassifier`, `RetryPolicy`, `TriageService`, `LoopDetector`, `WebhookAlerter` | vorhanden | Resilience auf Engine-Ebene |
| `SubWorkflowExpander`, HITL-Pause | vorhanden | statische Expansion, Pausieren |
| 34 Testklassen im `workflow-runner` | vorhanden | gute Basis für Regressionstests |
| `InputRequirement`, `RequirementChecker` | **fehlt** | Grundlage für Preflight |
| `AdaptationPlan`, `RuntimePlan`, `PlanValidator`, `PlanRef` | **fehlt** | Runtime-Adaptation |
| `WorkflowSupervisor`-Implementierungen | **fehlt** | Entscheidungslogik |
| Blast-Radius / Replay / SAGA-Invalidierung | **fehlt** | Korrektheit nach Upstream-Korrektur |
| `NodeGoal` / LLM-Judge | **fehlt** (nur `verifyOnStop` für Tool-Checks) | fachliche Validierung |
| Goal→Plan-Generierung | **fehlt** | initiale Planerzeugung aus Ziel |
| Isomorphes Modell (Workflow = Kontext) | **teilweise** | langfristige Vereinfachung |

---

## 2. Wo es hilft und was wichtig ist

| Idee | Nutzen | Aufwand/Risiko | Priorität |
|---|---|---|---|
| **RequirementChecker + Preflight** | Fehler früh und strukturiert erkennen statt später `UnresolvedPlaceholderException` | niedrig, keine Verhaltensänderung im Report-Modus | **P0** |
| **Session-Requery** | fehlendes Feld ohne kompletten Re-Run nachfordern; nutzt vorhandene Child-Sessions | niedrig–mittel, kein Graph-Eingriff | **P0** |
| **Reroute (Producer nachholen)** | fehlenden Producer nachträglich ausführen; nutzt vorhandenen `nextNode`-Override | niedrig, linear | **P1** |
| **AdaptationPlan + PlanValidator + RuntimePlan** | echte Plan-Anpassung (Insert/Patch) mit Validierung | mittel–hoch | **P1** |
| **Blast-Radius + Replay + SAGA** | verhindert stale Downstream-Ergebnisse nach Upstream-Korrektur | hoch | **P2** |
| **NodeGoal / LLM-Judge** | fachliche Qualität statt nur Schema | mittel | **P2** |
| **LLM-Supervisor + HITL** | autonome Reparatur mit Eskalation | mittel | **P3** |
| **Goal→Plan (Phase 0)** | initiale Workflow-Erzeugung aus Ist-Zustand + Ziel | hoch | **P3** |
| **Isomorphes Modell** | keine Mapping-Hölle, ein Baum für Workflow + Kontext | sehr hoch (Refactor) | **P4** |

### Die drei wichtigsten Prinzipien

1. **Erst beobachten, dann reparieren.** RequirementChecker im Report-Modus liefert Daten, ohne Verhalten zu ändern.
2. **Base-Plan bleibt immutable.** Adaptationen sind derived Pläne mit Lineage; kein stilles Mutieren.
3. **Günstig vor teuer.** Code-Check → Regeln → LLM-Judge; kein LLM, wo deterministische Prüfung reicht.

### Die drei größten Fallstricke

- Auto-Replay von seiten-effekt-behafteten Nodes ohne Idempotenz/Kompensation.
- Adaptation ohne Rejoin-Anker → Run hängt oder springt falsch.
- Token-Bloat durch den gesamten Baum im Prompt (Subagent-Purging, selektives Scoping).

---

## 3. Reihenfolge (empfohlene Roadmap)

### Phase 0 – Baseline sichern (klein)

- Feature-Flag `ENGINE_ADAPTATION_ENABLED` (Default `false`).
- Regressionstests für bestehende Navigation, `nextNode`-Override, Circuit-Breaker, Resume.

### Phase A – Beobachten + minimal reparieren (MVP, empfohlen zuerst)

1. **A1: Requirement-Modell + Checker (Report-only).** Gaps erkennen, loggen, keine Aktion.
2. **A2: Preflight.** Required Gap → `Failed` mit Gap-Details; optionaler Gap → `degraded:<node>` + weiter.
3. **A3: Session-Requery.** Wenn Child-Session vorhanden → Nachfrage; Ergebnis unter `resultKey`.
4. **A4: Reroute.** Producer nachholen über `nextNode` + `_pendingNode`; danach zurück zur blockierten Node.

**Warum zuerst:** hoher Nutzen, kein Graph-Umbau, nutzt vorhandene Mechanismen.

### Phase B – Plan-Adaptation

5. **B1: `AdaptationPlan` + `PlanValidator`** (nur Modell + Validierung, noch keine Ausführung).
6. **B2: `RuntimePlan` + Apply (InsertNode/PatchNode) + Rejoin.**
7. **B3: Journal-Events + Resume** (`PlanVersionChanged`, `AdaptationApplied`, `AdaptationRejoined`).

### Phase C – Korrektheit nach Upstream-Korrektur

8. **C1: DependencyGraph + Blast-Radius.**
9. **C2: Replay/SAGA + Idempotenz-Gates** (`PATCH_ONLY`, `REPLAY_FROM`, `COMPENSATE_AND_REPLAY`, `ESCALATE`).

### Phase D – Autonomie + Qualität

10. **D1: LLM-Supervisor + HITL.**
11. **D2: `NodeGoal` + LLM-Judge** (Funnel: Schema → Regeln → Judge).

### Phase E – Goal-Driven Planning

12. **E1: Tool-Registry (MCP/OpenAPI) + Goal→DAG-Generierung.**
13. **E2: Self-Healing-Loop für ungültige Pläne; GraphCompiler-Validierung.**

### Phase F – Isomorphes Modell

14. **F1: Prüfen, ob Workflow-Kontext und Agent-Kontext bereits angleichbar sind.**
15. **F2: Langfristige Vereinheitlichung (nur wenn der Nutzen den Refactor rechtfertigt).**

---

## 4. Kleine, sinnvolle Tests (pro Phase)

### Phase 0

- `WorkflowStepProviderImplTest`: Navigation bei mehreren Outgoing-Edges, `nextNode`-Override, Circuit-Breaker.
- `WorkflowRunJournalTest`: Resume nach simuliertem Crash.

### Phase A1 – RequirementChecker

- Optional fehlend → **kein** Gap.
- Required fehlend → Gap `ERROR`.
- `PRODUCER_NOT_EXECUTED`, `OUTPUT_MISSING`, `FIELD_MISSING`, `TYPE_MISMATCH`, `CONTRACT_VIOLATION`, `UNKNOWN_REFERENCE`.
- `!`-Suffix im Template → required; ohne `!` → optional.
- `strict=true` → alle Refs required.

### Phase A2 – Preflight

- Keine Gaps → Dispatch unverändert.
- Required Gap + kein Supervisor → `Failed` mit Details (kein Silent Skip).
- Optional Gap → Dispatch, `state("degraded:<node>")` gesetzt.

### Phase A3 – Session-Requery

- Child-Session vorhanden → `RequerySession`-Change.
- Keine Child-Session → Fallback (kein Requery).
- Ergebnis landet unter `resultKey`; blockierte Node wird umverdrahtet.
- Journal: `SessionFollowupRequested` / `SessionFollowupCompleted`.

### Phase A4 – Reroute

- Producer existiert, nicht ausgeführt → `nextNode=producer`, danach `_pendingNode` zurück.
- Producer bereits ausgeführt → kein Reroute.
- Circuit-Breaker bleibt aktiv; Completed-Node wird nicht neu definiert.

### Phase B1 – PlanValidator

- Rejoin-Anker existiert in Base **und** Derived.
- Completed Node mutieren → abgelehnt.
- Zyklus durch Insertion → abgelehnt.
- Budget überschritten → abgelehnt.
- Neuer Agent/MCP ohne Allowlist → abgelehnt.

### Phase B2 – RuntimePlan

- Base-Plan nach Adaptation unverändert.
- Derived Plan enthält eingefügte Node.
- Rejoin setzt `_activeAdaptation=null`.
- Resume lädt letzte akzeptierte Version.

### Phase C1/C2 – Blast Radius

- Kein Consumer → `PATCH_ONLY`.
- Consumer vorhanden → `REPLAY_FROM`.
- Seiteneffekt-Node ohne Idempotenz → `ESCALATE`.
- Transitive Abhängigkeit wird korrekt erkannt.

### Phase D2 – NodeGoal

- Code-Regel pass/fail.
- LLM-Judge pass/fail (Stub, kein Live-LLM).
- Funnel: Schema-Fehler → **kein** LLM-Aufruf.

### Phase E1 – Goal→Plan

- Valider Plan → `GraphCompiler` grün.
- Ungültiges JSON → Self-Healing-Retry.
- Ungültige `targetNodeId` → Retry.
- Max. Retries erreicht → sauberer Fehler.

---

## 5. Empfehlung für den Start

**MVP = Phase A1 + A2 + A3** (Report-only Checker, Preflight, Session-Requery).  
Das ist klein, testbar, nutzt vorhandene Child-Sessions und verändert den Base-Plan nicht. Reroute (A4) direkt danach.

**Nicht zuerst:** Isomorphes Modell und Goal→Plan. Beide sind große Refactors und sollten erst nach einer stabilen Runtime-Adaptation angegangen werden.

### Offene Entscheidungen vor A1

1. `RequirementChecker` im `workflow-runner` (kennt Definition + Context) – Empfehlung: ja.
2. Explizite `inputRequirements` vs. implizit aus Templates – Empfehlung: beides, explizit gewinnt.
3. Report-only Default vs. direkt Preflight – Empfehlung: Report-only, dann Flag.
4. Max. Adaptationen pro Run – Empfehlung: 5.

---

## 6. Referenzen

- `docs/plans/next/runtime-plan-adaptation.md`
- `docs/plans/next/goal-driven-planning-comparison.md`
- `docs/plans/next/ideen-summary.md`
- `docs/plans/next/ideen.txt`
- `docs/engineering/ReAct-Workflow.md`
- `docs/resilience.md`
- `docs/engineering/Task-Orchestration.md`
- `docs/engineering/SAGA.md`