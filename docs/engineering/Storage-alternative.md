# Storage Alternatives per Port

> **Purpose:** Evaluation of QOC storage ports for resilient execution,
> comparison with the original OpenCode implementation (`oc/`), and
> recommendations for DB reduction or alternative storage media.
>
> **Status:** 2026-11-09
>
> **Sources:** QOC `engine` module; OC reference checkout `oc/` (analyzed
> read-only). In case of contradictions, the QOC spec under
> [`spec/`](../spec/README.md) applies.

## 1. Questions

1. Which ports are most important for **resilient execution** (crash recovery,
   exactly one execution per session, no lost side-effects)?
2. How does the **original OC implementation** handle these ports?
3. Which **storage alternatives** exist per port – in particular for
   reducing database usage?

## 2. Evaluation Criteria

| Criterion | Question |
|---|---|
| **Durability** | Must state be durable before a side-effect? |
| **Frequency** | How often is it read/written (per step, per event)? |
| **Volume** | Small metadata or large blobs? |
| **Consistency** | Are transactions, unique constraints, atomic updates needed? |
| **Multi-Node** | Must the store be shared by multiple instances? |
| **Recoverability** | Can state be derived from other state? |

**Prioritization:**

- **P0** – must be durable before the side-effect (Claim, Journal, History,
  Session).
- **P1** – must survive a restart (Approvals, Instructions,
  Workspace assignment, Credentials).
- **P2** – may be lost or recomputed (Snapshots,
  Tool outputs, Caches).

## 3. QOC ↔ Original-OC Comparison

### 3.1 Storage Architecture of Original OC

The original OC uses **a single embedded SQLite database** with
WAL mode – no network DB:

| Aspect | Original OC | Reference |
|---|---|---|
| DB Engine | SQLite (`bun:sqlite`, `node:sqlite`, workerd) | `packages/core/src/database/sqlite.bun.ts:87` |
| WAL | `PRAGMA journal_mode = WAL` (Default) | `sqlite.bun.ts:93` |
| DB Path | `$XDG_DATA_HOME/opencode/opencode.db` (Default `~/.local/share/opencode/opencode.db`) | `packages/util/src/global-roots.ts:5`, `packages/cli/src/database-path.ts:10`, `database.ts:76` |
| File permissions | `chmod 0600` for DB, `-wal`, `-shm` | `database.ts:85-97` |
| Migrations | Drizzle migrations, numbered | `packages/core/src/database/migration/` |

**Tables (excerpt):**

| Table | Purpose | Reference |
|---|---|---|
| `session_v2` | Session metadata, claim (`time_suspended`), `resume_attempts`, cost/tokens | `session/sql.ts:22` |
| `session_message` | visible history, `seq`, JSON `data` | `session/sql.ts:79` |
| `session_pending` | not-yet-promoted inputs | `session/sql.ts:100` |
| `session_inbox` | unconsumed work (Steer/Queue/Control) | `session/sql.ts:125` |
| `instruction_entry` | Instruction entries per session | `session/sql.ts:147` |
| `instruction_blob` | content-addressed instruction blobs | `session/sql.ts:162` |
| `instruction_state` | epoch/basis per session | `session/sql.ts:167` |
| `event_sequence` | sequence per aggregate (`aggregate_id`, `seq`, `owner_id`) | `event/sql.ts:4` |
| `event` | durable event log, JSON `data` | `event/sql.ts:10` |
| `kv` | generic key-value store | `kv/sql.ts:5` |
| `credential` | credentials (JSON `value`, **unencrypted**) | `credential/sql.ts:5` |
| `permission` | stored approvals (project/action/resource) | `permission/sql.ts:7` |
| `account` | account tokens (access/refresh, **unencrypted**) | `account/sql.ts:5` |
| `project`, `workspace`, `worktree` | project/workspace metadata | `project/sql.ts:12`, `workspace/sql.ts:5`, `worktree/sql.ts:6` |

**Not in the DB:**

| Data type | Original OC | Reference |
|---|---|---|
| **Tool outputs** | Filesystem: `$DATA/tool-output/`, 7-day retention, inline limit 2,000 lines / 50 KiB | `tool-output.ts:13-16`, `:57` |
| **Snapshots** | Git objects (tree IDs, temporary index, pack files) | `git.ts:45-51` |
| **Config/Agents/Skills** | Files (`opencode.json`, `AGENTS.md`, skills) | – |

**Key findings from OC:**

1. **`event_sequence` instead of `history().size()`**: OC keeps a sequence
   row per aggregate and updates it with `max(seq, ?)` – no full scan.
   (`packages/core/src/bus.ts:414-427`)
2. **Events are the source, projections are the read model**: Durable events
   are kept minimal; projectors produce `session_message` &
   co. (`bus.ts:400-440`, AGENTS.md "Session Core").
3. **Large data does not belong in the DB**: Tool outputs on the FS, snapshots
   in Git.
4. **Claims are a DB field**: `session_v2.time_suspended` with
   null guard; release resets `resume_attempts`
   (`session/store.ts:208-231`).
5. **Credentials are unencrypted** in the DB (only file permissions 0600) –
   a security/hardening point QOC should not copy.

### 3.2 Port Comparison at a Glance

| QOC Port | QOC currently | Original OC | OC Storage |
|---|---|---|---|
| `ClaimStore` | `SqlClaims` / `SqliteClaims` | `SessionStore.claim/release/countResume` | SQLite `session_v2.time_suspended` |
| `Journal` | `SqlJournal` / `SqliteJournal` | `bus.ts` + `EventTable`/`EventSequenceTable` | SQLite (WAL) |
| `ConversationStore` | `SqlConversation` / `SqliteConversation` | `SessionMessageTable` | SQLite |
| `SessionStore`/`SessionReader` | `SqlSessions` / `SqliteSessions` | `SessionTable`, `SessionPendingTable`, `SessionInboxTable` | SQLite |
| `ApprovalStore` | `SqlApprovals` / `SqliteApprovals` | `PermissionTable` | SQLite |
| `InstructionStore` | `SqlInstructions` / `SqliteInstructions` | `InstructionEntry/Blob/StateTable` | SQLite |
| `SessionWorkspaceStore` | (SQL/SQLite) | `workspace`, `worktree`, `session_v2.workspace_id` | SQLite |
| `CredentialStore` | `SqlCredentials` / `SqliteCredentials` | `CredentialTable` | SQLite (JSON, unencrypted) |
| `SnapshotStore` | `SqlSnapshots` / `SqliteSnapshots` – **Blobs in DB** (`snapshot_file.content`) | Git snapshots | Git objects on FS |
| `ToolOutputStore` | `SqlToolOutput` / `SqliteToolOutput` – **BLOBs in DB** (`tool_output.content`) | `tool-output.ts` | Filesystem |
| `ToolResultCache` | `SqlToolResultCache` / `SqliteToolResultCache` | **no dedicated port** | – |
| `Clock`, `SessionWake` | System / Coordinator | Process-local | – |

> **QOC specialty:** There are already complete **SQLite adapters**
> for all store ports (`engine/adapter/sqlite/`), switchable via
> `engine.db.kind=sqlite`. SQLite runs with WAL, `busy_timeout=5000` and
> `foreign_keys=ON` (`adapter/sqlite/Sqlite.java:138-140`,
> `DatabaseMigration.java:44-45`).

## 4. Ports by Resilience Priority

### P0 – critical (must be durable before side-effect)

| Port | Rationale |
|---|---|
| `ClaimStore` | Prevents parallel executions; recovery after crash. Wrong ⇒ duplicate side-effects or lost sessions. |
| `Journal` | Write-ahead log for `ExecutionStarted`, `ToolCalled`, `ToolSuccess`, terminal events (G-03/G-04). |
| `ConversationStore` | History across steps; without it no resume. |
| `SessionStore`/`SessionReader` | Session identity, agent, location, inbox. |

### P1 – important (must survive restart)

| Port | Rationale |
|---|---|
| `ApprovalStore` | "Permanent allow" must survive restart. |
| `InstructionStore` | Instruction basis/epoch for reproducibility. |
| `SessionWorkspaceStore` | Workspace/instance assignment, orphan detection. |
| `CredentialStore` | Secrets; security over performance. |

### P2 – non-critical (derivable/replaceable)

| Port | Rationale |
|---|---|
| `SnapshotStore` | File snapshots; can live on FS/object storage. |
| `ToolOutputStore` | Large blobs; FS/object storage. |
| `ToolResultCache` | Pure cache; in-memory suffices. |

## 5. Alternatives per Port

### 5.1 `ClaimStore`

| Aspect | Value |
|---|---|
| Access | 1× `acquire` + 1× `release` per execution; `open()` at startup |
| Requirement | atomic, unique constraint, lease/TTL, multi-node capable |

| Alternative | Suitability | Note |
|---|---|---|
| **SQLite** (WAL) | ✅✅ | QOC adapter available; single-node; transactional |
| **PostgreSQL** (current) | ✅ | multi-node; network latency |
| **Redis** `SET NX PX` + AOF | ✅ | multi-node; AOF `everysec` may lose 1 s |
| **File lock** (atomic `create`/`rename` + `fsync`) | ✅ | single-node; no DB |
| **etcd/Consul** lease + CAS | ✅ | multi-node, strongly consistent; operational effort |

**Recommendation:** Single-node: SQLite or file lock. Multi-node: Redis/etcd.
Write claim durable **before** `ExecutionStarted`.

### 5.2 `Journal`

| Aspect | Value |
|---|---|
| Access | Append per event (sometimes batches); `history()` reads history |
| Requirement | append-only, monotonic sequence, `fsync` policy, retention |

| Alternative | Suitability | Note |
|---|---|---|
| **SQLite** (WAL) | ✅✅ | QOC adapter available; `event` + `event_sequence` like OC |
| **PostgreSQL** (current) | ✅ | multi-node; expensive for full scans |
| **Append-only JSONL/NDJSON** + rotation + `fsync` | ✅✅ | very cheap, ideal single-node; replay in memory |
| **Kafka / NATS JetStream** | ✅ | multi-node, ordered per partition |
| **S3/GCS** | ✅ | cold archive; not hot path |

**Hotspot in QOC:** `journal.history(id, Seq.zero()).size()` is called
**8×** per prompt in `Engine.java`. Every call loads the complete
event history. OC solves this via `event_sequence` (`bus.ts:414-427`).

**Recommendation:** Add `Journal.lastSeq(session)`/`nextSeq(session)`
(or an `event_sequence` table like OC). Keep `history()` only for replay/projection.

### 5.3 `ConversationStore`

| Aspect | Value |
|---|---|
| Access | `nextSeq` (`max(seq)+1`) per message; `insert` per message; `messages()` per step |
| Requirement | ordering, parent links, `deleteFrom` (revert/fork) |

| Alternative | Suitability | Note |
|---|---|---|
| **SQLite** (WAL) | ✅✅ | QOC adapter available; OC uses exactly this |
| **PostgreSQL** (current) | ✅ | multi-node |
| **Append log + snapshot** | ✅ | minimal; rebuild from journal |
| **Cassandra/DynamoDB** (partition = session) | ✅ | multi-node, high write load |
| **Object store** (cold history) | ✅ | cheap; no fast range reads |

**Recommendation:** Journal as source, Conversation as **projection**
materialized. Derive `nextSeq` from `Journal.lastSeq` (like OC
`event_sequence`), instead of scanning `max(seq)` per insert.

### 5.4 `SessionStore` / `SessionReader`

| Alternative | Suitability | Note |
|---|---|---|
| **SQLite** | ✅✅ | QOC adapter; single-node |
| **PostgreSQL** (current) | ✅ | multi-node |
| **JSON file + in-memory index** | ✅ | very small, single-node |
| **Redis hash** | ✅ | fast; configure persistence |

**Recommendation:** Single-node SQLite; inbox could live in the journal
(event sourcing), then `SessionStore` is only a read model.

### 5.5 `ApprovalStore`

| Alternative | Suitability |
|---|---|
| **SQLite** | ✅✅ (QOC adapter) |
| **Append-only file** | ✅ |
| **Redis** | ✅ (configure persistence) |
| **PostgreSQL** (current) | ✅ |

**Recommendation:** SQLite/file; volume is small, restart persistence matters.

### 5.6 `InstructionStore`

| Alternative | Suitability |
|---|---|
| **SQLite** | ✅✅ (QOC adapter; OC uses `instruction_*`) |
| **Filesystem + in-memory cache** | ✅✅ (content-addressed) |
| **Object store** (versioned blobs) | ✅ |
| **PostgreSQL** (current) | ⚠️ expensive for read-mostly |

**Recommendation:** Blobs content-addressed on the FS (`sha256`), state/epoch in
the journal or SQLite. No DB blob store needed.

### 5.7 `SessionWorkspaceStore`

| Alternative | Suitability |
|---|---|
| **SQLite** | ✅✅ |
| **JSON file per session** | ✅ |
| **Redis** | ✅ (ephemeral) |

**Recommendation:** SQLite/file; data is small and mostly derivable.

### 5.8 `CredentialStore`

| Alternative | Suitability | Note |
|---|---|---|
| **Vault / AWS/GCP Secret Manager** | ✅✅ | best security |
| **OS keyring** | ✅ | local |
| **Encrypted file** (age/GPG) | ✅ | simple |
| **SQLite with encryption** | ✅ | e.g. SQLCipher |
| **PostgreSQL** (current) | ⚠️ | check encryption |

> **OC comparison:** OC stores credentials and account tokens
> **unencrypted** as JSON in SQLite (`credential/sql.ts:5`,
> `account/sql.ts:5`); protected only by `chmod 0600` on the DB file.
> This is an anti-pattern QOC should not adopt.

### 5.9 `SnapshotStore`

| Alternative | Suitability | Note |
|---|---|---|
| **Git objects** (like OC) | ✅✅ | `git.ts:45-51`; diffable, packable, space-efficient |
| **Content-addressed FS** (sha256) | ✅✅ | simple, deduplicated |
| **S3/GCS** | ✅ | scaling/retention |
| **DB BLOBs** (current in QOC) | ❌ | bloats DB, expensive backups |

**Recommendation:** Take file contents out of the DB; keep metadata (snapshot ID,
paths, hashes) in SQLite, contents in Git/FS/object store. OC is the model here.

### 5.10 `ToolOutputStore`

| Alternative | Suitability | Note |
|---|---|---|
| **Filesystem** (`$DATA/tool-output/`) | ✅✅ | OC model: 7-day retention, 2,000 lines/50 KiB inline |
| **Content-addressed FS** | ✅✅ | dedupe |
| **S3/GCS** | ✅ | scaling |
| **DB BLOBs** (current in QOC) | ❌ | avoid |

**Recommendation:** Like OC: large outputs as files, only `ArtifactRef`
(metadata) in the DB.

### 5.11 `ToolResultCache`

| Alternative | Suitability |
|---|---|
| **In-memory LRU** (Caffeine) | ✅✅ |
| **Disk cache** | ✅ |
| **Redis** | ✅ (multi-node) |
| **DB** (current) | ⚠️ |

**Recommendation:** In-memory LRU; the cache may be empty after restart.
OC has no dedicated port for this – QOC can keep it as a pure
performance optimization.

## 6. Target Architecture (Hybrid Storage)

| Data type | Recommended storage | Priority |
|---|---|---|
| Claims | SQLite/file lock (single-node) or Redis/etcd (multi-node) | P0 |
| Journal/Events | SQLite WAL (like OC) or append log (single-node); Kafka (multi-node) | P0 |
| Conversation | SQLite (projection from journal) | P0 |
| Session/Inbox | SQLite | P0 |
| Approvals | SQLite/file | P1 |
| Instructions | FS + memory cache; state in SQLite/journal | P1 |
| Workspace assignment | SQLite/file | P1 |
| Credentials | Secret manager / encrypted file | P1 |
| Snapshots | Git objects or content-addressed FS | P2 |
| Tool outputs | Filesystem (retention) or object store | P2 |
| ToolResultCache | In-memory LRU | P2 |

**Guideline:** The DB stays for **small, consistency-critical metadata**.
Large blobs and derivable read models leave the DB.

## 7. Concrete Measures for QOC

### Phase 1 – Immediate (no architecture change)

1. **Test SQLite instead of Neon/Postgres:** `engine.db.kind=sqlite`,
   own `ENGINE_DATASOURCE_URL=jdbc:sqlite:/data/qoc.db`.
   Adapters are available; WAL is active. Result: no
   network round-trips, lower latency, lower DB cost.
2. **Eliminate `history().size()`:** `Journal.lastSeq(session)` or
   `nextSeq(session)` as a port method; replace the 8 calls in `Engine.java`.
   Alternatively an `event_sequence` table like OC.
3. **Check `Dialect`/SQL adjustments:** The SQLite adapters exist,
   but migrations, JSON functions and `max(seq)` must run on SQLite
   (Flyway location `db/migration-sqlite`).

### Phase 2 – DB Relief

4. **Snapshots out of the DB:** replace `snapshot_file.content` with
   content-addressed FS or Git; DB only metadata.
5. **Tool outputs out of the DB:** `tool_output.content` to FS
   (`$DATA/tool-output/`), retention and truncation like OC.
6. **ToolResultCache in memory:** Caffeine/LRU instead of DB table.

### Phase 3 – Sharpen Event Sourcing

7. **Journal as single source of truth**, Conversation as projection.
   One append per event instead of journal + conversation + session update.
8. **Update projections asynchronously**; the model reads from the
   materialized read model.
9. **Idempotency:** `IdempotencyKey` for tool side-effects; at-least-once
   recovery remains, tools must be idempotent.

### Phase 4 – Multi-Node (optional)

10. Claims → Redis/etcd with lease; Journal → Kafka/NATS; Conversation →
    partitioned DB. Only then do you leave SQLite.

## 8. Risks and Trade-offs

| Topic | Risk | Mitigation |
|---|---|---|
| SQLite single-writer | Write conflicts with many sessions | WAL, `busy_timeout`, one writer per DB; serialize sessions |
| Backup | SQLite file + WAL | Litestream/`sqlite3 .backup`, regular snapshots |
| Crash safety | `fsync` policy | Claim/journal with `synchronous=FULL` or group commit |
| Native image | SQLite JDBC/driver | Check Quarkus extension; possibly JVM mode |
| Multi-node | SQLite not shareable | Phase 4: Redis/etcd/Kafka |
| Credentials | Plaintext in DB (OC) | Secret manager/encryption |
| At-least-once | duplicate side-effects | Idempotency + `ToolResultCache` |

## 9. Open Questions

1. Should QOC stay permanently **single-node** (SQLite) or become
   multi-node (Postgres/Redis)?
2. Is the **event-sourcing projection** already sufficient to fully
   derive `Conversation` – or does it remain a separate
   read model?
3. How long should **events/history** be retained (retention)?
4. Should **credentials** go into an external secret manager or an
   encrypted local file?
5. Git snapshots (like OC) or content-addressed FS for QOC?

## 10. Sources (OC Reference)

| Topic | Path:Line |
|---|---|
| SQLite opening | `oc/packages/core/src/database/sqlite.bun.ts:87` |
| WAL pragma | `oc/packages/core/src/database/sqlite.bun.ts:93` |
| DB path/permissions | `oc/packages/core/src/database/database.ts:76-97` |
| XDG data dir | `oc/packages/util/src/global-roots.ts:5` |
| DB filename | `oc/packages/cli/src/database-path.ts:10` |
| Session table/claim | `oc/packages/core/src/session/sql.ts:22`, `store.ts:208-231` |
| Message table | `oc/packages/core/src/session/sql.ts:79` |
| Inbox/pending | `oc/packages/core/src/session/sql.ts:100`, `:125` |
| Instruction tables | `oc/packages/core/src/session/sql.ts:147-172` |
| Event/EventSequence | `oc/packages/core/src/event/sql.ts:4`, `:10` |
| Event sequence upsert | `oc/packages/core/src/bus.ts:414-427` |
| Event insert | `oc/packages/core/src/bus.ts:429-440` |
| Chat history read | `oc/packages/core/src/session/history.ts:113-121` |
| Tool output limits | `oc/packages/core/src/tool-output.ts:13-16` |
| Tool output directory | `oc/packages/core/src/tool-output.ts:57` |
| Snapshot git mechanics | `oc/packages/core/src/git.ts:45-51` |
| Credential table | `oc/packages/core/src/credential/sql.ts:5` |
| Account tokens | `oc/packages/core/src/account/sql.ts:5` |
| Permission table | `oc/packages/core/src/permission/sql.ts:7` |
| KV table | `oc/packages/core/src/kv/sql.ts:5` |

> `oc/` was analyzed read-only and not modified.