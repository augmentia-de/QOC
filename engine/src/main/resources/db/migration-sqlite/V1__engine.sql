-- Engine schema (PostgreSQL). Event log, projections, inbox, claims and
-- supporting stores. M13.

create table event (
  id            integer primary key autoincrement,
  aggregate_id  text        not null,
  seq           bigint      not null,
  type          text        not null,
  version       int         not null,
  data          text       not null,
  created_at    text not null default (datetime('now')),
  unique (aggregate_id, seq)
);

create table event_sequence (
  aggregate_id text primary key,
  seq          bigint not null
);

create table session (
  id               text primary key,
  project_id       text        not null,
  directory        text        not null,
  workspace_id     text,
  title            text        not null default '',
  agent_id         text        not null,
  model            text,
  permissions      text       not null default '[]',
  metadata         text       not null default '{}',
  cost             real not null default 0,
  tokens           text       not null default '{}',
  time_created     text not null default (datetime('now')),
  time_updated     text not null default (datetime('now')),
  time_viewed      text,
  time_idle        text,
  idle_outcome     text,
  time_suspended   text,
  resume_attempts  int         not null default 0,
  time_archived    text
);

create table session_claim (
  session_id      text primary key references session(id) on delete cascade,
  claimed_at      text not null default (datetime('now')),
  resume_attempts int         not null default 0
);

create table resume_counter (
  session_id text primary key references session(id) on delete cascade,
  attempts   int not null default 0
);

create table session_message (
  id         text primary key,
  session_id text   not null references session(id) on delete cascade,
  seq        bigint not null,
  type       text   not null,
  data       text  not null,
  created_at text not null default (datetime('now')),
  unique (session_id, seq)
);

create table session_inbox (
  id            text primary key,
  session_id    text   not null references session(id) on delete cascade,
  type          text   not null,
  payload       text  not null,
  delivery      text   not null check (delivery in ('steer', 'queue')),
  enqueued_seq  bigint not null,
  created_at    text not null default (datetime('now'))
);
create index session_inbox_order on session_inbox (session_id, delivery, enqueued_seq);

create table saved_approval (
  id         text primary key,
  project_id text not null,
  action     text not null,
  resource   text not null,
  unique (project_id, action, resource)
);

create table credential (
  id             text primary key,
  integration    text        not null,
  kind           text        not null,
  secret         text,
  active         integer     not null default true,
  created_at     text not null default (datetime('now'))
);
create unique index credential_one_active
  on credential (integration) where active;

create table instruction_state (
  session_id      text primary key references session(id) on delete cascade,
  epoch_start     bigint      not null,
  through_seq     bigint      not null,
  initial_values  text       not null default '{}',
  current_values  text       not null default '{}'
);

create table instruction_entry (
  session_id text not null references session(id) on delete cascade,
  key        text not null,
  value      text not null,
  hash       text not null,
  primary key (session_id, key)
);

create table instruction_blob (
  hash  text primary key,
  value text not null
);

create table tool_output (
  path       text primary key,
  content    blob       not null,
  created_at text not null default (datetime('now'))
);

create table snapshot (
  id         text primary key,
  created_at text not null default (datetime('now'))
);

create table snapshot_file (
  snapshot_id text  not null references snapshot(id) on delete cascade,
  path        text  not null,
  content     blob,
  primary key (snapshot_id, path)
);
