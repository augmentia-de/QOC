# 09 – Persistenz, Event-Log und Transaktionen

## Zweck

QOC speichert Sessions, Nachrichten, Inbox, Claims, Instructions und Snapshots
in SQLite oder PostgreSQL. Das Event-Log ist append-only und die Grundlage für
Replay, Projektionen und Audit. Dieses Diagramm zeigt die Tabellen und die
wichtigsten Transaktionsgrenzen.

```mermaid
erDiagram
  SESSION ||--o{ SESSION_MESSAGE : "contains"
  SESSION ||--o{ SESSION_INBOX : "queues"
  SESSION ||--o| SESSION_CLAIM : "claimed_by"
  SESSION ||--o| RESUME_COUNTER : "counts"
  SESSION ||--o| INSTRUCTION_STATE : "has"
  SESSION ||--o{ INSTRUCTION_ENTRY : "has"
  SNAPSHOT ||--o{ SNAPSHOT_FILE : "contains"

  EVENT {
    string aggregate_id
    int seq
    string type
    int version
    string data
    timestamp created_at
  }

  EVENT_SEQUENCE {
    string aggregate_id
    int seq
  }

  SESSION {
    string id PK
    string project_id
    string directory
    string title
    string agent_id
    string model
    string permissions
    float cost
    string tokens
    timestamp time_created
    timestamp time_updated
    timestamp time_idle
    timestamp time_suspended
    int resume_attempts
    timestamp time_archived
  }

  SESSION_MESSAGE {
    string id PK
    string session_id FK
    int seq
    string type
    string data
    timestamp created_at
  }

  SESSION_INBOX {
    string id PK
    string session_id FK
    string type
    string payload
    string delivery
    int enqueued_seq
    timestamp created_at
  }

  SESSION_CLAIM {
    string session_id PK
    timestamp claimed_at
    int resume_attempts
  }

  RESUME_COUNTER {
    string session_id PK
    int attempts
  }

  SAVED_APPROVAL {
    string id PK
    string project_id
    string action
    string resource
  }

  CREDENTIAL {
    string id PK
    string integration
    string kind
    string secret
    boolean active
    timestamp created_at
  }

  INSTRUCTION_STATE {
    string session_id PK
    int epoch_start
    int through_seq
    string initial_values
    string current_values
  }

  INSTRUCTION_ENTRY {
    string session_id FK
    string key
    string value
    string hash
  }

  INSTRUCTION_BLOB {
    string hash PK
    string value
  }

  TOOL_OUTPUT {
    string path PK
    string content
    timestamp created_at
  }

  SNAPSHOT {
    string id PK
    timestamp created_at
  }

  SNAPSHOT_FILE {
    string snapshot_id FK
    string path
    string content
  }
```

> `EVENT.aggregate_id` ist bewusst **kein** Fremdschlüssel: Das Event-Log ist
> ein eigenständiger Strom pro Aggregat. `event_sequence` hält die nächste
> Sequenz. `SAVED_APPROVAL`, `CREDENTIAL`, `TOOL_OUTPUT` und `SNAPSHOT` sind
> unterstützende Stores ohne harte Session-FK.

## Event-Log vs. Projektionen

```mermaid
flowchart LR
  Command["Command / Prompt"]:::det --> Append["Journal.append(draft)"]:::det
  Append --> Tx["Transaktion:<br/>Sequenz + Event"]:::det
  Tx --> Event[("event")]:::store
  Tx --> Seq[("event_sequence")]:::store
  Event --> Replay["Replay / SSE"]:::det
  Event --> Projection["Projektionen / Reads"]:::det
  Projection --> Sessions[("session / session_message")]:::store
  Projection --> Inbox[("session_inbox")]:::store

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef store fill:#e3f2fd,stroke:#1565c0,stroke-width:1px,color:#111;
```

## Transaktionsgrenzen (Ist-Stand)

| Operation | Transaktion? | Hinweis |
|---|---|---|
| `Journal.append` | ja | Sequenz und Event gemeinsam; `appendAll` ebenfalls |
| `SessionStore.put` | ja | Session-Projektion |
| `SessionStore.putInbox` | ja | Inbox-Zeile |
| `SessionStore.removeInbox` | ja | eigene Transaktion |
| `Delivery.deliver` | nein, über zwei Stores | `InboxDelivered` und `removeInbox` sind getrennte Aufrufe |
| `SessionStore.delete` | ja | löscht abhängige Projektionen |
| `SessionRetention.delete` | nein, über zwei Stores | erst Session, dann Event-Aggregat |
| `Snapshot.capture/restore` | adapterabhängig | SQL-Snapshots speichern Dateien in `snapshot_file` |
| `Revert` | über Journal + Snapshot + Instructions | Event bleibt append-only; Revert wird als Event gespeichert, Snapshot wird wiederhergestellt, Instructions werden entfernt |

## Deterministische Persistenzregeln

1. **Sequenz ist lückenlos und monoton** pro Aggregat.
2. **Event + Projektion** sollen in einer Transaktion geschrieben werden, wenn
   sie zusammengehören.
3. **Event-Log wird nie umgeschrieben**; Compaction ist eine Sicht, kein
   `DELETE`.
4. **Claims überleben den Prozess**; Shutdown gibt sie nicht frei.
5. **Retention** löscht Session und Event-Aggregat; laufende Sessions werden
   übersprungen.
6. **SQL nur im SQL-Adapter** (`adapter.sql` / `adapter.sqlite`).

## SQLite vs. PostgreSQL

| Eigenschaft | SQLite | PostgreSQL |
|---|---|---|
| Default | ja | nein |
| Mehrere Worker/Container | nein | ja |
| WAL | `Sqlite.enableWal` | – |
| Migrationen | `db/migration-sqlite` | `db/migration` |
| Geeignet für | lokale Instanz, GCP-Single-Node | verteilte Orchestrator-/Worker-Architektur |

## Quellen

- `qoc/engine/src/main/resources/db/migration/V1__engine.sql`
- `qoc/engine/src/main/resources/db/migration-sqlite/V1__engine.sql`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/adapter/sqlite/`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/adapter/sql/`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/session/core/SessionRetention.java`
- `qoc/engine/src/main/java/de/augmentia/oc/engine/runtime/session/delivery/Delivery.java`
