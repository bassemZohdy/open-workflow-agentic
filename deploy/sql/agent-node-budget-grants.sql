-- Run as migration owner after V77.3. Replace the two identifier placeholders.
-- Roles must be distinct fresh non-owner identities, with no elevated attributes,
-- inherited grants or memberships. This template does not provision credentials.
revoke all on task.agent_node_budget from public;
grant usage on schema task to __BUDGET_ALLOCATOR_ROLE__, __BUDGET_EXECUTOR_ROLE__;
grant select, insert on task.agent_node_budget to __BUDGET_ALLOCATOR_ROLE__;
grant select on task.agent_node_budget to __BUDGET_EXECUTOR_ROLE__;
grant update (steps) on task.agent_node_budget to __BUDGET_EXECUTOR_ROLE__;

-- V77.4: only the trusted allocator can create a tool budget; execution can only consume it.
revoke all on task.agent_tool_budget from public;
grant select, insert on task.agent_tool_budget to __BUDGET_ALLOCATOR_ROLE__;
grant select on task.agent_tool_budget to __BUDGET_EXECUTOR_ROLE__;
grant update (calls) on task.agent_tool_budget to __BUDGET_EXECUTOR_ROLE__;

-- V77.5: the allocator retains approved profile data; execution may read but never alter it.
revoke all on task.agent_tool_profile from public;
grant select, insert on task.agent_tool_profile to __BUDGET_ALLOCATOR_ROLE__;
grant select on task.agent_tool_profile to __BUDGET_EXECUTOR_ROLE__;
