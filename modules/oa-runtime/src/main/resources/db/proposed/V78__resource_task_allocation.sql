-- PROPOSED V78; outside Flyway. V77 remains the separately prepared effect-audit source/delivery schema.
-- Requires reviewed V73/V74/V76 and exact allocation/consumer/migration review. DATA, never model permission.
-- No guessed task/grant/profile/backfill. Existing unallocated execution facts block the final validated FK:
-- stop activation and preserve those facts for an explicit reviewed historical-data decision, never delete them.
create function task.ra_text_v1(value text) returns bytea language sql immutable strict as $$
    select int4send(octet_length(convert_to(value,'UTF8'))) || convert_to(value,'UTF8')
$$;

create function task.ra_tokens_v1(values_json jsonb) returns bytea language plpgsql immutable strict as $$
declare encoded bytea; token text;
begin
    if jsonb_typeof(values_json) is distinct from 'array' or jsonb_array_length(values_json)>100 then
        raise exception 'invalid allocation allow-list' using errcode='check_violation';
    end if;
    if exists(select 1 from jsonb_array_elements(values_json) v where jsonb_typeof(v) is distinct from 'string'
              or (v #>> '{}') !~ '^[a-z][a-z0-9_.:-]{0,127}$')
       or (select count(distinct v) from jsonb_array_elements(values_json) v)<>jsonb_array_length(values_json) then
        raise exception 'invalid or repeated allocation allow-list token' using errcode='check_violation';
    end if;
    encoded=int8send(jsonb_array_length(values_json)::bigint);
    for token in select v from jsonb_array_elements_text(values_json) v order by v collate "C" loop
        encoded=encoded || task.ra_text_v1(token);
    end loop;
    return encoded;
end $$;

create function task.ra_contract_hash_v1(c jsonb) returns text language plpgsql immutable strict as $$
declare encoded bytea; iterations bigint; wall bigint; cost bigint;
begin
    if jsonb_typeof(c) is distinct from 'object' or not(c ?& array['id','goal','permittedTools',
        'permittedDataScopes','riskTier','limits','requestingSubject','delegationGrantId'])
       or c-array['id','goal','permittedTools','permittedDataScopes','riskTier','limits',
                  'requestingSubject','delegationGrantId']<>'{}'::jsonb
       or jsonb_typeof(c->'id') is distinct from 'string' or (c->>'id') !~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'
       or jsonb_typeof(c->'goal') is distinct from 'string' or char_length(c->>'goal') not between 1 and 4000
       or btrim(c->>'goal')='' or c->>'riskTier' not in ('LOW','MEDIUM','HIGH')
       or jsonb_typeof(c->'limits') is distinct from 'object'
       or not(c->'limits' ?& array['maxIterations','maxWallClockSeconds','maxCostMicros'])
       or (c->'limits')-array['maxIterations','maxWallClockSeconds','maxCostMicros']<>'{}'::jsonb
       or jsonb_typeof(c->'requestingSubject') is distinct from 'object'
       or not(c->'requestingSubject' ?& array['kind','id'])
       or (c->'requestingSubject')-array['kind','id']<>'{}'::jsonb
       or c#>>'{requestingSubject,kind}' not in ('USER','AGENT','WORKFLOW','SERVICE')
       or jsonb_typeof(c#>'{requestingSubject,id}') is distinct from 'string'
       or char_length(c#>>'{requestingSubject,id}') not between 1 and 256
       or jsonb_typeof(c->'delegationGrantId') is distinct from 'string'
       or (c->>'delegationGrantId') !~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$' then
        raise exception 'invalid allocation task shape' using errcode='check_violation';
    end if;
    if jsonb_typeof(c#>'{limits,maxIterations}') is distinct from 'number'
       or jsonb_typeof(c#>'{limits,maxWallClockSeconds}') is distinct from 'number'
       or jsonb_typeof(c#>'{limits,maxCostMicros}') is distinct from 'number'
       or (c#>>'{limits,maxIterations}') !~ '^[1-9][0-9]*$'
       or (c#>>'{limits,maxWallClockSeconds}') !~ '^[1-9][0-9]*$'
       or (c#>>'{limits,maxCostMicros}') !~ '^[1-9][0-9]*$' then
        raise exception 'invalid allocation limits' using errcode='check_violation';
    end if;
    iterations=(c#>>'{limits,maxIterations}')::bigint; wall=(c#>>'{limits,maxWallClockSeconds}')::bigint;
    cost=(c#>>'{limits,maxCostMicros}')::bigint;
    if iterations not between 1 and 1000 or wall not between 1 and 86400 or cost not between 1 and 1000000000000 then
        raise exception 'allocation limits out of bounds' using errcode='check_violation';
    end if;
    encoded=task.ra_text_v1('oa.agent.task-contract.v1') || task.ra_text_v1(c->>'id') || task.ra_text_v1(c->>'goal')
        || task.ra_tokens_v1(c->'permittedTools') || task.ra_tokens_v1(c->'permittedDataScopes')
        || task.ra_text_v1(c->>'riskTier') || int8send(iterations) || int8send(wall) || int8send(0::bigint)
        || int8send(cost) || task.ra_text_v1(c#>>'{requestingSubject,kind}')
        || task.ra_text_v1(c#>>'{requestingSubject,id}') || task.ra_text_v1(c->>'delegationGrantId');
    return encode(sha256(encoded),'hex');
end $$;

create function task.ra_profile_hash_v1(p jsonb) returns text language plpgsql immutable strict as $$
declare encoded bytea; calls bigint; cost bigint; seconds bigint; nanos bigint; tokens bigint;
begin
    if jsonb_typeof(p) is distinct from 'object' or not(p ?& array['id','version','configurationFingerprint',
        'agent','modelId','maxModelCalls','perCallCeilingMicros','maxCallSeconds','maxCallNanos','maxOutputTokens'])
       or p-array['id','version','configurationFingerprint','agent','modelId','maxModelCalls',
        'perCallCeilingMicros','maxCallSeconds','maxCallNanos','maxOutputTokens']<>'{}'::jsonb
       or jsonb_typeof(p->'id') is distinct from 'string' or char_length(p->>'id') not between 1 and 256
       or jsonb_typeof(p->'version') is distinct from 'string' or char_length(p->>'version') not between 1 and 128
       or jsonb_typeof(p->'modelId') is distinct from 'string' or char_length(p->>'modelId') not between 1 and 128
       or btrim(p->>'id')='' or btrim(p->>'version')='' or btrim(p->>'modelId')=''
       or jsonb_typeof(p->'configurationFingerprint') is distinct from 'string'
       or (p->>'configurationFingerprint') !~ '^[0-9a-f]{64}$'
       or jsonb_typeof(p->'agent') is distinct from 'object' or not(p->'agent' ?& array['kind','id'])
       or (p->'agent')-array['kind','id']<>'{}'::jsonb or p#>>'{agent,kind}' is distinct from 'AGENT'
       or jsonb_typeof(p#>'{agent,id}') is distinct from 'string' or char_length(p#>>'{agent,id}') not between 1 and 256 then
        raise exception 'invalid allocation model profile' using errcode='check_violation';
    end if;
    if exists(select 1 from unnest(array['maxModelCalls','perCallCeilingMicros','maxCallSeconds','maxCallNanos','maxOutputTokens']) k
              where jsonb_typeof(p->k) is distinct from 'number' or (p->>k) !~ '^(0|[1-9][0-9]*)$') then
        raise exception 'invalid allocation model limits' using errcode='check_violation';
    end if;
    calls=(p->>'maxModelCalls')::bigint; cost=(p->>'perCallCeilingMicros')::bigint;
    seconds=(p->>'maxCallSeconds')::bigint; nanos=(p->>'maxCallNanos')::bigint; tokens=(p->>'maxOutputTokens')::bigint;
    if calls not between 1 and 10000 or cost not between 1 and 1000000000000 or seconds not between 1 and 86400
       or nanos not between 0 and 999999999 or (seconds=86400 and nanos<>0) or tokens not between 1 and 32768 then
        raise exception 'allocation model limits out of bounds' using errcode='check_violation';
    end if;
    encoded=task.ra_text_v1('oa.model.approved-profile.v1') || task.ra_text_v1(p->>'id') || task.ra_text_v1(p->>'version')
        || task.ra_text_v1(p->>'configurationFingerprint') || task.ra_text_v1(p#>>'{agent,kind}')
        || task.ra_text_v1(p#>>'{agent,id}') || task.ra_text_v1(p->>'modelId') || int8send(calls) || int8send(cost)
        || int8send(seconds) || int8send(nanos) || int8send(tokens);
    return encode(sha256(encoded),'hex');
end $$;

create function task.ra_allocation_hash_v1(call_key text, call_fingerprint text, parent_task_id text,
    contract_fingerprint text, profile_fingerprint text) returns text language sql immutable as $$
    select encode(sha256(task.ra_text_v1('oa.resource.task-allocation-contract.v1')||task.ra_text_v1(call_key)
        ||task.ra_text_v1(call_fingerprint)||case when parent_task_id is null then decode('00','hex')
          else decode('01','hex')||task.ra_text_v1(parent_task_id) end
        ||task.ra_text_v1(contract_fingerprint)||task.ra_text_v1(profile_fingerprint)),'hex')
$$;

create table task.resource_task_allocation (
    allocation_key text primary key check(allocation_key ~ '^ra1-[0-9a-f]{64}$'),
    call_key text not null unique references task.resource_call(call_key),
    call_fingerprint text not null check(call_fingerprint ~ '^[0-9a-f]{64}$'),
    parent_task_id text references task.agent_task(id),
    contract_id text not null unique check(contract_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    contract jsonb not null check(octet_length(contract::text)<=65536),
    contract_fingerprint text not null check(contract_fingerprint ~ '^[0-9a-f]{64}$'),
    grant_id text generated always as (contract->>'delegationGrantId') stored references identity.delegation_grant(id),
    model_profile jsonb not null check(octet_length(model_profile::text)<=16384),
    profile_fingerprint text not null check(profile_fingerprint ~ '^[0-9a-f]{64}$'),
    fingerprint text not null check(fingerprint ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null,
    deadline timestamptz not null check(deadline>created_at)
);
create index resource_allocation_parent_idx on task.resource_task_allocation(parent_task_id) where parent_task_id is not null;
create index resource_allocation_grant_idx on task.resource_task_allocation(grant_id);

create function task.resource_task_allocation_insert_guard() returns trigger language plpgsql as $$
declare invocation task.resource_call%rowtype; registry task.instance%rowtype; parent task.agent_task%rowtype;
        chosen identity.delegation_grant%rowtype; ancestor identity.delegation_grant%rowtype;
        ancestor_ids text[]; computed_contract text; computed_profile text; expected text; encoded bytea;
        maximum_deadline timestamptz; parent_deadline timestamptz; clock_value timestamptz;
begin
    select * into invocation from task.resource_call where call_key=new.call_key;
    if not found then raise exception 'allocation call missing' using errcode='foreign_key_violation'; end if;
    select * into registry from task.instance where id=invocation.instance_id for update;
    if not found or registry.tenant is distinct from invocation.tenant
       or registry.owner_kind is distinct from invocation.owner_kind or registry.owner_id is distinct from invocation.owner_id
       or registry.definition_id is distinct from invocation.namespace||'/'||invocation.definition_name
       or registry.definition_version is distinct from invocation.definition_version
       or registry.engine_instance_id is distinct from invocation.engine_instance_id or registry.status not in ('RUNNING','WAITING')
       or not exists(select 1 from task.resource_occurrence where call_key=new.call_key
                     and occurrence_key=task.resource_occurrence_key(invocation)) then
        raise exception 'allocation registry or occurrence differs' using errcode='check_violation';
    end if;
    if new.created_at is not null or new.deadline is not null
       or exists(select 1 from task.resource_execution where call_key=new.call_key) then
        raise exception 'new allocation requires database time before execution entry' using errcode='check_violation';
    end if;
    if new.call_fingerprint is distinct from task.resource_call_data_contract(invocation)
       or new.contract_id is distinct from new.contract->>'id'
       or new.contract_id is not distinct from new.parent_task_id
       or new.contract#>>'{requestingSubject,kind}' is distinct from invocation.owner_kind
       or new.contract#>>'{requestingSubject,id}' is distinct from invocation.owner_id then
        raise exception 'allocation contract attribution differs' using errcode='check_violation';
    end if;
    computed_contract=task.ra_contract_hash_v1(new.contract); computed_profile=task.ra_profile_hash_v1(new.model_profile);
    if new.contract_fingerprint is distinct from computed_contract or new.profile_fingerprint is distinct from computed_profile
       or (new.model_profile->>'perCallCeilingMicros')::bigint>(new.contract#>>'{limits,maxCostMicros}')::bigint
       or (new.model_profile->>'maxCallSeconds')::numeric+(new.model_profile->>'maxCallNanos')::numeric/1000000000
          >(new.contract#>>'{limits,maxWallClockSeconds}')::numeric then
        raise exception 'allocation task/profile fingerprint or bounds differs' using errcode='check_violation';
    end if;
    expected='ra1-'||encode(sha256(task.ra_text_v1('oa.resource.task-allocation.v1')||task.ra_text_v1(new.call_key)),'hex');
    if new.allocation_key is distinct from expected or new.fingerprint is distinct from
        task.ra_allocation_hash_v1(new.call_key,new.call_fingerprint,new.parent_task_id,computed_contract,computed_profile) then
        raise exception 'allocation canonical fingerprint differs' using errcode='check_violation';
    end if;
    with recursive ancestry as (
        select id,parent_grant_id from identity.delegation_grant where id=new.contract->>'delegationGrantId'
        union select g.id,g.parent_grant_id from identity.delegation_grant g join ancestry a on g.id=a.parent_grant_id
    ) select array_agg(id) into ancestor_ids from ancestry;
    if coalesce(cardinality(ancestor_ids),0) not between 1 and 8
       or not exists(select 1 from task.resource_call_binding where call_key=new.call_key and grant_id=any(ancestor_ids)) then
        raise exception 'allocation grant is not an actual issuing/narrowed chain' using errcode='check_violation';
    end if;
    -- Fixed order for shared grant locks. Read status AFTER lock acquisition; expiry is bounded again by final DB time.
    for ancestor in select * from identity.delegation_grant where id=any(ancestor_ids) order by id for share loop
        if ancestor.tenant is distinct from invocation.tenant or ancestor.subject_kind is distinct from invocation.owner_kind
           or ancestor.subject_id is distinct from invocation.owner_id or ancestor.status<>'ACTIVE' then
            raise exception 'allocation grant ancestry is not current' using errcode='check_violation';
        end if;
        maximum_deadline=least(maximum_deadline,ancestor.expires_at);
        if ancestor.id=new.contract->>'delegationGrantId' then chosen=ancestor; end if;
    end loop;
    if chosen.id is null or chosen.agent_kind is distinct from new.model_profile#>>'{agent,kind}'
       or chosen.agent_id is distinct from new.model_profile#>>'{agent,id}' then
        raise exception 'allocation model agent differs from actual grant' using errcode='check_violation';
    end if;
    if new.parent_task_id is not null then
        select * into parent from task.agent_task where id=new.parent_task_id for share;
        if not found or parent.instance_id is distinct from invocation.instance_id or parent.tenant is distinct from invocation.tenant
           or parent.owner_kind is distinct from invocation.owner_kind or parent.owner_id is distinct from invocation.owner_id
           or parent.status not in ('PENDING','RUNNING','WAITING_FOR_HUMAN','RESUMING')
           or parent.created_at is null or parent.created_at>clock_timestamp()
           or parent.contract->>'id' is distinct from parent.id
           or parent.contract#>>'{requestingSubject,kind}' is distinct from parent.owner_kind
           or parent.contract#>>'{requestingSubject,id}' is distinct from parent.owner_id
           or parent.thread_name is distinct from 'ws1-'||encode(sha256(task.ra_text_v1('oa.thread.ws1')
               ||task.ra_text_v1(parent.tenant)||task.ra_text_v1(parent.owner_id)||task.ra_text_v1(parent.instance_id)),'hex') then
            raise exception 'allocation parent task differs or is closed' using errcode='check_violation';
        end if;
        perform task.ra_contract_hash_v1(parent.contract);
        if not((new.contract->'permittedTools') <@ (parent.contract->'permittedTools'))
           or not((new.contract->'permittedDataScopes') <@ (parent.contract->'permittedDataScopes'))
           or array_position(array['LOW','MEDIUM','HIGH'],new.contract->>'riskTier')<array_position(array['LOW','MEDIUM','HIGH'],parent.contract->>'riskTier')
           or (new.contract#>>'{limits,maxIterations}')::bigint>(parent.contract#>>'{limits,maxIterations}')::bigint
           or (new.contract#>>'{limits,maxWallClockSeconds}')::bigint>(parent.contract#>>'{limits,maxWallClockSeconds}')::bigint
           or (new.contract#>>'{limits,maxCostMicros}')::bigint>(parent.contract#>>'{limits,maxCostMicros}')::bigint
           or not(parent.contract->>'delegationGrantId'=any(ancestor_ids)) then
            raise exception 'allocation widens actual parent task/grant' using errcode='check_violation';
        end if;
        parent_deadline=parent.created_at+make_interval(secs=>(parent.contract#>>'{limits,maxWallClockSeconds}')::double precision);
    end if;
    clock_value=clock_timestamp();
    new.created_at=clock_value;
    new.deadline=least(clock_value+make_interval(secs=>(new.contract#>>'{limits,maxWallClockSeconds}')::double precision),
        maximum_deadline,parent_deadline,registry.wait_deadline);
    if new.deadline is null or new.deadline<=clock_value then
        raise exception 'allocation original lifetime is exhausted' using errcode='check_violation';
    end if;
    return new;
end $$;
create trigger resource_task_allocation_insert_guard before insert on task.resource_task_allocation
    for each row execute function task.resource_task_allocation_insert_guard();
create trigger resource_task_allocation_no_change before update or delete on task.resource_task_allocation
    for each row execute function task.resource_call_no_change();
create trigger resource_task_allocation_no_truncate before truncate on task.resource_task_allocation
    for each statement execute function task.resource_call_no_change();

-- Validated prerequisite: never start an execution with an absent approved allocation. No fake legacy backfill.
alter table task.resource_execution add constraint resource_execution_allocation_fk
    foreign key(call_key) references task.resource_task_allocation(call_key);
