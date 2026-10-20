# ADR-0025: Acknowledge inbox delivery only after a durable checkpoint

**Status:** Proposed; implementation and exact-head reciprocal review remain open

**Date:** 2026-10-20

**Owners:** GPT (WP-107 delivery adapter and WP-105 recovery integration); Claude reviews

## Context and observed evidence

An inbox receipt and a matched wake signal do not prove that a workflow consumed
the event durably. After a crash, an in-memory callback may have run without a
checkpoint being committed. Deleting or acknowledging the signal then loses the
event; acknowledging before dispatch loses it if the process dies before dispatch.

Source inspection of the pinned Serverless Workflow SDK 7.35.3.Final establishes:

1. `EventPublisher.publish` returns a future, while `EventConsumer.register` accepts
   a callback and the workflow/task contexts. Neither API alone promises a persisted
   workflow checkpoint.
2. `WorkflowPersistenceListener.onTaskCompleted` returns the persistence writer's
   task-completion future. This is a candidate place to observe actual persistence.
3. `LifecycleEventsUtils` waits for priority groups but handles each listener's
   exceptional completion by logging and continuing. A later listener can therefore
   run after a failed checkpoint. Priority ordering alone is not a successful-commit
   proof and must not be used as the inbox acknowledgement.
4. `CompletedTaskInfo` stores an iteration value; `WorkflowPersistenceInstance`
   restores completed iteration values, and an unfinished task increments its
   position's restored iteration counter. This is a candidate occurrence identity
   for sequential execution. Parallel/re-entrant tasks sharing a position still
   need explicit proof; they are not assumed safe.
5. Quarkus Flow 1.2.0's `FlowPersistenceApplicationBuilderCustomizer` obtains
   `PersistenceInstanceHandlers.writer()` and registers it through
   `PersistenceApplicationBuilder`. This is a candidate supported integration seam
   for an acknowledgement-aware writer wrapper. Its workflow-exclusion and
   all-strategy correlation behavior must be preserved.

These are source observations, not runtime acceptance evidence. The current
WP-107 increment persists events and signals and implements authorized HTTP
receipt. It has not implemented the consumption protocol below.

## Proposed decision

1. Retain a matched signal until there is authoritative evidence that the matching
   logical wait occurrence is checkpointed, terminally cancelled, or explicitly
   resolved through an authorized recovery path. An in-memory publisher/callback
   success is insufficient.
2. Bind a signal and delivery attempt to tenant, registry instance, pinned
   definition, stable task position and persisted logical occurrence. Transport
   attempts and resume epochs are not occurrence identities. Prove sequential
   loops and parallel/re-entrant cases rather than inventing a new key on restart.
3. Observe the actual writer's successful commit future or verify the persisted
   checkpoint through the persistence API before marking consumption acknowledged.
   If the checkpoint fails, keep the signal retryable. Do not infer success from a
   later lifecycle listener: the pinned SDK can swallow the earlier failure.
4. Gate forward progress while a required checkpoint is unavailable, without an
   unbounded connection claim or a database transaction spanning callbacks/network
   calls. Preserve independent cancel/drain/recovery operations. The exact writer
   wrapper, retry bounds and recovery control require implementation and tests.
5. Recheck cancellation/deadlines under the instance lock when reserving dispatch,
   not only when matching. Define the dispatch reservation as a race linearization
   point and preserve ADR 0019's completion/cancellation outcome rules.
6. Replay an unacknowledged signal to the same logical wait after restart. Every
   downstream business effect must use its stable WP-108 identity; at-least-once
   delivery is not a promise of exactly-once effects on arbitrary targets.
7. Expose pending/error/expired or poisoned work and retain deduplication tombstones
   for the agreed replay horizon. Audit manual changes and compensation failures.

## Alternatives

- **Acknowledge after the callback/publish future:** rejected; there is a crash gap
  before persistence.
- **Acknowledge in a lower-priority completion listener:** rejected for the pinned
  SDK because an earlier persistence exception is logged and swallowed.
- **Acknowledge before dispatch:** rejected; a crash before callback delivery loses
  the only durable signal.
- **Use actual commit evidence:** proposed. A writer wrapper is preferable if the
  public integration seam preserves all existing persistence behavior; a verified
  checkpoint read is an alternative if its occurrence/version semantics are proved.

## Verification required before acceptance

| Case | Required proof |
|---|---|
| Receipt before registration | Same durable event wakes the intended wait after registration |
| Crash before callback | Signal is still available after process restart |
| Crash after callback, before checkpoint | Same occurrence replays without duplicate business effects |
| Checkpoint commit fails | No acknowledgement or unsafe forward progress; recovery remains available |
| Commit succeeds, acknowledgement write fails | Recovery recognizes that checkpoint without replaying into another occurrence |
| Sequential loop restart | The unfinished occurrence reuses its identity; the next occurrence gets a distinct identity |
| Parallel/re-entrant positions | No event is stolen or confused between occurrences |
| Cancellation before dispatch reservation | No new callback is reserved; committed late signals remain visible |
| Failed compensation | Original is not falsely marked compensated; durable manual work remains |

GPT implements and records actual commands/results; Claude reviews the exact
implementation head. T13 and WP-107 remain partial until end-to-end evidence
passes. This proposed ADR is not completion or approval evidence.

## Evidence locators

- [SDK 7.35.3.Final core source artifact](https://repo.maven.apache.org/maven2/io/serverlessworkflow/serverlessworkflow-impl-core/7.35.3.Final/serverlessworkflow-impl-core-7.35.3.Final-sources.jar):
  `EventConsumer`, `EventPublisher`, `LifecycleEventsUtils`, `AbstractTaskExecutor`.
- [SDK 7.35.3.Final persistence source artifact](https://repo.maven.apache.org/maven2/io/serverlessworkflow/serverlessworkflow-persistence-api/7.35.3.Final/serverlessworkflow-persistence-api-7.35.3.Final-sources.jar):
  `WorkflowPersistenceListener`, `CompletedTaskInfo`, `WorkflowPersistenceInstance`.
- [Quarkus Flow persistence-common 1.2.0 source artifact](https://repo.maven.apache.org/maven2/io/quarkiverse/flow/quarkus-flow-persistence-common/1.2.0/quarkus-flow-persistence-common-1.2.0-sources.jar):
  `FlowPersistenceApplicationBuilderCustomizer`.
- [WP-107 implementation and current handoff](https://github.com/bassemZohdy/open-workflow-agentic/pull/41).


## Partial implementation checkpoint (2026-10-20)

PR #41 d0243791450ee4997707eb27d517afebe4e0e853 adds an actual JPA writer
wrapper through an alternative handler producer, preserving the existing
customizer/exclusions/correlation factory and restore reader. Required writer
failure or timeout holds forward progress, latches readiness down and blocks
new starts; cancellation persistence and existing idempotent results remain
usable. Immutable post-commit observer identity is captured before context
mutation. The observer does not yet acknowledge any inbox signal.

Local gate/writer/admission plus ingress/parser tests: 14 pass; application
test-compile and 49 tools checks pass. New full CI 39353953446 and reciprocal
review are pending. Previous ingress head 04b421f passed full CI 39352802891.
This is infrastructure evidence only. All consumption/restart/cancellation/
compensation proofs in the table above still gate this proposed decision and
T13. The proposed status is unchanged.


### Recovery authority and current commit-gate evidence

Claude's WP-107 handoff at c6a9228 confirms the current ingress DATA_ACCESS
contract and adds R1-R3. Proposed consumption/replay runs as SERVICE principal
`system:event-recovery`, never with the producer's identity or stored bearer.
Every delivery attempt records action, tenant, instance, signal and occurrence
before releasing its callback. Reservation/consumption checks cancellation under
the instance lock; a matched signal alone does not authorize dispatch. Manual
poison/expiry resolution is an instance OPERATION through verified caller,
`oa.recover` and `operation:resolve-signal`, with per-change audit. Claude owns
the policy hook after GPT confirms the concrete adapter interface. These are
requirements, not an already implemented recovery authorization path.

The qualifier-corrected #41 head499550a passed full CI39354335254, including
CheckpointBindingTest proving the guarded writer/original reader in the real
application. No test was removed. Loop/parallel consumption, persisted signal
ACK, replay and complete T13 remain open; this ADR stays Proposed.
