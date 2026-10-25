# Integration queue: next actions (2026-10-26)

Current implementation baseline: main `63291fa432b251d8b2533b6c1488a019fa8cecef`.
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
  migration and private review databases were removed. Product V75 is not applied.

## Next sole GPT implementation increment: V75 and permissions

Follow [the concrete activation handoff](WP-108-v75-activation.md). The Claude-owned
fixture prerequisite is now merged: private proposal databases stop at target74,
and shared parent TRUNCATE probes include the retained child, verify the intended
parent's 42501/append-only error and always roll back.

1. Branch from current main. Activate the agreed V75 schema after V73/V74; preserve
   its source-key calculation, retained event/head references and immutable guards.
   Update only the applied copy's obsolete proposal comments. Never rewrite an
   earlier migration or change the pinned proposal fixture/digest.
2. Add GPT-owned migration-first tests for fresh migration, validation and upgrade
   from a nonempty V74 audit foundation without changing prior events or inventing
   source backfill. Exercise the concrete append-once API against actual V75.
3. Provide and verify dedicated writer/read grants: schema USAGE plus SELECT/INSERT
   on event/head_log/source_binding for the writer, SELECT only for the reader.
   Prove denial of protected-table mutation, protected-schema DDL and ledger access
   with non-owner roles. Do not invent deployed credentials or silently switch the
   default application data source.
4. Require exact Claude peer review, current required CI and serialized tracking
   before merge. Inspect the postmerge check without relabeling earlier evidence.

CDI/production audit data-source adoption belongs to Claude's audit consumer and
WP-304 boundary. Current caller/PDP/execution/lease checks, actual Flow output,
producer delivery/acknowledgement and recovery remain separate implementation
steps. A stored receipt or a new schema is never dispatch permission.

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
