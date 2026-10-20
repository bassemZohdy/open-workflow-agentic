# WP-107 pinned listen groups: current review handoff

Current scope is the GPT gpt/WP-107-listen-groups branch after its normal merge
of reviewed main57c3cc492b0a3e838879c05f5088a5327d5ccba1. GitHub review request
pins the final head; earlier component review/CI is not transferred to this head.
This is the next sole GPT implementation PR after #54 merged. No package or
acceptance status is promoted.

Delivered increment:

- Framework-free InboxListenGroup identities and finite ONE/ANY/ALL/stopping
  shapes, ordered logical filter ordinals (duplicates retained), wildcard ANY
  and stopping-event precedence. classify validates ONE receipt's complete
  trusted match set; it grants no callback, consumption proof or ACK.
- Additive centrally sequenced V71/V72 retain pinned configuration/deadline,
  full task identity, receipt/match membership, arrival-selection sequence and
  selecting owner/epoch. Leases/current registry fence writes; replayed reads
  retain historical attribution without authorizing a new owner. V72 prevents
  membership additions after an item is finalized, including direct SQL.
- JdbcListenGroups registers/retains configuration, offers complete trusted
  matches and reads bounded ordered pages. Mandatory audit is outside owned
  SQL transactions; commit/audit uncertainty requires same-identity readback.
  There is no predicate, transport callback, dispatch attempt or checkpoint ACK
  implementation in this store.
- ListenGroupSnapshots reads the actual SDK listen task and trusted registry
  identity, serializes its complete read/foreach/until/timeout shape canonically,
  includes only relevant scoped approved producer configuration and explicit
  approved correlation values, and preserves duplicate/empty arrays. A restore
  loads retained configuration rather than deployment defaults. Supplied hashes
  and correlation strings are data, never authorization.
- Four schema-valid complete SDK fixtures preserve observations/negative
  controls: ALL arrival order/overlap, unsafe ANY loser mutation and unsafe
  foreach/until child overlap/completion. These are NOT production FIFO or
  restoration/conformance proofs.

Local verification of the refreshed product:

- mvn -B -pl modules/oa-contracts test:192 passes including group9, zero failures/
  errors/skips; purity/schema/existing audit/resource-scope cases pass.
- mvn -B -pl apps/app-runtime -am test
  -Dtest=InboxListenGroupTest,ListenGroupSnapshotsTest,ListenGroupValidationTest,SdkListenGroupBehaviorTest,EventIngressTest
  -Dsurefire.failIfNoSpecifiedTests=false:25 passes (group9, snapshots6,
  validation2, actual SDK4, ingress4), zero failures/errors/skips, application
  test sources compile. This does not execute local PostgreSQL/native services.
- tools76, generated-source and diff checks pass. All four workflow YAML files
  validate against bundled OWS1.0.3. Linux actual store11/full/strict CI and
  Claude's exact-head review are required on the final request SHA.

Previous SQL/producer success is retained as historical evidence in the older
handoffs. Current #54 main postmerge full39398700002/BOTH39398724222 SUCCESS;
GPT inspected all three finished logs: audit projection9/core process1 per
provider/standard3/native8/strict checks pass, zero failures/errors/skips. Full
build's optional-provider discovery0 cases are not provider evidence; the two
strict real-provider lanes provide that evidence separately.

Explicit remaining gates: complete trusted selector/predicate/correlation and
receipt ordering at ingress; bounded serialized coordinator/renewal; actual
callback attempt/winner and processed-item/parent proofs; genuine FIFO foreach/
until/loops/parallel ownership; whole-group process/partition/old-owner/cancel/
deadline restore; event starts and full T13 including failed compensation;
retention/quotas/tombstones/archival; manual OPERATION; central audit delivery and
commit uncertainty; terminal telemetry. No production consumer/router or
ServiceLoader is activated. Existing ws1/single-wait/receipt proof keys and
migrations are unchanged. V73/V74 binding/occurrence and V75 proposed source
schema remain separate GPT preparation, not this PR's code.
