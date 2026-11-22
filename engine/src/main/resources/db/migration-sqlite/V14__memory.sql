create table if not exists memory_fact (
    fact_id text primary key,
    entity text not null,
    relation text not null,
    value_json text not null,
    event_time text not null,
    valid_from text not null,
    valid_to text,
    provenance_json text not null default '[]',
    confidence real not null default 1.0,
    tier integer not null default 0
);

create index if not exists memory_fact_entity_idx on memory_fact (entity);
create index if not exists memory_fact_entity_relation_idx on memory_fact (entity, relation);
create index if not exists memory_fact_validity_idx on memory_fact (valid_from, valid_to);
create index if not exists memory_fact_event_time_idx on memory_fact (event_time);