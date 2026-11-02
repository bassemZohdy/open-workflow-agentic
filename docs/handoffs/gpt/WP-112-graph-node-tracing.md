# WP-112: research graph node spans

The research graph can now emit `oa.agent.node` spans under the run's current OpenTelemetry context. The custom span
adds only a fixed schema version and completed/failed outcome; it does not add node names, graph state, caller identity,
prompt, output or exception details. Model spans created within a node are its children. A completed node span means
the wrapped action returned; the subsequent mandatory deadline, budget and audit checks can still withhold the result.

The existing node interceptor checks the deadline and durable budget before tracing, again after span setup and before
the node action, and again after span completion before releasing state. Tests deliberately move the clock during span
setup and completion to verify refusal before work or withholding after work. Tracing is optional and its exceptions
do not substitute a result or retry the node; a blocking tracing implementation can still delay execution.

The PostgreSQL graph test uses a fixture approval and model provider and verifies parentage for all three real
checkpointed research nodes without exporting their private topic. It does not prove production model approval,
workflow-to-graph propagation, tool execution in this graph, trace continuity after restart, or collector deployment.
The selected Linux/PostgreSQL runtime suite passed 65 tests, and the application CDI/startup suite passed six tests.
