# Erweiterung: Provenance und Data Governance

**Wofür:** QOC hat Event-Log und LLM-Trace, aber kein erstklassiges Modell dafür, welche Quellen, Kontextblöcke und Tool-Ergebnisse eine Antwort beeinflusst haben, und keine Datenfilterung unterhalb der Tool-Ebene. Diese Erweiterung fügt ein optionales Provenance-Modell und einen optionalen Data-Policy-Hook hinzu, ohne die bestehenden Aktions-Permissions zu ersetzen.

**Gewinn:** Antworten werden auditierbar und erklärbar („warum hat der Agent das gesagt?“). Tool-Ausgaben und MCP-Ressourcen können per Datensatz gefiltert werden, was für Enterprise-/Compliance-Szenarien und Multi-Tenancy nötig ist. Beide Fähigkeiten sind standardmäßig aus und verändern das bestehende Verhalten nicht.

---

## 1. Prinzip: Optionalität

- Default `qoc.provenance.enabled=false` und `qoc.datapolicy.enabled=false`.
- Provenance ist eine **abgeleitete Projektion**, keine zweite Wahrheit. Der Event-Log bleibt die Wahrheit.
- Data-Policy ist ein **Hook**, kein Ersatz für die Aktions-Permission. `PermissionGate` regelt weiterhin, ob ein Tool ausgeführt werden darf; `DataPolicy` regelt, welche Daten in der Ausgabe sichtbar sind.
- Deaktiviert = kein Overhead, keine zusätzlichen Events, keine Schemaänderung in Pflichtpfaden.

## 2. Teil A: Provenance

### 2.1 Domain-Typen (core/model)

Neue Records (versiegelt, konsistent mit `SessionEvent`-Stil):

- `ProvenanceRecord(assistantMessageId, contextBlockIds, toolResultIds, sourceUris, modelRef, promptHash, createdAt)`
- `ContextBlockRef(blockType, id, source)` – z. B. `instruction`, `skill`, `history-range`, `tool-result`.
- `SourceRef(uri, hash, mimeType)`.

### 2.2 Port

`core/port/store/ProvenanceStore` (optional, null = deaktiviert):

- `append(sessionId, ProvenanceRecord)`
- `byMessage(sessionId, assistantMessageId)`
- `bySession(sessionId, range)`

Adapter: `InMemoryProvenanceStore`, `SqlProvenanceStore` (SQLite/Postgres). Optionaler No-Op-Adapter, wenn deaktiviert.

Alternative (ohne neuen Port): Provenance als eigener Aggregate im bestehenden `Journal` (Präfix `provenance:<sessionId>`). Der dedizierte Port ist sauberer und macht die Optionalität explizit.

### 2.3 Capture-Punkte

| Ort | Was erfasst wird |
|---|---|
| `ContextBuilder.prepare()` | Liste der Kontextblöcke (Instructions, Skills, History-Range, Tool-Snapshot-Hash) → `contextBlockIds`. |
| `Step` (nach Tool-Ausführung) | `toolResultIds` der ausgeführten Tools. |
| `HttpProviderAdapter`/Encoder | `modelRef`, `promptHash` (Hash des gesendeten Prompts). |
| `QocEngine`/`Coordinator` | Verknüpfung Assistant-Message ↔ `ProvenanceRecord` nach erfolgreichem Step. |

### 2.4 Rekonstruktion

- Nach Compaction/Revert müssen Provenance-Records weiterhin abfragbar sein (sie referenzieren IDs, nicht Inhalte).
- `GET /session/{id}/provenance?messageId=...` in Quarkus.
- UI: Provenance-Ansicht pro Antwort.

### 2.5 Speicher-/Overhead-Budget

- Records speichern **IDs und Hashes**, nicht Inhalte (keine Duplikation).
- Ziel: < 5 % Event-Overhead, 90 % der Audit-Fragen beantwortbar (Experiment E3).

## 3. Teil B: Data-Policy-Hook

### 3.1 Vertrag

Neuer Hook in `core/contract/permission/` oder `core/eval/`:

```
DataPolicy.evaluate(sessionId, agent, tool, resource, output) -> DataPolicyDecision
DataPolicyDecision { effect: ALLOW | FILTER | DENY, filteredOutput?, reason }
```

- `ALLOW`: Ausgabe unverändert.
- `FILTER`: Ausgabe wird gefiltert (z. B. Felder/Zeilen entfernt), mit Audit-Event.
- `DENY`: Ausgabe wird durch Fehler ersetzt, mit Audit-Event.

Jede Entscheidung erzeugt ein Audit-Event (`DataPolicyDecision`).

### 3.2 Anwendungspunkt

- `ToolRuntime` nach Ausführung, vor Rückgabe des `ToolOutcome` an den Step.
- Für MCP-Ressourcen: im MCP-Tool-Adapter.
- Optionaler `DataPolicyEngine`-Port (null = Pass-Through).

### 3.3 Beziehung zur Permission

- `PermissionGate` (Aktion) und `DataPolicy` (Daten) sind **orthogonal**. Ein Tool darf ausgeführt werden (`ALLOW`), aber seine Ausgabe wird gefiltert (`FILTER`).
- `PermissionHook` (bestehend, kann Effekt umkehren) bleibt für Aktions-Entscheidungen; `DataPolicy` ist eine zusätzliche, separate Schicht.

## 4. Wo in QOC

| Ort | Änderung |
|---|---|
| `core/model/` | Neue Records `ProvenanceRecord`, `ContextBlockRef`, `SourceRef`, `DataPolicyDecision`. |
| `core/port/store/ProvenanceStore` | **Neu** (optional, null = deaktiviert). |
| `core/contract/permission/` oder `core/eval/` | `DataPolicy`-Interface + `DataPolicyDecision`. |
| `adapter/memory/`, `adapter/sql/` | `InMemoryProvenanceStore`, `SqlProvenanceStore`. |
| `runtime/context/ContextBuilder` | Capture von Kontextblöcken (IDs/Hashes). |
| `runtime/step/Step` / `ToolRuntime` | Provenance-Erfassung nach Tool-Ausführung; Data-Policy-Anwendung. |
| `runtime/QocEngine` | Wiring der optionalen Stores/Hooks. |
| `quarkus/` (HTTP) | `GET /session/{id}/provenance`, UI-Ansicht. |
| Tests | Overhead-Messung, Rekonstruktion nach Compaction/Revert, Filter-Tests. |

## 5. Konfiguration

- `qoc.provenance.enabled=false`
- `qoc.provenance.backend=memory|sql` (folgt `engine.storage` wenn `auto`)
- `qoc.datapolicy.enabled=false`
- `qoc.datapolicy.rules=<path>` (optional, Regeldatei)

## 6. Was genau vergleichen (Experiment E3)

Event-Größe, Schreib-/Lese-Latenz, Speicherwachstum, Abdeckung von 10 typischen Audit-Fragen, Verhalten nach Compaction/Revert, Filter-Genauigkeit und Audit-Vollständigkeit.

## 7. Risiken

- Speicherwachstum bei hohem Tool-Output-Volumen (IDs/Hashes helfen, aber nicht gratis).
- Datenschutz: Source-URIs und Hashes können sensible Pfade offenlegen → konfigurierbare Redaktion.
- Konsistenz nach Compaction/Revert (Provenance muss rekonstruierbar bleiben).
- Data-Policy-Regeln können falsch filtern → Audit-Events und Fail-open/Fail-closed-Konfiguration.

## 8. Nicht-Ziele

- Kein RDF/OWL/SHACL/PROV-O als Speicherformat (QOC-Typen reichen).
- Kein Ersatz der Aktions-Permissions durch Data-Policy.
- Keine automatische Datenklassifikation in Phase 1 (manuelle Regeln).
- Kein Graph-DB-Backend.

---

## Status der Implementierung (22.11.2026)

**Bereits implementiert (Engine-Modul):**
- Domain-Typen: `core/model/ProvenanceRecord`, `ContextBlockRef`, `SourceRef`.
- Port: `core/port/store/ProvenanceStore` (append/byMessage/bySession/latest).
- Adapter: `adapter/memory/InMemoryProvenanceStore`, `core/noop/NoOpProvenanceStore`.
- Data-Policy: `core/contract/permission/DataPolicy`, `DataPolicyDecision`, `DataPolicyEffect`, `NoOpDataPolicy`.
- `ToolRuntime`: `dataPolicy`-Feld + Setter + Anwendung im Erfolgspfad (DENY → Failed, FILTER → Output ersetzt, vor Caching).
- `ExtensionPorts` bündelt `provenance` + `dataPolicy` (NoOp-Defaults).
- `QocEngine.provenanceStore()`, `QocEngine.dataPolicy()`.

**Noch offen:** SQL-Adapter, Quarkus-Wiring, automatische Capture-Punkte (ContextBuilder/Step/Provider/Coordinator), HTTP-Endpoint + UI, Data-Policy-Regellader.

## Offene Todos (detailiert)

### Todo P1 – `SqlProvenanceStore`
- **Was:** Persistenter Adapter für `ProvenanceStore`.
- **Wo:** `engine/src/main/java/de/augmentia/oc/engine/adapter/sql/SqlProvenanceStore.java`
- **Wie:** Analog `AbstractJdbcSessions`: Connection aus `DataSource`, try-with-resources. Methoden: `append` (INSERT), `byMessage` (SELECT WHERE session_id AND assistant_message_id), `bySession` (SELECT ... ORDER BY created_at DESC LIMIT). JSON-Spalten für context_blocks/tool_result_ids/sources (nutze `Json`-Codec oder `ContractCodec`).
- **Migration:** `V13__provenance.sql`: Tabelle `provenance_record` (id BIGSERIAL, session_id VARCHAR, assistant_message_id VARCHAR, context_blocks TEXT, tool_result_ids TEXT, sources TEXT, model VARCHAR, prompt_hash VARCHAR, created_at TIMESTAMP). Indizes auf (session_id), (session_id, assistant_message_id).

### Todo P2 – Flyway-Migration (SQLite + Postgres)
- **Wo:** `engine/src/main/resources/db/migration/V13__provenance.sql` und `db/migration-sqlite/V13__provenance.sql`.
- **Wie:** SQLite-Variante mit `INTEGER PRIMARY KEY AUTOINCREMENT`, Postgres mit `BIGSERIAL`. JSON als TEXT (beide).

### Todo P3 – Storage-Factory
- **Wo:** Dort wo `InMemoryProvenanceStore` erzeugt wird (Quarkus-Startup / `StorageConfig`).
- **Wie:** Wenn `engine.storage=sqlite|postgres`, `SqlProvenanceStore(ds)`; sonst `InMemoryProvenanceStore` oder `NoOpProvenanceStore` (wenn disabled).

### Todo P4 – Quarkus-Config + Wiring
- **Properties:** `qoc.provenance.enabled`, `qoc.provenance.backend` (auto/memory/sql).
- **Wo:** `Engine.java` Startup, dort wo `storesField`/`ExtensionPorts` gebaut werden.
- **Wie:** `ProvenanceStore ps = enabled ? (backend == sql ? new SqlProvenanceStore(ds) : new InMemoryProvenanceStore()) : NoOpProvenanceStore.INSTANCE;`

### Todo P5 – Capture-Punkte
- **P5a ContextBuilder:** Bei `prepare()` Liste `List<ContextBlockRef>` sammeln (blockType, id, source) für Instructions, Skills, History-Range, Tool-Snapshot-Hash. Rückgabe in `Prepared` oder separater Capture-Holder.
- **P5b Step:** Nach Tool-Ausführung `toolResultIds` sammeln.
- **P5c Provider-Encoder:** `modelRef` + `promptHash` (SHA-256 des serialisierten Prompts) berechnen.
- **P5d Coordinator/QocEngine:** Nach erfolgreichem Step `provenanceStore().append(sessionId, new ProvenanceRecord(...))`.
- **Wo:** `runtime/context/ContextBuilder.java`, `runtime/step/Step.java`, `runtime/provider/client/OpenAiRequestEncoder.java` (oder `HttpProviderAdapter`), `runtime/execution/Coordinator.java`/`QocEngine`.

### Todo P6 – HTTP-Endpoint + UI
- **Was:** `GET /session/{id}/provenance?messageId=...`.
- **Wo:** `quarkus/src/main/java/de/augmentia/oc/engine/http/SessionResource.java` + ReactFlow-Provenance-Ansicht.
- **Wie:** `ProvenanceStore.byMessage(...)` serialisieren (JSON). UI: Panel pro Antwort mit Kontextblöcken/Tool-Results/Sources.

### Todo P7 – Data-Policy-Regellader
- **Was:** Wenn `qoc.datapolicy.rules` gesetzt, Regeldatei laden und `DataPolicy`-Implementierung bauen.
- **Wo:** Neues `core/eval/DataPolicyRules` oder `runtime/permission/engine/DataPolicyEngine`; Quarkus-Startup.
- **Wie:** JSON/YAML-Regeln (tool, resource-pattern, effect, filter-fields). Evaluation in `DataPolicy.evaluate(...)`. Fail-closed/ Fail-open per Config.

## Erkenntnisse für unabhängige Implementierung

- **Provenance speichert IDs/Hashes, nie Inhalte.** Verhindert Duplikation und hält Records klein (< 5 % Overhead-Ziel E3).
- **ProvenanceStore ist optional (NoOp).** Deaktiviert = kein Overhead, keine Schemaänderung in Pflichtpfaden. `QocEngine.provenanceStore()` nie null (NoOp-Default).
- **Data-Policy ist orthogonal zu Permission.** `PermissionGate` = Aktion, `DataPolicy` = Daten. Ein Tool darf laufen, aber Ausgabe wird gefiltert.
- **ToolRuntime.applyDataPolicy muss vor Caching greifen**, damit gefilterte Ergebnisse nicht unfiltered gecacht werden. Bereits implementiert (Filter vor `resultCache.store`).
- **Compaction/Revert-Konsistenz:** Provenance-Records referenzieren IDs, nicht Inhalte → bleiben nach Compaction abfragbar. Revert muss zugehörige Records nicht löschen (append-only).
- **DataPolicyDecision.filteredOutput ist `JsonValue`.** Policy-Implementierer müssen `JsonValue` liefern; bei komplexen Filtern `JsonValue.of(Map/...)`.
- **Tests:** E3-Experiment (Event-Größe, Latenz, Speicherwachstum, 10 Audit-Fragen, Compaction/Revert). Mindestens 9/10 Audit-Fragen beantwortbar bei < 5 % Overhead.
