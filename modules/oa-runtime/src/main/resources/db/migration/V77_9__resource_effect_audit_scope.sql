-- Additive DATA provenance for a governed resource call; no caller or dispatch authority is granted.
alter table effect.audit_scope add column resource_call_key text references task.resource_call(call_key);

-- Mirror ResourceCallScope.key(), including its fixed-width iteration encoding.
create function effect.canonical_resource_call_key(call task.resource_call) returns text
language plpgsql immutable strict set search_path=pg_catalog,pg_temp as $$
declare encoded bytea = ''::bytea; value text; bytes bytea;
begin
    foreach value in array array['oa.resource.invocation.v1',call.tenant,call.instance_id,
        call.owner_kind,call.owner_id,call.application_id,call.engine_instance_id,call.namespace,
        call.definition_name,call.definition_version,call.task_position] loop
        if value is null then raise exception 'resource call field missing' using errcode='check_violation'; end if;
        bytes=convert_to(value,'UTF8'); encoded=encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    encoded=encoded || int8send(call.iteration::bigint);
    bytes=convert_to(call.logical_occurrence,'UTF8'); encoded=encoded || int4send(octet_length(bytes)) || bytes;
    return 'rc1-' || encode(sha256(encoded),'hex');
end;
$$;

create or replace function effect.audit_scope_insert_guard() returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare l effect.ledger%rowtype; e effect.event%rowtype; r task.instance%rowtype; parent effect.audit_scope%rowtype; call task.resource_call%rowtype; expected_thread text;
begin
    select * into l from effect.ledger where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key;
    select * into e from effect.event where id=new.initial_event_id;
    if l.thread_name is null or e.id is null or row(e.thread_name,e.node_id,e.idempotency_key)
        is distinct from row(new.thread_name,new.node_id,new.idempotency_key)
        or e.from_state is not null or e.to_state <> 'INTENDED' or e.reason <> 'effect.intended'
        or l.state <> 'INTENDED' or l.attempted or l.request_fingerprint is null then
        raise exception 'effect scope requires its fresh initial intent fact' using errcode = 'check_violation';
    end if;
    if new.resource_call_key is null then
        expected_thread := 'ws1-' || effect.audit_fingerprint('oa.thread.ws1',new.tenant,new.owner_id,new.registry_instance_id);
    else
        select * into call from task.resource_call where call_key=new.resource_call_key;
        if not found or row(call.tenant,call.instance_id,call.owner_kind,call.owner_id)
                is distinct from row(new.tenant,new.registry_instance_id,new.owner_kind,new.owner_id)
                or call.call_key is distinct from effect.canonical_resource_call_key(call) then
            raise exception 'effect resource call attribution differs' using errcode = 'check_violation';
        end if;
        expected_thread := 'ws1-' || effect.audit_fingerprint('oa.thread.ws1',new.tenant,
            's1-' || effect.audit_fingerprint('oa.thread.subject',new.owner_kind,new.owner_id),
            'governed:' || new.resource_call_key);
    end if;
    if new.thread_name is distinct from expected_thread
        or new.scope_fingerprint is distinct from effect.audit_fingerprint('oa.effect.audit-scope.v1',
            new.tenant,new.registry_instance_id,new.owner_kind,new.owner_id) then
        raise exception 'effect scope identity differs' using errcode = 'check_violation';
    end if;
    if new.parent_initial_event_id is null then
        select * into r from task.instance where id=new.registry_instance_id for update;
        if l.purpose <> 'EFFECT' or not found or r.tenant is distinct from new.tenant
            or r.owner_kind is distinct from new.owner_kind or r.owner_id is distinct from new.owner_id
            or r.status not in ('RUNNING','WAITING')
            or (r.wait_deadline is not null and r.wait_deadline <= clock_timestamp()) then
            raise exception 'effect root scope registry is not current' using errcode = 'check_violation';
        end if;
    else
        -- Compensation inherits established DATA provenance, including after a failed/cancelled registry run.
        -- It does not inherit permission: current recovery policy/authority remains mandatory before target entry.
        select * into parent from effect.audit_scope where initial_event_id=new.parent_initial_event_id;
        if not found or l.purpose <> 'COMPENSATION' or parent.scope_fingerprint is distinct from new.scope_fingerprint
            or parent.thread_name is distinct from new.thread_name
            or parent.resource_call_key is distinct from new.resource_call_key then
            raise exception 'compensation scope does not identify its retained original' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;
