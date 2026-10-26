# WP-103: a zero remaining model budget refuses before the provider

Scope: `GovernedLlmClient` and its unit test only. Not production composition.

## Change

`ApprovedModelContext.Approved` permits `budgetRemainingMicros == 0`, and the budget is only an input to the PEP, so a
permissive ALLOW policy could admit a physical provider call with nothing left. `GovernedLlmClient.complete` now throws
`ModelCallRefused` when the approved context's remaining budget is zero, before the policy is asked, before any audit
record and before the provider.

Unchanged: a missing context still refuses (`NoApprovedModelContext` still defaults to empty); a positive budget goes to
the PEP exactly as before; policy DENY/ERROR and audit failure are still `PolicyDenied`, audited as before.

## Limits

- The counter is read as given. It is not a reservation: nothing here decrements, holds or reconciles budget, and two
  concurrent calls with a positive counter are both admitted.
- No approved source, task or grant is invented; no positive-budget cache, TTL or default allowance.
- The zero refusal is not audited by the PEP (no policy question is put), like the no-context refusal.

## Tests (`GovernedLlmClientTest`)

- `aZeroRemainingBudgetRefusesBeforeTheProviderEvenWhenThePolicyWouldAllow`: ALLOW engine, budget 0 gives
  `ModelCallRefused`, 0 provider calls, 0 policy questions, 0 audit events. Failed on the unchanged source (nothing thrown).
- `aPositiveRemainingBudgetStillReachesThePolicyAndTheProvider`: budget 1 gives one policy question and one provider call.

Run: `-Dtest=GovernedLlmClientTest,PepTest,ModelBoundaryArchitectureTest` (Java 21, offline). This command is not green:
on Windows 33 of 34 cases pass and 1 errors. The named suite did execute the native-Cedar-dependent case
`GovernedLlmClientTest.theShippedDefaultsRefuseEverything`; it errors with a native Cedar `UnsatisfiedLinkError` both
before and after this change (Windows environment, not caused by this change). It was run, not skipped, and it was not
disabled or removed. Native Cedar did not load here, so that case is unverified on Windows; Linux CI must run all 34.
