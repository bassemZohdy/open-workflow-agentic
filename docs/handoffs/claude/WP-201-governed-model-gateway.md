# WP-201: ModelGateway over the governed model boundary

Status: implementation increment for review. Not wired as a CDI bean and not yet used by the agent graph.

## Why

WP-201 asks to converge `LlmClient` and the `ModelGateway` contract into one governed model boundary. Today the contract
(`ModelGateway`, sealed `ModelResult`, fail closed) had no implementation, while the real governed path
(`GovernedLlmClient`: approved context, zero-budget refusal, audited PEP decision, one attempt) signals with exceptions.

## What this adds

`GovernedModelGateway implements ModelGateway` (oa-runtime, `runtime.agent`). It adds no authority and no second policy
path; every call goes through `GovernedLlmClient`. It translates:

| Situation | Result |
|---|---|
| no approved context, zero budget | `Rejected` (DENY, fail-closed policy ref), provider and policy untouched |
| call for another task than the approved one | `Rejected`, nothing asked, provider untouched |
| call naming another model than the approved one | `Rejected` (never switches model) |
| policy DENY or ERROR, audit failure | `Rejected` carrying the refusing decision, provider untouched |
| provider failure | `Failed` with a fixed reason (provider message is not copied) |
| cost function throws or returns negative | `Failed` (never `Completed` at an assumed zero) |
| audited ALLOW and success | `Completed(text, cost)` |

Cost has deliberately no default: the narrow provider port reports no usage, so the composition root must state it
(`ToLongBiFunction<ModelCall,String>`); an implicit zero would understate spend.

10 DB-free tests cover each row, including that refused calls make zero provider calls.

## Limits stated, not assumed

- `ModelCall.maxOutputTokens` is not enforced; the narrow provider port takes no output bound.
- The prompt is sent as user text with an empty system text.
- The approved context is read on the calling thread; the call must be synchronous on it.
- The reservation-aware, execution-controlled variant (`complete(Running, expected, ...)`) is not exposed here.

## Node interceptor: wall-clock limit

`NodeInterceptor` already counted steps and audited every node; `ExecutionLimits.maxWallClock` was not enforced. It now
takes an optional deadline (`startedAt + maxWallClock`, fixed at construction) and `NodeInterceptor.forLimits(actor,
runId, ExecutionLimits, audit, clock)` applies both bounds. A node admitted at or after the deadline is refused with
zero runs, an `agent.node.refused` record (best effort, the refusal stands if it fails) and no step used. A node already
running is not interrupted (nothing here can stop it); the next node is refused. Six new tests cover exact-deadline,
just-before, an overrunning node, a failing refusal record, `forLimits` and invalid input.
`maxCostMicros` is not enforced by the interceptor: cost is charged where the model call is priced.

## Node interceptor in the production graph

`ResearchAgent` now builds a fresh `NodeInterceptor` per run (its counter and deadline belong to one run) from the
approved context: the approved agent as actor, the approved task's `ExecutionLimits`, the thread id as run id, and the
central `AuditSink`. The graph is compiled per such run with the interceptor wrapping `plan`, `draft` and `review`.
Without an approved context, or with no audit sink set (tests that set fields directly), the shared unwrapped graph is
used and behaviour is unchanged; with the default context a run still refuses at its first model call. The interceptor
audit (`agent.node.*`) is mandatory for started/completed: a failed write refuses the node. Four database-backed tests
(`ResearchAgentNodeLimitsTest`, CI only) cover audited nodes, the iteration limit stopping before the third provider
call, per-run counters, and the unwrapped no-context path.

## Versioned checkpoint state

The checkpoint TABLES were pinned (`CheckpointProfileVerifier`), but the STATE a run keeps between nodes had no version.
`ResearchStateVersion` adds `state.version` (current 1, oldest supported 1). A new run records the current version; before
a stored run is continued, `ResearchAgent.run` reads the last checkpoint and refuses (`CheckpointStateIncompatibleException`,
fixed codes `newer` / `too-old` / `malformed`, no state values in the message) before any node or provider call. A state
with no marker was written before versioning and is version 1, so existing checkpoints stay continuable. Nine DB-free
tests cover the rule, including explicit bounds for a future bump; three database-backed tests
(`ResearchAgentStateVersionTest`, CI only) cover the written marker and the newer and unreadable refusals.
Rule for the next bump: raise `CURRENT`, keep `OLDEST_SUPPORTED` only as far back as a test proves, and add a
compatibility test per supported version first.

## Still open for WP-201

- Governed graph/catalog patterns (tool use, plan-and-execute, supervision).
- The governed graph/catalog patterns (tool use, plan-and-execute, supervision); only the plan/draft/review graph
  exists. Restore-time migration between state versions (today a different version is only refused, not converted).
- Moving agent code from `GovernedLlmClient` to this port, and the composition root that supplies context and cost.
