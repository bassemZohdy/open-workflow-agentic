# Retained wait profile permissions

Apply migration V77.8 first. Substitute the approved, distinct writer and reader role identifiers in
[wait-profile-grants.sql](sql/wait-profile-grants.sql), then apply it as the migration owner. The
writer receives select and insert on `inbox.wait_profile`; the reader receives select only. Neither
receives update, delete or truncate on the retained profile through this template.

This is an additive template. Registration and `findProfile` also access existing `task.instance`
and `inbox.delivery` records and require their separately approved privileges. The template does
not configure those privileges, create credentials, remove prior or inherited grants, or select a
datasource. Use distinct, non-owner, non-elevated roles and verify effective permissions in the
actual deployment. Existing digest-only waits have no guessed profile; an exact re-registration
can retain it without changing the original deadline.

The PostgreSQL test uses fresh restricted test roles to exercise the exact template. That test does
not prove deployed permissions, tenant authorization, replay authority or listener ownership.
