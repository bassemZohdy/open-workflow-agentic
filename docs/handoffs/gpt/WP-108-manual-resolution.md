# WP-108 manual effect resolution proposal

Owner: GPT. Status: focused implementation candidate; WP-108 remains partial.

## Scope

This increment adds a proposed V80 store and an explicitly constructed Java
resolver for one attempted `INTENDED` effect that has a retained
`effect.outcome.unknown` journal fact. The resolver requires a current verified
user token with `oa.recover`, a fresh `RESOLVE_EFFECT` PEP ALLOW, a stable caller
request ULID, and a closed reason/state pair. There is no CDI registration,
public route, Flyway migration or default runtime activation.

The immutable V80 request retains caller, scope, desired state, finite reason,
policy attribution, central audit source identity, draft id and draft time. The
resolver appends an `effect.manual-resolution-requested` central audit event
before any ledger mutation. Its `REQUESTED` status is an immutable decision
intent; the existing `effect.outcome` journal fact and atomic V80 apply link
identify the decision that actually changed state. It then rechecks authority
and policy under the apply transaction's locks, with each check bounded to one
second, before updating the effect. Retain and apply transactions cap PostgreSQL
lock, statement and idle timeouts at 2, 5 and 5 seconds respectively. The V80
apply trigger also requires the exact central MANUAL source binding and
`REQUESTED` event before it permits the local outcome mutation. The existing
V77 source projection and constraints remain unchanged. A retry with the same request id reuses the
stored source/draft and central receipt. A failed request can be superseded by
a new request id while the effect remains unresolved; a conflicting reuse of
an id or an already applied effect is refused. Multiple immutable requests
may be retained for one effect, but a unique per-effect apply link permits
only one outcome mutation. Replaying an already-applied request returns the
stored outcome after authority and policy rechecks even if its instance has
since become terminal; a new mutation still requires a current active instance.
The request-id idempotency check runs after registry and ledger locks are held,
so a concurrent loser observes a decision retained or applied by the winner.

## Evidence

- Exact local focused run: `mvn --% -pl apps/app-runtime -am test
  -Dtest=ManualEffectDecisionStoreTest,ManualEffectDecisionMigrationTest
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true` — 10 tests,
  0 failures/errors/skips, PostgreSQL 16.15. Store coverage includes same-request
  retry/conflict, denied/unverified caller, superseding a policy-refused request,
  concurrent identical requests, audit-append failure recovery and caller
  expiry during the final policy recheck. It also verifies applied replay after
  instance completion, a slow policy recheck timeout with a `FOR UPDATE NOWAIT`
  lock-release probe, a PostgreSQL lock-timeout refusal, and distinguishes
  requested audit intents from the terminal applied outcome. A direct-SQL
  attempt to apply a retained decision before its exact central source receipt
  exists is rejected and rolled back.
  Migration coverage verifies proposal isolation and transactional rollback.
- Runtime and application test compilation passed.
- `git diff --check` passed.

The resolver test PEP permits only a user with `oa.recover` requesting the
`operation:resolve-effect` boundary. The bundled Cedar snapshot's scope and
principal rules are independently exercised by `ResolveEffectPolicyTest`.

The V80 SQL is deliberately duplicated under the app test resources to rehearse
the proposal only. Migration rehearsal proves it is absent from Flyway and that
a conflicting existing table rolls back all proposal objects. Claude reviewed
implementation head `19681a07daa56b3f7676664a4426547bf8c06ca8` and returned PASS;
exact-head GitHub check `40433470521` and post-merge main check
`40435285080` both succeeded. The follow-up reviewed head
`50d75c97320eb5aca70a3e93597bbc58de45b151` passed Claude review and merged as
`70a6337f867788cbad1a07052ded5a7a4f2bafb3`. Its focused PostgreSQL run passes
12 tests with zero failures/errors/skips, exact-head CI `40438255834` and
post-merge main CI `40439483024` both succeeded. It matches the verified caller
tenant before PEP and sets READ COMMITTED in the bounded transaction helper; a
REPEATABLE READ test connection verifies the override. Audit-table grant
verification remains an activation gate. This is not deployment-role evidence,
a public operator API, target-state proof, a process crash/lost-ack T03 result,
or WP-108 acceptance closure.


PR #104 adds ambient JTA regression coverage. Claude reviewed and independently
ran 12 focused PostgreSQL tests at `3d62ad4ac1b61992ae52dd16db173f9ee37b0c1c`
with zero failures/errors/skips; exact-head PR CI `40788462057` passed and it
merged as `2c25b4bda6cd33f6deac65a6115412018ec951bd`; post-merge main CI
`40789673082` passed. The test observes the
resolver and ledger connections opening with no ambient transaction and checks
that retain failure restores the caller transaction. The audit append uses a
plain non-enlisted datasource in the fixture; behavior with a managed audit
datasource and audit-table grants remain activation gates. V80 and WP-108 stay
partial.
