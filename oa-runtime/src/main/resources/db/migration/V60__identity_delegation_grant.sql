-- WP-102: delegation grants (ADR 0021 section 1, docs/identity.md section 3).
-- A grant is OURS: a user lets an agent act for them, within scopes, until an expiry. It is not a token and it is not
-- stored in the identity provider. An agent may delegate part of its grant to another agent; the child can only
-- narrow (scopes are a subset, the expiry is not later), and the chain of agents is recorded.
--
-- The rules are enforced in the service AND here, so a bug or a direct write cannot widen authority:
--   * a child grant must have an ACTIVE parent of the same tenant and user, with scopes that contain the child's,
--     an expiry that is not later, no repeated agent in the chain (no cycles), and a consistent depth and chain;
--   * only the status may change after insert, and only from ACTIVE to REVOKED or EXPIRED, never back;
--   * a row is never deleted: revoked and expired grants remain as the record of what was granted.

create schema if not exists identity;

create table identity.delegation_grant (
    id              text        primary key
                                check (id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    tenant          text        not null check (length(tenant) between 1 and 256),
    -- The delegating user. Only a user can start a chain.
    subject_kind    text        not null check (subject_kind = 'USER'),
    subject_id      text        not null check (length(subject_id) between 1 and 256),
    -- The delegate: an agent, a workflow or a service identity.
    agent_kind      text        not null check (agent_kind in ('AGENT', 'WORKFLOW', 'SERVICE')),
    agent_id        text        not null check (length(agent_id) between 1 and 256),
    scopes          text[]      not null check (cardinality(scopes) between 1 and 100),
    parent_grant_id text        null references identity.delegation_grant (id),
    -- Agent ids from the first delegate to this grant's agent. chain[depth + 1] = agent_id.
    depth           integer     not null check (depth between 0 and 7),
    chain           text[]      not null,
    status          text        not null check (status in ('ACTIVE', 'REVOKED', 'EXPIRED')),
    created_at      timestamptz not null,
    created_by_kind text        not null check (created_by_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    created_by_id   text        not null check (length(created_by_id) between 1 and 256),
    expires_at      timestamptz not null,
    revoked_at      timestamptz null,
    revoked_by_id   text        null check (length(revoked_by_id) between 1 and 256),
    revoke_reason   text        null check (length(revoke_reason) between 1 and 128),
    version         bigint      not null default 0 check (version >= 0),
    constraint grant_expiry_after_creation check (expires_at > created_at),
    constraint grant_chain_shape check (cardinality(chain) = depth + 1 and chain[depth + 1] = agent_id),
    constraint grant_root_has_no_parent check ((depth = 0) = (parent_grant_id is null)),
    constraint grant_revoked_shape check ((status = 'REVOKED') = (revoked_at is not null)),
    constraint grant_revoke_reason_shape check ((status = 'REVOKED') or revoke_reason is null)
);

create index grant_subject_agent_idx on identity.delegation_grant (tenant, subject_id, agent_id, status);
create index grant_parent_idx on identity.delegation_grant (parent_grant_id) where parent_grant_id is not null;
create index grant_active_expiry_idx on identity.delegation_grant (expires_at) where status = 'ACTIVE';

create function identity.guard_grant_insert() returns trigger
    language plpgsql as $$
declare
    parent identity.delegation_grant%rowtype;
begin
    if new.status <> 'ACTIVE' or new.revoked_at is not null or new.version <> 0 then
        raise exception 'grant narrowing: a new grant must be ACTIVE, version 0' using errcode = 'check_violation';
    end if;
    if new.parent_grant_id is null then
        if new.depth <> 0 or new.chain <> array[new.agent_id] then
            raise exception 'grant narrowing: a root grant has depth 0 and a chain of its own agent'
                using errcode = 'check_violation';
        end if;
        return new;
    end if;
    select * into parent from identity.delegation_grant where id = new.parent_grant_id for share;
    if not found then
        raise exception 'grant narrowing: the parent grant does not exist' using errcode = 'check_violation';
    end if;
    if parent.status <> 'ACTIVE' or parent.expires_at <= new.created_at then
        raise exception 'grant narrowing: the parent grant is not active' using errcode = 'check_violation';
    end if;
    if parent.tenant <> new.tenant or parent.subject_id <> new.subject_id then
        raise exception 'grant narrowing: tenant and user must be those of the parent' using errcode = 'check_violation';
    end if;
    if not (new.scopes <@ parent.scopes) then
        raise exception 'grant narrowing: scopes are not a subset of the parent''s scopes'
            using errcode = 'check_violation';
    end if;
    if new.expires_at > parent.expires_at then
        raise exception 'grant narrowing: the expiry is later than the parent''s expiry'
            using errcode = 'check_violation';
    end if;
    if new.agent_id = any (parent.chain) then
        raise exception 'grant narrowing: the agent is already in the delegation chain' using errcode = 'check_violation';
    end if;
    if new.depth <> parent.depth + 1 or new.chain <> parent.chain || new.agent_id then
        raise exception 'grant narrowing: depth and chain must extend the parent''s chain'
            using errcode = 'check_violation';
    end if;
    return new;
end;
$$;

create trigger delegation_grant_guard_insert
    before insert on identity.delegation_grant
    for each row execute function identity.guard_grant_insert();

create function identity.guard_grant_change() returns trigger
    language plpgsql as $$
begin
    if tg_op <> 'UPDATE' then
        raise exception 'delegation grants are never deleted: % on %', tg_op, tg_table_name
            using errcode = 'insufficient_privilege';
    end if;
    if old.status <> 'ACTIVE' then
        raise exception 'a % grant cannot change', old.status using errcode = 'insufficient_privilege';
    end if;
    if new.status not in ('REVOKED', 'EXPIRED') then
        raise exception 'a grant can only move from ACTIVE to REVOKED or EXPIRED' using errcode = 'insufficient_privilege';
    end if;
    if new.id is distinct from old.id or new.tenant is distinct from old.tenant
        or new.subject_kind is distinct from old.subject_kind or new.subject_id is distinct from old.subject_id
        or new.agent_kind is distinct from old.agent_kind or new.agent_id is distinct from old.agent_id
        or new.scopes is distinct from old.scopes or new.parent_grant_id is distinct from old.parent_grant_id
        or new.depth is distinct from old.depth or new.chain is distinct from old.chain
        or new.created_at is distinct from old.created_at or new.created_by_kind is distinct from old.created_by_kind
        or new.created_by_id is distinct from old.created_by_id or new.expires_at is distinct from old.expires_at then
        raise exception 'only the status of a grant can change' using errcode = 'insufficient_privilege';
    end if;
    return new;
end;
$$;

create trigger delegation_grant_guard_change
    before update or delete on identity.delegation_grant
    for each row execute function identity.guard_grant_change();

create trigger delegation_grant_no_truncate
    before truncate on identity.delegation_grant
    for each statement execute function identity.guard_grant_change();
