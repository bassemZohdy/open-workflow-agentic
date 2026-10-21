# WP-112 dashboard import/render validation and binary-status correction

Reviewer=GPT author verification. Baseline/source candidate
cc4d9481bbf9df8b8b4d2571584e7e03e153e2ea remains fixed on its original branch.
This separate GPT validation branch preserves that review head and main513b878/
membershipc9ce389/#55 ea56f33. No second implementation PR or package promotion.

## Actual baseline observation and finding

An isolated loopback-only Grafana13.2.4 OSS/Prometheus3.15.0 preview imported the
exact candidate dashboard. Only the required datasource UID input was bound;
all six titles/query expressions were compared with the retained source. Actual
Grafana datasource requests returned the expected values in healthy, initial
unknown, stale, exporter-down, controlled collector-loss and recovery stages.
This uses recorded synthetic CI metrics, not a shared database or deployed app.

Playwright rendered all six panels. Unknown and stale queue panels show No data
as required, but Grafana's default series palette draws observation health0 in
GREEN, with an automatic0..100 axis. A failed/unknown observation should not
visually look successful. This is a presentation finding, not a query, business
state or authorization bypass. The original screenshot remains evidence.

The correction explicitly maps binary0 to red/1 to green and fixes their axes
to0..1, with step interpolation and explicit gaps. The second title now says
`Last completed observation succeeded`; its description points to observation
age for freshness. A successful historical observation can still be stale; its
success flag does not certify a current queue. All six panels explicitly keep
null gaps. Metric names, query expressions, alert rules, Java/POM/app bytes and
authority/configuration defaults remain unchanged.

Local actual metric4,40 alert expectations/eight panel-value cases/NaN control
and six syntax checks pass. Corrected exact-source Grafana rendering and fresh
dedicated CI are PENDING at this initial publication; no baseline result is
transferred to the new colour configuration.

## Reproducible owned preview boundary

`tools/preview_observability.py` is a manual verification fixture, not a deployment
tool. It requires an exact clean source SHA, verified native tools under this
worktree's ignored target and an explicitly supplied sample-artifact directory.
It uses only loopback ports, a new UUID directory, its own SQLite/TSDB files and
its own subprocess handles. Deployment/cloud/GF/OTEL/proxy environment settings
are not inherited; no shared database, container or existing service is used.
The disposable Grafana permits anonymous Viewer access only on loopback and
uses random scratch admin/encryption values, never real user credentials.

Only datasource UID binding, private fixture target and one-second test scrape/
evaluation intervals differ from the reviewed example configuration. Metric
files are actual retained initial-unknown and application-HTTP CI samples; stale
simulation changes only observation age to200s. Exporter loss returns503;
collector loss terminates ONLY the owned Prometheus subprocess and requires all
six Grafana queries to report connection refusal. Recovery starts that same
owned component; no workflow or business state is reset. Final cleanup waits
for all owned child handles to become terminal and closes the fixture listener.

Executed native tools were verified against upstream archive hashes:

- [Grafana13.2.4 OSS Windows download](https://grafana.com/grafana/download/13.2.4?edition=oss&platform=windows):
  SHA2563e005ff50fe3bb6b8cb428ef127636fac4ee676bf9b6d819c6a2297d21f4bcbd.
- [Prometheus3.15.0 release](https://github.com/prometheus/prometheus/releases/tag/v3.15.0):
  Windows archive SHA2565d333b385557d9adc2ff015d13da9809baccc52fb800a82d1e5c94b79258f87e.

This is verification-tool version pinning, not a product upgrade recommendation.
The archive/binary/database/cache files stay ignored; only bounded proof is
published. Windows long-path extraction and a missing scratch SQLite directory
caused initial setup failures; no product execution success is claimed for them.
Their owned child processes were stopped. Empty optional plugin/alerting
provisioning directories are now created before startup. A stock anonymous
Grafana request to `/api/user/stars` returns401; this is retained as non-dashboard
console noise, not hidden by granting broader access. All six data queries and
rendered panels were independently verified.

Example after preparing verified tools metadata and the recorded sample archive:

```text
python tools/preview_observability.py --source-sha <exact-candidate>
  --tools-metadata target/dashboard-preview-tools.json
  --sample-root <actual-extracted-CI-metric-artifact>
# Open the printed local dashboard URL. Commands on stdin:
# initial, stale, exporter-down, collector-down, recover, stop.
```

The metadata identifies the extracted grafanaHome and verified Grafana/
Prometheus versions/archive hashes; prometheus.exe (or the platform binary) is
under target. This preview does not install an OS service or production receiver.

## Scope and remaining gates

Actual screenshots and sanitized query/cleanup evidence will be recorded at the
corrected exact source SHA after execution. Query data and screenshots use only
synthetic replay samples; a real deployed exporter/collector, production auth/
access, target inventory, approved routing/retention/load/query-timeout/SLI/SLO
and full correlated Flow/graph/tool/model/restart tracing remain open. Local
controlled collector loss is not production availability or an approved SLO.

Keep membership reviewc9ce389 first. Exact other-agent review, current-main/
single-PR reconciliation and current required CI still precede integration.
Original metrics candidatecc4d948 stays available; this correction must receive
its own exact review. WP112 remains partial, acceptance3/39 unchanged.
