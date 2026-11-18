# Maintenance & Cleanup — Datenbank

> Projekt: `qoc` · DB: PostgreSQL (`qoc`, Port 20000) · Stand: 2026-11-18

## 1. Übersicht der Tabellen und Größen

| Tabelle | Zeilen | Größe | FK zu `session`? |
|---|---|---|---|
| `session_message` | 3.920 | 26 MB | ja (CASCADE) |
| `tool_output` | 11 | 7 MB (bis 4.5 MB/Blob) | nein |
| `tool_result_cache` | 597 | 3 MB | nein |
| `event` | 1.503 | 1.3 MB | nein |
| `event_sequence` | 439 | 144 KB | nein |
| `session` | 436 | 352 KB | — |
| `workflow_version` | 112 | 320 KB | ja (CASCADE) |
| `workflow_definition` | 9 | 224 KB | — |
| `agent_definition` | 26 | 144 KB | — |
| `resume_counter` | 428 | 136 KB | ja (CASCADE) |
| `mcp_server` | 0 | — | nein |
| `snapshot` / `snapshot_file` | 0 | — | ja (CASCADE) |
| `instruction_*` | 0 | — | ja (CASCADE) |
| `credential` | 0 | — | — |
| `pending_approval` | 0 | — | nein |
| `saved_approval` | 0 | — | ja (CASCADE) |
| `session_claim` / `session_inbox` | 0 | — | ja (CASCADE) |
| `question_request` | 1 | 104 KB | nein |
| **Gesamt** | | **74 MB** | |

## 2. Bereits automatisierte Cleanup-Jobs (`RetentionService`, stündlich)

| Job | Löscht Daten? | Ziel |
|---|---|---|
| `purgeExpiredSessions` | ja (Session + Cascade) | abgelaufene/leere Sessions |
| `sweepEphemeralOrphans` | ja | verwaiste Ephemeral-Workspaces |
| `sweepPausedSessions` | nein (State → `expired`) | pausierte Sessions markieren |
| `purgeAuditLog` | ja (Events, `tool_output`, Snapshots älter als `auditRetentionDays`) | Audit-Daten |
| `purgeDecidedApprovals` | ja | entschiedene Approvals (>7 Tage) |
| `purgeToolResultCache` | ja | Cache (>1 Tag) |

Konfiguration: `engine.session.retention-days`, `engine.audit.retention-days` (ENV/`application.properties`).

## 3. Lücken im Schema (fehlende Foreign Keys)

`tool_output`, `tool_result_cache`, `event`, `event_sequence` haben **keine FK** zu `session`. Folge: nach Session-Löschung verbleiben verwaiste Zeilen (aktuell 3 verwaiste `event_sequence`).

**Empfehlung:** Migration hinzufügen:
```sql
ALTER TABLE tool_output ADD CONSTRAINT tool_output_session_fk
  FOREIGN KEY (session_id) REFERENCES session(id) ON DELETE CASCADE;
-- analog für tool_result_cache, event (aggregate_id), event_sequence
```

## 4. Sinnvolle Cleanup-Funktionen

### A. Nicht-destruktiv (keine Datenlöschung)

1. **Postgres-Maintenance-Job** (nightly): `VACUUM ANALYZE` + `REINDEX` auf `session_message`, `tool_output`, `event`. Reclaims Dead Tuples, aktualisiert Planner-Stats. Kein Datenverlust.
2. **Session-Archivierung statt Löschen**: `time_archived` setzen statt `DELETE`. Retention blendet archivierte Sessions aus Listen aus, Daten bleiben erhalten und sind reversibel.
3. **`tool_output` Offload**: Blobs >1 MB in Dateisystem auslagern, DB speichert nur Metadaten + Pfad (`path`, `sha256`, `size`). Reduziert DB-Größe ohne Datenverlust.
4. **`session_message`-Kompaktierung**: Alte Nachrichten eines Sessions zu einer Summary-Nachricht zusammenfassen; Originale mit `archived=true` markieren (statt löschen). Reduziert Token-/Kontextlast.
5. **Manueller Trigger-Endpoint**: `POST /api/admin/db/maintenance` (Admin-Rolle) führt VACUUM + Orphan-Cleanup + Stats-Report on-demand aus. Aktuell nur stündlicher Scheduler.

### B. Destruktiv, aber regenerierbar/sicher

6. **Workflow-Version-Pruning**: `extract-transform-validate` hat 66 Versionen. Nur letzte N (konfigurierbar, Default 10) behalten; ältere löschen. Versionen sind Snapshots, regenerierbar. *Alternative ohne Löschung:* Archiv-Tabelle `workflow_version_archive`.
7. **Orphan-Cleanup**: `DELETE FROM tool_output/tool_result_cache/event/event_sequence WHERE session_id/aggregate_id NOT IN (SELECT id FROM session)`. Nur verwaiste Zeilen, keine "echten" Daten.
8. **Agent-Deduplizierung**: Test-/Leftover-Agents (`test-rs-agent`, `agent-1794820187948`, `alice-agent`, `qoc-instance`) und inkonsistente Kategorien (leer vs. `Generated`/`hidden`/etc.). Funktion: Admin-Endpoint zum Auflisten + manuellem Löschen/Deduplizieren. *Option:* "Generated"-Agents nach X Tagen automatisch entfernen, wenn kein Workflow sie mehr referenziert.
9. **Tool-Output-Artifact-Cleanup**: Artifacts älter als N Tage oder zu nicht mehr existierenden Sessions löschen (aktuell nur cutoff-basiert via `purgeAuditLog`).
10. **Leere/Abandoned Workflows**: Workflows ohne Nodes oder mit 0 Runs nach X Tagen archivieren/löschen.

## 5. Empfohlene Reihenfolge

1. **Sofort (sicher, nicht-destruktiv):** VACUUM/ANALYZE-Job + manueller Trigger-Endpoint + Orphan-Cleanup (nur verwaiste Zeilen).
2. **Kurzfristig (moderat):** `tool_output` Offload, Session-Archivierung, Version-Pruning (konfigurierbar, Default behält 10).
3. **Optional (aufwändig):** Message-Kompaktierung, Agent-Dedup, FK-Migration.

## 6. Offene Fragen

- Sollen "Generated"-Agents automatisch nach X Tagen gelöscht werden, wenn kein Workflow sie referenziert (oder nur manuell)?
- Darf Version-Pruning Daten löschen, oder soll es archivieren (`workflow_version_archive`)?
- Soll `POST /api/admin/db/maintenance` (VACUUM + Orphan-Cleanup + Stats) implementiert werden?
path: /home/torsten/dev/my-projects/2oc/qoc/qoc/docs/maintenance.md