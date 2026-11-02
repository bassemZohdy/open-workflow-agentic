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

create function human_proposal.guard_task() returns trigger language plpgsql as $$
begin
  if tg_op <> 'UPDATE' then
    raise exception 'human task history is retained' using errcode='insufficient_privilege';
  end if;
  if row(new.tenant,new.task_id,new.instance_id,new.wait_key,new.initiator_kind,new.initiator_id,
         new.risk_tier,new.policy_pin,new.separation)
       is distinct from row(old.tenant,old.task_id,old.instance_id,old.wait_key,old.initiator_kind,old.initiator_id,
                            old.risk_tier,old.policy_pin,old.separation)
     or new.version <> old.version + 1 or old.status <> 'IN_PROGRESS' then
    raise exception 'invalid human task mutation' using errcode='check_violation';
  end if;
  return new;
end $$;
create trigger human_task_guard before update or delete on human_proposal.task
  for each row execute function human_proposal.guard_task();
create trigger human_task_no_truncate before truncate on human_proposal.task
  for each statement execute function human_proposal.guard_task();

create function human_proposal.reject_history_change() returns trigger language plpgsql as $$
begin
  raise exception 'human decision history is immutable' using errcode='insufficient_privilege';
end $$;
create trigger human_decision_no_change before update or delete on human_proposal.decision
  for each row execute function human_proposal.reject_history_change();
create trigger human_decision_no_truncate before truncate on human_proposal.decision
  for each statement execute function human_proposal.reject_history_change();
create trigger human_pending_no_change before update or delete on human_proposal.pending
  for each row execute function human_proposal.reject_history_change();
create trigger human_pending_no_truncate before truncate on human_proposal.pending
  for each statement execute function human_proposal.reject_history_change();

-- At commit, a completed task, its one decision, its real audit event and its blocked wakeup must all agree.
-- These retained facts are not a policy decision or permission to deliver.
create function human_proposal.require_complete_decision() returns trigger language plpgsql as $$
declare task_tenant text; task_identifier text; task_status text;
begin
  task_tenant := new.tenant;
  if tg_table_name = 'pending' then
    select task_id into task_identifier from human_proposal.decision
      where tenant=new.tenant and decision_id=new.decision_id;
  else
    task_identifier := new.task_id;
  end if;
  select status into task_status from human_proposal.task where tenant=task_tenant and task_id=task_identifier;
  if task_status is null then raise exception 'human task is missing' using errcode='check_violation'; end if;
  if task_status <> 'COMPLETED' then
    if exists(select 1 from human_proposal.decision where tenant=task_tenant and task_id=task_identifier) then
      raise exception 'decision requires a completed task' using errcode='check_violation';
    end if;
    return null;
  end if;
  if not exists (
    select 1 from human_proposal.task t
    join human_proposal.decision d on d.tenant=t.tenant and d.task_id=t.task_id
    join human_proposal.pending p on p.tenant=d.tenant and p.decision_id=d.decision_id
    join audit.event e on e.event_id=p.audit_event_id
    where t.tenant=task_tenant and t.task_id=task_identifier
      and t.version=d.expected_version+1 and t.instance_id=d.instance_id and t.wait_key=d.wait_key
      and t.assignee_id=d.actor_id and p.audit_event_id=d.decision_id and p.audit_occurred_at=e.occurred_at
      and p.audit_state='RECORDED' and p.wake_state='BLOCKED'
      and e.actor_kind='USER' and e.actor_id=d.actor_id and e.event_type='human.decision.recorded'
      and e.resource_type='human-task' and e.resource_id=d.task_id
      and e.attributes->>'oa.chain'='human' and e.attributes->>'oa.tenant'=t.tenant
      and e.attributes->>'oa.instance'=t.instance_id and e.attributes->>'human.outcome'=d.outcome
      and e.attributes->>'human.expected-version'=d.expected_version::text
  ) then raise exception 'human decision, audit or wakeup is incomplete' using errcode='check_violation'; end if;
  return null;
end $$;
create constraint trigger human_task_complete after insert or update on human_proposal.task
  deferrable initially deferred for each row execute function human_proposal.require_complete_decision();
create constraint trigger human_decision_complete after insert on human_proposal.decision
  deferrable initially deferred for each row execute function human_proposal.require_complete_decision();
create constraint trigger human_pending_complete after insert on human_proposal.pending
  deferrable initially deferred for each row execute function human_proposal.require_complete_decision();
