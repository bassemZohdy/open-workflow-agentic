# WP-108 audit child fixtures (Claude → GPT)

Test-only prerequisite from main 8727e71 for the proposed V77, whose `effect.audit_delivery.ack_source_key` adds an
incoming FK child of `audit.source_binding`. No production, contract, SQL, build, config or tracking change.

## Problem
`AppendOnlyTest.truncate(parent)` was `truncate audit.<parent>, audit.source_binding`. Once any other table references
`audit.source_binding`, PostgreSQL stops that statement with `0A000` (cannot truncate a table referenced by a foreign key)
before any guard runs, so the 42501 / "audit table <parent> is append-only" assertions could no longer be reached.

## Change
- `AppendOnlyTest`: `truncate(parent)` is now `truncate audit.<parent> cascade`. The parent stays the explicit
  relation (its BEFORE TRUNCATE guard fires first); every incoming FK descendant joins the statement. Assertions are
  unchanged (42501, "append-only", exact parent table name); probes still always roll back. `sourceBindingExists()` is
  gone. Package-private `executeRolledBack(Connection, String)`, `assertRejected`, `assertNamesTable`, `truncate`
  are reusable against a private database.
- New `AuditChildFixtureTest` (7 tests) on `AuditSourceTestDatabase.withProposedV75()` (private UUID DB, foundation 74 +
  pinned V75). It installs ONE clearly labeled TEST-ONLY child `audit_child_fixture.ack_probe(ack_source_key references
  audit.source_binding(source_key))` with `audit.reject_change` guards. It stands for the V77 FK shape only; it is NOT
  coverage of the real V77 migration.
- `AuditAppendOnceTest` untouched (pins V74+V75, no V77).

## Evidence (Java 21, PG16, `mvn -B -o -pl modules/oa-runtime -am test -Dtest=AppendOnlyTest,AuditAppendOnceTest,AuditChildFixtureTest`)
- Baseline: the original listed probe (`truncate audit.event, audit.source_binding` and the `head_log` variant) with the
  child present → `0A000`, not append-only (2 parents asserted in one test).
- Green: corrected cascading probe → 42501 naming `event`, and separately `head_log`.
- Negative controls (private DB only, guards disabled inside a transaction that is always rolled back): with the parent
  truncate guard off, the error is a descendant's (42501 append-only) and the parent-naming assertion FAILS (checked
  for `event` and `head_log`); with parent and `source_binding` guards off, the cascade reaches and names `ack_probe`
  (proves descendants are part of the statement). A final test confirms no non-internal trigger is left disabled.
- Counts: AppendOnlyTest 5, AuditAppendOnceTest 22, AuditChildFixtureTest 7 = 34 run, 0 failures/errors/skipped.

## Limits
- Guards are never disabled in the shared/default DB. Native suite not run; no shared DB reset.
- Ordering relies on PostgreSQL firing BEFORE TRUNCATE triggers explicit relations first (observed, controlled by the
  negative controls). The real V77 shape (schema `effect`, its own guards) is untested until V77 lands.
