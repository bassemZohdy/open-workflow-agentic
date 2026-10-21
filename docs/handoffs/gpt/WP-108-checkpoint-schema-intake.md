# WP-108 checkpoint table ownership: pinned-profile intake

Reviewer=GPT; verdict READ_ONLY_PROFILE_INTAKE_COMPLETE, not schema adoption.
Request: Claude #53 a71fb1f / comment6304715187 and
docs/handoffs/claude/WP-103-research-run-inspector.md on merged main2f10662.
No migration, role, version allocation, schema mutation or runtime change here.
Shared main tracking and membership candidatec9ce389 stay frozen for peer review.

## Exact reviewed inputs

Locally inspect the pinned org.bsc.langgraph4j:langgraph4j-postgres-saver:1.9.5
source JAR using Python zipfile/hashlib. Its SHA256 is
0d4d933054c366c003520c258be64def705f2dfb46a2f2b49ef585856e730333.

| Resource | Bytes | SHA256 |
| --- | --- | --- |
| db/migration/v1.1__init.sql |1024|f25ce808c4bf2dcca9bd23be68fcdbc374068721a0901477c886ba85e116552d|
| db/v1.1__commands.sql |1510|3bf39dd8fc189531024ac9881db3c5bd90eedbb326732424ae0706669fe0ee78|
| org/bsc/langgraph4j/checkpoint/PostgresSaver.java |6132|630b39bf9c7823c578897ceb54dc15b0dc3e55439900445919f3a1f353159c71|
| org/bsc/langgraph4j/checkpoint/AbstractPostgresSaver.java |13049|30037229c4a6627f19ea5f3193d9101170f8f0a6d3e8e505765c4c8d09fdb783|

PostgresSaver selects the v1.1 resources explicitly. V2 resources also in the
same JAR are a different schema and are not this application's profile.

## Actual v1.1 shape and behavior

Unquoted LG4JThread/LG4JCheckpoint names fold to lg4jthread/lg4jcheckpoint.

- Thread: thread_id UUID primary key, thread_name varchar255 nullable,
  is_released boolean NOT NULL defaultfalse.
- Checkpoint: checkpoint_id UUID primary key, parent_checkpoint_id UUID nullable
  without a causal FK, thread_id UUID NOT NULL FK to thread ON DELETE CASCADE,
  node_id/next_node_id varchar255 nullable, state_data TEXT NOT NULL,
  state_content_type varchar100 NOT NULL, saved_at timestamptz defaultCURRENT_TIMESTAMP
  nullable.
- Indexes: checkpoint(thread_id), checkpoint(thread_id,saved_at DESC), partial
  UNIQUE thread(thread_name) WHERE is_released=false. Released historical names
  are intentionally not globally unique.

The builder's createTables default is false; ResearchAgent currently enables it.
The saver initializes tables in its own transaction when enabled. Its command
profile upserts one unreleased thread, reads checkpoints by saved_at DESC,
UPDATEs checkpoint_id/node/next/state_data and clears parent_checkpoint_id, and
releases by UPDATEing is_released. The checkpoint update does not rewrite media.
Migration ownership must support those real operations rather than inventing
append-only checkpoints or a populated causal-parent chain. saved_at/checkpoint
UUID sorting does not prove causality; the inspector's pinned linear-position
analysis remains a scoped observation, not general graph completion authority.

## Future ordered adoption and owned acceptance

GPT owns a future additive migration after auditing the existing reserved range;
NO version is assigned here. Do not renumber V71..V80/72.1, rewrite applied files
or allocate a Claude migration. Existing metadata/private state must survive
compatible adoption exactly. CREATE IF NOT EXISTS alone would silently hide
incompatible/partial tables and is insufficient validation.

Required later tests:

- Fresh migration before any graph creates tables; all test-class orders and app
  startup preserve the migration-first rule. Failed migration refuses graph work.
- Nonempty compatible profile retains physical ids, exact stored private text,
  release flags and existing rows without backfill/reset/baseline.
- Missing/partial/incompatible types, primary/foreign keys/indexes and conflicting
  unreleased history fail safely, leaving original data intact.
- Actual pinned saver insert/read/update/release and existing inspector24 snapshot/
  structure/encoding cases continue to work, with no fabricated causal fields.
- Role/configuration rollout preserves legitimate required updates and treats
  cleanup/retention/restore as separate reviewed operations; no deployed least-
  privilege or immutable-history guarantee is claimed from the current POC.

Only after reviewed migration activation/main integration should Claude change
the owned ResearchAgent createTables(true) call and verify real startup/failure
behavior. Keep the current Flyway-first test helper; no global
baseline-on-migrate setting change is implied. Use owned UUID fixtures and
rollback their DDL/DML; never reset another session's schema/data.

The shared output fingerprint is a separate queued representation contract. It
does not close table ownership, real entry/current caller/PDP/execution gates,
private output, central audit, restore, retention or full WP108/acceptance.
