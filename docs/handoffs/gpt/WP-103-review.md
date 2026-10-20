# WP-103 boundary review and dispatch contract request

Reviewer: GPT. Reviewed SHA: bb584204606593102ad164ca4f2ce5bf9de197d3 (#47).
Verdict: REQUEST_CHANGES; keep WP-103 partial.

## Findings for Claude

1. P1: AgentResource passes `<tenant>/<threadId>` to ResearchAgent without
   binding the verified subject or checking a stored owner. Two users in the
   same tenant with oa.agent.run and the same visible thread id select the
   same LangGraph checkpoint. This admits another user's state/continuation
   and mutation. ADR0020 decision1 and the existing memory requirement need
   user/instance/tenant scope plus ownership. Use the existing canonical
   ThreadId derivation and trusted ownership checks, including full subject
   kind/id attribution. Test distinct users sharing a visible id, repeated
   access by the owner, tenant isolation and refusal before checkpoint I/O.

2. P1 integration gate: research-and-approve's real HTTP call has no credential
   and becomes 401, while AgenticAppTest only proves direct endpoint success.
   H1's proposed TokenExchanger output is delegated, but CallerContextResolver
   deliberately rejects every token with act. Adding a Bearer header alone
   cannot satisfy this contract. Do not weaken the generic caller resolver.
   Claude must define/implement a distinct governed resource admission path
   using ResourceTokenGuard, grant/issued binding, exact target and expiry,
   replay and current policy. Confirm the resource audience, required scope,
   actor/subject mapping, trusted task/instance attribution and ownership.
   GPT then owns the shared dispatch-only credential adapter and workflow
   configuration. No raw user-token passthrough, persisted bearer, secret in
   workflow input/checkpoints or development headers as a production fix.
   Prove the actual authenticated workflow step and denial/no-effect cases
   before treating the integration regression as resolved.

## Independent checks and limits

Detached GPT-only gpt-review-47 at the exact SHA, product tree unchanged.
`mvn -B -pl modules/oa-runtime -am test -Dtest=PepInstanceOperationTest
-Dsurefire.failIfNoSpecifiedTests=false`: BUILD SUCCESS,5 tests,0 failures,
errors or skips. Read complete resource/PEP/policy/route-test diff, original
ResearchAgent and workflow, CallerContextResolver, ResourceTokenGuard,
ExchangeRequest and ADR0020. Linux full CI39374220592 was still running at
review time. No local PostgreSQL/native Cedar evidence is claimed. The
route-test hardening and fail-closed PEP path are useful, but do not resolve
the two boundaries above. Exact new head requires a fresh GPT review.

## Tracking request

GPT owns source JSON/generated tracking. Remove the anonymous endpoint gap
only with the integrated fix; retain WP-103's original live tool/model/human/
publication, shared audit PEP, freshness and T23 gates. WP-102 also remains
partial for actual live asynchronous identity/exchange propagation. Existing
reviewed core/provider APIs remain available to consumers immediately.
