-- Pin the original maximum lifetime of new instances. Rows predating the migration have no pin.
create table task.instance_lifetime (
    instance_id text primary key references task.instance(id),
    deadline timestamptz not null
);
create index instance_lifetime_deadline_idx on task.instance_lifetime(deadline);

create function task.require_initial_lifetime_pin() returns trigger language plpgsql as $$
declare original task.instance%rowtype;
begin
    select * into original from task.instance where id = new.instance_id for key share;
    if not found or original.status <> 'STARTING' or original.version <> 0 or new.deadline <= original.created_at then
        raise exception 'lifetime pin requires a newly created instance and a later deadline' using errcode='check_violation';
    end if;
    return new;
end $$;
create trigger instance_lifetime_initial_only before insert on task.instance_lifetime
    for each row execute function task.require_initial_lifetime_pin();

create function task.guard_instance_lifetime() returns trigger language plpgsql as $$
begin
    raise exception 'instance lifetime pins are immutable' using errcode='insufficient_privilege';
end $$;
create trigger instance_lifetime_no_change before update or delete on task.instance_lifetime
    for each row execute function task.guard_instance_lifetime();
create trigger instance_lifetime_no_truncate before truncate on task.instance_lifetime
    for each statement execute function task.guard_instance_lifetime();
