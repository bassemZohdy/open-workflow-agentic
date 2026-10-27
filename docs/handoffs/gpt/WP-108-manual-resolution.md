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
second, before updating the effect. The existing V77 source projection and
constraints remain unchanged. A retry with the same request id reuses the
stored source/draft and central receipt. A failed request can be superseded by
a new request id while the effect remains unresolved; a conflicting reuse of
an id or an already applied effect is refused. Multiple immutable requests
may be retained for one effect, but a unique per-effect apply link permits
only one outcome mutation. Replaying an already-applied request returns the
stored outcome after authority and policy rechecks even if its instance has
since become terminal; a new mutation still requires a current active instance.

## Evidence

- Exact local focused run: `mvn --% -pl apps/app-runtime -am test
  -Dtest=ManualEffectDecisionStoreTest,ManualEffectDecisionMigrationTest
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true` — 9 tests,
  0 failures/errors/skips, PostgreSQL 16.15. Store coverage includes same-request
  retry/conflict, denied/unverified caller, superseding a policy-refused request,
  concurrent identical requests, audit-append failure recovery and caller
  expiry during the final policy recheck. It also verifies applied replay after
  instance completion, a slow policy recheck timeout with lock release, and
  distinguishes requested audit intents from the terminal applied outcome.
  Migration coverage verifies proposal isolation and transactional rollback.
- Runtime and application test compilation passed.
- `git diff --check` passed.

The resolver test PEP permits only a user with `oa.recover` requesting the
`operation:resolve-effect` boundary. The bundled Cedar snapshot's scope and
principal rules are independently exercised by `ResolveEffectPolicyTest`.

The V80 SQL is deliberately duplicated under the app test resources to rehearse
the proposal only. Migration rehearsal proves it is absent from Flyway and that
a conflicting existing table rolls back all proposal objects. This is not
deployment-role evidence, a public operator API, target-state proof, a process
crash/lost-ack T03 result, or WP-108 acceptance closure. Exact peer review and
current GitHub CI for the implementation head remain merge gates.
