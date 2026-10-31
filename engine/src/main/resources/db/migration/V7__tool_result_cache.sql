-- Tool result cache for idempotent tool execution (Option B phase 3).
create table tool_result_cache (
  idempotency_key text primary key,
  session_id      text not null,
  tool_name       text not null,
  outcome         jsonb not null,
  created_at      timestamptz not null default now()
);
create index tool_result_cache_session_idx on tool_result_cache (session_id);