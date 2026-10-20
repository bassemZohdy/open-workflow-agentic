# WP-107 group preparation on reviewed receipt recovery

Owner: GPT. Reviewer: Claude. #49 resource-call contract411dc36 is the sole
GPT implementation PR. This branch remains preparation and grants no runtime
consumer activation or merge approval.

Normal merge703c136 takes reviewed #48/main dbec8e0; normal foundation merge
c1fd8351ea1dffaaa5095401fd011f50cba66843 takes prepared effect/CI4325993.
Both source JSON histories and changelog entries are preserved; only the
execution status scalar needed a deliberate replacement. Projections were
regenerated. No force push, branch switch, applied migration rewrite, Claude
write-area edit or shared-checkout change. V70->V71->V72 order is unchanged.

The combined branch now has the actual reviewed ACK finalizer/pre-start proof
repair, group store and snapshot preparation, effect guards/tracing, and CI
that explicitly requires the real process-death charge/refund IT alongside
each provider. This is preparation composition, not a claim that the missing
full group selector/coordinator/SDK execution/recovery bridge exists.

`mvn -B -pl apps/app-runtime -am test
-Dtest=EffectScopeTest,EffectCommandTest,InboxTaskTest,InboxListenGroupTest,ListenGroupSnapshotsTest,ListenGroupValidationTest,SdkListenGroupBehaviorTest,SdkEventRegistrationSemanticsTest,EventIngressTest,StoredCloudEventDecoderTest,EffectTelemetryTest,CheckpointingWriterTest,PersistenceCommitGateTest
-Dsurefire.failIfNoSpecifiedTests=false`: BUILD SUCCESS,60 selected plain
cases (contracts17/runtime43), zero failures/errors/skips. Application tests
compile. Tools76, generated projections and diff checks pass. No local SQL/
native/boot result is inferred from the selected build.

Fresh combined productc1fd835 runs39381927960. Exact previous producerf1842c0
passed BOTH39380401962 (producer6/SQL11/metadata2/group9/SDK observations4/
standard3/native8), but that does not prove this combined head. The new run
must execute its own SQL/CDI/provider/process cases and strict checkers.

The final PR still needs independent Claude review and full CI after its
prerequisites merge. Complete selector and durable correlation, ingress FIFO,
callback attempt/winner/item/parent proof, bounded coordinator/renewals,
actual SDK Flow/loop/parallel, process/partition/old-owner/cancellation group
recovery, starts/retention/quotas/archival/manual authority/central audit/
distributed tracing/full T13 including compensation remain open. Existing
negative controls remain observations, not guarded conformance passes.
