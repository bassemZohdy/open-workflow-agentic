# Research checkpoint ownership and permissions

V75.1 owns the pinned PostgresSaver 1.9.5/v1.1 profile in `public`. Apply it with
the migration identity before a graph touches the store. Both missing tables may
be created; compatible legacy tables retain their original rows, ids and private
text. Partial or incompatible stores refuse. Existing tables require an exclusive
NOWAIT lock: quiesce their users for adoption rather than retrying mutation or
terminating another session. Do not baseline, drop, repair or backfill history.

Provision two distinct new non-owner identities through the deployment's secret
procedure. They must have no elevated attributes, ownership, earlier grants or
memberships that provide other powers. Permission roles can be provisioned without
credentials, using `NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
NOREPLICATION NOBYPASSRLS`. Replace the identifiers in
[research-checkpoint-grants.sql](sql/research-checkpoint-grants.sql) and execute it
as the migration owner. The template is additive; it cannot downgrade an existing
broadly privileged login or revoke PUBLIC/inherited privileges.

The writer needs SELECT/INSERT/UPDATE on `public.lg4jthread` and
`public.lg4jcheckpoint`: the actual saver changes checkpoint ids/state and releases
threads through UPDATE. Checkpoints are mutable, unlike audit source receipts.
It receives no DELETE, TRUNCATE, protected-schema CREATE/ALTER, audit or ledger
permission. The reader receives SELECT only. Reader components use bounded,
read-only SQL; do not use a saver operation that may initialize a thread as a
read-only inspection API. Cleanup and retention require a separately reviewed role.

The supported namespace is explicitly `public`; application configuration and
the checkpoint connection's search path must agree with the migrated target.
Do not change global baseline-on-migrate or silently create another namespace.
A schema match is not private-state validity, complete history, current caller
authority, execution ownership or permission to enter a graph.

This change provides the schema and the permission procedure. It does not switch
the default workflow data source to a checkpoint-only role: that would break other
runtime stores. Dedicated checkpoint writer/reader data-source configuration and
credentials need the owned consumer/WP-304 handoff. Claude owns disabling
ResearchAgent's table creation only after the reviewed migration prerequisite,
with startup/current-profile refusal before graph/provider work. No runtime
consumer or private-output authority is activated by these grants.
