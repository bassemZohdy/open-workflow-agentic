-- One row per audit event. The hash covers every column except the generated ones, which are derived from the
-- attributes, so a row cannot name one chain or instance while its hashed content names another.
-- Reserved attribute keys: oa.chain, oa.tenant, oa.instance, oa.correlation, oa.payload.form, oa.payload.ref,
-- oa.payload.digest, stamp.* and field.* (see docs/audit.md).
create table if not exists audit.event (
  event_id       text        primary key check (event_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
  attributes     jsonb       not null check (jsonb_typeof(attributes) = 'object'),
  chain_id       text        generated always as (coalesce(attributes ->> 'oa.chain', 'default')) stored not null,
  seq            bigint      not null check (seq >= 1),
  occurred_at    timestamptz not null,
  actor_kind     text        not null check (actor_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
  actor_id       text        not null check (char_length(actor_id) between 1 and 256),
  event_type     text        not null check (char_length(event_type) between 1 and 512),
  resource_type  text        not null check (char_length(resource_type) between 1 and 512),
  resource_id    text        not null check (char_length(resource_id) between 1 and 512),
  tenant_id      text        generated always as (attributes ->> 'oa.tenant') stored,
  instance_id    text        generated always as (attributes ->> 'oa.instance') stored,
  correlation_id text        generated always as (attributes ->> 'oa.correlation') stored,
  previous_hash  text        not null check (previous_hash ~ '^[0-9a-f]{64}$'),
  hash           text        not null check (hash ~ '^[0-9a-f]{64}$'),
  constraint event_chain_seq_unique unique (chain_id, seq)
);

create index if not exists event_instance_idx on audit.event (instance_id);
create index if not exists event_correlation_idx on audit.event (correlation_id);
create index if not exists event_type_time_idx on audit.event (event_type, occurred_at);
create index if not exists event_occurred_idx on audit.event (occurred_at);
