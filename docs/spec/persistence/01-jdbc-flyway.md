# 01 – JDBC, PostgreSQL, and Migrations

> The concrete implementation of the data model from
> [`README.md`](./README.md) in Java 21 on PostgreSQL.
> **Status:** 2026-10-22

## 1. DDL (PostgreSQL 16)

```sql
-- ═══ Protocol ═══════════════════════════════════════════════════════

create table event_sequence (
  aggregate_id  text        primary key,
  seq           bigint      not null,
  owner_id      text
);

create table event (
  id            uuid        primary key,
  aggregate_id  text        not null references event_sequence (aggregate_id) on delete cascade,
  seq           bigint      not null,
  type          text        not null,
  version       int         not null,
  data          jsonb       not null,
  created       timestamptz not null default now(),
  constraint event_aggregate_seq unique (aggregate_id, seq)
);
create index event_aggregate_type_seq_idx on event (aggregate_id, type, seq);

-- ═══ Projects ════════════════════════════════════════════════════════

create table project (
  id               text        primary key,
  worktree         text        not null,
  vcs              text,
  name             text,
  time_created     timestamptz not null default now(),
  time_updated     timestamptz not null default now(),
  time_initialized timestamptz,
  time_active      timestamptz not null default now(),
  time_archived    timestamptz,
  sandboxes        text[]      not null default '{}',
  commands         jsonb
);

create table worktree (
  project_id  text        not null references project (id) on delete cascade,
  directory   text        not null,
  strategy    text        not null,
  primary key (project_id, directory)
);

-- ═══ Session ═════════════════════════════════════════════════════════

create table session (
  id               text        primary key,
  project_id       text        not null references project (id) on delete cascade,
  workspace_id     text,
  parent_id        text,
  fork_session_id  text,
  fork_boundary    jsonb,
  slug             text        not null,
  directory        text        not null,
  path             text,
  title            text,
  version          text        not null,
  share_url        text,
  summary_additions int,
  summary_deletions int,
  summary_files    int,
  summary_diffs    jsonb,
  metadata         jsonb,
  cost             numeric(14,6) not null default 0,
  tokens_input      int         not null default 0,
  tokens_output     int         not null default 0,
  tokens_reasoning  int         not null default 0,
  tokens_cache_read int         not null default 0,
  tokens_cache_write int        not null default 0,
  revert           jsonb,
  permission       jsonb       not null default '[]',
  agent            text,
  model            jsonb,
  time_created     timestamptz not null default now(),
  time_updated     timestamptz not null default now(),
  time_idle        timestamptz,
  time_viewed      timestamptz,
  idle_outcome     text,
  time_archived    timestamptz,
  resume_attempts  int         not null default 0
);
create index session_project_idx  on session (project_id);
create index session_workspace_idx on session (workspace_id);
create index session_parent_idx  on session (parent_id);

-- Write-Ahead-Claim: separate from the session record (decision D-2)
create table session_claim (
  session_id      text        primary key references session (id) on delete cascade,
  claimed_at      timestamptz not null default now(),
  resume_attempts int         not null default 0
);

create table session_message (
  id           text        primary key,
  session_id   text        not null references session (id) on delete cascade,
  type         text        not null,
  seq          bigint      not null,
  data         jsonb       not null,
  time_created timestamptz not null default now(),
  time_updated timestamptz not null default now(),
  constraint session_message_session_seq unique (session_id, seq)
);
create index session_message_type_seq_idx  on session_message (session_id, type, seq);
create index session_message_time_created_idx on session_message (session_id, time_created, id);

create table session_inbox (
  id           text        primary key,
  session_id   text        not null references session (id) on delete cascade,
  type         text        not null,
  payload      jsonb       not null,
  delivery     text        not null check (delivery in ('steer','queue')),
  enqueued_seq bigint      not null,
  time_created timestamptz not null default now(),
  constraint session_inbox_session_enqueued_seq unique (session_id, enqueued_seq)
);
create index session_inbox_delivery_seq_idx on session_inbox (session_id, delivery, enqueued_seq);

-- ═══ Instructions ════════════════════════════════════════════════════

create table instruction_blob (
  hash  text  primary key check (hash ~ '^[a-f0-9]{64}$'),
  value jsonb not null
);

create table instruction_entry (
  session_id   text        not null references session (id) on delete cascade,
  key          text        not null,
  value        jsonb,
  removed      boolean     not null default false,
  time_created timestamptz not null default now(),
  time_updated timestamptz not null default now(),
  primary key (session_id, key)
);

create table instruction_state (
  session_id     text   primary key references session (id) on delete cascade,
  epoch_start    bigint not null,
  through_seq    bigint not null,
  initial_values jsonb  not null,
  current_values jsonb  not null
);

-- ═══ Rights ══════════════════════════════════════════════════════════

create table permission (
  id           text        primary key,
  project_id   text        not null references project (id) on delete cascade,
  action       text        not null,
  resource     text        not null,
  time_created timestamptz not null default now(),
  time_updated timestamptz not null default now(),
  constraint permission_project_action_resource unique (project_id, action, resource)
);
```

### Conscious decisions

| # | Decision | Rationale |
|---|---|---|
| 1 | `cost numeric(14,6)`, **not** `double precision` | Money: `double` is binary and causes rounding drift when summed |
| 2 | `time_*` as `timestamptz`, **not** `bigint` | Time zones, comparison with `now()`, SQL functions (`date_trunc`) |
| 3 | `jsonb`, **not** `text` | Partial queries, smaller storage, `jsonb_path_query` for diagnostics |
| 4 | `session_claim` as its **own table** | The recovery sweep reads only claims; `session` stays untouched |
| 5 | `resume_attempts` duplicated (in `session` **and** in `session_claim`) | The counters represent different states: "attempts for this session" vs. "attempts for this claim". The session counter is taken over when a claim is created. |
| 6 | **no** `session_pending`, `project_directory`, `account*` | No writers (see [`README.md`](./README.md) §8) |
| 7 | `instruction_blob.hash` with `CHECK` | SHA-256 hex is enforced, not assumed |
| 8 | `event.id` as `uuid` | Sortable with `uuid v7`; `bigserial` does not fit aggregates |

## 2. Sequence allocation – atomic without a lock

The critical point: two concurrent `publish` calls for the **same aggregate** must
not create a sequence gap or a duplicate sequence.

```java
/** Atomic: returns the next sequence for the aggregate. */
private long nextSeq(Connection c, String aggregateId) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement("""
            insert into event_sequence (aggregate_id, seq) values (?, 1)
            on conflict (aggregate_id) do update set seq = event_sequence.seq + 1
            returning seq
            """)) {
        ps.setString(1, aggregateId);
        try (ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
```

> **Why this works:** `ON CONFLICT DO UPDATE … RETURNING` is atomic in
> PostgreSQL and **serializes** the two writers on the same row (row lock only
> for the duration of the statement). **No** `SELECT … FOR UPDATE` is needed and
> **no** retry.
>
> **Isolation level:** `READ COMMITTED` is sufficient. The row lock of the
> `UPSERT` statement provides the serialization.

### Why the aggregate needs a row in `event_sequence`

`event.aggregate_id` references `event_sequence` (cascade). The row is created
on the first `UPSERT` – that is why the **event INSERT must come after** the
`UPSERT`. Order within the transaction:

```
1. seq ← nextSeq()                      ← creates event_sequence if needed
2. INSERT event (id, aggregate_id, seq, type, version, data, created)
3. for each projection: p.apply(c, event, seq)
4. Commit hook (e.g. set claim)
5. COMMIT
```

## 3. JDBC access patterns

### 3.1 Reading (no transaction)

```java
public List<SessionMessage> messages(String sessionId, long afterSeq) throws SQLException {
    try (Connection c = dataSource.getConnection();
         PreparedStatement ps = c.prepareStatement("""
                 select id, type, seq, data, time_created
                   from session_message
                  where session_id = ? and seq > ?
                  order by seq
                 """)) {
        ps.setString(1, sessionId);
        ps.setLong(2, afterSeq);
        try (ResultSet rs = ps.executeQuery()) {
            List<SessionMessage> out = new ArrayList<>();
            while (rs.next()) out.add(map(rs));
            return out;
        }
    }
}
```

> **No `setAutoCommit(false)` for pure reads** – every PostgreSQL transaction
> carries overhead. Reading happens in `READ COMMITTED` (default), so it sees the
> state as of statement time.

### 3.2 Writing (transaction)

```java
public void publish(SessionEvent event) {
    try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
            long seq = nextSeq(c, aggregateOf(event));
            insertEvent(c, event, seq);
            for (Projection p : projections) p.apply(c, event, seq);
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw new StoreFailure("publish failed", e);   // do NOT swallow
        } finally {
            c.setAutoCommit(true);      // return connection
        }
    } catch (SQLException e) {
        throw new StoreFailure("publish failed", e);
    }
}
```

> **The error must not leave the method without being thrown.** In the reference
> checkout a failed `recordEvent` is **skipped** via `log.warnf` – silent data
> loss. That is exactly the trap the new implementation must not fall into.

### 3.3 The transaction checkout

```java
public interface Tx<T> {
    T apply(Connection c) throws SQLException;
}

public <T> T inTransaction(Tx<T> body) {
    try (Connection c = dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
            T result = body.apply(c);
            c.commit();
            return result;
        } catch (SQLException e) {
            c.rollback();
            throw new StoreFailure("transaction failed", e);
        }
    } catch (SQLException e) {
        throw new StoreFailure("transaction failed", e);
    }
}
```

> **One transaction per operation.** No nested `inTransaction` – PostgreSQL has
> no nested transactions; a second `setAutoCommit(false)` on the same connection
> is silently ineffective. If an operation needs several transactions, they are
> **two separate operations** (e.g. "write event" and "run tool").

## 4. Projections

```java
public interface Projection {
    void apply(Connection c, SessionEvent event, long seq) throws SQLException;
}
```

**Rules for every projection:**

1. **SQL only.** No HTTP, no cache, no logger metric, no thread switch.
2. **Idempotent with respect to `seq`.** A projection that runs twice on the
   same sequence must not cause damage.
3. **No `catch`** that swallows the error. A projection error aborts the
   **entire** transaction – that is intended.

**Two examples:**

```java
// Projection A: domain tables
public final class TableProjection implements Projection {
    @Override public void apply(Connection c, SessionEvent e, long seq) throws SQLException {
        switch (e) {
            case SessionEvent.InboxEnqueued ev ->
                insert(c, "insert into session_inbox (id, session_id, type, payload, delivery, enqueued_seq) values (?,?,?,?::jsonb,?,?)",
                       ev.inboxId(), ev.sessionId(), ev.item().type(), json(ev.item()), ev.item().delivery(), seq);
            case SessionEvent.InboxDelivered ev ->
                inTx(c, "delete from session_inbox where id = ?", ev.inboxId());
            default -> { /* not affected by this layer */ }
        }
    }
}

// Projection B: conversation folding
public final class ConversationProjection implements Projection {
    @Override public void apply(Connection c, SessionEvent e, long seq) throws SQLException {
        switch (e) {
            case SessionEvent.ToolCalled ev ->
                update(c, "update session_message set data = jsonb_set(data, '{content}', ?::jsonb) where id = ?",
                       json(cardRunning(ev)), ev.assistantMessageId());
            default -> { }
        }
    }
}
```

> **The message table is not written directly, but folded.** The folder reads the
> current state of the message, applies the change, and writes it back. That is
> why the message contains the tool state and why the event sequence remains the
> truth.

## 5. Migrations (Flyway)

### 5.1 Naming convention

```
V1__create_event_log.sql
V2__create_project.sql
V3__create_session.sql
V4__create_session_claim.sql
V5__create_session_message.sql
V6__create_session_inbox.sql
V7__create_instructions.sql
V8__create_permission.sql
V9__create_worktree.sql
```

### 5.2 Rules

| # | Rule | Why |
|---|---|---|
| 1 | **Never** modify an applied migration | Flyway checks a checksum; the change fails startup |
| 2 | Every change is **one** new file | reversible history |
| 3 | Every migration is thinkable as **forward** and **idempotent** (`if not exists`, `on conflict`) | a retry must not fail |
| 4 | Data reshaping and schema change in **one** file | otherwise an intermediate state is inconsistent |
| 5 | No `drop table` without a backup note | production data |
| 6 | Index creation with `create index concurrently` | does not block the table (only outside a transaction → **its own** migration) |

### 5.3 Example: claim from session column into claim table

```sql
-- V10__move_claim_to_own_table.sql
insert into session_claim (session_id, claimed_at, resume_attempts)
select id, time_suspended, resume_attempts
  from session
 where time_suspended is not null;

alter table session drop column time_suspended;
```

> **Order:** copy first, then delete. If the column is dropped first, the open
> claims are lost – and with them the resumption.

## 6. Operation

### 6.1 Connection pool

In Quarkus: `quarkus-jdbc-postgresql` + Agroal (default pool). Recommendation:

```properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.max-size=32
quarkus.datasource.jdbc.acquisition-timeout=5S
quarkus.datasource.jdbc.transaction-isolation-level=read-committed
```

> **32 connections** fit the architecture: a session needs at most **one**
> writing connection during a step (the `publish` transaction). Tool calls run in
> virtual threads **without** a database connection – that is exactly why a
> transaction must not span a thread boundary.

### 6.2 Indexes and their origin

| Index | Rationale | Kind |
|---|---|---|
| `event_aggregate_seq` (unique) | Replay | btree |
| `event_aggregate_type_seq` | Diagnostics "all tool calls" | btree |
| `session_message_session_seq` (unique) | History | btree |
| `session_message_type_seq` | History by type | btree |
| `session_inbox_delivery_seq` | "which steers are waiting?" | btree |
| `permission_project_action_resource` (unique) | Approval uniqueness | btree |
| `session_claim` (PK) | Recovery sweep | btree |

**Deliberately no index on `session.project_id` without `partial`:** In the
reference checkout `session_v2_time_suspended_idx` exists as a **partial**
index (`WHERE time_suspended IS NOT NULL`). In the new structure `session_claim`
carries the claims – the sweep reads the table completely anyway, so **no**
additional index is needed.

### 6.3 JSONB indexes (only when needed)

```sql
-- only if a query is frequent
create index session_message_agent_idx on session_message ((data ->> 'agent'))
    where type = 'assistant';
```

> **Rule of thumb:** Measure first. JSONB indexes cost write time and storage.
> The planned evaluations (cost per session, open inputs, history) all come from
> the btree indexes.

### 6.4 Growth

| Table | Rows per session (typical) | Note |
|---|---|---|
| `event` | 30–150 | ~6 per step, ~3 per tool call |
| `session_message` | 10–60 | one row per message |
| `session_inbox` | 0–3 | only while waiting |
| `instruction_entry` | 1–20 | one row per source |
| `instruction_blob` | **shared** across sessions | grows with the number of distinct files |
| `permission` | 0–50 | per project |

> **The `event` table dominates.** It is the only one that grows without bound.
> That is why **monthly partitioning** (decision D-10) is the only partitioning
> worth it:
>
> ```sql
> create table event (...) partition by range (created);
> create table event_2026_10 partition of event
>   for values from ('2026-10-01') to ('2026-11-01');
> ```
>
> This turns retention into a `DROP TABLE` instead of a `DELETE` – and the
> indexes become smaller per partition.

### 6.5 Backup

| Point | Recommendation |
|---|---|
| `pg_dump` | daily, with `--format=custom` |
| WAL / PITR | `archive_mode = on` → restore to any point in time |
| **Snapshots** | **not** in the database → **must** be backed up separately |
| **Tool outputs** | likewise (7 days, usually not critical) |
| Restore test | **semi-annually** – an untested backup is no backup |

> **The most important sentence for operations:** The database contains the
> **history**, but not the **undo state**. A restore from the database
> re-establishes a running session – but no file changes already reverted.

## 7. Implementation checklist

- [ ] No table without a writer
- [ ] Every migration is idempotent via `if not exists` / `on conflict`
- [ ] `nextSeq` uses the atomic `UPSERT … RETURNING`
- [ ] `publish` throws on errors – **no** `log.warn` and continue
- [ ] Projections contain **only** SQL
- [ ] The claim lives in `session_claim`, not in `session`
- [ ] `event.version` exists, and upgrading lives in **one** place
- [ ] `cost` is `numeric`, not `double precision`
- [ ] Tool calls run **without** an open transaction
- [ ] `DROP PARTITION` is the retention path for `event`
- [ ] Snapshots and tool outputs are backed up **separately**