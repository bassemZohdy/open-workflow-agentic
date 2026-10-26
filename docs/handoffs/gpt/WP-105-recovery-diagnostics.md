# WP-105 / issue75: distinguish actual engine state and event delivery failure

Baseline main0856d92; owner GPT, reviewer Claude. This is diagnostic evidence only,
not a fix or a completion claim for the unresolved packaged identity recovery failure.
Claude read-only intake is retained locally in target/identity-recovery-next-investigation.txt
on the integration checkout; it proposes hypotheses, not an established original cause.

An epoch1 authority log proves the effect handler was entered, not its response or
listen registration. Registry WAITING alone cannot distinguish a live engine listener
from an absent engine whose terminal report was not recorded. Existing outbox state
is not on this direct dev-probe event path.

The dev/test-only resource adds GET /dev/lifecycle/engine with definition, version,
instanceId. Disabled probes return404 before any dependency/request access. Enabled
snapshots report the actual engine status, ABSENT (unknown outcome, never inferred
COMPLETED), or503 UNAVAILABLE without exception text. They never prepare/start/cancel,
repair a row, read authority or issue permission. Existing deployment prohibition on
agentic.dev.lifecycle-probes=true remains in force.

Dev publish still returns202 immediately. It now observes asynchronous failure and
logs only the generated event id and exception class, never payload, request-selected
event type, exception message or credential. It does not await, retry, catch synchronous
publisher failure or change delivery/identity semantics.

Actual Java21 focused reactor test passes5 DevLifecycleResourceTest cases, zero
failures/errors/skips: disabled behavior, all actual engine statuses, absent/unknown,
invalid/unavailable refusal, and real WorkflowApplication with deferred failing event
publisher. The last case proves202 before completion and failure logging without
private canary/type text. Required Linux CI and exact Claude review remain pending.

## Claude-owned consumer request after merge

Enhance IdentityRecoveryIT timeout diagnostics only: include a bounded safe snapshot
for the already-known pinned lifecycle-probe engine id. Keep the original90-second
completion condition, authority/secret assertions, broadcast event types and event
order. Diagnostic transport failure must not replace the original test failure;
use a short independent request timeout and a non-sensitive unavailable marker.
Do not claim issue75 fixed. Independently investigate real listener failure/restore
thread and terminal-report-loss hypotheses only with deterministic proof, never by
assuming absent means completed or changing the probe to hide shared-listener races.
