# GPT review of stacked audit PEP increment

reviewer=GPT; reviewed SHA=427dc7267ba823e429b4d287688ed03a7bc60ef1 (#50).
Base60c1c7f5ddee25c9085bf8eb815718d54994e8e5 (#47). Verdict REQUEST_CHANGES.
Full GitHub review comment6285396372. Stacked parent must merge first; then
retarget, rerun affected checks and review the final #50 head.

P1: AuditResource resolves the bearer into CallerContext, then the new default
PEP path performs policy/mandatory-audit I/O, then query/verifier work starts.
Signed expiry was discarded and is not refreshed. An ignored deterministic
probe through the actual resolver/resource/access/PEP classes, with explicit
verified-claims/clock/audit/query doubles, advances past exp during the audit
and observes query getConnection attempted once. Retain a transient verified
caller handle and revalidate after all blocking policy/audit/parse work before
each read/verify. A blocked-audit expiry case should return401 with zero query/
verifier I/O. This is the particular ordinary-caller freshness producer requested
by WP-108, not whole WP-102 closure or persisted bearer state.

P2: AuditAccess calls pep.get outside its unavailable-decision try/catch.
A failing lazy DI producer therefore escapes as a runtime error and the common
mapper gives500 instead of the stated503. A probe through the public CDI
constructor with an explicit failing Instance<Pep> reproduces it. Cover lazy
acquisition failure with503 and zero reads, separately from invalid scopes403.
The handoff's known native TLS initialization limit makes this a relevant
dependency-failure path; no build/fork configuration is changed on speculation.

Independent detached GPT-only gpt-review-50, tracked tree unchanged:
`mvn -B -pl modules/oa-runtime -am test
-Dtest=AuditAccessTest,PepInstanceOperationTest,CallerContextResolverTest
-Dsurefire.failIfNoSpecifiedTests=false` BUILD SUCCESS,34 tests
(audit14/PEP5/caller15), zero failures/errors/skips. Ignored Java probe compiled
against the actual generated reactor test classpath reports:
`lazy DI failure: escaped=IllegalStateException, mapped status=500`;
`after blocked audit: signed expiry passed=true, query attempts=1`.
No actual JWT/signature, native Cedar, HTTP transport or PostgreSQL is claimed
by that probe. Delta diff check clean. Full39383362196 subsequently SUCCESS;
green CI does not resolve these two findings.

Scoped audited ALLOW/DENY/ERROR and separate read/verify scopes are useful.
Operator-wide audit access and retained test-PDP/dev-open precedence are stated
limits; no tenant isolation, universal PEP bypass proof or T23 completion is
inferred. WP-103 remains partial. #47's shared binding/actual workflow gate
remains open; #49 was merged as main30e81c3 after exact411dc36 Claude approval
6285298422 and full39380912923/BOTH39380912505 SUCCESS. GPT still supplies
production binding storage/dispatch; no real workflow success is claimed yet.
