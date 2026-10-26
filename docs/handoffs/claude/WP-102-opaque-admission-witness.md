# WP-102 opaque admission witness (foundation only)

Branch `claude/WP-102-opaque-admission-witness`, base main 5083e99. WP-102 / M11 / WP-108 stay PARTIAL.

## What exists
In `runtime/identity/AgentResourceAdmission` (nested types, so the private constructors are reachable only from it):

- `admitCall(bearer)` returns `AdmittedCall`. It runs the real `ResourceTokenGuard.admit` (one replay use) and the full
  `boundCall` lookup, then re-runs `guard.revalidate` (signed expiry, issuing grant and ancestors, no replay use) AFTER the
  blocking lookups, and refuses if the admission was closed meanwhile.
- `bind(AdmittedCall, ResourceExecutionEntry)` returns `BoundCall`, once only (compare-and-set). It requires same admission
  instance, open admission, unexpired signed expiry, and `entry.scope().equals(call scope)` (FULL record equality, not key or
  hash). A refused attempt does not consume the once-only bind. It does not repeat admission and touches no replay store.
- `BoundCall.requireEntry(expected)` re-checks open/unexpired and returns the exact retained entry (scope and epoch equal).
- `close()` (also `@PreDestroy`) invalidates all witnesses at once.
- `admit(bearer)` and `Principal` are unchanged (historical data). No factory takes `Principal`, `VerifiedToken`, a stored
  binding or a scope as proof. No accessor exposes token, claims, bearer or a freshness flag; `toString` is redacted; neither
  type is `Serializable`.

## What it is NOT
Admission/attribution evidence only. Not a current permission, not a callback acknowledgement, not a
`ResourceExecutionFence`, not a graph capability. `bind` does not claim the entry is fresh: the GPT durable gate alone owns
NO_ENTRY -> ENTERED, UNKNOWN and dispatch. A second credential for the same call key admits and may bind an equal entry;
that is attestation, not another fresh entry. The future Claude live fence must re-read grants, bindings and PDP after
blocking I/O and take a server-trusted workload input (no workload inference, no cached decisionFresh, no graph/model default
allow). Legacy default-unsatisfied `ResourceCallBindings` and missing `ApprovedModelContext` behaviour is untouched; the
canonical store is not wired as a fallback. Nothing is stored in graph/workflow state, RunnableConfig, checkpoint metadata or a
ThreadLocal; no graph, provider, worker pool or async context was added.

## Tests
`AdmittedCallWitnessTest` (14 tests, private PostgreSQL database created and dropped by the test, Flyway migrated; no shared
tables touched). Covers: real path only; bad signature, missing binding, expired, unconfigured; forged data not a mint path
(public-surface check, not constructor reflection); other admission instance; close (also during a slow lookup); expiry;
full-scope change with SAME call key (target, request, audience, operation); second bind, changed epoch, 8-way concurrent
bind; second credential; revocation and expiry during the slow lookup with replay consumed exactly once; replayed bearer;
historical `admit` still works; actual `ObjectOutputStream` and Jackson `ObjectMapper` refuse handles.

## Commands (Windows, Java 21.0.12.1, Maven 3.9.11, JAVA_HOME set per process, offline)
- `mvn.cmd -o -q -pl modules/oa-runtime -am test -Dtest=AdmittedCallWitnessTest,AgentResourceBindingAdmissionTest` -> 14 + 6 passed.
- `... -Dtest=AgentResourceAuthorityTest,AgentResourceUserFreshnessTest,CallerAuthorityTest` -> 4 + 3 + 11 passed.
Not run (known failing on Windows): full runtime, nativeCedar, symlink, process suites.

## Limits
No claim of live Flow dispatch, Flow serialization or context propagation, worker retirement, or deployment mapping. The
Jackson/ObjectStream test only shows these two types refuse; it is not coverage of a Flow path. Linux CI is for GPT to run.
