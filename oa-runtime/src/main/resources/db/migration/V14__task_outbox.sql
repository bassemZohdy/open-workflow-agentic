-- WP-105b: transactional outbox (plan section 2, canonical progress model; ADR 0002).
-- One row per history row of an instance or an agent task, written in the same transaction as that history row
-- (see OutboxWriter). The relay (OutboxRelay) delivers rows to consumers, in order within a partition.
--
-- Ordering. partition_key is the workflow instance id for both aggregate types, so an instance and its agent tasks
-- form one ordered stream. Writers serialize on the instance row, so the identity order of rows in a partition is
-- the commit order. The relay never claims a row while an earlier row of its partition is unpublished (published_at
-- null, whether it is in backoff, failed, or claimed by another relay). Other partitions are not affected.
--
-- Delivery is at least once. A row is marked published in the same transaction as the consumers' writes, so a
-- crash redelivers the row; consumers must be idempotent.
--
-- Poison rows. After max-attempts a row gets failed_at and last_error (class name only). It stays visible and keeps
-- blocking its own partition, so no later event of that instance is delivered past it. An operator re-queues it
-- with OutboxStore.requeue, which clears failed_at and attempts.

create table task.outbox (
    id                bigint       generated always as identity primary key,
    aggregate_type    text         not null check (aggregate_type in ('INSTANCE', 'AGENT_TASK')),
    aggregate_id      text         not null check (length(aggregate_id) between 1 and 64),
    aggregate_version bigint       not null check (aggregate_version >= 0),
    partition_key     text         not null check (length(partition_key) between 1 and 64),
    event_type        text         not null check (length(event_type) between 1 and 128),
    payload           jsonb        not null,
    occurred_at       timestamptz  not null,
    published_at      timestamptz  null,
    attempts          integer      not null default 0 check (attempts >= 0),
    next_attempt_at   timestamptz  not null default now(),
    failed_at         timestamptz  null,
    last_error        text         null check (length(last_error) <= 256),
    constraint outbox_aggregate_version_uk unique (aggregate_type, aggregate_id, aggregate_version),
    constraint outbox_published_or_failed_ck check (published_at is null or failed_at is null)
);

-- The relay's claim scans unpublished rows in id order and checks earlier unpublished rows of the same partition.
create index outbox_unpublished_idx on task.outbox (id) where published_at is null;
create index outbox_partition_unpublished_idx on task.outbox (partition_key, id) where published_at is null;
create index outbox_failed_idx on task.outbox (id) where failed_at is not null;
