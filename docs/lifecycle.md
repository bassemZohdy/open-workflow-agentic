# Run lifecycle and the instance registry (WP-105, slice 105a)

**Status:** WP-105a implemented; WP-105b (outbox and projection) implemented, see section 9 · **Decisions:** [ADR 0019](adr/0019-how-workflow-instances-are-started-queried-cancelled-and-resumed.md), [ADR 0020](adr/0020-checkpoint-scoping-and-state-evolution.md), [ADR 0023](adr/0023-workflow-to-agent-bridge.md) · **Plan:** [section 2, canonical progress](plan/README.md)

This slice makes the platform's own record of a run authoritative for lifecycle status, definition version and request idempotency. Quarkus Flow executes the pinned definition; it is not the record of what was asked for or what state the run is in. Slices 105c (reconciler) and 105d (operations API) are not built here; the outbox and projection of 105b are in section 9. Section 6 lists the seams they use.

## 1. Tables (schema `task`, migrations V10 to V13)

| Table | Row | Notes |
|---|---|---|
| `task.instance` | One workflow instance | Id is our ULID. Unique `(tenant, idempotency_key)` and unique `engine_instance_id`. Index on `(status, updated_at)` for the reconciler. |
| `task.agent_task` | One agent task (ownership row, ADR 0020) | Id is the task contract id. Unique `thread_name` (the derived `ThreadId`). FK to the instance. Holds the contract as JSON. `graph_version`, `state_schema_version` and `latest_checkpoint_id` are nullable and written by later slices. |
| `task.instance_event` | One history row per change of an instance | Primary key `(instance_id, version)`. `version` is the row version after the change, so history runs 0, 1, 2 with no gaps. |
| `task.agent_task_event` | One history row per change of an agent task | Same shape, keyed by `(agent_task_id, version)`. |

Every row has a `version` for optimistic locking. Both event tables are **append-only in the database**: a trigger refuses `UPDATE`, `DELETE` and `TRUNCATE` for any role (V13). A history row is written in the same transaction as the change it records.

Columns of `task.instance`: `id`, `tenant`, `owner_kind`, `owner_id`, `idempotency_key`, `request_fingerprint` (SHA-256 of the definition id, version and canonical input), `definition_id` (`namespace/name`), `definition_version`, `input` (JSON, at most 64 KiB), `status`, `engine_instance_id`, `start_attempts`, `last_error` (exception class name, never its message), `wait_deadline` (nullable, ADR 0019 Deadlines), `version`, `created_at`, `updated_at`.

## 2. State machines

Both are pure code in `io.github.bassemzohdy.openworkflow.agentic.runtime.lifecycle` (`InstanceStateMachine`, `AgentTaskStateMachine`). An illegal change throws `IllegalTransitionException`. A terminal state has no exit. A change that keeps the same status is not a transition: the store records it in history with `from` equal to `to` (used for a failed start attempt and for the engine id). `StateMachineTest` checks every pair of states against the edge list written out as literals.

### 2.1 Instance (registry) status

```mermaid
stateDiagram-v2
    [*] --> STARTING: request accepted (row inserted)
    STARTING --> RUNNING: engine accepted the start
    STARTING --> FAILED: start abandoned (reconciler, WP-105c)
    STARTING --> CANCEL_REQUESTED: cancel before the start was confirmed
    RUNNING --> WAITING: listen or human task registered
    RUNNING --> COMPLETED
    RUNNING --> FAILED
    RUNNING --> CANCEL_REQUESTED
    WAITING --> RUNNING: event or decision received
    WAITING --> COMPLETED
    WAITING --> FAILED: deadline passed
    WAITING --> CANCEL_REQUESTED
    CANCEL_REQUESTED --> CANCELLED: engine confirmed the removal
    CANCEL_REQUESTED --> COMPLETED: finished before the cancel took effect
    CANCEL_REQUESTED --> FAILED
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

| State | Meaning |
|---|---|
| `STARTING` | Registered. The engine has not confirmed the start. A failed attempt keeps the row here with `start_attempts` and `last_error`. |
| `RUNNING` | The engine accepted the start. Waiting and completion are reported by the engine observer (105b). |
| `WAITING` | The instance waits for an event or a human decision. Written by 105b. |
| `CANCEL_REQUESTED` | A cancel was asked for; the engine removal is asynchronous (WP-003 Q05). |
| `CANCELLED`, `COMPLETED`, `FAILED` | Terminal. |

### 2.2 Agent task status

The contract's `AgentTaskStatus` names the states. `PENDING` is the created state and the only one a new row may have. `RESUMING` was added for ADR 0023 decision 7; `WAITING_FOR_HUMAN` keeps the name the contract already uses.

```mermaid
stateDiagram-v2
    [*] --> PENDING: task created (insert, version 0)
    PENDING --> RUNNING
    PENDING --> FAILED
    PENDING --> CANCELLED
    RUNNING --> WAITING_FOR_HUMAN: interrupt at a checkpoint (M05)
    RUNNING --> COMPLETED
    RUNNING --> FAILED
    RUNNING --> CANCELLED
    WAITING_FOR_HUMAN --> RESUMING: human decision received
    WAITING_FOR_HUMAN --> FAILED: deadline passed
    WAITING_FOR_HUMAN --> CANCELLED
    RESUMING --> RUNNING: graph resumed from its checkpoint
    RESUMING --> WAITING_FOR_HUMAN: interrupted again
    RESUMING --> FAILED
    RESUMING --> CANCELLED
    COMPLETED --> [*]
    FAILED --> [*]
    CANCELLED --> [*]
```

Why these states. `RESUMING` separates "interrupted, waiting for a decision" from "resumed and executing", so the reconciler can restart a resumed run after a crash without confusing it with a waiting one (ADR 0023). `WAITING_FOR_HUMAN` is entered only from `RUNNING` because an interrupt happens inside a step. `PENDING` has no direct edge to `WAITING_FOR_HUMAN` or `COMPLETED`: a task must run before it can wait or finish.

## 3. Start sequence (ADR 0019, Start)

```mermaid
sequenceDiagram
    participant C as Client
    participant R as InstanceResource
    participant S as InstanceService
    participant DB as task.instance
    participant E as Flow engine
    C->>R: POST /instances (key, definition, version, input)
    R->>S: start(StartRequest)
    S->>E: hasDefinition(definition, version)?
    alt not held
        S-->>R: DefinitionNotFoundException
        R-->>C: 422, no row
    end
    S->>DB: tx1: insert STARTING ... on conflict (tenant, key) do nothing
    alt key already exists
        DB-->>S: existing row
        alt same fingerprint
            S-->>R: existing row, created = false
            R-->>C: 200, same body, nothing started
        else different request
            S-->>R: IdempotencyKeyReusedException
            R-->>C: 409
        end
    else inserted
        S->>E: prepare: definition.instance(input) gives the engine id
        S->>DB: tx2: engine_instance_id set (STARTING, history)
        S->>E: start() (not awaited)
        alt start accepted
            S->>DB: tx3: RUNNING (history)
            R-->>C: 202, Location, status RUNNING
        else prepare or start threw
            S->>DB: STARTING, start_attempts + 1, last_error (history)
            R-->>C: 202, status STARTING
        end
    end
```

Points that matter:

- **One engine start per key.** Only the request whose insert succeeded calls the engine. Concurrent identical requests wait on the unique key and then see the row. `ConcurrentStartTest` runs twenty threads through a barrier and asserts one row, one prepare, one start and three history rows.
- **The engine id is recorded before the engine runs anything.** `definition.instance(input)` already has the id, so tx2 stores it before `start()`. A crash after `start()` therefore leaves a row that names the engine instance the engine may already have persisted.
- **Our id, not the engine's.** The external instance id is our ULID, generated before the engine is involved, so that the idempotent insert can happen first. The engine id is kept in `engine_instance_id`. ADR 0019, amended on 2026-10-19, makes our ULID the external id; see section 7.
- **Start never waits.** `start()` is called and its future is not awaited. The request returns when the engine has accepted the instance.
- **Reads come from the registry only.** `GET /instances/{id}` never reads the Flow tables and returns 404 for another tenant's id.

## 4. REST contract

| Request | Response |
|---|---|
| `POST /instances` with `{definitionId, definitionVersion, input, idempotencyKey}` and headers `X-Tenant`, `X-Subject` | 202 and `Location` for a new instance; 200 with the same body for a repeated key; 409 for a key used with a different request; 422 unknown definition; 400 malformed; 401 no caller |
| `GET /instances/{id}` | 200 with `{id, definitionId, definitionVersion, status, engineInstanceId, startAttempts, version, createdAt, updatedAt}`; 404 unknown, malformed or another tenant's id; 401 no caller |

Errors are RFC 9457 problem documents with the correlation id. The caller comes from `CallerContextResolver`. It reads `X-Tenant` and `X-Subject` only when `agentic.dev.trust-caller-headers=true`. The default is false, so every instance endpoint answers 401 until WP-102 provides authentication. The flag must not be enabled in a deployment.

## 5. Failure modes

| Failure | What is stored | What happens next | Covered by |
|---|---|---|---|
| Definition not held by the engine | Nothing | 422; no row | `InstanceApiTest.anUnknownDefinitionIsRejectedAndNoRowIsCreated` |
| Same key, different request | Nothing changes | 409 | `InstanceApiTest.theSameKeyForADifferentRequest...` |
| Two identical requests at once | One row | One start; the other gets 200 | `ConcurrentStartTest` |
| Engine `prepare` throws | Row `STARTING`, attempt 1, `last_error` class, no engine id | 202 with `STARTING`. Repeats do not retry. The reconciler finds the row (WP-105c) | `EngineFailureTest` |
| Engine `start` throws | Row `STARTING`, engine id set, attempt 1, `last_error` | Same. The engine may have persisted the instance, so the reconciler must check before it starts again | `EngineFailureTest` |
| Registry write fails before the engine is prepared | Row `STARTING` may exist with no engine id, or no row | The response is 500 with the correlation id. The reconciler can find a `STARTING` row with no engine id and prepare it | not tested (fault injection, WP-110 and later) |
| Crash between tx2 and `start()` | `STARTING` with engine id, Flow has no instance | Reconciler starts the prepared instance by id rather than preparing a new one | not built (105c) |
| Crash after `start()` but before tx3 | `STARTING` with engine id, Flow may have persisted it | Reconciler checks the engine and sets `RUNNING`, or starts it. It must never call `prepare` again for that row | not built (105c) |
| Illegal status change | Nothing | `IllegalTransitionException`, no history row | `InstanceApiTest.anIllegalTransitionIsRejected...` |
| Stale version | Nothing | `StaleVersionException`, reload and decide again | `InstanceApiTest.aStaleVersionIsRejected...` |
| Edit of history | Refused by trigger | SQL error | `InstanceApiTest.historyIsAppendOnlyInTheDatabase` |
| Another tenant reads an id | Nothing | 404; the id is not revealed | `InstanceApiTest.anotherTenant...` |

Failure of a registry write after a successful engine start is the case that needs the reconciler most. The engine then runs an instance the registry does not show as running. That gap is closed in 105c, and until then the row stays `STARTING`, which is visible to any reader.

## 6. What slices 105b, 105c and 105d must add

Seams that exist now:

- `InstanceStore.replace` and `TaskStore.replace` are the only write paths. Each guards identity, version and transitions, and writes history in the same transaction.
- `JdbcTaskStore` implements `TaskStore`, so the runtime's ownership check is `findByThread` before any read, write or resume.
- `FlowWorkflowEngine` is the only class that calls Flow for start. Query and cancel for Flow go through the same adapter.
- The index `instance_status_updated_idx` supports the reconciler's scans.

**105b (outbox and projection)**
- Engine observer: write `WAITING`, `COMPLETED`, `FAILED` and `RUNNING` (resume) from engine events. No code in 105a writes these edges. They are in the state machine already.
- Transactional outbox: a row written in the same transaction as each history row. The writer is `JdbcInstanceStore.replace` and `JdbcTaskStore.replace`. Add a hook to the transaction so the outbox row and the history row commit together. 105a does not add it.
- `task_view` projection fed from the outbox, redelivered until acknowledged. The projection is never a source of truth.

**105c (reconciler)**
- Wait for definition warm-up at startup. Then, periodically and at startup: rows `STARTING` older than a threshold; rows `RUNNING` or `WAITING` whose engine instance is not active in memory, started through `activeInstance(id)` and `start()` (ADR 0019, resume; WP-003 Q02, Q11); rows `CANCEL_REQUESTED` that are stuck; `wait_deadline` passed.
- Agent runs in `RUNNING` or `RESUMING` are eligible for the reconciler when their liveness lease has expired or is missing (ADR 0023, decision 7, amended 2026-10-19: a run is alive while it holds a lease that its worker renews by heartbeat). Bridge A runs are restarted by the workflow instead.
- Every repair is audited. A repair never calls `prepare` for a row that has an engine id.
- Retries: set `quarkus.flow.http.client.resilience.retry.max-retries=0` (ADR 0019 Retries, ADR 0023 decision 3). Not done in 105a; it changes the behavior of the shipped definition's HTTP call and belongs with the reconciler's tests.

**105d (operations API)**
- Inspect, cancel, retry, pause and recover, authorized. Cancel moves `RUNNING` or `WAITING` to `CANCEL_REQUESTED`. A row with no engine instance id goes straight from `STARTING` to `CANCELLED` (ADR 0019, amended 2026-10-19; issue #24). The state machine has no such edge yet. Retry applies to `STARTING` rows only until issue #23 lands: a retry of a `FAILED` row gets 409, and afterwards it creates a new instance linked to the old one (ADR 0019). Each call writes history with the operator as actor.
- Authorization depends on WP-102. Until then the endpoints are not exposed.

**Outside the four slices but needed by the plan**
- The code that inserts `agent_task` rows and sets `graph_version` and `state_schema_version` (WP-201, WP-302). `AgentTaskRecord` has no pinning fields, so the columns are written by SQL there, or the record is extended with a decision.
- Owner-scoped reads (who may read which instance). WP-102.

## 7. Deviations and open points for the owner

These are not ADR edits. The ADRs were left unchanged, as the brief asks.

1. **Our id, not the engine's, is the external id (resolved 2026-10-19).** ADR 0019 first said "The instance id is the engine's monotonic ULID, which we keep as the external id". The start has to insert a row before the engine is prepared so that the idempotency key is decided first, so the external id is ours. The owner confirmed this, and ADR 0019 was amended. The engine id is stored in `engine_instance_id`.
2. **Engine id recorded before `start()`.** ADR 0019 Start lists the steps as insert, start, then record the id and set RUNNING. The Flow API gives the id from `definition.instance(input)`, before `start()`. Recording it first closes a gap after a crash. This is a refinement of the ADR's order.
3. **Query scope (resolved 2026-10-19).** ADR 0019 first said "a query by id is scoped by definition id and version; an id alone is not a valid key". The owner decided the lookup is scoped by tenant and id, because a ULID is unique on its own, and ADR 0019 was amended. `GET /instances/{id}` is scoped by tenant. The definition id and version are columns on the row, not part of the lookup key.
4. **Input stored inline.** ADR 0019 allows "input reference or input". The input is stored inline as `jsonb`, limited to 64 KiB. Larger inputs need a reference, which is a later decision.
5. **Failed start is not `FAILED`.** A failed attempt stays `STARTING`. ADR 0019 does not say what happens after repeated failures. The state machine allows `STARTING -> FAILED`, reserved for the reconciler after it gives up.
6. **Pinning fields absent from `AgentTaskRecord`.** ADR 0020 decision 4 requires the agent task to record graph version and state schema version. The columns exist; the record in `oa-contracts` does not carry them. Adding them changes a public record, so this was not done in 105a. Owner decision (2026-10-19, ADR 0020 amended): the fields are added to `AgentTaskRecord` at WP-302, not earlier.
7. **Retry configuration not applied.** ADR 0019 and ADR 0023 require the Flow HTTP client retry to be zero. Not configured in 105a (section 6).
8. **`RESUMING` added to `AgentTaskStatus`.** ADR 0023 uses the state name. The contract enum did not have it. It is additive, and no existing use changes.

## 8. Not verified

- No fault injection of a registry write between the engine call and the database; the failure rows are produced by the engine test double only.
- The real engine is used for start only. Whether the registry reflects the engine's later states is not tested, because 105b does not exist yet.
- The concurrency test uses twenty threads against one Postgres database on one machine; it shows the unique key works, not behavior under a multi-node deployment.

## 9. Outbox and projection (WP-105b)

Every change of an instance or an agent task writes an outbox row in the same transaction as its history row. A relay delivers the rows to consumers in order. The consumer `TaskViewProjector` keeps `task.task_view`, a read model with one row per instance. The projection is never a source of truth.

### 9.1 Tables (migrations V14 and V15)

| Table | Row | Notes |
|---|---|---|
| `task.outbox` | One event per history row | `id` is a bigint identity, so it gives the delivery order. `aggregate_type` (`INSTANCE` or `AGENT_TASK`), `aggregate_id`, `aggregate_version` (unique with the type), `partition_key` (the instance id, for both types), `event_type` (`instance.created`, `instance.updated` for a change that keeps the status, `instance.status_changed`, and the same three for `agent_task`), `payload` (jsonb: tenant, owner, definition id and version, previous and new status, reason, actor, occurred time), `occurred_at` (equal to the history row's time), `published_at`, `attempts`, `next_attempt_at`, `failed_at`, `last_error` (exception class name, never its message). |
| `task.task_view` | One row per instance | Instance id, tenant, owner, definition id and version, instance status and version, current agent task id, status and outbox id, `waiting_for_human` (generated: instance `WAITING` or agent task `WAITING_FOR_HUMAN`), last reason, last transition time, `last_outbox_id` applied, `updated_at`. |

Writes: `JdbcInstanceStore` (insert and replace) and `JdbcTaskStore` (insert and replace) call `OutboxEntry.writeTo` in their transaction. No other path writes outbox rows. `JdbcTaskStore` first locks the instance row, which serializes the writes of one instance, so the outbox id order of an instance is its commit order.

### 9.2 Sequence

```mermaid
sequenceDiagram
    participant S as Store (instance or agent task)
    participant DB as PostgreSQL
    participant R as OutboxRelay
    participant P as TaskViewProjector
    S->>DB: lock the instance row (agent writes), change the row
    S->>DB: history row and outbox row, commit
    R->>DB: claim a batch: FOR UPDATE SKIP LOCKED, id order, partition not blocked
    R->>P: apply(connection, event), inside a savepoint
    P->>DB: update task_view where last_outbox_id < event id
    R->>DB: published_at = now(), commit (same transaction)
```

The relay runs in each process on a single-thread executor (`OutboxRelayService`, every `agentic.outbox.relay.interval`, default 500 ms). db-scheduler is not used: the relay polls a table, it does not fire at a time, and several processes may poll at once because the claim skips locked rows. Configuration keys: `agentic.outbox.relay.enabled`, `.batch-size` (20), `.max-attempts` (5), `.backoff-base` (1 s), `.backoff-max` (1 min), `.interval`.

### 9.3 Ordering and delivery guarantees

- **Order within an instance.** The relay claims a row only when no earlier row of its partition is unpublished. "Unpublished" includes rows in backoff, failed rows, and rows another relay holds. So a later event of an instance is never delivered before an earlier one. Other instances are not blocked by it.
- **No two relays on one instance.** Two relays cannot claim two rows of one partition at once, for the reason above. `OutboxRelayTest.fourRelaysDeliverEveryRowOnceAndInOrderWithinAnInstance` runs four relays over 300 events of 60 instances and checks that each event is delivered once and each instance's events in version order.
- **At least once.** A row is marked published in the same transaction as the consumer's writes. A crash before the commit redelivers the row. A row can also be redelivered when an operator re-queues it. Consumers must be idempotent (`OutboxConsumer` javadoc).
- **Idempotent projection.** Each view row stores `last_outbox_id`. An event is applied only when its id is greater, in the same statement as the change. A duplicate or an older event changes nothing (`TaskViewProjectionTest`, `OutboxRelayTest.aRowDeliveredTwiceChangesNothingTheSecondTime`).
- **Agent event before its instance.** Not possible in order. If it happens anyway (for example a manual edit), the projector refuses it, the relay retries with backoff, and the row stays unpublished.

### 9.4 Failure and poison rows

- A consumer that throws has its writes for that event rolled back to a savepoint. The row gets `attempts + 1`, and `next_attempt_at` moves by `backoff-base × 2^(attempts−1)`, capped at `backoff-max`.
- After `max-attempts` the row gets `failed_at` and `last_error`. The row stays in the table and is never dropped. It blocks the later rows of its own instance, so the view never shows a later state over an unapplied earlier one. Other instances continue.
- **Operator procedure.** `OutboxAdmin.failures(limit)` lists failed rows (class name of the last error, the time, the event). Fix the cause, then `OutboxAdmin.requeue(id)`. The row's attempts are reset and it is delivered again, in order. `requeue` returns false for a row that is not failed. The HTTP operations API comes in WP-105d.
- **Lag.** Micrometer gauges, read at most once a second: `agentic.outbox.unpublished` (count, failed rows included), `agentic.outbox.unpublished.oldest.age.seconds`, `agentic.outbox.failed`.

### 9.5 The projection

- `TaskViewProjector` is the only consumer in this slice. Instance events set the instance fields. Agent task events set the current agent task fields, so the current agent task of an instance is the one with the latest agent event.
- `updated_at` and `last_transition_at` are the time of the event, not the projector's clock. That is what makes a rebuild comparable with the live view.
- `TaskViewQuery` reads one tenant at a time, filters by status and definition, and pages by instance id (a ULID, so creation order). The cursor is the encoded last instance id. `snapshot()` is for verification.

### 9.6 Rebuild

`TaskViewRebuilder.rebuild()` deletes the view and recreates it in one transaction. It takes `share row exclusive` locks on the outbox and the view, so relays and the projector wait while it runs. It folds the **published** events from the history tables, the same set the projector has applied. An event that is not published yet (in backoff, failed, or still in flight) is in neither the live view nor the rebuild, and it appears in both once it is published.

`TaskViewRebuildTest` deletes every row and rebuilds, then compares the live rows with the rebuilt rows, field by field. `OutboxKillIT` checks the same against the registry after a process restart.

### 9.7 What 105c and 105d may rely on

- `task.outbox` holds every change of an instance and an agent task, in order, from the first insert. Rows are not deleted in this slice (no retention yet).
- `task.task_view` is for reading and for dashboards. Repairs and decisions read the registry and history (ADR 0002). The view may lag by the relay interval plus the backlog, shown by the lag gauges.
- 105c may use `OutboxAdmin.lag()` and `failures()` to detect stuck work. A failed row is not a reason to repair the instance; it is a projection problem.
- 105d may call `OutboxAdmin.requeue` and `TaskViewRebuilder.rebuild`. Rebuild takes locks, so it is a maintenance action, not a request-path action.
- Any new writer of a registry or agent task row must go through the stores and hold the instance row lock, or the partition order breaks.

### 9.8 Not in this slice

- Retention of published outbox rows.
- Consumers outside the database. A consumer must be local to the relay's transaction.
- The engine observer that writes `WAITING`, `COMPLETED` and `FAILED` from engine events (listed in section 6 under 105b; the WP-105b brief did not include it, so `task_view` only shows the states the stores write today).
- A multi-node deployment test. The kill test runs one process at a time against one Postgres. The four-relay test shares one JVM.
