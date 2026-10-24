# QOC – Architektur- und Laufzeitdiagramme

Stand: 2026-10-24  
Basis: aktueller Code in `qoc/engine` und `qoc/quarkus`, dazu die Spec unter
`qoc/docs/spec/`.

Diese Sammlung beantwortet die Frage: **Wie ist QOC aufgebaut, wie fließt ein
Prompt durch das System, und wo ist das Verhalten deterministisch – und wo
entscheidet ein LLM?**

## Legende

| Kennzeichnung | Bedeutung |
|---|---|
| **DETERMINISTIC** (grün) | Gleicher Zustand + gleiche Eingabe + gleiche Regeln ergeben denselben nächsten Zustand. Kein LLM, keine freie Interpretation. |
| **LLM** (orange) | Ein Sprachmodell erzeugt Text, Tool-Calls oder eine Zusammenfassung. Das Ergebnis ist nicht deterministisch. |
| **MIXED** (gelb) | Deterministischer Rahmen, in dem ein LLM als Baustein aufgerufen wird. Der Rahmen validiert und entscheidet über die Ausführung. |
| **EXTERNAL / ADAPTER** (grau) | Technischer Adapter oder externer Dienst: HTTP, MCP, Firecrawl, Dateisystem, Git, Datenbank. |
| **STORE** (blau) | Persistenz: Event-Log, Projektionen, Snapshots. |

Wichtig für die Einordnung:

- QOC ist **kein vollständig deterministischer Orchestrator**. Der
  Session-Rahmen ist deterministisch, aber die Auswahl der Tool-Calls und der
  Text kommen vom LLM.
- Das LLM ist **nicht** die Instanz, die Zustandsübergänge schreibt. Es liefert
  Vorschläge; die Engine validiert, autorisiert und führt aus.
- Die Diagramme unterscheiden, wo nötig, zwischen **Ist-Stand** und
  **Zielbild**. Ein Zielbild ist ausdrücklich als solches markiert.

## Diagramme

| Datei | Frage |
|---|---|
| [`01-system-context.md`](./01-system-context.md) | Welche Akteure, Dienste und Speicher bilden das Gesamtsystem? |
| [`02-architecture.md`](./02-architecture.md) | Wie sind Maven-Module, Packages und Schichten aufgebaut? |
| [`03-runtime-dataflow.md`](./03-runtime-dataflow.md) | Was passiert bei einem Prompt von HTTP bis SSE und DB? |
| [`04-session-lifecycle.md`](./04-session-lifecycle.md) | Welche Zustände durchläuft eine Session, und wie werden Läufe koordiniert? |
| [`05-class-model.md`](./05-class-model.md) | Welche zentralen Klassen und Ports tragen das System? |
| [`06-dependencies.md`](./06-dependencies.md) | Welche Abhängigkeiten sind erlaubt, welche verboten? |
| [`07-tool-permission-chain.md`](./07-tool-permission-chain.md) | Wie wird ein Tool-Call autorisiert und ausgeführt? |
| [`08-agent-orchestrator.md`](./08-agent-orchestrator.md) | Wie funktionieren Agenten/Subagents heute – und wie sähe ein deterministischer Orchestrator mit LLM-Fallback aus? |
| [`09-persistence.md`](./09-persistence.md) | Welche Tabellen, Events und Transaktionsgrenzen gibt es? |
| [`10-deployment.md`](./10-deployment.md) | JVM, Native, Docker und GCP-Betrieb ohne Git. |

## Deterministische Teile auf einen Blick

| Bereich | Status | Begründung |
|---|---|---|
| HTTP-Validierung, ID-Parsing, Fehlercodes | deterministisch | reine Übersetzung und Validierung |
| Session Admission / Inbox / Delivery | deterministisch | feste Reihenfolge, Idempotenz, Regeln |
| Event-Log | deterministisch | append-only, lückenlose Sequenz, Transaktion |
| Projektionen | deterministisch | eigene Transaktionen; einzelne Cross-Store-Schritte sind noch nicht atomar |
| Permission Engine | deterministisch | Regeln, Wildcards, deny/ask/allow |
| Tool-Snapshot und Tool-Ausführung | deterministisch | Registry, Schema-Decode, Authorizer, Bounding |
| Context Builder | deterministisch | feste Reihenfolge, History-Folding, Budget |
| Step-Rahmen | gemischt | LLM liefert Events/Tool-Calls, Engine führt aus |
| Provider-Aufruf | LLM/extern | Inhalt und Tool-Auswahl kommen vom Modell |
| Compaction-Trigger | deterministisch | Overflow-Erkennung und genau eine Compaction |
| Compaction-Inhalt | LLM | Zusammenfassung wird generiert |
| Subagent-Aufruf | gemischt | Rahmen/Depth/Semaphore deterministisch, Inhalt LLM |
| Retention | deterministisch | Zeitstempel, Konfiguration, Löschregeln |

## Rendern

Die Diagramme sind Mermaid-Code in Markdown. Sie rendern in GitHub, in der
VS-Code-Markdown-Vorschau (Mermaid-Erweiterung) und in IntelliJ mit
Mermaid-Plugin. Für lokale Checks kann `@mermaid-js/mermaid-cli` verwendet
werden.
