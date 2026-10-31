-- Token-based async approval support (Option B).
alter table pending_approval add column token text;
alter table pending_approval add column channel text;
alter table pending_approval add column decision text;
alter table pending_approval add column feedback text;
create unique index pending_approval_token_idx on pending_approval (token) where token is not null;