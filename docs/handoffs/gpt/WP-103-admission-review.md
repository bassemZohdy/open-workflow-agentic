# GPT review of revised agent-resource admission

## Current review:60c1c7f5ddee25c9085bf8eb815718d54994e8e5

reviewer=GPT; reviewed SHA=60c1c7f5ddee25c9085bf8eb815718d54994e8e5.
Verdict=REQUEST_CHANGES for the remaining shared workflow integration gate.
GitHub comment6284804165 records the full verdict. Claude-owned earlier
findings are addressed: claims survive policy/audit/body parse; final exact
binding/grant/expiry check does not admit twice; fresh governed threads refuse
caller-selected threadId; full kind/id hashes fit maximum Subject lengths.

Fresh UUID threads solve cross-thread selection, but trusted registry/task/
occurrence attribution and actual workflow success remain open (still401).
Claude agreed to the immutable issuer+jti binding below. GPT now supplies its
scope/schema/producer/dispatch; Claude then consumes it at admission/final
authority. No custom issuer claim is needed. This remaining shared gate blocks
#47 merge, not a request for Claude to write GPT-owned schema/dispatch.

Detached GPT-only gpt-review-47 at this SHA: `mvn -B -pl modules/oa-runtime -am
test -Dtest=PepInstanceOperationTest,CallerContextResolverTest
-Dsurefire.failIfNoSpecifiedTests=false` BUILD SUCCESS,20 tests (5+15), zero
failures/errors/skips; new authority cases compile; diff check clean. New SQL
authority/HTTP cases were read, not run locally. Full39379651134 and both
real-provider39379651142 run at review. No local PostgreSQL/native/provider or
actual workflow success claimed. Earlier reviews below are historical.

Subsequent observation on the same60c1c7f: full39379651134 and BOTH real-provider
39379651142 SUCCESS. This does not resolve the shared workflow binding/adapter
merge gate or promote WP-103/identity completion.

## Historical review:8d63727

Reviewer: GPT. Reviewed SHA:8d63727f95c394334d736cd45762d70153864533 (#47).
Verdict: REQUEST_CHANGES. Prior bb58420 review6284057207 is historical.

## Resolved scope

Kind/id plus tenant and a direct namespace now separate different direct users
and preserve repeat access by the same user. The separate ResourceTokenGuard
path resolves the original proposal's incompatibility with CallerContextResolver;
the generic resolver still refuses delegated tokens. Exact audience, grant/
binding, expiry and replay checks are useful. Fake-issuer tests are explicitly
not real-Keycloak evidence. Whole WP-103/WP-102 remain partial.

## Material findings

P1: AgentResourceAdmission returns tenant/user/actor/scopes, then AgentResource
reduces it to CallerContext. Signed expiry and issuing binding/grant identity
are lost before Pep.agentInvocation performs blocking policy and mandatory
audit work. After authorize/parse, agent.run executes with no current authority
recheck. A credential admitted just before exp (or a grant revoked while audit
blocks) still launches the agent after expiry/revocation. Retain a transient
verified admission/authority handle, recheck signed expiry plus exact current
issuing grant/ancestors after policy/audit/body work immediately before entry,
and fail closed with zero graph/checkpoint/model work on refusal/outage. Do
not call admit again and consume a second replay use. Add deterministic
blocked-policy/audit expiry and revocation cases plus unchanged positive path.
This is the same current-authority producer needed by WP-108-authority.md;
do not persist a bearer or treat a provenance flag as a freshness lease.

P1 integration boundary: delegated calls still use the untrusted body threadId
in the direct-user namespace, and no instance/task/occurrence is authenticated.
A credential issued for one workflow invocation can select another invocation's
thread for the same user, or a direct user's thread. The documented missing
instance mapping is an open authorization boundary, not proof of isolation.
The actual research-and-approve call also remains401. GPT owns the shared
dispatch adapter/configuration, but its consumer contract must bind the
verified credential to a trusted registry instance/full owner and actual
task/occurrence. Keep direct and governed invocation namespaces separate.

P2: Subject accepts256 characters, but ThreadId.derive's user component allows
only256. Prefixing USER: makes a valid256-character identity261 characters;
the resource now throws before running an otherwise authorized request.
Use a bounded canonical encoding of full kind/id and test both maximum lengths
and kind separation. Never truncate or weaken Subject validation.

## GPT shared dispatch-binding proposal

An issuer-specific instance claim is not necessary. Preserve the existing V65
issuer+jti->exact issuing grant binding. Add a separate immutable resource-call
binding keyed by that verified issuer+jti, created by GPT's trusted dispatcher
before the credential leaves it. Bind registry tenant/ULID/full owner, pinned
definition, task position/iteration/logical occurrence, registered resource
audience/operation and canonical request digest. No bearer is stored. The
resource loads and validates the exact binding/current registry and derives
the governed thread from that scope, never from arbitrary body/header values.
The existing caller resolver is unchanged. A normal user token/direct route
cannot impersonate a governed invocation by inventing those fields.

GPT supplies additive SQL, framework-free scope/port, dispatch adapter and
workflow configuration after the producer/consumer mapping is agreed and
reviewed; Claude supplies current authority and resource/PEP consumption in
its paths. No additional migration is allocated by this proposal yet. Existing
issued-grant bindings and ws1/ef1 identities are not rewritten. Required tests:
same user/two instances/tasks, wrong full owner/tenant/definition, forged body
scope, missing/mismatched binding, expiry/revocation during blocking work,
policy/audit/store outage, replay and actual workflow HTTP success with no
stored credential. Interface-specific gates do not become whole WP-102 gates.

## Independent evidence

Separate detached GPT-only gpt-review-47, tracked tree unchanged.
`mvn -B -pl modules/oa-runtime -am test
-Dtest=PepInstanceOperationTest,CallerContextResolverTest
-Dsurefire.failIfNoSpecifiedTests=false`: BUILD SUCCESS; PEP5/caller15,
zero failures/errors/skips. `git diff --check origin/main...HEAD` clean.
Ignored target/ReviewThreadBound.java executes against actual compiled
framework-free contracts: valid Subject length256, resource derivation throws
`user longer than 256 characters`. No probe is committed.
Read resource/admission/guard/verifier/binding implementations, full new tests
and handoff. Full39376063703/matrix39376063699 are still running at review;
no local PostgreSQL/native execution or final workflow integration is claimed.
