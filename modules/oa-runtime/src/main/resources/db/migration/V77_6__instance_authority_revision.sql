-- A revision identifies each committed version of an authority, including an ABA rebind to the same grant.
-- The trigger covers updates issued by older replicas as well as the current application.
alter table identity.instance_authority
    add column authority_revision bigint not null default 0 check (authority_revision >= 0);

create function identity.advance_authority_revision() returns trigger language plpgsql as $$
begin
    new.authority_revision := old.authority_revision + 1;
    return new;
end $$;

create trigger instance_authority_revision before update on identity.instance_authority
    for each row execute function identity.advance_authority_revision();
