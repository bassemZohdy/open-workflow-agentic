# WP-102 live identity check (synchronous identity HALF)

Base `561c691` (V76 PR 74 merged). Branch `claude/WP-102-live-identity-check`. Not pushed; no PR. WP-102 remains partial.

## What this is, and is not

`LiveIdentityCheck` is the synchronous IDENTITY HALF of a future live check. It is not the composite fence. It is not a CDI
bean and nothing activates it: a producer constructs it with the one real `AgentResourceAdmission` that minted the
`BoundCall`. It knows nothing of the registry, lease, task, approved target, PDP or audit. It grants no callback,
fresh-commit or graph permission. It returns nothing and caches nothing (no `decisionFresh`), and it is not atomic across the
independent databases it reads. A future bounded composite check wraps it and adds those parts.

## Behaviour

`requireLive(BoundCall, ResourceExecutionEntry expected, Subject actingWorkload)` runs on the calling thread (no worker,
queue, ThreadLocal or context registry). The work is `AgentResourceAdmission.requireLiveIdentity` (package-private), which
uses the private verified token inside the bound call. Public `Principal`/`VerifiedToken` are data and cannot create a check.
Each call:

1. refuses if the interrupt flag is set (the flag is never cleared);
2. requires the witness open (per-call and bean close, same minter, signed expiry) and the exact full retained entry
   (scope and epoch); a missing expected workload refuses;
3. re-reads the exact issuer+jti resource-call binding and the grant binding, then, after that blocking lookup, rechecks
   interrupt, open state and signed expiry before using the answer; the call binding must equal the admitted one and the
   entry scope, and agree with token tenant/owner/audience/operation/times and the grant binding (BINDING_MISSING /
   BINDING_MISMATCH / VERIFIER_UNAVAILABLE);
4. revalidates the exact current issuing grant with the real guard (`ResourceTokenGuard.revalidateGrant`, package-private,
   same checks as `revalidate`: ancestors, tenant, user, actor chain, scopes, signed time before and after), then rechecks
   interrupt/open/expiry again, and requires the grant id to equal the call binding and the grant's typed `Subject` agent
   (kind and id) to equal the expected workload.

`ResourceTokenGuard.checkGrant` now returns the matched grant; public `admit`/`revalidate` behaviour is unchanged.
`AgentResourceAdmission` only had the binding-agreement comparison extracted (same checks). The expected workload is a required
explicit SERVER-TRUSTED argument; nothing is inferred from token, grant, request or owner, and no mapping or shared field
was invented (the scope/`AgentTaskContract` carry none). Callers must invoke the check again after PDP, audit or any I/O.

Refusals: `TokenRejectedException` for credential/binding/grant/store, `WitnessRefusedException` for closed handle, wrong
minter/entry/epoch, missing or different workload, and interrupt.

## Tests

`LiveIdentityCheckTest` (11 cases): real guard and real `DelegationGrants` on a private PostgreSQL database (created and
dropped by the test), controlled slow lookup. Covered: pass for the correct workload, repeatable; revocation during the
call-binding lookup refuses after it (data still readable, still refused); signed expiry during that lookup and during the
grant-binding work; revoked ancestor; binding removed, retargeted to another scope, to another grant, issued time/expiry
mismatch (issuedAt +1s keeps the lifetime valid; expiry -1s), grant-binding changed or missing; unavailable store; same id with wrong kind, other kind/id, user, null workload;
wrong epoch, wrong scope, other minter, per-call close (sibling unaffected), bean close, close during lookup; interrupt before
and during the lookup with the flag preserved. No reflection or method-list tests.

Commands (JDK 21.0.12.1, Maven 3.9.11, PG16 localhost:5432):

`mvn.cmd -B -o -pl modules/oa-runtime -am test -Dtest=LiveIdentityCheckTest,AdmittedCallWitnessTest,AgentResourceBindingAdmissionTest,AgentResourceAuthorityTest,AgentResourceUserFreshnessTest,CallerAuthorityTest -Dsurefire.failIfNoSpecifiedTests=false`

Result: 53 run (LiveIdentityCheck 11, AdmittedCallWitness 18, BindingAdmission 6, Authority 4, UserFreshness 3,
CallerAuthority 11), 0 failures/errors/skips, BUILD SUCCESS. No full, native or process suites were run and no shared fixture
was reset; CI has not run on this branch.

## Open gates (all still required)

- Composite bounded check wrapping this half: actual registry instance, lease, task, approved target, PDP and central audit,
  re-invoked after each blocking step; plus the GPT lifecycle gate (`JdbcResourceExecutionGate`) integration.
- Server-trusted workload mapping: the producer must supply the expected workload from a trusted source; none exists yet.
- Dedicated credentials, private async context, and the real producer/consumer that mints and binds `BoundCall`.
- No model/legacy admission activation, no default `ApprovedModelContext`, no RunnableConfig metadata/state/checkpoint
  handle propagation; handles must never enter graph state, config or checkpoints.
- Real-Keycloak evidence and the original intermittent recovery item remain open; exact GPT review and CI of this head.
