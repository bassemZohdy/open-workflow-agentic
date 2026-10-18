# open-workflow-agentic

An agentic process platform on **one Quarkus runtime**:

| Responsibility | Component |
|---|---|
| Process definition, durable execution, waits, timers | **Quarkus Flow** (CNCF Serverless Workflow / OpenWorkflow Spec 1.0 YAML) + `quarkus-flow-jpa` |
| Agent reasoning loop (plan, act, review, human interrupts) | **LangGraph4j** (embedded, Postgres checkpoints) |
| Models, tools, memory, guardrails, observability | **Quarkus LangChain4j** |
| Scheduling | **db-scheduler** Quarkiverse extension |
| State | one PostgreSQL database |

Option 1 of the technology evaluation: Quarkus Flow + LangGraph4j, Java only, no Python.
The workflow decides *when and under what control* an agent runs. The agent decides *how to do the work*.

## Quick start

Requires Java 21, Maven 3.9 and PostgreSQL 16.

```bash
docker compose up -d                 # Postgres on :5432 (db "agentic", user/password postgres)
export OPENAI_API_KEY=...            # or point LLM_BASE_URL at any OpenAI-compatible gateway
mvn quarkus:dev
curl -s localhost:8080/agents/research -H 'content-type: application/json' \
     -d '{"topic":"agentic process modernization"}'
```

Tests run without a model (a stub replaces it) but need Postgres on `localhost:5432/agentic`:

```bash
mvn verify
```

## Layout

```
src/main/resources/flow/                 OWS workflow definitions (loaded by Quarkus Flow)
src/main/java/.../agent/                 LangGraph4j graphs, model port
src/main/java/.../api/                   REST endpoints (agent step, later: control plane)
src/main/resources/db/migration/         Flyway (scheduler table)
docs/                                    architecture, proof-of-concept plan
```

## What works today (verified by `mvn test`)

- Application boots with Quarkus Flow, LangGraph4j, db-scheduler and Postgres together.
- The OWS definition `agentic/research-and-approve` is discovered and registered as a `WorkflowDefinition` bean.
- The research agent graph runs end to end with Postgres checkpoints (`POST /agents/research`).
- Health and Prometheus metrics endpoints.

## Not done yet (next steps)

See [docs/poc-plan.md](docs/poc-plan.md). Key gaps:

- Start a workflow instance and complete the human-approval wait through an event (the YAML contains the `listen` task, no test drives it yet).
- Graph-to-workflow integration is plain HTTP today. There is no official Quarkus Flow extension for LangGraph4j, so a tighter bridge is ours to write.
- Control-plane UI and the embedded assistant agent, authentication and identity delegation, tool registry (MCP), evaluation and monitoring.
- `quarkus-flow-langchain4j` is Preview and `langchain4j-agentic` is experimental; neither is used here.

Evaluation background: the `agentic-process-modernization` report in [bassemZohdy/tech-reports](https://github.com/bassemZohdy/tech-reports).
