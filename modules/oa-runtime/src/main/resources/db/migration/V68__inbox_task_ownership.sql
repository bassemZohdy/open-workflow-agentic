-- Task-scoped renewable ownership: no pooled connection held while a partial all-listen waits for another event.
-- Epoch authority must be checked in the SAME JTA transaction as the SDK checkpoint and receipt proof.
create table inbox.task_claim (
    tenant varchar(256) not null,
    instance_id text not null,
    task_digest varchar(64) not null check(task_digest ~ '^[0-9a-f]{64}$'),
    application_id varchar(256) not null,
    engine_instance_id varchar(256) not null,
    namespace varchar(256) not null,
    definition varchar(256) not null,
    version varchar(128) not null,
    task_position varchar(1024) not null,
    iteration integer not null check(iteration>0),
    owner varchar(128) not null check(owner ~ '^[A-Za-z0-9._:-]{1,128}$'),
    epoch bigint not null check(epoch>0),
    state varchar(16) not null check(state in ('ACTIVE','RELEASED')),
    acquired_at timestamptz not null,
    renewed_at timestamptz not null,
    expires_at timestamptz not null,
    primary key(tenant,instance_id,task_digest),
    foreign key(tenant,instance_id) references task.instance(tenant,id),
    check(renewed_at>=acquired_at and expires_at>renewed_at and expires_at<=renewed_at+interval '30 seconds')
);
create function inbox.guard_task_claim() returns trigger language plpgsql as $$
begin
    if tg_op in ('DELETE','TRUNCATE') then
        raise exception 'task claim fence history is retained' using errcode='insufficient_privilege';
    end if;
    if row(new.tenant,new.instance_id,new.task_digest,new.application_id,new.engine_instance_id,new.namespace,
           new.definition,new.version,new.task_position,new.iteration)
       is distinct from row(old.tenant,old.instance_id,old.task_digest,old.application_id,old.engine_instance_id,old.namespace,
           old.definition,old.version,old.task_position,old.iteration) then
        raise exception 'task claim identity is immutable' using errcode='insufficient_privilege';
    end if;
    if new.epoch<>old.epoch then
        if new.epoch<>old.epoch+1 or new.state<>'ACTIVE'
           or (old.state='ACTIVE' and old.expires_at>clock_timestamp()) then
            raise exception 'a live task owner cannot be replaced or its epoch skipped' using errcode='insufficient_privilege';
        end if;
    elsif new.owner<>old.owner or new.acquired_at<>old.acquired_at
          or (old.state='RELEASED')
          or (new.state='ACTIVE' and (old.expires_at<=clock_timestamp() or new.renewed_at<old.renewed_at))
          or (new.state='RELEASED' and row(new.renewed_at,new.expires_at) is distinct from row(old.renewed_at,old.expires_at)) then
        raise exception 'invalid task lease renewal/release' using errcode='insufficient_privilege';
    end if;
    return new;
end;
$$;
create trigger inbox_task_claim_guard before update or delete on inbox.task_claim
    for each row execute function inbox.guard_task_claim();
create trigger inbox_task_claim_no_truncate before truncate on inbox.task_claim
    for each statement execute function inbox.guard_task_claim();

-- Prior immutable receipts stay historical; future writer inserts record the exact task fence used.
alter table inbox.checkpoint_receipt add column task_digest varchar(64), add column claim_owner varchar(128),
    add column claim_epoch bigint;
alter table inbox.checkpoint_receipt add constraint checkpoint_task_fence_fields check (
    (task_digest is null and claim_owner is null and claim_epoch is null) or
    (task_digest is not null and task_digest ~ '^[0-9a-f]{64}$' and claim_owner is not null
     and claim_owner ~ '^[A-Za-z0-9._:-]{1,128}$' and claim_epoch is not null and claim_epoch>0));
alter table inbox.checkpoint_receipt add constraint checkpoint_task_fence_fk
    foreign key(tenant,instance_id,task_digest) references inbox.task_claim(tenant,instance_id,task_digest);
