# WP-102: IdentityRecoveryIT red on main (run 40246336207, b02875f)

Status: **cause not proven; no product or fixture-behaviour change made.** Main is not shown green by this report.

## What failed

`IdentityRecoveryIT.identitySurvivesAKillARevokedGrantHoldsTheInstanceAndNoCredentialIsPersisted` failed at
`decideUntilCompleted(second, y, bearer)` (line 226): after 90 s instance y was registry-`WAITING`. Everything before it
passed, including x completing (line 225), the held state, the audit/history assertions and "y resumes under the new grant
with its original subject and correlation" (the epoch=1 `probe-effect authority` line for y was seen). Elapsed 105 s.

Retained evidence: `ow-checkpoint-postmerge-failure.log` (runner output) and `ow-identity-recovery-failed.xml`. Neither has
the child-process log. `build.yml` uploads `failsafe-reports/*.xml` only, not `target/process-logs`, so
`identity-run2.log` (the one file that would show y's listen registration and event delivery) is lost.

## Same tree passed and failed: unresolved timing-dependent defect

The same IT passed in every earlier retained run: 1396bd4, 33b0562, 6610bf1, cd15aca, de853bb (16.8-19.7 s each) and the
Linux network run (38 s), including the PR67 head a01b8e1 run 40245351968 (all 170 suites green). The failing commit is
the merge of that same tree, so the same tree has both passed and failed. That demonstrates intermittent behaviour; it does
not show the tree is free of a defect. This is an unresolved timing-dependent defect with an unidentified cause.

Latest tracking of main d031e00, run 40246884344, succeeded. That is one passing run of an intermittent test; it is not a
fix and does not clear the failure. The failure metadata (original run 40246336207 on b02875f) remains the evidence of record.

## Hypothesis check (shared raw event type, completion/reauthorization ordering)

Checked in the SDK bytecode (`serverlessworkflow-impl-core` 7.35.3.Final, `javap`; no sources jar in `~/.m2`):

- Not proven as stated: delivery is **broadcast, not exclusive**. `AbstractTypeConsumer` keeps a `CopyOnWriteArrayList` of
  registrations per event type and `CloudEventConsumer.accept` offers each event to every registration. x consuming an
  event does not take it from y. Events that y misses before its `listen` registers are harmless because
  `decideUntilCompleted(y)` keeps publishing every 500 ms for 90 s.
- Real hazard found: `CloudEventConsumer.accept` has **no per-registration exception handling** and
  `InMemoryEvents.publish` is `CompletableFuture.runAsync` (the dev endpoint ignores the future). If one registration's
  predicate or consumer throws, delivery to every *later* registration of that type is dropped silently. x and y share the
  type `org.openworkflow.agentic.lifecycle.probe`, x registered first. For this to explain 90 s it would also require x's
  registration to outlive x's completion. That is **unverified**: nothing retained shows it.
- Registry side checked and cleared as the sole cause: `EngineStatusSync.steps` and `Reconciler.repairRegistryRow` /
  `recover` reconcile an engine WAITING/RUNNING/COMPLETED report with a registry WAITING row. A completed y would be moved by
  the next "polled" pass unless the engine had dropped the instance (`instance.absent`, which would leave the row WAITING
  and is also unverified).

Candidate causes still open, none supported by retained evidence: (1) stale x registration throwing on every publish;
(2) y engine instance not reaching/keeping its `listen` registration after a `recover` call made from the HTTP thread
(x was resumed by the periodic pass, y by `InstanceResource.reauthorize` -> `Reconciler.recover`); (3) y completed in the
engine but the registry never observed it (`instance.absent`).

## Change made (test only)

`IdentityRecoveryIT.decideUntilCompleted` now adds the engine instance id and the last 60 lines of the process log to the
failure message, so the next occurrence carries its own evidence in the failsafe XML. No timeout, sleep, ordering or
assertion changed; every identity, revoked-grant held-state, renewed-grant, correlation, no-credential and completion
assertion is untouched. Compiled only (`mvn -o -pl apps/app-runtime -am test-compile`); no test was run on Windows.

Not done on purpose: reordering to one publisher loop for both instances, or changing x/y event types. With no
supported cause that would only make the symptom rarer and could hide candidate (1) or (2). The event type is fixed in
`flow/lifecycle-probe.yaml` (GPT area) so per-instance types are not a fixture-only change anyway.

## Handoff requested from GPT (GPT-owned paths)

1. **`.github/workflows/build.yml`**: upload `apps/*/target/process-logs/` (as `keycloak-interop.yml` already does) at least
   on failure, so a red process IT keeps `identity-run2.log`.
2. **Targeted Linux process verification** of this IT in a loop (for example 20-30 runs from the same packaged jar, each
   keeping `target/process-logs/identity-run2.log`). On a failure, look in `identity-run2.log` for: y's `probe-effect
   authority ... epoch=1` line, any exception around `/dev/lifecycle/events` publishes (`DevLifecycleResource.publish`),
   x's and y's engine ids, and whether the registry row for y ever moved after the event.
3. If candidate (1) or (2) is confirmed, the fix is in lifecycle/dev-probe/flow paths (for example catching per-registration
   failures, awaiting the publish future in `DevLifecycleResource` and logging its failure, or a per-instance event type in
   `lifecycle-probe.yaml`). I will review that change; the identity area needs no change on current evidence.
4. Keep runtime checkpoint DDL removal blocked until the loop result is understood.
