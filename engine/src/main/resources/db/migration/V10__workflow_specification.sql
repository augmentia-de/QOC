-- Workflow specification storage.
-- Runtime state intentionally lives in the engine journal (event table),
-- not in these tables.

create table workflow_definition (
    id            text primary key,
    name          text not null,
    version       text not null,
    status        text not null,
    definition    jsonb not null,
    content_hash  text not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now()
);

create index workflow_definition_updated
    on workflow_definition (updated_at desc);

create table workflow_version (
    workflow_id   text not null references workflow_definition(id) on delete cascade,
    version       text not null,
    definition    jsonb not null,
    content_hash  text not null,
    created_at    timestamptz not null default now(),
    primary key (workflow_id, version)
);

create index workflow_version_hash
    on workflow_version (workflow_id, content_hash);
