# WP-201 handoff: graph node interceptor (Claude)

Status: implemented, tested, PR open. Merged #59 (WP-106) first; this is the single Claude PR after it.

## Behavior added
`NodeInterceptor.wrap(name, NodeAction)` wraps a LangGraph4j node for one run:
- every attempt (including failures and retries) is counted against `maxSteps`; the node after the limit is refused and never runs;
- `agent.node.started` must be recorded before the node runs, and `agent.node.completed` before its result is returned. A failed write refuses the step (the result of an unrecorded step is dropped);
- a throwing node is recorded as `agent.node.failed` (best effort) and its exception is rethrown unchanged;
- a limit refusal is recorded as `agent.node.refused`; if that write fails the refusal stands;
- events carry run id, node name, step number and an exception class only, never node input or output.

## Evidence
- `NodeInterceptorTest`: 9 tests (ordering and results, limit, failing-attempts-count, exception passthrough, started/completed/refused audit failure, no payload in audit, limit validation).
- Mutation check: disabling the limit failed 3 tests; restored.
- Full `oa-runtime` suite: 444 tests, 0 failures/errors (PostgreSQL 16 running).

## Not done
- `ResearchAgent` is not wired to it: the compiled graph is shared across runs and the interceptor is per run, so wiring needs a per-run graph or the run id/actor carried in state. Needs the approved agent context (`ApprovedModelContext`), which production does not provide yet.
- Policy per node (tool/model) stays in the PEP boundaries the node calls; this class does not replace them.
- Checkpoint state-schema versioning and the checkpoint migration are still open: they need a migration number from GPT (V73/V74 are in GPT's #60, V75 audit binding is proposed).

## Next action
Reviewer checks the exact head SHA. Then: wire per-run interceptors into the graph once the approved agent context exists.
