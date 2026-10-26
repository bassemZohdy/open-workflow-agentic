# WP-108 observed audit history — explicit DATA only

Writer GPT; reviewer Claude. Baseline main99631cce87a5471704a3a8d9ddd63f78756bbc89.
New files: runtime/effect/EffectAuditHistory.java and lifecycle/EffectAuditHistoryStoreTest.java.
No existing producer/delivery/target adapter, migration, contract, CDI registration or
scheduler is changed. The old df3604b synchronize method is design provenance only;
its timeout-widening and exception masking are not ported.

## Protocol and exact limits

`observedHistory(scope,key,limit)` means committed-and-visible in the final read
snapshot. It does not cover in-flight or later writes, grant authority, authenticate
an entire chain/history or permit a target. Plain immutable receipt DATA is returned;
there is no minted capability, special approval object or consumer installed by default.
A future target boundary must independently check current caller/workload/registry/full
owner/task/lease/PDP/target and audit after blocking work, including physical async fences.

Each read is transaction-local REPEATABLE READ / READ ONLY (or preserves an inherited
SERIALIZABLE level). SHOW precedes SET TRANSACTION; local settings reset naturally at
commit/rollback. No connection/pool isolation setter or timeout setting is changed.
Existing stricter role timeouts remain unchanged. This bounds cardinality, not elapsed
I/O: acquisition, statements and the trusted sink still need deployment/caller bounds;
there is no whole-call deadline and this is not a production root/adoption claim.

The snapshot checks full retained scope and ledger presence, equal exact journal/source/
queue id sets (all membership anchors, including source scope/key fingerprints), original
INTENT anchor, canonical producer-validated drafts, coherent from/to chain, current
ledger tail state and exact ACK/central binding. IDs increase but need not be contiguous:
other keys legitimately allocate intervening IDs. Extra/orphan checks defend against
restored/corrupt private stores with privileged guard bypass, not a hostile DBA or authenticity.

Every source, including previously ACKed sources, goes through actual trusted appendOnce
readback with all source connections closed. Returned receipts match the FIRST retained
facts. A second snapshot must contain the identical facts, matching ACKs/central receipts
and unchanged prior ACK timestamps/bindings. Growth or changes refuse without an internal
retry; already committed monotone ACKs remain and an explicit retry starts fresh on the
same original sources. Cancellation preserves the flag and partial ACKs. Original work
exceptions retain concurrent JTA resume failures as suppressed; success plus resume failure
refuses a return even after ACKs committed.

Limits are1..128. A history exceeding128 fails closed; paging does not establish complete
observed history. That liveness cliff requires a separately reviewed larger-history
protocol, never pruning protected history or silently accepting a tail. Original and
compensation keys are independent, with no cross-key central append ordering claim.
Historical DATA can be read after registry completion without renewing authority.

## Executed author verification

Actual Java21 / PostgreSQL16.15, common database UUID-owned/verified/dropped per run and
private migrated-through-V77 database per store case. Shared default database was not reset.

- Initial app28: history18 + delivery9 + default refusal1, zero failures/errors/skips.
- Restored family72: runtime28 (actual AuditAppendOnce22 / Producer6), app44
  (history19 / delivery9 / producer15 / default1), zero failures/errors/skips.
- New compensation/terminal-registry case independently passes1 afterward: history now20.
  The exact-head peer rerun must run the complete73-test family.
- Membership guard removal: orphaned non-initial source/queue case fails the intended
  BEFORE-central assertion, expected0 calls / observed1, even though a later SQL guard
  could still refuse. Original production bytes restored.
- Prior ACK preservation removal: changed ACK timestamp during actual trusted readback
  returns when it should refuse; intended missing-refusal assertion fails. Original
  production bytes restored before the72-case family.

History cases include mixed ACK/pending and repeated trusted readback, wrong full owner/
legacy/missing keys, exactly128 versus129, growth, actual second central commit/lost reply,
missing source/queue/ledger, orphan and extra queue, ledger tail mismatch, changed ACK time,
one-slot actual central I/O and real caller rollback, interruption after ACK commit,
two concurrent histories, an actual writer blocked before commit (not covered until later
commit), local isolation/stronger SERIALIZABLE and250ms/500ms timeout preservation, JTA
primary/suppressed and success/resume refusal, and independent compensation after terminal registry.

Local logs: target/audit-history-{author,final-family,membership-negative,ack-negative,
compensation-final}.log. The first Python-launched control runner failed before tests
because it could not resolve the Java21 path; the PowerShell runner uses the actual
installed Java21 and executes both intended controls/restored cases. This infrastructure
failure is not a test result or evidence that Java21 is absent.

Required current Linux CI, exact Claude independent review and raw archive inspection
remain pending. No work-package or acceptance closure is claimed; issues75/76 stay open.
