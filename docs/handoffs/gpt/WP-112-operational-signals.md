# WP-112 truthful outbox signals and operational dashboard proposal

Reviewer=GPT author verification. Baseline main513b878. Reviewed current runtime/
application product b14722e15c2c0cb52f9d2676c777ff4d97967c0d. Final monitoring query/
checker producte0b8676 has IDENTICAL metric producer/unit-test/runtime-POM/
monitoring-asset/checker bytes; the application HTTP case is new inb14722e.
Verdict author-verification PASS for these bounded signals/query/application
checks; exact peer/current PR/deployment/SLO/package gates remain HOLD.

## Why this is the next independent increment

WP-112 consumes the merged WP-105 lifecycle/outbox interface, the explicitly
accepted partial baseline. Its metrics already exist on main; it does not need
pending #55 or guessed V73..V80/agent execution APIs. #55 ea56f33 and correction
c9ce389/main513b878 stay fixed for Claude's exact review. No second implementation
PR is opened. GPT owns outbox/configuration/monitoring/test/tracking paths; no
Claude policy, identity, audit, agent or test fixture is edited.

## Finding, changes and tradeoff

Existing OutboxMetrics retains the last lag after a backend failure, initially
zero, without a freshness/health signal; failed attempts are not cached and each
gauge can retry the read. Monitoring could misread an unavailable database as an
empty or healthy queue. This is a telemetry truth gap, not permission to change
outbox business state.

Five unlabeled custom v1 metrics now include observation health and monotonic
age. Legacy lag values are unknown/NaN before the first success; later failures
retain their original values but explicitly become unhealthy/stale. Failure and
success attempts share the one-second cache and one in-flight read. Concurrent
scrapes read the preceding immutable observation instead of starting more work.
No background worker, mutation, credential or business recovery is introduced.
One read's JDBC duration remains unbounded here; cache frequency is not a query
timeout or atomic whole-scrape guarantee.

This intentionally changes pre-first-success unavailable lag from fabricated0
to NaN; downstream consumers must preserve unknown rather than coerce it to0.
Normal successful lag values and names retain their meaning. A bounded boolean
health signal plus age was chosen over replacing failed samples with zero or
adding tenant/task/error-message labels. Only error class is logged. See the
runbook for cardinality/privacy, missing telemetry, recovery and rollout limits.

## Deliverables and verification scope

- Runtime OutboxMetrics with four actual cache/failure/concurrency/real Prometheus
  registry cases, including exact exported names and no private labels.
- Actual application HTTP scrape at `/q/metrics` and real PostgreSQL observation,
  with five exact unlabeled finite signals/healthy1 and nonnegative counts and
  observation age. Oldest-row age retains the existing wall-clock SQL meaning.
  Only the bounded
  outbox lines are retained, not unrelated application/route/tenant metrics.
  This actual boot case passes BOTH final Linux/real-provider profiles, as recorded below.
- Shared test-only BOM-managed micrometer-registry-prometheus dependency, already
  used by the deployable; no production dependency/provider/version upgrade.
- Example collector config, six-panel Grafana JSON and five candidate warning
  rules. Eight scenarios evaluate every firing AND non-firing alert. Healthy lag
  queries guard observation health, up and candidate120s freshness; replica
  observations are never summed. The actual poison-panel expression is evaluated
  in all eight states, in addition to all five alerts and a NaN expression control.
- Checker requires actual four-case XML, pinned promtool3.15.0, evaluated rule
  scenarios and six syntactically valid dashboard queries; artifacts contain
  source hashes and the exact CI product SHA.
- `docs/operations-observability.md` maps M16/signal v1, five series per target,
  proposed owner/runbooks, candidate warning delays and open rollout/SLO gates.

The initial local test compilation used try-with-resources on MeterRegistry;
the pinned API has close() without AutoCloseable. Fixtures were corrected to
explicit finally-close and all four execute successfully. Promtool's fixture
parser rejects literal NaNx10 samples: the final tests separately exercise
initial observation absence, actual registry NaN output and a NaN comparison
expression. Neither initial diagnostic failure is counted as product execution.

## Remaining acceptance and review gates

Other-agent review of the exact final documented candidate, current-main/single
PR reconciliation and current required CI precede integration. Grafana actual
import/rendering, production collector/export-loss/target inventory, operator
access, approved routing/retention/SLIs/SLOs and load/timeout tests remain open.
The proposal's warning delays/owner label are not an installed paging policy.
No actual user-facing SLO or full correlated Flow/graph/tool/model/restart traces
are proved; WP-112 remains partial. Claude's owned tracing hooks still require
an explicit handoff before that later integration. No acceptance promotion.

Use the existing priority for reviews: membershipc9ce389 first, then the shared
fingerprint454ad4c and checkpoint72da48e; this independent preparation does not
move those heads. WP-605's producer capability check is already verified as
preparation and remains gated on its own consumer/peer/current integration.

## Current operator-query refinement

At query-refinement publication, initial product383cb81 dedicated39528905998/
BOTH39528905982 were running under their actual SHA; finished evidence is below.
Runtime/test/POM bytes are unchanged
by this later query-only refinement. Actual source comparison will be recorded
with the completed runs, without relabeling their SHAs.

Review found that a stalled read can leave the latest completed observation
healthy but old. The final queue panels and poison warning also require the
candidate120s freshness window; stale values do not appear as current queue
counts. Stale and completed-failure warnings are separated. The same eight
scenarios now execute the actual poison-panel expression and include a prolonged
failed observation retaining poison counts and a stale formerly successful
observation retaining poison counts. Both suppress the queue view and poison
warning in favor of the correct observation warning. All40 alert expectations,
eight query-value expectations, the separate NaN expression and six query syntax
checks passed locally at publication. Finished exact dedicated verification is
recorded below. This does not introduce an approved SLO or paging policy.

## Final current execution and actual artifacts

Reviewer=GPT; reviewed runtime/application product **b14722e15c2c0cb52f9d2676c777ff4d97967c0d**;
scoped author verdict PASS, other-agent/current PR/rollout HOLD. Exact
[BOTH39529291379](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39529291379)
SUCCESS attempt1: standard118991225551/delegation118991225234. Both finished
complete logs and ALL149 actual XML suites EACH independently inspected,
1056/1061 unique nonzero consistent cases: contracts183/runtime393/testkit196/
app280 plus Failsafe4/9, zero failures/errors/skips. Exact metric4/HTTP scrape1/
inspector24/governed12/rule3/physical-provider1/real standard3/delegation8/core1
source-method sets match. Both current strict provider/effect-recovery checker
reruns pass; actual initial/recovery AND HTTP metric bytes pass pinned promtool
metric validation. The HTTP case uses actual Quarkus/PostgreSQL, no override,
returns healthy1 and five exact unlabeled signals at the configured scrape path.

Final query/checker **e0b8676aec04a32a30aa82c81c48f83b747ef5f0**,
[dedicated39529071270](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39529071270)
SUCCESS job118990525157. Actual one-suite/four-case XML, exact product SHA,
workingTreeDirty=false, producer/asset/sample hashes verified. All eight rule
scenarios check all five firing/non-firing alerts (40 expectations); actual
poison-panel values in all eight states plus separate NaN expression (nine
query-value evaluations) and all six query syntax checks pass. The upstream
Linux promtool archive digest and actual3.15.0 version are verified in CI.
Those metric producer/unit-test/runtime-POM/monitoring asset/checker bytes are IDENTICAL in current
b14722e; this statement does not relabel the dedicated run's actual SHA or claim
that it ran the separately added HTTP test. The latter has its own current
BOTH proof above. Only documentation follows the tested product in this record.

Historical initial **383cb81fc51d2e5d459b909b960e7168fcd0d65d** dedicated39528905998
and BOTH39528905982 also SUCCESS attempt1. Both complete provider logs/ALL148
XML EACH inspected (1055/1060 cases, app279, same other modules/FS). Initial four
metric methods, raw initial/recovery samples and inherited provider/core methods
pass; dedicated one-suite4/eight rule scenarios/six query syntax controls are
verified. It did NOT include final freshness-masked dashboard values or the
actual application HTTP scrape. No historical proof is transferred to those
later changes.

| Actual run/profile artifact | ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| initial dedicated383 / observability-contract-results |12314391767|14788|32dcbb8dee20330746806cdeb2117eeace3b35d3de74953f7173ed5663ec6942|
| initial383 / keycloak-delegation-contract-java-results |12314876826|1876871|4d96b25de00892c1a88b6a280f90060244d0151f327176056679e29e9bb27598|
| initial383 / keycloak-standard-contract-java-results |12314232438|1878715|102450e3921d2c7f82f0495ade17f475680c3cd0807bea76be18af26fe8080e8|
| final querye0 / observability-contract-results |12315225586|14804|a2e26c3ee688670fdccdab95ad944e6071d797f3e9282b48cca51eb90a17a0bb|
| currentb147 / keycloak-standard-contract-java-results |12314333190|1895493|9bdc523339a69b52efb644d3d17d5f8ebd3630787f3bb89e0db6bcb2d6d9ea5b|
| currentb147 / keycloak-delegation-contract-java-results |12314103395|1889919|589608cf5598d937640311091f4007bcb72d71a31af7d20f19df3d96a25c3cc0|

ALL archive bytes match current GitHub metadata/digests; CRC, bounded extraction
and safe paths independently checked. Finished raw logs, actual XML/methods,
actual metric files and report/source hashes were inspected, not inferred solely
from a green workflow. The full provider archives do not evaluate monitoring
rules; that proof belongs to the dedicated run and its retained source hashes.

Author commands/results:

```text
mvn -B -pl modules/oa-runtime -am test -Dtest=OutboxMetricsTest
  -Dsurefire.failIfNoSpecifiedTests=false
  PASS: four actual cases locally and dedicated CI.
mvn -B -pl apps/app-runtime -am package -DskipTests
mvn -B -pl apps/app-runtime -am test-compile -DskipTests
mvn -B verify -DskipTests
  PASS: compilation/JVM augmentation/all-module dependency lists, not local SQL/native execution.
python tools/check_dependency_policy.py
python -m unittest discover -s tools -p 'test_*.py'
python tools/render_requirements.py --check
git diff --check
  PASS: actual dependency policy,76 tooling cases, source-generated tracking and whitespace.
python tools/check_observability.py --promtool <verified promtool3.15.0>
  PASS: actual four-case XML,8 rule scenarios/40 alerts,9 value evaluations,6 queries,
  actual registry sample validation; no Grafana rendering or deployment claim.
# Actual BOTH CI: mvn -B -pl apps/app-runtime -am verify with required
# KeycloakStandardIT / KeycloakDelegationIT plus EffectCrashRecoveryIT.
# Both archived XML strict profile + --effect-recovery checker reruns PASS.
```

The sole implementation PR remains #55 ea56f33. Main513b878 and its membership
correctionc9ce389 are unchanged pending exact Claude review6305069697. Request
exact final documented-candidate review after that priority, then current-main
reconciliation/single PR/current required CI before integration. Other prepared
fingerprint454ad4c/checkpoint72da48e/human7981714 requests remain unchanged.
No business-state/migration/current authority/endpoint permission is opened by
metrics; actual Grafana import/rendering/collector-loss/private access/approved
operational routing/retention/load/query-timeout/SLI/SLO/full correlated tracing
and package gates remain open. Candidate warning thresholds are not a deployed
paging policy or approved recovery objective. WP112 remains partial.
