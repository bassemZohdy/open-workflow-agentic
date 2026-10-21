# Outbox operational observations (WP-112)

This increment supports M16 with truthful outbox observations and a dashboard/
warning proposal. It does not establish a deployed monitoring service, a user
availability/latency SLO, full correlated Flow/graph/tool/model tracing or complete
WP-112. GPT owns these artifacts; `owner=runtime-operations` is a proposed warning
routing label, not an installed receiver or approved human on-call assignment.

## Signal contract v1

| Prometheus metric | Meaning |
| --- | --- |
| `agentic_outbox_unpublished` | Last successfully observed unpublished rows, including failed rows |
| `agentic_outbox_failed` | Last successfully observed retained poison rows |
| `agentic_outbox_unpublished_oldest_age_seconds` | Oldest unpublished age at that observation, zero for a successfully observed empty queue |
| `agentic_outbox_observation_healthy` | Latest completed database observation succeeded (1), failed or never succeeded (0) |
| `agentic_outbox_observation_age_seconds` | Monotonic elapsed seconds since last successful observation; NaN before the first success |

Before any successful observation the three lag gauges are NaN. A failed read
retains the last successful lag values, marks them unhealthy and lets observation
age increase. It does not claim an empty queue. A successful read restores health
and resets age. A process restart begins unknown; freshness is not durable state.

All five meters have no application labels: no tenant, subject, task, instance,
effect key, payload, credential or exception message. Prometheus adds controlled
job/target `instance` labels. Budget is five application series per scrape target,
plus Prometheus infrastructure signals; deployments must bound their target list
and access. The example collector keeps only these five application metrics.
These are custom Micrometer names/version1, not new OpenTelemetry semantic
convention attributes; existing effect span schema1 and its tracing defaults are
unchanged. Private queue contents are never exported here.

The one-second cache bounds completed read-attempt frequency, including failures.
Concurrent gauges do not start duplicate reads; they may see the prior immutable
observation while one read is in flight. This does not bound JDBC query duration,
make a whole scrape an atomic database snapshot, or certify current business
delivery. A blocked first query can still block its owning scrape; the external
exporter/up and stale signals address different observation failures. Query/
connection timeout and production load budgets still need deployment evidence.

## Collection and dashboard proposal

`deploy/observability/prometheus.example.json` is valid Prometheus configuration
(JSON is a YAML subset). It targets an example local runtime at127.0.0.1:8080 on
`/q/metrics`, every15s, job `open-workflow-agentic`. Change the approved private
target/security/retention settings for a real deployment. No service is started
by this repository change. Protect these operational counts with the deployment's
network/access controls; no collection endpoint is made public by this increment.

Import `outbox-dashboard.json` into a separately governed Grafana instance and
select its approved Prometheus datasource. The six time-series panels show up,
observation health/age and three lag values. Lag panels require a reachable target
AND a healthy observation no older than the candidate120s freshness window.
Missing/failed/stale telemetry remains missing, not a green
zero. Multiple replicas can report the SAME database-wide outbox: display by
target, never sum replica observations into a fictional queue total. This also
does not prove isolation across independently configured databases/tenants.

Promtool validates all six substituted query expressions. Actual Grafana import,
rendering, datasource permissions, deployed scrape/export loss, workload scale
and operator access are separate open rollout checks.

## Candidate warning policy and runbooks

The five warning delays below are engineering proposals for evaluation. They are
not an approved SLO, error budget, incident response time or paging policy. A
deployment owner must approve thresholds/routing/retention and exercise the
runbooks before enabling notifications. Queue age/count thresholds are deliberately
not fabricated without a delivery/recovery objective.

<a id="exporter-unavailable"></a>
### Exporter unavailable

`up=0` for2m. Check target discovery, private connectivity and process health;
separate a bad scrape path/auth configuration from an application outage. Inspect
readiness/checkpoint health through the authorized operations path. Do not reset
ledger/checkpoint state or restart from a workflow's first step. A target removed
from discovery has no `up` series: this rule cannot replace external monitoring
of target inventory or the monitoring service itself.

<a id="observation-missing"></a>
### Observation missing

Reachable `up=1` but no health series for2m. Check deployed version, registry,
metric filtering and scrape configuration. A missing observation is not proof
of zero queued rows. The dashboard does not fill it with zero.

<a id="observation-unavailable"></a>
### Observation unavailable

Reachable target, observation health0 for2m. Check authorized database availability,
pool exhaustion, schema/access and backend error class. Retained lag values are
historical. Recover storage/access first and verify a fresh successful sample;
do not delete/requeue rows merely because the observer failed.

<a id="observation-stale"></a>
### Observation stale

Last successful observation older than120s for1m while target remains reachable
and the last completed read was successful. Completed read failures use the
observation-unavailable warning instead of an additional stale warning.
Check a blocked/slow observation and scrape timing. Cached success is not fresh
success. Read-attempt caching does not cancel an executing database operation.
This threshold is an example, not an inferred maximum recovery time.

<a id="poison-rows"></a>
### Poison rows

A healthy observation within the candidate120s freshness window reports failed
rows for5m. Use authorized failure
inspection to identify the fixed error class and cause, preserve original rows,
repair the cause, then use the reviewed requeue operation if authorized. Relays
are at-least-once: verify consumer idempotency. A failed observer suppresses this
historical count warning in favor of observation failure; it does not clear rows.

## Verification and remaining gates

Run the four actual OutboxMetricsTest methods, then:

```text
python tools/check_observability.py --promtool <verified-promtool3.15.0>
```

The checker requires actual non-skipped method XML, evaluates eight rule scenarios
against ALL five firing/non-firing alerts, evaluates the poison-panel values in
all eight healthy/unhealthy/stale/missing/recovery scenarios, tests NaN comparison
behavior and checks all dashboard query syntax. The real Micrometer Prometheus registry test covers
the exact initial-failure NaN/export/recovery names; promtool's input fixture
format does not accept literal NaN samples, so the rule fixture separately checks
unavailable initial observation and evaluates `vector(0/0) > 0` to an empty vector.

The manual/PR `observability-contract` workflow verifies the upstream release
archive digest before using promtool3.15.0. Upstream release metadata observed
2026-10-21 is https://github.com/prometheus/prometheus/releases/tag/v3.15.0;
Linux archive SHA2562a542df32eac02ee17b9d844fb2aa1de00dafa5476579ba8a3ba862e9d572ea0,
Windows archive SHA2565d333b385557d9adc2ff015d13da9809baccc52fb800a82d1e5c94b79258f87e.
This is verification tooling, not a deployed Prometheus upgrade or recommendation.

Actual artifact results belong in the GPT handoff at their exact tested SHA.
The [dashboard render handoff](handoffs/gpt/WP-112-dashboard-render.md) records
actual isolated Grafana/Prometheus import, six synthetic failure/recovery states
and Windows process-family cleanup, with original screenshots and exact-source
CI evidence. Binary health uses red0/green1 and fixed0..1 axes; series colour is
the most recent value, while step history retains numeric status. Unknown age
can appear as an empty curve. Those local replay checks do not certify a deployed
receiver, private production access or alert delivery.
Until peer/current PR review and rollout checks pass, all of this is preparation.
Full structured logs/correlated asynchronous/restart/outbound traces, approved
SLIs/SLOs, collector/sampling/redaction/retention and production dashboard/alert
verification remain open. No package or acceptance status is promoted.
