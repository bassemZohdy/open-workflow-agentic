# Audit source receipt permissions

Apply V75 as the migration owner before preparing these permissions. The runtime
must not use that owner as its audit identity. This procedure prepares dedicated
identities for the central append-once API; it does not select an application data
source or activate a consumer.

Provision two distinct new roles under the deployment's identity/secret procedure.
They must not own protected objects, have elevated attributes, inherit other roles
or carry earlier grants. For example, the permission roles can be created without
login credentials:

```sql
create role oa_audit_source_writer nologin noinherit nosuperuser nocreatedb
    nocreaterole noreplication nobypassrls;
create role oa_audit_source_reader nologin noinherit nosuperuser nocreatedb
    nocreaterole noreplication nobypassrls;
```

Replace `__AUDIT_WRITER_ROLE__` and `__AUDIT_READER_ROLE__` in
[audit-source-grants.sql](sql/audit-source-grants.sql) with those role identifiers
and run the resulting SQL as the migration owner. The writer receives schema
USAGE and SELECT/INSERT on exactly `audit.event`, `audit.head_log` and
`audit.source_binding`; the reader receives USAGE and SELECT only. There are no
default grants on future tables, payload grants or effect-ledger grants.

Do not apply this additive grant template to an existing broadly privileged
identity: it does not revoke earlier or inherited permissions. Keep migration,
retention and source append identities separate. Connect approved logins through
the deployment's credential procedure and verify their effective permissions,
including memberships, before adoption.

`AuditSourceBindingMigrationTest` applies this exact template to fresh non-owner
test identities against real migrated PostgreSQL. It proves append/retry and read
success, denied mutation/TRUNCATE/protected-schema DDL, and denied ledger access.
Its credentials exist only in disposable test databases/roles. Dedicated runtime
data-source wiring and authenticated consumer admission remain the later audit
consumer/WP-304 integration; this change does not switch the default data source.
