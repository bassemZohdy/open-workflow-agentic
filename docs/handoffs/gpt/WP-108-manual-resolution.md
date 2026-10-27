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
resolver appends that `MANUAL` source with `AuditAppendOnce` before any ledger
mutation. It then rechecks authority and policy after locks, updates the effect
through the existing `effect.outcome` journal reason, and records the V80 apply
link in the same ledger transaction. The existing V77 source projection and
constraints remain unchanged. A retry with the same request id reuses the
stored source/draft and central receipt; a conflicting request or terminal
effect is refused.

## Evidence

- Exact local focused run: `mvn -pl apps/app-runtime -am test
  -Dtest=ManualEffectDecisionStoreTest,ManualEffectDecisionMigrationTest
  -Dsurefire.failIfNoSpecifiedTests=false -Dcheckstyle.skip=true` — 4 tests,
  0 failures/errors/skips, PostgreSQL 16.15.
- Runtime and application test compilation passed.
- `git diff --check` passed.

The V80 SQL is deliberately duplicated under the app test resources to rehearse
the proposal only. Migration rehearsal proves it is absent from Flyway and that
a conflicting existing table rolls back all proposal objects. This is not
deployment-role evidence, a public operator API, target-state proof, a process
crash/lost-ack T03 result, or WP-108 acceptance closure. Exact peer review and
current GitHub CI for the implementation head remain merge gates.
