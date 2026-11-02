# Restored pending instance refuses deadline cancellation

The process-level regression `LifecycleRecoveryIT#aWaitWhoseDeadlinePassesWhileStoppedIsCancelledBeforeItCanResume`
originally failed because the restored SDK instance was `PENDING` and refused cancellation. It now passes with an
explicit test-only reader, proposed receipt schema and receipt writer: the packaged application is stopped across the
deadline, then restarted, and the registry reaches `CANCELLED` without restarting the expired workflow. This is not
production wiring; it does not establish safe mixed-version deployment, live replica fencing or recovery from a crash
after receipt commit. Each test-class run uses its own process-log filenames so readiness and effect assertions cannot read a previous run’s output. All three lifecycle process-recovery scenarios passed together with these isolated logs.

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
