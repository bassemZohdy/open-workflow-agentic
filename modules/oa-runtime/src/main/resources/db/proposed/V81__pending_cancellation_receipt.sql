-- Opt-in proposal: a pending restored-instance cancellation fact, committed with SDK checkpoint removal.
-- Install only after the SDK JPA tables exist and the writer/reconciler roles have been reviewed.
create table task.pending_cancellation_receipt (
    instance_id text primary key references task.instance(id),
    application_id text not null check (length(application_id) between 1 and 255),
    engine_instance_id text not null check (length(engine_instance_id) between 1 and 64),
    definition_id text not null check (length(definition_id) between 3 and 257),
    definition_version text not null check (length(definition_version) between 1 and 64),
    recorded_at timestamptz not null default clock_timestamp(),
    unique (application_id, engine_instance_id)
);

create function task.pending_cancel_validate() returns trigger language plpgsql as $$
declare registry task.instance%rowtype;
begin
    select * into registry from task.instance where id = new.instance_id for update;
    if not found or registry.status <> 'CANCEL_REQUESTED'
        or registry.engine_instance_id is distinct from new.engine_instance_id
        or registry.definition_id is distinct from new.definition_id
        or registry.definition_version is distinct from new.definition_version then
        raise exception 'pending cancellation registry binding differs';
    end if;
    perform 1 from public.workflowinstanceentity
        where applicationid = new.application_id and instanceid = new.engine_instance_id
          and workflownamespace || '/' || workflowname = new.definition_id
          and workflowversion = new.definition_version for update;
    if not found then raise exception 'pending cancellation checkpoint missing or mismatched'; end if;
    new.recorded_at := clock_timestamp();
    return new;
end $$;
create trigger pending_cancel_validate before insert on task.pending_cancellation_receipt
    for each row execute function task.pending_cancel_validate();

-- The receipt is not evidence until the same transaction has actually removed the checkpoint.
create function task.pending_cancel_require_removal() returns trigger language plpgsql as $$
begin
    if exists (select 1 from public.workflowinstanceentity
               where applicationid = new.application_id and instanceid = new.engine_instance_id) then
        raise exception 'pending cancellation checkpoint was not removed';
    end if;
    return null;
end $$;
create constraint trigger pending_cancel_require_removal after insert on task.pending_cancellation_receipt
    deferrable initially deferred for each row execute function task.pending_cancel_require_removal();

create function task.pending_cancel_immutable() returns trigger language plpgsql as $$
begin raise exception 'pending cancellation receipts are immutable'; end $$;
create trigger pending_cancel_immutable before update or delete on task.pending_cancellation_receipt
    for each row execute function task.pending_cancel_immutable();
