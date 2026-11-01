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

## Still open for WP-201

- Governed graph/catalog patterns (tool use, plan-and-execute, supervision).
- Using `NodeInterceptor` in a production graph (today only tests construct it), and versioned checkpoint state with
  compatibility tests.
- Moving agent code from `GovernedLlmClient` to this port, and the composition root that supplies context and cost.
