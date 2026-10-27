# WP-103 proposed model reservations

This opt-in component holds the full configured ceiling for one original model-node
occurrence before invoking owned authorization/preparation and a synchronous fixture
closure. It supplies no CDI bean, registered model approval, price proof, provider
transport, actual graph/node producer, credential or production execution permission.
V79 remains in `db/proposed` after actual V77 and revised proposed V78.

Only the selected budget/node/reservation values, port, JDBC component, six value
tests and proposed SQL were extracted from `gpt/WP-103-execution-contract` and reworked.
The old redirected-schema Arena and its tests were not adopted. New tests migrate
actual private PostgreSQL databases through V77 and explicitly apply V78/V79.

## Retained accounting and invocation

Node identity binds the canonical resource call and complete trusted graph occurrence.
Changed request, output limit, worker UUID or execution epoch conflicts at the same
node rather than minting a new allowance. Constructor-valid values are DATA, not a
capability. Only an actual future private producer may supply occurrence coordinates.

The source account binds full tenant/instance/owner kind and id plus the actual retained
parent task, or the original allocation when no parent exists. Siblings share their
actual parent source cap. SQL serializes against the real registry row and derives
source fingerprints, original lifetime, held ceiling, cumulative leaf/account balances
and ordinal/count from retained facts. It never accepts a caller's remaining counter.
These are MODEL holds, not complete cross-category task spending or measured billing.

Only a newly inserted, acknowledged committed reservation can reach preparation and
one closure invocation. Exact historical reads/repeats never invoke either. All lost
commit replies, denied preparation, unknown callback outcomes, expiry, revocation,
cancellation and cleanup failures retain the full ceiling. There is no refund,
automatic retry or renewal. Output is withheld after a late refusal; already completed
callback work is not undone. `protectedCallBudgetMicros()` describes this call's held
ceiling, not free total balance or reusable task maximum.

Short owned transactions and the caller's real suspended JTA surround database work.
Connections are released before both callbacks. Metadata/current checks and the
private supplied live-authority fence run before reservation, after blocking
authorization, and after callback work before output. The consumer must implement
signed identity, actual grant/model scopes, current PDP/mandatory audit, source
approval, lease ownership, physical timeout and single provider attempt.

The JDBC constructor requires a transaction manager. Failure to resume refuses an
otherwise successful return; simultaneous primary/resume failures preserve the
primary with the resume failure suppressed. Reservation/current-check writes require READ COMMITTED and refuse every other configured isolation without downgrading it;
historical snapshots use at least REPEATABLE READ and retain SERIALIZABLE. Owned
transaction-local namespace pins and 2s lock/5s statement bounds preserve stricter
nonzero settings. They are not an acquisition/network/whole-call timeout guarantee.

## Proposed expansion

V79 requires one owned READ COMMITTED expansion transaction, refuses other isolation
before DDL, pins all new SQL function namespaces, preserves stricter inherited bounds,
and clears PUBLIC privileges on both new tables even under permissive creator defaults.
Conflicting existing schema must roll back without partial functions/tables or edits
to applied migration history. This is not deployed executor/writer-role proof.

## Verification

Actual Temurin21.0.12.1+1 / Maven3.9.11 / PostgreSQL16.15: restored full allocation/reservation family116 passes (contracts15/runtime4/app97), zero failures/errors/skips. Two final test-only additions pass in a separate named delta2 run; no production change followed the116 run. The previous peer selection was118 (contracts15/runtime4/app99: model store22/migration5 plus prior allocation72). That initial head subsequently received Claude REQUEST_CHANGES for inherited REPEATABLE READ sibling accounting; those earlier passes are not current-head approval. Logs: target/model-reservations-{fence-negative,restored-final,final-delta}.log. Initial six contract plus sixteen store cases had
one fixture error: a parent was inserted RUNNING despite the existing PENDING insert
contract. The fixture now uses real pending insertion and running transition; the
expanded six-contract/eighteen-store run passes24.

The post-authorization fence negative control removes only that Java fence. The
identity-loss test fails with expected zero closure calls, actual one. Original Java
bytes are restored before regression runs. Fixture callback counts are not evidence
of a physical provider attempt or interruption of an SDK worker.

Coverage includes exact/reopen identity, conflicting node facts, real concurrent
same/distinct nodes, count/leaf/parent ceilings, unchanged historical holds, actual
commit with lost reply, real ambient JTA rollback and resume failures, single source
connection slot across callbacks, corruption versus absence, actual grant revocation,
registry cancellation, DB-clock original allocation/node deadlines, historical exempt
execution refusal, immutable accounting rows and proposed migration failure/namespace/
PUBLIC-default checks. Per-case UUID databases are verified and dropped without FORCE;
the common DB is independently created/verified and dropped after Maven exits.

## Remaining gates

No activation or package/acceptance closure is claimed. Approved immutable model
configuration/price provenance, restricted production roles/defaults, the actual
occurrence/task/lease/Root producer, per-node/provider private context and fresh
identity/PDP/audit, physical single-attempt bounded I/O, measured usage/other budget
categories, actual Flow terminal/recovery and physical async termination remain open.
The shipped approved-model context and canonical admission still refuse by default.
Issues75/76 and the allocation migration plan remain open; no historical backfill,
parent thread remapping or permission from a receipt/hash/readback is introduced.


## Peer correction: stale writer snapshots

Claude found that an unchanged registry-row lock cannot refresh an inherited
REPEATABLE READ view of sibling holds. Root reproduced the ACTUAL old component:
seed1000 plus two sibling1000 holds committed against one original2500 parent cap,
for actual total3000. The new regression failed expected retained1000/actual3000.
This is not just a separate psql demonstration. No real provider was called.

The corrected protocol requires READ COMMITTED for mutation/current-check transactions
in Java and in all three proposed SQL guard paths. Unsupported settings are refused,
never silently weakened. Historical snapshots still retain SERIALIZABLE and stricter
100ms/500ms settings. New true concurrent sibling cases cover read committed (exactly
one new1000 hold/callback after an observed registry-lock wait) and repeatable read /
serializable (both refuse, seed hold unchanged), plus direct SQL isolation refusal.
Five focused fixed-isolation cases pass. The old Java/SQL bytes were used only for the
negative control, then the corrected bytes restored. The 27-case model reservation
store suite passes on the rebased candidate, zero failures/errors/skips; the direct
SQL state tamper refusal and aggregate-vs-retained amount/count checks are included.
The state summary removes account-wide historical rehash/sum work from the locked
insert path. A non-locking read-only currentness variant avoids the exclusive registry
lock after callbacks while reservation insertion retains its serialization lock.
Client-side 5s query timeout and 30s socket bound complement the stricter 2s/5s
server limits; the socket bound leaves commit headroom. A failed timeout reset after
commit evicts the connection without changing the committed result. Owned transaction
network timeout setup/reset/abort, PostgreSQL timeout caps and read snapshot isolation
now use shared `Transactions` helpers also used by the execution/history gates.

## Integrated result

PR #98 merged as `084a02d` after Claude exact-head PASS on
`df8daa129c5d3a526a418238dbe4bf22afef3828` and current PR CI
`40428062558` SUCCESS. Independent focused verification on that head passed 64
tests (reservation store27, effect-history20, execution-gate17), zero failures,
errors or skips. Claude also independently ran the 27-case store suite plus 44
effect-history/execution-gate/IO-bounds cases, all green. Claude noted only
non-blocking comment indentation and public-helper documentation nits. The post-merge
main build `40429186511` was still in progress at this tracking update. The merged
increment remains DATA-only: no source approval, budget authorization, model call,
consumer activation or package/acceptance closure.

The initial independent118 run had one private database teardown timeout while another
broad test run was creating/dropping databases; all bodies passed, and independent
store22 rerun passed. No clean single118 peer pass is claimed. Cleanup used no FORCE.

Additional limits: this account protocol aggregates only an immediate parent task or
root allocation, not a grandparent hierarchy. Privileged deletion of a reservation
TAIL with triggers bypassed is not detectable here; normal immutable guards and the
future restricted production-role boundary are required. Hashes do not prove database
owner honesty or restore/rollback authenticity. These limits do not authorize reentry
from historical data or relax the default denial / actual producer gates.
