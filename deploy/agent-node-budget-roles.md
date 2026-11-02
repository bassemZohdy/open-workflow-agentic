# Agent node budget permissions

Apply migrations V77.3, V77.4 and V77.5 as the migration owner. Provision two distinct, fresh non-owner
roles with no elevated attributes, inherited permissions or role memberships. The
allocator is trusted to insert a budget only in the same transaction as a genuinely
new approved resource-task allocation. The executor reads the existing budget and
updates only its step or tool-call count. Neither role may own the tables or assume the other role.
The opt-in proposed V78 allocation adapter can insert the tool budget in the same transaction as a fresh task and node
budget. Its caller must supply the approved tool-call ceiling; this is not an approval source or production activation.
The profiled variant also retains immutable tool profile data; the caller is responsible for obtaining real approval,
and the local gateway does not yet enforce the retained profile.
The separate preparation method must only be used when the caller has independently established that the task is new.
Never backfill based on a missing row.

Replace `__BUDGET_ALLOCATOR_ROLE__` and `__BUDGET_EXECUTOR_ROLE__` in
[agent-node-budget-grants.sql](sql/agent-node-budget-grants.sql) with those role
identifiers and run the result as migration owner. The template revokes table-level grants
from PUBLIC and grants no permission on other tables. Verify that no prior column-level
PUBLIC grants exist; a table-level revoke does not remove them. It is additive for the named
roles: do not use existing identities with broader or inherited access. Verify
membership, column privileges, ownership and effective login permissions before use.

The allocation transaction also needs separately scoped permissions on its own
resource-allocation and identity tables. This template grants none of them and does
not configure credentials, data sources, the proposed V78 schema, an approved-task
producer or an execution fence. The current application still uses its configured
default datasource; do not treat this template as a deployed security boundary until
separate allocator and executor identities are wired and verified end to end.

`NodeBudgetRolesTest` applies the exact template to fresh non-owner PostgreSQL roles,
checks that an executor can consume retained node and tool budgets, and verifies denied insertion,
reset, deletion, privilege crossing and protected-schema DDL. It is local role evidence,
not evidence of deployment configuration or whole-project completion.
