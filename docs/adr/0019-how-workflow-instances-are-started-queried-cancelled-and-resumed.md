# ADR-0019: How workflow instances are started, queried, cancelled and resumed

**Status:** Accepted  
**Date:** 2026-10-18

## Context

WP-003 (Spike A, [findings](../spikes/WP-003-quarkus-flow.md)) tested Quarkus Flow 1.2.0 against the requirements that decide the run lifecycle. Four results drive this decision:

1. **Quarkus Flow ships no instance API.** Start, query and cancel must be written by us on top of the engine (Q01, Q05).
2. **A restart does not resume waiting work by itself.** After a `kill -9`, a waiting instance is restored into memory as PENDING and is not started. It does not resume on the next event, and an in-flight task is not run again, until our code calls `start()` on it (Q02, Q11). The periodic restore scan does not fix this, because it skips instances already registered as active.
3. **The persisted row is incomplete for our needs.** A waiting instance has no persisted status; completed and cancelled instances are deleted from the Flow tables; the row is keyed by application and instance id, not by definition version (Q04, Q05).
4. **Two retry layers exist.** The Flow HTTP client retries failed calls three times by default, below the OWS retry policy, so a non-idempotent call can run more than once (Q08).

M28 requires one canonical progress model (ADR 0010 and the plan, section 2). For process progress the plan names the Flow instance as canonical. The spike shows that the Flow instance alone cannot be the source of truth across a crash, so the runtime must own the instance record and drive the engine from it.

## Decision

We own the instance lifecycle through a small instance registry in our own schema, and use Quarkus Flow only to execute.

**Start.** `POST /instances` with `{definition id, version, input, idempotency key}`. The runtime:
1. writes an `instance` row in our registry (status `STARTING`, definition id and version, input reference, idempotency key) in the same transaction as the request;
2. calls `definition.instance(input).start()` on the version named in the row;
3. records the engine instance id on the row and sets status `RUNNING`.

The request returns `202 Accepted` with the instance id. It does not wait for the instance to finish. A repeated request with the same idempotency key returns the existing instance and starts nothing. The public instance id is our registry-generated ULID, created before the engine is involved so that the idempotent insert comes first. The engine's instance id is stored separately on the row.

**Query.** `GET /instances/{id}` reads our registry row, never the Flow tables. The row carries the definition version, our status, the last engine status we observed, and the links to the audit and task records. History of completed or cancelled instances comes from our records, because Flow deletes its rows. A lookup by id is scoped by tenant and id. ULIDs are unique, so the id is the key within a tenant. The definition id and version are columns on the row, not part of the lookup key.

**Cancel.** `POST /instances/{id}/cancel` marks the row `CANCEL_REQUESTED`, then calls `cancel()` on the in-memory instance if it is active. The API returns `202` and treats cancellation as asynchronous: the engine removes the instance about 100 to 250 ms later (Q05). A cancel of an instance with no engine instance id ever recorded goes straight to `CANCELLED`. Otherwise the registry row becomes `CANCELLED` only when the engine reports the cancelled event, and the reconciler finishes the cancel after the attempt limit.

**Retry of a failed instance.** A retry of a `FAILED` instance creates a new instance linked to the old one (issue #23). `FAILED` stays terminal, and the old row is not reopened. Until #23 lands, the operations API retry applies to `STARTING` rows only, and a retry of a `FAILED` row gets 409.

**Resume after restart.** At startup and periodically, a reconciler reads registry rows in status `RUNNING` or `WAITING` whose engine instance is not active in memory, and for each one calls the engine start path on the restored object (`activeInstance(id)` then `start()`). This is the workaround verified in `Q02_listenResumesAfterKill9WhenStartedThroughApi` and `Q11_killDuringInFlightTaskRerunsThatTask`. Completed tasks are skipped by the engine; the first unfinished task runs again, so every external call in that task must be idempotent (ADR 0010, effect ledger WP-108).

**Retries.** The Flow HTTP client default of three retries is set to zero in configuration, so the only retry is the OWS `retry` policy in the definition. This is our rule, not an engine guarantee: a definition may use the OWS retry only for a call whose effect is recorded in the effect ledger with an idempotency key (WP-108). A definition review checks this rule; the engine does not enforce it.

**Deadlines.** The absolute deadline of a wait is stored on the registry row when the wait starts. The reconciler enforces it, because a restored engine timer restarts from resume (Q03). The engine's own timeout remains as a second guard. When the deadline has passed and the engine holds the instance, the cancel request is recorded on the row first, so that the engine's cancelled report finds it; then the engine instance is cancelled. If the engine holds no instance, the row becomes `FAILED`.

**Events.** Events for `listen` are stored by our inbox before any listener is registered (WP-107). The engine's in-memory event registration is used only to wake a listener that is already waiting.

## Consequences

- The runtime owns the lifecycle state. The engine is an executor under our control, and replacing it stays possible (R1 in the plan).
- A reconciler is mandatory before any restart test passes. Restart correctness depends on our code, not on the engine.
- Every write to the registry and the call to the engine are separate steps. A crash between them is repaired by the reconciler; the start request is idempotent through the idempotency key.
- Operators see one record per instance, with history, even though the engine deletes its own rows.
- The API surface is ours to version and document. There is no engine API to rely on for compatibility.
- The restore gap and the retry default belong to Quarkus Flow 1.2.0. They must be re-tested on every upgrade (WP-402), and the workarounds removed if the engine fixes them.

## Alternatives considered

- **Rely on the Flow restore and the periodic scan.** Rejected: measured. The scan skips the restored instance, and the restore never starts it (Q02, Q11).
- **Use only the Flow tables as the source of truth.** Rejected: the engine deletes completed and cancelled rows, has no persisted waiting status, and keys rows without the definition version (Q04, Q05).
- **Use the engine's `WorkflowPersistenceInstance` restore API directly, without our registry.** Rejected: it leaves the lifecycle in the engine's hands and gives no history or version pinning.
- **Keep the default HTTP client retry and rely on the idempotency of each endpoint.** Rejected: measured, a non-idempotent POST ran three times with no workflow-visible retry (Q08). Idempotency cannot be assumed for every downstream service.
- **Start instances synchronously and wait for completion in the request.** Rejected: waits can last hours or days (human approvals), and the request would hold a connection for the whole wait.
- **Use the Kubernetes lease (Q07) as the only restart mechanism.** Rejected for now: its behaviour is read in docs only and the lease expiry and renewal values are not documented. Revisit after the cluster test in WP-109.

## Amendments (2026-10-19)

The owner decided the five points below on 2026-10-19. The Decision text above now states them. The text as proposed on 2026-10-18 is kept in this file's git history, and each point gives the earlier wording.

1. **Public instance id.** Was: the engine's monotonic ULID is the external id. Now: the public id is our registry ULID, and the engine's id is stored separately on the row as `engine_instance_id`. Why: the start writes the row, with its idempotency key, before the engine is prepared, so the id has to be ours. Status: implemented in WP-105a (`docs/lifecycle.md`, section 3).
2. **Lookup scope.** Was: a query by id is scoped by definition id and version. Now: scoped by tenant and id, because ULIDs are unique; the definition id and version are columns on the row. Status: implemented in WP-105a (`GET /instances/{id}` returns 404 for another tenant's id).
3. **Retry of a `FAILED` instance.** New decision. A retry creates a new instance linked to the old one, and `FAILED` stays terminal. Status: decided, not yet implemented. Tracked in issue #23. Until it lands, the operations API retry applies to `STARTING` rows only, and a retry of a `FAILED` row gets 409.
4. **Cancel with no engine instance id.** Was: `CANCELLED` only when the engine reports the cancelled event, with a reconciler moving rows stuck in `CANCEL_REQUESTED`. Now: a cancel of an instance with no engine instance id ever recorded goes straight to `CANCELLED`; otherwise the reconciler finishes the cancel after the attempt limit. Status: decided, not yet implemented. Tracked in issue #24. The current code leaves such a row in `CANCEL_REQUESTED`.
5. **Missed wait deadline.** Was: the reconciler enforces the deadline, without a rule for the engine's state. Now: the cancel request is recorded on the row before the engine instance is cancelled, and the row becomes `FAILED` if the engine holds nothing. Status: accepted as implemented. The implementation is on the WP-105c branch (`wp-105c-reconciler`) and is not yet on main.

Items 3 and 4 are decided but not yet implemented. No other part of this ADR is changed by the owner's decisions.
