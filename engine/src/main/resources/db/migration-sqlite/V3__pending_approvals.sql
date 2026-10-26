-- Durable pending approval requests (QOC-009).

create table pending_approval (
  id          text primary key,
  session_id  text not null,
  action      text not null,
  context     text,
  created_at  integer not null,
  deadline_at integer not null,
  state       text not null
);

create index pending_approval_session
  on pending_approval (session_id, state);
