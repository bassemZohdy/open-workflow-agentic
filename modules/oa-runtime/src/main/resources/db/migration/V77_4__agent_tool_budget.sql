-- Registered by a trusted task-start producer only. Existing tasks are never automatically backfilled.
create table task.agent_tool_budget (
    task_id text primary key references task.agent_node_budget(task_id),
    max_calls integer not null check (max_calls between 1 and 100000),
    calls integer not null default 0 check (calls >= 0 and calls <= max_calls)
);

create function task.guard_agent_tool_budget() returns trigger language plpgsql as $$
begin
    if TG_OP <> 'UPDATE' or new.task_id <> old.task_id or new.max_calls <> old.max_calls
       or new.calls <> old.calls + 1 or new.calls > old.max_calls
       or not exists (select 1 from task.agent_node_budget n
                      where n.task_id = old.task_id and clock_timestamp() < n.deadline) then
        raise exception 'agent tool budget is immutable except for timely single-call admission' using errcode = 'check_violation';
    end if;
    return new;
end $$;
create trigger agent_tool_budget_guard before update or delete on task.agent_tool_budget
    for each row execute function task.guard_agent_tool_budget();
create trigger agent_tool_budget_no_truncate before truncate on task.agent_tool_budget
    for each statement execute function task.guard_agent_tool_budget();
