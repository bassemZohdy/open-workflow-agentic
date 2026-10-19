# WP-005 Spike C: workflow-to-agent bridge

**Version:** 0.1 · **Date:** 2026-10-18 · **Status:** Findings, not yet reviewed by the owner

Code: removed (issue #30). The spike was temporary, was never part of the root build or the product, and only its outcomes are kept here; the code is in git history at `b76181d` under `spikes/`. The product work package builds and tests the real implementation. Decision: [ADR 0023](../adr/0023-workflow-to-agent-bridge.md). Builds on [WP-003](WP-003-quarkus-flow.md) (Quarkus Flow 1.2.0) and [WP-004](WP-004-langgraph4j-durability.md) (LangGraph4j 1.9.5, V2 saver, derived ThreadId).

## How to read the evidence

- **verified-by-test**: a test in `spikes/spike-c-bridge` runs against the packaged application in separate JVMs (killed with `kill -9`) and passes, or is kept as a characterization of an observed failure. The test name is given.
- **measured-by-hand**: a number from a manual run against a running process, not a test. The count is given.
- **read-in-source** or **read-in-docs**: taken from the Quarkus Flow or Serverless Workflow 7.35.3 source jars, or the WP-003 findings. Not run here.
- **unknown**: not established.

Test prefixes: `BridgeA_`, `BridgeB_`, `BridgeC_` in `BridgeAIT`, `BridgeBIT`, `BridgeCIT` (classes run by failsafe after `package`). Criterion numbers below follow the brief.

## The shared agent

One LangGraph4j graph (`ResearchGraph`), the same for all three bridges:

- `draft` calls the model through the `LlmClient` port with the scripted double from oa-testkit (`ScriptedLlmClient` with a fallback responder), and records a step.
- `publish` records a step and writes a row to `spikec.external_sink` with the key `<thread id>:publish`, then returns. A test-only delay holds it in flight for the kill tests.
- The graph interrupts before `publish`. That interrupt is the approval gate.
- Checkpointer: `PostgresSaverV2`, `JsonSpikeSerializer` (plain text, ADR 0020), thread id `ThreadId.derive(tenant, user, instance)`.
- The context travels as one record, `Ctx` (tenant, user, instance id, correlation id, deadline, budget, topic). It is the workflow input, the HTTP body, the Java argument of bridge B, and the JSON value stored in the run record.

The three bridges share the registry, reconciler, observer and tests. Only the workflow definition and the call path differ:

| Bridge | Workflow side | Agent side |
|---|---|---|
| A | `call: http` to `POST /agents/research/run`, then `POST /resume` after approval (`flow/bridge-a.yaml`) | Two HTTP endpoints in the same app |
| B | Java DSL `function(...)` calling `InProcessBridge` (`InProcessFlow`) | A CDI bean in the same JVM |
| C | `call: http` to `POST /start` (returns 202), `listen` for `spikec.agent.started`, acknowledgement, approval `listen`, `POST /resume-async`, `listen` for `spikec.agent.completed` (`flow/bridge-c.yaml`) | Graph on a thread pool; callbacks are CloudEvents from an outbox, redelivered until acknowledged |

## Comparison

Each cell: result, then test (confidence).

| # | Criterion | A: HTTP, same app | B: in-process CDI function | C: async start and callback |
|---|---|---|---|---|
| 1a | Identity, correlation, deadline, budget arrive in the agent | Body carries the full `Ctx`. `X-Correlation-Id` header arrives. Tenant and user arrive as data, unauthenticated. Deadline and budget arrive as values; no bridge enforces them. (`BridgeA_Q1_contextArrivesInAgent`, verified-by-test) | Full `Ctx` as a Java argument; no header. Same limits on identity and deadline. (`BridgeB_Q1_contextArrivesInProcess`, verified-by-test) | Full `Ctx` in the start body and the header. Resume sends only the instance id; the agent reloads the context from its run record. Callback carries instance id and status. (`BridgeC_Q1_contextArrivesInAgent`, verified-by-test) |
| 1b | MDC arrives | No. The agent's node reads `correlationId` from MDC and gets null. Nothing sets it on the caller side in this spike, so this only shows the agent does not invent it. Earlier WP-003 Q10 showed MDC does not cross a Flow task. (`BridgeA_Q1` asserts `mdc_correlation` is null, verified-by-test; cause read-in-docs via WP-003 Q10) | Same. (`BridgeB_Q1`, verified-by-test) | Same. (`BridgeC_Q1`, verified-by-test) |
| 1c | How context is passed | Explicit: body and header. | Explicit: argument. | Explicit at start; by reference (run record) for resume and callbacks. |
| 2a | Instance recovers after `kill -9` mid-step | Yes, when our reconciler starts restored instances. Without it the approval reaches no listener and nothing runs. (`BridgeA_Q2_agentKilledMidStep`, `BridgeA_Q2_withoutReconcilerRestoredInstanceStaysIdle`, verified-by-test) | Yes, same condition. (`BridgeB_Q2_agentKilledMidStep`, verified-by-test) | Yes, same condition. (`BridgeC_Q2_agentKilledMidStep`, verified-by-test; one unexplained failure, see below) |
| 2b | Who restarts the agent | The workflow. The engine re-runs the in-flight HTTP task (`resumeAgent` after the approval, `runAgent` before it), and that call resumes the graph from its checkpoint. Read from the test: `TaskStarted do/…/resumeAgent` after restart. (`BridgeA_Q2_agentKilledMidStep`, verified-by-test) | The workflow, same mechanism. (`BridgeB_Q2_…`, verified-by-test) | Our reconciler. It finds the run record in status `RUNNING` or `RESUMING` and starts it again; it writes a `reconciler-restart` step row. (`BridgeC_Q2_agentKilledMidStep` asserts the row, verified-by-test) |
| 2c | Is the effect duplicated? | The `publish` node runs twice. The external row is written once. (`BridgeA_Q2_agentKilledMidStep` asserts `publish` steps ≥ 2 and `effects = 1`, verified-by-test) | Same. (`BridgeB_Q2_agentKilledMidStep`, verified-by-test) | Same. (`BridgeC_Q2_agentKilledMidStep`, verified-by-test) |
| 2d | Does the idempotency key prevent it? | Yes, with a deterministic key (`<thread>:publish`, `ON CONFLICT DO NOTHING`). Without a key (random per write) the same restart writes the effect twice. (`BridgeA_Q3_naiveEffectWithoutKeyIsDuplicated`, verified-by-test) | Same. (`BridgeB_Q3_naiveEffectWithoutKeyIsDuplicated`, verified-by-test) | Same. (`BridgeC_Q3_naiveEffectWithoutKeyIsDuplicated`, verified-by-test) |
| 2e | Mid-draft kill (model call in flight) | Draft runs again; the instance completes. (`BridgeA_Q2_agentKilledMidDraft`, verified-by-test) | Same. (`BridgeB_Q2_agentKilledMidDraft`, verified-by-test) | Same, restarted by the reconciler. (`BridgeC_Q2_agentKilledMidDraft`, verified-by-test) |
| 3a | At-least-once for the agent call | At-least-once. Draft and publish nodes re-run after a kill. (verified-by-test, as 2c and 2e) | At-least-once. (verified-by-test) | At-least-once. (verified-by-test) |
| 3b | Exactly-once for the effect | Exactly-once in effect, only because the key is deterministic and the sink is idempotent. The sink here is a table in the same database; a real external system must accept the key. (verified-by-test, as 2d; the real case is unknown) | Same. (verified-by-test) | Same. (verified-by-test) |
| 4a | Human interrupt and resume through the bridge | Yes. The instance is listening, the approval is published after the listen is registered, and the run resumes. (`BridgeA_Q2_killWhileWaitingForApproval`, verified-by-test) | Yes. (`BridgeB_Q2_killWhileWaitingForApproval`, verified-by-test) | Yes. (`BridgeC_Q2_killWhileWaitingForApproval`, verified-by-test) |
| 4b | Approval reaches only its own instance | Yes. Two instances wait on one event type; one approval resumes one. (`BridgeA_Q7_approvalWakesOnlyItsInstance`, verified-by-test) | Yes. (`BridgeB_Q7_…`, verified-by-test) | Yes. (`BridgeC_Q7_…`, verified-by-test) |
| 4c | An approval sent before the listen is registered | Lost. The tests wait for `TaskStarted awaitApproval` before approving. The loss itself is from WP-003 Q02 (verified there), not re-run here. | Same (WP-003 Q02). | Same (WP-003 Q02). |
| 4d | A callback sent before the listen is registered | Not applicable (no callback). | Not applicable (no callback). | **Lost in plain form.** Measured by hand: 3 of 10 normal starts never reached the approval listen. `BridgeC_Q5_plainCallbackBeforeListenIsLost` (verified-by-test: the start callback is consumed by nothing and the instance waits at `awaitAgentStarted`). With the outbox: 0 of 10 by hand; `BridgeC_Q5_outboxRecoversCallbackSentBeforeListen` (verified-by-test). |
| 5 | Testability with the scripted model | Yes. The agent is an in-process graph with the `ScriptedLlmClient` double, so each test runs against real Postgres and real processes with no network model. (verified-by-test) | Same, and the agent bean is called directly from a unit test without HTTP (read-in-source; not written as a test). | Same. |
| 6 | Move the agent to a separate process later without changing the workflow | Likely yes. The workflow only holds a URL. Requires an authenticated endpoint and a reachable address. Not run: the agent was never separated. (read-in-source) | **No.** The definition binds to the bean class through the Java DSL; a separate process needs a new task type. (read-in-source: `InProcessFlow`, `InProcessBridge`) | Likely yes. Moving needs the callback to be an inbound HTTP endpoint (CloudEvents) and the outbox to stay with the agent. Not run. (read-in-source) |
| 7 | Glue lines and moving parts | Workflow YAML 37 lines; agent endpoints in `AgentApi` (about 30 for `run` and `resume`). Moving parts: the HTTP hop, the auth it needs, the run record. | Java DSL 36 lines; bridge bean 39 lines. Moving parts: the CDI bean and the function binding. No hop, no serialization of the call. | YAML 72 lines; `AsyncAgent` 135 lines (callbacks and outbox); acknowledgement endpoint; outbox table; redelivery timer; acknowledgement tasks in the workflow. Most moving parts. |

Shared glue, needed by all three: `ResearchGraph` 177 lines (154 before issue #9), `Store` 246 lines (spike-owned tables), `Lifecycle` (reconciler) 161 lines (111 before issue #9), `Ctx` 48 lines, `Events` 37 lines.

### Engine facts found while building the bridges

These are verified by the test runs above and extend WP-003.

1. **A listen task's output can be an array.** In one run the `resumeAgent` task received the listen output as an array and failed with `Cannot index array with string "instanceId"`. The expressions handle both shapes with `if type == "array" then .[0] else . end`.
2. **The JSON event model is the whole CloudEvent.** In `correlate`, the payload field is `.data.<field>`, not `.<field>`. A wrong path gives no match and no error.
3. **The HTTP task cannot map an empty response.** An endpoint that returns 202 with no body fails the workflow task with a 500 communication error. Return a JSON body.
4. **YAML needs quoting for an object-literal expression.** `${ { instanceId: .x } }` is a YAML parse error; wrap it in single quotes.
5. **`correlate` is evaluated before the listen is registered.** An event arriving earlier is not stored (WP-003 Q02); see 4d.
6. **The reconciler is not optional.** Restored instances are not started without it (WP-003 fact 1). Verified again by `BridgeA_Q2_withoutReconcilerRestoredInstanceStaysIdle`.

## Recommendation

**Use bridge A now: a synchronous HTTP call from the workflow task to an agent endpoint, with the agent in the same app. This confirms ADR 0014. Drop the planned in-process replacement (bridge B). Keep bridge C as the pattern for agent runs that outlive an HTTP call, with the outbox as a requirement.**

Why:

- **Recovery is the same for all three.** Each needs the same reconciler, and each replays the same node set. The recovery property does not favour B or C.
- **B has no glue advantage over A and gives up criterion 6.** B's glue is about 75 lines (definition and bean) against about 67 for A (YAML and endpoints). B removes the HTTP hop but binds the definition to a class, so a later separation changes the workflow. ADR 0010 and the plan already require the agent to be replaceable without touching process definitions.
- **A returns at the interrupt.** The approval wait lives in the workflow's `listen`, not in an open HTTP connection, so a day-long approval does not hold a call. This answers the concern that made ADR 0019 reject synchronous starts.
- **C works, but it has the most moving parts and a race that had to be fixed.** Its callback path was lost in 3 of 10 plain runs; with the outbox it is lost in 0 of 10 (by hand). Use it only where a single agent step can run longer than the acceptable HTTP timeout.

Conditions on A, all required before the bridge goes beyond a spike:

1. **The agent endpoints are not public and do not trust the body.** `/resume` takes only an instance id and loads the context from the run record, but any caller that can reach the port can still resume any instance. Authenticate the call with a workload identity (ADR 0021, M11), and check that the run record belongs to the caller's tenant.
2. **Pass the Flow headers and our correlation id to the agent.** The engine sends `X-Flow-Instance-Id` and `X-Flow-Task-Id` (WP-003 Q10). The spike did not record them; the agent should.
3. **Disable the HTTP client's default retries** for every call to the agent, as the spike does (`max-retries=0`). The agent entry point must be idempotent per thread (WP-003 Q08).
4. **The effect key is the thread name plus the node id.** A real external system must accept it; the effect ledger (WP-108) still needs the intent-then-outcome record and reconciliation for unknown outcomes, which this spike does not test.

## What this means for the plan

Work packages whose assumptions change. Nothing in `docs/plan/` was edited; these are proposals for the owner.

- **WP-105 Run lifecycle and canonical progress (XL).** Confirmed: the reconciler starts restored instances, and the workflow re-runs its in-flight call. The agent does not need its own reconciler for bridge A. Add: the `agent_task` row stores the run status (`RUNNING`, `AWAITING_APPROVAL`, `RESUMING`, `COMPLETED`), which the reconciler uses for bridge C and for detecting stuck runs. Add a `reconciler-restart` audit record for every repair.
- **WP-107 Event correlation and idempotent inbox (M).** Strengthened. Callbacks and approvals must be stored before any listener is registered; a plain callback was lost in 3 of 10 runs (measured). The design needs an acknowledgement and redelivery loop (the outbox in this spike), not only an endpoint. Document the `.data.<field>` correlation path for the JSON event model.
- **WP-108 Effect ledger and compensation (L).** Confirmed for the key shape: `<thread>:<node>` gives exactly-once in effect against a sink that accepts keys. Not confirmed for unknown outcomes; the test uses a same-database sink.
- **WP-109 Timers, deadlines and durable failover (L).** Deadline and budget arrive at the agent but nothing enforces them. Add an enforcement step in the agent node (a check before each model call and before publish) and in the reconciler.
- **WP-106 Human task service (L).** An approval sent before the listen is registered is lost (WP-003 Q02, read as verified). The UI must not approve until the instance reports `awaitApproval` started, or the inbox must hold the approval. Prefer the inbox.
- **WP-201 Agent runtime and graph catalog (L).** The bridge is A. The graph uses `PostgresSaverV2` and the JSON serializer; both verified here across `kill -9`. The `Ctx` record is the agreed input shape. Add the authenticated agent endpoint to the scope.
- **WP-208 Interactive human-agent collaboration (L).** Interrupts survive restart on V2 (`BridgeA_Q2_killWhileWaitingForApproval` and its B and C variants). The approval is correlated per instance (`BridgeA_Q7…`).
- **WP-109 and WP-105 on bridge choice.** No change to the bridge choice for those; the reconciler cost is the same for all three.

## Open questions

1. **One unexplained failure.** In one full run, `BridgeC_Q2_agentKilledMidStep` completed the workflow but the reconciler's "restarting agent run" log line was absent. The test then checked the log and failed. The check now reads the `reconciler-restart` row, and four later full runs and one single-test run all passed. The root cause is not established: the most likely explanations are a timing gap in the reconciler (restart runs after a fixed one-second sleep) or the completion coming from the engine re-running the in-flight `resumeAgent` call. Needs a loop of 20 runs with the row and the lifecycle captured on each.
2. **Separate-process behaviour is not run.** Every bridge runs the agent in the workflow's JVM, so a `kill -9` of the agent is a `kill -9` of the engine. The case "agent dies, workflow survives" is untested for all three. It is the case criterion 6 cares about.
3. **Authentication of the agent endpoints.** Not tested. The spike has no identity provider and no workload identity. Needs WP-006 (policy and identity) before any bridge is used outside the spike.
4. **Deadline enforcement and budget.** Both arrive; neither is enforced. The engine's own task timeout (WP-003 Q03) is not wired to the agent.
5. **Retry defaults on A.** The HTTP client retry is turned off in this spike's configuration. The default-retry duplicate was measured in WP-003 Q08 but not for the agent call here.
6. **Callback redelivery bounds.** The outbox redelivers every 300 ms with no limit or backoff. A real version needs a limit, a dead-letter path, and a test for an agent that never completes.
7. **The listen output shape.** The array-or-object output of a listen task (engine fact 1) needs a check in WP-003's successor test suite, so an upgrade does not change it silently.

## Hardening (issue #9)

Two failure paths in the spike, fixed on branch `issue-9-wp005-hardening`. Each fix was preceded by a failing test.

1. **Readiness after a failed recovery.** The reconciler set `ready` in a `finally` block, so a failed recovery still opened the API. Readiness is now set only when an attempt completes. Recovery is retried at most `spikec.reconcile.max-attempts` times (default 3), `spikec.reconcile.retry-ms` apart (default 2000). A retry skips the instances and runs an earlier attempt already handled. After the last failed attempt readiness stays false and nothing retries again; a restart is needed. A new instance is refused with 503 and an RFC 9457 problem body, before any registry row is written. `spikec.reconcile.fail-attempts` forces the first attempts to fail; it is for tests and is zero by default.
   - Tests, `ReadinessHardeningIT` (process level): `forcedRecoveryErrorKeepsReadinessDownAndRejectsNewInstances` checks readiness 503 after every attempt and later, the refused POST and that no row exists; `readinessOpensOnlyAfterARetrySucceeds` forces one failure and checks that attempt 2 of 3 opens readiness and a start is then accepted.
   - Before the fix: `expected: <503> but was: <200>`, and the retry test timed out waiting for a retry that never ran.
2. **Resume failures replayed the graph.** `ResearchGraph.run` caught every `RuntimeException` from the resume and started the graph again from its input, so a checkpoint-store, serializer or node failure re-ran the draft (a model call and a step). The saver is now asked first with `BaseCheckpointSaver.get`. Only `Optional.empty()` means the checkpoint is missing, and only then does the fresh start run. Every other failure propagates.
   - Tests, `ResearchGraphResumeIT` (in process, against the database in `SPIKEC_JDBC_URL`): `CheckpointStoreFailureOnResumeIsPropagatedWithoutReplay`, `SerializerFailureOnResumeIsPropagatedWithoutReplay` and `NodeFailureOnResumeIsPropagatedWithoutReplay` each inject one fault and assert that the exception surfaces and that the draft and publish step counts and the effect count do not change. The saver and serializer faults are transient on purpose: a persistent fault also breaks the fresh start, which hides the replay. Controls `ResumeWithoutFaultCompletesWithoutReplay` and `PositivelyMissingCheckpointStartsFresh` pass before and after.
   - Before the fix all three failed with `Expected java.lang.RuntimeException to be thrown, but nothing was thrown`. The fallback had swallowed the failure and run the graph again.
3. **Configuration.** The spike listens on port 18196 (the YAML callbacks and the harness were moved from 18195). `SPIKEC_JDBC_URL` selects the database, default `spike_c`. The test seams (`saverDecorator`, `serializer`, `nodeFault`) are package-private and have no effect in production.

The 21 original tests are unchanged in meaning; the new tests add 7. Not covered: a kill -9 during the resume itself, a real database outage (only injected faults), and a retry after a failure in the middle of an attempt, which the retry rule allows but no test forces.

## Commands run

*Historical: these ran against the spike code, since removed (issue #30).*

```
git merge --no-edit wp-003-spike-flow            # fast-forward, already contained in HEAD
PGPASSWORD=postgres psql -h localhost -U postgres -c 'CREATE DATABASE spike_c'
mvn -B -q -pl oa-contracts,oa-runtime,oa-testkit -am install -DskipTests    # exit 0
mvn -B -q dependency:resolve -f spikes/spike-c-bridge/pom.xml              # exit 0
mvn -B -q package -DskipTests -f spikes/spike-c-bridge/pom.xml             # exit 0 (last build before the final verify)
mvn -B verify -f spikes/spike-c-bridge/pom.xml                             # exit 0: 21 tests, 0 failures, 0 errors
```

Earlier full verify runs in this session, before the final code change, failed as follows: run 1 with 2 bridge C failures (lost callback); run 2 with 6 bridge C failures (HTTP 202 with empty body); run 3 with 1 failure (the reconciler log check in open question 1). Each was fixed in code and rerun. The final run covers the code as committed.

Database: `spike_c` on localhost:5432, schemas `public` (Flow tables) and `spikec` (spike tables). Databases `agentic`, `spike_a` and `spike_b` were not touched. Port: 18195 (8081 not used). Both values are the original run's; issue #9 moved the port to 18196 and made the database configurable (see Hardening below).
