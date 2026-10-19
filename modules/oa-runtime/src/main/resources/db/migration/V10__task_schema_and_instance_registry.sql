-- WP-105a: platform-owned schema `task` and the instance registry (ADR 0019).
-- The registry is authoritative for lifecycle status, definition id and version, the request idempotency key and
-- history. Flow tables are execution state only.

create schema if not exists task;

create table task.instance (
    id                  text        primary key
                                    check (id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    tenant              text        not null check (length(tenant) between 1 and 256),
    owner_kind          text        not null check (owner_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    owner_id            text        not null check (length(owner_id) between 1 and 256),
    idempotency_key     text        not null check (length(idempotency_key) between 1 and 128),
    request_fingerprint text        not null check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    definition_id       text        not null check (length(definition_id) between 1 and 256),
    definition_version  text        not null check (length(definition_version) between 1 and 64),
    input               jsonb       not null,
    status              text        not null check (status in
                                    ('STARTING', 'RUNNING', 'WAITING', 'CANCEL_REQUESTED',
                                     'CANCELLED', 'COMPLETED', 'FAILED')),
    engine_instance_id  text        null     check (length(engine_instance_id) between 1 and 64),
    start_attempts      integer     not null default 0 check (start_attempts >= 0),
    last_error          text        null     check (length(last_error) <= 256),
    wait_deadline       timestamptz null,
    version             bigint      not null default 0 check (version >= 0),
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    -- A request key is unique per tenant. Two requests with the same key are one instance (ADR 0019, Start).
    constraint instance_tenant_idempotency_key_uk unique (tenant, idempotency_key),
    -- The engine assigns monotonic ids; one engine instance belongs to exactly one registry row.
    constraint instance_engine_instance_id_uk unique (engine_instance_id)
);

-- The reconciler (WP-105c) scans for rows that are STARTING or CANCEL_REQUESTED and stale.
create index instance_status_updated_idx on task.instance (status, updated_at);
