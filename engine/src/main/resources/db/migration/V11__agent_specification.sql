-- Agent specification storage (SSOT).
-- Runtime state intentionally lives in the engine journal.

create table agent_definition (
    id            text primary key,
    name          text not null,
    agent         jsonb not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index agent_definition_updated
    on agent_definition (updated_at desc);