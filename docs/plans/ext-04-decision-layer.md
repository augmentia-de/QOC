# Erweiterung: Decision Layer (engine-weite Entscheidungsschicht)

**Wofür:** QOC nutzt Decision Models bisher nur im Workflow-Runner (`classify`-Node). Routing, Relationsurteile, Risiko und Context-Sufficiency haben keinen allgemeinen, engine-weiten Entscheidungsweg und greifen auf das LLM zurück oder sind hartcodiert. Diese Erweiterung macht Decision Models zu einem optionalen, engine-weiten Port, wiederverwendet den bestehenden `/v1/systemone`-Client und ermöglicht Flide als Self-hosted-Backend.

**Gewinn:** Schnellere und billigere Entscheidungen (Routing, Triage, Prompt-Relation, Risiko, Answerability) auf einem spezialisierten Decision-Modell statt auf dem großen Chat-LLM. Flide kann als Self-hosted-Drop-in dienen, ohne Codeänderung. Standardmäßig deaktiviert; bei Ausfall Fallback auf bestehendes Verhalten.

---

## 1. Prinzip: Optionalität

- Default `qoc.decision.enabled=false`.
- Decision Model ist ein **Port**, kein Pflichtpfad. Deaktiviert = alle Consumer fallen auf bestehendes Verhalten zurück (NoOp/LLM).
- Fail-open by default: ein Decision-Modell-Ausfall blockiert keinen Turn, es sei denn, `failClosed=true` ist gesetzt.
- Der Port ist framework-neutral und testbar (Fake-Adapter für Tests).

## 2. Port

`core/port/runtime/DecisionModel` (oder `core/contract/provider/DecisionModel`):

```
DecisionModel.decide(DecisionRequest) -> DecisionResult
```

- `DecisionRequest`: Aufgabentyp (z. B. `yes_no`, `choice`, `scale`, `classify`), Frage(n), Kontext, Modell-Hinweise.
- `DecisionResult`: typisierte Antwort, Wahrscheinlichkeiten/Margins, `noul`-Flag, Latenz.
- Null = `NoOpDecisionModel` (gibt „unknown/low confidence“ zurück, Consumer entscheidet Fallback).

Adapter: `TypeSafeDecisionAdapter` (HTTP, `/v1/systemone`), `FlideDecisionAdapter` (gleiche API, anderer Base-URL/Modell), `OllamaDecisionAdapter` (falls gewünscht).

## 3. Bestehende Infrastruktur wiederverwenden

- `TypeSafeDecisionClient` (bereits im Workflow-Runner) spricht `/v1/systemone`. Die Logik kann in `runtime/provider/client/` generalisiert werden, damit Engine und Workflow-Runner denselben Adapter nutzen.
- Flide serviert dieselbe API → **kein neuer Code**, nur Konfiguration (`baseUrl`, `model`).
- Die `classify`-Node bleibt unverändert; sie nutzt denselben Port.

## 4. Consumer (opt-in, alle fail-safe)

| Consumer | Ort | Heutiges Verhalten | Mit Decision Layer |
|---|---|---|---|
| **Triage** | `runtime/resilience/TriageService` | LLM-basiert oder NoOp | Decision Model für Retry-Klassifikation |
| **Loop Detection** | `runtime/resilience/LoopDetector` | Heuristik | Optionale Decision-Modell-Bestätigung |
| **Prompt-Relation** | `runtime/session/core/Admission` oder neuer `PromptRelationClassifier` | STEER/QUEUE hart | Klassifikation supplement/correction/independent/cancel/status |
| **Permission-Risk** | `runtime/permission/engine/PermissionEngine` | Regelkette | Optionales Risk-Scoring für ASK-Eskalation |
| **Context-Sufficiency** | `runtime/context/ContextBuilder` + Preflight | keine | `check_answerable`-artige Prüfung |
| **Triage/Verify-on-Stop** | `runtime/resilience/*` | LLM | Decision Model |

Jeder Consumer prüft `DecisionModel != null` und fällt sonst auf bestehendes Verhalten zurück.

## 5. Prompt-Relation-Router (L-06)

- Neue optionale Stufe bei der Prompt-Admission.
- Mapping auf bestehende QOC-Semantik:
  - `supplement`/`correction` → `STEER`
  - `independent` → `QUEUE`
  - `cancel` → `interrupt`
  - `status` → sofortige Fortschrittsantwort ohne neuen Turn
- Decision Model mit Confidence; unter Schwellwert fällt QOC auf heutiges Verhalten zurück (kein stilles Uminterpretieren).
- Neues Event `PromptRelationClassified` (Modell, Confidence, gewählte Zustellung). Keine neue Wahrheitsquelle.

## 6. Wo in QOC

| Ort | Änderung |
|---|---|
| `core/port/runtime/DecisionModel` | **Neu** (optional, null = NoOp). |
| `core/model/` | `DecisionRequest`, `DecisionResult`, `PromptRelation` Records. |
| `runtime/provider/client/` | `TypeSafeDecisionAdapter` (generalisiert aus Workflow-Runner), `FlideDecisionAdapter` (nur Config). |
| `runtime/resilience/` | Triage/Loop/Preflight nutzen optionalen Port. |
| `runtime/session/core/` | `PromptRelationClassifier` (optional). |
| `runtime/permission/engine/` | Optionales Risk-Scoring. |
| `runtime/context/` | Optionale Answerability-Prüfung. |
| `workflow-runner/` | `classify`-Node nutzt denselben Port/Adapter. |
| `runtime/QocEngine` | Wiring des optionalen Ports. |
| Tests | Bake-off (E1), Replay (E2), Fallback-Tests. |

## 7. Konfiguration

- `qoc.decision.enabled=false`
- `qoc.decision.backend=typesafe|flide|ollama`
- `qoc.decision.baseUrl=https://api.typesafe.ai`
- `qoc.decision.model=<model>`
- `qoc.decision.timeout=5s`
- `qoc.decision.failClosed=false`
- `qoc.decision.promptRelation.enabled=false`
- `qoc.decision.promptRelation.threshold=0.7`

## 8. Flide als Backend (L-01)

- Flide serviert `/v1/systemone` → gleicher Client, andere `baseUrl` + `model`.
- Kein neuer Code; nur Konfiguration + Docker-Compose-Profil `flide`.
- Bake-off (E1): Accuracy, p50/p95, Durchsatz, Kosten, Betrieb gegen TypeSafe und Ollama/tev1.

## 9. Was genau vergleichen

- **E1 Decision-Backend-Bake-off:** 200–500 QOC-Entscheidungen, goldene Labels, drei Backends, Metriken Accuracy/F1, p50/p95, Durchsatz, Kosten, Ausfallverhalten. Entscheidungsregel: Flide optional, wenn Accuracy ≤ 2 Prozentpunkte schlechter und p95 nicht schlechter.
- **E2 Prompt-Relation-Replay:** 100–200 Mehrfachanfragen, Labels, Decision Model vs. Heuristik vs. heutiges Verhalten. Entscheidungsregel: nur übernehmen, wenn False-STEER < 2 %.

## 10. Risiken

- Latenz bei der Prompt-Admission (Decision-Call vor Zustellung).
- Falsche Klassifikation führt zu falscher Zustellung (False-STEER stört laufende Turns).
- Kosten bei hohem Turn-Volumen.
- Ausfall des Decision-Backends → Fallback muss robust sein (fail-open default).

## 11. Nicht-Ziele

- Kein Ersatz für das Chat-LLM (Decision Models sind für Entscheidungen, nicht für Generierung).
- Keine Python/PyTorch-Inferenz im JVM-Prozess (HTTP-only).
- Kein automatisches Routing ohne Confidence-Schwellwert und Fallback.
- Keine Änderung am bestehenden `classify`-Node-Verhalten (nur Backend-Austausch optional).
- Kein Multi-Modell-Orchestrierung in Phase 1 (ein Backend pro Deployment).

---

## Status der Implementierung (22.11.2026)

**Bereits implementiert (Engine-Modul):**
- Domain-Typen: `core/model/PromptRelation`, `DecisionRequest`, `DecisionResult`.
- Port: `core/port/runtime/DecisionModel` (decide, isAvailable).
- NoOp: `core/noop/NoOpDecisionModel`.
- `ExtensionPorts` bündelt `decision` (NoOp-Default).
- `QocEngine.decisionModel()`, `QocEngine.classifyPromptRelation(...)` Helper (fail-open).
- `ext-01`-analog: keine SQL-Adapter nötig (stateless HTTP).

**Noch offen:** Quarkus-Wiring + Config, Consumer-Integration (Triage, Loop, Preflight, Prompt-Relation, Permission-Risk, Context-Sufficiency), TypeSafe-Client-Generalisierung aus Workflow-Runner, Flide-Bake-off, Prompt-Relation-Event.

## Offene Todos (detailiert)

### Todo D1 – Quarkus-Config + Wiring
- **Properties:** `qoc.decision.enabled`, `qoc.decision.backend` (typesafe|flide|ollama), `qoc.decision.baseUrl`, `qoc.decision.model`, `qoc.decision.timeout`, `qoc.decision.failClosed`, `qoc.decision.promptRelation.enabled`, `qoc.decision.promptRelation.threshold`.
- **Wo:** `Engine.java` Startup.
- **Wie:** `DecisionModel dm = enabled ? new TypeSafeDecisionAdapter(baseUrl, model, timeout) : new NoOpDecisionModel();` Dann `ExtensionPorts(..., dm, ...)`.

### Todo D2 – TypeSafe-Client generalisieren
- **Was:** Den bestehenden `TypeSafeDecisionClient` aus `workflow-runner` in `runtime/provider/client/TypeSafeDecisionAdapter` (oder `DecisionModelAdapter`) verschieben/generalisieren, damit Engine und Workflow-Runner denselben Adapter nutzen.
- **Wo:** `engine/src/main/java/de/augmentia/oc/engine/runtime/provider/client/TypeSafeDecisionAdapter.java` (neu), `workflow-runner/.../TypeSafeDecisionClient.java` (delegieren oder entfernen).
- **Wie:** Implementiert `DecisionModel`. POST `/v1/systemone` mit `DecisionRequest` → `DecisionResult`. Timeout, Retry, Fehlerklassifikation. Wire-Format wie bestehender Client.

### Todo D3 – Flide-Backend (Config-only)
- **Was:** Flide serviert dieselbe API → kein neuer Code, nur Config.
- **Wie:** `qoc.decision.backend=flide` setzt `baseUrl=http://flide:port`, `model=...`. Optional Docker-Compose-Profil `flide`.
- **Bake-off (E1):** 200–500 QOC-Entscheidungen, goldene Labels, drei Backends. Entscheidungsregel: Flide optional, wenn Accuracy ≤ 2 Prozentpunkte schlechter und p95 nicht schlechter.

### Todo D4 – TriageService nutzt DecisionModel
- **Was:** Wenn `decisionModel().isAvailable()`, Triage über Decision Model statt LLM.
- **Wo:** `runtime/resilience/TriageService`, `LlmTriageService`, `NoOpTriageService`.
- **Wie:** Neuer `DecisionTriageService implements TriageService`, delegiert an `DecisionModel.decide(new DecisionRequest("triage", ..., ...))`. Bei Fehler/Timeout Fallback auf NoOp oder LLM (per Config).

### Todo D5 – LoopDetector nutzt DecisionModel
- **Was:** Optionale Bestätigung von Loop-Erkennung.
- **Wo:** `runtime/resilience/LoopDetector`.
- **Wie:** Nach Heuristik-Trigger optional `DecisionModel.decide("loop_check", ...)`; nur bei Bestätigung als Loop behandeln. Fail-open (Heuristik bleibt maßgeblich).

### Todo D6 – PreflightChecker nutzt DecisionModel
- **Was:** Optionale `check_answerable`-artige Prüfung.
- **Wo:** `runtime/resilience/PreflightChecker`.
- **Wie:** Neue Regel `DecisionPreflightRule` (optional), nutzt `DecisionModel`. Fail-open.

### Todo D7 – Prompt-Relation-Router in Admission
- **Was:** Neue optionale Stufe bei Prompt-Admission; klassifiziert und mappt auf STEER/QUEUE/interrupt/status.
- **Wo:** `runtime/session/core/Admission` oder neuer `runtime/session/core/PromptRelationClassifier`.
- **Wie:** Wenn `qoc.decision.promptRelation.enabled`, `QocEngine.classifyPromptRelation(...)` aufrufen. Confidence ≥ threshold → Mapping; sonst Fallback auf heutiges Verhalten. Neues Event `PromptRelationClassified` (Modell, Confidence, gewählte Zustellung) — entweder neue `SessionEvent`-Variante oder separates Event im Journal (empfohlen: separates Event, um Projektionen nicht zu zwingen).
- **Replay-Test (E2):** 100–200 Mehrfachanfragen, Labels, False-STEER < 2 %.

### Todo D8 – PermissionEngine Risk-Scoring
- **Was:** Optionales Risk-Scoring für ASK-Eskalation.
- **Wo:** `runtime/permission/engine/PermissionEngine`.
- **Wie:** Wenn `decisionModel` verfügbar und Regel nicht eindeutig, `DecisionModel.decide("permission_risk", ...)`; bei hoher Risk → ASK/DENY, sonst ALLOW. Konfigurierbar.

### Todo D9 – ContextBuilder Answerability
- **Was:** Optionale Context-Sufficiency-Prüfung.
- **Wo:** `runtime/context/ContextBuilder` oder Preflight.
- **Wie:** `DecisionModel.decide("answerable", question + context)`; bei `false` → Synthetische Nachricht oder Retrieval-Trigger. Fail-open.

## Erkenntnisse für unabhängige Implementierung

- **`/v1/systemone` ist die gemeinsame API.** TypeSafe und Flide servieren dasselbe Format. Ein Adapter reicht; Backend-Wechsel ist Config-only. Bake-off (E1) entscheidet über Default.
- **Fail-open by default.** Decision-Modell-Ausfall darf keinen Turn blockieren, außer `failClosed=true`. `NoOpDecisionModel` gibt `noul=true` + leere Antworten; Consumer prüfen `isAvailable()` oder Confidence.
- **Prompt-Relation muss Confidence-Schwellwert haben.** Unter Schwellwert Fallback auf heutiges Verhalten (kein stilles Uminterpretieren). False-STEER ist teuer (stört laufende Turns).
- **Event `PromptRelationClassified` als separates Event**, nicht als `SessionEvent`-Variante, um alle Projektionen nicht zu zwingen (sealed interface → Compiler-Fehler überall).
- **TypeSafe-Client-Generalisierung** ist der größte Refactoring-Schritt (D2): Client aus Workflow-Runner nach Engine verschieben, Workflow-Runner delegiert. Gemeinsame Codebasis vermeidet Drift.
- **Kosten/Latenz-Budget** muss vor Rollout gemessen werden (E1, E2). Decision-Calls bei jedem Turn summieren sich.
- **Tests:** E1 (Backend-Bake-off), E2 (Prompt-Relation-Replay). Mindestens False-STEER < 2 % und p95 ≤ TypeSafe.
