-- V71 may already have been applied by another checkout. Add the missing late-INSERT fence without changing it.
create or replace function inbox.guard_listen_member() returns trigger language plpgsql as $$
declare g record; receipt_row record;
begin
    select selecting_owner,selecting_epoch,matched_slots into receipt_row from inbox.listen_item
      where tenant=new.tenant and instance_id=new.instance_id and task_digest=new.task_digest and sequence=new.sequence;
    if not found then
        raise exception 'membership has no selected input' using errcode='insufficient_privilege';
    end if;
    perform inbox.require_group_owner(new.tenant,new.instance_id,new.task_digest,receipt_row.selecting_owner,receipt_row.selecting_epoch);
    select spec_json::jsonb as spec,group_digest,slots_json,last_sequence,capture_complete,deadline into g from inbox.listen_group
      where tenant=new.tenant and instance_id=new.instance_id and task_digest=new.task_digest for update;
    if not found or g.capture_complete or g.deadline<=clock_timestamp() or new.sequence<>g.last_sequence+1 then
        raise exception 'committed selection membership is closed' using errcode='insufficient_privilege';
    end if;
    if not exists(select 1 from jsonb_array_elements(g.slots_json) s
        where s->>'role'=new.role and (s->>'ordinal')::integer=new.ordinal and s->>'filterFingerprint'=new.filter_fingerprint)
       or not exists(select 1 from jsonb_array_elements(receipt_row.matched_slots) s
        where s->>'groupDigest'=g.group_digest and s->>'role'=new.role and (s->>'ordinal')::integer=new.ordinal
          and s->>'filterFingerprint'=new.filter_fingerprint) then
        raise exception 'membership was not a pinned selected match' using errcode='insufficient_privilege';
    end if;
    new.exclusive_slot := new.role='UNTIL' or g.spec->>'strategy' in ('ONE','ALL');
    return new;
end;
$$;
