# Goal-Driven AI Planning + Deterministische Workflow-Orchestrierung

> **Status:** Konzept / Vergleich (2026-11-18).  
> **Quellen:** User-Idee (Goal-Driven AI Planning) + `docs/plans/runtime-plan-adaptation.md`.  
> **Ziel:** Beide Ansätze zusammenführen und als einheitliche Roadmap beschreiben.

---

## Teil 1 – Aktuelle Idee: Goal-Driven AI Planning + deterministische Orchestrierung

### Grundprinzip (zwei Welten)

1. **KI-Ebene (Planung):** Die KI versteht den Ausgangszustand und das gewünschte Ziel, nutzt ihr Wissen über Drittsysteme und Tools und zerlegt den Weg in logische Zwischenschritte (Dekomposition).
2. **Deterministische Ebene (Ausführung):** Der entworfene Pfad wird in ein festes, reproduzierbares Format (Workflow, Code-Skript, State Machine) übersetzt, sodass er bei gleicher Ausgangslage immer exakt gleich und fehlerfrei ausgeführt wird – ohne dass die KI bei jedem Schritt neu raten muss.

### Architektur (vier Phasen)

1. **State & Goal Input:** Der Benutzer definiert Ist-Zustand (z. B. „Unstrukturierte PDF-Rechnung liegt im S3-Bucket“) und Ziel (z. B. „Daten in SAP verbucht, Kunde hat Bestätigungsmail, Vorgang im CRM als ‚erledigt‘“).
2. **Tool-Registry & Capability Mapping:** Die KI hat Zugriff auf eine Liste bekannter Drittsysteme und deren Schnittstellen (APIs, DBs, RPA-Tools). Sie weiß, welche Parameter ein Tool benötigt und was es zurückgibt.
3. **Pathfinding & Graph Generation (LLM/Agent):** Die KI plant die Sequenz. Moderne Frameworks nutzen strukturierte Ausgaben (JSON Mode / Pydantic), um einen gerichteten Graphen (DAG) oder eine strikte Schritt-für-Schritt-Abfolge zu erstellen.
4. **Deterministic Execution Engine:** Eine Workflow-Engine (keine KI mehr!) arbeitet diesen Plan Schritt für Schritt ab. Bei Fehlern greifen definierte Fehlerbehandlungsroutinen.

### Relevante Frameworks

**KI-Planung (Entwurf):**
- LangGraph (LangChain): zustandsbasierte, deterministische Graphen.
- CrewAI / AutoGen: spezialisierte Agenten im Team (Planer, API-Spezialist, Prüfer).
- Semantic Kernel (Microsoft): native Planners aus registrierten Funktionen (Plugins/APIs).

**Workflow-Orchestrierung (Determinierung):**
- Temporal.io / Camunda: Königsklasse für deterministische Workflows (Retries, Status-Tracking).
- n8n / Make: API-lastige Workflows; KI kann Workflows generieren/anpassen.
- Apache Airflow: datenlastige / ETL-ähnliche Pfade.

**Schnittstellen-Standardisierung:**
- OpenAPI / Swagger: KI liest Schnittstellenbeschreibungen und weiß, welche Endpunkte für welchen Teilschritt zu nutzen sind.

### Typischer Ablauf (Praxisbeispiel)

Anfrage: „Migriere Kundendaten aus Altsystem X in CRM Y und validiere die E-Mail-Adressen.“

1. KI-Planung erkennt:
   - Schritt 1: API-Abfrage an Altsystem X (Tool: `Legacy_API_Connector`).
   - Schritt 2: Datenbereinigung und Validierung (Tool: `Email_Validation_Service`).
   - Schritt 3: Datensatz-Erstellung in CRM Y (Tool: `CRM_REST_API`).
2. Ausgabe als deterministischer Code/Workflow: valides JSON oder Python-Skript (oder n8n-Workflow).
3. Ausführung: klassischer Skript-Runner/Engine führt den exakten Code aus – schnell, sicher, ohne unerwartete KI-Kreativität zur Laufzeit.

---

## Teil 2 – Vergleich mit `runtime-plan-adaptation.md`

### Übereinstimmungen

| Aspekt | Pasted Ideas | runtime-plan-adaptation.md |
|---|---|---|
| Trennung Planung vs. Ausführung | KI plant, Engine führt deterministisch aus | Supervisor plant Adaptation, Engine führt deterministisch aus |
| Strukturierte Pläne (DAG/Graph) | JSON/Pydantic → DAG/Schrittfolge | AdaptationPlan + GraphCompiler-Validierung |
| Tool-Registry / Capabilities | OpenAPI/Swagger für Schnittstellen | MCP-Tools, Agent-Refs, Output-Contracts |
| Validierung vor Ausführung | implizit (strukturierte Ausgabe) | explizit: GraphCompiler + PlanValidator |
| Resilienz/Retry | definierte Fehlerbehandlung | ErrorClassifier, RetryPolicy, TriageService, Circuit-Breaker |
| Journaling/Replay | nicht explizit | zentral: PlanVersionChanged, Adaptation*-Events, Resume |

### Unterschiede & Lücken

**Was die pasted Ideas haben, das der Doc (noch) nicht abdeckt:**
1. **Goal→Plan-Generierung (Phase 0):** Der Doc setzt voraus, dass ein Base-Plan existiert. Es fehlt ein Modul, das aus Ist-Zustand + Ziel einen initialen Workflow-DAG erzeugt (LLM mit strukturiertem Output, validiert durch GraphCompiler).
2. **Tool-Registry als OpenAPI/Swagger-Quelle:** Der Doc nutzt MCP-Tools/Agent-Refs, aber keine explizite Schnittstellen-Standardisierung (OpenAPI) für die KI-Planung.
3. **Framework-Integrationen:** LangGraph/CrewAI/Semantic Kernel und Temporal/Camunda/n8n werden in den pasted Ideas genannt; QOC hat eigene Engine + workflow-runner, keine externen Orchestratoren.
4. **State & Goal Input als explizite Phase:** Der Doc hat `inputSchema` und `initial`-State, aber keine dedizierte „Ziel-Definition“-Phase.

**Was der Doc hat, das die pasted Ideas nicht abdecken:**
1. **Runtime-Reparatur / Plan-Adaptation:** Base-Plan-Immutabilität, derived Plans mit Lineage, Rejoin-Anker.
2. **Requirement-Gap-Modell:** Explizite Input-Requirements, Gap-Reasons, Severity (ERROR/INFO).
3. **Blast-Radius & Divergenz-Handling:** Statische Abhängigkeitsanalyse, deterministische Reparatur-Modi (PATCH_ONLY, REPLAY_FROM, COMPENSATE_AND_REPLAY, ESCALATE).
4. **Session-Requery:** Wiederaufnahme persistierter Child-Sessions zur Nachfrage fehlender Felder.
5. **Idempotenz/Side-Effect-Gates:** Auto-Replay nur bei deklarierter Idempotenz/Kompensation (SAGA).
6. **Resume nach Crash:** Journal-basierte Wiederherstellung des zuletzt akzeptierten Plans.

---

## Teil 3 – Integrationsplan (Phasen)

| Phase | Inhalt | Quelle |
|---|---|---|
| **Phase 0 (neu)** | Goal→Plan-Generierung: LLM mit Tool-Registry (MCP/OpenAPI) erzeugt initialen DAG; Validierung durch GraphCompiler; Base-Plan-Version 1.0.0 | Pasted Ideas |
| **Phase A (Doc)** | RequirementChecker + Session-Requery + Reroute-Fallback | Doc |
| **Phase B (Doc)** | AdaptationPlan + RuntimePlan + PlanValidator + InsertNode/PatchNode; Journal-Versionierung; Resume | Doc |
| **Phase C (Doc)** | LlmSupervisor via TriageService; HITL; UI/API | Doc |
| **Phase D (Doc)** | Budgets, Metriken, Hardening | Doc |

**Kernentscheidung:** Der Supervisor (Doc) wird nicht nur für Reparatur genutzt, sondern auch für Phase 0 (Goal→Plan) als `HumanSupervisor`/`LlmSupervisor`-Variante, die einen initialen Adaptationsplan auf einem leeren Base-Plan erzeugt. Damit bleibt die Architektur einheitlich: alles läuft über `AdaptationPlan` + `GraphCompiler`.

### Nächste Schritte (Plan, keine Änderungen)

1. **Phase 0 spezifizieren:** Tool-Registry-Schema (MCP-Tools + OpenAPI) definieren; LLM-Prompt-Template für Goal→DAG; Output-Schema (JSON) validierbar durch GraphCompiler.
2. **Phase A implementieren:** RequirementChecker, Session-Requery, Reroute; Tests (Doc Testplan Punkt 1–3).
3. **UI/API:** `GET /run/{id}/plan`, Badge „Adapted (n)“ (Doc §10).
4. **Keine externen Orchestratoren (Temporal/Camunda):** QOC behält eigene Engine; n8n-Export optional als späteres Feature.

---

## Referenzen

- `docs/plans/runtime-plan-adaptation.md` (Original-Konzept)
- `docs/engineering/ReAct-Workflow.md`
- `docs/resilience.md`
- `docs/engineering/Task-Orchestration.md`
- `docs/engineering/SAGA.md`