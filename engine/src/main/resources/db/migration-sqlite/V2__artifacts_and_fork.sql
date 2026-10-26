-- Session-scoped artifacts and stable message parent links.

alter table tool_output add column session_id text;
alter table tool_output add column artifact_id text;
alter table tool_output add column media_type text;
alter table tool_output add column sha256 text;
alter table tool_output add column size bigint;

create unique index tool_output_artifact
  on tool_output (session_id, artifact_id);

alter table session_message add column parent_id text;
create index session_message_parent
  on session_message (session_id, parent_id);
