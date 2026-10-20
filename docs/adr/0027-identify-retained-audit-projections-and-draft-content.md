# ADR-0027: Identify retained audit projections and draft content

Status: Shared contract foundation merged; producer preparation is explicit opt-in, production projection not activated

Date: 2026-10-20

The effect journal is not the central hash-chain audit. An append may commit
before its reply is lost. A fresh event id on retry duplicates history; resealing
an existing id can fail without proving its exact previously committed content.

Use shared AuditSource/AuditReceipt and AuditDraftFingerprint v1 as specified in
docs/handoffs/gpt/WP-108-audit-contract.md. Source identity binds schema/scope/key/
finite transition/committed source sequence independently of content. Retain ONE
draft ULID, original microsecond time and content. Changed content for the same
source conflicts; central chain position/hash remain separately assigned.
Canonical draft bytes bind id/time/full actor/action/resource/selected chain/all
sorted attributes, excluding central sealing fields. Attribute keys use Java String natural order: UTF-16 code-unit order, which can differ from Unicode code-point or UTF-8 byte order for supplementary characters. Other-language implementations must reproduce that exact order. Refuse malformed text/finer
precision rather than normalizing. Existing AuditHash encoding is unchanged.

This data contract does no I/O and grants no authority. GPT later retains source/
outbox atomically with effect facts; Claude appends idempotently, returning the
same stored receipt on an exact retry. Unknown reply requires same-identity
readback. Mandatory audit still precedes target entry; current authority/execution
checks follow all blocking work. Source binding SQL, producer/consumer/relay,
concurrency/commit-loss/restart/backup/retention and actual governed Flow evidence
are adoption gates. No applied migration or package/acceptance status changes here.

The prepared effect producer is specified in WP-108-audit-producer.md. A known
fresh ledger insert establishes full-owner provenance and retains its actual
journal id/time plus original draft in that same commit. Existing legacy rows
cannot be upgraded by guessing their scope. Subsequent scoped facts retain their
draft atomically too; proposed V77 defers the journal/source completeness check
until commit and retains immutable parent references. Source identity uses the
exact V75 pure as1 function; draft content uses the ONE merged Java helper.
Compensation retains the original scope as historical data after a run ends.
Short transactions and the complete cross-process claim suspend ambient JTA.

These are producer data guarantees, not a target authorization or proof of a
central append. Proposed V75/V77 stay outside Flyway until centrally ordered
activation and exact review. An old writer's scoped journal mutation refuses,
but an old read-only lookup/replay path is not fenced by deferred constraints.
Require a coordinated adapter rollout, authentic task/target/owner/live identity,
mandatory verified central receipt and final current execution/authority checks
after blocking work before activation. Stable manual decisions, full Flow,
retention/relay/partition/process/backup proof remain open; no legacy backfill.

Prepared delivery uses separate operational work rows, atomically enqueued with
the immutable source. Pending-only indexes and same-key predecessor checks avoid
a global committed sequence assumption. ACK is irreversible, references and
matches the actual co-located central source binding, and grants no permission.
Close source transactions before calling the trusted IdempotentAuditSink; retain
pending work after an unknown central reply and the same original receipt after
an uncertain local ACK. This interface formalizes the agreed appendOnce shape;
Claude's actual atomic consumer/current stored-chain validation and its evidence
are still required. Eight added SQL cases use explicit central DATA fixtures,
not a substitute consumer; see WP-108-audit-delivery.md for exact proof limits.
No scheduler, remote-store assumptions, callback or applied migration is added.
