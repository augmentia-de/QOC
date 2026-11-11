-- Durable question requests (parallel email + UI).
create table question_request (
  id              text primary key,
  session_id      text        not null,
  parent_session_id text,
  session_title   text,
  questions_json  text        not null,
  created_at      timestamptz not null,
  deadline_at     timestamptz not null,
  token_hash      text        not null,
  answer_selected text,
  answer_text     text,
  status          text        not null default 'pending'
);

create index question_request_session
  on question_request (session_id, status);

create index question_request_status
  on question_request (status, deadline_at);
