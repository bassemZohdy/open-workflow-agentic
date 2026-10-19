-- WP-105c: the bridge kind and the liveness lease of an agent run. The reconciler reads them to decide whether a
-- run recorded as RUNNING or RESUMING was left behind by a process that died (ADR 0023, decision 7).
-- A null bridge is unknown; the reconciler reports such a run and restarts nothing.

alter table task.agent_task
    add column bridge      text        null check (bridge is null or bridge in ('HTTP_SAME_APP', 'IN_PROCESS', 'ASYNC_CALLBACK')),
    add column lease_owner text        null check (lease_owner is null or length(lease_owner) between 1 and 256),
    add column lease_until timestamptz null;

create index agent_task_lease_idx on task.agent_task (status, lease_until);
