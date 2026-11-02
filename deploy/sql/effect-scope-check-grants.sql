-- Apply after V77 as the migration owner. Replace the role identifier placeholder.
-- Scope-key detection only; effect ledger and audit-delivery privileges are separate.
grant usage on schema effect to __EFFECT_EXECUTOR_ROLE__;
grant select (thread_name,node_id,idempotency_key) on effect.audit_scope to __EFFECT_EXECUTOR_ROLE__;
