# ADR-0023: Workflow-to-agent bridge

**Status:** Proposed  
**Date:** 2026-10-18

## Context

ADR 0014 started the bridge as an OWS `call: http` to `POST /agents/research` and planned to replace it with an in-process step once the contract was stable. WP-005 (Spike C, [findings](../spikes/WP-005-workflow-to-agent-bridge.md)) built three bridges around one agent and tested each against real processes killed with `kill -9`:

- **A:** HTTP call from the workflow task to an endpoint in the same application.
- **B:** Java DSL function calling a CDI bean in the same JVM.
- **C:** asynchronous start, with callback CloudEvents that wake `listen` tasks.

Four results decide the bridge:

1. **Recovery is the same for all three.** A restored Flow instance is not started by the engine (WP-003 fact 1). Our reconciler must start it, and the workflow then re-runs its in-flight call. The agent resumes from its checkpoint. The external effect is written once, because its key is deterministic (`<thread>:<node>`); without the key it is written twice. (`BridgeA_Q2_agentKilledMidStep`, `BridgeB_…`, `BridgeC_…`, `BridgeA_Q3_naiveEffectWithoutKeyIsDuplicated`.)
2. **A callback without an inbox is lost.** Plain callbacks sent before the workflow registers its listen were lost in 3 of 10 normal runs. With an outbox that is redelivered until acknowledged, 0 of 10 were lost (measured by hand). (`BridgeC_Q5_plainCallbackBeforeListenIsLost`, `BridgeC_Q5_outboxRecoversCallbackSentBeforeListen`.)
3. **Bridge B binds the definition to the agent class.** Moving the agent to another process would change the workflow definition. (read in source.)
4. **Bridge A returns at the interrupt.** The approval waits in the workflow's `listen`, not in an open HTTP call. (`BridgeA_Q2_killWhileWaitingForApproval`.)

The brief for this ADR also has to answer ADR 0010 (agent loops stay in LangGraph4j) and ADR 0019 (the instance lifecycle is ours).

## Decision

1. **The workflow calls the agent with a synchronous HTTP request to an endpoint of the same deployable, through one agent port.** This is the bridge for bounded agent steps: a call that returns at the agent's interrupt or its end. The request carries the full context (tenant, user, instance id, correlation id, deadline, budget, topic) in the body and the correlation id in `X-Correlation-Id`. The resume call carries only the instance id; the agent loads the rest from its run record.
2. **The agent endpoints are not trusted by the body.** Before the bridge leaves the spike, each call is authenticated with a workload identity (ADR 0021, M11) and checked against the tenant of the run record. The endpoint is not reachable from outside the deployable.
3. **Every call from a workflow task to the agent has retries off at the engine level** (`quarkus.flow.http.client.resilience.retry.max-retries=0`). Retries are the OWS `retry` policy, and only for calls whose effect is recorded in the effect ledger (ADR 0019). The agent entry points are idempotent per thread: a call for a thread that exists continues it.
4. **Effects use a deterministic key `<thread id>:<node id>`** and are written through the effect ledger (WP-108). The ledger keeps an intent record before the call and an outcome after it. The key is only as good as the target's acceptance of it; a target that does not accept keys is not used for effects.
5. **Long-running agent steps use the asynchronous pattern (bridge C) only when one step can exceed the acceptable call timeout.** That pattern requires an outbox in the agent, redelivery until the workflow acknowledges each callback, and an acknowledgement task in the workflow definition. A plain callback without an outbox is not allowed.
6. **Approvals and callbacks are correlated per instance,** on the `instanceId` field of the event data (`correlate` with `from: ${ .data.instanceId }`). An approval is sent only after the instance reports its approval `listen` has started, until the inbox of WP-107 stores events before listeners exist.
7. **The reconciler (ADR 0019) starts restored instances and restarts agent runs that are recorded as `RUNNING` or `RESUMING`.** For bridge A the reconciler does not restart the agent itself; the workflow's re-run of the in-flight call does that.

This ADR **confirms** ADR 0014's call shape and HTTP contract. It **does not** adopt the planned replacement by an in-process step (bridge B), because that replacement would couple the workflow definition to the agent class and cannot move the agent out of process later without changing the definition (criterion 6). ADR 0014 is not edited here.

## Consequences

- The agent is a separate contract (the endpoint and its JSON) that the workflow depends on. It can be moved out of process by changing a URL and deploying the endpoint elsewhere, without changing the definition's task structure.
- Each bounded agent step needs an authenticated endpoint, a run record, and a reconciler step before it is safe. These costs apply to all three bridges, so they do not decide between them.
- Bridge C remains available for long agent steps, at the cost of an outbox, redelivery, an acknowledgement task in each definition, and a callback endpoint with its own authentication. It is not built until a work package needs it.
- The effect ledger's exactly-once claim holds only for targets that accept idempotency keys. A call whose answer is lost still leaves an unknown outcome, which the ledger must reconcile (WP-108); this spike does not test that.
- Some engine behaviour is now an explicit rule for definition authors: listen outputs can be arrays (use `if type == "array"`), the JSON event model is the whole CloudEvent (use `.data`), an HTTP call needs a JSON response body, and object-literal expressions in YAML are quoted.
- Approvals sent before a listen is registered are lost until WP-107 stores events. The UI must not approve early.
- Deadlines and budgets reach the agent but are not enforced by any bridge. WP-109 owns enforcement.

## Alternatives considered

- **Bridge B, in-process CDI function (replace ADR 0014 now).** Rejected for now. Recovery and tests are no better than A, its glue is no smaller (about 75 lines against about 67), and the definition binds to the agent class. Moving the agent out of process later would change the definition. Revisit only if a measured latency or cost problem appears on the HTTP hop.
- **Bridge C, asynchronous start for every step.** Rejected as the default. It has the most moving parts (about 135 lines of agent glue, an outbox, a redelivery timer, an acknowledgement task in each definition), and the callback path was the only one that lost events in the spike. Kept for steps that outlive the call timeout.
- **Synchronous HTTP with the agent in another process from the start.** Rejected for the spike. It needs a deployed second process and a reliable network path before the recovery behaviour can be tested, and the spike has not run that case (open question 2 in the findings).
- **Plain callbacks without an outbox.** Rejected. Measured: 3 of 10 normal starts lost the callback and waited forever.
- **Rely on the engine's restore and periodic scan to restart instances.** Rejected in ADR 0019 and confirmed again here: the restored instance is not started without a call to `start()`.
