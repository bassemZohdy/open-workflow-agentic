# Manual effect provenance permissions

After V77.9, substitute the approved manual resolver role in
[manual-effect-provenance-grants.sql](sql/manual-effect-provenance-grants.sql) and apply it as the migration owner.
The template supplies read-only access to retained resource-call data and its occurrence key so the resolver can
revalidate resource-scoped provenance. It grants no insert, update or delete permission on either table.

This is additive and does not configure the proposed V80 schema, effect ledger, registry row locks, central audit,
policy, credentials or a datasource. Those require separately approved permissions and verified caller authority.
Use a dedicated, non-owner role without elevated or inherited permissions, and verify its effective permissions in
the actual deployment. The test exercises the exact template with a disposable restricted role and fixture authority;
it is not evidence of production activation or deployed access.
