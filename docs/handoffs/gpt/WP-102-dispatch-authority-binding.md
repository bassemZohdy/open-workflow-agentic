# WP-102: retain dispatch authority across blocking work

The asynchronous dispatch previously checked whether the instance's current grant was usable without confirming
that it was still the grant captured for the task. A reauthorization during exchange or work could therefore leave
the new grant active while old-grant work ran or its result was released.

The consumer now compares the complete captured authority with the registry before and after each grant recheck,
checks it again after exchange and before work, and checks it again after work. It refuses interruption and checks the
token's expiry after the pre-work checks. The tests cover changed bindings during exchange, work and a recheck, as well
as interruption during exchange. The database-backed tests use real stored authority rows and directly rebind them to
simulate the change; their exchanger remains a test double.

When the initial read found delegated authority, a changed, removed or newly undelegated authority during a later read
is reported as revoked, not as an initially ungoverned instance. This matters for the task guard's optional
`allow-ungoverned` mode: it must not swallow a change in an already-governed check. Real Flow-engine listener tests
exercise both changed-grant and removed-authority cases. This mode can still allow an instance whose initial read finds
no delegated authority; deployments requiring every instance to be delegated must use `require-delegated`.

These are application-level observations across separate transactions. A grant can still change after the last check;
this is not a commit-time or physical-effect fence. The Flow production dispatch, credential source, supported issuer
contract, real-provider/restart proof and full WP-102 acceptance remain open.

The reauthorization-required update and rebind compare the grant ID and database-maintained authority revision observed
before their audit writes. V77.6 initializes existing rows and advances the revision on every update, including writes
from an older replica. This detects rebinding to the same grant even when timestamps do not change. The dispatch
authority comparisons also include the revision. The audit records the refusal attempt even if its conditional update
changes no row. Migration must precede new-code rollout; old application replicas still lack the conditional checks and
can perform stale updates until they are upgraded. The trigger makes their writes visible to new-code observations; it
does not make their own decisions safe. This does not make the final authorization check atomic with an external effect.
