# Proof-of-concept plan

Test ids refer to [test-cases.md](test-cases.md); requirement ids to [requirements](requirements/README.md).
Status of each acceptance test is tracked in [traceability.md](traceability.md).

| # | Proof | Acceptance tests | Status |
|---|---|---|---|
| 1 | Boot with Quarkus Flow, LangGraph4j and scheduler on one Postgres | smoke tests in `AgenticAppTest` (not acceptance tests) | done |
| 2 | OWS definition loaded as a `WorkflowDefinition` bean | smoke test | done |
| 3 | Agent graph with Postgres checkpoints, plan versions recorded, re-plan after tool failure | T34 | partial: graph runs and checkpoints; plan versions and re-plan missing |
| 4 | Start an instance, call the agent, wait for approval event, resume; inspect, cancel, retry tasks | T12, T16, T37 | todo |
| 5 | Restart or fail over mid-wait; duplicate and late events; compensation | T12, T13 | todo |
| 6 | Scheduled start through db-scheduler | T12 (due timer) | todo |
| 7 | Replace the HTTP bridge with an in-process step (ADR 0014) | T36, T04 | todo |
| 8 | MCP tools and knowledge access with permissions and citations | T05, T35 | todo |
| 9 | Supervisor with specialist agents, one through A2A | T36 | todo |
| 10 | Control UI with embedded assistant agent | T22, T37 | todo |
| 11 | Tracing, model and prompt versions, sampled quality evaluation | T04, T38 | todo |
| 12 | Model choice by configuration and gateway | T39 | todo |
