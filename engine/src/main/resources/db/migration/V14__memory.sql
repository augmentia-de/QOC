create table if not exists memory_fact (
    fact_id varchar(255) primary key,
    entity varchar(255) not null,
    relation varchar(255) not null,
    value_json text not null,
    event_time timestamp not null,
    valid_from timestamp not null,
    valid_to timestamp,
    provenance_json text not null default '[]',
    confidence double precision not null default 1.0,
    tier int not null default 0
);

create index if not exists memory_fact_entity_idx on memory_fact (entity);
create index if not exists memory_fact_entity_relation_idx on memory_fact (entity, relation);
create index if not exists memory_fact_validity_idx on memory_fact (valid_from, valid_to);
create index if not exists memory_fact_event_time_idx on memory_fact (event_time);