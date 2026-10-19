-- WP-105a: lifecycle history in platform records. One row for every change of an instance or an agent task,
-- written in the same transaction as the change. `version` is the row version after the change, so the history
-- of one row is numbered 0, 1, 2 ... without gaps.

create table task.instance_event (
    instance_id  text        not null references task.instance (id),
    version      bigint      not null check (version >= 0),
    from_status  text        null check (from_status is null or from_status in
                             ('STARTING', 'RUNNING', 'WAITING', 'CANCEL_REQUESTED',
                              'CANCELLED', 'COMPLETED', 'FAILED')),
    to_status    text        not null check (to_status in
                             ('STARTING', 'RUNNING', 'WAITING', 'CANCEL_REQUESTED',
                              'CANCELLED', 'COMPLETED', 'FAILED')),
    reason       text        not null check (length(reason) between 1 and 128),
    actor_kind   text        not null check (actor_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    actor_id     text        not null check (length(actor_id) between 1 and 256),
    occurred_at  timestamptz not null,
    primary key (instance_id, version)
);

create table task.agent_task_event (
    agent_task_id text        not null references task.agent_task (id),
    version       bigint      not null check (version >= 0),
    from_status   text        null check (from_status is null or from_status in
                              ('PENDING', 'RUNNING', 'WAITING_FOR_HUMAN', 'RESUMING',
                               'COMPLETED', 'FAILED', 'CANCELLED')),
    to_status     text        not null check (to_status in
                              ('PENDING', 'RUNNING', 'WAITING_FOR_HUMAN', 'RESUMING',
                               'COMPLETED', 'FAILED', 'CANCELLED')),
    reason        text        not null check (length(reason) between 1 and 128),
    actor_kind    text        not null check (actor_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    actor_id      text        not null check (length(actor_id) between 1 and 256),
    occurred_at   timestamptz not null default now(),
    primary key (agent_task_id, version)
);
