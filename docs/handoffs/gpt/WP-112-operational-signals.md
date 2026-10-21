# WP-112 truthful outbox signals and operational dashboard proposal

Reviewer=GPT author verification. Baseline main513b878; current product SHA and
actual CI evidence will be recorded after commit/execution. Local four metric
cases and pinned upstream promtool rule evaluation pass; final checker/application
compile/combined Linux regressions remain pending. Verdict PROVISIONAL, not peer
approval, deployment or package completion.

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

Initial producer/monitoring product383cb81 has dedicated39528905998 and
BOTH39528905982 running at its actual SHA. Runtime/test/POM bytes are unchanged
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
checks pass locally. Fresh exact dedicated verification of this query revision
remains required. This does not introduce an approved SLO or paging policy.
