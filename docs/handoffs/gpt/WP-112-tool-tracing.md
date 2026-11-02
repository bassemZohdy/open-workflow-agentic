# WP-112: tool-call tracing

Trusted compositions can supply `ToolCallTelemetry` to the explicitly composed tool gateway. Its `oa.tool.call` span
contains only a fixed schema version and completed/failed outcome. It adds no tool name, arguments, response, caller,
credential or exception details. Completed means the enclosed callback returned; later authorization, validation or
audit checks may still withhold its result. An early refusal after span setup can produce a failed span without I/O.

For the legacy read-only worker, only the caller's span identity is copied into a fresh OpenTelemetry context. Baggage
and inherited thread-local values are not copied. Worker-side approval and budget checks and the start gate still run
after span setup. Controlled read-only and effect paths recheck their live execution and approval after span setup;
controlled effect spans enclose the existing ledger and dispatch spans. Tracing does not confer authority or guarantee
physical termination. A blocked telemetry implementation can still hold a worker or delay the caller.

The gateway and its tracing composition remain explicit, not production wiring. This does not establish workflow-to-tool
linkage across restarts, collector deployment, or full WP-112 completion. User-provided baggage and other instrumentation
outside this worker boundary need their own privacy controls.

The final worker implementation passed 39 focused runtime tests. A Linux/PostgreSQL reactor passed 27 tool-gateway
and 56 application tests, including tool-to-ledger span parentage; that reactor preceded the final worker-scope
failure-handling adjustment. A separate PostgreSQL test confirmed that losing control during trace setup starts no
effect. Neither component test establishes production wiring or cross-process trace continuity.
