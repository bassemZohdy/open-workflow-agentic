-- Short-transaction dispatch tied to the task owner; existing historical/legacy rows retain null ownership.
alter table inbox.delivery add column task_digest varchar(64), add column claim_owner varchar(128), add column claim_epoch bigint;
alter table inbox.delivery add constraint delivery_task_fence_fields check (
    (task_digest is null and claim_owner is null and claim_epoch is null) or
    (task_digest is not null and task_digest ~ '^[0-9a-f]{64}$' and claim_owner is not null
     and claim_owner ~ '^[A-Za-z0-9._:-]{1,128}$' and claim_epoch is not null and claim_epoch>0));
alter table inbox.delivery add constraint delivery_task_fence_fk
    foreign key(tenant,instance_id,task_digest) references inbox.task_claim(tenant,instance_id,task_digest);

create function inbox.guard_owned_delivery() returns trigger language plpgsql as $$
declare current_claim record;
begin
    if old.task_digest is not null
       and row(new.task_digest,new.claim_owner,new.claim_epoch) is distinct from row(old.task_digest,old.claim_owner,old.claim_epoch)
       and not (new.state='DISPATCHED' and new.attempts=old.attempts+1 and new.task_digest is not null) then
        raise exception 'dispatch ownership changes only with a new fenced attempt' using errcode='insufficient_privilege';
    end if;
    if old.task_digest is null and new.task_digest is not null and new.state<>'DISPATCHED' then
        raise exception 'ownership belongs to a dispatch reservation' using errcode='insufficient_privilege';
    end if;
    if new.state='DISPATCHED' and new.task_digest is not null and new.attempts<>old.attempts then
        if new.attempts<>old.attempts+1
           or row(new.task_digest,new.claim_owner,new.claim_epoch) is not distinct from row(old.task_digest,old.claim_owner,old.claim_epoch) then
            raise exception 'one dispatch permission per task owner epoch' using errcode='insufficient_privilege';
        end if;
        select owner,epoch,state,expires_at into current_claim from inbox.task_claim
         where tenant=new.tenant and instance_id=new.instance_id and task_digest=new.task_digest for update;
        if not found or current_claim.owner<>new.claim_owner or current_claim.epoch<>new.claim_epoch
           or current_claim.state<>'ACTIVE' or current_claim.expires_at<=clock_timestamp() then
            raise exception 'dispatch lost task ownership' using errcode='insufficient_privilege';
        end if;
    end if;
    return new;
end;
$$;
create trigger inbox_owned_delivery_guard before update on inbox.delivery
    for each row execute function inbox.guard_owned_delivery();
