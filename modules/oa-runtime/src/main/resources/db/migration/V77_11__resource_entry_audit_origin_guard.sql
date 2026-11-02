-- Retain no guessed origin for entries written before this migration. New entries receive a database-assigned
-- full transaction ID from the insert trigger, even if an ordinary writer supplies another value. xid8 avoids
-- treating a historical row as fresh when PostgreSQL's 32-bit xmin wraps.
alter table task.resource_execution add column audit_origin_transaction xid8;

create function task.resource_execution_set_audit_origin() returns trigger
    language plpgsql set search_path = pg_catalog as $$
begin
    new.audit_origin_transaction = pg_current_xact_id();
    return new;
end;
$$;
create trigger resource_execution_set_audit_origin before insert on task.resource_execution
    for each row execute function task.resource_execution_set_audit_origin();

create function task.resource_entry_audit_key(entry_key text) returns text
    language sql immutable strict set search_path = pg_catalog as $$
    select encode(sha256(int4send(octet_length(convert_to('oa.resource.entry.audit-key.v1','UTF8')))
        || convert_to('oa.resource.entry.audit-key.v1','UTF8')
        || int4send(octet_length(convert_to(entry_key,'UTF8'))) || convert_to(entry_key,'UTF8')), 'hex')
$$;

create function task.resource_entry_audit_source_origin_guard() returns trigger
    language plpgsql set search_path = pg_catalog as $$
declare stored_origin xid8; stored_contract text; stored_key text; insertion_xmin xid;
begin
    select e.audit_origin_transaction, e.contract_fingerprint, e.entry_key, e.xmin
        into stored_origin, stored_contract, stored_key, insertion_xmin
        from task.resource_execution e where e.entry_key = new.entry_key;
    -- The xmin check also refuses a restored old row if its insertion xid differs from the current transaction.
    -- Logical restore can eventually reuse both IDs; restricted writer privileges remain necessary.
    -- The gate owns a top-level transaction; an entry inserted under a subtransaction conservatively refuses.
    if not found or stored_origin is distinct from pg_current_xact_id()
        or insertion_xmin::text::numeric is distinct from mod(pg_current_xact_id()::text::numeric, 4294967296) then
        raise exception 'entry audit source must be inserted with its fresh entry' using errcode = 'check_violation';
    end if;
    if new.scope_fingerprint is distinct from stored_contract
        or new.key_fingerprint is distinct from task.resource_entry_audit_key(stored_key) then
        raise exception 'entry audit source attribution differs' using errcode = 'check_violation';
    end if;
    return new;
end;
$$;
create trigger resource_entry_audit_source_origin_guard before insert on task.resource_execution_audit_source
    for each row execute function task.resource_entry_audit_source_origin_guard();
