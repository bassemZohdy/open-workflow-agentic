-- PROPOSAL for GPT (owner of db/migration): copy to the next free Vnn__identity_issued_token_binding.sql. See docs/handoffs/claude/WP-102.md section 7.
create table identity.issued_token_binding (
    issuer     text        not null check (char_length(issuer) between 1 and 300),
    token_id   text        not null check (char_length(token_id) between 1 and 300),
    grant_id   text        not null references identity.delegation_grant (id),
    tenant     text        not null check (length(tenant) between 1 and 256),
    expires_at timestamptz not null,
    created_at timestamptz not null default now(),
    primary key (issuer, token_id)
);
create index issued_token_binding_expires_idx on identity.issued_token_binding (expires_at);
create index issued_token_binding_grant_idx on identity.issued_token_binding (grant_id);
create function identity.issued_token_binding_guard() returns trigger language plpgsql as $$
begin
    if tg_op = 'UPDATE' then
        raise exception 'issued_token_binding is append-only';
    end if;
    if old.expires_at > now() - interval '1 minute' then
        raise exception 'issued_token_binding rows are kept until a minute after the token expires';
    end if;
    return old;
end $$;
create trigger issued_token_binding_guard before update or delete on identity.issued_token_binding
    for each row execute function identity.issued_token_binding_guard();
