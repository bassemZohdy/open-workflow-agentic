# Architecture

```
            +--------------------------- Quarkus application ---------------------------+
 client --> | REST / control API                                                         |
            |   |                                                                        |
            |   v                                                                        |
            | Quarkus Flow  (OWS YAML: sequence, switch, listen, timers, retries)        |
            |   | call: http / function                                                  |
            |   v                                                                        |
            | LangGraph4j agent graphs  --> LangChain4j ChatModel / tools / memory       |
            |                                                                            |
            | db-scheduler (time triggers)                                               |
            +--------------------------------+-------------------------------------------+
                                             |
                                       PostgreSQL
                    (workflow state, agent checkpoints, scheduled_tasks)
```

## Responsibilities

| Plane | Where |
|---|---|
| Development (author definitions) | `src/main/resources/flow/*.yaml`, agent graphs in `agent/` |
| Control (start, observe, approve, pause) | REST endpoints in `api/` (UI and assistant agent are planned) |
| Execution (durable run) | Quarkus Flow instances, LangGraph4j runs under a thread id |

## Design rules

1. Process shape lives in OWS YAML, so it stays portable and readable.
2. Agent loops live in LangGraph4j graphs, never inside workflow tasks.
3. The only coupling is a narrow contract (HTTP body in, JSON out) so either side can be replaced.
4. Models are reached only through the `LlmClient` port, so tests need no network.
5. One database, one runtime, one build.
