-- WP-107 storage increment. V63 is the pending effect ledger migration; integrate in version order.
create schema if not exists inbox;

create table inbox.message (
    sequence bigint generated always as identity unique not null,
    tenant varchar(256) not null,
    source varchar(1024) not null,
    event_id varchar(256) not null,
    type varchar(256) not null,
    instance_id text not null references task.instance(id),
    correlation_key varchar(256) not null,
    data_json text not null check (octet_length(data_json) <= 65536),
    fingerprint varchar(64) not null check (fingerprint ~ '^[0-9a-f]{64}$'),
    received_at timestamptz not null default clock_timestamp(),
    retain_until timestamptz not null,
    primary key (tenant, source, event_id),
    check (length(trim(tenant)) > 0 and length(trim(source)) > 0 and length(trim(event_id)) > 0),
    check (length(trim(type)) > 0 and length(trim(correlation_key)) > 0)
);
create index inbox_pending_match on inbox.message (tenant, instance_id, correlation_key, type, sequence);

-- A durable wake intent, not proof that an in-memory Flow publisher accepted an event.
-- One event consumes one logical wait; the logical wait key includes its persisted occurrence identity.
create table inbox.signal (
    tenant varchar(256) not null,
    instance_id text not null references task.instance(id),
    wait_key varchar(256) not null,
    source varchar(1024) not null,
    event_id varchar(256) not null,
    matched_at timestamptz not null default clock_timestamp(),
    primary key (tenant, instance_id, wait_key),
    unique (tenant, source, event_id),
    foreign key (tenant, source, event_id) references inbox.message(tenant, source, event_id)
);
