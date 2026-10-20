# WP-103 resource-call attribution storage (GPT -> Claude)

Preparation on gpt/WP-103-resource-binding-store, normally combining reviewed
main49/30e81c3 and groupaa94bd9. #51 remains the sole GPT implementation PR.
Integrate reviewed V70 then V71/V72 before V73; never backdate or edit applied
migrations. No PR, bearer release, HTTP adapter, manual endpoint or graph execution
is activated by this preparation. The real workflow401 gate remains open.

## Producer and consumer

JdbcResourceCallBindings implements the MERGED #49 ResourceCallBindings port in
GPT-owned runtime/lifecycle, not Claude identity code. CDI supplies the data source
and TransactionManager; plain construction is available for standalone tools.
All reads/writes suspend ambient JTA and own short READ_COMMITTED JDBC transactions.
Both invocation contract and issuer/jti mapping commit together before a bind can
return. SQL, commit or JTA suspend/resume failure throws StoreUnavailableException;
never read that as absent or permission to mint a new call/token. Reconcile the
SAME issuer/jti/call identity after an uncertain actual commit reply.

New mappings lock the registry before call/binding writes, checking tenant, full
owner KIND+id, pinned definition/version and engine id, and RUNNING/WAITING status.
V73 triggers independently enforce registry attribution plus exact V65 issuer/jti,
grant/tenant/signed expiry and the grant's full user subject. Foreign keys retain
all referenced metadata; update/delete/truncate are refused. No central audit of
arbitrary SQL or administrator immunity is claimed. Current grant status is NOT
read as authority by this data store: the trusted producer/consumer must recheck
the complete current chain after blocking work.

An exact historical repeat/read returns retained data even after expiry or
registry completion. A competing same issuer/jti with changed fields conflicts;
the same stable invocation key with a changed target/request/audience/operation
also conflicts, including a freshly exchanged jti. Multiple credentials may name
one unchanged call; they are NOT multiple-execution permits. Readback reconstructs
every scope field and recomputes rc1/contract/rb1 fingerprints, refusing inconsistent
rows. It contains no raw request, bearer, refresh/user/actor token or graph result.

PostgreSQL stores microseconds. Input timestamps with finer precision or outside
UTC years0001..9999 are rejected before I/O, never silently rounded. Current signed
JWT producers use whole seconds. The existing platform DefinitionRef constraints
apply to this registry backend; the broader framework-free type is unchanged.

The dispatcher still must derive the complete stable enclosing occurrence from
actual durable SDK execution, validate approved canonical request and registered
target configuration/audience/operation, compare the exact admitted signed claims,
complete mandatory audit, then recheck current authority and execution ownership
AFTER storage and immediately before transient bearer release. No caller-selected
header/body metadata, token jti, retry epoch or random UUID substitutes for the
occurrence. The admission consumer uses only verified issuer+jti and compares
ALL retained fields. Missing/unavailable/conflicting bindings refuse graph entry.
Resource replay and durable graph/effect/result/unknown-outcome protocols remain
separate, including prevention of duplicate execution under refreshed credentials.

## Verification and limits

Local final application-reactor selected build:23 plain cases, zero failures,
errors or skips (ResourceCallScope6, InboxListenGroup9, ListenGroupSnapshots6,
ResourceCallBindingPreflight2). All application tests compile. Preflight tests
prove lossy timestamps/invalid definitions/malformed verified identities use zero
connections. Tools76/generated/diff checks pass. No local PostgreSQL/native claim.

Twelve new ACTUAL PostgreSQL cases compile and await fresh Linux execution:
roundtrip/repeat/new-token/issuer separation; concurrent identical writes; changed
target/request contract; same token moved to another occurrence; competing
contracts with one winner; tenant/full owner/definition/engine mismatch; exact
V65 grant/tenant/expiry/subject plus atomic rollback; terminal historical readback
with new binding refused; actual commit followed by lost reply then same-identity
reconciliation; ambient caller rollback; independent SQL guards; immutable rows
and issuing reference retention beyond V65's expiry grace. Test runs finish their
fake registry instances to avoid startup reconciliation of nonexistent definitions.
No failure case consumes a production token, starts a real graph or proves workflow401.

The existing issuing-migration TRUNCATE assertion includes the new child table
inside its ALWAYS-rolled-back transaction so it still tests the immutable trigger,
rather than the earlier PostgreSQL referenced-table refusal. No applied migration
is changed and no other fixture is removed.

No acceptance/package promotion. Current final-head review/CI, group/effect
foundation integration, whole governed Flow/adapter/cancellation/partition/recovery,
central audit delivery/dedup/lost reply, retention/archive/quotas and full T03/T13
remain open. The current caller/manual policy interface in Claude #50 a191ef26 is
scoped GPT APPROVE6285730481 with full39385783072 and BOTH strict real-provider
39385783028 SUCCESS; it must still resolve its #47 parent/final retarget review.

## Actual Linux execution, productc63130e (2026-10-20)

Exact c63130e3de4cbfb9ab14a0650b8328719258773c passed BOTH lanes of
39387463567. GPT inspected the complete finished-job logs using the GitHub jobs
API: native118510633790 and standard118510637847. New ResourceCallBindingStoreTest12,
ResourceCallBindingPreflightTest2, existing IssuedTokenBindingMigrationTest5 and
ListenGroupStoreTest11 pass in EACH lane, zero failures/errors/skips. Both reactors
report application281 cases passing; each also runs original EffectCrashRecoveryIT1.
Real standard3/native8 and strict provider/process evidence checks pass.
This supersedes the earlier execution-pending statement for these exact cases.

The later f68abcb commit changes only the audit proposal and CHANGELOG; it is NOT
the SHA executed by this run. Do not transfer exact-head integration approval.
No full build workflow/current peer approval or actual workflow dispatch/T13 is
claimed by these component/provider runs. Final integration still requires
effect/group foundations, current full/strict CI and exact Claude review. The
proposed audit consumer/outbox in WP-108-audit-projection-proposal.md is not code.

## Current effect foundation combined in preparation

Normal merge of frozen #51 exact7cbb4c978e1a1482e21aad29843c73485a237e90 into this
preparation brings the current interruption boundaries and four real SQL recovery
cases together with group V71/V72 and binding V73. It does NOT merge #51 to main,
change its head or assert peer approval. Both source progress histories and
CHANGELOG entries are retained; only execution_plan.status conflicted as a scalar
and generated projections were regenerated from merged JSON.

Combined local application-reactor selected build:62 plain tests (contracts21,
runtime41), zero failures/errors/skips, all application tests compile. Tools76,
generated and diff checks pass. This does NOT transfer old c63130e CI evidence
to the changed combined head. Fresh current-head Linux/peer/full verification
and ordered integration remain required. No dispatch/acceptance activation.

Exact combined product6fbb9fb subsequently passed BOTH39388831952. GPT inspected
finished standard118515146162/native118515146729 logs: actual effects16/group11/
binding12, interruption6, original process recovery1, standard3/native8 and strict
checks pass, zero failures/errors/skips. Final-head full/peer/integration remains.
The later SDK occurrence investigation/tests in WP-103-sdk-occurrence.md establish
an additional production-producer gate: never derive a new call from a changing
SDK task-entry counter on retry. No actual workflow credential driver is enabled.
