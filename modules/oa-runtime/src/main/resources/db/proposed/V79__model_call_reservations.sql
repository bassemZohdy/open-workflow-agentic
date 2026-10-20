-- PROPOSED V79; outside Flyway after V78 and separately prepared V77. No consumer/model/CDI activation.
-- Full-ceiling MODEL allowances only, not measured spend. Retain UNKNOWN/no-response reservations; no refund/reset.
create function task.mb_time_v1(t timestamptz) returns bytea language sql immutable strict as $$
    select int8send(floor(extract(epoch from t))::bigint)
        ||int8send(((extract(epoch from t)-floor(extract(epoch from t)))*1000000000)::bigint)
$$;
create function task.mb_key_v1(t text,i text,k text,o text,kind text,anchor text) returns text language sql immutable strict as $$
    select 'mb1-'||encode(sha256(task.ra_text_v1('oa.model.budget-account.v1')||task.ra_text_v1(t)||task.ra_text_v1(i)
        ||task.ra_text_v1(k)||task.ra_text_v1(o)||task.ra_text_v1(kind)||task.ra_text_v1(anchor)),'hex')
$$;
create function task.mb_fingerprint_v1(k text,c text,maximum bigint,created timestamptz,deadline timestamptz)
    returns text language sql immutable strict as $$
    select encode(sha256(task.ra_text_v1('oa.model.budget-account-contract.v1')||task.ra_text_v1(k)||task.ra_text_v1(c)
        ||int8send(maximum)||task.mb_time_v1(created)||task.mb_time_v1(deadline)),'hex')
$$;
create function task.mn_key_v1(call_key text,node text) returns text language sql immutable strict as $$
    select 'mn1-'||encode(sha256(task.ra_text_v1('oa.resource.model-node.v1')||task.ra_text_v1(call_key)||task.ra_text_v1(node)),'hex')
$$;
create function task.mn_fingerprint_v1(k text,entry text,executor uuid,request text,tokens integer)
    returns text language sql immutable strict as $$
    select encode(sha256(task.ra_text_v1('oa.resource.model-node-contract.v1')||task.ra_text_v1(k)||task.ra_text_v1(entry)
        ||task.ra_text_v1(executor::text)||task.ra_text_v1(request)||int8send(tokens::bigint)),'hex')
$$;
create function task.mr_fingerprint_v1(k text,intent text,allocation text,allocation_created timestamptz,
    allocation_deadline timestamptz,account text,account_fingerprint text,held bigint,leaf_remaining bigint,
    root_remaining bigint,ordinal integer,calls_remaining integer,reserved timestamptz,deadline timestamptz)
    returns text language sql immutable strict as $$
    select encode(sha256(task.ra_text_v1('oa.model.reservation.v1')||task.ra_text_v1(k)||task.ra_text_v1(intent)
        ||task.ra_text_v1(allocation)||task.mb_time_v1(allocation_created)||task.mb_time_v1(allocation_deadline)
        ||task.ra_text_v1(account)||task.ra_text_v1(account_fingerprint)||int8send(held)||int8send(leaf_remaining)
        ||int8send(root_remaining)||int8send(ordinal::bigint)||int8send(calls_remaining::bigint)
        ||task.mb_time_v1(reserved)||task.mb_time_v1(deadline)),'hex')
$$;

create table task.model_budget_account (
    account_key text primary key check(account_key ~ '^mb1-[0-9a-f]{64}$'),
    tenant text not null,
    instance_id text not null references task.instance(id),
    owner_kind text not null check(owner_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    owner_id text not null,
    anchor_kind text not null check(anchor_kind in ('TASK','ALLOCATION')),
    anchor_id text not null,
    source_contract_fingerprint text not null check(source_contract_fingerprint ~ '^[0-9a-f]{64}$'),
    max_cost_micros bigint not null check(max_cost_micros between 1 and 1000000000000),
    created_at timestamptz not null,
    deadline timestamptz not null check(deadline>created_at),
    fingerprint text not null check(fingerprint ~ '^[0-9a-f]{64}$'),
    parent_task_id text generated always as (case when anchor_kind='TASK' then anchor_id end) stored references task.agent_task(id),
    root_allocation_key text generated always as (case when anchor_kind='ALLOCATION' then anchor_id end) stored references task.resource_task_allocation(allocation_key),
    unique(tenant,instance_id,owner_kind,owner_id,anchor_kind,anchor_id)
);
create table task.model_call_reservation (
    reservation_key text primary key check(reservation_key ~ '^mn1-[0-9a-f]{64}$'),
    call_key text not null references task.resource_call(call_key),
    entry_key text not null references task.resource_execution(entry_key),
    executor uuid not null,
    node_occurrence text not null check(char_length(node_occurrence) between 1 and 1024),
    request_fingerprint text not null check(request_fingerprint ~ '^[0-9a-f]{64}$'),
    max_output_tokens integer not null check(max_output_tokens between 1 and 32768),
    intent_fingerprint text not null check(intent_fingerprint ~ '^[0-9a-f]{64}$'),
    allocation_key text not null references task.resource_task_allocation(allocation_key),
    allocation_fingerprint text not null check(allocation_fingerprint ~ '^[0-9a-f]{64}$'),
    account_key text not null references task.model_budget_account(account_key),
    account_fingerprint text not null check(account_fingerprint ~ '^[0-9a-f]{64}$'),
    held_micros bigint not null check(held_micros between 1 and 1000000000000),
    allocation_remaining_after bigint not null check(allocation_remaining_after between 0 and 1000000000000),
    account_remaining_after bigint not null check(account_remaining_after between 0 and 1000000000000),
    ordinal integer not null check(ordinal between 1 and 10000),
    calls_remaining integer not null check(calls_remaining between 0 and 9999),
    reserved_at timestamptz not null,
    call_deadline timestamptz not null check(call_deadline>reserved_at),
    fingerprint text not null check(fingerprint ~ '^[0-9a-f]{64}$'),
    unique(allocation_key,ordinal)
);
create index model_reservation_allocation_idx on task.model_call_reservation(allocation_key);
create index model_reservation_account_idx on task.model_call_reservation(account_key);
create index model_reservation_entry_idx on task.model_call_reservation(entry_key);

create function task.model_budget_account_guard() returns trigger language plpgsql as $$
declare registry task.instance%rowtype; parent task.agent_task%rowtype; a task.resource_task_allocation%rowtype;
        invocation task.resource_call%rowtype; source jsonb; source_deadline timestamptz; clock_value timestamptz;
begin
    select * into registry from task.instance where id=new.instance_id for update;
    if not found or registry.tenant is distinct from new.tenant or registry.owner_kind is distinct from new.owner_kind
       or registry.owner_id is distinct from new.owner_id or registry.status not in ('RUNNING','WAITING') then
        raise exception 'model budget registry differs' using errcode='check_violation';
    end if;
    if new.source_contract_fingerprint is not null or new.max_cost_micros is not null or new.created_at is not null
       or new.deadline is not null or new.fingerprint is not null then
        raise exception 'model budget source fields are database assigned' using errcode='check_violation';
    end if;
    if new.anchor_kind='TASK' then
        select * into parent from task.agent_task where id=new.anchor_id for share;
        if not found or parent.tenant is distinct from new.tenant or parent.instance_id is distinct from new.instance_id
           or parent.owner_kind is distinct from new.owner_kind or parent.owner_id is distinct from new.owner_id
           or parent.status not in ('RUNNING','RESUMING') or parent.created_at is null or parent.created_at>clock_timestamp()
           or parent.contract->>'id' is distinct from parent.id
           or parent.contract#>>'{requestingSubject,kind}' is distinct from parent.owner_kind
           or parent.contract#>>'{requestingSubject,id}' is distinct from parent.owner_id
           or parent.thread_name is distinct from 'ws1-'||encode(sha256(task.ra_text_v1('oa.thread.ws1')
                ||task.ra_text_v1(parent.tenant)||task.ra_text_v1(parent.owner_id)||task.ra_text_v1(parent.instance_id)),'hex') then
            raise exception 'model budget parent task differs' using errcode='check_violation';
        end if;
        source=parent.contract;
        source_deadline=parent.created_at+make_interval(secs=>(source#>>'{limits,maxWallClockSeconds}')::double precision);
    elsif new.anchor_kind='ALLOCATION' then
        select * into a from task.resource_task_allocation where allocation_key=new.anchor_id;
        if not found or a.parent_task_id is not null then
            raise exception 'model budget allocation anchor differs' using errcode='check_violation';
        end if;
        select * into invocation from task.resource_call where call_key=a.call_key;
        if not found or invocation.tenant is distinct from new.tenant or invocation.instance_id is distinct from new.instance_id
           or invocation.owner_kind is distinct from new.owner_kind or invocation.owner_id is distinct from new.owner_id then
            raise exception 'model budget allocation scope differs' using errcode='check_violation';
        end if;
        source=a.contract;source_deadline=a.deadline;
    else raise exception 'model budget anchor kind differs' using errcode='check_violation';
    end if;
    if new.account_key is distinct from task.mb_key_v1(new.tenant,new.instance_id,new.owner_kind,new.owner_id,new.anchor_kind,new.anchor_id) then
        raise exception 'model budget identity differs' using errcode='check_violation';
    end if;
    new.source_contract_fingerprint=task.ra_contract_hash_v1(source);
    new.max_cost_micros=(source#>>'{limits,maxCostMicros}')::bigint;
    clock_value=clock_timestamp();new.created_at=clock_value;new.deadline=source_deadline;
    if new.deadline<=clock_value or registry.wait_deadline<=clock_value then
        raise exception 'model budget original lifetime ended' using errcode='check_violation';
    end if;
    new.fingerprint=task.mb_fingerprint_v1(new.account_key,new.source_contract_fingerprint,new.max_cost_micros,new.created_at,new.deadline);
    return new;
end $$;
create trigger model_budget_account_guard before insert on task.model_budget_account
    for each row execute function task.model_budget_account_guard();

-- Called in short owned transactions; no JDBC connection is held across policy/audit/provider callbacks.
-- This validates retained metadata, not signed identity, registered approval, model scope or actual provider pricing.
create function task.model_call_current_v1(call_identity text,allocation_contract text,execution_identity text,
    execution_owner uuid,budget_identity text) returns timestamptz language plpgsql as $$
declare invocation task.resource_call%rowtype; registry task.instance%rowtype; a task.resource_task_allocation%rowtype;
        e task.resource_execution%rowtype; b task.model_budget_account%rowtype; parent task.agent_task%rowtype;
        g identity.delegation_grant%rowtype; ancestry text[]; chosen identity.delegation_grant%rowtype;
        clock_value timestamptz; expected_anchor text; expected_kind text; contract_hash text; profile_hash text;
        root_contract jsonb; root_deadline timestamptz;
begin
    select * into invocation from task.resource_call where call_key=call_identity;
    if not found then raise exception 'model call source missing' using errcode='foreign_key_violation';end if;
    select * into registry from task.instance where id=invocation.instance_id for update;
    if not found or registry.tenant is distinct from invocation.tenant or registry.owner_kind is distinct from invocation.owner_kind
       or registry.owner_id is distinct from invocation.owner_id or registry.definition_id is distinct from invocation.namespace||'/'||invocation.definition_name
       or registry.definition_version is distinct from invocation.definition_version or registry.engine_instance_id is distinct from invocation.engine_instance_id
       or registry.status not in ('RUNNING','WAITING') or not exists(select 1 from task.resource_occurrence where call_key=call_identity
            and occurrence_key=task.resource_occurrence_key(invocation)) then
        raise exception 'model call registry/occurrence differs' using errcode='check_violation';
    end if;
    perform task.resource_call_data_contract(invocation);
    select * into a from task.resource_task_allocation where call_key=call_identity;
    if not found then raise exception 'model allocation missing' using errcode='foreign_key_violation';end if;
    contract_hash=task.ra_contract_hash_v1(a.contract);profile_hash=task.ra_profile_hash_v1(a.model_profile);
    if a.fingerprint is distinct from allocation_contract or a.call_fingerprint is distinct from invocation.fingerprint
       or a.contract_fingerprint is distinct from contract_hash or a.profile_fingerprint is distinct from profile_hash
       or a.fingerprint is distinct from task.ra_allocation_hash_v1(a.call_key,a.call_fingerprint,a.parent_task_id,contract_hash,profile_hash)
       or a.contract#>>'{requestingSubject,kind}' is distinct from invocation.owner_kind
       or a.contract#>>'{requestingSubject,id}' is distinct from invocation.owner_id then
        raise exception 'model allocation data differs' using errcode='check_violation';
    end if;
    select * into e from task.resource_execution where call_key=call_identity;
    if not found or e.entry_key is distinct from execution_identity or e.entering_executor is distinct from execution_owner
       or e.contract_fingerprint is distinct from invocation.fingerprint
       or e.entry_key is distinct from task.resource_execution_identity(invocation.call_key,invocation.fingerprint,e.execution_epoch) then
        raise exception 'model execution entry/owner differs' using errcode='check_violation';
    end if;
    with recursive chain as (
        select id,parent_grant_id from identity.delegation_grant where id=a.grant_id
        union select ancestor_row.id,ancestor_row.parent_grant_id from identity.delegation_grant ancestor_row join chain x on ancestor_row.id=x.parent_grant_id
    ) select array_agg(id) into ancestry from chain;
    if coalesce(cardinality(ancestry),0) not between 1 and 8
       or not exists(select 1 from task.resource_call_binding where call_key=call_identity and grant_id=any(ancestry)) then
        raise exception 'model grant ancestry differs' using errcode='check_violation';
    end if;
    for g in select * from identity.delegation_grant where id=any(ancestry) order by id for share loop
        if g.tenant is distinct from invocation.tenant or g.subject_kind is distinct from invocation.owner_kind
           or g.subject_id is distinct from invocation.owner_id or g.status<>'ACTIVE' or g.expires_at<=clock_timestamp() then
            raise exception 'model grant ancestry ended' using errcode='check_violation';
        end if;
        if g.id=a.grant_id then chosen=g;end if;
    end loop;
    if chosen.id is null or chosen.agent_kind is distinct from a.model_profile#>>'{agent,kind}'
       or chosen.agent_id is distinct from a.model_profile#>>'{agent,id}' then
        raise exception 'model profile delegate differs' using errcode='check_violation';
    end if;
    if a.parent_task_id is not null then
        select * into parent from task.agent_task where id=a.parent_task_id for share;
        if not found or parent.tenant is distinct from invocation.tenant or parent.instance_id is distinct from invocation.instance_id
           or parent.owner_kind is distinct from invocation.owner_kind or parent.owner_id is distinct from invocation.owner_id
           or parent.status not in ('RUNNING','RESUMING') or parent.created_at is null or parent.created_at>clock_timestamp()
           or parent.contract->>'id' is distinct from parent.id
           or parent.contract#>>'{requestingSubject,kind}' is distinct from parent.owner_kind
           or parent.contract#>>'{requestingSubject,id}' is distinct from parent.owner_id
           or parent.thread_name is distinct from 'ws1-'||encode(sha256(task.ra_text_v1('oa.thread.ws1')||task.ra_text_v1(parent.tenant)
                ||task.ra_text_v1(parent.owner_id)||task.ra_text_v1(parent.instance_id)),'hex') then
            raise exception 'model parent task ended or differs' using errcode='check_violation';
        end if;
        perform task.ra_contract_hash_v1(parent.contract);
        if not((a.contract->'permittedTools') <@ (parent.contract->'permittedTools'))
           or not((a.contract->'permittedDataScopes') <@ (parent.contract->'permittedDataScopes'))
           or array_position(array['LOW','MEDIUM','HIGH'],a.contract->>'riskTier')<array_position(array['LOW','MEDIUM','HIGH'],parent.contract->>'riskTier')
           or (a.contract#>>'{limits,maxIterations}')::bigint>(parent.contract#>>'{limits,maxIterations}')::bigint
           or (a.contract#>>'{limits,maxWallClockSeconds}')::bigint>(parent.contract#>>'{limits,maxWallClockSeconds}')::bigint
           or (a.contract#>>'{limits,maxCostMicros}')::bigint>(parent.contract#>>'{limits,maxCostMicros}')::bigint
           or not(parent.contract->>'delegationGrantId'=any(ancestry)) then
            raise exception 'model allocation widens parent' using errcode='check_violation';
        end if;
        expected_kind='TASK';expected_anchor=parent.id;root_contract=parent.contract;
        root_deadline=parent.created_at+make_interval(secs=>(parent.contract#>>'{limits,maxWallClockSeconds}')::double precision);
    else expected_kind='ALLOCATION';expected_anchor=a.allocation_key;root_contract=a.contract;root_deadline=a.deadline;
    end if;
    select * into b from task.model_budget_account where account_key=budget_identity;
    if not found then raise exception 'model budget account missing' using errcode='foreign_key_violation';end if;
    if b.account_key is distinct from task.mb_key_v1(invocation.tenant,invocation.instance_id,invocation.owner_kind,invocation.owner_id,expected_kind,expected_anchor)
       or b.tenant is distinct from invocation.tenant or b.instance_id is distinct from invocation.instance_id
       or b.owner_kind is distinct from invocation.owner_kind or b.owner_id is distinct from invocation.owner_id
       or b.anchor_kind is distinct from expected_kind or b.anchor_id is distinct from expected_anchor
       or b.source_contract_fingerprint is distinct from task.ra_contract_hash_v1(root_contract)
       or b.max_cost_micros is distinct from (root_contract#>>'{limits,maxCostMicros}')::bigint or b.deadline is distinct from root_deadline
       or b.fingerprint is distinct from task.mb_fingerprint_v1(b.account_key,b.source_contract_fingerprint,b.max_cost_micros,b.created_at,b.deadline) then
        raise exception 'model budget source changed or differs' using errcode='check_violation';
    end if;
    clock_value=clock_timestamp();
    if a.deadline<=clock_value or b.deadline<=clock_value or registry.wait_deadline<=clock_value then
        raise exception 'model original lifetime ended' using errcode='check_violation';
    end if;
    return clock_value;
end $$;

create function task.model_reservation_insert_guard() returns trigger language plpgsql as $$
declare a task.resource_task_allocation%rowtype; b task.model_budget_account%rowtype;
        clock_value timestamptz; leaf_held bigint; root_held bigint; used_calls integer; ceiling bigint; calls integer;
        timeout_micros bigint;
begin
    if new.account_fingerprint is not null or new.held_micros is not null or new.allocation_remaining_after is not null
       or new.account_remaining_after is not null or new.ordinal is not null or new.calls_remaining is not null
       or new.reserved_at is not null or new.call_deadline is not null or new.fingerprint is not null then
        raise exception 'model reservation accounting is database assigned' using errcode='check_violation';
    end if;
    clock_value=task.model_call_current_v1(new.call_key,new.allocation_fingerprint,new.entry_key,new.executor,new.account_key);
    select * into a from task.resource_task_allocation where call_key=new.call_key;
    select * into b from task.model_budget_account where account_key=new.account_key;
    if new.allocation_key is distinct from a.allocation_key or new.reservation_key is distinct from task.mn_key_v1(new.call_key,new.node_occurrence)
       or new.intent_fingerprint is distinct from task.mn_fingerprint_v1(new.reservation_key,new.entry_key,new.executor,new.request_fingerprint,new.max_output_tokens)
       or new.max_output_tokens>(a.model_profile->>'maxOutputTokens')::integer then
        raise exception 'model node request attribution differs' using errcode='check_violation';
    end if;
    -- Validate retained source/charge facts before deriving balances. No mutable JVM/caller counter.
    if exists(select 1 from task.model_call_reservation r
        left join task.resource_task_allocation x on x.allocation_key=r.allocation_key
        left join task.resource_execution e on e.entry_key=r.entry_key
        where r.account_key=b.account_key and (x.allocation_key is null or e.entry_key is null
          or r.call_key is distinct from x.call_key or r.call_key is distinct from e.call_key or r.executor is distinct from e.entering_executor
          or r.allocation_fingerprint is distinct from x.fingerprint or r.held_micros is distinct from (x.model_profile->>'perCallCeilingMicros')::bigint
          or r.account_fingerprint is distinct from b.fingerprint
          or r.reservation_key is distinct from task.mn_key_v1(r.call_key,r.node_occurrence)
          or r.intent_fingerprint is distinct from task.mn_fingerprint_v1(r.reservation_key,r.entry_key,r.executor,r.request_fingerprint,r.max_output_tokens)
          or r.fingerprint is distinct from task.mr_fingerprint_v1(r.reservation_key,r.intent_fingerprint,x.fingerprint,x.created_at,x.deadline,
                b.account_key,b.fingerprint,r.held_micros,r.allocation_remaining_after,r.account_remaining_after,r.ordinal,r.calls_remaining,r.reserved_at,r.call_deadline))) then
        raise exception 'retained model reservation data differs' using errcode='check_violation';
    end if;
    select coalesce(sum(held_micros),0)::bigint,count(*)::integer into leaf_held,used_calls
        from task.model_call_reservation where allocation_key=a.allocation_key;
    select coalesce(sum(held_micros),0)::bigint into root_held from task.model_call_reservation where account_key=b.account_key;
    ceiling=(a.model_profile->>'perCallCeilingMicros')::bigint;calls=(a.model_profile->>'maxModelCalls')::integer;
    if leaf_held>(a.contract#>>'{limits,maxCostMicros}')::bigint or root_held>b.max_cost_micros or used_calls>calls then
        raise exception 'retained model budget exceeds original source' using errcode='check_violation';
    end if;
    if ceiling>(a.contract#>>'{limits,maxCostMicros}')::bigint-leaf_held or ceiling>b.max_cost_micros-root_held or used_calls>=calls then
        raise exception 'model reservation allowance exhausted' using errcode='OA001';
    end if;
    timeout_micros=(a.model_profile->>'maxCallSeconds')::bigint*1000000+floor((a.model_profile->>'maxCallNanos')::numeric/1000)::bigint;
    new.account_fingerprint=b.fingerprint;new.held_micros=ceiling;
    new.allocation_remaining_after=(a.contract#>>'{limits,maxCostMicros}')::bigint-leaf_held-ceiling;
    new.account_remaining_after=b.max_cost_micros-root_held-ceiling;new.ordinal=used_calls+1;new.calls_remaining=calls-new.ordinal;
    new.reserved_at=clock_value;new.call_deadline=least(a.deadline,b.deadline,clock_value+timeout_micros*interval '1 microsecond');
    new.fingerprint=task.mr_fingerprint_v1(new.reservation_key,new.intent_fingerprint,a.fingerprint,a.created_at,a.deadline,
        b.account_key,b.fingerprint,new.held_micros,new.allocation_remaining_after,new.account_remaining_after,
        new.ordinal,new.calls_remaining,new.reserved_at,new.call_deadline);
    return new;
end $$;
create trigger model_reservation_insert_guard before insert on task.model_call_reservation
    for each row execute function task.model_reservation_insert_guard();
create trigger model_budget_no_change before update or delete on task.model_budget_account
    for each row execute function task.resource_call_no_change();
create trigger model_budget_no_truncate before truncate on task.model_budget_account
    for each statement execute function task.resource_call_no_change();
create trigger model_reservation_no_change before update or delete on task.model_call_reservation
    for each row execute function task.resource_call_no_change();
create trigger model_reservation_no_truncate before truncate on task.model_call_reservation
    for each statement execute function task.resource_call_no_change();
