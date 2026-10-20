-- PROPOSED V75: not in db/migration, so Flyway does NOT apply it yet.
-- GPT sequences activation after the contract review and Claude's append-only test/consumer handoff.
-- Existing events have no guessed source: no backfill and no change to the sealed AuditHash format.
create function audit.source_identity_key(schema_version integer, scope_fingerprint text,
    key_fingerprint text, transition text, source_sequence bigint) returns text
    language plpgsql immutable strict as $$
declare encoded bytea; value text; bytes bytea;
begin
    bytes = convert_to('oa.audit.source.v1', 'UTF8');
    encoded = int4send(octet_length(bytes)) || bytes || int8send(schema_version::bigint);
    foreach value in array array[scope_fingerprint, key_fingerprint, transition] loop
        bytes = convert_to(value, 'UTF8');
        encoded = encoded || int4send(octet_length(bytes)) || bytes;
    end loop;
    return 'as1-' || encode(sha256(encoded || int8send(source_sequence)), 'hex');
end;
$$;

create table audit.source_binding (
    source_key text primary key check (source_key ~ '^as1-[0-9a-f]{64}$'),
    schema_version integer not null check (schema_version = 1),
    scope_fingerprint text not null check (scope_fingerprint ~ '^[0-9a-f]{64}$'),
    key_fingerprint text not null check (key_fingerprint ~ '^[0-9a-f]{64}$'),
    transition text not null check (transition in ('INTENT','DISPATCH','UNKNOWN','OUTCOME','MANUAL','COMPENSATION')),
    source_sequence bigint not null check (source_sequence > 0),
    content_fingerprint text not null check (content_fingerprint ~ '^[0-9a-f]{64}$'),
    event_id text not null unique references audit.event(event_id),
    chain_id text not null check (chain_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
    chain_sequence bigint not null check (chain_sequence > 0),
    hash text not null check (hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz not null default clock_timestamp(),
    unique (schema_version, scope_fingerprint, key_fingerprint, transition, source_sequence),
    foreign key (chain_id, chain_sequence) references audit.head_log(chain_id, seq)
);

create function audit.source_binding_insert_guard() returns trigger language plpgsql as $$
declare retained audit.event%rowtype; head_hash text;
begin
    if new.source_key is distinct from audit.source_identity_key(new.schema_version, new.scope_fingerprint,
        new.key_fingerprint, new.transition, new.source_sequence) then
        raise exception 'audit source identity differs from retained tuple' using errcode = 'check_violation';
    end if;
    select * into retained from audit.event where event_id = new.event_id;
    if not found then
        raise exception 'audit source event is missing' using errcode = 'foreign_key_violation';
    end if;
    if new.chain_id is distinct from retained.chain_id or new.chain_sequence is distinct from retained.seq
        or new.hash is distinct from retained.hash then
        raise exception 'audit source receipt differs from retained event' using errcode = 'check_violation';
    end if;
    select hash into head_hash from audit.head_log where chain_id = new.chain_id and seq = new.chain_sequence;
    if not found then
        raise exception 'audit source head record is missing' using errcode = 'foreign_key_violation';
    end if;
    if head_hash is distinct from new.hash then
        raise exception 'audit source head record differs' using errcode = 'check_violation';
    end if;
    -- Draft content uses the ONE shared Java canonical helper at the appendOnce boundary. A digest is not authority.
    return new;
end;
$$;
create trigger source_binding_insert_guard before insert on audit.source_binding
    for each row execute function audit.source_binding_insert_guard();
create trigger source_binding_no_change before update or delete on audit.source_binding
    for each row execute function audit.reject_change();
create trigger source_binding_no_truncate before truncate on audit.source_binding
    for each statement execute function audit.reject_change();
