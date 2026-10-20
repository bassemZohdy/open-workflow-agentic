# WP-108 immutable manual decision storage preparation

Owner: GPT. Consumer/reviewer: Claude. Preparation, not operator activation.
Sole GPT implementation PR #55 remains frozen ea56f3319d0e896d54703d9f4d857252906ed2a4
with exact Claude review absent. No second PR or approval/CI transfer.

## Producer, consumer and affected paths

GPT supplies EffectManualDecision in oa-contracts/governance, explicit
JdbcEffectManualDecisions and extended EffectAuditProducer in oa-runtime/effect,
proposed db/proposed/V80__effect_manual_decisions.sql and matching contract/owned
SQL tests. ADR0033 records the decision. Existing applied migrations and V77
proposal are unchanged. V80 publication sequences after V71..V79, outside Flyway;
its direct storage dependencies are V63/V70 plus proposed V75/V77. No speculative
legacy provenance/evidence/decision backfill or id allocation on retry.

This is the missing shared durable DATA foundation for Claude's actual verified
operator/evidence/policy producer. It is not that producer or a final mutation
permission interface. The store has no CDI bean, public endpoint, credential,
policy callback, target/provider dispatch or automatic Flow advancement. Trusted
raw storage acknowledgment is not a policy-verified operator success response.

## Exact immutable data and concurrency contract

EffectManualDecision retains original ULID, full tenant/registry instance/owner
kind+id, exact existing ef1 key/node/occurrence/target/request fingerprint, expected
latest journal id, explicit CONFIRMED or FAILED, full operator, original PolicyRef
and authorization/evidence SHA256 references. Reason codes are fixed safe
operator.evidence.confirmed / operator.evidence.refused. FAILED requires actual
authoritative refusal/nonapplication evidence; never infer it from timeout.

The v1 decision fingerprint encodes domain oa.effect.manual-decision.v1, textual
schema1, original id, full scope, complete command, DECIMAL expected journal id,
outcome/operator/policy/ref fingerprints, all as length-prefixed strict UTF-8.
Unicode/delimiter/control semantics are exact, no normalization or locale. Golden
vector (independent Python encoding) is in EffectManualDecisionTest; actual SQL
recomputes the same full tuple on insertion. Hashes are attribution DATA, not
proof that evidence, an allow decision, signed operator or scope permission exists.

The actual evidence reference must resolve to a retained, scoped, authoritative
record under an approved custodian. This metadata does not implement evidence
storage/provenance/validation. Original policy attribution is retained after
refresh/retry; current reauthorization is a separate future private operation and
must not replace the original tuple with a new fingerprint/policy version.

The store suspends caller JTA, acquires the same session advisory effect claim as
DurableEffects, then locks registry -> ledger -> queue. All owned short statements
use lock2s/statement5s. Validate actual full registry owner, retained audit_scope
and initial/latest sources, complete command, attempted INTENDED, open queue and
latest journal CAS. A competing active executor/manual component fails promptly;
new current history, outcome or second id refuses. Exact id+tuple retry reopens
the original decision/draft/time even after subsequent compensation. A different
effect cannot reuse the id. No target is called by this persistence operation.

ONE transaction inserts journal+manual decision+original MANUAL draft/pending
delivery and updates ledger outcome/queue closure to that same journal time.
Deferred DB checks/FKs require all of them together, even if application code
omits an outcome/source. Fingerprint/scope/history guards and immutable parent
FKs prevent rewrite/delete/truncate. Atomicity is PostgreSQL, not caller JTA.
Before-commit failure rolls everything back; an actual committed reply loss leaves
the same decision/source for a fresh component to reopen, without another event.

Manual audit retains service system:effect-ledger as operational actor, adding
bounded manual.* operator/policy/evidence/decision attribution. MANUAL is now
produced in as1, and checked using the same AuditDraftFingerprint as other facts.
Other transition drafts remain byte-compatible. Existing central delivery/barrier
handles the MANUAL tail, including on a cached CONFIRMED/FAILED result. Real audit
dependency failure must continue holding private output/advancement.

A terminal registry does not delete unresolved business history. This trusted
storage can retain that decision DATA after termination; ordinary AuditedEffects
still refuses that registry. Actual recovery permission/current output rules are
not relaxed. An undo decision resolves only the separate undo key; original stays
CONFIRMED until the existing separate compensation protocol checks the exact
confirmed undo/link/source and retains COMPENSATED.

## Verification at initial publication

Author verification=GPT, not peer approval. All220 contracts pass locally,
including eight new cases and the independent Unicode vector. All91 application
test sources compile via Maven reactor test-compile -DskipTests. Twenty-five new
owned actual PostgreSQL test cases compile but have no local execution claim.
Initial aeed0ffacf034336062eeb6c27c173c9d5d1435b/BOTH39453457278 FAILED:
manual SQL25 had24 passes/one fixture expectation failure in EACH lane. V77's
reused audit_no_change correctly rejects immutable changes with42501; the new
retention test incorrectly expected23514. Corrected to exact42501 PLUS the actual
retention-guard message, preserving refusal and strengthening attribution to the
intended guard. No product SQL, permission, validator or mutation rule changed.
The original assertion stopped at UPDATE; DELETE/TRUNCATE require the corrected
fresh run too. No failed assertion was rerun unchanged. Fresh exact Linux BOTH
execution/complete inspection is pending at the corrected source.

Both finished failed logs inspected (standard118734067332/native118734068001).
Both actual archives12280545199/12280425264 downloaded by id; raw bytes/digests/
CRC/paths checked. All157 actual XML per lane parsed: EXACTLY that single failure,
zero errors/skips, contracts220/runtime351/testkit196/app416. No actual Failsafe
XML exists, so provider/HTTP/process success is NOT inferred from this run.

The SQL tests create only a fresh gpt_eap_UUID database, verify ownership, run
Flyway before writes, apply actual proposed V75/V77/V80 and drop only that owned
database without FORCE/session termination. Cases cover both decisions/exact
reopen, changed full tuple/id/scope/history, legacy/unattempted/nonqueued/resolved
refusal, actual claim/concurrent winners, actual commit before/after reply failure,
source-insert failure, deliberate omission caught at DB commit, row retention,
caller JTA rollback/interruption, pending manual receipt/cache hold, terminal
history and separate confirmed undo. No policy/grant/audit parent validators are
disabled to make a fixture pass. Central receipts in the final boundary cases
are explicit TWO-COMMIT DATA fixtures, not the actual atomic appendOnce consumer;
private authority and targets there are protocol spies, not actual operator proof.

## Explicit Claude handoff and remaining gates

Current unmerged #53 d38776c already contains CallerAuthority/resolveAuthority,
Pep.InstanceOperation.RESOLVE_EFFECT and operations.resolve-effect in the existing
registered operations.cedar/snapshot. GPT re-read those exact detached sources;
reuse these interfaces after their reviewed integration, rather than introducing
another policy file or equivalent token handle. Their scoped unit/provider proof
does not establish an actual manual mutation/evidence/result path.

Supply that actual current signed operator/tenant/full-owner or authorized
delegation producer, consuming explicit OPERATION operation:resolve-effect and
verified oa.recover. Do not reuse resolve-signal or DEV_HEADERS/UNVERIFIED. Request
GPT manifest/registration changes only if a concrete additional owned policy path
is needed. Bind genuine authoritative evidence and original Decision.policy plus
the authorization fingerprint; define canonical authorization attribution and the
retained evidence custodian. Never mint a dummy allow decision/evidence/grant.
The existing Cedar comment describing manual resolution as release for another
attempt needs clarification by its owner: FAILED is an authoritative final refusal,
not permission for a fresh occurrence/key or an automatic resend.

Review/propose the minimal ephemeral final mutation/current-result seam so
revocation/expiry/owner/history changes after ALL blocking audit/storage/policy
work refuse mutation/output. The raw data record and .record are not that seam;
GPT must integrate the reviewed producer before any public/manual/Flow caller.
Actual mandatory atomic central appendOnce/chain verification remains your owned
dependency. No forged tuple, earlier allow or local ACK may grant permission.

Prove real ordinary operator positive and forged/cross-tenant/full-owner/delegated,
expired/revoked-during-wait, stale/competing decision, policy/audit/store outage and
lost reply denial/recovery with zero unintended target calls. Keep original id
and full tuple on retries; never overwrite/correct it with another id implicitly.
Manual process/partition/restore/final authority/Flow/retention/correction protocol
and full T03/WP-108 remain open. Only GPT integrates after exact peer/current CI.
Tracking remains11 completed/13 partial/29 not started; acceptance3/39.
