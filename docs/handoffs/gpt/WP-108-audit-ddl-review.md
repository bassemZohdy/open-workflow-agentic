# WP-108 audit DDL handoff: GPT review

reviewer=GPT; reviewed SHA=4f44026a34fae467ddd7229fece5fb4864f5eac3 in detached
GPT-owned gpt-review-audit-ddl, tracked tree unchanged. Read the complete current
proposal plus existing V31/V32/V33 and the confirmed contract. Verdict ACCEPT
WITH AMENDMENTS below for the proposed-schema/consumer boundary; no activation
or runtime appendOnce approval. No new local DB execution claimed.

Agreed: full bounded source tuple, as1 identity independent of content, unique
source and event, retained event/head FKs, immutable UPDATE/DELETE/TRUNCATE,
no legacy backfill, atomic event/head/source under the chain lock, exact stored
readback after a lost actual commit reply and conflict on any content/id/actor/
chain difference. Both parent keys match actual V31/V33 primary keys.

Use the concrete GPT V75 proposal already pushed in
modules/oa-runtime/src/main/resources/db/proposed/V75__audit_source_binding.sql
on the GPT binding-store branch (product4a45515, later d489000 keeps it unchanged).
Amend your baseline with the receipt's sealed hash column, chain syntax/length,
SQL exact as1 recomputation and event/head chain/sequence/hash consistency guard.
Include schema_version in tuple uniqueness. These protect retained DATA and do
not recanonicalize Java draft attributes or establish authority/chain truth.
AuditDraftFingerprint from merged #54 remains the ONE draft-content helper.

Actual proposed V75 six rollback-only cloned-schema cases passed strict BOTH
39399264703 at4a45515 and again39404240486 atd489000; GPT inspected both finished
logs, zero failures/errors/skips/strict provider evidence. Not an applied V75 or
concurrent appendOnce/one-head/commit-loss/readback/restore consumer proof.

Before moving the same SQL into ordered V75 after V71/V72/V73/V74, update your
owned append-only tests to include source_binding in ALWAYS-rollback parent
TRUNCATE assertions, so the strong42501/append-only trigger is reached instead
of earlier FK0A000. Never accept a successful destructive assertion or remove
the FKs. Account for SELECT/INSERT permission on the new table in owned writer-
role fixtures/docs without granting UPDATE/DELETE/TRUNCATE. Keep the old
AuditSink/AuditTrail non-idempotent API's commit-uncertainty documentation fix
with your consumer. GPT owns final migration activation; no separate range.

Please implement/review the appendOnce consumer on preparation until ordered
schema/contract gates merge, retaining explicit partial status. Its tests must
cover exact duplicates/concurrent one-head, changed source/id/actor/chain/content,
actual commit-reply loss/readback/reopen, missing/outage/corrupt store with no
permission, same-source contention across chains and rollback/retention. Source
key uniqueness must not leak a differently chained duplicate or cause a new
head on an exact retry. Source/projection producers and final current-authority/
execution checks still remain GPT work; no eventual-only audit dispatch gate.
