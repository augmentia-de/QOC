# Learnings für QOC aus sieben Agenten-, Graph- und Inferenzprojekten

**Stand:** 22. November 2026  
**Gelesen:** `qoc/README.md`, `qoc/docs/Features.md`  
**Zusätzlich herangezogen:** `docs/meta/ROADMAP.md`, `docs/meta/STATUS.md`, `docs/meta/FUTURE-WORK.md`, `docs/spec/learnings.md`, `docs/plans/typesafe-decision-model-integration.md`, Ports und Contracts im Engine-Code  
**Projektbasis:** `REPOS.md` mit der Analyse der sieben Repositories  
**Analysierte Projekte:** git-ontology-agent, Flide, Omnigraph, kagent, SelfGraph, TrustGraph, OpenComputer

---

## 0. Executive Summary

QOC ist bereits deutlich weiter als die meisten Referenzen: reiner Java-Core, Event-Log als Wahrheit, SQLite/PostgreSQL, Claims, durables Session-Inbox-Modell mit `STEER`/`QUEUE`, Snapshots/Revert, Compaction, Subagents, MCP, A2A, Workflow-Runner mit 16 Node-Typen, Deny-by-default-Permissions und eine `classify`-Node auf Basis eines Decision Models.

Die sieben Projekte liefern deshalb **keine fehlende Grundfunktion**, sondern Referenzen für vier konkrete Lücken:

1. **Execution Isolation:** QOCs Tools laufen auf dem Host. Permissions sind keine Sandbox.
2. **Provenance und Data Governance:** QOC hat Event-Log und LLM-Trace, aber kein erstklassiges Modell dafür, welche Quellen, Kontextblöcke und Tool-Ergebnisse eine Antwort beeinflusst haben, und keine Datenfilterung unterhalb der Tool-Ebene.
3. **Semantisches Langzeitgedächtnis:** QOC hat Session-Historie, Instructions und Compaction, aber keinen sessionübergreifenden, versionierten Wissensgraphen.
4. **Decision Layer:** QOC nutzt Decision Models bisher nur im Workflow-Runner (`classify`). Für Routing, Relationsurteile, Risiko und Context-Sufficiency gibt es noch keinen allgemeinen, engine-weiten Entscheidungsweg.

### Top-5-Empfehlungen

| # | Empfehlung | Quelle | Warum zuerst? |
|---|---|---|---|
| 1 | **Flide als optionales Backend für die bestehende `classify`-Node** | Flide | QOC spricht bereits `/v1/systemone`; Flide ist ein möglicher Self-hosted-Drop-in. Sehr kleiner Aufwand, gut messbar. |
| 2 | **Provenance für Kontext und Antworten** | TrustGraph, git-ontology-agent | Macht QOC auditierbar und erklärbar, ohne den Event-Log zu ersetzen. |
| 3 | **Container-Sandbox hinter den bestehenden Ports** | OpenComputer | Schließt die größte Sicherheitslücke bei der Ausführung von Agenten-Code. |
| 4 | **Prompt-Relation-Router auf `STEER`/`QUEUE`** | git-ontology-agent | Nutzt die vorhandene Inbox und verbessert Mehrfachanfragen ohne neue Session-Semantik. |
| 5 | **Artifact-Store für große Tool-Outputs** | Omnigraph | QOC hat `ArtifactRef` und `tool_output`; Objektspeicher verhindert DB-Wachstum. |

### Was QOC bewusst nicht übernehmen sollte

- Kein Git-/Claim-Modell als primäre Wahrheit. Der Event-Log bleibt die Wahrheit.
- Keine Graph-DB, kein RDF/OWL/SHACL und keine Cedar-Policy als Core-Abhängigkeit.
- Keine Kubernetes-CRDs als primäres Konfigurationsmodell.
- Kein Cloudflare-Workers-/Slack-Stack im Core.
- Keine Python-/PyTorch-Inferenz im Java-Prozess. Flide wird per HTTP angebunden.
- Keine autonome Memory-Pipeline in dieser Ausbaustufe. Zuerst manuelle, kuratierte Memory und `RULES.md`, wie im bestehenden Learnings-Dokument festgehalten.

---

### Detaillierte Umsetzungspläne (vier konkrete Lücken)

Für jede der vier Lücken gibt es eine eigene Datei mit ausführlicher Beschreibung, wo in QOC was implementiert werden kann, sauberer Trennung und optionalem Charakter:

| Datei | Lücke | Kurzbeschreibung |
|---|---|---|
| `ext-01-execution-isolation.md` | Execution Isolation | Optionale per-Session-Container-Sandbox hinter bestehenden Ports (ProcessRunner/FileSystem/HttpTransport), Egress-Policy, Checkpoints. |
| `ext-02-provenance-governance.md` | Provenance & Data Governance | Optionales Provenance-Modell (IDs/Hashes, kein RDF) + Data-Policy-Hook (allow/filter/deny) für Tool-Ausgaben. |
| `ext-03-semantic-memory.md` | Semantisches Langzeitgedächtnis | Optionaler `MemoryPort` mit temporalen Fakten, BM25+Graph-Retrieval, `check_answerable`; manuell zuerst. |
| `ext-04-decision-layer.md` | Decision Layer | Optionaler `DecisionModel`-Port, engine-weite Consumer (Triage, Prompt-Relation, Risk, Answerability), Flide als Backend. |

Alle vier sind standardmäßig deaktiviert und lassen sich ohne Core-Änderungen ein- und ausschalten.

---

## 1. Bewertungsrahmen

### 1.1 QOC-Baseline

| Bereich | Heute vorhanden | Konsequenz für Erweiterungen |
|---|---|---|
| **Persistenz** | Event-Log als Wahrheit, Projektionen, Claims, SQLite/PostgreSQL, Flyway V1–V12, 22 Tabellen | Neuer Zustand muss Event oder Projektion sein, kein zweiter Wahrheitsbegriff. |
| **Sessions** | Durable Inbox mit `STEER`/`QUEUE`, Pause/Resume, Claims, Resume-Counter, Crash-Recovery, Fork/Tree, Snapshots, Revert | Relationsurteile können auf der Inbox aufsetzen; keine neue Session-Engine nötig. |
| **Tools** | Dateien, Shell inkl. PTY/Background/Timeout, Suche, Web, Skills, Todos, Fragen, Worktrees, MCP, A2A, E-Mail, Remote Shell, `submit_result` | Neue Fähigkeiten als Tool oder Adapter, nicht als Sonderpfad im Core. |
| **Permissions** | Deny-by-default, Wildcards, letzte passende Regel, `deny > ask > allow`, gespeicherte Approvals, Shell-Scanner, Verzeichnisgrenzen, OS-Hardening | Aktionsrechte sind stark. Daten- und Ausgaberechte fehlen als eigene Schicht. |
| **HITL** | Durables Approval, Approval-URL, Fragen per UI/E-Mail, Token/Deadline/Status | Gute Basis für neue Gates (z. B. PR-Delivery, Datenfreigabe). |
| **Provider** | OpenAI-kompatibel, Streaming, Fehlerklassifikation, Retry/Backoff, Budgets, Cache-Affinität, LLM-Trace | Decision Models gehören als eigener Port oder in den Workflow-Runner, nicht in den Chat-Provider. |
| **Workflows** | ReactFlow-Graphen, 16 Node-Typen, `classify`, GraphCompiler, Versionen mit Content-Hash, Journal-Resume, Recovery, ContextAssembler | Neue Node-Typen sind der natürliche Ort für Delivery-, Memory- und Policy-Schritte. |
| **Interop** | MCP-Client, A2A-Client und optionaler A2A-Server, HTTP/SSE | kagent ist die beste Vergleichsreferenz für A2A/MCP/OTel-Härtung. |
| **Observability** | SSE, OpenTelemetry, rotierender LLM-Trace, Token-/Kostenrechnung | Provenance und GenAI-Semantic-Conventions sind die nächsten Schritte. |
| **Deployment** | Docker JVM/native, systemd, ECS/Fargate, GCP/AWS-Dokus | Helm/Kubernetes fehlt; ein Operator ist eine strategische, keine Core-Entscheidung. |
| **Memory** | Instructions, `AGENTS.md`/`CLAUDE.md`, `SYSTEM.md`, Compaction, keine Vektor- oder Graph-Memory | Langzeit-Memory ist die größte konzeptionelle Lücke, aber kein Sofortbedarf. |
| **Sandbox** | Shell-Scanner, OS-Hardening, `docker-secure-shell`-Variante, Remote Shell | Keine Isolation pro Session, keine Egress-Policy, keine Umgebungs-Snapshots. |

### 1.2 Filterkriterien

Eine Erweiterung ist nur „sinnvoll“, wenn sie:

1. **einen echten QOC-Schmerz löst** (Produktion, Sicherheit, Kosten, Qualität, Audit),
2. **zur Architektur passt** (Port/Adapter, Event-Log, reiner Core, Java 21),
3. **kein Duplikat** vorhandener QOC-Funktionen ist,
4. **messbar** ist (Experiment, klare Erfolgsschwelle),
5. **reversibel** bleibt (optionaler Adapter, Konfiguration, kein One-Way-Door),
6. **keinen operativen Ballast** erzeugt, der QOCs „runtime, not platform“-These widerspricht.

### 1.3 Prioritätsbegriffe

- **Jetzt:** nach den bestehenden P0-Produktionsblockern (durable Approvals, Idempotenz, Outcome-Algebra, Recovery, Compaction, Prompt-Stabilität, Limits, Observability) und mit kleinem Aufwand oder hohem Sicherheitsgewinn.
- **Als Nächstes:** klar sinnvoll, aber mittlerer Aufwand oder Voraussetzung durch ein Experiment.
- **Später/Beobachten:** strategisch interessant, aber noch kein belegter Bedarf.
- **Ablehnen:** passt nicht zu QOCs Kern oder würde bestehende Invarianten verwässern.

---

## 2. Gesamtmatrix

| ID | Erweiterung | Quelle | QOC-Anker | Nutzen | Aufwand | Risiko | Empfehlung | Horizont |
|---|---|---|---|---|---|---|---|---|
| L-01 | Flide als Decision-Backend | Flide | `TypeSafeDecisionClient`, `classify` | hoch | S | niedrig | **Übernehmen, optional** | Jetzt |
| L-02 | Provenance für Kontext/Antworten | TrustGraph, git-ontology-agent | `Journal`, `LlmTrace`, HTTP-API | hoch | M | mittel | **Übernehmen** | Jetzt |
| L-03 | A2A/MCP/OTel-Härtung | kagent | A2A-Server, MCP, OTel | hoch | S/M | niedrig | **Übernehmen** | Jetzt |
| L-04 | Container-Sandbox | OpenComputer | `ProcessRunner`, `FileSystem` | sehr hoch | M/L | mittel | **Spike, dann übernehmen** | Als Nächstes |
| L-05 | Egress-Policy für Tools | OpenComputer | `HttpTransport`, Shell, MCP | hoch | M | mittel | **Übernehmen** | Als Nächstes |
| L-06 | Prompt-Relation-Router | git-ontology-agent | `session_inbox`, `STEER`/`QUEUE` | mittel | M | mittel | **Anpassen** | Als Nächstes |
| L-07 | Artifact-Store (S3-kompatibel) | Omnigraph | `ToolOutputStore`, `ArtifactRef` | mittel | M | niedrig | **Übernehmen, wenn DB wächst** | Als Nächstes |
| L-08 | Plan/Apply für Definitionen | Omnigraph, kagent | `workflow_version`, `agent_definition`, CLI | mittel | M | mittel | **Anpassen** | Später |
| L-09 | MemoryPort und Answerability | SelfGraph, TrustGraph | `ContextBuilder`, neuer Port | hoch | L | hoch | **Spike** | Später |
| L-10 | PR-Delivery-Workflow | OpenComputer | Workflow-Nodes, Worktrees, Saga | mittel | S/M | niedrig | **Anpassen** | Später |
| L-11 | Helm/Kubernetes-Paketierung | kagent | `deploy/` | mittel | M | niedrig | **Optional** | Später |
| L-12 | Data-Policy-Hook | TrustGraph | `PermissionHook` | mittel | M | mittel | **Beobachten** | Später |
| L-13 | Agent-Templates/Vererbung | kagent | `AgentDefinition` | niedrig | M | niedrig | **Beobachten** | Später |
| L-14 | Graph-DB/RDF/Cedar im Core | Omnigraph, TrustGraph | – | niedrig | L | hoch | **Ablehnen** | – |

**Wichtigste Erkenntnis:** Die größten QOC-Gewinne liegen nicht in mehr Agenten-Features, sondern in **Isolation, Provenance und einer kleinen, gut platzierten Decision-Schicht**. Die Graph- und Ontologieprojekte sind wertvoll als Denkmodell und optionale Adapter, nicht als neue Kernabhängigkeiten.

---

## 3. Projekt für Projekt

### 3.1 git-ontology-agent

**Was das Projekt zeigt:** Jede Nutzeranfrage ist ein Git-Branch, der Arbeitszustand ist eine typisierte Claim-Liste mit `depends_on`, ein Decision Model ordnet neue Nachrichten als Supplement, Correction, Independent, Cancel oder Progress ein. Nur abhängige Claims werden neu berechnet, der Merge erfolgt nach Tyregeln, und Antworten tragen ihre Herkunft.

**Relevanz für QOC:** QOC hat mit `STEER`/`QUEUE` bereits ein Zustellmodell für Nachrichten während eines laufenden Turns, aber keine semantische Einordnung. Es gibt Session-Fork/Tree, Snapshots und Revert, aber keine partielle Neuberechnung auf Claim-Ebene. Der wertvollste Teil ist die **Beziehungsklassifikation**, nicht das Git-Store-Modell.

**Konkrete Erweiterung L-06: Prompt-Relation-Router**

- Neue optionale Stufe bei der Prompt-Admission: `supplement`, `correction`, `independent`, `cancel`, `status`.
- Mapping auf bestehende QOC-Semantik:
  - `supplement`/`correction` → `STEER` in die laufende Session,
  - `independent` → `QUEUE` als neue Aufgabe,
  - `cancel` → `interrupt`/Abbruch,
  - `status` → sofortige Fortschrittsantwort ohne neuen Turn.
- Decision Model mit Confidence; unter Schwellwert fällt QOC auf das heutige Verhalten zurück (kein stilles Uminterpretieren).
- Neues Event `PromptRelationClassified` mit Modell, Confidence und gewählter Zustellung; keine neue Wahrheitsquelle.
- Kein Git-Branch pro Request. Die vorhandene Session- und Inbox-Struktur bleibt.

**Was genau vergleichen:**

- QOCs `STEER`-/`QUEUE`-Semantik gegen die fünf Relationen: Welche Fälle sind heute schon abgedeckt, welche fehlen?
- Klassifikationsgüte und Latenz auf echten QOC-Sessions; besonders die **False-STEER-Rate**, weil ein falsches Supplement einen laufenden Turn stört.
- Verhalten bei bereits ausgeführten Tool Calls: Darf eine Correction abgeschlossene Side Effects revidieren? QOC hat Snapshots/Saga-Undo, aber keine Claim-Invalidierung.
- Claim-Merge-Regeln gegen QOCs Event-Log: Was wäre Projektion, was neue Wahrheit?

**Empfehlung:** L-06 als mittelfristige Erweiterung prüfen. Die Claim-/Branch-Mechanik nicht übernehmen; sie würde QOCs Event-Sourcing und Session-Modell duplizieren.

---

### 3.2 Flide

**Was das Projekt zeigt:** Eine auf Decision Models spezialisierte Inferenz-Engine. Sie berechnet den gemeinsamen Prompt-Zustand einmal und beantwortet mehrere typisierte Fragen in einem Forward Pass. Sie serviert die Jev-API `/v1/systemone`, also genau die Schnittstelle, die QOCs `TypeSafeDecisionClient` bereits nutzt.

**Relevanz für QOC:** QOC hat im Workflow-Runner bereits eine `classify`-Node mit TypeSafe, Ollama/tev1 und Fail-open/Fail-closed. Flide ist damit kein neues Feature, sondern ein möglicher **zweiter Backend-Adapter** für dieselbe Node. Das ist der günstigste Hebel in dieser ganzen Analyse.

**Konkrete Erweiterung L-01: Flide als optionales Decision-Backend**

- Keine neue Java-Abhängigkeit. Flide läuft als Sidecar/Container und wird über HTTP angesprochen.
- Konfiguration über die bestehende `TYPESAFE_BASE_URL` bzw. einen neutraleren Namen `DECISION_BASE_URL`; Modellname, Timeout und Retries bleiben Node-Konfiguration.
- Optional ein Docker-Compose-Profil `flide` für Self-hosted-Deployments.
- Der `classify`-Node-Code bleibt unverändert, solange das Wire-Format passt.

**Was genau vergleichen:**

- **Wire-Kompatibilität:** Request/Response für YesNo, Choice, Scale, `noul`, Probabilities, Margins. Wo weicht Flide vom TypeSafe-Format ab?
- **Modellgüte:** dieselben 200–500 QOC-Entscheidungen (Routing, Guard, `low_margin`, Klassifikation) gegen TypeSafe und Ollama/tev1.
- **Latenz/Durchsatz:** p50/p95 pro Entscheidung, mehrere Fragen pro Call, Batch-Größe.
- **Betrieb:** GPU-Typ und -Speicher, Startup, Modell-Download, Lizenz, Updatepfad, Ausfallverhalten.
- **Kosten:** GPU-Stunden gegen API-Preise bei QOCs erwartetem Volumen.

**Empfehlung:** L-01 sofort als kleinen Bake-off umsetzen. Flide bleibt optional; TypeSafe bleibt Default, solange keine klare Kosten-/Latenzverbesserung belegt ist.

---

### 3.3 Omnigraph

**Was das Projekt zeigt:** Eine objektstorage-native Graph-DB mit typisierter Ontologie, Graph-Branches, Three-Way-Merge, Time Travel, hybridem Retrieval (Graph + Vektor + BM25 + RRF), Cedar-Policies und deklarativen Clustern mit `plan`/`apply`.

**Relevanz für QOC:** QOC hat bereits Versionen mit Content-Hash für Workflows und eine Agent-SSOT. Es fehlt ein deklaratives Bundle mit Diff/Apply/Rollback. Außerdem liegen große Tool-Outputs in SQLite/PostgreSQL; ein Objektspeicher-Adapter wäre eine natürliche Ergänzung. Die Graph-DB selbst ist für QOCs Core zu schwer.

**Konkrete Erweiterungen:**

- **L-08 Plan/Apply für Agent- und Workflow-Definitionen:** Ein `qoc.yaml`-Bundle deklariert Agents, Workflows, MCP-Server und Policies. `qoc plan` zeigt den Diff, `qoc apply` veröffentlicht transaktional, `qoc rollback` geht auf eine Version zurück. Owner-Checks und Audit-Events werden wiederverwendet.
- **L-07 Artifact-Store:** Ein `ArtifactStore`-Port für S3-kompatible Speicher; `ArtifactRef` und SHA-256 bleiben die stabile Referenz. SQL speichert Metadaten, der Blob liegt im Objektspeicher.
- **L-09a Retrieval-Schnittstelle:** Für spätere Memory nicht die Graph-DB übernehmen, sondern das Muster „hybrides Retrieval mit RRF und Filtern“ als Interface modellieren.
- **Policy-Konzepte:** Scopes, serverseitig aufgelöste Akteure und Audit-Events sind gute Ideen. Cedar selbst ist für QOC zu schwer und würde die bestehende Regelkette ersetzen.

**Was genau vergleichen:**

- QOCs CRUD/Versionierung gegen `plan`/`apply`: Was fehlt wirklich, um Definitionen per GitOps zu verwalten?
- DB-Wachstum und p95-Latenz großer Tool-Outputs gegen Objektspeicher; Ausfall- und Retention-Verhalten.
- Retrieval-Qualität und Betriebsaufwand eines hybriden Memory-Interfaces gegen einfache BM25-Suche.
- Policy-Ausdrucksstärke: QOCs Regelkette vs. Cedar für Daten- und Aktionsrechte.

**Empfehlung:** L-07 und L-08 als mittelfristige Erweiterungen prüfen. Keine Graph-DB und kein Cedar im Core.

---

### 3.4 kagent

**Was das Projekt zeigt:** Eine Kubernetes-native Agenten-Plattform mit CRDs für Agent, AgentTemplate, Harness und ModelConfig, gemeinsam genutzten MCP-Servern, A2A, PostgreSQL-Sessions, Substrate-Laufzeit, OTel und Helm.

**Relevanz für QOC:** QOC hat Agents, MCP und A2A bereits, aber keine Kubernetes-Paketierung und keine explizite Trennung von Verhalten (Template) und Laufzeit (Harness). Die wichtigsten Learnings liegen weniger in neuen Features als in **Interop-Härtung und Betriebskonventionen**.

**Konkrete Erweiterungen:**

- **L-03 A2A/MCP/OTel-Härtung:** QOCs offene Punkte (A2A-Token-Validierung, Per-Service-Allowlist, Token-Refresh) gegen kagents Implementierung vergleichen. Agent-Card-Format, Task-Lifecycle, Streaming, Cancellation und Fehler-Mapping testen. MCP-Server-Katalog und per-Agent-Scoping (QOC M17) mit kagents `RemoteMCPServer` vergleichen. OTel-GenAI-Attribute und Trace-Propagation über A2A/MCP/Subagents vereinheitlichen.
- **L-11 Helm/Kubernetes:** Zuerst ein Helm-Chart für QOC (Deployment, Service, PostgreSQL, Secrets, OTel). Ein Operator mit CRDs ist erst sinnvoll, wenn Plattformteams QOC wirklich deklarativ betreiben wollen.
- **L-13 Agent-Templates:** Eine leichte Vererbung/Composition für `AgentDefinition` prüfen (Basis-Template + Overrides), ohne das Harness-Konzept von kagent zu kopieren.

**Was genau vergleichen:**

- A2A: Agent Card, Auth/Token-Propagation, Task-Zustände, Streaming, Abbruch, Fehlercodes.
- MCP: Server-Katalog, Health/Status, OAuth, Tool-Scoping pro Agent, gemeinsame Nutzung.
- OTel: Span-Namen und -Attribute, Trace-Propagation, Kosten-/Token-Attribution.
- Sessions: Checkpoint/Fork/Suspend/Resume-Semantik gegen QOCs Fork/Tree, Snapshots und Claims.
- Deployment: QOCs Docker/systemd/ECS gegen Helm/K8s; Aufwand, Secrets, Skalierung, Session-Affinität.

**Empfehlung:** L-03 jetzt, L-11 und L-13 später. QOC soll kein Kubernetes-Operator werden, nur weil kagent einer ist.

---

### 3.5 SelfGraph

**Was das Projekt zeigt:** Ein SDK, das Text in einen versionierten Knowledge Graph überführt. Fakten tragen Event-Time, neue Fakten lösen alte ab, `as_of` liest historische Zustände, `graph.diff` zeigt Änderungen, eine Ontologie groundingt Fragen, und `check_answerable` prüft, ob der Graph eine Frage überhaupt beantworten kann. Ein `level`-Regler hält den Default deterministisch und macht LLM-Tiers opt-in.

**Relevanz für QOC:** QOC hat keine sessionübergreifende semantische Memory. Die bestehende Historie ist turn-basiert und wird kompaktiert, nicht in Entitäten und Relationen überführt. SelfGraph ist damit die beste Referenz für eine **Memory-Schicht**, aber QOCs eigenes Learnings-Dokument hat autonome Memory-Pipelines ausdrücklich als Non-Goal markiert. Die Empfehlung muss also kleiner starten.

**Konkrete Erweiterungen:**

- **L-09 MemoryPort:** Ein neuer Port `MemoryStore` mit Entitäten, Relationen, Event-Time, Provenance und Konfliktregeln. Adapter: SQL-basiert für den Anfang, später SelfGraph per MCP/HTTP. Der Event-Log bleibt die Wahrheit für Sessions; Memory ist eine abgeleitete, explizit schreibbare Projektion.
- **Answerability als Preflight:** `check_answerable` als optionale `PreflightCheck`- oder `VerifyOnStop`-Regel: Fehlt benötigtes Wissen, fragt QOC nach oder retrievalt gezielt, statt zu halluzinieren.
- **Tiered Extraction:** Deterministische Extraktion als Default, LLM nur für unentschiedene Fälle, mit `max_tier`-Cap. Das passt zu QOCs Kosten- und Fail-safe-Denken.
- **Temporale Semantik:** `as_of`, Retire statt Delete und `diff` als Vorbild für „was hat sich seit dem letzten Turn geändert?“.

**Was genau vergleichen:**

- Memory-Semantik: append-only, Retire, Contradiction-Policy, Entity Resolution. Was passt zu QOCs Event-Log und was würde ihn duplizieren?
- Retrieval: BM25 + Graph-Traversal vs. Vektor. QOC hat keinen Vektor-Store; SelfGraph zeigt, wie weit man ohne geht.
- `check_answerable`: Qualität, Latenz, false-positive/negative Rate auf QOC-Aufgaben.
- Kosten und Pflegeaufwand: Wer schreibt Memory, wann, mit welcher Freigabe?
- Integration: SQL-Adapter vs. SelfGraph-MCP; Datenhoheit, Betrieb, Ausfallverhalten.

**Empfehlung:** L-09 als Spike, nicht als sofortiges Produkt-Feature. Zuerst kuratierte Projekt-Memory und `RULES.md`, dann ein MemoryPort mit einem einfachen SQL-Adapter. SelfGraph als Referenz und optionaler Adapter, nicht als Kernabhängigkeit.

---

### 3.6 TrustGraph

**Was das Projekt zeigt:** Eine Ontologie-Runtime mit RDF/OWL/SHACL-AF/PROV-O, ontologiegestützter Extraktion, GraphRAG, Provenance von Dokument über Chunk bis zur Kante, dreiwertiger Policy-Auswertung (Allowed/Filtered/Violation), Decision Scoring und Multi-Tenancy.

**Relevanz für QOC:** Zwei Ideen sind direkt wertvoll, ohne den RDF-Stack zu übernehmen: **Provenance** und **Datenfilterung**. QOC hat den Event-Log und den LLM-Trace, aber keine strukturierte Antwort auf „welche Quellen und Tool-Ergebnisse haben diese Antwort beeinflusst?“. QOCs Permissions regeln Aktionen, nicht die Sichtbarkeit einzelner Datensätze in Tool-Ausgaben.

**Konkrete Erweiterungen:**

- **L-02 Provenance:** Ein `ContextProvenance`-Event/Projektionsmodell, inspiriert von PROV-O, aber als einfache QOC-Typen: Antwort → Kontextblöcke → Tool-Results → Quellen. Enthält Hashes und IDs, nicht die Inhalte selbst. Abfragbar über die HTTP-API und im UI darstellbar.
- **L-12 Data-Policy-Hook:** Ein separater Hook für Tool-Ausgaben und MCP-Ressourcen mit `allow`/`filter`/`deny`, unabhängig von der Aktions-Permission. Jede Entscheidung erzeugt ein Audit-Event. QOCs Regelkette bleibt für Aktionen zuständig.
- **L-09b Ontologie:** Nur relevant, wenn QOC später Dokumente oder strukturierte Daten ingestiert. Dann Extraktion und Validierung von TrustGraph als Referenz prüfen, nicht RDF als Speicherformat.
- **L-12b Multi-Tenancy:** Workspaces, API-Keys, Capabilities und Rate-Limits gegen QOCs RBAC-Plan und offene Punkte (Resource-ACL, per-User-Rate-Limit) vergleichen.

**Was genau vergleichen:**

- Provenance-Granularität: Welche Frage muss beantwortbar sein (Audit, Debugging, Compliance)? Welche Daten dürfen gespeichert werden?
- Overhead: Event-Größe, Schreib-/Lese-Latenz, Speicherwachstum, Verhalten nach Compaction/Revert.
- Data-Policy: Reicht ein Hook mit `allow/filter/deny`, oder braucht es eine Policy-Sprache? Wie werden Fehlentscheidungen auditiert?
- Tenancy: QOCs Owner/Project gegen TrustGraphs Workspaces und Capabilities.

**Empfehlung:** L-02 jetzt als schlanken Spike, L-12 später. RDF/OWL/SHACL nicht in den Core.

---

### 3.7 OpenComputer

**Was das Projekt zeigt:** Einen Coding-Agenten in Slack, der pro Session in einer isolierten Umgebung läuft (rootless Podman für die Calvin-Sessions; die Self-Hosting-Plattform darunter nutzt QEMU/KVM-Sandboxes), outbound-only vernetzt ist, seinen Zustand lokal und git-backed persistiert und das Ergebnis als Pull Request liefert.

**Relevanz für QOC:** Das ist die wichtigste Sicherheitsreferenz. QOC führt Shell, Dateien und Prozesse auf dem Host aus und schützt sie durch Permissions, Shell-Scanner und OS-Hardening. Das ist für vertrauenswürdige Entwickler okay, aber keine Sandbox für beliebigen Agenten-Code.

**Konkrete Erweiterungen:**

- **L-04 Container-Sandbox:** Ein `ExecutionEnvironment`-Port oder Adapter für `ProcessRunner`/`FileSystem`, der eine Session in einem rootless Container ausführt. Workspace-Mount, Resource-Limits, Cleanup, PTY, Background-Prozesse und MCP-stdio müssen erhalten bleiben. MicroVM (QEMU/KVM) als spätere Option.
- **L-05 Egress-Policy:** Netzwerkzugriffe von Shell, Web- und MCP-Tools über einen Proxy oder eine Allowlist führen; Default outbound-only. QOCs `HttpTransport` ist der natürliche Anker.
- **L-10 PR-Delivery-Workflow:** Ein Workflow-Node oder Skill für commit, push, PR, CI-Warten und Ergebnisbericht. QOC hat Worktrees, Git-Tools, Saga/Undo und HITL; die Delivery-Semantik fehlt.
- **L-04b Checkpoints:** Container-/Workspace-Snapshots gegen QOCs Datei-Snapshots vergleichen. VM-Hibernation ist für QOC wahrscheinlich zu schwer; Container-Checkpoint oder Workspace-Snapshot könnte reichen.

**Was genau vergleichen:**

- Isolation: Escape-Tests, Dateisystemgrenzen, Netzwerk, Prozesse, Secrets, Ressourcenlimits.
- Kompatibilität: PTY, Background-Prozesse, Signale, MCP-stdio, Worktrees, Git.
- Performance: Container-Start, p95 Tool-Latenz, Speicher/CPU, Cleanup.
- Checkpoints: granularity, Restore-Zeit, Speicherkosten, Konsistenz mit dem Event-Log.
- Delivery: Zeit bis PR, CI-Green-Rate, menschliche Eingriffe, Umgang mit Fehlern.

**Empfehlung:** L-04 und L-05 als Sicherheits-Spike priorisieren. L-10 später als Workflow-Erweiterung. Den Cloudflare-/Slack-Stack nicht übernehmen.

---

## 4. Vergleichsplan: Was genau gemessen werden sollte

### 4.1 Vergleichsdimensionen

| Dimension | Leitfrage | Metrik |
|---|---|---|
| **Qualität** | Löst die Erweiterung die Aufgabe besser? | Task-Erfolg, Accuracy/F1, Groundedness, Audit-Abdeckung |
| **Latenz** | Bleibt QOC schnell genug? | p50/p95 pro Tool-Call, Decision, Turn |
| **Durchsatz** | Skaliert es? | Entscheidungen/s, Sessions parallel, Batch-Größe |
| **Kosten** | Rechnet es sich? | Tokens, GPU-Stunden, API-Kosten, Speicher |
| **Sicherheit** | Hält es Angriffen stand? | Escape-Tests, Egress-Block, Permission-Bypass, Secret-Leak |
| **Zuverlässigkeit** | Überlebt es Crash und Retry? | verlorene Approvals, Duplikate, Recovery-Zeit |
| **Betrieb** | Bleibt es wartbar? | Dependencies, Deployment, Backup/Restore, Observability |
| **Architektur-Fit** | Passt es zu QOC? | Ports/Adapter, Core-Reinheit, ArchUnit, LOC |
| **Datenhoheit** | Wo liegen Daten? | lokal/Cloud, Verschlüsselung, Retention, Export |

### 4.2 Experimente

#### E1: Decision-Backend-Bake-off (Flide vs. TypeSafe vs. Ollama/tev1)

- **Hypothese:** Flide liefert vergleichbare Decision-Qualität bei besserer Latenz/Kosten und bleibt self-hosted.
- **Setup:** 200–500 echte QOC-Entscheidungen aus `classify`-Nodes und Guard-Szenarien, mit goldenen Labels. Gleiche Prompts, drei Backends, mehrere Fragen pro Call.
- **Metriken:** Accuracy/F1, p50/p95, Durchsatz, GPU/CPU, Kosten, Fehlerverhalten, Modellverfügbarkeit.
- **Entscheidungsregel:** Flide wird optionales Backend, wenn Accuracy höchstens 2 Prozentpunkte schlechter und p95 nicht schlechter als TypeSafe ist; sonst bleibt TypeSafe Default.

#### E2: Prompt-Relation-Replay (git-ontology-agent)

- **Hypothese:** Ein Relation-Router reduziert falsche Zustellungen und verbessert Mehrfachanfragen.
- **Setup:** 100–200 Mehrfachanfragen aus QOC-Sessions, Labels `supplement/correction/independent/cancel/status`. Decision Model gegen Heuristik und gegen das heutige `STEER`/`QUEUE`-Verhalten.
- **Metriken:** Accuracy, False-STEER-Rate, Latenz, Auswirkung auf laufende Tool Calls, Nutzerkorrekturen.
- **Entscheidungsregel:** Nur übernehmen, wenn False-STEER unter 2 % liegt und der UX-Gewinn in Tests sichtbar ist; sonst heutiges Verhalten behalten.

#### E3: Provenance-Overhead (TrustGraph/git-ontology-agent)

- **Hypothese:** Kontext- und Antwort-Provenance ist mit geringem Overhead möglich und beantwortet Audit-Fragen.
- **Setup:** `ContextProvenance`-Event für 20 Sessions; Verknüpfung Antwort → Kontextblöcke → Tool-Results → Quellen; Rekonstruktion nach Compaction und Revert.
- **Metriken:** Event-Größe, Schreib-/Lese-Latenz, Speicherwachstum, Abdeckung von 10 typischen Audit-Fragen.
- **Entscheidungsregel:** Übernehmen, wenn der Overhead unter 5 % bleibt und mindestens 9 von 10 Audit-Fragen beantwortbar sind.

#### E4: Sandbox-Isolation (OpenComputer)

- **Hypothese:** Eine Container-Sandbox pro Session ist mit vertretbarem Overhead möglich und schließt die wichtigsten Escape-Pfade.
- **Setup:** Eine Session in rootless Podman, Workspace-Mount, PTY, Background-Prozess, MCP-stdio, Egress-Test, Secret-Test, Cleanup.
- **Metriken:** Container-Start, p95 Tool-Latenz, CPU/Speicher, Isolationstests, Kompatibilität, Cleanup-Zeit.
- **Entscheidungsregel:** Als optionales Profil übernehmen, wenn alle Isolationstests grün sind und p95 höchstens doppelt so hoch wie lokal ist; sonst nachbessern oder verwerfen.

#### E5: Artifact-Store (Omnigraph)

- **Hypothese:** Große Tool-Outputs belasten SQLite/PostgreSQL und gehören in einen Objektspeicher.
- **Setup:** 30 Tage repräsentative Tool-Outputs; SQL vs. S3-kompatibler Store; Retention und Ausfalltests.
- **Metriken:** DB-Wachstum, p95 Lesen/Schreiben, Kosten, Konsistenz, Recovery.
- **Entscheidungsregel:** Adapter bauen, wenn DB-Wachstum oder p95 auffällig steigen; sonst später.

#### E6: Plan/Apply für Definitionen (Omnigraph/kagent)

- **Hypothese:** Ein deklaratives Bundle mit Diff/Apply/Rollback erleichtert Enterprise- und GitOps-Betrieb.
- **Setup:** `qoc.yaml` mit 3 Agents und 2 Workflows; `plan`, `apply`, `rollback`, konkurrierende Änderungen, Owner-Checks.
- **Metriken:** Diff-Genauigkeit, Apply-Dauer, Rollback-Erfolg, Konflikte, Audit-Vollständigkeit.
- **Entscheidungsregel:** Übernehmen, wenn mindestens zwei reale Nutzer/GitOps-Szenarien den Bedarf bestätigen.

#### E7: Interop-Matrix (kagent)

- **Hypothese:** QOC und kagent können über A2A/MCP/OTel zuverlässig zusammenspielen.
- **Setup:** QOC-A2A-Server gegen kagent-Client und umgekehrt; gemeinsamer MCP-Server mit zwei Agents; Trace-Propagation über A2A/MCP/Subagents.
- **Metriken:** erfolgreiche Tasks, Token-Validierung, Fehler-Mapping, Trace-Vollständigkeit, Abbruch/Timeout.
- **Entscheidungsregel:** Alle Pflichtfälle grün; offene Lücken werden als konkrete Issues mit Priorität erfasst.

#### E8: Memory und Answerability (SelfGraph/TrustGraph)

- **Hypothese:** Eine kleine, kuratierte Memory-Schicht verbessert Antwortqualität und senkt Tokens, ohne operative Komplexität zu sprengen.
- **Setup:** Kuratierte Projekt-Memory (Markdown/SQL) mit BM25 und Graph-Links; `check_answerable` als Preflight; 50 typische Fragen.
- **Metriken:** Antwortqualität, Tokens, Latenz, Halluzinationen, Pflegeaufwand, Datenhoheit.
- **Entscheidungsregel:** Nur weiterverfolgen, wenn Qualität oder Tokens klar besser sind und die Pflege von einem Team leistbar ist; sonst bei manueller Memory/`RULES.md` bleiben.

---

## 5. Priorisierte Roadmap

### 5.1 Jetzt (nach den bestehenden P0-Blockern)

1. **L-01 Flide-Backend-Bake-off** (S): gleiche `classify`-Node, nur Konfiguration und Messung.
2. **L-03 A2A/MCP/OTel-Härtung** (S/M): offene QOC-Punkte schließen, Interop mit kagent testen.
3. **L-02 Provenance-Spike** (M): Event/Projektion für eine Session, Audit-Fragen validieren.
4. **L-04 Sandbox-Spike** (M/L): Container-Profil parallel prüfen, weil es die größte Sicherheitslücke ist.

### 5.2 Als Nächstes

5. **L-05 Egress-Policy** (M): Allowlist/Proxy für Shell, Web und MCP.
6. **L-06 Prompt-Relation-Router** (M): Decision Model auf der bestehenden Inbox.
7. **L-07 Artifact-Store** (M): S3-Adapter, falls Messung DB-Wachstum zeigt.
8. **L-08 Plan/Apply** (M): deklarative Agent-/Workflow-Bundles.
9. **L-10 PR-Delivery** (S/M): Workflow-Node oder Skill für commit/push/PR/CI.

### 5.3 Später/Beobachten

10. **L-09 MemoryPort + Answerability** (L): erst nach kuratierter Memory und klarem Bedarf.
11. **L-11 Helm/Kubernetes** (M): nur bei Plattform-Bedarf; Operator noch später.
12. **L-12 Data-Policy-Hook** (M): nur wenn externe Datenquellen mit Zeilen-/Feldrechten dazukommen.
13. **L-13 Agent-Templates** (M): Komfort, kein Produktionsblocker.

### 5.4 Ablehnen

| Idee | Warum nicht |
|---|---|
| Git-Branches/Claims als primäre Session-Wahrheit | Dupliziert QOCs Event-Log und Session-Modell; nur Relation-Router und Provenance übernehmen. |
| Graph-DB im Core | QOC ist Runtime, keine Datenbank; als externer Memory-Adapter später denkbar. |
| RDF/OWL/SHACL/Cedar als Kern | Zu schwer, zu viel Betrieb, ersetzt QOCs einfache und testbare Regelkette. |
| Kubernetes-CRDs als primäres Konfigurationsmodell | QOC soll auch ohne Cluster laufen; Helm/Operator optional. |
| Cloudflare Workers/Slack/PR-UI | Produktoberfläche, nicht Engine-Kern. |
| Python/PyTorch-Inferenz im JVM-Prozess | Flide per HTTP, nicht als Java-Abhängigkeit. |
| Autonome Memory-Pipeline | Widerspricht dem bestehenden QOC-Non-Goal; zuerst manuelle Memory. |
| Exactly-once-Versprechen | Bei at-least-once plus Idempotenz bleiben; keine Überversprechung. |
| Multi-Agenten-Swarm-Runtime | QOC hat Subagents, Workflows und A2A; kein zusätzlicher Swarm-Layer. |

---

## 6. Offene Fragen

1. **Primäres Deployment:** single VM, Container, Kubernetes oder serverless? Das entscheidet über Helm/Operator und Sandbox.
2. **Bedrohungsmodell:** Sind Shell-Tools für vertrauenswürdige Entwickler oder für beliebigen Agenten-Code gedacht? Ohne diese Antwort ist die Sandbox-Priorität unklar.
3. **Memory-Produkt oder Plugin?** Ist sessionübergreifendes Wissen ein QOC-Feature oder ein externer MCP-Service?
4. **Enterprise-Daten:** Welche Quellen brauchen Datenfilterung unterhalb der Tool-Ebene?
5. **Decision-Budget:** Wie viele Classification-Calls pro Turn sind akzeptabel, und welche Latenz ist erlaubt?
6. **GitOps:** Müssen Agenten und Workflows aus Git deploybar sein, oder reicht die DB/UI?
7. **Interop-Partner:** Welche A2A-/MCP-Clients und -Server sind real relevant?
8. **Audit-Anforderungen:** Welche Fragen muss QOC nachträglich beantworten können? Das bestimmt die Provenance-Granularität.

---

## 7. Quellen und Abgrenzung

- **QOC:** `README.md`, `docs/Features.md`, `docs/meta/ROADMAP.md`, `docs/meta/STATUS.md`, `docs/meta/FUTURE-WORK.md`, `docs/spec/learnings.md`, `docs/plans/typesafe-decision-model-integration.md`, `docs/plans/node-data-contracts-and-context-economy.md`, `docs/plans/rbac-implementation-plan.md` sowie die Ports/Contracts unter `engine/src/main/java/de/augmentia/oc/engine/core/`.
- **Sieben Projekte:** `REPOS.md` mit detaillierten Profilen, Architektur, Code-Orientierung und Reifegrad.
- **GitHub-Metadaten:** Abruf vom 22.11.2026.
- **Nicht Teil dieser Analyse:** konkrete Implementierung, Schätzungen in Personentagen, detaillierte Kostenmodelle. Die Aufwände sind relative Größen (S/M/L), keine Commitments.
- **Wichtige Einschränkung:** Die sieben Projekte sind überwiegend Python, Rust und TypeScript. Direkte Codeübernahme ist minimal; der Wert liegt in Semantik, Protokollen, Betriebsmodellen und Testideen.

---

## 8. Empfehlung in einem Satz

QOC sollte aus den sieben Projekten **nicht deren Stacks**, sondern drei Denkweisen übernehmen: **OpenComputers Isolation**, **TrustGraphs Provenance und Daten-Governance** und **Flides spezialisierte Decision-Schicht**, ergänzt um **SelfGraphs temporale Memory-Semantik** und **git-ontology-agents Relationsurteil**. Alles davon als optionale Adapter und Projekte, nie als Ersatz für QOCs Event-Log, Core-Reinheit und Deny-by-default-Permissions.
