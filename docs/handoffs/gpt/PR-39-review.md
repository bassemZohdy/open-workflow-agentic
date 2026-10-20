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

