# Integration queue: next actions (2026-10-26)

Current implementation baseline: main `2beaeb4a62087da1f02728b7328d815651a9a897`.
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

## Next sole GPT increment: execution contract handoff

Integrate the existing framework-free preparation on `gpt/WP-103-execution-contract`
against current main before activating an execution backend. Extract the intended
entry/outcome/fence types and tests; do not restore stale helper/schema/runtime
copies from that older branch. The shared ResearchOutputFingerprint is already
merged and remains the single retained-output representation.

1. Reconcile the full contract with the current ResourceCallScope/canonical call
   identity. Keep entry epochs and outcome ids/fingerprints as DATA, never minted
   authority. A new credential, epoch or empty checkpoint is not permission to
   enter or retry a graph.
2. Publish the explicit transient composite-fence handoff. Actual caller/PDP,
   registry/full owner/task/target/execution/lease checks must remain bound to the
   exact entry and refreshed after blocking I/O, before entry, on governed nodes
   and before private output release. No ambient or serialized permission handle.
3. Run contracts and actual serializer compatibility checks, exact Claude review,
   required current CI and serialized tracking. Leave the legacy admission port
   unsatisfied and introduce no default allowance, graph callback or model call.
4. Then sequence actual checkpoint ownership/adoption and the reserved V76
   execution-fact backend against the measured saver profile. Preserve nonempty
   data and fail closed on incompatible stores. No later number is allocated by
   this queue. V77 producer/delivery and V78-V80 remain ordered later gates.

The merged V75 schema/low-level receipt is not dispatch permission. Audit
CDI/production data-source adoption belongs to the Claude consumer/WP-304 boundary;
producer acknowledgement, mandatory receipt-before-dispatch, current authority,
actual Flow/output and recovery still require their own complete integration.

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
