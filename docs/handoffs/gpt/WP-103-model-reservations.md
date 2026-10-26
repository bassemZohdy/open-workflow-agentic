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
primary with the resume failure suppressed. Writes preserve inherited isolation;
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

Actual Temurin21.0.12.1+1 / Maven3.9.11 / PostgreSQL16.15: restored full allocation/reservation family116 passes (contracts15/runtime4/app97), zero failures/errors/skips. Two final test-only additions pass in a separate named delta2 run; no production change followed the116 run. The final peer selection is118 (contracts15/runtime4/app99: model store22/migration5 plus prior allocation72). Exact peer and current required CI remain pending. Logs: target/model-reservations-{fence-negative,restored-final,final-delta}.log. Initial six contract plus sixteen store cases had
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
