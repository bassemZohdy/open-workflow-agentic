# WP-108 V77 retained effect-audit facts

GPT-owned additive migration candidate after V76. Claude owns peer review and the parent-probe fixture prerequisite
PR84. V77 must not merge before that fixture prerequisite and exact peer/current CI approval. V78..V80 remain reserved.

## Scope

Activate only tables/functions/triggers for `effect.audit_scope`, `effect.audit_source` and `effect.audit_delivery`.
No producer, relay, CDI binding, new effect callback, admission, model context, graph or automatic target dispatch.
An acknowledged central receipt is retained DATA; it is never current identity, policy, task/lease or output permission.
Old effect/journal history remains byte-for-byte intact, with no invented owner/source/draft/receipt backfill.
Existing unscoped effects remain unscoped; the later producer must refuse guessed upgrades and persist initial intent,
scope, original draft and delivery together. A scope comparison alone cannot establish historical authority.

The previously unactivated V77 proposal is brought forward in isolation, without its old branch's producer/manual
runtime stack. New V77 functions pin search_path to pg_catalog; applied migrations V1..V76 are unchanged.
Scope comparisons include full owner kind/id even though the legacy EffectScope thread hash omits kind.
This schema still uses that legacy registry effect key, not the canonical resource-call checkpoint thread; its presence
does not solve the missing resource-call/task/lease mapping or approve a production governed root.

For opted-in scoped histories, deferred constraints require retained sources for journal facts and matched provenance
for compensation links. Source INSERT atomically enqueues one pending delivery. Identity/draft data is immutable;
ACKs require exact retained central source-binding data, cannot be erased/reset and cannot overtake an earlier pending
source for the same effect key. No global sequence watermark or polling worker is introduced.

These are DATA constraints, not a complete causal-delivery protocol. A central append could already have happened
before an ACK is refused; the future relay must enforce predecessors BEFORE append, and the producer must serialize
same-key journal creation before allocating its sequence. SQL's content fingerprint field is shape-checked; the shared
Java AuditDraftFingerprint and real AuditAppendOnce verify its equality to the draft. No receipt authenticity or source
writer permission is inferred from a stored hash. Deployment least-privilege grants and coordinated writer adoption
remain future reviewed gates.

## Verification

Java21/PostgreSQL16.15: EffectAuditFactsMigrationTest21 and existing ResourceExecutionMigrationTest20 pass, zero
failures/errors/skips. Private UUID databases are created, actually migrated with Flyway, validated and dropped without
resetting or terminating shared sessions. The latter20 were rerun with V77 present; no V76 guard was weakened.

New cases cover fresh/nonempty V76 upgrade, no backfill, conflicting preexisting table rollback, canonical Unicode
scope/key/draft retention, atomic enqueue failure, missing-source deferred refusal, full-owner-kind mismatch, actual
registry row-lock waits followed by cancel or expiry, finite journal transitions, compensation provenance after registry
completion, actual AuditAppendOnce receipt/duplicate readback, pending ACK ordering and retained DELETE/TRUNCATE.
Only test fixtures construct raw source facts; this is not the missing production producer implementation.

Negative controls run only in owned private transactions and always roll back: remove only the source INSERT guard
and the same wrong-key probe becomes insertable; remove only the function search-path pin and a proven active session
function shadow changes the canonical encoding. Parent-probe PR84 separately proves child errors cannot mask a removed
parent guard. The original migration test run had one fixture Boolean text assertion failure (t vs true), corrected by an
explicit PostgreSQL text cast; production SQL was not relaxed.

Final peer SHA, independent results, current required CI and archive inspection are recorded on the implementation PR.
WP108/WP102/T03/Flow/recovery and acceptance stay partial. Default admission/model allowance remains absent.

## Proposal provenance

Original unactivated proposal SHA-256: 1623a1e09bc28a0f1d73a9ae38fb2079fd9dd48ebeab254bb052fa8f74b2d0ad .
Candidate SHA-256: 7825bde76670cb6809dc94dd1a57740d701001eafdb0f28e741ea447a2b8a544 .
Source: gpt/WP-108-audit-producer:modules/oa-runtime/src/main/resources/db/proposed/V77__retained_effect_audit_sources.sql.
Historical component evidence is not transferred as current approval.
