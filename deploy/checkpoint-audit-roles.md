# Checkpoint audit permissions

After applying migration V77.7, substitute the approved runtime role identifier for
`__CHECKPOINT_AUDIT_ROLE__` in [checkpoint-audit-grants.sql](sql/checkpoint-audit-grants.sql)
and apply it as the migration owner. This additive template grants only what the checkpoint
audit source and appender need: read access to actual checkpoint receipts, select/insert on
the retained audit source, and select/insert on audit events and heads. It does not grant
update, delete or truncate on those retained records.

Owned delivery and recovery also need their separately approved registry, inbox, workflow and
task-lease permissions. The template does not configure those permissions, create a login,
revoke earlier grants, configure a datasource, or prove effective permissions in a deployment.
Use a non-owner role without elevated or inherited privileges and review all its existing grants.
The appender and acknowledgement transaction must use the same PostgreSQL database.

The PostgreSQL application test applies this exact template to a fresh restricted test role,
first verifies refusal without it, and then exercises actual checkpoint acknowledgement and
idempotent retry. It also checks that the role cannot modify or remove retained audit sources.
It is evidence for that disposable test configuration, not proof of a deployed role.
