# Isomorphes Java-Workflow- und LLM-System – Zusammenfassung

> **Quelle:** `docs/next/ideen.txt` (lange Architektur-Konversation).  
> **Status:** Konzept / Referenz (2026-11-18).  
> **Ziel:** Kompakte Extraktion der architektonischen Ideen für QOC.

---

## 1. Kernidee: Isomorphes Datenmodell

- Workflow-Struktur und LLM-Kontext haben **exakt dieselbe Struktur** (AST/JSON-Baum).
- „Der Zustand ist der Prompt ist der Ablaufplan“ – keine Mapping-Hölle.
- Persistenz als einheitlicher Baum (JSON), versionierbar, event-log-fähig.
- Java: `sealed interface Node` + Records + Pattern Matching / Visitor.

## 2. Dynamische Planung: OODA-Loop pro Schritt

Nach jedem Node:
1. Zustandsaktualisierung (Ergebnis in Baum schreiben).
2. LLM bekommt den gesamten Baum als Kontext.
3. Strukturierte Entscheidung (JSON Mode / Function Calling): `CONTINUE`, `GO_BACK`, `REPLAN`, `FINISH`.

## 3. Selbstheilungs-Loop (Self-Correction)

- Native JSON-Schema-Enforcement (Constrained Decoding) für syntaktische Validität.
- Semantische Validierung (Guardrails) für fachliche Korrektheit.
- Bei Fehler: Exception abfangen, Prompt mit Fehlerdetails anreichern, zurück an LLM (max. N Retries).
- In der Praxis korrigieren State-of-the-Art-Modelle im 2. Versuch in >95 % der Fälle.

## 4. Ziel-Definition pro Knoten (`NodeGoal`)

```java
public record NodeGoal(String description, List<ValidationRule> rules, EvaluationStrategy strategy) {}
```

- **Deterministisch (Code):** Schema, Datentypen, Formate, exakte Matches.
- **LLM-as-a-Judge:** Semantische Vollständigkeit, Tone/Compliance, logische Konsistenz.
- **Hybrid (empfohlen):** Code-Check zuerst (schnell, kostenlos), dann LLM-Judge (nur wenn nötig).
- Funnel-Prinzip: Schema → Programmatische Regeln → LLM-Judge (günstig → teuer).

## 5. Persistente Chat-Session pro Agent/Subagent

- Jeder Knoten behält seine eigene Chat-Historie (DB-persistent).
- **Gezielte Nachfragen** ohne kompletten Re-Run (`node.chatSession().sendMessage(...)`).
- **Inspect & Debug:** Entwickler können in die Chat-Session eines Knotens einsteigen.
- **Agent-to-Agent-Kommunikation:** Peer-Review (Generator & Kritiker), Experten-Konsultation (On-Demand Sparring) via Cross-Session-Calls.
- **Subagent-Purging:** Subagents machen intern viele Tool-Calls, aber nur das kompakte Ergebnis fließt in den übergeordneten Workflow-Kontext (Token-Bloat-Prävention).

## 6. Upstream-Korrektur & Lineage

- **Root-Cause-Discovery:** Agent in Schritt N merkt fehlendes Datum → Cross-Session-Call an den Producer-Schritt.
- **Memory-Patch:** Producer korrigiert Output und speichert Erkenntnis in seiner Chat-Historie.
- **Selektives Rollback/Replay:** Nur konsumierende Nachfolge-Knoten werden invalidiert (`DIRTY`/`PENDING_REPLAY`) und neu ausgeführt; nicht betroffene Schritte werden übersprungen (Bypass).
- **Data-Scope-Tracking:** Jeder Knoten weiß, welche JSON-Pfade er konsumiert hat → Impact-Scope-Berechnung.
- **Execution Modes:** `NORMAL`, `DRY_RUN`, `IMPACT_ANALYSIS`.

## 7. Hybride Knoten: Agent vs. Service

```java
public sealed interface WorkflowNode permits AgentNode, SubAgentNode, ServiceNode {}
```

- **AgentNode/SubAgentNode:** Chat-Session, Self-Healing-Loop, LLM-Judge.
- **ServiceNode:** Deterministisch (REST, DB, Code). Keine Chat-Session.
- **Input-Hashing:** Bei Upstream-Änderung Hash der neuen Eingaben vergleichen → Rerun oder Bypass.
- **Saga-Pattern:** Für Services mit Seiteneffekten (Kompensierende Transaktionen, Rollback).

## 8. DB als Single Source of Truth

- React Flow = reine Projektions-Schicht (UI View) auf die DB.
- Jeder Schritt mit seinem exakten Input persistiert → einzeln reproduzierbar.
- Versionierung und Event-Log für Audit-Trail und Replay.

## 9. Kritische Risiken & Gegenmaßnahmen

| Risiko | Gegenmaßnahme |
|---|---|
| Token-Bloat / Attention Drift | Subagent-Purging, selektives Data-Scoping |
| State-Chaos / Race Conditions | DB als SSOT, React Flow nur View |
| Reproduzierbarkeits-Desaster | Step-Input-Persistenz, Event Sourcing |
| Prompt Injection im Baum | Wenig Außenangriff (wenig Externzugriff), Daten-/Instruktions-Trennung |
| Kosten/Latenz-Killer | Pragmatische Validierungsmatrix (Funnel), Selbstheilungs-Loop mit max. Retries |

## 10. Implementierungsplan (aus ideen.txt)

1. **Isomorphes Datenmodell** in Java (Sealed Interface + Records) und React Flow (node.data spiegelt Java-Struktur).
2. **Trennung Chat-Session (Isolation) vs. Workflow-State (Commit)** – Sandbox vs. Commit.
3. **Hybride Knotentypen** (Agent vs. Service) in der Engine.
4. **Upstream-Korrektur- & Invalidation-Flow** (Epizentrum → Lineage-Check → Rerun/Dry-Run).
5. **UI-Synchronisation** in React Flow (Farbkodierung, Chat-Drawer pro Knoten).

## 11. Relevanz für QOC

- **Bestehende Architektur:** QOC hat bereits Workflow-Engine, Agent-Catalogue, Child-Sessions, Journal, Resilience. Die isomorphe Idee (Workflow = Kontext) ist ein möglicher nächster Schritt für die Agenten-Integration.
- **Runtime-Plan-Adaptation:** Der Doc (`runtime-plan-adaptation.md`) deckt Runtime-Reparatur ab; die isomorphe Idee ergänzt dies um dynamische Planung (OODA-Loop) und Chat-Session-Integration.
- **Empfehlung:** Phase 0 (Goal→Plan) könnte als OODA-Loop auf Basis des isomorphen Modells implementiert werden, wobei der Supervisor (Doc) die Entscheidungslogik übernimmt.