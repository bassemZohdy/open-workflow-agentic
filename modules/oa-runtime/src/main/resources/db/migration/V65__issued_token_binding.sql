-- WP-102 H4. V63 is merged; V64 (the pending inbox increment) must merge before V65.
-- Bind only issuer/jti and authority identifiers, NEVER a bearer, secret or refresh token.
-- The grant and its ancestors are immutable except for status (V60); live status is checked by the resource guard.
alter table identity.delegation_grant
    add constraint grant_id_tenant_unique unique (id, tenant);

create table identity.issued_token_binding (
    issuer text not null check (char_length(issuer) between 1 and 300 and issuer ~ '[^[:space:]]'),
    token_id text not null check (char_length(token_id) between 1 and 300 and token_id ~ '[^[:space:]]'),
    grant_id text not null,
    tenant text not null check (char_length(tenant) between 1 and 256 and tenant ~ '[^[:space:]]'),
    expires_at timestamptz not null,
    created_at timestamptz not null default clock_timestamp(),
    primary key (issuer, token_id),
    foreign key (grant_id, tenant) references identity.delegation_grant (id, tenant)
);

create index issued_token_binding_expires_idx on identity.issued_token_binding (expires_at);
create index issued_token_binding_grant_idx on identity.issued_token_binding (grant_id);

-- Even an identical UPDATE is refused. Idempotent bind is INSERT/ON CONFLICT then a fresh exact-content read;
-- a conflicting issuer+jti must never silently acquire another grant, tenant or signed expiry.
create function identity.issued_token_binding_guard() returns trigger language plpgsql as $$
begin
    if tg_op in ('UPDATE', 'TRUNCATE') then
        raise exception 'issued_token_binding is append-only: % is refused', tg_op
            using errcode = 'insufficient_privilege';
    end if;
    if old.expires_at > clock_timestamp() - interval '1 minute' then
        raise exception 'issued_token_binding is retained until a minute after signed expiry'
            using errcode = 'insufficient_privilege';
    end if;
    return old;
end;
$$;

create trigger issued_token_binding_guard before update or delete on identity.issued_token_binding
    for each row execute function identity.issued_token_binding_guard();
create trigger issued_token_binding_no_truncate before truncate on identity.issued_token_binding
    for each statement execute function identity.issued_token_binding_guard();
