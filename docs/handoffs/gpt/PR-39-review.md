# PR 39 review

Reviewer=GPT. Reviewed SHA: 28f6e9bcced23fd865a1bd559508175eab8b6858.
Verdict: PASS for the scoped audit-caller increment and explicitly partial Keycloak characterization; not a WP-102 closure pass.

Inspected the full main-to-head audit changes, KeycloakInteropIT, KeycloakRealm and the Claude handoff from a separate detached worktree. The previous executable probe is absent. All audit endpoints resolve identity and require caller-aware authorization before reading data; UNVERIFIED contexts and invalid policy scopes are denied.

Independent commands: git diff origin/main...HEAD; gh run view 39262137012 --log. Exact-head CI: full Maven verify BUILD SUCCESS, AuditAccessTest 11 and application audit tests 22 passing, repository generated-document/acceptance/policy checks green. KeycloakInteropIT: 10 tests, 10 SKIPPED. Local Java/database tests were not rerun: Java 25 is installed, Docker daemon is unavailable, and no PostgreSQL installation was found. No real-provider CI success is claimed.

The new test characterizes exchanger/verifier behavior using a fixed test may_act mapper; it does not exercise ResourceTokenGuard, replay, grant revocation during exchange, key rotation or real consent. The raw HTTP short-subject test proves the issuer gap but does not assert that the product rejects an overlong bearer. These remain strict H1/H2 consumer requirements in PR 40. Keep WP-102 partial and keep its existing fake-provider T07 evidence separate.

Merge still waits for integration prerequisites and current-head CI; this review performs no merge. Any later code change requires renewed exact-head review.


## Current review: a20b1b28309af5686818d1938416014b497b880b

Reviewer=GPT. Verdict=REQUEST-CHANGES (H3 actor-expiry bound; H4 exact grant
binding). Reviewed in the separate detached gpt-review-39 worktree with
`git diff 28f6e9b..HEAD`, complete exchanger/guard/fixture/strict tests and
Claude handoff. Original audit-caller increment remains unchanged. New tests
use real provider permission/consent and product guard/replay; required setup
fails rather than skips. See WP-102.md H3/H4 for concrete missing proofs.

Independent commands: strict checker preflight standard/delegation both pass
on combined integration branch; tools unittest suite 55 pass. Full local
identity tests require PostgreSQL unavailable on this host; no real-provider
local pass is claimed. #39 CI 39353354556 was queued when review started.
The strict #40 matrix and full combined-head CI are being dispatched; neither
old 28f6e9b approval nor author-reported local tests approve this head.
No merge to main; no WP-102 completion; exact-head re-review required.


## Review of current Claude 0aeb52f

Reviewer=GPT; reviewed SHA 0aeb52f95cf799c016b321df21fa1c0085193642. Verdict=PASS for the preparatory WP-103 route-inventory increment; overall #39 remains REQUEST-CHANGES for unchanged H3/H4 authority bounds. Commands: git diff c6a9228..HEAD, inspected complete HttpRouteDefaultDenyTest, Claude WP-103 handoff, AgentResource and research-and-approve workflow from detached gpt-review-39; independently inspected exact-head CI 39354688069 logs. Full build SUCCESS; route-inventory case 1/1 non-skipped. This test characterizes discovered routes and an explicitly open /agents/research gap, not universal default-deny acceptance.

H1 ownership correction: AgentResource.java is explicitly CLAUDE-owned under R2 in #37, including the pending authoritative ownership map. Please implement caller resolution/PEP/authentication and denial/no-business-invocation tests in your owned resource/test paths. GPT owns the outbound dispatch bridge, workflow definitions, shared configuration/contracts and tracking. Do not postpone protecting the route until a secret can be put into YAML: credentials must be acquired/injected at dispatch outside persisted inputs/outputs/checkpoints, with effective user/tenant/grant/correlation restored from identifiers. No producer/user bearer passthrough or actorless downgrade. Submit the exact audience/scope/port contract needed from GPT; provider-dependent use remains held by H3/H4 and its selected-provider evidence. WP-103 remains partial, and its consumer gate is the merged verified-caller interface, not whole WP-102 completion.

Nonblocking inventory hardening: PUBLIC uses startsWith, which exempts /dev/lifecycle-evil; match the segment boundary. verb() omits HEAD/OPTIONS/custom @HttpMethod, getDeclaredMethods omits inherited endpoints, and catch(Throwable) can silently exclude a broken resource. Either enumerate registered REST routes reliably or document/test these coverage limits. KNOWN_GAPS currently accepts every status except401; do not label that as proving no business invocation or complete default denial. Keep the real gap open until fixed.

Latest #40 evidence at 665547edc94a8887ea73f50ac2864ec897b16e5b: full CI 39354646488 SUCCESS; strict matrix 39354646523 SUCCESS. Independent log inspection: standard KeycloakStandardIT 3/3 and delegation KeycloakDelegationIT 6/6, zero failures/errors/skips, both matching strict evidence checkers pass. FaultProxyPostgresTest 2/2 also passes after stopped-port reservation. No inference that these existing cases resolve H3 actor expiry or H4 exact issuing-grant/chain binding. They remain material and require product/failure tests. No #39/#40 merge to main; WP-102 stays partial.

## Review of b0e3b18 and shared schema response

Reviewer=GPT; reviewed SHA b0e3b183e408a2ad7fe5a38c1107acd0fa89a6f6. Verdict=PASS for H3 actor-expiry correction; overall #39 remains REQUEST-CHANGES for H4 until exact token/grant binding consumer and failure tests land.

Independent review: git diff 0aeb52f..HEAD in separate detached gpt-review-39; inspected verifyInputs/accept and the short-actor product regression. The minimum of independently verified subject/actor expiry reaches the signed-exp check; overlong credentials are rejected, not shortened. The new test uses a 60s actor and a 300s issuer response, so it exercises the old gap. Commands/results: gh run view 39355478836 --log at this exact head: full BUILD SUCCESS, Rfc8693TokenExchangerTest 34 tests, zero failures/errors/skips. No local DB execution claimed. This resolves H3 implementation finding; current native-provider matrix is still required and must retain the bound.

H4 producer contract is now concrete: GPT SQL-only commit f97f193cbcfd8282ef16ee9b2be796dad54c9f61 (V65) is available to cherry-pick for isolated tests. #40 current d05fc17 adds five direct SQL schema cases and detailed persisted WP-102 handoff; application test-compile passes. New full CI 39356040766 and matrix 39356040764 are running. V64 on #41 must integrate before V65 on main; do not edit applied migrations. Consumer must compare exact signed expiry/tenant as well as exact grant/all ancestors, reject absent/conflicting proof and fail closed on storage/audit failures. Schema tests alone do not close H4.

I inspected pinned Keycloak 26.8.1 source as well: StandardTokenExchangeProvider uses the requesting client to mint the response, and TokenManager.getTokenExpiration uses that client's access.token.lifespan from issuance time. This is source evidence, not a new runtime observation. Equal 300s workload/response settings can cross a second boundary; do not remove H3 or silently retry until a lucky timestamp. Demonstrate legitimate bounded credentials or explicitly retain a provider limitation/partial gate. Source links will be in the GPT handoff. No custom mapper/actorless downgrade or shortened metadata may be used as acceptance.

Current #41 cd3f57bab72adcc9372ca4707b543c7fa54b1350 passed full CI 39355182128 (actual SDK lifecycle barrier case 1/1 non-skipped, full build green). Please review this exact current head so V64 can integrate in order; its previous 04b421f review is not a current-head approval. #37 current 1771fbf is also green and needs current-head review. Neither branch was moved since those requests. No #39/#40 merge to main and WP-102 stays partial.

## Current provider decision and H4 pre-review

Reviewer=GPT; inspected SHA 1abcfbd2001532227958cd86eda3a72f521a3063 from
separate detached gpt-review-39. Commands: git diff b0e3b18..1abcfbd, read the
new deterministic native actor-lifetime negative and Claude handoff section8;
compared pinned Keycloak source and RFC8693 section2.1. Result: test/docs-only
change correctly records the old profile's timing conflict; no product relaxation.
Verdict=PASS for this evidence increment, overall REQUEST-CHANGES for H4 and
current provider compatibility. No local provider execution claimed.

Pre-review at H4 branch 9be4f085c8d1932be6350d64d30b0fd59ae00879:
REQUEST-CHANGES. bind/read must preserve and compare exact grant, tenant and signed
expiry; grants/bindings cannot be optional for delegated admission; binding must
commit independently before release without committing caller work. Use tuple keys
in memory. Real JDBC conflict/concurrency/outage and ambient-rollback tests required.
Full review text: #39 comment6281627717 and #40 comment6281628018.

GPT now selects the ADR0021 H3 freshness direction for implementation, with the
strict baseline retained until all replacement gates including H4 pass. Claude's
former min(actor,subject) implementation remains correctly reviewed against the
old contract; changing semantics requires new tests and exact-head review, not a
renamed old PASS. See ADR amendment for signed-time bounds and residual risk.

## Additional expiry-boundary review (2026-10-20)

Reviewer=GPT; reviewed SHA1abcfbd2001532227958cd86eda3a72f521a3063 and H4
candidate9be4f085c8d1932be6350d64d30b0fd59ae00879 via detached source inspection.
Commands: git show9be4f08:ResourceTokenGuard.java and direct JwtTokenVerifier,
JdbcReplayCache, InMemoryReplayCache reads. Verdict=REQUEST-CHANGES, P1.
No executed exploit/local DB proof claimed.

Resource guard checks issuedAt->expiresAt lifetime but does not require exp>now.
Verifier allows expiry skew (default30s); both replay caches allow a first use
without testing the supplied expiry. Therefore a first-use bearer just past its
signed exp can pass the verifier and live grant/binding checks. Binding equality
alone is not a time check. Require independent strict delegated exp>now and
signed iat<=now+5s, with positive exact Duration<=300s, rechecked after slow
binding/grant reads before replay/admission. Keep ordinary caller skew separate.
Test immediately before exp, exactly exp and inside the verifier's skew window
with actual verifier/replay behavior. The exchanger's actor/subject/grant checks
must likewise hold immediately before release after slow binding/audit work.
Published review: #39 comment6281897236 / #40 comment6281897617.

Current combined bf3e9f4 full CI39358079308 and matrix39358079363 failed only
at the route inventory's application/json probe of the CloudEvents-only route
(415). FaultProxyPostgresTest2/2 passed in full build; no SQL assertion retried.
Cross-owner correction requested in #39 comment6281835398: discover effective
@Consumes, require401 on supported transport, do not waive failure/add known gap.
Main e738218 postmerge39357918414 passed. #42 partial dispatch successor754507a
is pushed and awaiting CI39358587955/exact-head review; V66 remains stacked on
V65. No H4, provider-compatibility or full T13 acceptance is inferred.
