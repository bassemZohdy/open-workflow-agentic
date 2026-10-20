# ADR-0026: Share event-task ownership and fence the actual checkpoint

**Status:** Proposed; implementation is inert pending database evidence, reciprocal review and complete consumer/recovery gates.

**Date:** 2026-10-20

## Context

The SDK may register several event filters for one `all` or `any` task. Giving each
registration a separate long-lived JDBC session can exhaust the pool while an
`all` listen has received only some events. Separate owners can also reserve a
mixed receipt vector. A session lock cannot fence an old in-memory callback after
its connection is lost. A callback return is not proof of a durable checkpoint.

## Decision

1. Identify the task by tenant, registry instance, application, engine instance,
   pinned namespace/definition/version, native SDK position and iteration. Its
   filter, type, public route, registration timeout, owner and transport attempt
   do not change task identity. Per-registration identities remain distinct.
2. Use one renewable database lease for that task. Acquisition, renewal and
   release use short transactions, with no idle session/connection while waiting.
   Each takeover increments a retained epoch; a live different owner is refused.
   Owner is a runtime nonce, not a caller credential. A lost acquisition reply
   retried by the same live owner retains its original epoch and expiry.
3. Limit each grant/renewal to 100ms..30s. Database time determines expiry AFTER
   locks. Renewal does not re-arm any registration's original absolute deadline.
   Audit each returned decision before returning authority; audit failure leaves
   a visible lease but returns no permission. Retry audits again before returning.
4. Carry the task owner/epoch in trusted durable checkpoint metadata. Require
   it in the SAME JPA transaction as the actual SDK task and receipt proof,
   holding registry -> task lease -> delivery row locks until commit. Check
   expiry again after the last blocking delivery-row lock. Lost/expired authority
   rolls back both rows and the guarded lifecycle stage stays pending.
   An in-time committed dispatch reservation wins its wait deadline: checkpointing
   still requires the live task lease and non-cancelled registry, but does not
   retroactively reject that accepted reservation after its wait deadline.
5. Record the exact task digest/owner/epoch on new immutable receipt proofs.
   Preserve V64..V67; V68 adds the lease and nullable historical proof fields.
   New markers require format2 and an explicit claim. Missing/old marker authority
   fails closed; historical immutable proofs are not rewritten or reinterpreted
   as ownership grants. No user event field can install this runtime marker.
6. Owned matching, reservation and proof-based acknowledgement use short JDBC
   transactions with registry -> task lease -> delivery/message locking. Proof
   reads reuse that connection. No connection spans an audit, callback or idle
   wait. Recheck the live lease after blocking matching reads and after the
   independent dispatch audit, immediately before returning permission.
7. Each registration receives at most one dispatch permission per task epoch.
   An unknown commit reply or failed audit leaves a visible reservation and
   returns no permission; retrying that epoch does not dispatch again. Recovery
   takes a new task epoch and rebuilds the entire uncheckpointed task, preserving
   its bound receipts and original deadlines. Never replay into an old partial
   `all` callback array. V69 adds ownership to delivery rows; V64..V68 are unchanged.
8. A committed immutable SDK receipt permits historical ACK after completion or
   lease loss. Read the real proof before and after independent audit, then ACK
   under the delivery lock. ACK grants no continuation authority. A returned
   reservation or callback return never establishes consumption.

## Consequences and remaining gates

This component fences the checkpoint transaction, not every later workflow
continuation or external effect. A production consumer must still serialize task
callbacks, integrate the owned storage path, make `any` losers inert, maintain
bounded renewals, ACK before allowing advancement, and repair committed receipts
before restore advances. Its pool and executor limits must be explicit. The old
session-based dispatch seam remains inert and must not be used to hold every
registration while a partial `all` listen waits.

Process/partition loss, whole task vectors, loop/parallel restore, governed
`foreach` effects and complete T13 remain acceptance gates. Existing proposed
ADR0025 is included by the merged reviewed plan #35; this decision does not
accept that plan or close WP-107. Metrics/tracing, retention cleanup, manual
OPERATION policy and event starts remain in the package checklist. No production
consumer is activated by V68/V69, and no arbitrary-target exactly-once claim is made.
