# Checkpoint profile activation (2026-10-26)

Baseline main949d055 includes the reviewed entry/outcome contract and reserves
V75_1 after V75, before the unchanged V76-V80 preparations. GPT owns this
migration, application lifecycle tests, deployment grants and build evidence.
Claude owns the later ResearchAgent runtime change. No ownership is transferred.

## Applied migration candidate

`V75_1__research_checkpoint_profile.sql` reconciles the executable proposal from
`gpt/WP-108-checkpoint-schema` with current main. Both absent public tables may
create the pinned PostgresSaver1.9.5/v1.1 layout. Compatible nonempty tables keep
physical ids, private text, release flags and constraint identities. Partial or
unsupported relation/column/default/key/index/namespace/RLS/inheritance/trigger
layouts refuse; existing tables require ACCESS EXCLUSIVE NOWAIT. There is no
repair, reset, baseline, backfill, session termination or private-state decoding.

One refinement follows a real PostgreSQL16 probe: the old proposal accepted a
nondefault column collation when its index collation followed the column. The
candidate requires each column's default type collation, matching the pinned DDL,
and adds the `column-collation` regression. No historical approval is transferred
to that change or to the new applied migration.

Checkpoint updates/releases remain legitimate. The permission procedure at
`deploy/research-checkpoint-roles.md` and its exact SQL template grant a fresh
writer SELECT/INSERT/UPDATE on only the two checkpoint tables, and a reader
SELECT only. They provide no DELETE/TRUNCATE/protected-schema DDL/audit/ledger
access. The additive template does not revoke PUBLIC or earlier/inherited powers;
identities must be distinct, fresh and non-owner. No production credentials or
default data-source switch are supplied. The supported target is explicitly public.

## Verification and retained evidence

Local PostgreSQL16/Java21 execution passes14 focused methods, zero skips/errors:

- ResearchCheckpointSchemaTest10 executes the actual migration SQL in an isolated
  database and owned UUID namespaces. Its report requires all24 unique observations,
  including the new collation refusal, complete malformed-profile boundaries,
  unchanged history/constraint ids, actual saver update/release and immediate
  live-writer refusal. The pinned vendor DDL hash remains
  f25ce808c4bf2dcca9bd23be68fcdbc374068721a0901477c886ba85e116552d.
- ResearchCheckpointMigrationTest4 uses the actual ordered Flyway path in separate
  private databases: fresh migration before saver writes, nonempty vendor-profile
  preservation, partial-store migration rollback and the exact grant template
  with the real saver createTables(false), bounded read and privilege denial.

The schema cases deserialize only their own synthetic state through the real saver;
this grants no permission to deserialize unknown private history. Snapshot hashes
are test diagnostics, not a second product output fingerprint. All owned namespaces,
databases and roles are cleaned up; no shared store is reset or killed.

The applied SQL resource preserves LF bytes for consistent evidence hashing. Build
CI retains actual Surefire/Failsafe XML and the24-case JSON report, including on
failure, for7 days. The report labels local execution as local; CI records its
actual checkout GITHUB_SHA. No later head is relabeled as tested.

## Claude-owned consumer handoff after merge

After this migration has exact peer review, current CI and merged evidence:

1. Update `modules/oa-runtime/src/main/java/io/github/bassemzohdy/openworkflow/agentic/runtime/agent/ResearchAgent.java`
   to stop creating tables at runtime. GPT does not edit that owned source.
2. Verify the actual configured connection/profile read-only before graph/provider
   work, including when migration history already exists. Missing/incompatible or
   unsupported/shadowed namespace configuration must refuse; do not create/repair
   or silently fall back to another store. Coordinate any shared configuration or
   lifecycle interface needed through GPT. Do not change global baseline-on-migrate.
3. Prove fresh startup/class-order correctness and incompatible-store refusal before
   graph/model effects. Preserve the default unsatisfied legacy admission port.
4. Dedicated checkpoint data-source credentials/role adoption remain the owned
   consumer/WP-304 step; do not replace the workflow data source with a checkpoint-only
   identity. A schema match is not current authority, complete history or retry permission.

V76 entry/backend/current composite fence and later producer delivery are not
activated here. Exact Claude review, current CI and integration are pending.
WP-108 and acceptance remain partial.
