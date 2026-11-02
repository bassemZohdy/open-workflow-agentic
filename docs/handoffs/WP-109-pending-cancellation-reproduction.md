# Restored pending instance refuses deadline cancellation

The process-level regression `LifecycleRecoveryIT#aWaitWhoseDeadlinePassesWhileStoppedIsCancelledBeforeItCanResume`
originally failed because the restored SDK instance was `PENDING` and refused cancellation. It now passes with an
explicit test-only reader, proposed receipt schema and receipt writer: the packaged application is stopped across the
deadline, then restarted, and the registry reaches `CANCELLED` without restarting the expired workflow. This is not
production wiring; it does not establish safe mixed-version deployment or live replica fencing. A separate test
kills a process after receipt commit and uses an explicitly enabled test-only consumer in a fresh process to recover
the matching cancellation through the normal registry history and outbox path. This single-owner experiment does not
establish production or cross-replica crash recovery. Each test-class run uses its own process-log filenames so readiness and effect assertions cannot read a previous run’s output. The lifecycle process-recovery scenarios use these isolated logs. A separate real-process regression also verifies cancellation when a restored timed wait starts before its deadline.

The pinned SDK's `WorkflowMutableInstance.internalCancel` checks whether the current status is active and refuses a
`PENDING` instance. Starting the expired workflow merely to cancel it could execute work after its deadline; it is not
an acceptable fix. Any correction needs to prove durable SDK cleanup, no post-deadline task execution, and safe behavior
under concurrent start, cancellation, process loss and multiple replicas. This is not evidence of a two-replica failover
or T12 completion.

Reproduce against a disposable PostgreSQL server with a user allowed to create and drop test databases:

```
mvn -B -pl apps/app-runtime -am verify -Dtest=LifecycleSettingsTest \
  -Dit.test=LifecycleRecoveryIT#aWaitWhoseDeadlinePassesWhileStoppedIsCancelledBeforeItCanResume \
  -Dsurefire.failIfNoSpecifiedTests=false
```

The first run used the wrong checkpoint lookup and timed out before stopping the process. The corrected run observed
the persisted `do/0/effect` row and the expired deadline, then failed waiting for `CANCELLED`. Its retained child log
recorded `engine refused the cancel ... (deadline)` followed by repeated cancellation-repair passes.

A process-level failure-injection test rejected registry writes for cancellation after a restored timed wait.
Without a retry, the SDK checkpoint was removed and the registry remained `CANCEL_REQUESTED` after database writes
were restored. The observer now buffers confirmed cancellation events locally and the reconciler retries them; the
buffer has a fixed capacity and is lost on process exit. Durable outcome recovery, buffer exhaustion, and cross-replica
races remain open. A separate source-level concern is that polling a transient `FAULTED` status may report `FAILED`;
a race-specific regression is still needed.
