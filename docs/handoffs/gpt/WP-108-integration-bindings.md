# WP-108 prerequisite increment: resource attribution and canonical occurrence

Reviewer=GPT (author verification, not peer approval).
Product SHA: pending preparation commit.
Verdict: nine focused plain controls/application compilation and augmentation
PASS; all193 contract tests also PASS. Fresh Linux SQL/provider execution pending.
This is a queued candidate, not a second implementation PR or package completion.

## Small integration boundary

Base5522bce is the membership correction awaiting Claude review6304631674; #55
remains at its earlier reviewed ea56f33. This candidate isolates existing
preparation694a238 without merging that branch's later execution/audit proposals.
The nine selected source/test files are byte-identical to that preparation.
No V71/V72/72.1 applied file is edited; V73/V74 retain their existing prepared
bytes. V75..V80 are absent. After #55 integrates, normally merge current main
and rerun affected checks before requesting a new implementation PR review.

Producer/consumer: GPT owns ResourceCallScope.occurrenceKey, ResourceInvocations,
JdbcResourceCallBindings and database/CI/tracking. Claude's existing verified
issuer+jti consumer retains ResourceCallBindings. CDI exposes the attribution
store, but this increment adds no HTTP dispatcher, graph resource, bearer release,
manual endpoint, model call, callback or execution permission. Historical reads
and exact repeats after expiry/completion remain data. New writes require current
registry full owner/definition/engine/status and exact V65 issuing metadata.

V73 commits the full immutable call contract and issuer/jti binding atomically in
short owned READ_COMMITTED transactions, suspending ambient JTA. SQL/commit/JTA
failure refuses and requires same-identity readback, never permission to create
a replacement call. No raw credential/request is retained. Lossy timestamp,
invalid registry definition and malformed verified identity refuse before I/O.

V74's separate ro1 key uses the complete retained business occurrence, excluding
the volatile SDK entry counter and target/request contract. The original rc1,
counter, contract and thread remain unchanged. Lookup returns the first scope
only if every other field matches; changed target/request/audience/operation
conflicts. A new credential/counter cannot create a second call for that same
business occurrence. The trusted producer must still derive actual enclosing
loop/goto coordinates; this data helper does not derive or authorize them.
Existing conflicting historical calls fail migration without a chosen winner,
deletion or key rewrite. SQL anchors subsequent calls in the same transaction
and retains immutable occurrence/call/token references.

## Tests, CI evidence and ownership

Selected source paths from694a238:

- contracts identity/ResourceCallScope.java, port/ResourceInvocations.java and
  identity/ResourceCallScopeTest.java.
- runtime lifecycle/JdbcResourceCallBindings.java, lifecycle preflight test and
  db/migration/V73__resource_call_bindings.sql plus V74__canonical_resource_occurrence.sql.
- app lifecycle/ResourceCallBindingStoreTest.java and IssuedTokenBindingMigrationTest.java.

Seven scope/two preflight methods pass locally with zero failures/errors/skips.
Application tests compile and Quarkus JVM augmentation passes with the new CDI
store. This is not local PostgreSQL/native execution. The retained17 real SQL
cases cover full roundtrip/repeat/issuer separation/concurrent binding, changed
contracts, complete registry/issuing fields, actual lost commit reply/readback,
ambient rollback, direct SQL/retention, original-counter reuse, changed payload
and canonical-anchor integrity. The existing migration TRUNCATE negative test
includes new retained children in an always-rollback transaction so the strong
42501 trigger assertion remains; no FK/guard is dropped or fixture cleared.

The Keycloak workflow now archives app/module Surefire reports as well as its
existing Failsafe/process reports. This closes the parent workflow's SQL method
evidence-retention gap without changing test selection or accepting skipped
tests. Fresh exact candidate execution is required; old preparation results do
not establish this isolated integration candidate's current commit.

Local commands/results: mvn -B -pl modules/oa-contracts test ->193 PASS;
application reactor package with selected ResourceCallScopeTest and
ResourceCallBindingPreflightTest ->nine PASS plus app compilation/augmentation.
Separate mvn -B verify -DskipTests ->all eight modules compile/package and emits
dependency lists, not a full local test run. The actual dependency-policy check
then passes; tools unittest discover76/generated/diff and both strict provider/
effect preflights pass. No dependency or version changes are introduced.

## Next ordered handoffs and acceptance gates

After exact peer review/required CI/main checks for the membership correction,
this resource-attribution increment can be reviewed and integrated separately.
Then review the already prepared V75 audit.source_binding activation with
Claude's owned append-only/role test updates. Claude must implement the actual
atomic AuditTrail.appendOnce consumer and prove duplicate/concurrent one-head,
changed-source/draft conflict, real commit-loss/readback/reopen/corruption/outage
and retained references. The concrete proposal remains in existing GPT/Claude
handoffs; this increment does not bypass its approval or test prerequisites.

Full WP108 still needs actual source producer, signed current caller/PDP and
registered target/execution/lease fences after all blocking work, mandatory
verified central receipt, actual Flow checkpoint/output and unknown/manual
recovery. Full WP103 needs governed producer/consumer/workflow401 and model
boundaries. #53/#56 remain unchanged with standing correction/consolidation
findings. No whole-WP102 completion dependency is introduced. No status or
acceptance promotion, new implementation PR, merge or deployment is claimed.
