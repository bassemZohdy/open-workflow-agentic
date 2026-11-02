# Restored pending instance refuses deadline cancellation

The process-level regression `LifecycleRecoveryIT#aWaitWhoseDeadlinePassesWhileStoppedIsCancelledBeforeItCanResume`
currently fails. It starts a packaged application with PostgreSQL, waits for the workflow to reach `WAITING` and for
the preceding effect step to be persisted, kills the application, and waits until the stored deadline has passed.
On restart, the startup reconciler records `reconciler.deadline.passed`, but the restored SDK instance is `PENDING`
and its `cancel()` returns false. Repeated reconciliation leaves the registry in `CANCEL_REQUESTED`; the desired
`CANCELLED` state is not reached within 60 seconds. The test is intentionally failing and remains isolated from the
integration branch until a fix is verified.

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
