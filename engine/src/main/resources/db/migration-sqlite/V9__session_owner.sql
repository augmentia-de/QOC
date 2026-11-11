-- Session ownership for multi-user isolation (H10).

alter table session add column owner_id text;

create index session_owner_updated
  on session (owner_id, time_updated);

-- Best-effort backfill from the per-user workspace layout.
update session
   set owner_id = substr(
       substr(directory, instr(directory, '/user-ws/') + 9),
       1,
       instr(substr(directory, instr(directory, '/user-ws/') + 9) || '/', '/') - 1)
 where owner_id is null
   and directory like '%/user-ws/%';
