-- WP-105b: task_view, a read model with one row per workflow instance (plan section 2). It is a projection of the
-- outbox, never a source of truth: TaskViewRebuilder recreates it from the registry and history tables.
--
-- The projector applies the events of one instance in outbox order and records the last applied outbox id, so a
-- duplicate or older event is ignored. instance_version and agent_outbox_id are kept so that a rebuild can be
-- compared with the live projection column by column.

create table task.task_view (
    instance_id         text         primary key
                                     check (length(instance_id) = 26),
    tenant              text         not null check (length(tenant) between 1 and 256),
    owner_kind          text         not null check (owner_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
    owner_id            text         not null check (length(owner_id) between 1 and 256),
    definition_id       text         not null check (length(definition_id) between 1 and 256),
    definition_version  text         not null check (length(definition_version) between 1 and 64),
    instance_status     text         not null check (instance_status in
                                     ('STARTING', 'RUNNING', 'WAITING', 'CANCEL_REQUESTED',
                                      'CANCELLED', 'COMPLETED', 'FAILED')),
    instance_version    bigint       not null check (instance_version >= 0),
    agent_task_id       text         null check (length(agent_task_id) = 26),
    agent_task_status   text         null check (agent_task_status is null or agent_task_status in
                                     ('PENDING', 'RUNNING', 'WAITING_FOR_HUMAN', 'RESUMING',
                                      'COMPLETED', 'FAILED', 'CANCELLED')),
    agent_outbox_id     bigint       null,
    waiting_for_human   boolean      generated always as (
                                     coalesce(instance_status = 'WAITING', false)
                                     or coalesce(agent_task_status = 'WAITING_FOR_HUMAN', false)) stored,
    last_reason         text         not null check (length(last_reason) between 1 and 128),
    last_transition_at  timestamptz  not null,
    last_outbox_id      bigint       not null check (last_outbox_id >= 1),
    updated_at          timestamptz  not null
);

create index task_view_tenant_status_idx on task.task_view (tenant, instance_status, instance_id);
create index task_view_tenant_definition_idx on task.task_view (tenant, definition_id, definition_version, instance_id);
create index task_view_waiting_idx on task.task_view (tenant, instance_id) where waiting_for_human;
