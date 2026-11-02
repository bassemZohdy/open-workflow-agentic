-- Complete wait identity for recovery. Existing digest-only rows are not guessed or backfilled.
create table inbox.wait_profile (
    tenant varchar(256) not null,
    instance_id text not null,
    wait_key varchar(256) not null,
    identity_digest varchar(64) not null check(identity_digest ~ '^[0-9a-f]{64}$'),
    application_id varchar(256) not null,
    engine_instance_id varchar(256) not null,
    namespace varchar(256) not null,
    definition varchar(256) not null,
    version varchar(128) not null,
    task_position varchar(1024) not null,
    iteration integer not null check(iteration>0),
    route varchar(96) not null check(route ~ '^[A-Za-z0-9._:-]{1,96}$'),
    event_type varchar(256) not null,
    filter_digest varchar(64) not null check(filter_digest ~ '^[0-9a-f]{64}$'),
    max_wait varchar(64) not null,
    primary key(tenant,instance_id,wait_key),
    foreign key(tenant,instance_id,wait_key) references inbox.delivery(tenant,instance_id,wait_key),
    check(wait_key=route || '~' || identity_digest)
);
create function inbox.guard_wait_profile() returns trigger language plpgsql as $$
begin
    if tg_op <> 'INSERT' then
        raise exception 'wait profile is immutable' using errcode='insufficient_privilege';
    end if;
    if not exists(select 1 from inbox.delivery d where d.tenant=new.tenant and d.instance_id=new.instance_id
                  and d.wait_key=new.wait_key and d.identity_digest=new.identity_digest) then
        raise exception 'wait profile differs from its registration' using errcode='check_violation';
    end if;
    return new;
end $$;
create trigger wait_profile_insert before insert on inbox.wait_profile for each row execute function inbox.guard_wait_profile();
create trigger wait_profile_immutable before update or delete on inbox.wait_profile for each row execute function inbox.guard_wait_profile();
create trigger wait_profile_no_truncate before truncate on inbox.wait_profile for each statement execute function inbox.guard_wait_profile();
