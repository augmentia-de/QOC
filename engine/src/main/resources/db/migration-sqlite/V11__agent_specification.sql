-- Agent specification storage (SSOT).
create table agent_definition (
    id            text primary key,
    name          text not null,
    agent         text not null,
    created_at    text not null default (datetime('now')),
    updated_at    text not null default (datetime('now'))
);

create index agent_definition_updated
    on agent_definition (updated_at desc);