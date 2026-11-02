-- Proposed only. No endpoint, CDI activation, role grants or event-start authorization is supplied here.
-- The initial authority row and event binding must be inserted by the same new-instance transaction.
create table task.event_start_binding (
    tenant text not null,
    start_key text not null check (start_key ~ '^event-[0-9a-f]{64}$'),
    instance_id text not null unique references task.instance(id),
    request_fingerprint text not null check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    owner_kind text not null,
    owner_id text not null,
    initial_grant_id text not null check (initial_grant_id ~ '^[0-9A-Z]{26}$'),
    binding_fingerprint text not null check (binding_fingerprint ~ '^[0-9a-f]{64}$'),
    producer_kind text not null check (producer_kind in ('USER','AGENT','WORKFLOW','SERVICE')),
    producer_id text not null check (char_length(producer_id) between 1 and 256),
    binding_id text not null check (char_length(binding_id) between 1 and 256),
    binding_version text not null check (char_length(binding_version) between 1 and 128),
    event_envelope text check (octet_length(event_envelope) between 1 and 65536),
    created_at timestamptz not null default statement_timestamp(),
    retain_until timestamptz not null,
    redacted_at timestamptz,
    check (retain_until > created_at and retain_until <= created_at + interval '30 days'),
    check ((event_envelope is null) = (redacted_at is not null)),
    primary key (tenant, start_key)
);
create function task.event_start_binding_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then raise exception 'event start tombstones cannot be deleted'; end if;
    if tg_op = 'UPDATE' then
        if row(new.tenant,new.start_key,new.instance_id,new.request_fingerprint,new.owner_kind,new.owner_id,
               new.initial_grant_id,new.binding_fingerprint,new.producer_kind,new.producer_id,new.binding_id,
               new.binding_version,new.created_at,new.retain_until)
           is distinct from
           row(old.tenant,old.start_key,old.instance_id,old.request_fingerprint,old.owner_kind,old.owner_id,
               old.initial_grant_id,old.binding_fingerprint,old.producer_kind,old.producer_id,old.binding_id,
               old.binding_version,old.created_at,old.retain_until)
           or old.event_envelope is null or new.event_envelope is not null or new.redacted_at is null
           or old.retain_until > clock_timestamp()
           or not exists (select 1 from task.instance i where i.id=old.instance_id and i.tenant=old.tenant
                          and i.status in ('CANCELLED','COMPLETED','FAILED')) then
            raise exception 'only expired terminal event payloads can be redacted';
        end if;
        new.redacted_at := clock_timestamp();
        return new;
    end if;
    if new.event_envelope is null or new.redacted_at is not null
       or new.created_at is distinct from statement_timestamp() or new.retain_until <= statement_timestamp() then
        raise exception 'new event start requires its payload and a future retention deadline';
    end if;
    if not exists (
        select 1 from task.instance i join identity.instance_authority a on a.instance_id=i.id
         where i.id=new.instance_id and i.tenant=new.tenant and i.idempotency_key=new.start_key
           and i.request_fingerprint=new.request_fingerprint and i.owner_kind=new.owner_kind and i.owner_id=new.owner_id
           and a.tenant=new.tenant and a.subject_kind=new.owner_kind and a.subject_id=new.owner_id
           and a.initial_grant_known and a.initial_grant_id=new.initial_grant_id
    ) then raise exception 'event start binding does not match initial instance authority'; end if;
    return new;
end $$;
create trigger event_start_binding_guard before insert or update or delete on task.event_start_binding
    for each row execute function task.event_start_binding_guard();
