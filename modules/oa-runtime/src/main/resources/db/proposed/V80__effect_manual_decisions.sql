-- PROPOSED V80: ordered publication after V71..V79, not Flyway activation or an operator authorization API.
-- Only trusted persistence DATA. Real current signed caller/resolve-effect policy/evidence and audit gates remain.
create table effect.manual_decision (
    decision_id text primary key check(decision_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    schema_version integer not null check(schema_version=1),
    tenant text not null,
    registry_instance_id text not null,
    owner_kind text not null check(owner_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    owner_id text not null check(char_length(owner_id) between 1 and 256),
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    target varchar(256) not null,
    request_fingerprint text not null check(request_fingerprint ~ '^[0-9a-f]{64}$'),
    expected_event_id bigint not null references effect.event(id),
    outcome text not null check(outcome in ('CONFIRMED','FAILED')),
    operator_kind text not null check(operator_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    operator_id text not null check(char_length(operator_id) between 1 and 256),
    policy_id text not null check(char_length(policy_id) between 1 and 128),
    policy_version text not null check(char_length(policy_version) between 1 and 64),
    authorization_fingerprint text not null check(authorization_fingerprint ~ '^[0-9a-f]{64}$'),
    evidence_fingerprint text not null check(evidence_fingerprint ~ '^[0-9a-f]{64}$'),
    decision_fingerprint text not null check(decision_fingerprint ~ '^[0-9a-f]{64}$'),
    source_event_id bigint not null unique references effect.event(id),
    occurred_at timestamptz not null,
    unique(thread_name,node_id,idempotency_key),
    foreign key(thread_name,node_id,idempotency_key) references effect.audit_scope(thread_name,node_id,idempotency_key),
    foreign key(source_event_id) references effect.audit_source(source_event_id) deferrable initially deferred
);
create function effect.manual_fingerprint(m effect.manual_decision) returns text language sql immutable strict as $$
    select effect.audit_fingerprint('oa.effect.manual-decision.v1',m.schema_version::text,m.decision_id,
        m.tenant,m.registry_instance_id,m.owner_kind,m.owner_id,m.thread_name,m.node_id,m.idempotency_key,
        m.target,m.request_fingerprint,m.expected_event_id::text,m.outcome,m.operator_kind,m.operator_id,
        m.policy_id,m.policy_version,m.authorization_fingerprint,m.evidence_fingerprint)
$$;
create function effect.manual_attributes(m effect.manual_decision) returns jsonb language sql immutable strict as $$
    select jsonb_build_object('manual.id',m.decision_id,'manual.fingerprint',m.decision_fingerprint,
        'manual.operator.kind',m.operator_kind,'manual.operator.id',m.operator_id,
        'manual.policy.id',m.policy_id,'manual.policy.version',m.policy_version,
        'manual.authorization',m.authorization_fingerprint,'manual.evidence',m.evidence_fingerprint,
        'manual.expectedJournal',m.expected_event_id::text,
        'manual.reason',case when m.outcome='CONFIRMED' then 'operator.evidence.confirmed' else 'operator.evidence.refused' end)
$$;
create function effect.manual_decision_guard() returns trigger language plpgsql as $$
declare r task.instance%rowtype; l effect.ledger%rowtype; s effect.audit_scope%rowtype; e effect.event%rowtype; prior bigint;
begin
    select * into r from task.instance where id=new.registry_instance_id for update;
    if not found or row(r.tenant,r.owner_kind,r.owner_id) is distinct from row(new.tenant,new.owner_kind,new.owner_id) then
        raise exception 'manual full registry scope differs' using errcode='check_violation';
    end if;
    -- Terminal registry status does not erase unresolved business history. Recovery permission is a separate gate.
    select * into l from effect.ledger where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key for update;
    select * into s from effect.audit_scope where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key;
    select * into e from effect.event where id=new.source_event_id;
    select max(id) into prior from effect.event where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key and id<>new.source_event_id;
    if l.thread_name is null or s.initial_event_id is null or e.id is null
        or l.state<>'INTENDED' or not l.attempted
        or row(l.target,l.request_fingerprint) is distinct from row(new.target,new.request_fingerprint)
        or row(s.tenant,s.registry_instance_id,s.owner_kind,s.owner_id) is distinct from row(new.tenant,new.registry_instance_id,new.owner_kind,new.owner_id)
        or row(e.thread_name,e.node_id,e.idempotency_key,e.from_state,e.to_state,e.reason,e.occurred_at)
            is distinct from row(new.thread_name,new.node_id,new.idempotency_key,'INTENDED',new.outcome,'effect.manual',new.occurred_at)
        or prior is distinct from new.expected_event_id or e.id<=new.expected_event_id
        or new.decision_fingerprint is distinct from effect.manual_fingerprint(new)
        or not exists(select 1 from effect.manual_resolution q where q.thread_name=new.thread_name and q.node_id=new.node_id and q.idempotency_key=new.idempotency_key and q.closed_at is null) then
        raise exception 'manual decision requires exact unresolved queued intent and latest history' using errcode='check_violation';
    end if;
    return new;
end;
$$;
create trigger manual_decision_guard before insert on effect.manual_decision for each row execute function effect.manual_decision_guard();
create trigger manual_decision_no_change before update or delete on effect.manual_decision for each row execute function effect.audit_no_change();
create trigger manual_decision_no_truncate before truncate on effect.manual_decision for each statement execute function effect.audit_no_change();

create function effect.manual_decision_complete() returns trigger language plpgsql as $$
begin
    if not exists(select 1 from effect.ledger l join effect.audit_source s on s.source_event_id=new.source_event_id
        join effect.manual_resolution q using(thread_name,node_id,idempotency_key)
        where l.thread_name=new.thread_name and l.node_id=new.node_id and l.idempotency_key=new.idempotency_key
          and l.state=new.outcome and l.detail=case when new.outcome='CONFIRMED' then 'operator.evidence.confirmed' else 'operator.evidence.refused' end
          and l.recorded_at=new.occurred_at and q.closed_at=new.occurred_at and s.transition='MANUAL') then
        raise exception 'manual outcome/queue/source must commit atomically' using errcode='check_violation';
    end if;
    return new;
end;
$$;
create constraint trigger manual_decision_complete after insert on effect.manual_decision deferrable initially deferred
    for each row execute function effect.manual_decision_complete();

alter table effect.audit_source drop constraint audit_source_transition_check;
alter table effect.audit_source add constraint audit_source_transition_check
    check(transition in ('INTENT','DISPATCH','UNKNOWN','OUTCOME','MANUAL','COMPENSATION'));

create or replace function effect.audit_transition(reason text, from_state text, to_state text) returns text
    language plpgsql immutable as $$
begin
    if reason='effect.manual' and from_state='INTENDED' and to_state in ('CONFIRMED','FAILED') then return 'MANUAL';
    elsif reason='effect.intended' and from_state is null and to_state='INTENDED' then return 'INTENT';
    elsif reason='effect.dispatch' and from_state='INTENDED' and to_state='INTENDED' then return 'DISPATCH';
    elsif reason='effect.outcome' and from_state='INTENDED' and to_state in ('CONFIRMED','FAILED') then return 'OUTCOME';
    elsif reason='effect.outcome' and from_state in ('CONFIRMED','FAILED') and to_state='COMPENSATED' then return 'COMPENSATION';
    elsif reason='effect.outcome.unknown' and from_state='INTENDED' and to_state='INTENDED' then return 'UNKNOWN';
    elsif reason='effect.compensation.unresolved' and from_state in ('CONFIRMED','FAILED') and from_state=to_state then return 'UNKNOWN';
    end if;
    raise exception 'unsupported scoped effect journal fact' using errcode = 'check_violation';
end;
$$;


create or replace function effect.audit_source_insert_guard() returns trigger language plpgsql as $$
declare e effect.event%rowtype; s effect.audit_scope%rowtype; l effect.ledger%rowtype; transition text; key_fp text; attrs jsonb; manual effect.manual_decision%rowtype;
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
    if transition='MANUAL' then
        select * into manual from effect.manual_decision where source_event_id=e.id;
        if not found then raise exception 'manual audit source requires retained decision' using errcode = 'check_violation'; end if;
        attrs=attrs || effect.manual_attributes(manual);
    end if;
    if new.attributes is distinct from attrs then
        raise exception 'effect audit draft attributes differ' using errcode = 'check_violation';
    end if;
    -- Draft content uses the ONE shared Java AuditDraftFingerprint helper, checked on retained readback too.
    return new;
end;
$$;
