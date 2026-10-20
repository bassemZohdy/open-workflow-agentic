# WP-108 retained-source delivery (GPT -> Claude)

Preparation continues the existing GPT producer branch from af061b0. Product
2941204/BOTH39410266875 proves the earlier source SQL9, NOT this queue addition.
#55 remains the sole GPT implementation PR, frozen ea56f33 with required CI
green and exact Claude review absent. No consumer or dispatch activation.

Shared framework-free IdempotentAuditSink publishes the already agreed shape:
appendOnce(AuditSource, retained AuditEvent) -> AuditReceipt. This is separate
from the existing runtime AuditSink void record API; do not silently convert
that API to retries. Claude owns AuditTrail/consumer-side wrapper implementation.
Atomically store event/head/source, fully validate stored source/content/chain
facts on duplicate readback, and return the SAME receipt. A thrown call may have
committed. No new draft, copied receipt alone or eventual-only mandatory audit
may release a target. The specific interface/implementation must be reviewed
and merged before activation; no guessed dependency on this preparation.

GPT extends PROPOSED V77 (still outside Flyway, never applied) with a separate
effect.audit_delivery operational row, inserted by source INSERT trigger in the
same commit. The original source/draft remains immutable. Row identity cannot
change; acknowledgment is one-way and retained, DELETE/TRUNCATE refuse. The
nullable acknowledgment references actual V75 audit.source_binding. Its full
tuple/content/draft id/chain must match the original source. SQL also refuses
acknowledging a later same-key fact while a predecessor is pending.

Two partial pending indexes support bounded ready reads: global source id and
full effect key plus source id. There is no global committed high-water mark:
source ids can commit out of order across keys. Return one ready pending fact
per key; deliver later facts only after prior local ACK. Competing workers may
call the SAME central source; only Claude's idempotent consumer can prove one
head advance. Source connections/transactions close before the dependency call,
including with one source pool slot. No cross-network transaction is held.

Explicit opt-in EffectAuditDelivery suspends ambient JTA, loads/verifies retained
facts, calls IdempotentAuditSink, validates the receipt and SAME retained source,
then compares event/chain/sequence/hash/content against the actual co-located
central binding before committing local ACK. Even a repeated acknowledged call
uses the consumer's readback again; it does not treat the queue flag as chain
authenticity or authority. Lost central reply leaves pending; actual local ACK
commit followed by lost reply remains acknowledged and retry preserves identity.
drain is ONE bounded pass, stops on failure, has no scheduler/unbounded retries.
This API returns historical receipt DATA only: no target, callback or permission.

This baseline is explicitly co-located with V75. A remote audit deployment needs
its own authenticated retained receipt/recovery contract; do not pretend a remote
append creates the local binding. Missing/unavailable schemas refuse. ACK/data
indexes are not whole-chain verification, quotas/retention, broker relay, process
restore, lease/current identity/authority, manual decision or full Flow proof.

## Actual test scope / peer request

New shared port/delivery compile and all existing sixteen selected local cases
pass (producer6/cancellation6/scope4), zero failures/errors/skips. Eight additional
real SQL cases compile, bringing the owned-database source/delivery fixture to17.
Fresh exact-head Linux BOTH proof is required before calling these passed.

SQL cases cover direct/ready per-key predecessor refusal, actual stored-binding
requirement and changed sealed-hash refusal, irreversible ACK/update/delete/
truncate refusal, central reply failure after fixture facts commit, actual local
ACK commit reply loss/fresh-component readback, one-slot source connection closure
and ambient rollback isolation, delayed lower-id actual commit after a higher id
has been ACKed, and four concurrent deliveries retaining one local ACK. Existing
source AND queue insert failures verify complete rollback too. Parent+child TRUNCATE assertions
include audit_delivery, preserving strong42501 guard assertions rather than a
weaker earlier FK failure. No production triggers or assertion are weakened.

CENTRAL RECEIPTS ARE EXPLICIT DATA FIXTURES here: existing AuditTrail.append
commits event/head, then a separate fixture insert writes V75 binding. This is
deliberately NOT a real atomic appendOnce implementation, actual idempotent
consumer concurrency or one-central-head/central unknown-commit proof. The
fixture itself says so. Tests prove the real queue/local transactions/protocol,
never substitute that fixture for Claude's required actual consumer evidence.

Claude: implement the owned IdempotentAuditSink wrapper after shared review/merge
and V75 sequence agreement, plus the previously requested actual atomic/concurrent
one-head/changed-source/lost-central-commit/readback/reopen/corrupt/outage/cross-
chain tests. V77 adds an incoming FK from effect.audit_delivery to source_binding:
when ordered activation eventually includes V77, owned always-rollback TRUNCATE
fixtures must include that child too. Producer writer needs queue INSERT/SELECT;
delivery role needs source/event/queue and central binding SELECT plus queue ACK
column UPDATE only, not source/audit mutation privileges. GPT owns final schema/
configuration rollout and source JSON; report role/test evidence through handoffs.

Full governed target/Flow activation remains gated by actual task/registry/owner/
registered target/live identity/PDP/execution/lease AFTER ALL blocking work and
mandatory verified central receipt. Historical projection after registry end is
not permission to repeat UNKNOWN or advance a checkpoint. No package/acceptance
promotion, whole-WP-102 start dependency or second GPT implementation PR.
