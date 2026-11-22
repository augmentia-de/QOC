# Erweiterung: Semantisches Langzeitgedächtnis (MemoryPort)

> **Hinweis:** Diese Datei liegt in `done/`, obwohl noch Integrationsarbeiten offen sind (s. "Noch offen"). Die Kernkomponenten (Domain, Port, Adapter, Tools, Services) sind implementiert und getestet. Die verbleibenden Punkte sind Wiring/Integration, keine neuen Komponenten.

**Wofür:** QOC hat Session-Historie, Instructions und Compaction, aber kein sessionübergreifendes, versioniertes Wissen. Diese Erweiterung führt einen optionalen `MemoryPort` ein, der Fakten mit temporaler Gültigkeit, Provenance und Konfliktregeln speichert und abfragt. Die autonome Extraktion bleibt bewusst ausgeschlossen; zuerst manuelle, kuratierte Memory.

**Gewinn:** Agenten können über Sessions hinweg auf Projektwissen, Entscheidungen und Präferenzen zugreifen, statt bei jedem Turn neu zu recherchieren. Retrieval (BM25 + Graph-Links, ohne Vektor-Store) hält den Kontext klein. `check_answerable` reduziert Halluzinationen, indem vor der Antwort geprüft wird, ob das Wissen überhaupt vorhanden ist.

---

## 1. Prinzip: Optionalität und schrittweiser Ausbau

- Default `qoc.memory.enabled=false`.
- Memory ist eine **abgeleitete, explizit schreibbare Projektion**. Der Event-Log bleibt die Wahrheit für Sessions.
- Phase 1: **nur manuelle/kuratierte Memory** (UI/CLI/API zum Eintragen von Fakten, `RULES.md`-Import). Keine autonome Pipeline (aligns mit bestehendem QOC-Non-Goal).
- Phase 2: tiered Extraktion (deterministisch default, LLM opt-in, `max_tier`-Cap).
- Deaktiviert = kein Overhead, keine Kontext-Injektion, keine Schemaänderung.

## 2. Domain-Typen (core/model)

Neue Records (versiegelt, konsistent mit bestehenden Modellen):

- `Fact(factId, entity, relation, value, eventTime, validFrom, validTo, provenance, confidence, tier)`
- `MemoryQuery(entity?, relation?, asOf?, text?, limit)`
- `MemoryDiff(since, added, retired, changed)`
- `AnswerabilityResult(answerable, missingEntities, missingRelations, suggestedRetrieval)`
- `RetiredFact(factId, retiredAt, reason, supersededBy)`

Temporal-Semantik: neue Fakten **lösen alte ab** (Retire statt Delete), `as_of` liest historische Zustände, `diff` zeigt Änderungen.

## 3. Port

`core/port/store/MemoryStore` (optional, null = deaktiviert):

- `upsert(Fact)` – idempotent (deterministische IDs).
- `retire(factId, reason, supersededBy)`.
- `query(MemoryQuery)` → `List<Fact>`.
- `asOf(entity, relation, instant)` → `Option<Fact>`.
- `diff(since)` → `MemoryDiff`.
- `checkAnswerable(question)` → `AnswerabilityResult`.

Adapter: `InMemoryMemoryStore`, `SqlMemoryStore` (SQLite/Postgres), später `SelfGraphMemoryAdapter` (MCP/HTTP).

## 4. Retrieval

- **Kein Vektor-Store in Phase 1.** BM25 + Graph-Traversal (Entity/Relation-Nachbarschaft) reichen für den Anfang (SelfGraph zeigt, wie weit man ohne Vektoren kommt).
- Retrieval-Schnittstelle als eigenes, kleines Interface, damit später hybrides Retrieval (Graph+BM25+Vektor+RRF) nachgerüstet werden kann, ohne den Port zu ändern.
- Token-Budget-aware: Memory-Block wird wie andere Kontextblöcke budgetiert.

## 5. Integration in QOC

### 5.1 ContextBuilder

- Optionaler Memory-Block in der Prompt-Assembly (gated by Config und Per-Agent-Setting).
- Retrieval-Top-K und Min-Score konfigurierbar.
- Memory-Block erhält Provenance (Fact-IDs), damit Provenance (ext-02) verknüpfen kann.

### 5.2 check_answerable als Preflight

- Optionale `PreflightCheck`- oder `VerifyOnStop`-Regel: fehlt benötigtes Wissen, fragt QOC nach oder retrievalt gezielt, statt zu halluzinieren.
- Fail-open: bei Fehler oder Timeout wird die Antwort nicht blockiert.

### 5.3 Agent-Konfiguration

- `agents[].memory.allow/deny`, `memory.maxFacts`, `memory.retrieval` (`none`/`top-k`/`all`), `memory.core` (immer geladene Fakten).

### 5.4 Schreibpfad

- Manuell: UI/CLI/API (`POST /memory/facts`), Import aus `RULES.md`/`AGENTS.md`.
- Optional: Tool `memory_write` für den Agenten (mit Permission `memory`), damit der Agent selbst Fakten speichern kann – gated by Permission.
- Tiered Extraction (Phase 2): deterministisch default, LLM opt-in, `max_tier`-Cap.

## 6. Wo in QOC

| Ort | Änderung |
|---|---|
| `core/model/` | Neue Records `Fact`, `MemoryQuery`, `MemoryDiff`, `AnswerabilityResult`, `RetiredFact`. |
| `core/port/store/MemoryStore` | **Neu** (optional, null = deaktiviert). |
| `adapter/memory/`, `adapter/sql/` | `InMemoryMemoryStore`, `SqlMemoryStore`. |
| `runtime/context/ContextBuilder` | Optionaler Memory-Block + Retrieval. |
| `runtime/resilience/PreflightChecker` | Optionale Answerability-Regel. |
| `runtime/tool/*` | Optionales Tool `memory_write`/`memory_query`. |
| `quarkus/` | REST-Endpoints, UI. |
| Tests | Qualität, Tokens, Latenz, Halluzinationen, Pflegeaufwand (E8). |

## 7. Konfiguration

- `qoc.memory.enabled=false`
- `qoc.memory.backend=memory|sql|selfgraph` (folgt `engine.storage` wenn `auto`)
- `qoc.memory.maxTier=0` (0 = nur manuell, 1+ = tiered extraction)
- `qoc.memory.maxFacts=1000`
- `qoc.memory.retrieval=top-k`

## 8. Was genau vergleichen (Experiment E8)

Antwortqualität, Tokens, Latenz, Halluzinationen, Pflegeaufwand, Datenhoheit auf 50 typischen Fragen; Vergleich manuell kuratierte Memory vs. keine Memory vs. (später) tiered Extraction.

## 9. Risiken

- Stale/contradictory Fakten → Retire-Semantik und Konfliktregeln müssen klar definiert sein.
- Datenhoheit und Datenschutz (Fakten können personenbezogene Daten enthalten).
- Pflegeaufwand: Wer schreibt Memory, wann, mit welcher Freigabe?
- Kosten bei tiered Extraction.

## 10. Nicht-Ziele (in dieser Ausbaustufe)

- Keine autonome Memory-Pipeline (aligns mit bestehendem QOC-Non-Goal).
- Kein Vektor-Store in Phase 1.
- Keine Graph-DB als Kern (SelfGraph als optionaler Adapter später).
- Keine automatische Entity Resolution über Sessions hinweg ohne menschliche Freigabe.
- Kein Ersatz für Session-Historie oder Compaction.

---

## Status der Implementierung (22.11.2026)

**Implementiert (Engine-Modul + Quarkus):**
- Domain-Typen: `core/model/Fact`, `MemoryQuery`, `MemoryDiff`, `AnswerabilityResult`, `RetiredFact`.
- Port: `core/port/store/MemoryStore` (upsert/retire/query/asOf/diff/checkAnswerable).
- Adapter: `adapter/memory/InMemoryMemoryStore`, `core/noop/NoOpMemoryStore`, `adapter/sql/SqlMemoryStore` (Postgres), `adapter/sqlite/SqliteMemoryStore` (SQLite).
- `ExtensionPorts` bündelt `memory` (NoOp-Default).
- `QocEngine.memoryStore()`.
- Flyway-Migration `V14__memory.sql` (Postgres + SQLite).
- Quarkus-Wiring: `Engine.createMemoryStore()` basierend auf `engine.memory.enabled` und `engine.memory.backend` (auto/memory/sql).
- Config-Properties: `engine.memory.enabled`, `engine.memory.backend`, `engine.memory.max-tier`, `engine.memory.max-facts`, `engine.memory.retrieval`.
- `.env` / `.env.example` aktualisiert.
- **M5** Memory-Tools: `MemoryWriteTool`, `MemoryQueryTool` (runtime/tool/memory/).
- **M6** MemoryImporter: Import aus Markdown (RULES.md/AGENTS.md).
- **M7** ExtractionService: Tiered extraction (deterministisch, tier 0).
- **M8** isMemoryAnswerable: Preflight-Check in ContextBuilder (fail-open).
- **M9** ContextBuilder-Integration: Optionaler Memory-Block in Prompt-Assembly.
- **M10** RetrievalService: BM25 + graph-traversal retrieval.

**Noch offen (Integration/Wiring):**
- Memory-Tools sind in ToolRegistry registriert (Engine.start), aber nur wenn memoryEnabled. Agent-Level allow/deny nicht geprüft.
- MemoryImporter ist vorhanden, wird aber nicht automatisch beim Startup aufgerufen.
- ContextBuilder.memoryBlock() und isMemoryAnswerable() sind vorhanden; Memory-Block wird in prepare() eingefügt, aber memoryStore muss gesetzt sein.
- check_answerable Preflight ist als Methode vorhanden, aber nicht in den Agent-Loop integriert.
- M3: StorageFactory erstellt keinen MemoryStore (wiring in Engine.start, nicht in StorageFactory).
- RetrievalService bietet BM25+Graph, wird aber nicht automatisch von MemoryStore.query() genutzt.

## Offene Todos (detailiert)

### Todo M1 – ✅ IMPLEMENTED `SqlMemoryStore` ✅ IMPLEMENTED
- **Was:** Persistenter Adapter für `MemoryStore`.
- **Wo:** `engine/src/main/java/de/augmentia/oc/engine/adapter/sql/SqlMemoryStore.java`
- **Wie:** Tabelle `memory_fact` (fact_id PK VARCHAR, entity VARCHAR, relation VARCHAR, value TEXT JSON, event_time TIMESTAMP, valid_from TIMESTAMP, valid_to TIMESTAMP, provenance TEXT JSON, confidence DOUBLE, tier INT). Indizes auf (entity), (entity, relation), (valid_from, valid_to). `query`: WHERE mit optionalen Filtern. `asOf`: WHERE entity=? AND relation=? AND valid_from <= ? AND valid_to >= ?. `diff`: WHERE event_time > since. `checkAnswerable`: Count distinct entities, token-coverage-Heuristik (wie InMemory).
- **Migration:** `V14__memory.sql` (Postgres + SQLite).

### Todo M2 – ✅ IMPLEMENTED Flyway-Migration ✅ IMPLEMENTED
- **Wo:** `db/migration/V14__memory.sql`, `db/migration-sqlite/V14__memory.sql`.

### Todo M3 – ✅ IMPLEMENTED Storage-Factory
- **Wo:** Quarkus-Startup / StorageConfig.
- **Wie:** Wie bei Provenance: `engine.storage` bestimmt Backend.

### Todo M4 – ✅ IMPLEMENTED Quarkus-Config + Wiring ✅ IMPLEMENTED
- **Properties:** `qoc.memory.enabled`, `qoc.memory.backend`, `qoc.memory.maxTier`, `qoc.memory.maxFacts`, `qoc.memory.retrieval`.
- **Wo:** `Engine.java` Startup.
- **Wie:** `MemoryStore ms = enabled ? (backend==sql ? new SqlMemoryStore(ds) : new InMemoryMemoryStore()) : NoOpMemoryStore.INSTANCE;`

### Todo M5 – ✅ IMPLEMENTED Memory-Tools (`memory_write`, `memory_query`)
- **Was:** Optionale Tools, damit der Agent selbst Fakten speichern/abfragen kann.
- **Wo:** `runtime/tool/memory/MemoryWriteTool.java`, `MemoryQueryTool.java` (neues Paket).
- **Wie:** `memory_write(entity, relation, value, provenance?)` → `MemoryStore.upsert` mit deterministischer Fact-ID (z. B. Hash aus entity+relation+value). Permission `memory` (deny-by-default). `memory_query(entity?, relation?, text?, limit?)` → `MemoryStore.query`.
- **Registrierung:** In `ToolRegistry` nur wenn `qoc.memory.enabled` und Agent erlaubt.

### Todo M6 – ✅ IMPLEMENTED Import aus `RULES.md`/`AGENTS.md`
- **Was:** Beim Start Markdown-Regeln als Fakten importieren (manuell kuratiert).
- **Wo:** `runtime/memory/MemoryImporter.java` (neu) + Quarkus-Startup.
- **Wie:** Parser für einfache Fakt-Syntax (z. B. `- entity: relation = value` oder Abschnitte). Nur importieren, wenn `store.query(...)` leer (DB-Daten haben Vorrang, wie bei Agents).

### Todo M7 – ✅ IMPLEMENTED Tiered Extraction (Phase 2)
- **Was:** Automatische Fakten-Extraktion aus Sessions/Tool-Outputs, deterministisch default, LLM opt-in.
- **Wo:** `runtime/memory/ExtractionService.java`.
- **Wie:** `max_tier=0` → nur manuell. `max_tier>=1` → deterministische Extraktion (Regex/Keywords) als Tier 0. `max_tier>=2` → LLM-basierte Extraktion für unentschiedene Fälle. Cost-Cap pro Turn.

### Todo M8 – ✅ IMPLEMENTED `check_answerable` als Preflight
- **Was:** Optionale `PreflightCheck`-Regel: fehlt Wissen, nachfragen oder retrievalen statt halluzinieren.
- **Wo:** `runtime/resilience/PreflightChecker` (neue Regel) oder `runtime/context/ContextBuilder`.
- **Wie:** Wenn `memoryStore().checkAnswerable(question).answerable() == false`, entweder Synthetische Nachricht „fehlendes Wissen“ oder Retrieval-Trigger. Fail-open bei Fehler/Timeout.

### Todo M9 – ✅ IMPLEMENTED ContextBuilder-Integration
- **Was:** Optionaler Memory-Block in der Prompt-Assembly.
- **Wo:** `runtime/context/ContextBuilder.prepare()`.
- **Wie:** Wenn `memory.enabled` und Agent erlaubt: `MemoryStore.query(...)` mit entity/relation aus Kontext (oder Top-K über Text), Memory-Block als System-Block einfügen (Token-Budget-aware). Provenance-IDs mitgeben (für ext-02).

### Todo M10 – ✅ IMPLEMENTED Retrieval (BM25 + Graph)
- **Was:** Bessere Retrieval-Qualität als einfache Textsuche.
- **Wo:** `adapter/memory/Bm25MemoryRetriever.java` oder `runtime/memory/RetrievalService.java`.
- **Wie:** BM25 über Fakten-Text + Graph-Traversal (Entity/Relation-Nachbarschaft). Kein Vektor-Store in Phase 1. Später hybrides Retrieval (RRF) nachrüstbar ohne Port-Änderung.

## Erkenntnisse für unabhängige Implementierung

- **Memory ist abgeleitete Projektion, keine Wahrheit.** Event-Log bleibt Wahrheit für Sessions. Memory wird explizit geschrieben (manuell oder via Tool), nicht automatisch aus jeder Session.
- **Temporal-Semantik: Retire statt Delete.** Neue Fakten lösen alte ab; `as_of` liest historische Zustände; `diff` zeigt Änderungen. Das ermöglicht Audit und Rollback.
- **Deterministische IDs für Idempotenz.** `factId = hash(entity + relation + value)` (oder UUID mit Upsert-Semantik). Wiederholtes Upsert desselben Fakts ist no-op.
- **Kein Vektor-Store in Phase 1.** SelfGraph zeigt, wie weit man mit BM25 + Graph kommt. Vektor-Store erst nach belegtem Bedarf (E8).
- **Autonome Pipeline ist Non-Goal.** Nur manuelle/kuratierte Memory zuerst; tiered Extraction erst in Phase 2 mit `max_tier`-Cap und Cost-Cap.
- **Answerability-Heuristik ist simpel.** Token-Coverage über Entities; kann falsch positiv/negativ sein. Experiment E8 misst Qualität.
- **Provenance-Verknüpfung:** Memory-Fakten tragen `provenance`-Liste (Source-URIs). Verknüpfung mit ext-02 Provenance-Records über Fact-IDs.
- **Tests:** E8-Experiment (Antwortqualität, Tokens, Latenz, Halluzinationen, Pflegeaufwand auf 50 Fragen). Nur weiterverfolgen, wenn Qualität/Tokens klar besser.
