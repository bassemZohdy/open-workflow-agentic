# WP-112: HTTP effect trace context

The explicit pinned HTTP effect target sends the current W3C `traceparent` on both effect dispatch and read-only
lookup, when there is a valid current span. It does not forward OpenTelemetry baggage or vendor `tracestate`. The
existing `X-Correlation-ID` is still sent separately; it is not an authentication credential and is not the same
identifier as the W3C trace ID. Optional tracing failures are ignored, and the target rechecks its authority after
header preparation before it sends anything.

Loopback tests check the exact trace header on dispatch and lookup, no trace header without a current span, omission
of baggage and vendor state, and refusal before network I/O when the second authority check fails. A separate
PostgreSQL ledger test checks that the service receives the actual `oa.effect.dispatch` child span's identity. The
transport and credentials in these tests are locally composed; this does not establish production adapter wiring,
cross-restart continuity, or complete workflow-to-model-to-tool correlation.

The final local Linux reactor passed 14 transport tests and 12 database-backed effect and tracing tests. An earlier
test assumed sampled trace flags were always `01`; the SDK emitted another valid flag as well. The final test compares
the service header to the actual exported dispatch span's complete flags.
