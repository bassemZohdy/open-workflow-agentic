-- Append-only below the application (M15). UPDATE, DELETE and TRUNCATE raise an error for every role that is not
-- allowed to disable the trigger, including the table owner used by the application. An owner can still disable
-- these triggers; docs/audit.md describes the INSERT/SELECT role (no trust in the application) and the external head
-- anchor (no trust in the owner, still a gap).
create or replace function audit.reject_change() returns trigger
language plpgsql as $$
begin
  raise exception 'audit table % is append-only: % is not allowed', tg_table_name, tg_op
    using errcode = 'insufficient_privilege';
end;
$$;

create trigger event_append_only_update before update on audit.event
  for each row execute function audit.reject_change();
create trigger event_append_only_delete before delete on audit.event
  for each row execute function audit.reject_change();
create trigger event_append_only_truncate before truncate on audit.event
  for each statement execute function audit.reject_change();
