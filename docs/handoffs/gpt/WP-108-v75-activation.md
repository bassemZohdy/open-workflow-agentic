# WP-108: V75 activation prerequisites (2026-10-26)

GPT owns the next additive migration and deployment grant/test increment. Claude owns
the audit test fixtures that must remain valid when the retained child table reaches
the shared migrated database. This handoff requests fixture preparation first; it
does not activate the migration, CDI consumer, authority or effect delivery.

## Exact preparation requested from Claude

Change only these owned test files on a separate branch from current main:

- `modules/oa-runtime/src/test/java/io/github/bassemzohdy/openworkflow/agentic/runtime/audit/AuditSourceTestDatabase.java`
- `modules/oa-runtime/src/test/java/io/github/bassemzohdy/openworkflow/agentic/runtime/audit/AppendOnlyTest.java`

Pin the private proposal foundation to Flyway target 74. The missing-schema test
must still genuinely lack source_binding after production V75 lands; proposal tests
must still apply their unchanged, SHA-256-pinned proposal exactly once. Preserve the
missing-schema refusal and every append-once failure/concurrency/least-privilege test.
Update comments to describe that explicit pre-V75 foundation. No applied migration
or expected digest changes.

For shared-database parent TRUNCATE probes, include audit.source_binding when that
table exists. Execute those probes in a transaction that ALWAYS rolls back, even if
TRUNCATE unexpectedly succeeds. Keep the strong 42501/append-only assertion. Do not
weaken it to the earlier foreign-key 0A000 refusal, disable a guard or delete data.
Before V75 exists, the same tests must still pass against the current schema.

GPT independently reviews the exact fixture commit and relevant PostgreSQL tests,
then integrates the preparation before any migration activation depends on it.

## Following GPT increment

Activate the agreed V75 source-binding DDL after existing V73/V74, preserving the
proposal's schema, source-identity calculation, foreign keys and immutable guards.
Only update the applied copy's now-obsolete proposal comments; retain the proposal
fixture and its expected digest. Never rewrite earlier migrations or backfill
guessed sources onto existing events.

Add migration-first tests in the GPT-owned application lifecycle test area using
the real complete Flyway path and the concrete append-once API. Prove actual V75
presence and Flyway validation, exact duplicate receipt, mismatched source refusal,
retained-event/head constraints and append-only denial. Demonstrate fresh migration
and upgrade from a nonempty V74 audit foundation without changing prior events.

Provide an explicit deployment writer/read grant procedure: the writer needs only
schema USAGE and SELECT/INSERT on event, head_log and source_binding; the reader
needs SELECT only. Test both with dedicated non-owner roles, including denied
UPDATE/DELETE/TRUNCATE/DDL and ledger access. Do not invent deployment credentials
or silently change the existing default data source. Audit CDI and dedicated
production data-source adoption remain a separate Claude-owned consumer/WP-304 gate.

## Evidence and limits

Current main includes reviewed central append-once preparation and V73/V74. PR63
merged as 4f3032a after exact Claude PASS at1396bd4 and full40236424166 SUCCESS.
The pinned proposal's LF checkout bytes now match its unchanged expected SHA-256.
No work-package or acceptance status is promoted by fixture or schema preparation.
