# Effect tracing contract and evidence

Owner: GPT. Reviewer: Claude. Standalone WP-108/WP-112 increment, not a DAP
baseline or whole-package closure. Built on main34b6a52 plus the separate
EffectScope/V70 preparation; no extra implementation PR while #48 is active.

## Signals and scope

| Signal | Contract / evidence | Remaining boundary |
| --- | --- | --- |
| Effect spans | Four fixed names: oa.effect.execute, compensate, dispatch, lookup. Logical spans are INTERNAL; target calls are CLIENT. Current OTel context supplies the parent, and the scope is closed before return/throw. | Actual Flow/agent/tool/model/human paths, async handoff, restart linkage and outbound adapter propagation remain open. |
| Attributes | Custom oa.effect schema1: only oa.effect.schema=1 and oa.effect.outcome (intended, confirmed, failed, compensated, unknown, busy, error). No request, reply, target, key, tenant, subject, payload, bearer, exception message or stack is exported by this instrumentation. | This is a custom schema, not a claim of complete OTel business semantic conventions. Other producers need their own privacy review. |
| Outcomes | Failed/unresolved/refused spans have ERROR with a fixed safe status description. Confirmed/compensated spans retain UNSET. Tracing does not change ledger state or turn an unknown result into failure. | Traces are sampled operational evidence, never consumption proof, policy authority or central mandatory audit. |
| Metrics/logs | Existing fixed effect outcome counter and safe component logs remain. No new high-cardinality metric label is added. | RED dashboards, measured SLOs, structured cross-runtime log correlation and deployed alerting are not delivered here. |

Mapping: WP-108 compensation/reconciliation diagnostics; WP-112 and M16
correlated operational inspection. No measured latency/availability SLO or
trace retention approval is invented. Existing T03 remains process-death
business-effect evidence, not proof that a span flushes before a hard kill.

## Runtime and privacy budget

Quarkus3.39.5's existing BOM manages the extension and OpenTelemetry1.62.0;
no framework version is upgraded. Service name is
open-workflow-agentic-runtime. OTel supplies trace/span identity; deployments
own environment/version resource attribution and collector access controls.
The custom instrumentation version is1.0. The semantic schema above is pinned
to1; changes require review and a versioned contract.

SDK disabled by default, so the default runtime creates no external exporter
traffic. To opt in at process startup, set QUARKUS_OTEL_SDK_DISABLED=false and
QUARKUS_OTEL_EXPORTER_OTLP_TRACES_ENDPOINT to the approved collector; configure
transport/TLS through the deployment's OTel settings. The cdi exporter and
parentbased_traceidratio sampler are build settings; the default sampling arg
is0.1 and the operator may set QUARKUS_OTEL_TRACES_SAMPLER_ARG=1.0 for an
approved diagnostic run. Batch queue512, export batch128 and timeout2s bound
the exporter workload. Overflow/export outage drops optional telemetry;
it never releases business execution that a required ledger/audit write held.

Automatic HTTP/database tracing stays disabled by this profile. LangChain4j
prompt/completion/tool argument/result capture is explicitly false, so adding
the extension does not enable content capture. The effect span attributes do
not copy baggage or arbitrary untrusted metadata. No trace identity is used
for tenant, caller, target or lease authority. Sampling and queue bounds do
not impose a storage-retention policy: before external export, the deployment
must choose collector/backend limits, retention, access and any redaction for
other instrumentation. Those operational deployment gates remain open.

## Failure semantics and verification

EffectTelemetry wraps each call exactly once. Begin/attribute/end failures are
isolated from business work; scopes restore the prior context. Business
exceptions retain their original identity and are never put into span events.
The mandatory ledger and central audit keep their own fail-closed contracts;
optional tracing cannot substitute for them. Hard VM failures are outside
this in-process exception guarantee.

Five plain cases execute the real OTel SDK/exporter or deliberately faulty
Tracer/Span implementations: parent/child/context and exact safe attributes;
unknown/error sanitization; failed span creation with no duplicate work;
attribute/end failure with context restored; null return preserved. Local
`mvn -B -pl modules/oa-runtime -am test -Dtest=EffectTelemetryTest
-Dsurefire.failIfNoSpecifiedTests=false` passes5/5, zero skips.

Three new PostgreSQL/Quarkus cases: real ledger execute/refund trace
shape, attempted-intent lookup without redispatch, and the actual CDI service
using the extension plus an isolated test-only SpanExporter alternative. They
use explicit external-target doubles; no external service or whole Flow trace
is claimed. Exact producte2dc636 workflow39376019493 SUCCESS BOTH lanes;
complete logs independently inspected: EffectTracingTest3 (including actual
CDI extension/exporter wiring), EffectTelemetryTest5, V70 guard5, original
DurableEffectsTest12 and standard3/native8, zero failures/errors/skips and
strict provider evidence checks pass. These three SQL/CDI cases are now
executed, not compile-only. No external production collector, whole Flow/
distributed trace or hard-kill span flush follows from that component result.
Local application reactor test-compile passes. Full reactor package with
tests skipped passes (build/CDI augmentation only, never test evidence);
dependency/license policy passes. Final implementation PR full CI and exact
Claude review remain required, including process-kill T03 (not selected by
this provider matrix). V70's prior independent run was not transferred to
the new code; the new trace cases executed in their own current-product run.

## Review, operations and remaining gates

Claude reviews the exact final head; GPT integrates only after required CI
and resolved findings. GPT owns the future collector/export/dashboard/runbook
integration; Claude supplies spans at its owned policy/agent/model boundaries.
Runbook verification remains planned: enable an approved collector, run a
governed effect and lookup/refund, verify a common trace and safe attributes,
disconnect the collector and verify durable business outcomes and bounded
export backlog. No alert is deployed or measured threshold claimed here.

Authority/manual/central-audit requests in WP-108-authority.md still apply.
Actual governed adapter/Flow recovery, all later ownership checks, manual
decisions, central audit projection and complete distributed traces remain
open acceptance gates. WP-108/WP-112 and M06/M07/M16 remain partial.

Sources inspected: the pinned local BOM/config metadata and the official
[Quarkus tracing guide](https://quarkus.io/guides/opentelemetry-tracing/),
[configuration reference](https://quarkus.io/guides/opentelemetry/) and
[OpenTelemetry Java instrumentation](https://opentelemetry.io/docs/languages/java/instrumentation/).
