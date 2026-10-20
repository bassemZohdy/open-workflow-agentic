-- PROPOSED V76; outside Flyway until V71..V75 and exact interface/consumer reviews integrate in order.
-- Immutable data only. INSERT is NOT permission: the runtime gate must own the one freshly committed callback,
-- mandatory audit, current identity/registry/target/owner checks and private output release.
create function task.resource_call_data_contract(invocation task.resource_call) returns text
    language plpgsql immutable strict as $$
declare encoded bytea = ''::bytea; value text; bytes bytea; call_identity text; contract text;
begin
    foreach value in array array['oa.resource.invocation.v1', invocation.tenant, invocation.instance_id,
        invocation.owner_kind, invocation.owner_id, invocation.application_id, invocation.engine_instance_id,
        invocation.namespace, invocation.definition_name, invocation.definition_version, invocation.task_position] loop
        if value is null then raise exception 'resource invocation field missing' using errcode = 'check_violation'; end if;
        bytes = convert_to(value, 'UTF8'); encoded = encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    bytes = convert_to(invocation.logical_occurrence, 'UTF8');
    encoded = encoded || int8send(invocation.iteration::bigint) || int4send(octet_length(bytes)) || bytes;
    call_identity = 'rc1-' || encode(sha256(encoded), 'hex');
    encoded = ''::bytea;
    foreach value in array array['oa.resource.invocation-contract.v1', call_identity, invocation.audience,
        invocation.operation, invocation.target_fingerprint, invocation.request_fingerprint] loop
        if value is null then raise exception 'resource invocation field missing' using errcode = 'check_violation'; end if;
        bytes = convert_to(value, 'UTF8'); encoded = encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    contract = encode(sha256(encoded), 'hex');
    if invocation.call_key is distinct from call_identity or invocation.fingerprint is distinct from contract then
        raise exception 'resource invocation canonical data differs' using errcode = 'check_violation';
    end if;
    return contract;
end;
$$;

create function task.resource_execution_identity(call_key text, contract_fingerprint text, execution_epoch bigint)
    returns text language plpgsql immutable strict as $$
declare encoded bytea = ''::bytea; value text; bytes bytea;
begin
    foreach value in array array['oa.resource.execution-entry.v1', call_key, contract_fingerprint] loop
        bytes = convert_to(value, 'UTF8'); encoded = encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    return 're1-' || encode(sha256(encoded || int8send(execution_epoch)), 'hex');
end;
$$;

-- There is ONE irreversible entry per call, not per credential, process owner, lease or execution epoch.
-- Absence of an outcome is unresolved/possibly in progress; never proof of non-entry or permission to re-run.
create table task.resource_execution (
    call_key text primary key references task.resource_call(call_key),
    entry_key text not null unique check (entry_key ~ '^re1-[0-9a-f]{64}$'),
    contract_fingerprint text not null check (contract_fingerprint ~ '^[0-9a-f]{64}$'),
    execution_epoch bigint not null check (execution_epoch > 0),
    first_binding_key text not null references task.resource_call_binding(binding_key),
    entering_executor uuid not null,
    entered_at timestamptz not null default clock_timestamp()
);

create function task.resource_execution_insert_guard() returns trigger language plpgsql as $$
declare invocation task.resource_call%rowtype; r task.instance%rowtype; binding task.resource_call_binding%rowtype;
begin
    select * into invocation from task.resource_call where call_key = new.call_key;
    if not found then raise exception 'resource invocation missing' using errcode = 'foreign_key_violation'; end if;
    select * into r from task.instance where id = invocation.instance_id for update;
    if not found or r.tenant is distinct from invocation.tenant
        or r.owner_kind is distinct from invocation.owner_kind or r.owner_id is distinct from invocation.owner_id
        or r.engine_instance_id is distinct from invocation.engine_instance_id
        or r.definition_id is distinct from (invocation.namespace || '/' || invocation.definition_name)
        or r.definition_version is distinct from invocation.definition_version
        or r.status not in ('RUNNING','WAITING')
        or (r.wait_deadline is not null and r.wait_deadline <= clock_timestamp()) then
        raise exception 'resource execution registry attribution is not current' using errcode = 'check_violation';
    end if;
    if new.contract_fingerprint is distinct from task.resource_call_data_contract(invocation)
        or new.entry_key is distinct from task.resource_execution_identity(new.call_key,new.contract_fingerprint,new.execution_epoch) then
        raise exception 'resource execution contract differs' using errcode = 'check_violation';
    end if;
    select * into binding from task.resource_call_binding where binding_key = new.first_binding_key;
    if not found then raise exception 'resource execution binding missing' using errcode = 'foreign_key_violation'; end if;
    if binding.call_key is distinct from new.call_key or binding.issued_at > clock_timestamp()
        or binding.expires_at <= clock_timestamp() then
        raise exception 'resource execution binding is not current' using errcode = 'check_violation';
    end if;
    -- Database time follows all lock waits. This is not JWT/grant/policy verification or the final dispatch fence.
    new.entered_at = clock_timestamp();
    return new;
end;
$$;
create trigger resource_execution_insert_guard before insert on task.resource_execution
    for each row execute function task.resource_execution_insert_guard();

-- Retained reference only: no raw output, credentials or authority handles. Agent-store UUIDs can be remote;
-- the trusted inspector must prove the exact physical terminal run/checkpoint and content before associating.
create table task.resource_execution_outcome (
    entry_key text primary key references task.resource_execution(entry_key),
    profile text not null check (profile = 'langgraph4j-postgres-saver-1.9.5-v1.1-object-stream'),
    target_fingerprint text not null check (target_fingerprint ~ '^[0-9a-f]{64}$'),
    run_id uuid not null,
    checkpoint_id uuid not null,
    content_type text not null check (content_type = 'application/octet-stream'),
    content_fingerprint text not null check (content_fingerprint ~ '^[0-9a-f]{64}$'),
    observed_at timestamptz not null default clock_timestamp(),
    unique (profile,target_fingerprint,run_id),
    unique (profile,target_fingerprint,checkpoint_id)
);
create function task.resource_execution_outcome_insert_guard() returns trigger language plpgsql as $$
declare entry task.resource_execution%rowtype; invocation task.resource_call%rowtype;
begin
    select * into entry from task.resource_execution where entry_key = new.entry_key;
    if not found then raise exception 'resource execution entry missing' using errcode = 'foreign_key_violation'; end if;
    select * into invocation from task.resource_call where call_key = entry.call_key;
    if not found then raise exception 'resource invocation missing' using errcode = 'foreign_key_violation'; end if;
    if entry.contract_fingerprint is distinct from task.resource_call_data_contract(invocation)
        or new.target_fingerprint is distinct from invocation.target_fingerprint then
        raise exception 'resource execution outcome target differs' using errcode = 'check_violation';
    end if;
    -- UUID identity is scoped to the approved target/backend, not assumed globally unique across copied stores.
    new.observed_at = clock_timestamp();
    return new;
end;
$$;
create trigger resource_execution_outcome_insert_guard before insert on task.resource_execution_outcome
    for each row execute function task.resource_execution_outcome_insert_guard();
-- Outcome facts may be retained after cancellation/expiry. Current authority must still refuse private output.
-- Never attach a live-registry/credential test to historical observation INSERT, erase facts or reset entry.
create function task.resource_execution_no_change() returns trigger language plpgsql as $$
begin
    raise exception 'resource execution facts are immutable and retained' using errcode = 'insufficient_privilege';
end;
$$;
create trigger resource_execution_no_change before update or delete on task.resource_execution
    for each row execute function task.resource_execution_no_change();
create trigger resource_execution_no_truncate before truncate on task.resource_execution
    for each statement execute function task.resource_execution_no_change();
create trigger resource_execution_outcome_no_change before update or delete on task.resource_execution_outcome
    for each row execute function task.resource_execution_no_change();
create trigger resource_execution_outcome_no_truncate before truncate on task.resource_execution_outcome
    for each statement execute function task.resource_execution_no_change();
