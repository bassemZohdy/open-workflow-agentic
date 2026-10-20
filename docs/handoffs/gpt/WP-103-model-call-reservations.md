# WP-103 model reservations and callback gate (GPT -> Claude)

Partial preparation on existing gpt/WP-103-execution-contract. #55 remains the
sole GPT implementation PR, frozen ea56f33, with exact Claude review still needed.
No new PR, CDI bean, model provider, migration or gateway activation. This extends
the verified allocation source1ae6886/BOTH39426839423, not its approval scope.
See ADR0030 for the decision and the prior task-authority/budget handoff.

## Producer, consumer and exact APIs

GPT supplies ResourceModelCall, ModelBudgetAccount, ModelCallReservation,
ModelBudgetExhaustedException/ModelCallAlreadyReservedException and ResourceModelCalls.
The explicit JdbcResourceModelCalls implements a callback-only once protocol;
V79 remains in db/proposed. Actual model gateway/PEP/identity/audit/approved config
and transport enforcement remain Claude's interfaces and code. No Claude paths
are edited. GPT lifecycle stores/config/migration/contracts/test paths retain their
existing ownership. Prior15 allocation test bodies remain, with shared fixture
setup extracted to a non-test abstract class; they require fresh regression proof.

ResourceModelCall.of takes the exact retained allocation/entry, actual entering
executor UUID, complete canonical node occurrence and actual ModelCall. It checks
original task/model/token bound and hashes EXACT task/model/prompt/token request.
Only the digest is retained. The node's mn1 identity hashes domain
 oa.resource.model-node.v1 + text(original call key) + text(nodeOccurrence).
It EXCLUDES prompt/credentials/worker/execution retry epochs. Full intent digest
uses oa.resource.model-node-contract.v1 with node key/entry re1/executor/request
fingerprint/output tokens. Genuine loops/branches need actual durable coordinates;
never invent a nonce or use a fresh SDK retry counter to create another budget.

ModelBudgetAccount mb1 hashes full tenant/instance/owner/anchor kind/id under
 oa.model.budget-account.v1. The anchor is the ACTUAL parent task when present,
otherwise original ra1 allocation. It retains original full source contract digest,
cost cap and deadline. Its content digest domain oa.model.budget-account-contract.v1
binds key/source digest/cap/createdAt/deadline. ModelCallReservation retains full
intent, original allocated task/account, server held ceiling, original after-
reservation snapshots, ordinal/call slots, reservedAt/callDeadline. Fingerprint
 oa.model.reservation.v1 binds ALL those facts, including original allocation
and account timestamps. CanonicalEncoder uses strict UTF-8 length prefixes and
int8 big-endian values; timestamps are seconds+nanos, exact microseconds. Account
snapshots and constructed data never authorize or establish measured usage.

ResourceModelCalls.invokeOnce(intent, private ResourceExecutionFence,
Authorization<T>) reserves in one owned transaction. Only a known fresh committed
INSERT may call Authorization.authorize(reservation), which returns Supplier<T>.
That authorizer must perform real model PEP and mandatory audit WITHOUT calling
the provider. The gate refreshes actual database facts and then live private
identity AFTER blocking authorization, before invoking the synchronous closure
once, and after it before returning private output. Existing intent always refuses;
find(intent) is exact historical DATA, not another callback. Wrong content conflicts.

Pass protectedCallBudgetMicros (the full held ceiling for THIS call) to the real
Pep.modelCall budget context, not a caller integer/reset max or a free-balance
snapshot another node can consume. Claude must review this conservative protected
allowance semantics with its policy. Missing profile/price/max-charge guarantee,
actual model scope/approval/current identity, budget state, audit or private context
must make ZERO physical calls. The gate does not verify actual registered approval,
model-action scopes/pricing or make a supplied lambda trustworthy.

The closure must make exactly one synchronous physical attempt, with no hidden
SDK/provider retry and a transport timeout bounded by the remaining ORIGINAL
call deadline. No automatic model fallback. A string response carries no trusted
usage: never turn the held cap into ModelResult.Completed measured cost. The
shared ModelGateway/ModelResult documentation is corrected accordingly.

## Data authority, transaction and lifetime

New reservations lock the actual registry, then validate immutable call/ro1,
allocation/profile/contract, actual irreversible entry/executor, real grant
ancestry/delegate, actual parent RUNNING/RESUMING and narrowing, original account
source and deadlines. Both leaf profile call count/cost and shared parent's MODEL
cost are checked atomically from retained facts. Siblings cannot obtain separate
parent caps. Model-call count is NOT inferred from task.maxIterations. Other
cost categories/parent hierarchies and actual loop enforcement remain future gates.

Amounts/counts/timestamps/hashes are database assigned; direct overrides refuse.
The full ceiling stays HELD on authorization/provider/deadline/output/cleanup
failure, including uncertain replies; it is not reported as actual expenditure.
No refund or settlement protocol is supplied. Unknown actual commit replies do not
enter callbacks; reconcile the SAME node, never a new budget/task/credential key.

All hooks run with caller JTA suspended. Own JDBC transactions close before
callbacks; local lock2s/statement5s limits bound own waits, not the caller's locks.
Post-I/O current checks and interruption preserve committed facts. Historical find
uses a coherent repeatable snapshot; new writes serialize under READ_COMMITTED
with a fresh read AFTER lock wait. Missing/changed metadata refuses; no latest-row,
NONE/default allowance or blind retry fallback. Do not serialize private guards
or use ambient ThreadLocal identity propagation.

Call deadline is min(original allocation/account deadlines, DB reservedAt +
profile maxCallTime FLOORED to microseconds). Authorization consumes that original
time; no silent rounding up or timeout reset. Provider cancellation is not supplied
by postchecks. Direct production calls still REQUIRE their own genuine authorized
allocation; these records do not implement the owned consumer refusal.

| Data | Authority/lineage | Classification | Retention/quality |
|---|---|---|---|
| Account | Real parent contract/lifetime or original leaf allocation | Confidential owner/limit metadata | Immutable, no cap/lifetime reset; actual source rechecked |
| Reservation | First canonical node/entry + original profile cap + DB clock | Confidential fingerprints/allowance, no raw prompt/token/result | Immutable FK facts, one original node, exact hashes, no refund on uncertainty |
| Usage/results | NOT measured/cached here | Private | Needs trusted provider usage/idempotency and owned graph/result protocols |

Restore/partition/archival remain unproved. Preserve calls, occurrences,
allocations, accounts, reservation facts, grants, parents and execution together;
no model replay from an incomplete restored ledger. Data hashes are not storage
authenticity or administrator immunity. No physical provider/whole T06/T03 claim.

## Verification and open gates

Author verification=GPT (self-check, not Claude peer approval); exact tested
product SHA 4bbf02906c54168bb194fc35c3d7dc8ea501408b.
Local 212 contracts (six new) pass with zero skips. Local application test
compilation is separate from execution. The 24 new SQL/callback-protocol cases
and 15 preserved allocation regressions execute in both fresh CI lanes below.
These include concurrent
same/distinct nodes and aggregate sibling caps, commit/JTA reply loss, no-call/
output refusal after lifecycle/grant/parent/deadline/private-fence/interruption,
one-connection admission and caller-held-lock timeouts, missing/corrupt metadata,
direct SQL cost/batch/retention, and original historical facts. Callback spies and
JDBC failure injection are labelled; they are not actual model/JWT/PEP/transport proof.
Current product 4bbf029 passes [BOTH39434232554](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39434232554).
Both completed full logs inspected: standard job 118666920763 / delegation job 118666920039.
Each executes contracts 212 / runtime 344 / testkit 196 / app unit 343, with zero failures,
errors or skips. ModelCallReservationStoreTest 24 and all 15 prior allocation
regressions pass, including the corrected active-trigger corruption check (SQLSTATE 23514).
Exact current source method names match all 24 + 15 actual XML cases; contracts 6,
allocation contracts7/preflight3/conformance7/binding17/proposed execution6/source6
also pass. Real standard 3 / delegation 8 / core process 1 EACH and strict checks pass.
The standard lane has 4 failsafe cases; delegation has 9. These real Keycloak tests
exercise the existing identity contract, not an actual model provider.

Downloaded artifacts 12269298608 (standard) and 12269578641 (delegation); ALL 154
actual suite XML reports per lane parsed, counters/unique identities/cases checked,
zero failures/errors/skips, strict provider checker rerun with matching --profile
plus --effect-recovery. GitHub reports artifact SHA256 digests respectively
bf0b86d9f7f3ef7e34b03774e84f4fc191c71af950375e54e62fdd721aafb372 and
efa3a6cc7b81f69147b8800252b477ed2c5dd60f7cc71d0fdb9ecc3bb3c9cb9f.
Both actual conformance reports match the assessment with ONLY the two bounded
known wrong fork-order fingerprints admitted: 42 sources / 76 serialization attempts,
74 successes/two metadata writer diagnostics/48 outputs (30 MATCH,18 DIVERGED).
Actual 48 output files, 74 export files and two expected diagnostics inspected per
lane. No full semantic-conformance or separate WP-108 audit-process4 proof transfers.

Commands/results: CI mvn -B -pl apps/app-runtime -am verify with required real
provider profile and EffectCrashRecoveryIT succeeds; Python evidence checker above
succeeds. Local 212 contracts/six new tests and selected 6 + 3 codec/preflight pass;
current all-eight-module mvn -B verify -DskipTests compiles/packages, and actual
dependency policy holds from fresh dependency lists. That skipped command is NOT
a full local suite. Application test sources compile; tools 76 / generated/diff pass.
Local full Cedar JNI/SQL/provider execution is not claimed.

Verdict: preparation verified; integration HOLD for exact Claude peer/final PR
and actual consumer gates. #55 remains frozen ea56f3319d0e896d54703d9f4d857252906ed2a4
without a Claude review; no second GPT implementation PR or merge. Signed identity,
real PEP/audit, registered profile/model scope/price/retry/transport and physical
model calls were NOT supplied by callback spies. No migration/CDI/gateway activation,
full task budget/T06/T03, restore or package/acceptance promotion.

Claude: review the protected-allowance PDP input, original profile/cost/retry and
synchronous-closure requirements before adopting. Implement actual non-bypass
model gateway/identity/audit/timeout/no-physical-call tests only after the specific
shared interface reviews merge. GPT still owns complete execution/occurrence/
lease/fence integration and ordered migration/build configuration. No full T06,
precise usage/settlement, process/restore/partition or private-output/HTTP success
is established by this preparation. #53 d38776c/#56 26def97 material findings and
required one-PR consolidation remain. No package or acceptance status promotion.

Initial product eef6c4f / BOTH39433117858 FAILED in both lanes: all24 new
model reservation cases ran, with23 passing and one failing exact SQLSTATE
assertion. The corruption fixture disabled its user triggers to alter retained
held_micros, then failed to re-enable the INSERT validator before testing the
next node. PostgreSQL correctly refused missing database-assigned fields23502,
which was NOT proof of the intended retained-corruption guard23514. Restore
USER triggers immediately after each owned corruption mutation (also the
profile mutation) in finally; retain exact expected23514. No permissive expected
SQLSTATE change or product bypass. All15 prior allocation regressions ran/pass
in both failed runs, but no overall/provider/strict success is claimed there.
Both complete failed job logs118663038740/118663039058 inspected and retained.
