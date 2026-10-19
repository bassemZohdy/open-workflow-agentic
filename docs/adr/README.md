# Architecture decision records

Requirement-level decisions (ADR 0001 to 0007) restate decisions D01 to D07 of the requirements set. Project decisions (ADR 0008 onward; 0016 to 0018 concern delivery) record how this repository implements them.

| ADR | Title | Status |
|---|---|---|
| [0001](0001-logical-development-responsibility.md) | Logical Development responsibility | Proposed (requirement-level, D01) |
| [0002](0002-state-and-task-authority.md) | State and task authority | Proposed (requirement-level, D02) |
| [0003](0003-integrity-protected-activation.md) | Integrity-protected activation | Proposed (requirement-level, D03) |
| [0004](0004-optional-administrator-assistant.md) | Optional administrator assistant | Proposed (requirement-level, D04) |
| [0005](0005-oss-and-ows-priority.md) | OSS and OWS priority | Proposed (requirement-level, D05) |
| [0006](0006-gaps-closed-in-review-of-18-october.md) | Gaps closed in review of 18 October | Proposed (requirement-level, D06) |
| [0007](0007-agentic-balance-review-of-18-oct.md) | Agentic balance (review of 18 Oct) | Proposed (requirement-level, D07) |
| [0008](0008-use-quarkus-flow-with-langgraph4j-in-one-quarkus-runtime.md) | Use Quarkus Flow with LangGraph4j in one Quarkus runtime | Accepted for the proof of concept |
| [0009](0009-define-processes-in-ows-yaml.md) | Define processes in OWS YAML | Accepted |
| [0010](0010-keep-agent-loops-in-langgraph4j-not-in-workflow-tasks.md) | Keep agent loops in LangGraph4j, not in workflow tasks | Accepted |
| [0011](0011-use-one-postgresql-database-for-all-durable-state.md) | Use one PostgreSQL database for all durable state | Accepted for the proof of concept |
| [0012](0012-reach-models-only-through-an-llmclient-port.md) | Reach models only through an LlmClient port | Accepted |
| [0013](0013-use-db-scheduler-for-time-based-triggers.md) | Use db-scheduler for time-based triggers | Accepted for the proof of concept |
| [0014](0014-bridge-workflow-to-agent-over-a-narrow-http-contract.md) | Bridge workflow to agent over a narrow HTTP contract | Accepted as an interim step |
| [0015](0015-specify-requirements-and-tests-as-data-render-documents-from-it.md) | Specify requirements and tests as data, render documents from it | Accepted |
| [0016](0016-deliver-in-vertical-slices-with-spikes-first.md) | Deliver in vertical slices by scenario, spikes first | Proposed |
| [0017](0017-one-codebase-two-deployable-roles-with-enforced-boundaries.md) | One codebase, two deployable roles, boundaries enforced by the build | Proposed |
| [0018](0018-plan-is-data-and-checked.md) | Keep the implementation plan as validated data | Proposed |
| [0019](0019-how-workflow-instances-are-started-queried-cancelled-and-resumed.md) | How workflow instances are started, queried, cancelled and resumed (spike WP-003) | Accepted |
| [0020](0020-checkpoint-scoping-and-state-evolution.md) | Checkpoint scoping and state evolution (spike WP-004) | Accepted |
| [0021](0021-identity-delegation-policy-engine-and-secrets.md) | Identity delegation, policy engine and secret store (spike WP-006) | Accepted |
| [0022](0022-protocol-choices-for-tools-and-agent-interop.md) | Protocol choices for tools and agent interoperability (spike WP-007) | Accepted |
| [0023](0023-workflow-to-agent-bridge.md) | Workflow-to-agent bridge: HTTP call confirmed, async callback for long steps (spike WP-005) | Accepted |

| [0024](0024-group-applications-and-library-modules.md) | Group applications and library modules | Accepted |

New decisions: copy the structure of an existing ADR, take the next number, add a row here.
