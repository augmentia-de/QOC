# Maintenance & Cleanup — Database

> Project: `qoc` · DB: PostgreSQL (`qoc`, Port 20000) · As of: 2026-11-18

## 1. Table Overview and Sizes

| Table | Rows | Size | FK to `session`? |
|---|---|---|---|
| `session_message` | 3,920 | 26 MB | yes (CASCADE) |
| `tool_output` | 11 | 7 MB (up to 4.5 MB/Blob) | no |
| `tool_result_cache` | 597 | 3 MB | no |
| `event` | 1,503 | 1.3 MB | no |
| `event_sequence` | 439 | 144 KB | no |
| `session` | 436 | 352 KB | — |
| `workflow_version` | 112 | 320 KB | yes (CASCADE) |
| `workflow_definition` | 9 | 224 KB | — |
| `agent_definition` | 26 | 144 KB | — |
| `resume_counter` | 428 | 136 KB | yes (CASCADE) |
| `mcp_server` | 0 | — | no |
| `snapshot` / `snapshot_file` | 0 | — | yes (CASCADE) |
| `instruction_*` | 0 | — | yes (CASCADE) |
| `credential` | 0 | — | — |
| `pending_approval` | 0 | — | no |
| `saved_approval` | 0 | — | yes (CASCADE) |
| `session_claim` / `session_inbox` | 0 | — | yes (CASCADE) |
| `question_request` | 1 | 104 KB | no |
| **Total** | | **74 MB** | |

## 2. Already Automated Cleanup Jobs (`RetentionService`, hourly)

| Job | Deletes data? | Target |
|---|---|---|
| `purgeExpiredSessions` | yes (Session + Cascade) | expired/empty sessions |
| `sweepEphemeralOrphans` | yes | orphaned Ephemeral workspaces |
| `sweepPausedSessions` | no (State → `expired`) | mark paused sessions |
| `purgeAuditLog` | yes (Events, `tool_output`, Snapshots older than `auditRetentionDays`) | audit data |
| `purgeDecidedApprovals` | yes | decided approvals (>7 days) |
| `purgeToolResultCache` | yes | cache (>1 day) |

Configuration: `engine.session.retention-days`, `engine.audit.retention-days` (ENV/`application.properties`).

## 3. Schema Gaps (Missing Foreign Keys)

`tool_output`, `tool_result_cache`, `event`, `event_sequence` have **no FK** to `session`. Consequence: after session deletion, orphaned rows remain (currently 3 orphaned `event_sequence`).

**Recommendation:** add migration:
```sql
ALTER TABLE tool_output ADD CONSTRAINT tool_output_session_fk
  FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE;
-- analogous for tool_result_cache, event (aggregate_id), event_sequence
```

## 4. Useful Cleanup Functions

### A. Non-destructive (no data deletion)

1. **Postgres-Maintenance-Job** (nightly): `VACUUM ANALYZE` + `REINDEX` on `session_message`, `tool_output`, `event`. Reclaims dead tuples, updates planner stats. No data loss.
2. **Session archiving instead of deletion**: set `time_archived` instead of `DELETE`. Retention excludes archived sessions from lists, data remains preserved and reversible.
3. **`tool_output` offload**: offload blobs >1 MB to the filesystem, DB stores only metadata + path (`path`, `sha256`, `size`). Reduces DB size without data loss.
4. **`session_message` compaction**: summarize old messages of a session into a single summary message; mark originals with `archived=true` (instead of deleting). Reduces token/context load.
5. **Manual trigger endpoint**: `POST /api/admin/db/maintenance` (Admin role) runs VACUUM + orphan cleanup + stats report on demand. Currently only hourly scheduler.

### B. Destructive but regenerable/safe

6. **Workflow version pruning**: `extract-transform-validate` has 66 versions. Keep only last N (configurable, default 10); delete older ones. Versions are snapshots, regenerable. *Alternative without deletion:* archive table `workflow_version_archive`.
7. **Orphan cleanup**: `DELETE FROM tool_output/tool_result_cache/event/event_sequence WHERE session_id/aggregate_id NOT IN (SELECT id FROM session)`. Only orphaned rows, no "real" data.
8. **Agent deduplication**: test/leftover agents (`test-rs-agent`, `agent-1794820187948`, `alice-agent`, `qoc-instance`) and inconsistent categories (empty vs. `Generated`/`hidden`/etc.). Function: Admin endpoint to list + manually delete/deduplicate. *Option:* automatically remove "Generated" agents after X days if no workflow references them anymore.
9. **Tool-output artifact cleanup**: delete artifacts older than N days or belonging to no longer existing sessions (currently only cutoff-based via `purgeAuditLog`).
10. **Empty/abandoned workflows**: archive/delete workflows without nodes or with 0 runs after X days.

## 5. Recommended Order

1. **Immediately (safe, non-destructive):** VACUUM/ANALYZE job + manual trigger endpoint + orphan cleanup (only orphaned rows).
2. **Short term (moderate):** `tool_output` offload, session archiving, version pruning (configurable, default keeps 10).
3. **Optional (effort-intensive):** message compaction, agent dedup, FK migration.

## 6. Open Questions

- Should "Generated" agents be automatically deleted after X days if no workflow references them (or only manually)?
- May version pruning delete data, or should it archive (`workflow_version_archive`)?
- Should `POST /api/admin/db/maintenance` (VACUUM + orphan cleanup + stats) be implemented?
