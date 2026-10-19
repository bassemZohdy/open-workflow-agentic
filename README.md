# open-workflow-agentic

An agentic process platform combining **Quarkus Flow** for CNCF Serverless Workflow definitions,
**LangGraph4j** for agent graphs, **Quarkus LangChain4j** for model access, and **PostgreSQL** for durable state.
The workflow controls when an agent runs; the agent decides how to perform its assigned work.

## Applications and modules

**One runnable Quarkus application exists today: `app-runtime`.** A second administration application,
`app-control`, is planned and currently contains only a skeleton. The seven Maven modules are not seven services.

| Location | Maven artifact | Purpose | State |
|---|---|---|---|
| `apps/app-runtime` | `app-runtime` | Execution application: configuration, workflow definitions, boot and process tests | Runnable |
| `apps/app-control` | `app-control` | Future administration application | Skeleton |
| `modules/oa-contracts` | `oa-contracts` | Pure Java contracts, identifiers, schemas and ports | Implemented |
| `modules/oa-runtime` | `oa-runtime` | Execution capabilities: agents, lifecycle, policy, audit, outbox and scheduling | Implemented capabilities; see linked docs |
| `modules/oa-control` | `oa-control` | Future registry, publication and approvals | Skeleton |
| `modules/oa-eval` | `oa-eval` | Future evaluation suites and release gates | Skeleton |
| `modules/oa-testkit` | `oa-testkit` | Acceptance reporting, model doubles and fault injection | Test support |

The root `pom.xml` builds all modules. `apps/` and `modules/` group source directories;
they do not introduce additional Maven projects or deployment units.
See [repository structure](docs/repository-structure.md) and [architecture](docs/architecture.md).

## Quick start

Requires Java 21, Maven 3.9 or later, and PostgreSQL 16. Python 3 runs the documentation and dependency checks.

```bash
docker compose up -d                 # development Postgres on localhost:5432/agentic
export OPENAI_API_KEY=...             # or configure an OpenAI-compatible model gateway
mvn -B install -DskipTests            # from the repository root
cd apps/app-runtime
mvn quarkus:dev
```

The database and model settings are in
[`apps/app-runtime/src/main/resources/application.properties`](apps/app-runtime/src/main/resources/application.properties).
Do not enable the development identity or audit bypass switches in a deployment.

## Build and test

From the repository root, with PostgreSQL available:

```bash
mvn -B clean verify
python3 tools/render_requirements.py --check
python3 tools/check_dependency_policy.py
python3 -m unittest discover -s tools -p 'test_*.py' -v
java -cp "modules/oa-testkit/target/classes:$(cat modules/oa-testkit/target/classpath.txt)" \
  io.github.bassemzohdy.openworkflow.agentic.testkit.acceptance.AcceptanceStatus --check
```

Unit tests live with the library they test. Quarkus boot tests and packaged-process recovery tests live in
`apps/app-runtime`. Model calls are stubbed in tests. See [CONTRIBUTING.md](CONTRIBUTING.md) and
[testing](docs/testing.md) for the full workflow.

## Documentation and implementation status

- [Architecture](docs/architecture.md) and [repository structure](docs/repository-structure.md).
- [Lifecycle and recovery](docs/lifecycle.md), [policy](docs/policy.md), [audit](docs/audit.md), and [contracts](docs/contracts.md).
- [Requirements](docs/requirements/README.md), [acceptance tests](docs/test-cases.md), and [traceability](docs/traceability.md).
- [Implementation plan](docs/plan/README.md) and [work packages](docs/plan/work-packages.md).
- [Architecture decisions](docs/adr/README.md) and [spike outcomes](docs/spikes/).

Acceptance status is maintained in `docs/requirements/test-status.json` and rendered into the test and
traceability documents. Implemented endpoints do not imply that an entire work package is complete.
Authentication and delegated identity (WP-102), the administration application, and other planned capabilities
must be assessed against their acceptance criteria.

Only spike findings, measurements and decisions are retained in `docs/spikes` and ADRs.
Experimental spike implementations are not part of this repository. Product code and regression tests belong
in the application or library responsible for that capability.

After editing a requirements, status or work-package JSON source, run `python3 tools/render_requirements.py`.
CI checks that generated Markdown matches the source data.

Evaluation background: [bassemZohdy/tech-reports](https://github.com/bassemZohdy/tech-reports).
