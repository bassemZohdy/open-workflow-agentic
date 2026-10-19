-- Insert-only record of the head of every chain: one row per appended event, written in the same transaction as the
-- event. A removed tail event leaves a head row without its event, which the chain verifier reports.
create table if not exists audit.head_log (
  chain_id text   not null,
  seq      bigint not null check (seq >= 1),
  hash     text   not null check (hash ~ '^[0-9a-f]{64}$'),
  primary key (chain_id, seq)
);

create trigger head_log_append_only_update before update on audit.head_log
  for each row execute function audit.reject_change();
create trigger head_log_append_only_delete before delete on audit.head_log
  for each row execute function audit.reject_change();
create trigger head_log_append_only_truncate before truncate on audit.head_log
  for each statement execute function audit.reject_change();
