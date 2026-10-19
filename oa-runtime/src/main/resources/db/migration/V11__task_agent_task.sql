-- WP-105a: agent_task is the ownership row for agent threads (ADR 0020, decision 1).
-- The runtime checks it before any read, write or resume of a checkpoint thread.

create table task.agent_task (
    id                   text        primary key
                                     check (id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    instance_id          text        not null references task.instance (id),
    tenant               text        not null check (length(tenant) between 1 and 256),
    owner_kind           text        not null check (owner_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    owner_id             text        not null check (length(owner_id) between 1 and 256),
    thread_name          text        not null unique
                                     check (thread_name ~ '^ws1-[0-9a-f]{64}$'),
    status               text        not null check (status in
                                     ('PENDING', 'RUNNING', 'WAITING_FOR_HUMAN', 'RESUMING',
                                      'COMPLETED', 'FAILED', 'CANCELLED')),
    -- Pinning (WP-302) and the latest checkpoint (WP-201). Written by the agent run path, not by WP-105a.
    graph_version        text        null,
    state_schema_version integer     null check (state_schema_version is null or state_schema_version >= 1),
    latest_checkpoint_id text        null,
    contract             jsonb       not null,
    version              bigint      not null default 0 check (version >= 0),
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now()
);

create index agent_task_instance_idx on task.agent_task (instance_id);
