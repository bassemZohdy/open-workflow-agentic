# Implementation plan

**Version:** 0.2 · **Date:** 2026-10-19 · **Status:** Active implementation tracking

This plan turns the 64 requirements and 39 acceptance tests ([requirements](../requirements/README.md), [test cases](../test-cases.md)) into an ordered build. The exact work packages, sizes, dependencies and critical path are in [work-packages.md](work-packages.md), generated from [work-packages.json](work-packages.json). A checker fails the build if a Mandatory requirement has no work package, a test has no single owner, or a dependency points to a later phase.

## Current progress and next work

The plan contains **53 work packages**: **11 completed**, **12 partial**, and **30 not started**. Five completed
packages are research outcomes; their prototype code is removed. Only **T01, T03 and T07 (3 of 39 acceptance cases)**
are recorded as automated. [TODO.md](../../TODO.md) is the complete generated checklist;
[CHANGELOG.md](../../CHANGELOG.md) records shipped scope and requirement evidence.

The merged code includes foundation/testkit work, contracts, policy and audit components, workflow/output validation,
lifecycle registry/outbox/recovery/operator APIs and identity/delegation. Full requirement coverage remains partial.
Strict real-Keycloak interop evidence is recorded for the selected native-delegation preview profile and the standard
actorless-rejection profile; T07 also retains in-process acceptance fixtures. WP-102 remains partial because the real
asynchronous Flow dispatch/resume consumer is not complete. A previously observed packaged-process identity recovery
failure is unresolved (issue #75), despite subsequent passing runs. API audit-caller binding is integrated, while
model/tool and end-to-end execution policy/audit gates remain open.

Execution now uses [two backlogs in TODO.md](../../TODO.md#two-agent-delivery-plan): **GPT owns 21 unfinished
packages** across execution/platform/evaluation; **Claude owns 21** across identity/policy/agents/governance.
Each reviews the other's implementation PRs at the current commit. GPT continues WP-108's durable execution backend
independently of whole-WP-102 closure; Claude continues the live identity/policy consumers and the recovery investigation.
As of 2026-10-26, data-only V76 PR #74 has passing full CI but still requires exact-head Claude review before merge.
Consumers gate on their required merged interface, not an entire unfinished package. Claude review of each exact
implementation head and passing CI remain mandatory before merge.
Both queues specify merged prerequisite handoffs and outcomes, while the detailed package checklists retain
all closure gates. WP-105's existing core can support new work; its full T16 recovery proof follows ledger,
inbox and timer integration. T23 stays with WP-305 for cross-task emergency revocation.

The `execution_plan` object in work-packages.json is the source for assignments, review rules and exclusive
write areas. Shared contracts, lifecycle integration, migrations, build/configuration and generated tracking
have GPT as their sole writer; Claude submits explicit handoffs for those changes. Claude-owned caller,
policy, audit and agent paths use the reciprocal process. CI checks assignment coverage, reciprocal reviewers,
prerequisites, cycles and overlapping reserved paths. It does not authenticate which AI wrote a change;
the recorded reciprocal PR review and writer agreement remain required. No implementation status or
acceptance evidence changes merely because a package has been assigned.

Work-package progress, evidence, completed deliverables, remaining gates and decisions are maintained in
work-packages.json. The size table and critical path remain original full-scope estimates; they include completed
work and must be re-estimated before making remaining-delivery commitments.

## 1. What the requirements really ask for

The 34 Mandatory requirements fall into seven groups. Each group has one hard problem that decides the design.

| Group | Requirements | Hard problem | Design answer |
|---|---|---|---|
| Durable execution | M03 M04 M07 M28 | Two engines (workflow and agent graph) each keep progress; a crash can leave them disagreeing | One canonical progress model with a reconciler (WP-105); an effect ledger for external effects (WP-108) |
| Bounded autonomy | M02 M12 M14 M29 M30 | An agent must not widen its own authority or bypass policy | Limits and policy enforced at non-bypassable gateways, not in prompts (WP-103, WP-202, WP-204) |
| Identity and data | M11 M13 M19 M24 | A user's authority must flow to agents and tools for weeks without leaking | Delegation grants and token exchange, never token passthrough; scoped memory (WP-102, WP-206) |
| Governed change | M09 M10 M17 M18 M25 M26 M27 | AI behavior changes with prompts and models, not only code | Everything that changes behavior is an inventoried, versioned, evaluated, approved artifact; running work is pinned (WP-301 to WP-304, WP-501) |
| Humans and agents together | M05 M32 | Humans approve, redirect and take over | Durable human tasks and durable agent interrupts (WP-106, WP-208) |
| Operability and containment | M08 M15 M16 M20 M21 | Prove recovery, trace decisions, stop a bad agent fast | Hash-chained audit, OpenTelemetry, kill switch, restore and upgrade drills (WP-111, WP-112, WP-305, WP-401, WP-402) |
| Agentic capability | M29 to M34 | The project exists for agents; the workflow is the control structure | Planning, tools, knowledge, multi-agent, interaction, quality monitoring, model choice (phases 2 and 5) |

Two consequences shape everything:

1. **Most of the effort is governance, not orchestration.** Quarkus Flow and LangGraph4j supply orchestration; the unproven, mostly new work is policy, identity, inventory, pinning, publication, evaluation and containment.
2. **Agent capability depends on the gateways.** Tools, models and delegation go through the same enforcement points. Building them first (phase 1 and the start of phase 2) avoids retrofitting security later.

## 2. Target architecture

| Location | Responsibility | Current state |
|---|---|---|
| `modules/oa-contracts` | Pure Java records, JSON schemas and ports | Implemented |
| `modules/oa-runtime` | Execution: Flow glue, agents, lifecycle, gateways, audit and persistence | Implemented capabilities |
| `modules/oa-control` | Registry, publication, approvals and incidents | Skeleton |
| `modules/oa-eval` | Evaluation suites, scorers and release gate | Skeleton |
| `modules/oa-testkit` | Acceptance reporting, model doubles and fault injection | Implemented |
| `apps/app-runtime` | Execution application | Runnable |
| `apps/app-control` | Administration application | Skeleton |
| Future console and authoring tooling | UI and artifact lifecycle tooling | Planned; no module yet |

Module paths are defined in the root POM. See [repository structure](../repository-structure.md).


Rules:

- `oa-runtime` never depends on `oa-control` (enforced by ArchUnit). The runtime reads an approved-state snapshot and keeps working when the control application is stopped (M26, M27).
- One codebase, two deployable roles, co-deployable. Separate deployment is possible but not required (ADR 0017).
- Every call to a tool or a model passes through a gateway that applies policy, limits, validation and audit (M02, M12, M14, M15).
- The workflow engine decides when and under which authority an agent runs; the agent decides how (ADR 0010).

### Data

One PostgreSQL database, separate schemas and database roles:

| Schema | Owner | Content |
|---|---|---|
| flow | Quarkus Flow JPA | Workflow instance state |
| graph | LangGraph4j saver | Agent checkpoints |
| task | runtime | agent_task, human_task, task_view projection, outbox |
| effect | runtime | Effect ledger: intent, idempotency key, outcome, compensation |
| audit | runtime (append only) | Hash-chained events |
| registry | control | Artifacts, versions, bindings, approvals, activations |
| memory | runtime | Long-term memory with provenance, consent, retention |
| eval | control and eval | Datasets, runs, scores, review queue |
| scheduler | db-scheduler | Timers |

The runtime database role can read `registry` activations but not write them; the control role cannot write `flow` or `graph`. This is how separation of authority is enforced below the code (WP-304).

### The canonical progress model (M28)

- The platform instance registry is authoritative for lifecycle status, definition id/version, request idempotency key and durable history. Flow executes the pinned definition; its persisted rows are not the only source of truth.
- The `agent_task` row is authoritative for agent progress and links to the Flow instance and the latest checkpoint.
- The effect ledger is authoritative for external side effects and their idempotency keys.
- `task_view` is a projection written through a transactional outbox, never a second source of truth.
- After definitions warm up, a startup and periodic reconciler finds restored Flow instances that are registered as active but still PENDING, explicitly starts them, and repairs orphaned or stuck work. Every repair is audited.

### Idempotent external effects (M03, M06)

Write the intent with a stable idempotency key before calling the target, then record the outcome. A restart can re-run the first unfinished Flow task, and the Flow HTTP client may retry below the workflow policy, so retries must reuse the same key. After a crash the ledger shows an intent without an outcome; the reconciler asks the target or retries with that key. Exactly-once effects cannot be promised for arbitrary targets: require target-side idempotency or make the uncertain outcome visible for reconciliation/manual resolution (M08).

### Identity (M11)

A user's request creates a delegation grant: scope, expiry, subject. It is not a token. When an agent or workflow acts, the runtime exchanges the grant for a short-lived, narrowly scoped token (RFC 8693). After a long wait the runtime reauthorizes before continuing. Agent-to-agent delegation can only narrow scope. Agents have their own workload identity.

## 3. Reuse before build

| Need | Reuse | Status found on Maven Central (18 Oct 2026) | Build ourselves |
|---|---|---|---|
| Process engine | Quarkus Flow 1.2.0 | Stable core; extensions for JPA, Kubernetes durable mode, messaging, scheduler, OIDC, OpenTelemetry, gRPC | Governance around it |
| Agent graphs | LangGraph4j 1.9.5 | Postgres saver, OpenTelemetry, AG-UI SDK, handoff modules | Graph catalog, interceptors |
| Models, MCP, RAG, memory | Quarkus LangChain4j 1.14.2 (its BOM brings langchain4j 1.20.2, see the note below) | MCP, OIDC MCP and model auth providers, pgvector, memory stores, evaluation testing modules. Version 2.0.0.Beta1 exists (beta only) | Gateways, ACL-aware retrieval |
| Agent interop | A2A Java SDK, Apicurio registry integration | To be confirmed in WP-007 | Delegation scope and cycle control |
| Timers | db-scheduler 0.0.2 | Young (0.0.x) | Deadlines tied to instances |
| Identity | Keycloak, quarkus-oidc | Mature | Delegation grants |
| Policy | OPA or Cedar | Decision in WP-006 | PEP library, fail-closed rules |

Version note (checked 19 Oct 2026). The `quarkus-langchain4j-bom` 1.14.2 imports `dev.langchain4j:langchain4j-bom` 1.20.2, so the langchain4j libraries that the BOM manages are 1.20.2 (the dependency tree of the WP-007 spike resolved the same version). The number 1.14 names the Quarkus LangChain4j line, not the langchain4j library. On Maven Central that day the Quarkus 4 platform line had only 4.0.0.Beta1 and the quarkus-langchain4j 2 line only 2.0.0.Beta1, so neither had a final release; 1.15.0.CR1 is a release candidate. The 3.x platform has the final 3.40.1, which D-8 does not adopt. Revisit when finals ship (issue #26).

Known weak spots: `quarkus-flow-langchain4j` is Preview and `langchain4j-agentic` is experimental, so they are not used. There is no official Quarkus Flow to LangGraph4j bridge. Quarkus 4.0 and LangChain4j 2.0 are in beta; we stay on 3.39 and 1.14 and plan an upgrade (WP-402).

## 4. Phases and milestones

| Phase | Goal | Milestone demonstration | Effort (days) |
|---|---|---|---:|
| 0 Foundations and spikes | Structure, harness, answers to open questions | Build enforces boundaries; tests report status | 57 |
| 1 Secure governed execution core | Durable, authenticated, policy-enforced run with humans | Scenario S1 and S3 core: a case survives kill -9 and completes once | 124 |
| 2 Agent capability | Plan, tools, knowledge, memory, multi-agent, interaction | Scenario S8: an unstructured request is resolved by a supervisor and specialists | 98 |
| 3 Governed lifecycle and authority | Inventory, pinning, publication, separation, containment | Scenario S7: governed release and rollback; runtime survives control outage | 79 |
| 4 Operations and deployment | Backup, upgrade, deploy, tenancy, scale | Scenario S4: restore and upgrade drills | 57 |
| 5 Quality and model governance | Evaluation gate, production monitoring, model routing | A quality drop raises an alert; same task on three models | 43 |
| 6 Tooling, coexistence, console | Authoring tools, shadow runs, console, assistant | All 39 tests automated | 75 |

The total is 533 days for one engineer. The critical path is 144 days. Sizes are relative; they exist to compare packages and find the critical path, not to promise dates.

### Critical path

```mermaid
flowchart LR
  A[WP-001 Foundations] --> B[WP-004 Spike B]
  B --> C[WP-005 Bridge spike]
  C --> D[WP-105 Run lifecycle]
  D --> E[WP-108 Effect ledger]
  E --> F[WP-113 Adapters]
  F --> G[WP-204 Tool gateway]
  G --> H[WP-301 Inventory]
  H --> I[WP-304 Separation]
  I --> J[WP-305 Containment]
  J --> K[WP-501 Evaluation gate]
  K --> L[WP-601 Authoring lifecycle]
  L --> M[WP-606 Replay and explanations]
```

WP-105 (canonical progress) is the single most important package: almost everything after phase 1 builds on it. Start it as soon as the spikes close and give it the best-understood design.

**Spike code (issue #30).** The spike implementations were temporary, are not part of the product or the root build, and have been removed. Only their outcomes are kept: findings, measurements, confidence labels and decisions in [`docs/spikes`](../spikes) and the ADRs. WP-207 builds and tests the A2A implementation in its own work package; it does not revive the spike harness.

## 5. Order of work and parallelism

- **Start together:** WP-001, then WP-002 to WP-007 in parallel; WP-008 follows WP-002 (two or three people can close all spikes in two weeks of elapsed time).
- **Phase 1** splits into three streams: execution (WP-105, 107, 108, 109, 110), security (WP-102, 103, 104), and cross-cutting (WP-111, 112, 113).
- **Phase 2** splits into agent core (201 to 203), tools and knowledge (204, 205, 209), and collaboration (206 to 208).
- **From phase 3** the control application (WP-301 to 305) and runtime hardening (WP-306, 401, 402) run in parallel.

## 6. Test strategy

| Layer | What | Where | Runs |
|---|---|---|---|
| Unit | Contracts, validators, state machines | each module | every commit |
| Component | Quarkus tests with Postgres and scripted model | modules | every commit |
| Acceptance | The 39 tests, annotated `@Acceptance("Txx")` | oa-testkit based | every commit for fast ones; nightly for failover and soak |
| Fault | Kill the process, pause the database, drop calls, advance the clock | oa-testkit | nightly |
| Model-in-the-loop | Real model against evaluation suites | oa-eval | on prompt, model or tool change; scheduled |
| Drill | Restore, rolling upgrade, revoke, control outage | scripts and CI jobs | scheduled; before release |

Principles:

- The scripted model makes tests deterministic. Real-model runs are evaluation, not unit testing.
- Status of each acceptance test is generated from the test reports into `test-status.json`; documents cannot claim more than the build proves.
- The sandbox used for development has Postgres but no container daemon, so tests rely on a Postgres instance from the environment (local, CI service, or compose), not on Testcontainers.
- A test is "done" only when it is automated, runs in CI, and its requirement IDs are in the annotation.

## 7. Definition of done for a work package

1. Requirements and tests listed in the package are met; owned tests are automated and green in CI.
2. Contracts are in `oa-contracts`; no framework leak.
3. Failure behavior is specified and tested: timeout, crash, duplicate, denial.
4. Audit events, metrics and traces exist for new decisions and effects.
5. Configuration is documented; defaults are safe (deny, off, bounded).
6. ADR written or updated for any decision that is hard to reverse.
7. `docs/` regenerated; traceability shows the new status.

## 8. Risks and mitigations

| # | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R1 | Quarkus Flow is young (1.0 in August 2026); persistence or recovery gaps appear late | Medium | High | Spike A first; findings retained; product characterization tests maintained in the owning work packages; contribute fixes upstream; keep the workflow-to-agent contract narrow so the engine can be replaced |
| R2 | No official Flow to LangGraph4j bridge; our bridge is the weakest point | High | High | Spike C; ADR; keep agent behind a port; test crash-mid-step explicitly |
| R3 | Two engines diverge after a crash | Medium | High | Canonical progress model and reconciler built before agent features (WP-105) |
| R4 | Checkpoint state classes change between versions and break resume | Medium | High | State-schema versioning in WP-201; pinning in WP-302; upgrade drill in WP-402 |
| R5 | Preview and beta modules change (LangChain4j 2.0, Quarkus 4.0, db-scheduler 0.0.x) | High | Medium | Stay on stable lines; wrap behind our ports; scheduled upgrade work |
| R6 | Policy engine choice causes rework | Medium | Medium | Decide in WP-006; PEP library hides the engine |
| R7 | Scope is large (64 requirements) and governance dominates | High | High | Vertical slices by scenario; milestones with demonstrations; defer H items explicitly |
| R8 | Model nondeterminism makes tests flaky | High | Medium | Scripted model for acceptance; statistical thresholds only in evaluation |
| R9 | Prompt injection or tool abuse bypasses enforcement | Medium | High | Gateways are the only path; injection corpus in CI; sandbox off by default |
| R10 | Console becomes a bottleneck or a second source of truth | Medium | Medium | API-first; console last; the assistant acts as the user |
| R11 | Development environment limits (no container daemon, network allow-list) hide integration problems | Medium | Medium | CI with real services; documented dev compose; nightly drills |
| R12 | Data location and encryption rules differ by deployment | Medium | Medium | Configurable constraints from WP-306; deployment profiles in WP-403 |

## 9. Decisions and conditional follow-ups

Each has a recommended default so work is not blocked. Rows marked decided were settled by the owner, and their text is the decision.

| Decision | Status | Current decision / proposed default | Owner |
|---|---|---|---|
| D-1 Identity provider | open | Recommended: Keycloak OIDC/token exchange; confirm the deployment issuer and complete real integration. | WP-102 |
| D-2 Policy engine | decided | Cedar embedded; OPA sidecar remains an organizational alternative (ADR 0021). | WP-103 |
| D-3 Target platform | open | Recommended: OpenShift and plain Kubernetes, packaged with Helm. | WP-403 |
| D-4 Console technology | open | Recommended: Qute unless the console needs a TypeScript SPA. | WP-603 |
| D-5 Model access | open | Current implementation: Quarkus LangChain4j through LlmClient. Confirm governed bindings and optional external gateway for WP-503. | WP-503 |
| D-6 Tenancy | open | Recommended: single tenant first; introduce row-level security when multi-tenancy is enabled. | WP-404 |
| D-7 AI incident reporting | open | Recommended: reporting hook; content and obligations follow the activated deployment profile. | WP-305 |
| D-8 Quarkus 4 and LangChain4j 2 | decided | Stay on the current stable lines; revisit only when final releases are evaluated (issue #26). | WP-402 |
| D-9 Secret store | decided | Mounted Kubernetes Secrets for static values. Vault where already operated; validate the BOM-pinned 4.9.0 binding before selecting that path. | WP-102 |

## 10. Tracking and closure

1. Use TODO.md for the active backlog and every remaining deliverable; keep deferred requirements visible.
2. Update work-packages.json progress and evidence when code lands; update test-status.json only from passing acceptance evidence.
3. A completed component does not close a whole requirement. Keep integration gates and evidence limits explicit.
4. Run tools/render_requirements.py after changing any source JSON. CI checks generated progress and TODO.md.
5. Record shipped changes in CHANGELOG.md. Re-estimate incomplete work before assigning calendar dates.
