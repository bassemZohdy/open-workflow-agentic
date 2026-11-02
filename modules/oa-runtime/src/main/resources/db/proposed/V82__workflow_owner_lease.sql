-- Proposed only: workflow-owner generations. Deploy neither this schema nor the companion code until owner
-- acquisition, renewal, checkpoint/effect fencing and mixed-version rollout are integrated and reviewed.
create table task.workflow_owner_lease (
    instance_id text primary key references task.instance(id),
    tenant text not null,
    application_id text not null,
    engine_instance_id text not null,
    definition_id text not null,
    definition_version text not null,
    owner_id uuid not null,
    epoch bigint not null check (epoch > 0),
    state text not null check (state in ('ACTIVE', 'RELEASED')),
    acquired_at timestamptz not null,
    renewed_at timestamptz not null,
    expires_at timestamptz not null,
    check (expires_at > acquired_at and renewed_at >= acquired_at)
);

-- Retained epochs prevent an old token becoming valid again. A live owner cannot be displaced.
create function task.workflow_owner_lease_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'workflow ownership generations cannot be deleted'; end if;
    if (new.instance_id, new.tenant, new.application_id, new.engine_instance_id,
        new.definition_id, new.definition_version) is distinct from
       (old.instance_id, old.tenant, old.application_id, old.engine_instance_id,
        old.definition_id, old.definition_version) then
        raise exception 'workflow ownership binding cannot change';
    end if;
    if new.epoch = old.epoch then
        if (old.state = 'ACTIVE' and new.state = 'ACTIVE' and old.expires_at <= clock_timestamp())
           or new.owner_id <> old.owner_id or (old.state = 'RELEASED' and new.state <> 'RELEASED')
           or new.acquired_at <> old.acquired_at or new.expires_at < old.expires_at
           or new.renewed_at < old.renewed_at then
            raise exception 'invalid workflow owner generation update';
        end if;
    elsif new.epoch = old.epoch + 1 then
        if new.state <> 'ACTIVE' or (old.state = 'ACTIVE' and old.expires_at > clock_timestamp()) then
            raise exception 'live workflow owner cannot be replaced';
        end if;
    else raise exception 'workflow owner generation must advance exactly once';
    end if;
    return new;
end $$;
create trigger workflow_owner_lease_guard before update or delete on task.workflow_owner_lease
    for each row execute function task.workflow_owner_lease_guard();
