# WP-108: fail-closed composite execution fence

Status: implementation increment for review. It adds a building block; it does not enable production execution.

## What this adds

`CompositeExecutionFence` (oa-runtime, `runtime.lifecycle`) implements `ResourceExecutionFence` as a fail-closed
composition of five mandatory check categories, run in fixed order and stopping at the first refusal:

1. `IDENTITY` — the pairing-aware live identity check on the SAME admitted credential.
2. `POLICY` — current PDP decision.
3. `TASK_AND_TARGET` — actual task and approved target.
4. `OWNER_AND_LEASE` — registry, full owner, definition and execution ownership.
5. `AUDIT` — mandatory central audit receipt.

Rules covered by `CompositeExecutionFenceTest` (10 cases, no database):

- A missing category cannot build; there is no default allowance, partial composition or duplicate category.
- A refusal is propagated as-is; any other failure or unavailable dependency becomes a `RefusedException` whose
  message names the category only (detail stays in the cause).
- An interrupted thread refuses before any check and after the last one, and the interrupt flag is preserved.
- Serialization is rejected and `toString` is redacted, matching the gate's transient-control rules.

## First concrete delegate: `InstanceRegistryFence`

`InstanceRegistryFence` is the registry part of `OWNER_AND_LEASE`. Fresh on every call it requires the instance row to
exist, be `RUNNING`, and agree with the entry scope on tenant, full owner (kind and id), engine instance id, and
definition (`namespace/name` plus version). Cancelled, finished, waiting or replaced instances, a different owner, a
missing engine id and a failing lookup all refuse; the lookup cause stays out of the message. 11 DB-free tests, including
composition as the `OWNER_AND_LEASE` check. It does not establish replica execution ownership (a lease), which stays open,
so it must not be presented as the whole check. The `namespace/name` mapping is an assumption taken from `DefinitionRef`;
confirm it against the production scope builder.

## What this does not do (still open WP-108 gates)

- The trusted production composition root that sources the expected workload, approved target and task from
  server-trusted pinned facts, and constructs the delegates. Missing mapping must refuse.
- Concrete delegates for POLICY (needs reviewed Cedar rules for resource entries), TASK_AND_TARGET (needs the server-trusted
  approved-target source), the lease half of OWNER_AND_LEASE and AUDIT; only identity pairing and the registry half exist.
- Rechecks after blocking I/O at every model/tool node and output release, actual Flow/agent dispatch, private async
  propagation and UNKNOWN-outcome recovery.

The gate still owns the single fresh durable entry. No CDI default is enabled and nothing here is wired into app-runtime.
Review request: confirm the category set and ordering before concrete delegates are written against it.
