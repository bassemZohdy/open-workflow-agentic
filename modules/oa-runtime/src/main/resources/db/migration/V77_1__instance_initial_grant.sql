-- Preserve the original start grant for idempotency checks. Existing rows have unknown provenance; they are not guessed.
-- BEFORE INSERT also captures starts made by older application versions during a rolling upgrade.
alter table identity.instance_authority
    add column initial_grant_known boolean not null default false,
    add column initial_grant_id text check (initial_grant_id ~ '^[0-9A-Z]{26}$'),
    add constraint initial_grant_known_or_empty check (initial_grant_known or initial_grant_id is null);

create function identity.capture_initial_grant() returns trigger language plpgsql as $$
begin
    new.initial_grant_known := true;
    new.initial_grant_id := new.grant_id;
    return new;
end $$;
create trigger instance_initial_grant_capture before insert on identity.instance_authority
    for each row execute function identity.capture_initial_grant();

-- Preserve every pre-existing V62 identity and reauthorization invariant, and make the initial binding immutable.
create or replace function identity.guard_instance_authority() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'instance authority rows are not deleted';
    end if;
    if new.instance_id <> old.instance_id or new.tenant <> old.tenant or new.subject_kind <> old.subject_kind
       or new.subject_id <> old.subject_id or new.correlation_id is distinct from old.correlation_id
       or new.created_at <> old.created_at or new.initial_grant_known is distinct from old.initial_grant_known
       or new.initial_grant_id is distinct from old.initial_grant_id then
        raise exception 'the original subject, tenant, correlation id and grant of an instance do not change';
    end if;
    if old.grant_id is null and new.grant_id is not null then
        raise exception 'an instance without a grant cannot gain one';
    end if;
    return new;
end $$;
