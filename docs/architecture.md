# Architecture

## Current implementation and planned roles

The runnable application is `apps/app-runtime`. It embeds Quarkus Flow, LangGraph4j and db-scheduler in one
Quarkus process. PostgreSQL stores durable execution state, checkpoints, lifecycle records, audit events,
projections and scheduled work. Models are accessed through the `LlmClient` port.

| Responsibility | Current location | Deployment status |
|---|---|---|
| Execution and operational APIs | `modules/oa-runtime`, wired by `apps/app-runtime` | One runnable application |
| Governance administration: registry, publication and approvals | `modules/oa-control`, wired by `apps/app-control` | Skeletons; planned |
| Evaluation suites and release gates | `modules/oa-eval` | Library skeleton; no service |
| Authoring workflow definitions and agent graphs | Workflow resources in the execution app; graphs in the runtime library | Source/build responsibility; future lifecycle tooling is planned |
| Shared contracts and test support | `modules/oa-contracts`, `modules/oa-testkit` | Libraries; no services |

The target remains two separable application roles that may be deployed together or separately
([ADR 0017](adr/0017-one-codebase-two-deployable-roles-with-enforced-boundaries.md)).
Administrative-outage safety, database privilege separation and approved-state snapshots are planned controls,
not consequences of the current folder structure.

## Execution path

A workflow definition controls process steps, waits and agent invocation. The current bridge invokes the
embedded agent through a narrow HTTP contract. LangGraph4j owns the agent reasoning loop and checkpoints;
Quarkus LangChain4j supplies model access. Lifecycle records and reconciliation coordinate recovery between
the workflow and agent engines. See [lifecycle](lifecycle.md) and [ADR 0023](adr/0023-workflow-to-agent-bridge.md).

## Code and dependency boundaries

- Applications own configuration, composition, workflow definitions and application-level tests.
- Libraries own contracts and feature implementations; they do not depend on application artifacts.
- Contracts have no main-classpath dependency. Execution cannot depend on control code.
- Test support is consumed only in test scope.
- Feature packages group related behavior inside the runtime library; they do not imply additional services.

The complete module map and build conventions are in [repository structure](repository-structure.md).

## Implementation evidence

Use [acceptance tests](test-cases.md) and [traceability](traceability.md) for completion status.
Use [policy](policy.md), [audit](audit.md), [contracts](contracts.md) and [lifecycle](lifecycle.md) for implemented
surfaces, configuration and limits. Spike reports preserve findings and decisions; prototype implementations
are not retained in this repository.
