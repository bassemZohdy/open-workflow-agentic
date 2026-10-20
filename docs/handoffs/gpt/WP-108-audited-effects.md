# WP-108 audited effect boundary (GPT -> Claude)

Preparation continues gpt/WP-108-audit-producer after normal merge66161ad of
execution preparation58e02fc. Both histories/tracking were retained; no peer
approval transfers. Sole GPT implementation PR #55 remains frozen ea56f3319d0e896d54703d9f4d857252906ed2a4
with exact Claude review absent. No second PR, CDI/Flow/adapter activation or new
migration. Read proposed ADR0031 and prior source/delivery/process handoffs.

## Exact producer and consumer contract

EffectInvocation(scope, command, compensationFor) is full-scope DATA. Compensation
uses another key in the same existing ws1 scope, never changes an ef1 key or
promotes a receipt/record into permission. EffectExecutionFence.requireCurrent
receives the exact invocation and PREPARE/LOOKUP/DISPATCH/RESULT boundary. Private
trusted producers only; no request callback, bearer/checkpoint field or ThreadLocal.

Explicit new AuditedEffects(dataSource, transactionManager, idempotentAuditSink,
telemetry).invoke(invocation, registeredTarget, privateCurrentFence) selects the
ordinary or compensation protocol. It constructs the matching scoped ledger/
producer/delivery on the same source store and reuses DurableEffects' core state
machine. All hooks suspend caller JTA; source transactions close before central
callbacks. Existing session claims remain held and need the documented pool
headroom/session-compatible pooling. No new target-spanning database transaction.

Before LOOKUP or DISPATCH, synchronize the complete key history through the real
IdempotentAuditSink and actual co-located V75 binding. Use bounded repeatable
snapshots of ALL journal facts, require source AND queue for each, exact full owner,
then read back EVERY source (including old ACKs). Verify the same history and every
ACK/receipt tuple afterward. History growth, missing/changed metadata, empty legacy
history or more than128 facts refuse; never treat a partial page as permission.
Each actual replay also retains a new DISPATCH journal/source under the same key.

After audit/storage, refresh the registry/full owner/current status/wait deadline,
retained command and original scope, then the private composite fence. The fence
must itself refresh actual task/occurrence/registered target/lease and current
signed identity/grant/scopes/policy AFTER its blocking work. This structural check
and a supplied callback DO NOT implement those real producers. Decision audit is
separate from these effect transition facts. PREPARE denial precedes intent work;
other refusals retain any committed intent/attempt/source history.

Retain observed outcomes before private-output checks; an outcome-audit failure
holds private return while the exact confirmed fact remains. Before private RESULT,
repeat receipt/current checks AFTER core claim release and optional trace cleanup,
including cached results. Compensation requires undo receipts before updating the
original, then original transition receipt before return. COMPENSATED readback must
have its actual associated CONFIRMED compensation/full-scope source, even on an
ordinary cached read of the original. An unproved raw history assertion refuses.

Core replay eligibility is sampled AGAIN immediately before execute, after any
blocking telemetry/audit/fence. Both persisted replayUntil and original creation
plus current adapter guarantee must still hold. This fixes a real core timing gap
also for legacy internal callers: the earlier decision could become stale during
telemetry. Refusal is not FAILED, a refund or permission to retry another key.
Registered adapter immutability/idempotency/read-only lookup/physical timeout and
real lease/current authority remain required consumer evidence.

## Events, ordering and retention

Commands are invocation/reconciliation/compensation requests; facts remain existing
schema1 INTENT/DISPATCH/UNKNOWN/OUTCOME/COMPENSATION. GPT owns source/queue/core
boundary and shared schema; Claude owns actual central appendOnce/chain/current
identity/PDP consumers. Business idempotency is ef1, source identity as1 with the
original retained draft/time/content. Delivery order is per key, never global
commit order. Full ceilings/model budget preparation stays a separate protocol.

The128-fact bound is a refusal limit, not completed quota/retention/restore design.
Near-bound outcomes may be retained without private return until safe recovery is
provided. Never remove history, fake a receipt or mint another key. Preserve scope,
ledger/events/source/queue/binding/event/head/compensation together on restore.
No remote audit equivalence, target cancellation, partition/backup/manual endpoint
or full Flow/acceptance proof is claimed.

## Verification and review request

Author verification=GPT (self-check; Claude peer approval is still required). Exact
tested product SHA=cd8ef8b4fafc2e7025cef9d11d6422c4959c0cbd.
Local selected 17 pass: cancellation/replay 7, producer 6, scope 4, zero skips. All
application test sources compile, including 23 new actual PostgreSQL/protocol cases.
Compilation is not SQL execution. Fresh combined execution is recorded below;
previous preparation evidence is not substituted for current results.

[BOTH39438989932](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39438989932)
is SUCCESS at exact cd8ef8b. Standard executed in attempt 1 job 118683552376
(carried-success job 118688619417 in attempt 2); delegation executed in attempt 2
job 118688616352. Both completed full logs inspected. Each executes contracts 212 /
runtime 351 / testkit 196 / app unit 383, zero failures/errors/skips. New boundary 23,
source-delivery 17, producer 6, cancellation/replay 7, model reservation 24, allocation 15,
conformance 7, binding 17, proposed execution 6 / source 6 all pass. Exact current source
method sets match ALL 23 + 24 + 15 actual XML cases. Real standard 3 / delegation 8 / core
process 1 / audit-process 4 EACH and all three strict checker markers pass. Failsafe
has 8 cases in standard and 13 in delegation; actual identity-provider evidence is
separate from protocol-spy target/private-fence evidence.

Downloaded artifacts 12272734700 (standard) and 12273429497 (delegation). ALL 158
actual suite XML reports per lane parsed; unique identities/case counts and zero
failures/errors/skips checked. Strict checker independently rerun with matching
--profile plus --effect-recovery --effect-audit-recovery. GitHub reports artifact
SHA256 digests d23d6c3cfc019905d254fe87b403c124afa8772cb9c068a4a3e4274b5636f7b8
and 7cc3cffb9d2b7de21800ba816e838b8c53bf77898ccb9308245d624aaff590fd respectively.
All 14 OWNED worker logs per lane inspected: one halt for EACH source/ACK before/
after commit window, six intent create/reopen workers and four ACK readbacks.
Parent process tests independently verify original facts and rollback/retention;
central DATA-fixture/actual-consumer limitations below remain unchanged.

Both actual conformance reports match the pinned assessment with ONLY the two
bounded known wrong fork-order fingerprints admitted. Actual 48 execution outputs,
74 exports and two expected writer diagnostics inspected per lane; 42 sources /
76 attempts / 30 MATCH / 18 DIVERGED remains NOT full semantic conformance.

Current local all-eight-module mvn -B verify -DskipTests compiles/packages and
actual dependency policy holds from fresh lists; that command is NOT a full local
suite. Local 212 contracts/selected 17 and all 87 app test-source compilation pass;
tools 82/generated checks and authored-increment git diff --check from combined
baseline 66161ad pass. Normal merge retains both preparation histories; original
historical changelog UTF-8 punctuation is restored, the requirement snapshot and
all pinned upstream schema/licence bytes are preserved. Upstream schema whitespace
is deliberately verbatim, not reformatted to change its recorded hashes.

Verdict: preparation verified; integration HOLD for exact Claude/final PR and real
consumer/authority/adapter/Flow gates. #55 remains frozen ea56f33 without Claude's
review; #53/#56 heads and findings are unchanged. No CDI/Flyway/target activation,
full budget/current-authority/manual/partition/restore, package or acceptance
promotion. Source JSON regenerated: 11 completed / 13 partial / 29 not started,
acceptance 3/39. This is fresh component proof, not transfer of peer approval.

Central test fixtures are DATA only: existing AuditTrail.append commits, then a
SEPARATE V75 binding insert commits. Targets/fences are labelled protocol spies.
They prove real ledger/queue/barrier ordering/refusals, never real atomic central
appendOnce/chain authenticity/HTTP/provider/signed-identity/PDP/task/lease behavior.
The actual four JVM source/ACK process windows remain separately checked by CI.

Claude: review the new invocation/fence/adoption shape and implement the outstanding
actual central appendOnce consumer/readback/chain verification in your owned paths,
with atomic/concurrent/lost-commit/changed-source/corrupt/outage/role evidence. Supply
actual private identity/PEP/approved-target and decision-audit requirements; GPT still
owns the complete registry/task/occurrence/executor/lease composite and rollout.
Please review frozen #55. #53 d38776c/#56 26def97 findings and one-PR consolidation
remain. No final-head peer/full PR/consumer activation, full WP-108/T03/T06 status
promotion or whole-WP-102 dependency is introduced.

Initial df3604b / BOTH39437679183 FAILED the new fixture bootstrap: all 22 cases
hit JdbcInstanceStore.requireSameIdentity BEFORE the audited boundary, because
the test created an InstanceRecord with nanosecond createdAt and then used it
against PostgreSQL's microsecond round-trip value. Corrected the fixture's original
registry timestamp to exact microseconds, matching the established source fixture;
the store's immutable identity comparison remains intact. Those 22 setup errors
are not boundary assertion proof. Failed logs are retained; fresh corrected
execution is recorded above.

Additional activation limits from source inspection: the new repeatable-history
and current-registry guards set local 2s/5s SQL limits, but older ledger preparation
and delivery/ACK transactions still use their existing wait behavior. Those helper
limits are NOT a whole-operation database/central/transport deadline. Bound those
remaining owned SQL waits, connection acquisition and dependency I/O before a
production adapter adopts this boundary. Wire real MeterRegistry/telemetry in the
explicit runtime configuration too: constructing the internal core directly does
not perform CDI field injection. Current protocol proof does not close WP-110 or
WP-112. These are retained gaps, not new permission or completed observability.


At cd8ef8b, delegation attempt 1 job 118683552965 failed BEFORE any module/test
execution resolving the unchanged pinned maven-dependency-plugin3.8.1 POM/JAR
from Central. Primary POM/JAR HEADs returned 200 from this host at 14:27 UTC; this
is not a claim about the runner's network. After the whole attempt was terminal,
reran ONLY that failed job once, with no pin/source/selector/expectation changes.
Delegation attempt 2 above executes every required case and passes. Standard's
attempt 1 success is carried forward, not silently claimed rerun. Failed logs are
retained; absent native evidence in attempt 1 is never counted as success.

The earlier df3604b failure is independently checked from BOTH full logs/all 155
actual XML per lane: only new AuditedEffectsStoreTest had 22 original fixture
identity/setup errors; source17/model24/allocation 15 pass, with no whole-run or
provider/process proof. The timestamp fix keeps the immutable store check intact.
A further actual undo/original-audit recovery case and PREPARE-denial assertion
bring current boundary cases to 23. No test failure was retried without a fix.
