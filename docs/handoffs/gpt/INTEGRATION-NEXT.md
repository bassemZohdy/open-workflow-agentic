# Integration queue: next actions (2026-10-26)

Current implementation baseline: main `1773a00c219024d566453cc3918f24c6ec00aeb3`.
No open pull request remains. This queue supersedes the former PR55/57/58 pending
snapshots; their exact evidence remains in the linked historical handoffs and PRs.
Counts remain 11 completed / 12 partial / 30 not started; acceptance remains 3/39.

## Integrated prerequisites

- Membership PR55, shared fingerprint PR57, central append-once preparation PR58,
  V73/V74 attribution PR60 and node-interceptor PR61 are merged.
- CI proposal PR62 is integrated through reviewed PR63 as main `4f3032a`.
  Exact Claude PASS at `1396bd4` is recorded in comment 6403683125; full build
  40236424166 and main/tracking builds 40237056066/40237188334 passed. The actual
  Linux scoped reactor command passed too (comment 6403972943). Only Markdown
  prose skips Maven; test sources, oa-testkit, unknown paths and tracking sources
  force full checks. Both sides of renames and missing scope output are safe.
- Claude fixture PR64 merged as `63291fa`, exact implementation `6610bf1`.
  GPT PASS 6403925367 and full build 40237862668 cover that exact head. The earlier
  child-first version was rejected because its child guard masked parent-guard
  removal. Independent PostgreSQL16 proof: 31 tests pass with the V74 foundation
  and with the V75 proposal discoverable/applied in a private review database;
  disabling the parent TRUNCATE guards gives the two intended assertion failures;
  restoring them returns all5 append-only tests to PASS. Temporary generated
  migration and private review databases were removed. Production migration activation followed in PR65 below.

- V75 PR65 merged as `2beaeb4`, exact implementation `cd15aca`. Claude PASS
  6404200386 independently reran all6 PostgreSQL16 migration/permission cases.
  Full40239627410 passed; actual logs confirm migration6, appendOnce22, acceptance
  consistency, dependency policy and Python88. The real migration preserves the
  agreed DDL body and prior events without backfill. The additive grant template
  is tested with fresh non-owner writer/read identities; it revokes no earlier or
  inherited powers and supplies no credentials or data-source/CDI adoption.
  Postmerge verification is pending.

- Execution contract PR66 merged as `1773a00`, exact `de853bb`. Claude PASS
  6404473197 independently reran contracts200, actual serializer1, Python88 and
  generated checks. Full40241496066 passed; actual logs confirm contract4,
  serializer1, negative default admission wiring1 and acceptance/dependency checks.
  The three production types retain prepared bytes; the merged fingerprint and
  existing helper tests are reused. No backend, schema, callback or authority is
  activated. Postmerge verification is pending.

## Next sole GPT increment: checkpoint profile migration

Use the existing executable checkpoint-profile preparation on
`gpt/WP-108-checkpoint-schema`, after reconciling current main. The observed pinned
profile and its SQL proposal/real-saver cases are already documented there. Do not
merge unrelated old branch copies or transfer historical review/CI to a new head.

1. Activate the reviewed profile as additive **V75_1**, after V75 and before the
   reserved V76-V80 proposals. This allocation preserves all applied migrations
   and existing proposal numbers. Own the explicit public checkpoint profile;
   preserve original physical ids/private text/release flags in compatible stores.
2. Both tables absent may create the pinned profile. Partial/incompatible tables,
   columns/keys/indexes/namespace/RLS/inheritance/triggers must refuse without repair,
   reset, baseline, guessed backfill or private-state deserialization. A live table
   writer must refuse with NOWAIT rather than being killed or waited on indefinitely.
3. Prove the actual migration-first path, compatible nonempty adoption, rollback on
   failure and real pinned saver insert/read/update/release with createTables(false).
   Provide the owned role/data-source/configuration procedure and least-privilege
   proof, distinguishing mutable checkpoints from immutable audit receipts.
4. Require exact Claude review, current CI and serialized integration. Claude owns
   the later ResearchAgent createTables change, after the reviewed schema prerequisite,
   and must prove startup/failure ordering before graph/provider work. Do not change
   its source from the GPT branch or silently alter global baseline-on-migrate.

The actual execution backend/current composite fence, reserved V76 facts and V77
producer/delivery remain later steps. A schema, constructed entry or stored outcome
cannot authorize a graph retry or release private output. Keep the legacy production
admission port unsatisfied until the complete reviewed consumer/authority gate exists.
No package or acceptance promotion follows from a profile migration alone.

## Retained later preparation

- [Checkpoint schema intake](WP-108-checkpoint-schema-intake.md) records the actual
  saver profile and adoption tests. No later migration number is allocated here.
- [Attribution integration](WP-108-integration-bindings.md) records V73/V74 storage
  and the deliberately unsatisfied legacy admission port.
- [Central audit contract](WP-108-central-audit-schema-handoff.md) and
  [consumer review](WP-108-audit-append-once-review.md) retain the append-once and
  unknown-commit requirements.
- Existing WP112/native-preview and WP605 preparation stays parked behind this
  integration queue. No package is promoted merely because its preparation exists.
