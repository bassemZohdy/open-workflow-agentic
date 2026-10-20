# WP-103 audit API: second GPT review

reviewer=GPT; reviewed SHA=a191ef26b1c4bd541f269e8203ec17ddc7cfbdc8 (#50).
Compared with original review427dc7267ba823e429b4d287688ed03a7bc60ef1 and
stack base60c1c7f5ddee25c9085bf8eb815718d54994e8e5 in detached GPT-owned
gpt-review-50. Verdict: APPROVE the scoped audit/freshness/authority/policy
increment. Both material findings from6285396372 are resolved. This is not
permission to merge the stack before its parent/shared integration gates.

P1: all three audit resource entries retain CallerAuthority and refresh signed
expiry AFTER policy, mandatory audit and parsing, immediately before query or
verification I/O. The five actual-class freshness cases include expired401 with
zero connections and a positive connection-count control. Development headers
retain their explicitly configured test/dev seam; requireCurrent itself refuses
development/hand-built handles, including hand-built VERIFIED_TOKEN provenance.

P2: lazy Instance<Pep>.get failure is caught and mapped503 with zero query I/O.
Invalid-scope403 remains separate. No eager native engine initialization added.

The new transient CallerAuthority retains private verified claims, no bearer;
checks expiry/scopes and delegated exact grant/ancestors without a second replay
admission, maps dependency failure to refusal, then samples expiry again after
the blocking grant check. The delegated factory is an in-process trusted-producer
interface, not token verification by construction. Consumers still establish
target audience, exact registry full owner and current execution ownership.
RESOLVE_EFFECT uses operation:resolve-effect with oa.recover and stays outside
the safe-recovery allow list; no manual endpoint is activated by this increment.

Independent verification:

- Selected Maven reactor tests: AuditAccessTest14, AuditResourceFreshnessTest5,
  CallerContextResolverTest15, PepInstanceOperationTest5 and the seven
  non-database CallerAuthorityTest methods. BUILD SUCCESS,46 tests, zero
  failures/errors/skips. Command: mvn -B -pl modules/oa-runtime -am test with
  -Dtest selecting those classes and the seven user/scope/dev/resolver/kind/
  unavailable/slow-recheck authority methods, and
  -Dsurefire.failIfNoSpecifiedTests=false.
- Initial broader selection52 cases had46 passes and SIX environment errors:
  four delegated SQL cases cannot connect to localhost5432; two Cedar cases
  cannot load the Windows native library. Zero assertion failures/skips. These
  are not local SQL/native passes.
- mvn -B -pl apps/app-runtime -am test -DskipTests: BUILD SUCCESS; all application
  test sources compile. This does not execute application tests.
- python tools/render_requirements.py --check; python -m unittest discover
  -s tools -p test_*.py:68 pass; git diff --check60c1c7f..HEAD clean. Tracked
  detached review tree unchanged; logs/probes only in ignored target/.
- Inspected exact-head full Linux CI39385783072 SUCCESS: CallerAuthorityTest11,
  ResolveEffectPolicyTest2 using real Cedar and AuditResourceFreshnessTest5 all
  pass with zero failures/errors/skips. Provider/process39385783028 native lane
  SUCCESS at review; standard lane still running. Final integration requires
  both current lanes successful and a fresh final-head check.

The overlapping authority/policy changes in #52 are included in this exact #50
review; this does not transfer approval to an arbitrary retargeted #52 head.
Keep one Claude implementation PR and consolidate the redundant stack safely.
Parent #47's trusted resource-call backend/actual workflow401 gate remains GPT
work. Merge parent only after those gates, then retarget/reverify/review the
final #50 SHA. Audit remains operator-wide (WP-404 tenant filtering open), not
universal PEP/whole WP-103/WP-102 closure. Central effect audit idempotency and
lost-commit-reply delivery remain the explicit producer handoff, not implemented.

#51 exact7cbb4c978e1a1482e21aad29843c73485a237e90 has full39384993920 and BOTH
real-provider/process39384993946 SUCCESS. Logs confirm interruption6, actual
PostgreSQL DurableEffectsTest16, process recovery1 in each provider lane and
strict evidence checks. Exact Claude review is still required; no merge claimed.

The separate pushed GPT binding-store preparation reserves V73 after V70/V71/V72
and merged #49 types. No new implementation PR or dispatch activation exists.
