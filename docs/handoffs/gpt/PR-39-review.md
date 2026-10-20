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
