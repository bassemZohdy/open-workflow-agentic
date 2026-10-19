-- WP-108. Additive: V35's historical resume-epoch comment is not changed. Epoch is never an effect identity.
create schema if not exists effect;

create table effect.ledger (
    thread_name varchar(68) not null check (thread_name ~ '^ws1-[0-9a-f]{64}$'),
    node_id varchar(128) not null check (length(trim(node_id)) > 0),
    idempotency_key varchar(200) not null check (length(trim(idempotency_key)) > 0),
    target varchar(256) not null check (length(trim(target)) > 0),
    state varchar(20) not null check (state in ('INTENDED','CONFIRMED','FAILED','COMPENSATED')),
    detail varchar(1000),
    recorded_at timestamptz not null,
    created_at timestamptz not null default clock_timestamp(),
    request_fingerprint varchar(64) check (request_fingerprint ~ '^[0-9a-f]{64}$'),
    attempted boolean not null default false,
    replay_until timestamptz,
    purpose varchar(16) not null default 'EFFECT' check (purpose in ('EFFECT','COMPENSATION')),
    primary key (thread_name, node_id, idempotency_key)
);
create index effect_unresolved on effect.ledger (thread_name, created_at) where state = 'INTENDED';

create table effect.event (
    id bigint generated always as identity primary key,
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    from_state varchar(20),
    to_state varchar(20) not null,
    reason varchar(64) not null,
    occurred_at timestamptz not null default clock_timestamp(),
    foreign key (thread_name, node_id, idempotency_key)
        references effect.ledger (thread_name, node_id, idempotency_key)
);

create table effect.manual_resolution (
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    reason varchar(64) not null,
    first_seen timestamptz not null default clock_timestamp(),
    last_seen timestamptz not null default clock_timestamp(),
    closed_at timestamptz,
    primary key (thread_name, node_id, idempotency_key),
    foreign key (thread_name, node_id, idempotency_key)
        references effect.ledger (thread_name, node_id, idempotency_key)
);

create table effect.compensation (
    thread_name varchar(68) not null,
    node_id varchar(128) not null,
    idempotency_key varchar(200) not null,
    compensation_node varchar(128) not null,
    compensation_key varchar(200) not null,
    primary key (thread_name, node_id, idempotency_key),
    unique (thread_name, compensation_node, compensation_key),
    check (node_id <> compensation_node or idempotency_key <> compensation_key),
    foreign key (thread_name, node_id, idempotency_key)
        references effect.ledger (thread_name, node_id, idempotency_key),
    foreign key (thread_name, compensation_node, compensation_key)
        references effect.ledger (thread_name, node_id, idempotency_key)
);
