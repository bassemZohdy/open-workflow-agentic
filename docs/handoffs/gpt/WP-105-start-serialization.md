# WP-105: in-process Flow start serialization

The Flow adapter now claims an SDK instance by object identity before checking its status or invoking its `start()` method. Prepared
launches and restored starts use the same claim. Concurrent starts of that same object refuse while the first call is
in progress; the claim is released when the call returns with a non-pending status. If the call throws or returns
while the status is still pending or unavailable, its weak claim remains so the adapter does not blindly invoke it
again. A failed status check before invoking `start()` releases the claim. At most 1,024 live claims are admitted per classloader, with no queue. Claims for unreachable instances are
reclaimed through a reference queue. A refused prepared launch throws instead of reporting a successful launch.

This protects calls through this adapter within one classloader. It does not fence direct SDK calls, other processes,
or a start already in flight against cancellation, nor does it establish physical termination. The existing database
advisory locks and authority checks remain required. The claim lock is not held across SDK calls, so a blocked
status check or start does not prevent unrelated instances or cancellation from being called. A pending claim left by an uncertain
start can hold availability until the instance is no longer reachable; capacity exhaustion fails closed.

The pinned SDK's `WorkflowMutableInstance.startExecution` checks its future before publishing the started future;
that check and publication are not atomic. A controlled race fixture pauses the first start before status changes and
verifies a second call cannot enter, an uncertain start is not retried, other instances can progress, and cancellation
is not blocked. This is a separate concurrency finding. It does **not** establish the cause of the historical
intermittent identity-recovery failure in issue 75 or close WP-105/T16. As a negative control, the race fixture fails
against the unguarded check-then-start implementation.

The final guard passed 15 focused runtime tests, 68 database-backed recovery, cancellation and repair tests, and
the packaged process-restart `IdentityRecoveryIT` (one test). The database-backed reactor also passed the five
start-race tests.

For the separate recovery investigation, ten consecutive packaged `IdentityRecoveryIT` runs passed on baseline
`e3a77a2b9f8b133919f82e54ac1e29d3fcc4283f`, using isolated dependencies built from that revision. Each report
contained the expected test and both process logs were retained locally. These repetitions did not reproduce the
historical failure and do not establish its cause; earlier runs using stale cached dependencies were excluded.
