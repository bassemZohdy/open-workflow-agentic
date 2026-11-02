# WP-109: fault polling during cancellation

The SDK can report a timeout-shaped fault when a timed task is cancelled, and its cancellation event can arrive later.
The observer already ignores that particular cancellation-induced failure, but a reconciler polling the bare FAULTED
status had no cause and could move a CANCEL_REQUESTED registry row to irreversible FAILED before the cancellation
report was recorded.

`EngineStatusSync` now leaves a CANCEL_REQUESTED row unchanged for a cause-free polled FAULTED status. A genuine
observed failure still produces FAILED; a polled FAULTED on a row without a cancellation request still produces
FAILED. A later cancellation report can complete the cancellation. The registry regression uses a fake engine with a
real PostgreSQL registry; it demonstrates the ordering risk, not that the live SDK was observed in that exact polling
window. The previously characterized SDK timeout behavior and observer tests are separate evidence.

The selected local reactor passed 13 observer/mapping tests and 42 PostgreSQL reconciler tests. The packaged
`LifecycleRecoveryIT#cancellationReportIsRecoveredAfterTheRegistryIsUnavailable` also passed: it uses the proposed
pending-cancellation schema and test-only reader, then demonstrates local retry after an injected registry failure.
That process test does not prove recovery after losing the reconciler that buffered the report.

This deliberately trades availability for correct classification: if no cancellation or genuine fault event is
available, a polled fault alone will leave the cancellation unresolved. The local cancellation buffer is still bounded
and volatile, and process-loss recovery, buffer exhaustion, and production cross-replica cancellation evidence remain
open. No T12 or WP-109 completion is claimed.
