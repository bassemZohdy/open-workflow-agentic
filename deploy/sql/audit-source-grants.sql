-- Run as the migration owner after V75. Replace the two identifier placeholders.
-- Roles must be distinct, freshly provisioned non-owner identities without other
-- grants, elevated attributes or memberships. See deploy/audit-source-roles.md.
-- This procedure neither provisions credentials nor changes application wiring.
grant usage on schema audit to __AUDIT_WRITER_ROLE__, __AUDIT_READER_ROLE__;
grant select, insert on audit.event, audit.head_log, audit.source_binding to __AUDIT_WRITER_ROLE__;
grant select on audit.event, audit.head_log, audit.source_binding to __AUDIT_READER_ROLE__;
