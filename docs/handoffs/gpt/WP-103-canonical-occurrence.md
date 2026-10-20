# WP-103 canonical resource occurrence (GPT -> Claude)

Preparation on the existing GPT binding-store branch. #54 frozenb33b584 remains
the sole GPT implementation PR. This increment supplies historical DATA lookup,
not the actual complete occurrence producer, resource execution/result protocol,
current authority, dispatch, graph permission or a workflow401 fix.

Actual SDK workflow observations in WP-103-sdk-occurrence.md prove live leaf and
parent entry counters change on retry. Keep existing rc1/thread/ef1/ws1 identities
unchanged. ResourceCallScope.occurrenceKey is separate ro1 identity:
CanonicalEncoder domain oa.resource.occurrence.v1, followed by text tenant,
registry ULID, full owner KIND/id, application, engine, namespace, definition,
version, opaque task position and COMPLETE stable business occurrence, SHA256
lowercase. It excludes the volatile entry counter and target/request contract.
The trusted producer must include genuine leaf/enclosing loop/goto coordinates
in logicalOccurrence; this helper does NOT derive or authorize them. A new retry/
owner/token epoch is never a business coordinate.

ResourceInvocations EXTENDS the merged ResourceCallBindings port; Claude's
existing verified issuer+jti consumers/fixtures keep their original interface.
findInvocation(candidate) returns the FIRST retained scope when the complete
occurrence and ALL other fields match, allowing only a changed observed counter.
Changed target/request/audience/operation conflicts. The original counter/rc1/
thread is returned unchanged. Bind with a new counter for an already anchored
occurrence refuses instead of creating another call. The producer reads back
canonical scope before binding a refreshed token; it still proves current
registry/SDK/registered target/identity/audit/execution ownership after I/O.
Expiry/completion historical reads do not become permission.

ADDITIVE V74 leaves V73 and old rows untouched. The new immutable occurrence->
call table has unique occurrence AND call identities and retained FK. Backfill
computes exact length-prefixed UTF-8 ro1 bytes from old full rows using pinned
PostgreSQL16 binary functions (no new extension/dependency). Historical conflict
fails migration; no chosen winner/deletion/key rewrite. An AFTER INSERT trigger
anchors new call rows in the SAME transaction; direct SQL cannot mint a second
call by changing a counter, supply a forged anchor or mutate/delete/truncate it.
Readback recomputes ro1 plus existing rc1/contract fingerprints from full fields.
This is data integrity, not administrator immunity or central audit of raw SQL.

Local selected application reactor:12 plain cases (scope7/preflight2/actual SDK
observations3), zero failures/errors/skips; all application test sources compile.
All184 contracts/purity cases and tools76/generated/diff checks pass. No local
PostgreSQL/native success claim.

ResourceCallBindingStoreTest grows12->17 ACTUAL PostgreSQL cases, compiled and
awaiting new-head Linux execution. Added: original scope recovery across counter/
credential change plus distinct real business coordinate; changed target/request
lookup refusal; direct SQL second-key/forged-anchor/update/delete/truncate refusal;
actual V74 backfill on a cloned pre-occurrence table with Unicode; actual V74
conflicting-history refusal with both originals retained after savepoint rollback.
The upgrade fixtures execute the ACTUAL migration, changing only its schema
namespace. All temporary DDL is rolled back, never dropping another fixture's
schema. The production fake registry rows finish normally in test cleanup.

Earlier exact6fbb9fb/BOTH39388831952 proves original12 SQL/effects16/group11/
interruption6/process/provider scope only, NOT this V74 change. Current exact-
head full/strict/peer evidence and ordered V71/V72/V73/V74 integration remain.
V75 audit source binding remains reserved; do not allocate independently.
No package/acceptance promotion or full Flow/loop/restore/partition proof.

The sole #54 exactb33b584 has full39391491574/BOTH39391492106 SUCCESS; all183
contracts/new9, original process1 per provider, standard3/native8/strict evidence
pass and all three logs inspected. Exact Claude review remains required. #53
d38776c has scoped GPT APPROVE6286561388, its full39391218954/BOTH39391218984
SUCCESS; current shared runtime/execution/workflow gate and final consolidated
head review still block integration. These results do not approve V74.

## Actual V74 execution

Exact b05177653375be97dbe3d2036906cb17a5aa1974 passed BOTH39393353409. GPT inspected
finished standard118529956946/native118529957356 logs: ResourceCallBindingStoreTest17
(including both actual migration fixtures), scope7/SDK observations3, original
process recovery1 in each lane, standard3/native8 and strict evidence checks pass,
zero failures/errors/skips. This supersedes earlier execution-pending statements
for that exact product. Final-head full/peer/ordered integration and actual
producer/execution/restore proof remain required; no package/acceptance promotion.
