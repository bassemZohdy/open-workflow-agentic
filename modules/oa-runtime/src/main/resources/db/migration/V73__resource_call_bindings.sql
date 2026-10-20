-- Shared GPT allocation, after V70/V71/V72. Attribution DATA, never dispatch authority.
-- No bearer, raw request, checkpoint or graph result. The producer validates the actual SDK/task/target.
create table task.resource_call (
    call_key text primary key check (call_key ~ '^rc1-[0-9a-f]{64}$'),
    fingerprint text not null check (fingerprint ~ '^[0-9a-f]{64}$'),
    tenant text not null,
    instance_id text not null,
    owner_kind text not null check (owner_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    owner_id text not null check (char_length(owner_id) between 1 and 256),
    application_id text not null check (char_length(application_id) between 1 and 256),
    engine_instance_id text not null check (char_length(engine_instance_id) between 1 and 256),
    namespace text not null check (char_length(namespace) between 1 and 256),
    definition_name text not null check (char_length(definition_name) between 1 and 256),
    definition_version text not null check (char_length(definition_version) between 1 and 128),
    task_position text not null check (char_length(task_position) between 1 and 1024),
    iteration integer not null check (iteration > 0),
    logical_occurrence text not null check (char_length(logical_occurrence) between 1 and 1024),
    audience text not null check (char_length(audience) between 1 and 300),
    operation text not null check (char_length(operation) between 1 and 256),
    target_fingerprint text not null check (target_fingerprint ~ '^[0-9a-f]{64}$'),
    request_fingerprint text not null check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default clock_timestamp(),
    foreign key (tenant, instance_id) references task.instance(tenant, id)
);

create table task.resource_call_binding (
    binding_key text primary key check (binding_key ~ '^rb1-[0-9a-f]{64}$'),
    issuer text not null,
    token_id text not null,
    call_key text not null references task.resource_call(call_key),
    grant_id text not null references identity.delegation_grant(id),
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    created_at timestamptz not null default clock_timestamp(),
    unique (issuer, token_id),
    foreign key (issuer, token_id) references identity.issued_token_binding(issuer, token_id),
    check (expires_at > issued_at and expires_at <= issued_at + interval '300 seconds')
);
create index resource_call_instance_idx on task.resource_call(tenant, instance_id);
create index resource_call_binding_call_idx on task.resource_call_binding(call_key);
create index resource_call_binding_grant_idx on task.resource_call_binding(grant_id);

-- Registry -> immutable call -> issued binding. Exact repeats use a fresh read, never UPDATE.
create function task.resource_call_insert_guard() returns trigger language plpgsql as $$
declare r task.instance%rowtype;
begin
    select * into r from task.instance where id = new.instance_id for update;
    if not found or r.tenant is distinct from new.tenant
       or r.owner_kind is distinct from new.owner_kind or r.owner_id is distinct from new.owner_id
       or r.engine_instance_id is distinct from new.engine_instance_id
       or r.definition_id is distinct from (new.namespace || '/' || new.definition_name)
       or r.definition_version is distinct from new.definition_version
       or r.status not in ('RUNNING', 'WAITING') then
        raise exception 'resource call registry attribution is not current' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger resource_call_insert_guard before insert on task.resource_call
    for each row execute function task.resource_call_insert_guard();

create function task.resource_call_binding_insert_guard() returns trigger language plpgsql as $$
declare invocation task.resource_call%rowtype; r task.instance%rowtype;
        issued identity.issued_token_binding%rowtype; g identity.delegation_grant%rowtype;
begin
    select * into invocation from task.resource_call where call_key = new.call_key;
    if not found then
        raise exception 'resource call is missing' using errcode = 'foreign_key_violation';
    end if;
    select * into r from task.instance where id = invocation.instance_id for update;
    if not found or r.tenant is distinct from invocation.tenant
       or r.owner_kind is distinct from invocation.owner_kind or r.owner_id is distinct from invocation.owner_id
       or r.engine_instance_id is distinct from invocation.engine_instance_id
       or r.definition_id is distinct from (invocation.namespace || '/' || invocation.definition_name)
       or r.definition_version is distinct from invocation.definition_version
       or r.status not in ('RUNNING', 'WAITING') then
        raise exception 'resource call registry attribution is not current' using errcode = 'check_violation';
    end if;
    select * into issued from identity.issued_token_binding
        where issuer = new.issuer and token_id = new.token_id for key share;
    if not found or issued.grant_id is distinct from new.grant_id
       or issued.tenant is distinct from invocation.tenant or issued.expires_at is distinct from new.expires_at then
        raise exception 'resource call issuing metadata does not match' using errcode = 'check_violation';
    end if;
    select * into g from identity.delegation_grant where id = new.grant_id;
    if not found or g.tenant is distinct from invocation.tenant
       or g.subject_kind is distinct from invocation.owner_kind or g.subject_id is distinct from invocation.owner_id then
        raise exception 'resource call issuing subject does not match' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger resource_call_binding_insert_guard before insert on task.resource_call_binding
    for each row execute function task.resource_call_binding_insert_guard();

-- Retain through credential AND outstanding invocation/effect/recovery/audit references.
-- No generic cleanup here; an independently reviewed archival protocol is still required.
create function task.resource_call_no_change() returns trigger language plpgsql as $$
begin
    raise exception 'resource call attribution is immutable and retained' using errcode = 'insufficient_privilege';
end;
$$;
create trigger resource_call_no_change before update or delete on task.resource_call
    for each row execute function task.resource_call_no_change();
create trigger resource_call_no_truncate before truncate on task.resource_call
    for each statement execute function task.resource_call_no_change();
create trigger resource_call_binding_no_change before update or delete on task.resource_call_binding
    for each row execute function task.resource_call_no_change();
create trigger resource_call_binding_no_truncate before truncate on task.resource_call_binding
    for each statement execute function task.resource_call_no_change();
