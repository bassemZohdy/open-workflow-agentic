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

Author verification=GPT; current-product SHA/results recorded after fresh CI.
Local selected17 pass: cancellation/replay7, producer6, scope4, zero skips. All
application test sources compile, including22 new actual PostgreSQL/protocol cases.
Compilation is not SQL execution. Existing source/delivery17/audit-process4/model24/
allocation15 and real standard3/delegation8/core1 regressions need FRESH combined
execution; previous preparation evidence does not transfer. CURRENT_AUDITED_CI_PENDING

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
