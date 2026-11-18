# Implementierungsplan: LangChain4j TypeSafe Decision Model Integration

Status: Plan (2026-11-18)
Bezug: `workflow-runner/.../runner/DefaultNodeDispatcher.java`, `workflow-runner/.../validation/GraphCompiler.java`,
`workflow-runner/.../model/WorkflowDefinition.java`, `docs/plans/verify-on-stop-guards.md` (low_margin-Mechanismus)

## 1. Problem

Workflows können heute Text generieren (`agent`, `llm_eval`, `llm_summarize`) und
deterministisch branchen (`decision`, `guard`), aber es gibt **keinen Node-Typ für
typisierte Entscheidungen** (Klassifikation/Routing/Gating/Grading mit
Wahrscheinlichkeiten). Routing-Entscheidungen basieren auf String-Templates oder
LLM-Text, nicht auf kalibrierten Wahrscheinlichkeiten. Der bestehende `guard`-Node
hat bereits einen `low_margin`-Pfad (Correction-Rounds-Limit), der auf
Decision-Model-Margins zugeschnitten ist, aber kein Producer liefert diese Werte.

LangChain4j bietet seit 1.21 das `@Experimental`-`DecisionModel`-API und die
`TypeSafeDecisionModel`-Implementierung (TypeSafe System One, `POST /v1/systemone`),
die genau diese typisierten Antworten (YesNo/Choice/Scale mit Probabilities) liefert.

## 2. Zielbild

Ein neuer Workflow-Node-Typ **`classify`**, der:

1. Einen Input (Template-resolved) gegen ein Decision Model evaluiert.
2. Typisierte Fragen (yesno/choice/scale) in **einem** Call beantwortet.
3. Ergebnisse (gewählte Werte, Probabilities, Margins) in den Workflow-State
   schreibt (`ctx.setState`, `ctx.setNodeOutput`).
4. Bei niedriger Margin (`margin < threshold`) `low_margin=true` setzt, damit der
   bestehende `guard`-Mechanismus (Correction-Rounds) greift.
5. Fail-Open oder Fail-Closed konfigurierbar ist.

Die Engine (`engine.core`) bleibt unberührt (Pure-Java-Grenze, kein Use-Case).

## 3. Abhängigkeiten / Versionen

- `workflow-runner` nutzt **keine** Quarkus-BOM → freie Versionwahl möglich.
- **Keine neue Maven-Dependency:** Implementierung nutzt `java.net.http.HttpClient` (JDK 21)
  und den vorhandenen Jackson-`ObjectMapper` (`WorkflowJson`). Damit bleibt `workflow-runner`
  frei von LangChain4j und dessen Versionsdrift. Die ursprünglich geplante
  `langchain4j-typesafe`-Dependency wurde **nicht** aufgenommen.
- Die Quarkus-App (`quarkus/`) pinnt `langchain4j-core 1.19.3` via BOM → **nicht** anfassen;
  der DecisionModel-Client lebt ausschließlich im Workflow-Runner.
- API-Key über Env `TYPESAFE_API_KEY` (Fallback: Node-Config `apiKey`).
- Modellname default `jev-latest` nur für Dev; Produktion pinnt Version
  (z. B. `jev-1.13.0`) via Node-Config oder Env `TYPESAFE_MODEL_NAME`.
- Protokoll: `POST <baseUrl>/v1/systemone` (Default `https://api.typesafe.ai`),
  Header `Authorization: Bearer <apiKey>`, Body `{model, state, questions}` →
  `{model, answers, usage}`. Entspricht 1:1 dem, was LangChain4j's `TypeSafeMapper`
  serialisiert.

## 4. Datenmodell

### 4.1 Node-Typ

Neuer Eintrag in `GraphCompiler.VALID_NODE_TYPES`: `"classify"`.

Kein neues Feld in `NodeData` nötig: Konfiguration läuft über `mcpArgs`
(options bag), analog `guard`/`compact`/`rest_call`. Damit bleiben Schema und
UI-Serialisierung kompatibel.

### 4.2 Node-Config (mcpArgs)

```json
{
  "type": "classify",
  "data": {
    "title": "Route ticket",
    "mcpArgs": {
      "modelName": "jev-1.13.0",
      "input": "${input}",
      "questions": [
        { "name": "team", "type": "choice", "text": "Which team?",
          "options": { "billing": "Payments, payouts", "support": "Product usage" } },
        { "name": "urgent", "type": "yesno", "text": "Needs attention today?" }
      ],
      "thresholds": { "urgent": 0.8, "margin": 0.2 },
      "outputMapping": { "team": "ticketTeam", "urgent": "urgent" },
      "failMode": "open"
    }
  }
}
```

Felder:
- `modelName` (String, optional): Decision-Model-Name. Fallback Env/`jev-latest`.
- `baseUrl` (String, optional): für OpenRouter/self-hosted. Default TypeSafe.
- `input` (String oder Map): Template-resolved. Wenn String → `DecisionRequest.input(String)`;
  wenn Map → `input(Map)`.
- `questions` (List<Map>): je `name`, `type` ∈ {yesno, choice, scale}, `text`,
  plus `options` (Map) für choice, `levels` (List<String>) für scale,
  `yesWhen`/`noWhen` (optional) für yesno.
- `thresholds` (Map): `margin` (double, default 0.2) für Choice, pro-Frage
  Schwellen (z. B. `urgent: 0.8` für YesNo).
- `outputMapping` (Map questionName → stateKey): schreibt gewählten Wert in State.
  Default: Frage-Name als State-Key.
- `failMode` ∈ {open, closed}: Verhalten bei Fehler/Refusal.
- `apiKey` (String, optional): Override für Env.

### 4.3 Ausgabe (NodeResult)

- `text`: kompakte Zusammenfassung, z. B. `classify: team=billing (0.91), urgent=yes (0.97)`.
- `structured`: Map mit pro Frage `{ value, probabilities, margin, probability }`.
- State: für jede Frage den gewählten Wert unter dem gemappten Key; plus
  `classifyResult` (vollständige Map) und `low_margin` (boolean).

## 5. Implementierung

### 5.1 Dependency

`workflow-runner/pom.xml`:
```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-typesafe</artifactId>
    <version>1.22.0-beta32</version>
</dependency>
```

### 5.2 GraphCompiler

`VALID_NODE_TYPES` um `"classify"` erweitern.

### 5.3 DefaultNodeDispatcher

Neuer Case `"classify"` in `dispatchImpl`, analog `llm_eval`/`rest_call`:

1. `mcpArgs` lesen (null-safe).
2. `input` Template resolven (`TemplateResolver.resolveStrict` bzw. `resolveToObject`).
3. `TypeSafeDecisionModel` bauen (apiKey aus Config/Env, modelName, baseUrl optional,
   timeout 60s, maxRetries 2).
4. `DecisionRequest` aus Config bauen.
5. `model.decide(request)` aufrufen (synchron).
6. Ergebnis mappen → `NodeResult` + State.
7. Margin-Check → `low_margin`.
8. Fehlerbehandlung je `failMode`.

### 5.4 Client-Klasse (testbar)

Neue Klasse `workflow-runner/.../runner/TypeSafeDecisionClient` (oder innerhalb
Dispatcher als statische Helper). Kapselt:
- `buildModel(config)` → `TypeSafeDecisionModel`
- `buildRequest(config, input)` → `DecisionRequest`
- `execute(...)` → `DecisionResponse`
- `mapResponse(...)` → Map

Damit lässt sich der Client in Tests mit einem Fake-Model (MockDecisionModel)
oder einem lokalen HTTP-Server testen. `TypeSafeDecisionModel` implementiert
`DecisionModel` → für Unit-Tests kann `dev.langchain4j.model.decision.MockDecisionModel`
genutzt werden (falls vorhanden) oder ein eigener Fake.

### 5.5 Margin-/Threshold-Logik

- Für jede `ChoiceQuestion`: wenn `answer.margin() < thresholds.margin` → `low_margin=true`.
- Für `YesNoQuestion`: wenn `probability` zwischen `(1-threshold)` und `threshold`
  (unsicher) → optional `low_margin=true` (konfigurierbar via `thresholds.uncertainBand`).
- State `low_margin` wird gesetzt, sodass bestehender `guard`-Node mit
  Condition `low_margin` reagiert.

### 5.6 Fail-Mode

- `open` (default): bei Fehler/Refusal → NodeResult mit Fehler-Text, Workflow
  läuft weiter (wie `rest_call` error).
- `closed`: bei Fehler/Refusal → `IllegalStateException` (bricht Workflow ab),
  analog Agent-Node.

## 6. Dateien

### Neu
- `workflow-runner/.../runner/TypeSafeDecisionClient.java`
- `workflow-runner/src/test/.../runner/ClassifyNodeTest.java`

### Geändert
- `workflow-runner/pom.xml` (Dependency)
- `workflow-runner/.../validation/GraphCompiler.java` (VALID_NODE_TYPES)
- `workflow-runner/.../runner/DefaultNodeDispatcher.java` (case "classify")

### Nicht geändert
- `engine/` (Pure-Java-Grenze)
- `quarkus/` (BOM/Versionen)
- `WorkflowDefinition.java` (mcpArgs reicht)

## 7. Reihenfolge der Umsetzung

1. **Tag 1:** Dependency + GraphCompiler-Eintrag + `TypeSafeDecisionClient`
   (Model-Build + Request-Build + Response-Mapping) + Unit-Tests mit Fake-Model.
2. **Tag 1-2:** Dispatcher-Case `"classify"` + State/Output-Mapping + Margin-Logik.
3. **Tag 2:** Fail-Mode + Template-Resolution + Tests (GraphCompiler, Dispatcher).
4. **Tag 2-3:** Doku (`docs/CONFIG.md` oder neues `docs/decision-models.md`),
   Beispiele in `docs/Agent-Def-Example.md`-Stil.
5. **Tag 3:** Manueller End-to-End-Test gegen TypeSafe (mit API-Key) + Tuning.

## 8. Risiken

- **Experimental API:** LangChain4j 1.22.0-beta32; API kann sich ändern.
  Mitigation: Client-Klasse kapselt alle LangChain4j-Typen; bei Update nur Client anpassen.
- **Versionsdrift:** workflow-runner nutzt core 1.22.0, quarkus nutzt 1.19.3.
  Kein gemeinsamer Classpath im Build (getrennte Module) → kein Konflikt.
  Bei späterer Quarkus-BOM-Aktualisierung Versionsangleich prüfen.
- **Netzwerk/Timeout:** TypeSafe-Aufrufe können 15s+60s+Retries dauern.
  Mitigation: konfigurierbarer Timeout, Fail-Mode, Workflow-Step-Timeout.
- **Kalibrierung:** Schwellen sind modell-/versionsabhängig. Dokumentieren, dass
  Thresholds pro Modell neu kalibriert werden müssen.
- **Fail-Closed bei Gating:** Missbrauchsfilter sollten `failMode=closed` nutzen
  (Doku-Hinweis).

## 9. Offene Fragen

1. Soll `classify` auch in der Quarkus-UI als Node-Typ sichtbar sein
   (ReactFlow node renderer), oder zunächst nur Backend?
2. Soll der Decision-Model-Client in Quarkus als CDI-Bean exponiert werden
   (für HTTP-Endpoint `/classify`), oder nur Workflow-intern?
3. Default-Modellversion pinnen (`jev-1.13.0`) oder `jev-latest` für Dev?
4. Skalierung: mehrere Fragen pro Call (empfohlen) vs. ein Call pro Frage
   (teurer) – Default: alle Fragen in einem Call.

## 10. Akzeptanzkriterien

- [ ] `mvn -q test` in `workflow-runner/` grün.
- [ ] `GraphCompiler` akzeptiert `classify`-Nodes, lehnt ungültige Config ab.
- [ ] Unit-Test mit Fake-`DecisionModel` prüft YesNo/Choice/Scale-Mapping,
      State-Writes, `low_margin`-Setzung, Fail-Open/Closed.
- [ ] Manueller Run gegen TypeSafe (mit API-Key) liefert typisierte Antworten.
- [ ] Keine Änderungen in `engine/` oder `quarkus/`.

## 11. Entscheidungen (2026-11-18)

### 11.1 Pure HTTP statt LangChain4j

Die Implementierung nutzt **pure HTTP** (`java.net.http.HttpClient`) statt
`dev.langchain4j.model.typesafe.TypeSafeDecisionModel`. Begründung:

- `workflow-runner` bleibt frei von LangChain4j und dessen Versionsdrift
  (Quarkus-BOM pinnt `langchain4j-core 1.19.3`, TypeSafe bräuchte ≥ 1.21).
- Keine `@Experimental`-API im Produktivpfad.
- Das System-One-Protokoll ist klein und stabil; das JSON-Format entspricht 1:1
  dem, was LangChain4j's `TypeSafeMapper` serialisiert (verifiziert via Bytecode).
- Tests nutzen einen lokalen `com.sun.net.httpserver.HttpServer` (kein Mocking).

LangChain4j kann später als alternativer Adapter hinter derselben
`TypeSafeDecisionClient`-Fassade nachgezogen werden, falls gewünscht.

### 11.2 Logik bleibt im Workflow-Runner (nicht in der Engine)

Die DecisionModel-Logik wird **nicht** in `engine.core` verschoben. Begründung:

1. **Use-Case:** `engine.core` ist die Session-Engine (Chat/Tool-Loop, Compaction,
   Revert). `DecisionModel` ist ein Workflow-Baustein (Klassifikation/Routing vor
   Agent-Ausführung). Engine hat keinen Consumer (Triage läuft über `ProviderAdapter`,
   Permission ist regelbasiert).
2. **Pure-Java-Grenze:** Ein Port ohne Consumer verletzt YAGNI und bläht `QocEngine` auf.
3. **Konsistenz:** `workflow-runner` nutzt die Engine als Library und hat bereits
   HTTP-Infrastruktur (`rest_call`, `java.net.http`).

**Falls später ein Engine-Consumer entsteht** (z. B. `/classify`-Endpoint in Quarkus,
Triage auf DecisionModel), ist das richtige Package:

- Interface: `engine.core.contract.decision.DecisionModel` (analog
  `core.contract.provider.ProviderAdapter`) **oder**
  `engine.core.port.world.DecisionModelClient` (wenn externer Service).
- Adapter (pure Java HTTP): `engine.adapter.decision.HttpDecisionModel`
  (bzw. `engine.runtime.provider.client`-Stil).
- Wiring in `QocEngine` erst, wenn ein Consumer existiert (Factory-Methode,
  nicht Fassaden-Feld).

Bis dahin bleibt der Client im `workflow-runner` und kann bei Bedarf nach
`engine.adapter.decision` verschoben werden, ohne die Node-Semantik zu ändern.

### 11.3 llm_eval/llm_summarize durch Agent-Files ersetzt

Die spezialisierten Node-Typen `llm_eval` und `llm_summarize` wurden entfernt.
Stattdessen existieren zwei wiederverwendbare Agent-Definitionen als Markdown-Files:

- `.opencode/agents/llm-eval.md` (System-Prompt: Eval/JSON)
- `.opencode/agents/llm-summarize.md` (System-Prompt: Summarize)

Diese werden über `AgentMarkdownLoader` (Quarkus) geladen und stehen als
Agent-Refs `llm-eval` / `llm-summarize` zur Verfügung. In Workflows nutzt man
stattdessen einen `agent`-Node mit `agentRef: "llm-eval"` bzw. `"llm-summarize"`.

Entfernt aus: `GraphCompiler.VALID_NODE_TYPES`, `DefaultNodeDispatcher` (Cases),
`WorkflowContractValidator`, UI (`nodeRegistry`, `workflowNodes`, `EditorPage`-Palette).
