# Storage-Alternativen je Port

> **Zweck:** Bewertung der QOC-Storage-Ports für resiliente Ausführung,
> Vergleich mit der originalen OpenCode-Implementierung (`oc/`) und
> Empfehlungen für DB-Reduktion bzw. alternative Speichermedien.
>
> **Status:** 2026-11-09
>
> **Quellen:** QOC `engine`-Modul; OC-Referenzcheckout `oc/` (nur lesend
> analysiert). Bei Widersprüchen gilt die QOC-Spec unter
> [`spec/`](../spec/README.md).

## 1. Fragestellung

1. Welche Ports sind für **resiliente Ausführung** (Crash-Recovery, genau eine
   Execution pro Session, keine verlorenen Side-Effects) am wichtigsten?
2. Wie löst die **originale OC-Implementierung** diese Ports?
3. Welche **Storage-Alternativen** gibt es je Port – insbesondere zur
   Reduktion der Datenbank-Nutzung?

## 2. Bewertungsmaßstab

| Kriterium | Frage |
|---|---|
| **Durability** | Muss der Zustand vor einem Side-Effect durable sein? |
| **Frequenz** | Wie oft wird gelesen/geschrieben (pro Step, pro Event)? |
| **Volumen** | Kleine Metadaten oder große Blobs? |
| **Konsistenz** | Braucht es Transaktionen, Unique-Constraints, atomare Updates? |
| **Multi-Node** | Muss der Store von mehreren Instanzen geteilt werden? |
| **Wiederherstellbarkeit** | Kann der Zustand aus anderem Zustand abgeleitet werden? |

**Priorisierung:**

- **P0** – muss vor dem Side-Effect durable sein (Claim, Journal, History,
  Session).
- **P1** – muss einen Neustart überleben (Approvals, Instructions,
  Workspace-Zuordnung, Credentials).
- **P2** – darf verloren gehen oder neu berechnet werden (Snapshots,
  Tool-Outputs, Caches).

## 3. Vergleich QOC ↔ Original-OC

### 3.1 Storage-Architektur der Original-OC

Die Original-OC nutzt **eine einzige eingebettete SQLite-Datenbank** mit
WAL-Modus – keine Netzwerk-DB:

| Aspekt | Original-OC | Beleg |
|---|---|---|
| DB-Engine | SQLite (`bun:sqlite`, `node:sqlite`, workerd) | `packages/core/src/database/sqlite.bun.ts:87` |
| WAL | `PRAGMA journal_mode = WAL` (Default) | `sqlite.bun.ts:93` |
| DB-Pfad | `$XDG_DATA_HOME/opencode/opencode.db` (Default `~/.local/share/opencode/opencode.db`) | `packages/util/src/global-roots.ts:5`, `packages/cli/src/database-path.ts:10`, `database.ts:76` |
| Dateirechte | `chmod 0600` für DB, `-wal`, `-shm` | `database.ts:85-97` |
| Migrationen | Drizzle-Migrationen, nummeriert | `packages/core/src/database/migration/` |

**Tabellen (Auszug):**

| Tabelle | Zweck | Beleg |
|---|---|---|
| `session_v2` | Session-Metadaten, Claim (`time_suspended`), `resume_attempts`, Kosten/Tokens | `session/sql.ts:22` |
| `session_message` | sichtbare History, `seq`, JSON-`data` | `session/sql.ts:79` |
| `session_pending` | noch nicht promotete Eingaben | `session/sql.ts:100` |
| `session_inbox` | unkonsumierte Arbeit (Steer/Queue/Control) | `session/sql.ts:125` |
| `instruction_entry` | Instruction-Einträge pro Session | `session/sql.ts:147` |
| `instruction_blob` | content-addressed Instruction-Blobs | `session/sql.ts:162` |
| `instruction_state` | Epoch/Basis pro Session | `session/sql.ts:167` |
| `event_sequence` | Sequenz pro Aggregate (`aggregate_id`, `seq`, `owner_id`) | `event/sql.ts:4` |
| `event` | durables Event-Log, JSON-`data` | `event/sql.ts:10` |
| `kv` | generischer Key-Value-Store | `kv/sql.ts:5` |
| `credential` | Credentials (JSON-`value`, **unverschlüsselt**) | `credential/sql.ts:5` |
| `permission` | gespeicherte Approvals (project/action/resource) | `permission/sql.ts:7` |
| `account` | Account-Tokens (Access/Refresh, **unverschlüsselt**) | `account/sql.ts:5` |
| `project`, `workspace`, `worktree` | Projekt-/Workspace-Metadaten | `project/sql.ts:12`, `workspace/sql.ts:5`, `worktree/sql.ts:6` |

**Nicht in der DB:**

| Datentyp | Original-OC | Beleg |
|---|---|---|
| **Tool-Outputs** | Dateisystem: `$DATA/tool-output/`, Retention 7 Tage, Inline-Limit 2.000 Zeilen / 50 KiB | `tool-output.ts:13-16`, `:57` |
| **Snapshots** | Git-Objekte (Tree IDs, temporärer Index, Pack-Dateien) | `git.ts:45-51` |
| **Config/Agents/Skills** | Dateien (`opencode.json`, `AGENTS.md`, Skills) | – |

**Wichtige Erkenntnisse aus OC:**

1. **`event_sequence` statt `history().size()`**: OC führt pro Aggregat eine
   Sequenzzeile und aktualisiert sie mit `max(seq, ?)` – kein Full-Scan.
   (`packages/core/src/bus.ts:414-427`)
2. **Events sind die Quelle, Projektionen das Read-Model**: Durable Events
   werden minimal gehalten; Projektoren erzeugen `session_message` &
   Co. (`bus.ts:400-440`, AGENTS.md „Session Core").
3. **Große Daten gehören nicht in die DB**: Tool-Outputs aufs FS, Snapshots
   in Git.
4. **Claims sind ein DB-Feld**: `session_v2.time_suspended` mit
   Null-Guard; Release setzt `resume_attempts` zurück
   (`session/store.ts:208-231`).
5. **Credentials sind unverschlüsselt** in der DB (nur Dateirechte 0600) –
   ein Sicherheits-/Härtungspunkt, den QOC nicht kopieren sollte.

### 3.2 Port-Vergleich im Überblick

| QOC-Port | QOC aktuell | Original-OC | OC-Storage |
|---|---|---|---|
| `ClaimStore` | `SqlClaims` / `SqliteClaims` | `SessionStore.claim/release/countResume` | SQLite `session_v2.time_suspended` |
| `Journal` | `SqlJournal` / `SqliteJournal` | `bus.ts` + `EventTable`/`EventSequenceTable` | SQLite (WAL) |
| `ConversationStore` | `SqlConversation` / `SqliteConversation` | `SessionMessageTable` | SQLite |
| `SessionStore`/`SessionReader` | `SqlSessions` / `SqliteSessions` | `SessionTable`, `SessionPendingTable`, `SessionInboxTable` | SQLite |
| `ApprovalStore` | `SqlApprovals` / `SqliteApprovals` | `PermissionTable` | SQLite |
| `InstructionStore` | `SqlInstructions` / `SqliteInstructions` | `InstructionEntry/Blob/StateTable` | SQLite |
| `SessionWorkspaceStore` | (SQL/SQLite) | `workspace`, `worktree`, `session_v2.workspace_id` | SQLite |
| `CredentialStore` | `SqlCredentials` / `SqliteCredentials` | `CredentialTable` | SQLite (JSON, unverschlüsselt) |
| `SnapshotStore` | `SqlSnapshots` / `SqliteSnapshots` – **Blobs in DB** (`snapshot_file.content`) | Git-Snapshots | Git-Objekte auf FS |
| `ToolOutputStore` | `SqlToolOutput` / `SqliteToolOutput` – **BLOBs in DB** (`tool_output.content`) | `tool-output.ts` | Dateisystem |
| `ToolResultCache` | `SqlToolResultCache` / `SqliteToolResultCache` | **kein eigener Port** | – |
| `Clock`, `SessionWake` | System / Coordinator | Prozess-lokal | – |

> **QOC-Besonderheit:** Es existieren bereits vollständige **SQLite-Adapter**
> für alle Store-Ports (`engine/adapter/sqlite/`), umschaltbar über
> `engine.db.kind=sqlite`. SQLite wird mit WAL, `busy_timeout=5000` und
> `foreign_keys=ON` betrieben (`adapter/sqlite/Sqlite.java:138-140`,
> `DatabaseMigration.java:44-45`).

## 4. Ports nach Resilienz-Priorität

### P0 – kritisch (vor Side-Effect durable)

| Port | Begründung |
|---|---|
| `ClaimStore` | Verhindert parallele Executions; Recovery nach Crash. Falsch ⇒ doppelte Side-Effects oder verlorene Sessions. |
| `Journal` | Write-Ahead-Log für `ExecutionStarted`, `ToolCalled`, `ToolSuccess`, Terminal-Events (G-03/G-04). |
| `ConversationStore` | Step-übergreifende History; ohne sie kein Resume. |
| `SessionStore`/`SessionReader` | Session-Identität, Agent, Location, Inbox. |

### P1 – wichtig (muss Neustart überleben)

| Port | Begründung |
|---|---|
| `ApprovalStore` | „Permanent allow" muss Neustart überleben. |
| `InstructionStore` | Instruction-Basis/Epoch für Reproduzierbarkeit. |
| `SessionWorkspaceStore` | Workspace-/Instance-Zuordnung, Orphan-Erkennung. |
| `CredentialStore` | Secrets; Sicherheit statt Performance. |

### P2 – unkritisch (ableitbar/ersetzbar)

| Port | Begründung |
|---|---|
| `SnapshotStore` | Datei-Snapshots; können auf FS/Objektstorage. |
| `ToolOutputStore` | Große Blobs; FS/Objektstorage. |
| `ToolResultCache` | Reiner Cache; In-Memory reicht. |

## 5. Alternativen je Port

### 5.1 `ClaimStore`

| Aspekt | Wert |
|---|---|
| Zugriff | 1× `acquire` + 1× `release` pro Execution; `open()` beim Start |
| Anforderung | atomar, Unique-Constraint, Lease/TTL, multi-node-fähig |

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **SQLite** (WAL) | ✅✅ | QOC-Adapter vorhanden; single-node; transaktional |
| **PostgreSQL** (aktuell) | ✅ | multi-node; Netzwerk-Latenz |
| **Redis** `SET NX PX` + AOF | ✅ | multi-node; AOF `everysec` kann 1 s verlieren |
| **Datei-Lock** (atomares `create`/`rename` + `fsync`) | ✅ | single-node; null DB |
| **etcd/Consul** Lease + CAS | ✅ | multi-node, stark konsistent; Betriebsaufwand |

**Empfehlung:** Single-Node: SQLite oder Datei-Lock. Multi-Node: Redis/etcd.
Claim **vor** `ExecutionStarted` durable schreiben.

### 5.2 `Journal`

| Aspekt | Wert |
|---|---|
| Zugriff | Append pro Event (teils Batches); `history()` liest Historie |
| Anforderung | append-only, monotonische Sequenz, `fsync`-Politik, Retention |

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **SQLite** (WAL) | ✅✅ | QOC-Adapter vorhanden; `event` + `event_sequence` wie OC |
| **PostgreSQL** (aktuell) | ✅ | multi-node; teuer bei Full-Scans |
| **Append-only JSONL/NDJSON** + Rotation + `fsync` | ✅✅ | sehr günstig, ideal single-node; Replay in Memory |
| **Kafka / NATS JetStream** | ✅ | multi-node, geordnet pro Partition |
| **S3/GCS** | ✅ | kaltes Archiv; nicht Hot-Path |

**Hotspot in QOC:** `journal.history(id, Seq.zero()).size()` wird in
`Engine.java` **8×** pro Prompt aufgerufen. Jeder Aufruf lädt die komplette
Event-Historie. OC löst das über `event_sequence` (`bus.ts:414-427`).

**Empfehlung:** `Journal.lastSeq(session)`/`nextSeq(session)` ergänzen
(oder `event_sequence`-Tabelle wie OC). `history()` nur für Replay/Projektion.

### 5.3 `ConversationStore`

| Aspekt | Wert |
|---|---|
| Zugriff | `nextSeq` (`max(seq)+1`) pro Message; `insert` pro Message; `messages()` pro Step |
| Anforderung | Reihenfolge, Parent-Links, `deleteFrom` (Revert/Fork) |

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **SQLite** (WAL) | ✅✅ | QOC-Adapter vorhanden; OC nutzt genau das |
| **PostgreSQL** (aktuell) | ✅ | multi-node |
| **Append-Log + Snapshot** | ✅ | minimal; Rebuild aus Journal |
| **Cassandra/DynamoDB** (Partition = Session) | ✅ | multi-node, hohe Schreiblast |
| **Object Store** (kalte History) | ✅ | günstig; keine schnellen Range-Reads |

**Empfehlung:** Journal als Quelle, Conversation als **Projektion**
materialisieren. `nextSeq` aus `Journal.lastSeq` ableiten (wie OC
`event_sequence`), statt `max(seq)` pro Insert zu scannen.

### 5.4 `SessionStore` / `SessionReader`

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **SQLite** | ✅✅ | QOC-Adapter; single-node |
| **PostgreSQL** (aktuell) | ✅ | multi-node |
| **JSON-Datei + In-Memory-Index** | ✅ | sehr klein, single-node |
| **Redis Hash** | ✅ | schnell; Persistenz konfigurieren |

**Empfehlung:** Single-Node SQLite; Inbox könnte im Journal liegen
(Event-Sourcing), dann ist `SessionStore` nur Read-Model.

### 5.5 `ApprovalStore`

| Alternative | Eignung |
|---|---|
| **SQLite** | ✅✅ (QOC-Adapter) |
| **Append-only Datei** | ✅ |
| **Redis** | ✅ (Persistenz konfigurieren) |
| **PostgreSQL** (aktuell) | ✅ |

**Empfehlung:** SQLite/Datei; Volumen ist klein, Neustart-Persistenz wichtig.

### 5.6 `InstructionStore`

| Alternative | Eignung |
|---|---|
| **SQLite** | ✅✅ (QOC-Adapter; OC nutzt `instruction_*`) |
| **Dateisystem + In-Memory-Cache** | ✅✅ (content-addressed) |
| **Object Store** (versionierte Blobs) | ✅ |
| **PostgreSQL** (aktuell) | ⚠️ teuer für read-mostly |

**Empfehlung:** Blobs content-addressed aufs FS (`sha256`), State/Epoch im
Journal oder SQLite. Kein DB-Blob-Store nötig.

### 5.7 `SessionWorkspaceStore`

| Alternative | Eignung |
|---|---|
| **SQLite** | ✅✅ |
| **JSON-Datei pro Session** | ✅ |
| **Redis** | ✅ (ephemer) |

**Empfehlung:** SQLite/Datei; Daten sind klein und größtenteils ableitbar.

### 5.8 `CredentialStore`

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **Vault / AWS/GCP Secret Manager** | ✅✅ | beste Sicherheit |
| **OS-Keyring** | ✅ | lokal |
| **Verschlüsselte Datei** (age/GPG) | ✅ | einfach |
| **SQLite mit Verschlüsselung** | ✅ | z. B. SQLCipher |
| **PostgreSQL** (aktuell) | ⚠️ | Verschlüsselung prüfen |

> **OC-Vergleich:** OC speichert Credentials und Account-Tokens
> **unverschlüsselt** als JSON in SQLite (`credential/sql.ts:5`,
> `account/sql.ts:5`); geschützt nur durch `chmod 0600` der DB-Datei.
> Das ist ein Anti-Pattern, das QOC nicht übernehmen sollte.

### 5.9 `SnapshotStore`

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **Git-Objekte** (wie OC) | ✅✅ | `git.ts:45-51`; diffbar, packbar, platzsparend |
| **Content-addressed FS** (sha256) | ✅✅ | einfach, dedupliziert |
| **S3/GCS** | ✅ | Skalierung/Retention |
| **DB-BLOBs** (aktuell in QOC) | ❌ | bläht DB, teure Backups |

**Empfehlung:** Dateiinhalte aus der DB nehmen; Metadaten (Snapshot-ID,
Pfade, Hashes) in SQLite, Inhalte in Git/FS/Object Store. OC ist hier
Vorbild.

### 5.10 `ToolOutputStore`

| Alternative | Eignung | Bemerkung |
|---|---|---|
| **Dateisystem** (`$DATA/tool-output/`) | ✅✅ | OC-Vorbild: 7 Tage Retention, 2.000 Zeilen/50 KiB inline |
| **Content-addressed FS** | ✅✅ | Dedupe |
| **S3/GCS** | ✅ | Skalierung |
| **DB-BLOBs** (aktuell in QOC) | ❌ | vermeiden |

**Empfehlung:** Wie OC: große Outputs als Dateien, nur `ArtifactRef`
(Metadaten) in der DB.

### 5.11 `ToolResultCache`

| Alternative | Eignung |
|---|---|
| **In-Memory LRU** (Caffeine) | ✅✅ |
| **Disk-Cache** | ✅ |
| **Redis** | ✅ (multi-node) |
| **DB** (aktuell) | ⚠️ |

**Empfehlung:** In-Memory-LRU; bei Neustart darf der Cache leer sein.
OC hat dafür keinen eigenen Port – QOC kann ihn als reine Performance-Optimierung
behalten.

## 6. Zielarchitektur (Hybrid Storage)

| Datentyp | Empfohlener Storage | Priorität |
|---|---|---|
| Claims | SQLite/Datei-Lock (single-node) oder Redis/etcd (multi-node) | P0 |
| Journal/Events | SQLite WAL (wie OC) oder Append-Log (single-node); Kafka (multi-node) | P0 |
| Conversation | SQLite (Projektion aus Journal) | P0 |
| Session/Inbox | SQLite | P0 |
| Approvals | SQLite/Datei | P1 |
| Instructions | FS + Memory-Cache; State in SQLite/Journal | P1 |
| Workspace-Zuordnung | SQLite/Datei | P1 |
| Credentials | Secret Manager / verschlüsselte Datei | P1 |
| Snapshots | Git-Objekte oder content-addressed FS | P2 |
| Tool-Outputs | Dateisystem (Retention) oder Object Store | P2 |
| ToolResultCache | In-Memory LRU | P2 |

**Leitlinie:** Die DB bleibt für **kleine, konsistenzkritische Metadaten**.
Große Blobs und ableitbare Read-Models verlassen die DB.

## 7. Konkrete Maßnahmen für QOC

### Phase 1 – Sofort (ohne Architekturänderung)

1. **SQLite statt Neon/Postgres testen:** `engine.db.kind=sqlite`,
   eigener `ENGINE_DATASOURCE_URL=jdbc:sqlite:/data/qoc.db`.
   Die Adapter sind vorhanden; WAL ist aktiv. Ergebnis: keine
   Netzwerk-Roundtrips, geringere Latenz, weniger DB-Kosten.
2. **`history().size()` eliminieren:** `Journal.lastSeq(session)` bzw.
   `nextSeq(session)` als Port-Methode; die 8 Aufrufe in `Engine.java`
   ersetzen. Alternativ `event_sequence`-Tabelle wie OC.
3. **`Dialect`/SQL-Anpassungen prüfen:** Die SQLite-Adapter existieren,
   aber Migrationen, JSON-Funktionen und `max(seq)` müssen auf SQLite
   laufen (Flyway-Location `db/migration-sqlite`).

### Phase 2 – DB-Entlastung

4. **Snapshots aus der DB:** `snapshot_file.content` durch
   content-addressed FS oder Git ersetzen; DB nur Metadaten.
5. **Tool-Outputs aus der DB:** `tool_output.content` aufs FS
   (`$DATA/tool-output/`), Retention und Truncation wie OC.
6. **ToolResultCache in Memory:** Caffeine/LRU statt DB-Tabelle.

### Phase 3 – Event-Sourcing schärfen

7. **Journal als Single Source of Truth**, Conversation als Projektion.
   Ein Append pro Event statt Journal + Conversation + Session-Update.
8. **Projektionen asynchron** aktualisieren; Modell liest aus dem
   materialisierten Read-Model.
9. **Idempotenz:** `IdempotencyKey` für Tool-Side-Effects; At-least-once-
   Recovery bleibt, Tools müssen idempotent sein.

### Phase 4 – Multi-Node (optional)

10. Claims → Redis/etcd mit Lease; Journal → Kafka/NATS; Conversation →
    partitionierte DB. Erst dann verlässt man SQLite.

## 8. Risiken und Trade-offs

| Thema | Risiko | Gegenmaßnahme |
|---|---|---|
| SQLite single-writer | Schreibkonflikte bei vielen Sessions | WAL, `busy_timeout`, ein Writer pro DB; Sessions serialisieren |
| Backup | SQLite-Datei + WAL | Litestream/`sqlite3 .backup`, regelmäßige Snapshots |
| Crash-Sicherheit | `fsync`-Politik | Claim/Journal mit `synchronous=FULL` oder Group-Commit |
| Native Image | SQLite-JDBC/Treiber | Quarkus-Extension prüfen; ggf. JVM-Mode |
| Multi-Node | SQLite nicht teilbar | Phase 4: Redis/etcd/Kafka |
| Credentials | Klartext in DB (OC) | Secret Manager/Verschlüsselung |
| At-least-once | doppelte Side-Effects | Idempotenz + `ToolResultCache` |

## 9. Offene Fragen

1. Soll QOC dauerhaft **single-node** bleiben (SQLite) oder multi-node
   (Postgres/Redis) werden?
2. Ist die **Event-Sourcing-Projektion** bereits ausreichend, um
   `Conversation` komplett abzuleiten – oder bleibt sie ein eigenes
   Read-Model?
3. Wie lange sollen **Events/History** aufbewahrt werden (Retention)?
4. Sollen **Credentials** in einen externen Secret Manager oder in eine
   verschlüsselte lokale Datei?
5. Git-Snapshots (wie OC) oder content-addressed FS für QOC?

## 10. Quellen (OC-Referenz)

| Thema | Pfad:Zeile |
|---|---|
| SQLite-Öffnung | `oc/packages/core/src/database/sqlite.bun.ts:87` |
| WAL-Pragma | `oc/packages/core/src/database/sqlite.bun.ts:93` |
| DB-Pfad/Rechte | `oc/packages/core/src/database/database.ts:76-97` |
| XDG-Datenverzeichnis | `oc/packages/util/src/global-roots.ts:5` |
| DB-Dateiname | `oc/packages/cli/src/database-path.ts:10` |
| Session-Tabelle/Claim | `oc/packages/core/src/session/sql.ts:22`, `store.ts:208-231` |
| Message-Tabelle | `oc/packages/core/src/session/sql.ts:79` |
| Inbox/Pending | `oc/packages/core/src/session/sql.ts:100`, `:125` |
| Instruction-Tabellen | `oc/packages/core/src/session/sql.ts:147-172` |
| Event/EventSequence | `oc/packages/core/src/event/sql.ts:4`, `:10` |
| Event-Sequenz-Upsert | `oc/packages/core/src/bus.ts:414-427` |
| Event-Insert | `oc/packages/core/src/bus.ts:429-440` |
| Chat-History-Lesen | `oc/packages/core/src/session/history.ts:113-121` |
| Tool-Output-Limits | `oc/packages/core/src/tool-output.ts:13-16` |
| Tool-Output-Verzeichnis | `oc/packages/core/src/tool-output.ts:57` |
| Snapshot-Git-Mechanik | `oc/packages/core/src/git.ts:45-51` |
| Credential-Tabelle | `oc/packages/core/src/credential/sql.ts:5` |
| Account-Tokens | `oc/packages/core/src/account/sql.ts:5` |
| Permission-Tabelle | `oc/packages/core/src/permission/sql.ts:7` |
| KV-Tabelle | `oc/packages/core/src/kv/sql.ts:5` |

> `oc/` wurde ausschließlich lesend analysiert und nicht verändert.
