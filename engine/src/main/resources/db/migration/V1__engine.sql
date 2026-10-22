-- Engine schema (PostgreSQL). Event log, projections, inbox, claims and
-- supporting stores. M13.

create table event (
  id            bigserial primary key,
  aggregate_id  text        not null,
  seq           bigint      not null,
  type          text        not null,
  version       int         not null,
  data          jsonb       not null,
  created_at    timestamptz not null default now(),
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
  model            jsonb,
  permissions      jsonb       not null default '[]',
  metadata         jsonb       not null default '{}',
  cost             numeric(18,6) not null default 0,
  tokens           jsonb       not null default '{}',
  time_created     timestamptz not null default now(),
  time_updated     timestamptz not null default now(),
  time_viewed      timestamptz,
  time_idle        timestamptz,
  idle_outcome     text,
  time_suspended   timestamptz,
  resume_attempts  int         not null default 0,
  time_archived    timestamptz
);

create table session_claim (
  session_id      text primary key references session(id) on delete cascade,
  claimed_at      timestamptz not null default now(),
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
  data       jsonb  not null,
  created_at timestamptz not null default now(),
  unique (session_id, seq)
);

create table session_inbox (
  id            text primary key,
  session_id    text   not null references session(id) on delete cascade,
  type          text   not null,
  payload       jsonb  not null,
  delivery      text   not null check (delivery in ('steer', 'queue')),
  enqueued_seq  bigint not null,
  created_at    timestamptz not null default now()
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
  active         boolean     not null default true,
  created_at     timestamptz not null default now()
);
create unique index credential_one_active
  on credential (integration) where active;

create table instruction_state (
  session_id      text primary key references session(id) on delete cascade,
  epoch_start     bigint      not null,
  through_seq     bigint      not null,
  initial_values  jsonb       not null default '{}',
  current_values  jsonb       not null default '{}'
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
  content    bytea       not null,
  created_at timestamptz not null default now()
);

create table snapshot (
  id         text primary key,
  created_at timestamptz not null default now()
);

create table snapshot_file (
  snapshot_id text  not null references snapshot(id) on delete cascade,
  path        text  not null,
  content     bytea,
  primary key (snapshot_id, path)
);
