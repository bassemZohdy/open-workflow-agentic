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
are labelled fixture mechanics, not production admission/reservations. Covers: positive (exactly 1 provider call, the expected output released, the ALLOW audited; no fence-count assertions), ended control, closed-in-callback, interrupted caller, fence refusal
before; close and fence loss during lookup (no policy question); close during PEP decision and during audit record (audited
ALLOW but zero provider); close, fence loss and interrupt during the provider (output withheld, provider ran once);
exact-entry mismatch (control still usable for the true entry); null control/entry; default context; zero budget with
permissive ALLOW; DENY audited; baseline legacy path ignores a closed control; same handle on another thread, and closed
from the callback thread while a worker is blocked in lookup or provider (worker joined before the callback returns).

`GovernedLlmClientTest` (+2): absent control/entry refused with zero lookups/policy/audit/provider; legacy path unchanged.
A real `Running` cannot be forged outside a gate callback, so the rest is only testable in the integration test.

### Red controls (guard removal, production file restored byte-identical after each)

Removing one `control.requireCurrent(expected)` at a time (lines 85/88/98/100 of `GovernedLlmClient`) and rerunning
`GovernedModelRunningControlTest` (21 cases). Every failure is an intended refusal/output assertion; the positive test
no longer fails by design (it does not count checks):

| Removed check | Failures | Failing assertions |
|---|---|---|
| 1 before lookup | 5 | ended control, interrupted caller, fence refusing before, exact-entry mismatch: "no approved-context lookup" expected 0 was 1; control closed in callback: expected `RefusedException` |
| 2 after lookup | 3 | close during lookup and worker blocked in lookup: expected `RefusedException`; fence lost during lookup: policy question expected 0 was 1 |
| 3 before provider | 2 | close during PEP decision / during audit record: expected `RefusedException` (provider reached) |
| 4 after provider | 4 | interrupt, fence loss and close during provider, and worker blocked in provider: output not withheld (expected exception not thrown) |

Plus baseline `baselineTheLegacyPathIgnoresAClosedControl...` shows the unguarded path does reach the provider with a closed
control.

## Evidence (Windows, Maven 3.9.11 offline, PG on localhost:5432 via `DB_URL` defaults)

Corrected counts: `GovernedLlmClientTest` has 16 `@Test` methods, 1 native-Cedar (`theShippedDefaultsRefuseEverything`)
left enabled in source but not selected locally, so 15 run. The earlier "13 / total 33" figure came from a command that
did not cover the 2 new unit cases; it is superseded.

- Unit (runtime): `GovernedLlmClientTest` 15 (explicit source-derived list in `target/running-model-gpt-selection.txt`,
  no `#!` shortcut) + `PepTest` 17 + `ModelBoundaryArchitectureTest` 3 = 35.
- Integration (app): `GovernedModelRunningControlTest` 21 + `ResourceExecutionConstructionArchitectureTest` 7 = 28.
- Total 63, 0 failures/errors/skips (`target/running-model-claude-63.log`). Re-run here on JDK 25.0.2 (compiling
  `--release 21`) because my CLI could not resolve/run the supplied Java 21 path; this was not a Java 21 runtime run.
  GPT's independent run used the Java 21.0.12 runtime on this same shared filesystem. The native case is
  unverified locally; Linux CI must run it. Not a full-suite claim. No Quarkus boot, shared DB or native Windows suite;
  the test's own `oa_gm_run_<uuid>` DB is dropped.

## Remaining gates

GPT independent review; Linux CI (native Cedar case, full suite); real approved-context source/profile/lease and
task-to-call mapping (V78/V79); mandatory audit/output-fingerprint propagation; a reviewed composition root that mints
Running and calls this overload; `ResearchAgent` adoption.
