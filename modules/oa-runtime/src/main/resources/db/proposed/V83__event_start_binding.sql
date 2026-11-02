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
    primary key (tenant, start_key)
);
create function task.event_start_binding_guard() returns trigger language plpgsql as $$
begin
    if tg_op <> 'INSERT' then raise exception 'event start bindings are immutable'; end if;
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
