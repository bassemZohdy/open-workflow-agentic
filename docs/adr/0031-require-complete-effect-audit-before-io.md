# ADR-0031: Require complete effect audit before target I/O and private results

Status: Proposed preparation; no governed adapter, CDI or Flow activation

Date: 2026-10-20

## Decision

Add explicit AuditedEffects over the existing DurableEffects protocol. Reuse its
business keys, advisory exclusion, attempted marker, lookup-first reconciliation,
finite idempotent replay, outcome persistence and compensation association. Optional
internal hooks select the scoped producer and enforce mandatory retained-source
receipts; do not fork another effect state machine or treat an audit queue ACK as
permission. Existing CDI entry points remain internal legacy APIs. Activation
requires coordinated adoption and non-bypass verification at every governed caller.

EffectInvocation carries full scope, command and optional original compensation
key as DATA. EffectExecutionFence is a transient private composite dependency,
with PREPARE/LOOKUP/DISPATCH/RESULT boundaries. The real producer must verify actual
task/occurrence/registry/full owner/approved target/lease plus current signed
identity, grant ancestry/expiry/scopes, policy and mandatory decision audit after
its own blocking work. This preparation does not implement that producer or turn
a supplied callback into trusted authority.

Before target lookup or dispatch, and before returning any cached/private result,
synchronize the complete retained history of each relevant effect key. Inspect
source/event/queue metadata in coherent repeatable snapshots, require every fact
and queue row, and refuse empty, changed or over-bound histories. Deliver at most
128 facts in one pass, in per-key journal order; even previously ACKed facts must
go through the actual consumer's readback. Compare the same complete history and
all local ACK/central binding tuples after delivery. Never accept a partial page,
a global source-id watermark or a copied receipt as a completed barrier.

New replays retain another existing-schema DISPATCH fact under the SAME business
key. Clock checks immediately before physical resend recheck both original and
current promised retention deadlines AFTER telemetry, audit and the private fence.
This final clock check also fixes the legacy core's late-retention race. A refusal
leaves uncertainty/history; it does not turn a timeout into FAILED or mint another
key. The actual registered adapter still guarantees atomic durable deduplication,
read-only authoritative lookup, immutable approved configuration and bounded I/O.

Retain observed target outcomes before private-output/current-authority refusal.
Require outcome receipts before an undo can advance its original, and require the
original's COMPENSATION receipt before returning. A cached COMPENSATED result
requires its exact retained association, confirmed compensation and full-scope
source/readback too. Raw history without that proof never releases success.
Manual operator authorization/evidence/decision identity remain separate work.

Suspend caller JTA across all hooks and cleanup; keep source transactions closed
across central calls. Existing session advisory claim connections DO remain held
across work: retain session-pooling and pool-headroom constraints. Database guard
waits are bounded locally; actual dependency/transport deadlines, lease loss and
partition handling remain adoption gates, not properties of an in-memory callback.

## Consequences and verification

No new migration or identity/hash domain. Proposed V75/V77 remain outside Flyway;
no applied migration is rewritten. Claude owns actual atomic appendOnce, chain
validation and identity/PDP/audit consumers. GPT owns this protocol, lifecycle,
contracts, shared schema/configuration and coordinated rollout. No second GPT PR;
#55 stays frozen pending Claude's exact review.

The bounded barrier refuses histories beyond128; it is not a complete quota,
archival, retention or paged restore protocol. A call near the limit can retain an
outcome that needs recovery before private return. Never discard earlier facts or
reset a key to work around this limit. Full Flow/actual adapter/task/lease/manual/
central consumer/process/partition/backup and acceptance proof remain open.

A deterministic core test advances the clock during dispatch telemetry and checks
zero resend. New actual PostgreSQL tests use owned UUID databases, real ledger,
queue and core exclusion with protocol-spy targets/private fences. Central data
fixtures deliberately append and bind in TWO commits; they cannot prove atomic
appendOnce, chain authenticity, actual provider or signed-identity behavior. The
handoff records exact executed and pending evidence with all remaining gates.
