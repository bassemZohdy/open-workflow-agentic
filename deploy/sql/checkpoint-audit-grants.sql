-- Apply after V77.7 as migration owner. Replace the identifier placeholder.
-- Additive checkpoint-audit privileges only; the runtime still requires its separately governed inbox/registry grants.
grant usage on schema inbox, audit to __CHECKPOINT_AUDIT_ROLE__;
grant select on inbox.checkpoint_receipt to __CHECKPOINT_AUDIT_ROLE__;
grant select, insert on inbox.checkpoint_audit_source to __CHECKPOINT_AUDIT_ROLE__;
grant select, insert on audit.event, audit.head_log to __CHECKPOINT_AUDIT_ROLE__;
