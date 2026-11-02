-- Retained immutable approved-profile data. Existing budgets are deliberately not backfilled.
create table task.agent_tool_profile (
    task_id text primary key references task.agent_tool_budget(task_id),
    profile_id text not null check (length(profile_id) between 1 and 256),
    profile_version text not null check (length(profile_version) between 1 and 128),
    configuration_fingerprint text not null check (configuration_fingerprint ~ '^[0-9a-f]{64}$'),
    agent_id text not null check (length(agent_id) between 1 and 256),
    tools jsonb not null check (jsonb_typeof(tools) = 'array' and jsonb_array_length(tools) <= 100),
    max_calls integer not null check (max_calls between 1 and 100000),
    max_call_nanos bigint not null check (max_call_nanos between 1000000 and 60000000000),
    fingerprint text not null check (fingerprint ~ '^[0-9a-f]{64}$')
);
create function task.guard_agent_tool_profile() returns trigger language plpgsql as $$
begin
    raise exception 'agent tool profile is immutable' using errcode = 'check_violation';
end $$;
create trigger agent_tool_profile_guard before update or delete on task.agent_tool_profile
    for each row execute function task.guard_agent_tool_profile();
create trigger agent_tool_profile_no_truncate before truncate on task.agent_tool_profile
    for each statement execute function task.guard_agent_tool_profile();
