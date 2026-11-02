# WP-102: retain dispatch authority across blocking work

The asynchronous dispatch previously checked whether the instance's current grant was usable without confirming
that it was still the grant captured for the task. A reauthorization during exchange or work could therefore leave
the new grant active while old-grant work ran or its result was released.

The consumer now compares the complete captured authority with the registry before and after each grant recheck,
checks it again after exchange and before work, and checks it again after work. It refuses interruption and checks the
token's expiry after the pre-work checks. The tests cover changed bindings during exchange, work and a recheck, as well
as interruption during exchange. The database-backed tests use real stored authority rows and directly rebind them to
simulate the change; their exchanger remains a test double.

These are application-level observations across separate transactions. A grant can still change after the last check;
this is not a commit-time or physical-effect fence. The Flow production dispatch, credential source, supported issuer
contract, real-provider/restart proof and full WP-102 acceptance remain open. Separately, `InstanceAuthorities.require`
currently marks a row by instance ID after an audit write without comparing the observed grant; a delayed old-grant
refusal can mark a replacement grant for reauthorization. That availability race needs a conditional update and
database-backed verification; this change does not fix it.
