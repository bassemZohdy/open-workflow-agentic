-- PROPOSED V80: idempotent manual resolution DATA. Never part of Flyway or production activation by default.
-- Caller authority, PEP, central appendOnce and effect mutation are coordinated by the opt-in Java service.
set local search_path = pg_catalog, pg_temp;
do $$
begin
    if current_setting('transaction_isolation') <> 'read committed' then
        raise exception 'manual effect decision proposal requires READ COMMITTED' using errcode='25001';
    end if;
    if current_setting('lock_timeout')::interval = interval '0' or current_setting('lock_timeout')::interval > interval '2 seconds' then
        perform set_config('lock_timeout','2s',true);
    end if;
    if current_setting('statement_timeout')::interval = interval '0' or current_setting('statement_timeout')::interval > interval '5 seconds' then
        perform set_config('statement_timeout','5s',true);
    end if;
end $$;
create table effect.manual_decision (
    decision_id bigint generated always as identity primary key,
    request_id text not null unique check(request_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    tenant text not null,
    registry_instance_id text not null,
    owner_kind text not null check(owner_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    owner_id text not null check(char_length(owner_id) between 1 and 256),
    actor_kind text not null check(actor_kind='USER'),
    actor_id text not null check(char_length(actor_id) between 1 and 256),
    desired_state text not null check(desired_state in ('CONFIRMED','FAILED')),
    reason_code text not null check(reason_code in ('TARGET_CONFIRMED','TARGET_REJECTED','OPERATOR_VERIFIED')),
    policy_decided_by text not null,
    policy_outcome text not null check(policy_outcome='ALLOW'),
    policy_id text not null,
    policy_version text not null,
    policy_decided_at timestamptz not null,
    source_key text not null unique check(source_key ~ '^as1-[0-9a-f]{64}$'),
    source_scope_fingerprint text not null check(source_scope_fingerprint ~ '^[0-9a-f]{64}$'),
    source_key_fingerprint text not null check(source_key_fingerprint ~ '^[0-9a-f]{64}$'),
    source_content_fingerprint text not null check(source_content_fingerprint ~ '^[0-9a-f]{64}$'),
    draft_id text not null unique check(draft_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    occurred_at timestamptz not null,
    created_at timestamptz not null default clock_timestamp(),
    foreign key(thread_name,node_id,idempotency_key) references effect.ledger(thread_name,node_id,idempotency_key),
    foreign key(tenant,registry_instance_id) references task.instance(tenant,id),
    unique(thread_name,node_id,idempotency_key)
);
create index manual_decision_effect_idx on effect.manual_decision(thread_name,node_id,idempotency_key,decision_id);
create function effect.manual_decision_insert_guard() returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare l effect.ledger%rowtype; s effect.audit_scope%rowtype; r task.instance%rowtype;
begin
    select * into l from effect.ledger where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key for update;
    select * into s from effect.audit_scope where thread_name=new.thread_name and node_id=new.node_id and idempotency_key=new.idempotency_key;
    select * into r from task.instance where id=new.registry_instance_id for update;
    if not found or l.state<>'INTENDED' or not l.attempted or l.request_fingerprint is null
       or s.tenant is distinct from new.tenant or s.registry_instance_id is distinct from new.registry_instance_id
       or s.owner_kind is distinct from new.owner_kind or s.owner_id is distinct from new.owner_id
       or new.source_scope_fingerprint is distinct from s.scope_fingerprint
       or new.source_key_fingerprint is distinct from effect.audit_fingerprint('oa.effect.audit-key.v1',new.thread_name,new.node_id,new.idempotency_key)
       or new.source_key is distinct from audit.source_identity_key(1,new.source_scope_fingerprint,new.source_key_fingerprint,'MANUAL',new.decision_id)
       or (new.desired_state='CONFIRMED' and new.reason_code not in ('TARGET_CONFIRMED','OPERATOR_VERIFIED'))
       or (new.desired_state='FAILED' and new.reason_code not in ('TARGET_REJECTED','OPERATOR_VERIFIED'))
       or r.tenant is distinct from new.tenant or r.status not in ('RUNNING','WAITING')
       or (r.wait_deadline is not null and r.wait_deadline<=clock_timestamp())
       or not exists(select 1 from effect.event e where e.thread_name=new.thread_name and e.node_id=new.node_id
           and e.idempotency_key=new.idempotency_key and e.reason='effect.outcome.unknown'
           and e.from_state='INTENDED' and e.to_state='INTENDED') then
        raise exception 'manual decision requires current attempted unknown scoped effect' using errcode='check_violation';
    end if;
    return new;
end;
$$;
create trigger manual_decision_insert_guard before insert on effect.manual_decision for each row execute function effect.manual_decision_insert_guard();
create table effect.manual_decision_applied (
    decision_id bigint primary key references effect.manual_decision(decision_id),
    effect_event_id bigint not null unique references effect.event(id),
    resulting_state text not null check(resulting_state in ('CONFIRMED','FAILED')),
    applied_at timestamptz not null default clock_timestamp()
);
create function effect.manual_decision_immutable() returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
begin raise exception 'manual effect decisions are immutable and retained' using errcode='insufficient_privilege'; end;
$$;
create trigger manual_decision_no_change before update or delete on effect.manual_decision for each row execute function effect.manual_decision_immutable();
create trigger manual_decision_no_truncate before truncate on effect.manual_decision for each statement execute function effect.manual_decision_immutable();
create function effect.manual_decision_applied_guard() returns trigger language plpgsql set search_path = pg_catalog, pg_temp as $$
declare d effect.manual_decision%rowtype; e effect.event%rowtype; l effect.ledger%rowtype;
begin
    if TG_OP<>'INSERT' then raise exception 'manual decision application is immutable' using errcode='insufficient_privilege'; end if;
    select * into d from effect.manual_decision where decision_id=new.decision_id;
    select * into e from effect.event where id=new.effect_event_id;
    select * into l from effect.ledger where thread_name=d.thread_name and node_id=d.node_id and idempotency_key=d.idempotency_key;
    if d.decision_id is null or e.id is null or e.thread_name is distinct from d.thread_name or e.node_id is distinct from d.node_id
       or e.idempotency_key is distinct from d.idempotency_key or e.reason<>'effect.outcome' or e.from_state<>'INTENDED'
       or e.to_state is distinct from d.desired_state or new.resulting_state is distinct from d.desired_state
       or l.state is distinct from d.desired_state then
        raise exception 'manual decision application differs from effect outcome' using errcode='check_violation';
    end if;
    return new;
end;
$$;
create trigger manual_decision_applied_guard before insert or update or delete on effect.manual_decision_applied for each row execute function effect.manual_decision_applied_guard();
create trigger manual_decision_applied_no_truncate before truncate on effect.manual_decision_applied for each statement execute function effect.manual_decision_immutable();
revoke all on effect.manual_decision,effect.manual_decision_applied from public;
revoke all on sequence effect.manual_decision_decision_id_seq from public;
