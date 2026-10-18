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
mvn -B install -DskipTests          # once, from the repository root
cd app-runtime && mvn quarkus:dev
curl -s localhost:8080/agents/research -H 'content-type: application/json' \
     -d '{"topic":"agentic process modernization"}'
```

Tests run without a model (a stub replaces it) but need Postgres on `localhost:5432/agentic`:

```bash
mvn verify
```

## Layout

Maven multi-module build (ADR 0017). The runtime never depends on control; `oa-contracts` depends on nothing but the JDK. See [CONTRIBUTING.md](CONTRIBUTING.md) for the rules and how to build.

```
oa-contracts/      pure Java: ids (ULID), pagination, problem details, correlation id, ports
oa-runtime/        execution: agent graphs, workflow glue, REST conventions, scheduler migration
oa-control/        administration (skeleton)
oa-eval/           evaluation suites and gate (skeleton)
oa-testkit/        test support (skeleton)
app-runtime/       deployable: Quarkus application, configuration, OWS workflow definitions, boot tests
app-control/       deployable: administration (skeleton)
docs/requirements/                       requirements (JSON source + generated Markdown)
docs/test-cases.md                       39 acceptance tests in 8 scenarios
docs/traceability.md                     requirement-to-test coverage and status
docs/adr/                                architecture decision records
docs/plan/                               implementation plan (7 phases, 52 work packages)
docs/                                    architecture
tools/render_requirements.py             regenerates the generated documents
tools/check_dependency_policy.py         contracts purity and licence allow-list (after mvn verify)
```

## Requirements, tests and decisions

- [Requirements](docs/requirements/README.md): 34 Mandatory, 25 High, 5 Optional, deployment profiles, principles.
- [Test cases](docs/test-cases.md): 39 acceptance tests (T01 to T39) in 8 scenarios, with implementation status.
- [Traceability](docs/traceability.md): which tests verify which requirement.
- [Implementation plan](docs/plan/README.md): phases, milestones, risks, decisions needed, 52 work packages with owners for all 39 tests.
- [ADRs](docs/adr/README.md): 7 requirement-level and 11 project decisions.

The JSON is the source of truth. After editing `docs/requirements/requirements.json` or `test-status.json`, run `python3 tools/render_requirements.py`. CI fails if the generated files are out of date.

None of the 39 acceptance tests is automated yet; the tests in `app-runtime/src/test` are bootstrap smoke tests, and the module rules and API conventions have their own unit tests.

## What works today (last verified on the single-module layout, before the module split)

- Application boots with Quarkus Flow, LangGraph4j, db-scheduler and Postgres together.
- The OWS definition `agentic/research-and-approve` is discovered and registered as a `WorkflowDefinition` bean.
- The research agent graph runs end to end with Postgres checkpoints (`POST /agents/research`).
- Health and Prometheus metrics endpoints.

## Not done yet (next steps)

See the [implementation plan](docs/plan/README.md) and [work packages](docs/plan/work-packages.md). Key gaps:

- Start a workflow instance and complete the human-approval wait through an event (the YAML contains the `listen` task, no test drives it yet).
- Graph-to-workflow integration is plain HTTP today. There is no official Quarkus Flow extension for LangGraph4j, so a tighter bridge is ours to write.
- Control-plane UI and the embedded assistant agent, authentication and identity delegation, tool registry (MCP), evaluation and monitoring.
- `quarkus-flow-langchain4j` is Preview and `langchain4j-agentic` is experimental; neither is used here.

Evaluation background: the `agentic-process-modernization` report in [bassemZohdy/tech-reports](https://github.com/bassemZohdy/tech-reports).
