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
and six syntax checks pass. Corrected rendering at86e1cb9 and its dedicated
CI39531606323 passed; artifact inspection and final evidence publication follow
the process-family correction below. Series colour represents the latest value,
not the status of every historical point; binary history retains its numeric
step values. Unknown age can render as an empty curve rather than literal text.

## Preview process-family finding

The original controller stopped direct child handles but left26 Grafana plugin
workers from two preview runs. Therefore its `allOwnedChildrenStopped` field is
NOT proof of complete cleanup. GPT identified only executables in this session's
new private Grafana tool directory, rechecked each PID/path/creation time/parent,
stopped those26 workers and verified none remained there. No shared service or
other session's process was stopped. The guarded cleanup record remains ignored
at target/preview-orphan-cleanup.json; this finding is not hidden as a clean pass.

The corrected Windows fixture uses a separate unnamed private Job Object per
component. It creates the native target suspended, assigns its actual process
handle and then resumes its primary thread. No breakaway
flags are set. Kill-on-close is read back from the kernel; normal shutdown also
terminates and waits for zero job members. This follows Microsoft's
[Job Object lifecycle](https://learn.microsoft.com/en-us/windows/win32/procthread/job-objects).
There are no global process-name/PID kills in the published driver. Two actual
Python and native PowerShell checks each cover live/exited leaders, a surviving
grandchild and an unrelated sentinel (four cases). Local checks pass; final
exact-source native Grafana and Windows CI proof are recorded below. Initial handshake testing exposed and
fixed Windows CRLF handling; that failed attempt is not a passing result.
The intermediate becc6d4 launcher revision passed Python-only local/Windows CI
checks but actual Grafana inspection found its native descendants outside the
job. That revision is superseded, not accepted cleanup proof. Only its own15
remaining private native processes were identity-checked and stopped. The final
suspended-target implementation and stronger native cases address that gap.
At e80c5a9 the actual17-member family included Grafana/all13 plugins, Prometheus
and their console hosts; every native Grafana process was independently checked
against job membership. All six backend stages passed and shutdown left zero
native processes. The controller then exposed an exit-notification race: an
empty job can precede a signalled process handle, and a redundant termination
returned access-denied. It now waits for the already-requested exit and closes
native handles explicitly. That earlier controller exit1 is not a clean-run
pass; the final exact-source cleanup proof is recorded below.

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
for all private job members and direct handles to become terminal and closes
the fixture listener. This helper currently requires Windows; Linux CI compiles
its syntax, and a separate Windows job executes the native process checks.

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

Actual screenshots and sanitized query/cleanup evidence are retained under
[evidence/WP-112-dashboard](evidence/WP-112-dashboard). Query data and screenshots use only
synthetic replay samples; a real deployed exporter/collector, production auth/
access, target inventory, approved routing/retention/load/query-timeout/SLI/SLO
and full correlated Flow/graph/tool/model/restart tracing remain open. Local
controlled collector loss is not production availability or an approved SLO.

Keep membership reviewc9ce389 first. Exact other-agent review, current-main/
single-PR reconciliation and current required CI still precede integration.
Original metrics candidatecc4d948 stays available; this correction must receive
its own exact review. WP112 remains partial, acceptance3/39 unchanged.

## Finished author verification (reviewer=GPT)

Reviewed product **5312221ee7f781914de8aadb53169db63026b343**; verdict **PASS for
scoped local rendering/process ownership and component CI; HOLD for peer/current
PR/deployment and complete WP112**. Final evidence/tracking changes are documentation
only. No approval or run SHA is transferred to a later documentation head.

- `python tools/preview_observability.py --source-sha 5312221ee7f781914de8aadb53169db63026b343 --tools-metadata target/dashboard-preview-tools.json --sample-root ../gpt-wp112-signals/target/obs-current-s`:
  clean exact-source native Grafana13.2.4/Prometheus3.15.0, all six proxy-query
  stages PASS. Unknown/stale queue samples are absent; exporter-down gives up0;
  collector-down requires all six actual connection-refused errors with Grafana
  itself still healthy; recovery restores the original five synthetic values.
  Controller exits0, all three native handles exit0,17 job members become zero,
  all component jobs empty, independent private-native inventory0 and all three
  loopback listeners closed. See [sanitized result](evidence/WP-112-dashboard/native-preview.json).
- `python tools/check_preview_job_windows.py`: four physical local cases PASS;
  both native PowerShell and Python cover live/exited parents, stopped descendants
  and an unrelated sentinel that survives. Fresh exact-source Windows
  [39533627266](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39533627266),
  job119005061725 also PASS. Actual archive12316621136,573bytes,
  SHA2560cf427f349b631494a4d682d45f38b96c2421bb59a0177603ede54d1d37e9673;
  report source5312221/clean worktree/four cases/canonical source hashes checked.
- Same exact run's Linux signals job119005061968 PASS: actual XML has all four
  exact metric methods, zero failures/errors/skips; promtool checks40 alert
  expectations/eight panel values/NaN/six query syntax. Actual archive12316805587,
  14804bytes,SHA256484a12c1463e7229f66489274a910bda04754b2691bb0c32910f20c2e425e8f5.
  Source/producer/sample hashes match actual5312221 Git blobs. Report and exact
  archive verification are [retained](evidence/WP-112-dashboard/ci-verification.json).
- Earlier UI source86e1cb9/run39531606323 and native sourcee80c5a9/run39533486453
  actual Linux archives were independently inspected too. Their component checks
  pass under their original SHAs; neither certifies final native cleanup.
- `python -m unittest discover -s tools -p 'test_*.py'`:76 pass.
  `python tools/render_requirements.py --check`, Python compilation and
  `git diff --check`:pass. Originalcc4d948 Java/POM/app/rules/query bytes unchanged;
  its earlier real PostgreSQL/HTTP/BOTH proof stays in the operational-signals handoff.

Actual browser images retain their own execution SHA/hash in
[screenshots.json](evidence/WP-112-dashboard/screenshots.json). Dashboard bytes
are identical at86e1cb9/e80c5a9/5312221. GPT visually inspected all images;
earlier Grafana scroll-container viewports can crop lower panels. The recovery
image shows all six panels. These are replayed CI samples, not deployed users.

| Case | Retained browser image |
|---|---|
| Original default green health0 | [baseline](evidence/WP-112-dashboard/baseline-initial.png) |
| Healthy | [corrected](evidence/WP-112-dashboard/healthy.png) |
| Initial unknown; red0 and queue No data | [corrected](evidence/WP-112-dashboard/initial.png) |
| Stale200s; queue No data | [corrected](evidence/WP-112-dashboard/stale.png) |
| Exporter unavailable; red up0 | [corrected](evidence/WP-112-dashboard/exporter-down.png) |
| Collector unavailable; six panel errors | [corrected](evidence/WP-112-dashboard/collector-down.png) |
| Recovered six panels | [corrected](evidence/WP-112-dashboard/recovered.png) |

Integration priority remains c9ce389/#55, then the existing WP108 shared
fingerprint/schema/current execution/audit handoffs. No further observability or
conformance preparation is authorized by this record. Claude must review the
final correction head before its later single-PR/current-main integration.
