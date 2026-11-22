# Erweiterung: Decision Layer (engine-weite Entscheidungsschicht)

**Wofür:** QOC nutzt Decision Models bisher nur im Workflow-Runner (`classify`-Node). Routing, Relationsurteile, Risiko und Context-Sufficiency haben keinen allgemeinen, engine-weiten Entscheidungsweg und greifen auf das LLM zurück oder sind hartcodiert. Diese Erweiterung macht Decision Models zu einem optionalen, engine-weiten Port, wiederverwendet den bestehenden `/v1/systemone`-Client und ermöglicht Flide als Self-hosted-Backend.

**Gewinn:** Schnellere und billigere Entscheidungen (Routing, Triage, Prompt-Relation, Risiko, Answerability) auf einem spezialisierten Decision-Modell statt auf dem großen Chat-LLM. Flide kann als Self-hosted-Drop-in dienen, ohne Codeänderung. Standardmäßig deaktiviert; bei Ausfall Fallback auf bestehendes Verhalten.

---

## 1. Prinzip: Optionalität

- Default `engine.decision.enabled=false`.
- Decision Model ist ein **Port**, kein Pflichtpfad. Deaktiviert = alle Consumer fallen auf bestehendes Verhalten zurück (NoOp/LLM).
- Fail-open by default: ein Decision-Modell-Ausfall blockiert keinen Turn. Ein `failClosed`-Modus ist bewusst nicht implementiert.
- Der Port ist framework-neutral und testbar (Fake-Adapter für Tests).

## 2. Port

`core/port/runtime/DecisionModel`:

```java
public interface DecisionModel {
    DecisionResult decide(DecisionRequest request);
    default boolean isAvailable() { return true; }
}
```

- `DecisionRequest(taskType, question, questions, context, options)`.
- `DecisionResult(taskType, answer, answers, probabilities, margin, noul, latencyMillis)` mit `confidence()`.
- Null/unavailable = `NoOpDecisionModel` (`isAvailable() == false`, `noul = true`); Consumer entscheidet Fallback.

Adapter: `runtime/provider/client/TypeSafeDecisionAdapter` (HTTP, `/v1/systemone`, nutzt `core.json.Json`, kein Jackson). Flide nutzt denselben Adapter (nur `baseUrl`/`model` anders); ein separater `FlideDecisionAdapter` ist nicht nötig. `OllamaDecisionAdapter` ist optional und nicht implementiert.

## 3. Bestehende Infrastruktur wiederverwenden

- `TypeSafeDecisionClient` (bereits im Workflow-Runner) spricht `/v1/systemone`. Die Logik wurde in `runtime/provider/client/TypeSafeDecisionAdapter` generalisiert; die Workflow-Runner-Delegation ist noch offen (D2/F5).
- Flide serviert dieselbe API → **kein neuer Code**, nur Konfiguration (`baseUrl`, `model`).
- Die `classify`-Node bleibt unverändert; sie nutzt denselben Port (sofern der Workflow-Runner später delegiert).

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

| Ort | Änderung | Status |
|---|---|---|
| `core/port/runtime/DecisionModel` | **Neu** (optional, null = NoOp). | ✅ |
| `core/model/` | `DecisionRequest`, `DecisionResult`, `PromptRelation`, `SessionEvent.PromptRelationClassified`. | ✅ |
| `runtime/provider/client/` | `SystemOneHttpClient` (gemeinsamer Transport), `TypeSafeDecisionAdapter`. `FlideDecisionAdapter` entfällt (Config-only). | ✅ |
| `runtime/resilience/` | `DecisionTriageService`, `DecisionLoopDetector`; Engine-Core-Wiring. `DecisionPreflightChecker` entfernt. | ✅ |
| `runtime/session/core/` | `Admission`/`SessionService.setDecisionModel(...)`; Routing in `Engine.runPrompt` (D7). | ✅ |
| `runtime/permission/engine/` | `PermissionEngine.isHighRisk(...)` + `decide()`-Eskalation. | ✅ |
| `runtime/context/` | `ContextBuilder.isAnswerable(...)` + `prepare()`-System-Block. | ✅ |
| `workflow-runner/` | `classify`-Node nutzt denselben HTTP-Transport; Mapping bleibt getrennt. | ✅ |
| `runtime/QocEngine` | Wiring des optionalen Ports + Wrapping der Resilience-Komponenten + `classifyPromptRelationDetailed`. | ✅ |
| Tests | `DecisionConsumerTest`, `TypeSafeDecisionAdapterTest`, `SystemOneHttpClientTest`, `EventCodecTest`, `QocEngineTest`; Bake-off (E1), Replay (E2). | ✅ Unit-Tests; ❌ E1/E2 |

## 7. Konfiguration

Der Code folgt der bestehenden Konvention `engine.*` (nicht `qoc.*`). Die ursprünglichen Plan-Namen sind der Vollständigkeit halber zugeordnet:

| Plan (ursprünglich) | Implementiert (`@ConfigProperty`) | Status |
|---|---|---|
| `qoc.decision.enabled` | `engine.decision.enabled` | genutzt |
| `qoc.decision.baseUrl` | `engine.decision.base-url` | genutzt |
| `qoc.decision.model` | `engine.decision.model` | genutzt |
| `qoc.decision.timeout` | `engine.decision.timeout-seconds` | genutzt |
| — | `engine.decision.api-key` | genutzt (Fallback `TYPESAFE_API_KEY`) |
| — | `engine.decision.max-retries` | genutzt |
| `qoc.decision.promptRelation.enabled` | `engine.decision.prompt-relation.enabled` | genutzt (D7) |
| `qoc.decision.promptRelation.threshold` | `engine.decision.prompt-relation.threshold` | genutzt (D7) |

**Nicht mehr vorhanden:** `engine.decision.backend` (Flide ist derselbe Adapter mit anderer `base-url`) und `engine.decision.fail-closed` (der Adapter ist bewusst immer fail-open).

## 8. Flide als Backend (L-01)

- Flide serviert `/v1/systemone` → gleicher Client, andere `baseUrl` + `model`.
- Kein neuer Code; nur Konfiguration + Overlay `deploy/docker-dev/docker-compose.flide.yml` (Profil `flide`, `FLIDE_IMAGE`).
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

## Status der Implementierung (verifiziert 2026-11-22)

**Vollständig implementiert und im Verhaltenspfad wirksam:**
- **D1** – Quarkus-Config (`engine.decision.*`) + Wiring: `Engine.java` baut `TypeSafeDecisionAdapter` (oder `NoOpDecisionModel`) und übergibt ihn via `ExtensionPorts` an `QocEngine.create`.
- **D2** – Gemeinsamer Transport: `runtime/provider/client/SystemOneHttpClient` kapselt URL, Auth, Custom-Header, Timeout, Retry und 4xx-Verhalten. `TypeSafeDecisionAdapter` und der Workflow-Runner-`TypeSafeDecisionClient` nutzen ihn; das jeweilige Request/Response-Mapping bleibt bewusst getrennt (Engine: `DecisionRequest`/`DecisionResult`; Workflow-Runner: reichhaltige `classify`-Config + normalized map).
- **D4** – `DecisionTriageService`: konsultiert `DecisionModel` (`taskType=triage`), fällt bei Fehler/Unavailable auf Delegate (`LlmTriageService`/`NoOpTriageService`) zurück.
- **D5** – `DecisionLoopDetector`: Heuristik zuerst; bei Trigger bestätigt/vetot das Modell (`taskType=loop_check`); Fehler → Heuristik.
- **D7** – Prompt-Relation-Router im Admission-Pfad (`Engine.runPrompt`): bei `engine.decision.prompt-relation.enabled=true` und aktiver Session klassifiziert `QocEngine.classifyPromptRelationDetailed(...)`; `confidence >= threshold` → `SUPPLEMENT`/`CORRECTION`/`INDEPENDENT` = `QUEUE` (STEER ist vorbereitet, aber der synchrone `runPrompt`-Pfad konsumiert die Inbox nicht), `CANCEL` = `QocEngine.interrupt(...)`, `STATUS` = letzte Assistant-Antwort ohne neuen Turn; `UNKNOWN`/unter Schwellwert = bisheriges Verhalten. Event `SessionEvent.PromptRelationClassified` wird geschrieben.
- **D8** – `PermissionEngine.decide(...)`: bei `Ask` und `isHighRisk(...)` → `Denied("high risk (decision model)")`; `Allowed`/`Denied` bleiben unverändert.
- **D9** – `ContextBuilder.prepare(...)`: bei `step == 0`, vorhandener Query und `isAnswerable(...) == false` wird ein System-Block (`ANSWERABILITY_PROMPT`) ergänzt; kein Block bei fehlender Query.
- **Engine-Core-Wiring** – `QocEngine.create` wrappt `LoopDetector`/`TriageService` mit den Decision-Wrappern und setzt `ContextBuilder.setDecisionModel(...)`.

**Erledigt / entfernt:**
- **D6** – `DecisionPreflightChecker` wurde entfernt. Preflight prüft Provider/DB/Workspace; Answerability gehört zu D9/`ContextBuilder`.
- **D3** – Flide ist config-only; Overlay `deploy/docker-dev/docker-compose.flide.yml` mit Profil `flide` und `FLIDE_IMAGE`.

**Offen:**
- **F9** – Bake-off E1/E2 (echte Backends + goldene Labels; nicht Teil dieses Arbeitspakets).
- **`Layering.md` (A10)** – noch nicht um die Decision-Layer-Pakete ergänzt (ArchUnit-Regel ist vorhanden).

**Konfiguration (wirksam):**
- `engine.decision.enabled`, `engine.decision.base-url`, `engine.decision.model`, `engine.decision.api-key`, `engine.decision.timeout-seconds`, `engine.decision.max-retries`, `engine.decision.prompt-relation.enabled`, `engine.decision.prompt-relation.threshold`.
- Entfernt: `engine.decision.backend` (Flide = gleicher Adapter + `base-url`) und `engine.decision.fail-closed` (nicht implementiert, immer fail-open).

**Tests/Abdeckung:**
- `DecisionConsumerTest` (Triage/Loop/Permission/Context/Admission/SessionService)
- `TypeSafeDecisionAdapterTest`, `SystemOneHttpClientTest` (HTTP-Success, 4xx/5xx, Retry, Custom-Header, URL-Normalisierung, Parse-Fehler, Truncation)
- `EventCodecTest` (`PromptRelationClassified` Round-trip), `QocEngineTest` (`decisionModel()`, `classifyPromptRelation(...)`, `classifyPromptRelationDetailed(...)`)
- Engine: 1256 Tests, 0 Failures, 4 skipped; JaCoCo 80,00 %.

## Todos mit Status

### Todo D1 – Quarkus-Config + Wiring — **ERLEDIGT**
- **Properties:** `engine.decision.enabled`, `engine.decision.base-url`, `engine.decision.model`, `engine.decision.api-key`, `engine.decision.timeout-seconds`, `engine.decision.max-retries`.
- **Wo:** `quarkus/.../Engine.java` Startup.
- **Wie:** `DecisionModel dm = enabled ? new TypeSafeDecisionAdapter(baseUrl, model, apiKey, timeout, maxRetries) : new NoOpDecisionModel();` → `ExtensionPorts(..., dm, ...)`.

### Todo D2 – TypeSafe-Client generalisieren — **ERLEDIGT**
- `runtime/provider/client/SystemOneHttpClient` ist der gemeinsame HTTP-Transport.
- `TypeSafeDecisionAdapter` (Engine) und `TypeSafeDecisionClient` (Workflow-Runner) nutzen ihn.
- Das Mapping bleibt getrennt: der Workflow-Runner braucht die reichhaltige `classify`-Config (mehrere Fragen, Choice/Scale, Output-Mapping); der Engine-Port bewusst schlank.

### Todo D3 – Flide-Backend (Config-only) — **TEILWEISE (Bake-off offen)**
- Flide funktioniert über `engine.decision.base-url` + `engine.decision.model` mit demselben Adapter.
- Overlay `deploy/docker-dev/docker-compose.flide.yml` (Profil `flide`, `FLIDE_IMAGE`).
- **Offen:** Bake-off E1.
- **Entscheidungsregel E1:** Flide optional, wenn Accuracy ≤ 2 Prozentpunkte schlechter und p95 nicht schlechter als TypeSafe.

### Todo D4 – TriageService nutzt DecisionModel — **ERLEDIGT**
- `DecisionTriageService` implementiert `TriageService`; konsultiert `DecisionModel` (`taskType=triage`), Fallback auf Delegate bei Fehler/Unavailable.
- Engine-Core-Wiring in `QocEngine.create`.

### Todo D5 – LoopDetector nutzt DecisionModel — **ERLEDIGT**
- `DecisionLoopDetector`: Heuristik zuerst; bei Trigger `DecisionModel.decide("loop_check", ...)`.
- Semantik: Modell `NO`/`FALSE` → veto (kein Loop); sonst Bestätigung. Fehler → Heuristik (fail-open).

### Todo D6 – PreflightChecker nutzt DecisionModel — **ENTFERNT**
- `DecisionPreflightChecker` wurde entfernt. Preflight prüft Provider/DB/Workspace, nicht Answerability.
- Answerability ist jetzt in D9/`ContextBuilder.prepare` umgesetzt.

### Todo D7 – Prompt-Relation-Router in Admission — **ERLEDIGT (ohne STEER)**
- **Wo:** `Engine.runPrompt` (Admission-/Delivery-Pfad), `QocEngine.classifyPromptRelationDetailed(...)`, `QocEngine.interrupt(...)`.
- **Mapping:** `SUPPLEMENT`/`CORRECTION`/`INDEPENDENT` → `QUEUE`; `CANCEL` → `interrupt`; `STATUS` → letzte Assistant-Antwort; `UNKNOWN`/unter Schwellwert → bisheriges Verhalten.
- **Einschränkung:** Echtes `STEER` setzt voraus, dass der laufende Turn gelieferte Inbox-Items zwischen Steps konsumiert. Der synchrone `runPrompt`-Pfad tut das nicht; ein STEER-Item würde geliefert und entfernt, ohne verarbeitet zu werden. Deshalb bleibt `SUPPLEMENT`/`CORRECTION` vorerst `QUEUE`.
- **Event:** `SessionEvent.PromptRelationClassified` (registriert in `EventCodec`, `NativeReflectionConfig`).
- **Hinweis:** `Admission`/`SessionService.setDecisionModel(...)` bleibt als untergeordneter Hook bestehen; die Routing-Entscheidung liegt in `runPrompt`, weil dort die `Delivery` gesetzt wird.
- **Replay-Test (E2):** 100–200 Mehrfachanfragen, Labels, False-STEER < 2 % (offen, F9).

### Todo D8 – PermissionEngine Risk-Scoring — **ERLEDIGT**
- `PermissionEngine.isHighRisk(...)` (fail-open, `false` bei Fehler/Unavailable).
- `PermissionEngine.decide(...)` eskaliert `Ask` bei hohem Risk zu `Denied`; `Allowed`/`Denied` bleiben unverändert.

### Todo D9 – ContextBuilder Answerability — **ERLEDIGT**
- `ContextBuilder.isAnswerable(...)` (fail-open, `true` bei Fehler/Unavailable).
- `ContextBuilder.prepare(...)` ergänzt bei `step == 0`, vorhandener Query und `false` einen System-Block (`ANSWERABILITY_PROMPT`).
- **Achtung:** Zusätzlicher Decision-Call pro Turn; Latenz-/Kostenbudget beachten.

## Offene Follow-ups (priorisiert)

### F1 – D7 im Admission-Pfad aktivieren (Prompt-Relation-Router) — **ERLEDIGT (ohne STEER)**
- Umgesetzt in `Engine.runPrompt` (Admission-/Delivery-Pfad), inkl. `PromptRelationClassified`-Event.
- Unter Schwellwert bleibt das bisherige Verhalten erhalten.
- Echtes `STEER` bleibt offen, bis der Turn-Loop die Inbox zwischen Steps konsumiert; bis dahin `QUEUE`.

### F2 – D8 in `PermissionEngine.decide()` integrieren — **ERLEDIGT**
- `Ask` + hohes Risk → `Denied`; keine Änderung an `Allowed`/`Denied`.
- Fail-open bei Modellfehler.

### F3 – D9 in `ContextBuilder.prepare()` integrieren — **ERLEDIGT**
- Answerability-Prüfung nur bei `step == 0` und vorhandener Query; Ergebnis ist ein zusätzlicher System-Block.

### F4 – D6 semantisch korrigieren oder entfernen — **ERLEDIGT**
- `DecisionPreflightChecker` entfernt; Preflight bleibt `DefaultPreflightChecker`.

### F5 – D2 Workflow-Runner delegieren — **ERLEDIGT (Transport)**
- `SystemOneHttpClient` ist die gemeinsame HTTP-Schicht.
- Das Mapping bleibt getrennt, weil der Workflow-Runner mehr Question-Typen und Output-Mapping unterstützt.

### F6 – Ungenutzte Config bereinigen — **ERLEDIGT**
- `engine.decision.backend` und `engine.decision.fail-closed` entfernt.
- `engine.decision.prompt-relation.enabled`/`threshold` werden jetzt gelesen.

### F7 – Flide Docker-Compose-Profil — **ERLEDIGT**
- `deploy/docker-dev/docker-compose.flide.yml` (Profil `flide`, `FLIDE_IMAGE`).

### F8 – ArchUnit/Layering — **ERLEDIGT**
- `providerClientsAreLeafAdapters` in `ArchitectureTest`.
- `Layering.md` (A10) bleibt als Dokumentations-Follow-up offen.

### F9 – Bake-off E1/E2 — **OFFEN (Infrastruktur)**
- **Was:** E1 (200–500 Entscheidungen, drei Backends, Accuracy/p50/p95/Durchsatz/Kosten) und E2 (100–200 Mehrfachanfragen, False-STEER < 2 %).
- **Voraussetzung:** echte Backends + goldene Labels.

## Erkenntnisse für unabhängige Implementierung

- **`/v1/systemone` ist die gemeinsame API.** TypeSafe und Flide servieren dasselbe Format. Ein Adapter reicht; Backend-Wechsel ist Config-only. Bake-off (E1) entscheidet über Default.
- **Fail-open by default.** Decision-Modell-Ausfall darf keinen Turn blockieren. `NoOpDecisionModel` gibt `noul=true` + leere Antworten; Consumer prüfen `isAvailable()` oder Confidence. `failClosed` ist bewusst nicht implementiert.
- **Prompt-Relation muss Confidence-Schwellwert haben.** Unter Schwellwert Fallback auf heutiges Verhalten (kein stilles Uminterpretieren). False-STEER ist teuer (stört laufende Turns).
- **`PromptRelationClassified` als `SessionEvent`-Variante.** `Payload` ist sealed auf `SessionEvent`; ein separates Event würde das Modell öffnen. Es gibt keine exhaustiven `SessionEvent`-Switches, daher ist die neue Variante unkritisch. Sie ist in `EventCodec` und `NativeReflectionConfig` registriert.
- **Gemeinsamer HTTP-Transport statt gemeinsamem Mapping.** `SystemOneHttpClient` teilt URL/Auth/Retry/4xx; das Mapping bleibt getrennt, weil der Workflow-Runner reichhaltigere Question-Typen und Output-Mapping braucht.
- **Kosten/Latenz-Budget** muss vor Rollout gemessen werden (E1, E2). Decision-Calls bei jedem Turn summieren sich; D9 prüft nur bei `step == 0`.
- **Tests:** E1 (Backend-Bake-off), E2 (Prompt-Relation-Replay). Mindestens False-STEER < 2 % und p95 ≤ TypeSafe.
- **Quarkus-Wrapper allein reicht nicht.** `QocEngine.create` baut die Resilience-Komponenten intern aus `EngineConfig`. Die Decision-Wrapper müssen dort (Engine-Core) sitzen; die Quarkus-seitigen Instanzen sind separate Objekte für die HTTP-Schicht.
- **Hooks sind in den Hauptpfaden aktiv.** D7 in `Engine.runPrompt`, D8 in `PermissionEngine.decide`, D9 in `ContextBuilder.prepare`. D6 wurde entfernt, weil Preflight semantisch nicht zu Answerability passt.
- **Config-Naming driftet.** Der Plan verwendet `qoc.decision.*`, der Code `engine.decision.*`. Die Tabelle in §7 ist die verbindliche Zuordnung.
