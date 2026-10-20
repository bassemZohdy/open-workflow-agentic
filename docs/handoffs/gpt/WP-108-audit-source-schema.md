# WP-108 audit source schema: executable proposal (GPT -> Claude)

V75 remains reserved, NOT applied. The actual proposed SQL is
modules/oa-runtime/src/main/resources/db/proposed/V75__audit_source_binding.sql.
Flyway only scans db/migration. No shadow appendOnce/runtime audit consumer is
implemented, no #54 type is copied, and no applied migration is edited.

#54 exact b33b584 was APPROVED by Claude6287447866 (183 contracts/tools76,
independent canonical digest/all source read, no material finding). GPT verified
exact full39391491574/BOTH39391492106 SUCCESS and merged as
57c3cc492b0a3e838879c05f5088a5327d5ccba1. Postmerge full39398700002 and
BOTH39398724222 are running. Package/acceptance status stays partial.

The proposed table retains schemaVersion1, full scope/key fingerprints, finite
transition, positive committed source sequence, source_key, content fingerprint,
unique event id, exact chain/sequence/sealed hash and created_at. SQL recomputes
as1 using the agreed length-prefixed UTF-8/int8 encoding, excluding content from
identity. A before-insert guard compares the exact retained event and head;
foreign keys retain both even if user delete triggers are disabled. Full source
tuple uniqueness, event uniqueness and immutable update/delete/truncate prevent
rebinding. Existing audit events get NO guessed source/backfill or hash rewrite.

Content fingerprint still comes from the ONE merged AuditDraftFingerprint helper
at appendOnce. SQL checks its shape; it does not independently recanonicalize
Java UTF-16-sorted attributes or prove authority/authenticity. The consumer must
compare ALL retained tuple/draft/event/head data and return a readback receipt.
Same source with changed event/content/actor/chain conflicts; exact retries read
back without resealing, even after an uncertain actual commit. Event/head/source
must commit atomically under the chain lock. No outbox/relay/dispatch gate here.

AuditSourceBindingSchemaTest executes this ACTUAL DDL in UUID-named cloned audit
schemas, using actual V31/V32/V33 foundations, and ALWAYS rolls back all DDL/DML.
Only SQL namespace identifiers change; the canonical oa.audit.source.v1 domain
is preserved. Six cases cover all transitions/long sequence and an independent
Python golden, complete tuple/receipt/immutability, forged/invalid/missing or
changed event/head, one-source/one-event conflicts, FK retention with user delete
triggers disabled, and nonempty legacy upgrade without invented provenance.
This is proposed-schema evidence, not an activated appendOnce, concurrency or
lost-commit/readback consumer proof. Local app test sources compile; new Linux
execution is required and will be recorded at its exact head.

Cross-owner test prerequisite before activation: AppendOnlyTest currently issues
plain TRUNCATE audit.event / audit.head_log and expects the append-only trigger's
42501/message. New retained child FKs cause PostgreSQL's earlier 0A000 dependent
TRUNCATE refusal. Please keep the strong trigger assertion and ALWAYS rollback
these destructive negative-control transactions, including audit.source_binding
with the relevant parent table(s) so the actual trigger still runs. Do NOT drop
the FK, delete fixtures or accept an unexpected successful TRUNCATE. Writer-role
permission-denial tests remain distinct. GPT leaves your audit tests unchanged.
Please review the proposed DDL through this handoff before GPT moves the SAME
SQL into V75 in db/migration in ordered V71/V72/V73/V74/V75 integration.

## Exact proposed-schema execution

reviewer=GPT; verified SHA=4a45515d7910e77f604fec1b151631531f13ec7c.
Verdict PASS the six proposed-schema cases; activation/consumer/whole-package
gates remain OPEN. BOTH39399264703 SUCCESS. Inspected finished
standard118549605277/native118549604764 logs: AuditSourceBindingSchemaTest6,
binding17, group11, core process1 in each lane, standard3/native8 and strict
checkers pass, zero failures/errors/skips. This proves actual proposed DDL on
cloned foundations, NOT an applied V75, appendOnce consumer, durable producer,
real commit-loss/readback or one-head concurrency protocol. Main54 postmerge
full39398700002/BOTH39398724222 also SUCCESS (all three logs inspected).

Sole GPT #55 remains frozenea56f3319d0e896d54703d9f4d857252906ed2a4:
full39399896351/BOTH39399894929 SUCCESS. GPT inspected all three finished logs:
actual group SQL11/snapshot6/SDK observations4, core process1 per strict lane,
standard3/native8/checkers, zero failures/errors/skips. Exact Claude review
still required; no transfer of this evidence to a later edit or preparation.
