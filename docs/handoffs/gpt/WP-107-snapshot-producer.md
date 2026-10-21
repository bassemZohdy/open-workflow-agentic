# WP-107: pinned listen snapshot producer (GPT preparation)

Status: partial preparation on gpt/WP-107-listen-groups. No additional PR or
activated SDK consumer/selector/executor. #48 remains frozen pending peer review.

GPT-owned ListenGroupSnapshots prepares InboxListenGroup plus the exact JSON
for JdbcListenGroups.register. Caller supplies an authoritative registry row,
actual SDK workflow/task contexts, explicitly approved/resolved correlation
parameters. It checks engine id and pinned namespace/name/version, uses SDK
position verbatim and iteration, and retains tenant/registry ULID. SDK positions
are not ordinary JSON AST pointers. Caller still owns registry/full-owner/
claim/policy/audit/publication pinning; this value is data, never authority.

Snapshot includes the complete actual listen Task AST (read/foreach/input/
output/export/timeout), scoped producer routes and explicit correlation map.
It copies no workflow/registry input, context variables, authorization descriptor
or bearer. Inline literals remain part of the approved definition; the factory
does not scrub or approve them. No snapshot is logged or added to telemetry.

EventIngress's existing validator validates all settings without policy
initialization. Its internal projection includes ONLY this tenant/definition's
routes sorted by projected identity. Foreign routes and changes to unrelated
definition allowlists do not disclose or perturb this scope. Producer kind/id,
source/types/correlation allowlists and retention stay pinned. Ingress receipt
authorization and its global audit binding hash are unchanged.

Object keys sort recursively; arrays preserve declaration order/duplicates.
SDK union mixins stay. The SDK mapper disables WRITE_EMPTY_JSON_ARRAYS by
default; a private copy enables it to preserve ANY [] and empty data arrays.
Shared SDK configuration and dependencies are unchanged. Strict UTF-8 snapshot
identity uses the existing 256 KiB bound. This is a versioned SDK-model snapshot,
not RFC8785 or numeric precision beyond the actual parsed SDK definition.

ONE/ANY/ALL full filters get domain-separated ordered hashes; event until keeps
its own roles and an expression its own hash. OWS1.0.3 explicitly forbids until
inside an event-based stopping strategy (`until: false` in AnyEventUntilConsumed):
https://raw.githubusercontent.com/serverlessworkflow/specification/v1.0.3/schema/workflow.yaml.
Reader and mapper reject it, including deliberate reader-validation bypass.
Earlier references to nested strategies mean permitted ONE/ANY/ALL within
until; recursively nested until is not a conformance gate.

Restore MUST load retained group/snapshot/deadline, never rebuild with new
deployment defaults. No JDBC, grant, policy, audit, callback, reservation,
checkpoint proof or ACK occurs here. Future caller owns approved correlation
resolution, predicate truth and current dispatch authorization.

Evidence: `mvn -B -pl modules/oa-runtime -am test
-Dtest=ListenGroupSnapshotsTest,ListenGroupValidationTest,InboxListenGroupTest,InboxTaskTest,SdkListenGroupBehaviorTest,EventIngressTest,StoredCloudEventDecoderTest,SdkEventRegistrationSemanticsTest,EffectTelemetryTest
-Dsurefire.failIfNoSpecifiedTests=false` BUILD SUCCESS,40 selected plain cases
(contracts11/runtime29), zero failures/errors/skips. Six new cases run actual
SDK reader/application/listen registration contexts with explicit registry/
deployment fixtures: duplicate ordinals/full task/no input leak; wildcard/event/
expression roles; canonical key/array order; relevant producer stability/config
changes; read/foreach/timeout/correlation pinning; foreign registry/invalid
configuration/schema-forbidden recursion refusal. Initial ordinary-Jackson
union and SDK empty-array omissions failed these tests and were corrected.

No local PostgreSQL/native service: these are not SQL/admission/recovery/full
conformance evidence. Separate exact V72 product4d751617720e17a78fdad2ba3c50a393803706bc
passed BOTH39379015967 lanes: SQL11, metadata2, contracts9, actual SDK observations4,
standard3/native8, zero skips, strict checks and full logs inspected. That SQL
evidence does not transfer automatically to a later producer head; final-head
CI and independent peer review remain required.

Final local application reactor test-compile also BUILD SUCCESS; tooling68,
generated tracking check and diff check pass. This compile is not a boot/SQL
test. The new producer head still requires fresh Linux CI.

Executed follow-up: exact productf1842c0afe38fdd8ea8cfb4249a390fc48593491
run39380401962 SUCCESS BOTH lanes. Both complete logs and strict evidence checks
inspected: producer6, SQL11, metadata2, group contracts9, actual SDK observations4,
standard3/native8; zero failures/errors/skips. These component cases execute on
the producer head, not just the earlier V72 baseline. #48 is subsequently merged
as maindbec8e0 after exact peer/CI; this preparation must incorporate reviewed
main before a final PR. #49 shared resource-call contract411dc36 is now the sole
GPT implementation PR. Independent group review/full integration remain open.

Complete selector/ingress FIFO, callback attempts/winners/per-item/parent proof,
bounded serial coordinator/renewals, actual SDK Flow/loop/parallel integration,
whole-group process/partition/old-owner/cancellation restoration remain open.
WP-107-groups.md and WP-107-group-storage.md retain event starts, retention/
archival/quotas, manual OPERATION, central audit, tracing and full T13 including
compensation. No package completion or acceptance marker is promoted.
