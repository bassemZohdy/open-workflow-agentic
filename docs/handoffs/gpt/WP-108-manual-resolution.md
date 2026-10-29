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


PR #105 suspends the caller transaction around the entire central audit append,
including uncertain-commit readback, and resumes it on success or failure. The
retained decision, audit intent and ledger outcome are separately durable;
caller rollback cannot undo them. A failed audit can leave a retained decision
with the effect still unresolved. Claude reviewed `a1e0737d6b1900166917f6504aa50e7c300402bf`
and independently ran 13 PostgreSQL tests without failures/errors/skips; PR
CI `40790001025` passed and the change merged as
`2305bce6ec3376c53bc74454ec663d0cada15621`; post-merge main CI
`40791140439` passed. Tests observe transaction suspension
at audit connection acquisition and restoration after an injected connection
failure. A real JTA-enlisted datasource and deployment role grants remain
activation gates; no CDI route, Flyway V80 activation, or WP-108 closure is claimed.


PR #107 adds a real-commit, lost-reply and failed-readback test under an ambient
transaction. It observes suspension for both connections, verifies the effect
remains unresolved after the uncertain result, then retries the same request
and retains one audit event and one applied outcome. Claude reviewed and
independently ran 14 PostgreSQL tests at `7567c9e3a7a0a35c1c5e362c8d9782786c7e2c05`
without failures/errors/skips; PR CI `40791984770` passed and it merged as
`3576a94514ff3b4804bb8e09d3bee496f598b9b6`; post-merge main CI
`40793439594` passed. The test uses a plain observed
datasource; real JTA-managed datasource and deployment grants remain activation
gates. No V80 activation or WP-108 closure is claimed.


PR #109 tests a private PostgreSQL database with an Agroal pool integrated with
Quarkus Narayana. The caller marker rolls back while the manual decision, audit
event and source binding, and applied outcome persist. Claude reviewed
`53c89ab2e9f71bd5f2bf60a0466f2ea5636087cd` and independently inspected CI
`40794331994` (15 focused tests, zero failures/errors/skips) and both passing
provider checks `40794331895`; it merged as
`57389b34cc9740c5d3465dc229cc5fed07473340`; post-merge build `40796036689`
and provider checks `40796036659` passed. After merge, Claude independently
ran the same 15 tests locally with zero failures/errors/skips. An earlier local
execution attempt failed before test execution because PostgreSQL was down. The
pool is created for the test: deployed datasource configuration and role grants
remain activation gates. V80 and WP-108 remain partial.


PR #111 tests the proposed V80 apply guard with a disposable non-owner role.
Without audit table SELECT it fails with 42501 and the effect update rolls back;
after SELECT on `audit.source_binding` and `audit.event`, the same request
completes while audit INSERT remains absent. Broad effect grants are fixture
setup and do not establish a production least-privilege profile. Claude reviewed
`833c013679fa9b60ef6074054f9ec8bbd7ab00dd` and independently ran 16 focused
PostgreSQL cases with zero failures/errors/skips; PR CI `40796666096` passed
and the test merged as `d7099067f5cc0bafc5a4f9bc0878866f235c3df9`. Deployed
roles/datasource, V80 activation and WP-108/T03 closure remain open.


PR #113 injects an ambient transaction resume failure after audit append. The failure
is surfaced; after the caller transaction is restored, a same-request retry retains
one audit event and one applied outcome. Claude reviewed `28455e107c45d6bd76311bbce456f7a0021016c2`
and independently ran 17 focused tests with no failures, errors or skips. PR CI
`40798076108` passed; the test merged as `0d5aa2b88427e1511d387a4ac7aeec8f531b4321`.
The post-merge build `40799276896` is pending. Lookup and retain resume failures,
and failure of the real transaction manager to resume, are not covered. Deployment
datasource and role configuration, V80 activation and WP-108/T03 remain open.
