-- Execution mode for sessions (persistent workspace vs. ephemeral one-shot).
alter table session add column execution_mode text not null default 'workspace';
alter table session add column state          text not null default 'active';
alter table session add column workspace_root text;
alter table session add column instance_id     text;
alter table session add column retain          boolean not null default false;

create index session_ephemeral_idx
  on session (execution_mode, state)
  where execution_mode = 'ephemeral';