# WP-108 explicit retained audit delivery

GPT-owned data-delivery component on merged atomic producer86/V77. No scheduler, CDI registration, target callback,
model context, graph admission, production root, role grants or reserved migrations activate. WP108 remains partial.

## Boundary

IdempotentAuditSink is a framework-free trusted central append-once port. Inputs are the retained original source/draft;
the output is historical AuditReceipt DATA, never current authority or dispatch permission. AuditAppendOnce's existing
public method can be supplied explicitly as a method reference; this introduces no default implementation or bean.

EffectAuditDelivery is explicitly constructed with source datasource, transaction manager, producer and bounded trusted
sink. It selects at most128 ready pending records, one predecessor per effect key. There is no global committed id
watermark: a delayed lower-id commit is still visible after a higher id was acknowledged. Same-key id order depends on
the producer serializing before journal-id allocation, as producer86 does. No unreviewed arbitrary SQL writer is admitted.

Before central append, require the retained queue/source and no earlier pending same-key source. Finish and CLOSE the
owned source transaction/connection before invoking the sink. Then compare returned source/draft data, reopen a short
owned source transaction, revalidate retained data and the exact actually stored central binding, and retain a local ACK.
This component is co-located: the source connection must see the central audit.source_binding schema. It is not a remote
receipt-authentication protocol. Caller-held pool resources and transport/dependency bounds still need reviewed capacity
configuration; a closed owned read connection is not a global pool/latency guarantee.

A missing reply may follow either central or local commit. Leave original data and repeat the SAME source through the
trusted append-once/readback path; never regenerate an id/time/draft or infer target nonapplication. Repeats verify central
readback even for an already ACKed source, preserving original ACK time. A bounded drain stops on failure; prior committed
receipts survive. Ambient caller rollback cannot erase its independently committed data. Preserve an original action error
if caller resume also fails, attaching the resume failure; a resume failure after success still prevents an acknowledged
return. No target or private output is released by this API. Mandatory receipt/current-authority and full journal/source/
queue history checks before any actual target remain a future reviewed adapter, not a receipt-object shortcut.

## Verification

Java21/PostgreSQL16.15:52 focused cases pass (delivery8, producer store15/unit6, actual central append-once22, default
canonical admission refusal1), zero failures/errors/skips. Fresh verified common suite DB plus a newly created, actually
migrated private DB per delivery case; all dropped after actual work termination without FORCE. Source connection
counting uses the SAME one-slot datasource for the actual AuditAppendOnce consumer, not a seeded fake receipt.

Cases cover ready/direct predecessor rejection and bounded drain, invalid limits, exact duplicate/unchanged ACK time,
matching-shaped-but-unstored and actually wrong receipts, actual central commit followed by lost reply, actual local ACK
commit followed by lost reply, no owned source connection across the consumer, real ambient JTA rollback, a delayed
lower-id commit and two concurrent deliveries producing one actual central event. Default delivery/sink beans are absent.
The initial integration fixture used nanosecond registry identity times and was refused on6 cases by the existing immutable
identity guard. Correcting ONLY fixture times to PostgreSQL microseconds makes the intended cases pass; no guard relaxed.

Negative control removes ONLY the predecessor read check from production in a temporary local probe: one case fails at
'an earlier pending source must refuse BEFORE central append' (expected0, observed1). The later SQL ACK guard rejected the
write but could not undo that central append; it cannot mask the intended assertion. Production bytes were restored and
verified identical before the restored positive probe. Logs target/audit-delivery-*.log. The restored positive case passes1 with zero failures/errors/skips. Exact Claude review/current Linux CI and raw
archive inspection are required on the PR before merge.

## Preparation provenance

Only the port and initial data-delivery class from preparationc4adaab are carried forward. Tests are new against the
ACTUAL merged AuditAppendOnce/V77; old seeded-receipt, manual, history-barrier, target-adapter and later stacks are not
merged wholesale. Production adoption still needs coherent current caller/PDP/task/lease/target and central receipt gates,
resource-call/task/profile mapping, bounded operations, actual Flow/node/output association and private async/recovery.
