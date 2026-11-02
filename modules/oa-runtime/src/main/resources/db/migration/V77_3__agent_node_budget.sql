-- Retained per-task admission and deadline. Task IDs cannot be rebound or reset by runtime updates.
create table task.agent_node_budget (
    task_id text primary key check (task_id ~ '^[0-7][0-9A-HJKMNP-TV-Z]{25}$'),
    binding text not null check (binding ~ '^[0-9a-f]{64}$'),
    max_steps integer not null check (max_steps between 1 and 1000),
    started_at timestamptz not null default clock_timestamp(),
    deadline timestamptz not null,
    steps integer not null default 0 check (steps >= 0 and steps <= max_steps),
    check (deadline > started_at)
);

create function task.guard_agent_node_budget() returns trigger language plpgsql as $$
begin
    if TG_OP <> 'UPDATE' or new.task_id <> old.task_id or new.binding <> old.binding
       or new.max_steps <> old.max_steps or new.started_at <> old.started_at or new.deadline <> old.deadline
       or new.steps <> old.steps + 1 or new.steps > old.max_steps or clock_timestamp() >= old.deadline then
        raise exception 'agent node budget is immutable except for timely single-step admission' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger agent_node_budget_guard before update or delete on task.agent_node_budget
    for each row execute function task.guard_agent_node_budget();
create trigger agent_node_budget_no_truncate before truncate on task.agent_node_budget
    for each statement execute function task.guard_agent_node_budget();
