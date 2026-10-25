# 09 – Persistence, Event Log, and Transactions

## Purpose

QOC stores sessions, messages, inbox, claims, instructions, and snapshots
in SQLite or PostgreSQL. The event log is append-only and the foundation for
replay, projections, and audit. This diagram shows the tables and the
most important transaction boundaries.

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

> `EVENT.aggregate_id` is deliberately **not** a foreign key: the event log is
> an independent stream per aggregate. `event_sequence` holds the next
> sequence. `SAVED_APPROVAL`, `CREDENTIAL`, `TOOL_OUTPUT`, and `SNAPSHOT` are
> supporting stores without a hard session FK.

## Event log vs. projections

```mermaid
flowchart LR
  Command["Command / Prompt"]:::det --> Append["Journal.append(draft)"]:::det
  Append --> Tx["Transaction:<br/>Sequence + Event"]:::det
  Tx --> Event[("event")]:::store
  Tx --> Seq[("event_sequence")]:::store
  Event --> Replay["Replay / SSE"]:::det
  Event --> Projection["Projections / Reads"]:::det
  Projection --> Sessions[("session / session_message")]:::store
  Projection --> Inbox[("session_inbox")]:::store

  classDef det fill:#d9f7d9,stroke:#2e7d32,stroke-width:1px,color:#111;
  classDef store fill:#e3f2fd,stroke:#1565c0,stroke-width:1px,color:#111;
```

## Transaction boundaries (current state)

| Operation | Transaction? | Note |
|---|---|---|
| `Journal.append` | yes | Sequence and event together; `appendAll` as well |
| `SessionStore.put` | yes | Session projection |
| `SessionStore.putInbox` | yes | Inbox row |
| `SessionStore.removeInbox` | yes | own transaction |
| `Delivery.deliver` | no, across two stores | `InboxDelivered` and `removeInbox` are separate calls |
| `SessionStore.delete` | yes | deletes dependent projections |
| `SessionRetention.delete` | no, across two stores | first session, then event aggregate |
| `Snapshot.capture/restore` | adapter-dependent | SQL snapshots store files in `snapshot_file` |
| `Revert` | across Journal + Snapshot + Instructions | Event stays append-only; Revert is stored as an event, snapshot is restored, instructions are removed |

## Deterministic persistence rules

1. **Sequence is gapless and monotonic** per aggregate.
2. **Event + projection** should be written in one transaction when
   they belong together.
3. **Event log is never rewritten**; compaction is a view, not a
   `DELETE`.
4. **Claims survive the process**; shutdown does not release them.
5. **Retention** deletes session and event aggregate; running sessions are
   skipped.
6. **SQL only in the SQL adapter** (`adapter.sql` / `adapter.sqlite`).

## SQLite vs. PostgreSQL

| Property | SQLite | PostgreSQL |
|---|---|---|
| Default | yes | no |
| Multiple workers/containers | no | yes |
| WAL | `Sqlite.enableWal` | – |
| Migrations | `db/migration-sqlite` | `db/migration` |
| Suitable for | local instance, GCP single-node | distributed orchestrator/worker architecture |

## Sources

- `engine/src/main/resources/db/migration/V1__engine.sql`
- `engine/src/main/resources/db/migration-sqlite/V1__engine.sql`
- `engine/src/main/java/de/augmentia/oc/engine/adapter/sqlite/`
- `engine/src/main/java/de/augmentia/oc/engine/adapter/sql/`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/session/core/SessionRetention.java`
- `engine/src/main/java/de/augmentia/oc/engine/runtime/session/delivery/Delivery.java`
