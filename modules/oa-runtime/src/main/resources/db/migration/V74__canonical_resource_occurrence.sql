-- Additive after V73. Existing call keys and immutable metadata are NEVER rewritten.
-- Complete business occurrence comes from the trusted durable producer, not the live SDK retry counter.
create function task.resource_occurrence_key(invocation task.resource_call) returns text
    language plpgsql immutable strict as $$
declare encoded bytea = ''::bytea; value text; bytes bytea;
begin
    foreach value in array array['oa.resource.occurrence.v1', invocation.tenant, invocation.instance_id,
        invocation.owner_kind, invocation.owner_id, invocation.application_id, invocation.engine_instance_id,
        invocation.namespace, invocation.definition_name, invocation.definition_version,
        invocation.task_position, invocation.logical_occurrence] loop
        if value is null then
            raise exception 'resource occurrence field is missing' using errcode = 'check_violation';
        end if;
        bytes = convert_to(value, 'UTF8');
        encoded = encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    return 'ro1-' || encode(sha256(encoded), 'hex');
end;
$$;

create table task.resource_occurrence (
    occurrence_key text primary key check (occurrence_key ~ '^ro1-[0-9a-f]{64}$'),
    call_key text not null unique references task.resource_call(call_key),
    created_at timestamptz not null default clock_timestamp()
);

create function task.resource_occurrence_insert_guard() returns trigger language plpgsql as $$
declare invocation task.resource_call%rowtype;
begin
    select * into invocation from task.resource_call where call_key = new.call_key;
    if not found or new.occurrence_key is distinct from task.resource_occurrence_key(invocation) then
        raise exception 'resource occurrence attribution differs' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger resource_occurrence_insert_guard before insert on task.resource_occurrence
    for each row execute function task.resource_occurrence_insert_guard();

-- A conflict in historical data FAILS the migration. Never select a winner or change/delete either original call.
insert into task.resource_occurrence(occurrence_key, call_key)
    select task.resource_occurrence_key(invocation), invocation.call_key from task.resource_call invocation;

create function task.resource_call_anchor_occurrence() returns trigger language plpgsql as $$
begin
    insert into task.resource_occurrence(occurrence_key, call_key)
        values(task.resource_occurrence_key(new::task.resource_call), new.call_key);
    return new;
end;
$$;
create trigger resource_call_anchor_occurrence after insert on task.resource_call
    for each row execute function task.resource_call_anchor_occurrence();

create function task.resource_occurrence_no_change() returns trigger language plpgsql as $$
begin
    raise exception 'resource occurrence is immutable and retained' using errcode = 'insufficient_privilege';
end;
$$;
create trigger resource_occurrence_no_change before update or delete on task.resource_occurrence
    for each row execute function task.resource_occurrence_no_change();
create trigger resource_occurrence_no_truncate before truncate on task.resource_occurrence
    for each statement execute function task.resource_occurrence_no_change();
