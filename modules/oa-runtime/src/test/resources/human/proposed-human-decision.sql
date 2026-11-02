-- Isolated test proposal only. It is not a Flyway migration or a production authority boundary.
create schema human_proposal;
create table human_proposal.task (
  tenant text not null, task_id char(26) not null, instance_id char(26) not null,
  wait_key text not null, version bigint not null check (version >= 0), status text not null,
  initiator_kind text not null, initiator_id text not null, assignee_id text not null,
  separation text not null, risk_tier text not null, policy_pin text not null, due_at timestamptz,
  primary key (tenant, task_id),
  check (status in ('IN_PROGRESS', 'COMPLETED', 'CANCELLED', 'EXPIRED')),
  check (separation in ('NONE', 'INITIATOR_MUST_NOT_ACT')),
  check (initiator_kind in ('USER', 'AGENT', 'WORKFLOW', 'SERVICE')),
  check (risk_tier in ('LOW', 'MEDIUM', 'HIGH')),
  check (length(policy_pin) between 1 and 256)
);
create table human_proposal.decision (
  tenant text not null, decision_id char(26) not null, task_id char(26) not null,
  expected_version bigint not null, actor_id text not null, outcome text not null,
  content text, fingerprint char(64) not null, instance_id char(26) not null, wait_key text not null,
  primary key (tenant, decision_id), unique (tenant, task_id),
  foreign key (tenant, task_id) references human_proposal.task (tenant, task_id),
  check (outcome in ('APPROVE', 'REJECT'))
);
-- A decision is never deliverable merely because its database transaction committed.
create table human_proposal.pending (
  tenant text not null, decision_id char(26) not null,
  audit_event_id char(26) not null references audit.event(event_id),
  audit_occurred_at timestamptz not null,
  audit_state text not null check (audit_state = 'RECORDED'),
  wake_state text not null check (wake_state = 'BLOCKED'),
  primary key (tenant, decision_id),
  foreign key (tenant, decision_id) references human_proposal.decision (tenant, decision_id)
);
