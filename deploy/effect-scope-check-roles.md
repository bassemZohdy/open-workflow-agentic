# Effect scope-check permissions

Apply V77 first. Substitute the approved executor role identifier in
[effect-scope-check-grants.sql](sql/effect-scope-check-grants.sql) and apply it as the migration owner.
The template grants schema usage and read access only to the three key columns required to detect a scoped effect.
It grants no access to owner or tenant fields, mutation rights, ledger permissions, audit delivery or credentials.

This is additive: use a dedicated non-owner role and inspect inherited and pre-existing permissions in the deployed
system. The executor also needs separately approved ledger permissions; the V77 deferred event guard requires read access to `effect.audit_source.source_event_id`, including for unscoped writes. Pre-V77 execution additionally needs read
access to the migration history `version` and `success` columns; this post-V77 template does not provide it.
The database tests apply the template to a fresh role and verify scope detection and denial of private columns and
mutations. They do not establish the permissions or identity of a deployed runtime.
