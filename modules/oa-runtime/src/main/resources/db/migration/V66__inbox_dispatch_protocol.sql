-- Successor to V64 receipt and V65 authority binding. Integrate in this order; never edit applied migrations.
alter table task.instance add constraint instance_tenant_id_unique unique (tenant, id);

create table inbox.delivery (
    tenant varchar(256) not null,
    instance_id text not null,
    wait_key varchar(256) not null,
    identity_digest varchar(64) not null check (identity_digest ~ '^[0-9a-f]{64}$'),
    state varchar(24) not null check (state in ('REGISTERED','DISPATCHED','ACKNOWLEDGED','CANCELLED','EXPIRED')),
    registered_at timestamptz not null default clock_timestamp(),
    deadline timestamptz not null,
    attempts integer not null default 0 check (attempts >= 0),
    reserved_at timestamptz,
    acknowledged_at timestamptz,
    primary key (tenant, instance_id, wait_key),
    foreign key (tenant, instance_id) references task.instance (tenant, id),
    check ((state = 'ACKNOWLEDGED') = (acknowledged_at is not null)),
    check (deadline > registered_at),
    check ((attempts = 0) = (reserved_at is null))
);
create index inbox_delivery_pending on inbox.delivery (state, deadline);

create function inbox.guard_delivery() returns trigger language plpgsql as $$
begin
    if tg_op <> 'UPDATE' then
        raise exception 'delivery identities and dispositions are retained: % is refused', tg_op
            using errcode = 'insufficient_privilege';
    end if;
    if row(new.tenant,new.instance_id,new.wait_key,new.identity_digest,new.registered_at,new.deadline)
       is distinct from row(old.tenant,old.instance_id,old.wait_key,old.identity_digest,old.registered_at,old.deadline) then
        raise exception 'wait identity and original deadline do not change' using errcode = 'check_violation';
    end if;
    if old.state in ('ACKNOWLEDGED','CANCELLED','EXPIRED') then
        raise exception 'delivery disposition is final' using errcode = 'check_violation';
    end if;
    if new.state = 'DISPATCHED' then
        if new.attempts <> old.attempts + 1 or new.reserved_at is null then
            raise exception 'dispatch must record its next attempt' using errcode = 'check_violation';
        end if;
    elsif new.state in ('ACKNOWLEDGED','CANCELLED','EXPIRED') then
        if new.attempts <> old.attempts or new.reserved_at is distinct from old.reserved_at
           or (new.state = 'ACKNOWLEDGED' and old.state <> 'DISPATCHED') then
            raise exception 'invalid delivery disposition' using errcode = 'check_violation';
        end if;
    else
        raise exception 'invalid delivery transition' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger inbox_delivery_guard before update or delete on inbox.delivery
    for each row execute function inbox.guard_delivery();
create trigger inbox_delivery_no_truncate before truncate on inbox.delivery
    for each statement execute function inbox.guard_delivery();
