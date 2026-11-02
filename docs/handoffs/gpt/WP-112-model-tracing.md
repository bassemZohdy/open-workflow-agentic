# WP-112: governed model tracing

Governed model-call attempts now create an optional `oa.model.call` span under the current OpenTelemetry context. The
custom span records only schema version and a fixed completed/failed outcome; it adds no prompt, response, caller,
model name, credential, or exception text. A completed span records completion of the enclosed provider attempt; a
later approval check can still withhold its result. A failed span may also reflect a pre-call refusal after span setup.
Denied policy decisions do not enter this span or call the provider.

The normal, controlled and reserved call paths check their approval and execution fences after tracing setup and
before provider I/O, then perform their existing post-call checks after tracing finishes. Tracing failures do not retry
or replace business work. Research graph nodes carry the OpenTelemetry context captured for that run across their
executor; the wrapper restores the worker's prior context. This context is not identity or authorization, and this
instrumentation does not constrain what other instrumentation or user-provided baggage records.

The final local runtime reactor passed 50 tests, including the actual LangGraph executor context test, the governed
model client and gateway, privacy checks and effect tracing. A separate application reactor exercises the controlled
and reserved paths and effect tracing against PostgreSQL; it was started before the graph-context wrapper was added.

These changes do not provide production collector deployment, trace linkage across process restart or the complete
workflow/graph/tool/model correlation required by WP-112. The default model approval source still refuses execution.
