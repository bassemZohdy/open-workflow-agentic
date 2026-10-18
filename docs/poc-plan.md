# Proof-of-concept plan

Test ids refer to the requirement set of the agentic-process-modernization report (T01 to T39).

| # | Proof | Report tests | Status |
|---|---|---|---|
| 1 | Boot with Flow + LangGraph4j + scheduler on one Postgres | T12 to T16 | done |
| 2 | OWS definition loaded as `WorkflowDefinition` | T12 | done |
| 3 | Agent graph with Postgres checkpoints | T34 to T36 | done (single run) |
| 4 | Start a workflow instance, call the agent, wait for approval event, resume | T22, T37 | todo |
| 5 | Restart mid-wait and resume from Postgres | T13 to T15 | todo |
| 6 | Scheduled start through db-scheduler | T16 | todo |
| 7 | Replace HTTP bridge with an in-process step (own the graph-to-workflow wiring) | cohesion 4 vs 5 | todo |
| 8 | MCP tools and knowledge access for the agent | T34 to T36 | todo |
| 9 | Multi-agent graph and agent-to-agent calls | T37 to T38 | todo |
| 10 | Control UI with embedded assistant agent | T38 to T39 | todo |
| 11 | Tracing, token cost and quality metrics | T39 | todo |
