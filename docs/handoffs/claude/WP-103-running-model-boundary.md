# WP-103: governed model call bound to the merged private Running control

Scope: `GovernedLlmClient` (one new overload), its unit test, one new private-PG integration test, this note. No
SQL/contracts/build/config/tracking change, no CDI/Flow/root activation, no `ResearchAgent` adoption. Not a Flow, SDK or
production-authority closure.

## Change

`GovernedLlmClient.complete(JdbcResourceExecutionGate.Running control, ResourceExecutionEntry expected, system, user)`.
It takes the explicit handle the merged gate mints inside a fresh acknowledged governed callback and the exact expected
entry, and calls `control.requireCurrent(expected)` (exact entry + the gate's live fence + invalidation + interrupt flag)
at four points:

1. before the approved-context lookup;
2. after the (possibly blocking) lookup, before the policy question;
3. after an audited ALLOW, immediately before the physical provider (inside the `Pep.guard` supplier);
4. after the provider returns, before its private output is released.

A missing (`null`) control or expected entry throws `ModelCallRefused` before anything. A closed, ended, mismatched,
interrupted or fence-refused control throws the gate's `RefusedException` (or the fence's own exception) and the provider
is not reached or its output is dropped. No new exception type; `ModelCallRefused` is unchanged.

Unchanged: legacy `complete(system, user)`; `NoApprovedModelContext` still defaults to empty (refuses); a zero remaining
budget still hard-refuses before policy/audit/provider independently of a permissive policy; DENY/ERROR/audit failure
are still `PolicyDenied`.

## Not provided (explicit)

- The overload is NOT an approved-context source, reservation or task mapping. The context still comes only from the
  injected `ApprovedModelContext`; holding a `Running` approves nothing.
- No caller-supplied fence callback is accepted; the handle is not stored, not in a ThreadLocal, metadata or checkpoint.
  The client starts no workers and runs synchronously on the calling thread (a caller may pass the SAME handle to its own
  joined worker, as tested).
- No workload / approved profile / lease / task-to-call mapping is invented; actual V78/V79 sources remain missing. No
  legacy task thread identity is assumed. A provider's own output is not audited here by the gate; a lost control after
  the provider call withholds output but cannot undo the physical call (it happened once).
- Nothing makes `ResearchAgent` use this; production graph calls still go through the legacy overload.

## Tests

`apps/app-runtime/.../agent/GovernedModelRunningControlTest` (new, 21 cases): a fresh private `oa_gm_run_<uuid>`
PostgreSQL DB is created/migrated, Running is minted by a REAL fresh committed `executeGoverned` callback (own small
fixture on public APIs; GPT lifecycle helpers not edited). Fence, approved context, policy engine, audit sink and provider
are labelled fixture mechanics, not production admission/reservations. Covers: positive (1 provider call, exactly 4
control checks, 1 before lookup, 3 before provider), ended control, closed-in-callback, interrupted caller, fence refusal
before; close and fence loss during lookup (no policy question); close during PEP decision and during audit record (audited
ALLOW but zero provider); close, fence loss and interrupt during the provider (output withheld, provider ran once);
exact-entry mismatch (control still usable for the true entry); null control/entry; default context; zero budget with
permissive ALLOW; DENY audited; baseline legacy path ignores a closed control; same handle on another thread, and closed
from the callback thread while a worker is blocked in lookup or provider (worker joined before the callback returns).

`GovernedLlmClientTest` (+2): absent control/entry refused with zero lookups/policy/audit/provider; legacy path unchanged.
A real `Running` cannot be forged outside a gate callback, so the rest is only testable in the integration test.

### Red controls (guard removal, production file restored after each)

Removing one `control.requireCurrent(expected)` at a time and rerunning the integration test (21 cases + 7 architecture):

| Removed check | Failures |
|---|---|
| 1 before lookup | 6 (ended control, closed in callback, interrupted, fence-before, mismatch, positive) |
| 2 after lookup | 4 (close during lookup, fence loss during lookup, worker blocked in lookup, positive) |
| 3 before provider | 3 (close during PEP, close during audit, positive) |
| 4 after provider | 5 (close/fence/interrupt during provider, worker blocked in provider, positive) |

Each check is killed by a distinct negative test. Plus baseline `baselineTheLegacyPathIgnoresAClosedControl...` shows the
unguarded path does reach the provider with a closed control.

## Evidence (Windows, Java 21.0.12, Maven 3.9.11 offline, PG 16.15 on localhost:5432 via `DB_URL`)

- Unit: `-pl modules/oa-runtime -am -Dtest='GovernedLlmClientTest#!theShippedDefaultsRefuseEverything,PepTest,ModelBoundaryArchitectureTest'`:
  13 + 17 + 3 = 33 run, 0 failures/errors/skips. The native-Cedar case `theShippedDefaultsRefuseEverything` stays
  enabled and was deliberately NOT selected here (cannot load native on Windows): it is unverified locally; this is NOT a
  full-suite or green-suite claim. Linux CI must run it.
- Integration: `-pl apps/app-runtime -am -Dtest='GovernedModelRunningControlTest,ResourceExecutionConstructionArchitectureTest'`:
  21 + 7 = 28 run, 0 failures. The test's own `oa_gm_run_<uuid>` DB was dropped without FORCE (0 remain). No Quarkus boot,
  no shared DB migration/reset. No full or native Windows suite run.

## Remaining gates

GPT independent review; Linux CI (native Cedar case, full suite); real approved-context source/profile/lease and
task-to-call mapping (V78/V79); mandatory audit/output-fingerprint propagation; a reviewed composition root that mints
Running and calls this overload; `ResearchAgent` adoption.
