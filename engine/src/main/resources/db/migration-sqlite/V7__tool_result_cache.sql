-- Tool result cache for idempotent tool execution (Option B phase 3).
create table tool_result_cache (
  idempotency_key text primary key,
  session_id      text not null,
  tool_name       text not null,
  outcome         text not null,
  created_at      text not null default (datetime('now'))
);
create index tool_result_cache_session_idx on tool_result_cache (session_id);