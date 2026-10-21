-- Additive review hardening after V72 and before the centrally reserved V73/V74.
-- Retained historical rows are not rewritten. Every new finalized receipt must
-- represent all its matches, except exclusive slots already selected earlier.
create function inbox.guard_complete_listen_membership() returns trigger language plpgsql as $$
declare receipt_row record;
begin
    select role,matched_slots into receipt_row from inbox.listen_item
      where tenant=new.tenant and instance_id=new.instance_id and task_digest=new.task_digest
        and sequence=new.last_sequence;
    if not found or exists (
        select 1 from jsonb_array_elements(receipt_row.matched_slots) slot
        where slot->>'groupDigest' is distinct from new.group_digest
           or slot->>'role' is distinct from receipt_row.role
           or not exists (
               select 1 from inbox.listen_member m
               where m.tenant=new.tenant and m.instance_id=new.instance_id and m.task_digest=new.task_digest
                 and m.role=slot->>'role' and m.ordinal::text=slot->>'ordinal'
                 and m.filter_fingerprint=slot->>'filterFingerprint'
                 and (m.sequence=new.last_sequence
                      or (m.sequence<new.last_sequence and m.exclusive_slot)))) then
        raise exception 'selected receipt membership is incomplete' using errcode='insufficient_privilege';
    end if;
    return new;
end;
$$;
-- Immediate AFTER row validation follows the existing owner/pin/deadline/capture
-- BEFORE guard. Failure rolls the advancement statement back atomically.
create trigger listen_group_complete_membership after update on inbox.listen_group
for each row execute function inbox.guard_complete_listen_membership();
