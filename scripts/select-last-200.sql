-- agent_definition: gespeicherte Agent-Spezifikationen (SSOT)
SELECT * FROM agent_definition ORDER BY updated_at DESC, created_at DESC LIMIT 200;

-- credential: gespeicherte Credentials/Integrationen
SELECT * FROM credential ORDER BY created_at DESC LIMIT 200;

-- event: Journal-Events pro Session/Aggregate
SELECT * FROM event ORDER BY created_at DESC LIMIT 200;

-- event_sequence: Sequenznummern pro Aggregate
SELECT * FROM event_sequence ORDER BY seq DESC, aggregate_id DESC LIMIT 200;

-- flyway_schema_history: angewendete DB-Migrationen
SELECT * FROM flyway_schema_history ORDER BY installed_on DESC, installed_rank DESC LIMIT 200;

-- instruction_blob: gespeicherte Instruction-Blobs
SELECT * FROM instruction_blob ORDER BY hash DESC LIMIT 200;

-- instruction_entry: Instruction-Eintraege pro Session
SELECT * FROM instruction_entry ORDER BY session_id DESC, key DESC LIMIT 200;

-- instruction_state: Instruction-State pro Session
SELECT * FROM instruction_state ORDER BY session_id DESC, epoch_start DESC LIMIT 200;

-- pending_approval: offene/asynchrone Freigaben
SELECT * FROM pending_approval ORDER BY created_at DESC LIMIT 200;

-- question_request: offene Fragen an Nutzer/Agenten
SELECT * FROM question_request ORDER BY created_at DESC LIMIT 200;

-- resume_counter: Resume-Versuche pro Session
SELECT * FROM resume_counter ORDER BY session_id DESC, attempts DESC LIMIT 200;

-- saved_approval: gespeicherte Freigabeentscheidungen
SELECT * FROM saved_approval ORDER BY id DESC LIMIT 200;

-- session: Sessions und Workflow-Runs
SELECT * FROM session ORDER BY time_created DESC LIMIT 200;

-- session_claim: aktive Claims/Leases auf Sessions
SELECT * FROM session_claim ORDER BY claimed_at DESC LIMIT 200;

-- session_inbox: eingehende Nachrichten/Events pro Session
SELECT * FROM session_inbox ORDER BY created_at DESC LIMIT 200;

-- session_message: Nachrichtenverlauf pro Session
SELECT * FROM session_message ORDER BY created_at DESC LIMIT 200;

-- snapshot: Snapshots
SELECT * FROM snapshot ORDER BY created_at DESC LIMIT 200;

-- snapshot_file: Dateien innerhalb von Snapshots
SELECT * FROM snapshot_file ORDER BY snapshot_id DESC, path DESC LIMIT 200;

-- tool_output: Tool-Ausgaben/Artefakte
SELECT * FROM tool_output ORDER BY created_at DESC LIMIT 200;

-- tool_result_cache: gecachte Tool-Ergebnisse
SELECT * FROM tool_result_cache ORDER BY created_at DESC LIMIT 200;

-- workflow_definition: Workflow-Definitionen (aktuelle Versionen)
SELECT * FROM workflow_definition ORDER BY updated_at DESC, created_at DESC LIMIT 200;

-- workflow_version: versionierte Workflow-Snapshots
SELECT * FROM workflow_version ORDER BY created_at DESC LIMIT 200;
