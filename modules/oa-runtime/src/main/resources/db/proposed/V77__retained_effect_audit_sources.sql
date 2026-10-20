-- PROPOSED V77, not a Flyway migration yet. Ordered activation follows V71..V76 and consumer/adapter review.
-- Uses V75's PURE as1 encoding function locally; no central audit append/network call occurs in these transactions.
create function effect.audit_fingerprint(domain text, variadic parts text[]) returns text
    language plpgsql immutable strict as $$
declare encoded bytea = ''::bytea; item text; bytes bytea;
begin
    foreach item in array (array[domain] || parts) loop
        if item is null then raise exception 'effect audit identity field missing' using errcode = 'check_violation'; end if;
        bytes = convert_to(item, 'UTF8'); encoded = encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    return encode(sha256(encoded), 'hex');
end;
$$;

create table effect.audit_scope (
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    tenant text not null,
    registry_instance_id text not null,
    owner_kind text not null check (owner_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    owner_id text not null check (char_length(owner_id) between 1 and 256),
    scope_fingerprint text not null check (scope_fingerprint ~ '^[0-9a-f]{64}$'),
    initial_event_id bigint not null unique references effect.event(id),
    parent_initial_event_id bigint references effect.audit_scope(initial_event_id),
    created_at timestamptz not null default clock_timestamp(),
    primary key (thread_name,node_id,idempotency_key),
    foreign key (thread_name,node_id,idempotency_key) references effect.ledger(thread_name,node_id,idempotency_key),
    foreign key (tenant,registry_instance_id) references task.instance(tenant,id)
);
create function effect.audit_scope_insert_guard() returns trigger language plpgsql as $$
declare l effect.ledger%rowtype; e effect.event%rowtype; r task.instance%rowtype; parent effect.audit_scope%rowtype;
begin
    select * into l from effect.ledger where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key;
    select * into e from effect.event where id=new.initial_event_id;
    if l.thread_name is null or e.id is null or row(e.thread_name,e.node_id,e.idempotency_key)
        is distinct from row(new.thread_name,new.node_id,new.idempotency_key)
        or e.from_state is not null or e.to_state <> 'INTENDED' or e.reason <> 'effect.intended'
        or l.state <> 'INTENDED' or l.attempted or l.request_fingerprint is null then
        raise exception 'effect scope requires its fresh initial intent fact' using errcode = 'check_violation';
    end if;
    if new.thread_name is distinct from ('ws1-' || effect.audit_fingerprint('oa.thread.ws1',new.tenant,new.owner_id,new.registry_instance_id))
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
            or parent.thread_name is distinct from new.thread_name then
            raise exception 'compensation scope does not identify its retained original' using errcode = 'check_violation';
        end if;
    end if;
    return new;
end;
$$;
create trigger audit_scope_insert_guard before insert on effect.audit_scope for each row execute function effect.audit_scope_insert_guard();

create function effect.audit_transition(reason text, from_state text, to_state text) returns text
    language plpgsql immutable as $$
begin
    if reason='effect.intended' and from_state is null and to_state='INTENDED' then return 'INTENT';
    elsif reason='effect.dispatch' and from_state='INTENDED' and to_state='INTENDED' then return 'DISPATCH';
    elsif reason='effect.outcome' and from_state='INTENDED' and to_state in ('CONFIRMED','FAILED') then return 'OUTCOME';
    elsif reason='effect.outcome' and from_state in ('CONFIRMED','FAILED') and to_state='COMPENSATED' then return 'COMPENSATION';
    elsif reason='effect.outcome.unknown' and from_state='INTENDED' and to_state='INTENDED' then return 'UNKNOWN';
    elsif reason='effect.compensation.unresolved' and from_state in ('CONFIRMED','FAILED') and from_state=to_state then return 'UNKNOWN';
    end if;
    raise exception 'unsupported scoped effect journal fact' using errcode = 'check_violation';
end;
$$;

create table effect.audit_source (
    source_event_id bigint primary key references effect.event(id),
    scope_initial_event_id bigint not null references effect.audit_scope(initial_event_id),
    schema_version integer not null check (schema_version=1),
    scope_fingerprint text not null check (scope_fingerprint ~ '^[0-9a-f]{64}$'),
    key_fingerprint text not null check (key_fingerprint ~ '^[0-9a-f]{64}$'),
    transition text not null check (transition in ('INTENT','DISPATCH','UNKNOWN','OUTCOME','COMPENSATION')),
    source_sequence bigint not null check (source_sequence=source_event_id and source_sequence>0),
    source_key text not null unique check (source_key ~ '^as1-[0-9a-f]{64}$'),
    draft_id text not null unique check (draft_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    occurred_at timestamptz not null,
    actor_kind text not null check (actor_kind='SERVICE'),
    actor_id text not null check (actor_id='system:effect-ledger'),
    action text not null,
    resource_type text not null check (resource_type='effect'),
    resource_id text not null,
    attributes jsonb not null check (jsonb_typeof(attributes)='object' and octet_length(attributes::text)<=65536),
    content_fingerprint text not null check (content_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default clock_timestamp()
);
create function effect.audit_source_insert_guard() returns trigger language plpgsql as $$
declare e effect.event%rowtype; s effect.audit_scope%rowtype; l effect.ledger%rowtype; transition text; key_fp text; attrs jsonb;
begin
    select * into e from effect.event where id=new.source_event_id;
    if not found then raise exception 'effect audit journal fact missing' using errcode = 'foreign_key_violation'; end if;
    select * into s from effect.audit_scope where thread_name=e.thread_name and node_id=e.node_id and idempotency_key=e.idempotency_key;
    if not found then raise exception 'effect audit scope missing' using errcode = 'foreign_key_violation'; end if;
    select * into l from effect.ledger where thread_name=e.thread_name and node_id=e.node_id and idempotency_key=e.idempotency_key;
    transition=effect.audit_transition(e.reason,e.from_state,e.to_state);
    key_fp=effect.audit_fingerprint('oa.effect.audit-key.v1',e.thread_name,e.node_id,e.idempotency_key);
    if new.scope_initial_event_id is distinct from s.initial_event_id or new.scope_fingerprint is distinct from s.scope_fingerprint
        or new.key_fingerprint is distinct from key_fp or new.transition is distinct from transition
        or new.occurred_at is distinct from e.occurred_at
        or new.source_key is distinct from audit.source_identity_key(new.schema_version,new.scope_fingerprint,key_fp,transition,new.source_sequence)
        or new.action is distinct from ('effect.' || lower(transition)) or new.resource_id is distinct from ('efp1-' || key_fp) then
        raise exception 'effect audit source attribution differs' using errcode = 'check_violation';
    end if;
    attrs=jsonb_build_object('oa.chain','effect-' || effect.audit_fingerprint('oa.effect.audit-chain.v1',s.tenant),
        'oa.tenant',s.tenant,'oa.instance',s.registry_instance_id,'effect.owner.kind',s.owner_kind,'effect.owner.id',s.owner_id,
        'effect.scope',s.scope_fingerprint,'effect.key',key_fp,'effect.source',new.source_key,'effect.journal',e.id::text,
        'effect.transition',transition,'effect.from',coalesce(e.from_state,'NONE'),'effect.to',e.to_state,
        'effect.purpose',l.purpose,'effect.target',effect.audit_fingerprint('oa.effect.audit-target.v1',l.target),
        'effect.request',l.request_fingerprint);
    if new.attributes is distinct from attrs then
        raise exception 'effect audit draft attributes differ' using errcode = 'check_violation';
    end if;
    -- Draft content uses the ONE shared Java AuditDraftFingerprint helper, checked on retained readback too.
    return new;
end;
$$;
create trigger audit_source_insert_guard before insert on effect.audit_source for each row execute function effect.audit_source_insert_guard();

create function effect.require_audit_source() returns trigger language plpgsql as $$
begin
    if exists(select 1 from effect.audit_scope s where s.thread_name=new.thread_name and s.node_id=new.node_id and s.idempotency_key=new.idempotency_key)
        and not exists(select 1 from effect.audit_source a where a.source_event_id=new.id) then
        raise exception 'scoped journal fact requires atomic retained audit source' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create constraint trigger event_requires_audit_source after insert on effect.event deferrable initially deferred
    for each row execute function effect.require_audit_source();
create function effect.require_scope_sources() returns trigger language plpgsql as $$
begin
    if exists(select 1 from effect.event e where e.thread_name=new.thread_name and e.node_id=new.node_id and e.idempotency_key=new.idempotency_key
        and not exists(select 1 from effect.audit_source a where a.source_event_id=e.id)) then
        raise exception 'scoped history requires retained audit sources' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create constraint trigger scope_requires_sources after insert on effect.audit_scope deferrable initially deferred
    for each row execute function effect.require_scope_sources();
create function effect.require_compensation_scope() returns trigger language plpgsql as $$
declare original effect.audit_scope%rowtype; undo effect.audit_scope%rowtype;
begin
    select * into original from effect.audit_scope where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key;
    if not found then return new; end if;
    select * into undo from effect.audit_scope where thread_name=new.thread_name and node_id=new.compensation_node and idempotency_key=new.compensation_key;
    if not found or undo.scope_fingerprint is distinct from original.scope_fingerprint
        or undo.parent_initial_event_id is distinct from original.initial_event_id then
        raise exception 'scoped compensation requires matching retained provenance' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create constraint trigger compensation_requires_scope after insert on effect.compensation deferrable initially deferred
    for each row execute function effect.require_compensation_scope();
create function effect.audit_no_change() returns trigger language plpgsql as $$
begin
    raise exception 'effect audit provenance and source are immutable and retained' using errcode = 'insufficient_privilege';
end;
$$;
create trigger audit_scope_no_change before update or delete on effect.audit_scope for each row execute function effect.audit_no_change();
create trigger audit_scope_no_truncate before truncate on effect.audit_scope for each statement execute function effect.audit_no_change();
create trigger audit_source_no_change before update or delete on effect.audit_source for each row execute function effect.audit_no_change();
create trigger audit_source_no_truncate before truncate on effect.audit_source for each statement execute function effect.audit_no_change();

-- Operational delivery state is separate from immutable source facts. Pending indexes, NOT a global sequence
-- watermark, bound recovery work and retain delayed lower-id commits. One ready fact per effect key preserves
-- intent -> dispatch -> outcome order across competing drainers; the central appendOnce remains idempotent.
create table effect.audit_delivery (
    source_event_id bigint primary key references effect.audit_source(source_event_id),
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    ack_source_key text references audit.source_binding(source_key),
    acked_at timestamptz,
    check ((ack_source_key is null) = (acked_at is null))
);
create index audit_delivery_pending on effect.audit_delivery(source_event_id) where ack_source_key is null;
create index audit_delivery_key_pending on effect.audit_delivery(thread_name,node_id,idempotency_key,source_event_id)
    where ack_source_key is null;
create function effect.audit_delivery_guard() returns trigger language plpgsql as $$
declare s effect.audit_source%rowtype; e effect.event%rowtype; b audit.source_binding%rowtype;
begin
    select * into s from effect.audit_source where source_event_id=new.source_event_id;
    select * into e from effect.event where id=new.source_event_id;
    if s.source_event_id is null or e.id is null or row(new.thread_name,new.node_id,new.idempotency_key)
        is distinct from row(e.thread_name,e.node_id,e.idempotency_key) then
        raise exception 'audit delivery source differs' using errcode = 'check_violation';
    end if;
    if TG_OP='INSERT' then
        if new.ack_source_key is not null or new.acked_at is not null then
            raise exception 'audit delivery starts pending' using errcode = 'check_violation';
        end if;
        return new;
    end if;
    if row(new.source_event_id,new.thread_name,new.node_id,new.idempotency_key)
        is distinct from row(old.source_event_id,old.thread_name,old.node_id,old.idempotency_key)
        or (old.ack_source_key is not null and row(new.ack_source_key,new.acked_at)
            is distinct from row(old.ack_source_key,old.acked_at)) then
        raise exception 'audit delivery identity and acknowledgment are retained' using errcode = 'insufficient_privilege';
    end if;
    if old.ack_source_key is not null then return new; end if;
    if new.ack_source_key is null then
        if new.acked_at is not null then raise exception 'audit acknowledgment is incomplete' using errcode = 'check_violation'; end if;
        return new;
    end if;
    select * into b from audit.source_binding where source_key=new.ack_source_key;
    if not found or row(b.source_key,b.schema_version,b.scope_fingerprint,b.key_fingerprint,b.transition,
            b.source_sequence,b.content_fingerprint,b.event_id,b.chain_id)
        is distinct from row(s.source_key,s.schema_version,s.scope_fingerprint,s.key_fingerprint,s.transition,
            s.source_sequence,s.content_fingerprint,s.draft_id,s.attributes->>'oa.chain') then
        raise exception 'audit acknowledgment requires exact retained central binding' using errcode = 'check_violation';
    end if;
    if exists(select 1 from effect.audit_delivery p where p.thread_name=new.thread_name and p.node_id=new.node_id
        and p.idempotency_key=new.idempotency_key and p.source_event_id<new.source_event_id and p.ack_source_key is null) then
        raise exception 'earlier effect audit fact remains pending' using errcode = 'check_violation';
    end if;
    new.acked_at=clock_timestamp();
    return new;
end;
$$;
create trigger audit_delivery_guard before insert or update on effect.audit_delivery for each row execute function effect.audit_delivery_guard();
create trigger audit_delivery_no_change before delete on effect.audit_delivery for each row execute function effect.audit_no_change();
create trigger audit_delivery_no_truncate before truncate on effect.audit_delivery for each statement execute function effect.audit_no_change();
create function effect.enqueue_audit_source() returns trigger language plpgsql as $$
begin
    insert into effect.audit_delivery(source_event_id,thread_name,node_id,idempotency_key)
        select new.source_event_id,e.thread_name,e.node_id,e.idempotency_key from effect.event e where e.id=new.source_event_id;
    return new;
end;
$$;
create trigger audit_source_enqueue after insert on effect.audit_source for each row execute function effect.enqueue_audit_source();
