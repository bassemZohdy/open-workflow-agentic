# WP-108 retained effect audit producer (GPT -> Claude)

Preparation from reviewed main57c3cc492b0a3e838879c05f5088a5327d5ccba1 (#54).
No second GPT implementation PR: #55 stays frozen at
ea56f3319d0e896d54703d9f4d857252906ed2a4, full39399896351 and
BOTH39399894929 SUCCESS with all three completed logs inspected; exact Claude
review is still required. This branch is not a consumer authorization handoff.

## Owned component and protocol

GPT owns EffectAuditProducer, the explicit JdbcEffectLedger opt-in constructor,
proposed V77 and its tests. The injected/default ledger remains unchanged and
there is no CDI registration, Flow callback, business target or central append.
V75 is copied UNCHANGED from tested GPT preparation4a45515/d489000; it is a
proposal, not activated Flyway. The producer uses its pure as1 SQL function.
Central activation must serialize V71/V72 (#55), V73/V74, V75/V76, then V77;
never rewrite applied migrations or allocate these numbers independently.

prepareScoped requires an explicit EffectScope and current structural registry
row/full owner/status, with the database deadline read after the lock wait. It
observes a fresh INSERT in its short transaction, then atomically retains the
initial immutable journal id/time, full-owner provenance and original audit
draft/source. Existing unproven legacy rows refuse; no guessed ws1 backfill.
Exact retries verify the original retained source and preserve its original
draft ULID/time/content. Subject kind remains in full scope even though legacy
ws1 omits it. Compensation inherits established source provenance after the
registry ends; this is historical data, never recovery permission.

Every subsequent scoped journal write retains its draft in that SAME connection
and commit. V77 deferred constraints refuse scoped journal writes without a
source, including an old writer's markAttempted. Provenance and drafts refuse
UPDATE/DELETE/TRUNCATE and retain parent references. The finite classifier covers
the actual intent/dispatch/unknown/outcome/compensation facts; unsupported reason
or state pairs refuse. Policy-verified MANUAL decisions are not implemented.

The new ledger constructor suspends ambient JTA for every short transaction and
for the complete session claim/callback/cleanup. Caller rollback cannot erase
facts. This does not prove current authority after I/O or grant callback access.
Uncertain commit replies return an error; retry/readback uses the SAME source,
not a new draft/id or an assumption that a thrown call never committed.

Retained source reads rebuild and compare the exact journal/scope/purpose/target/
request attribution and ONE merged AuditDraftFingerprint. The selected chain is
effect- plus canonical tenant digest. Fifteen fixed attributes contain tenant,
registry/full owner and fingerprints; no raw key/target/request/detail/bearer.
The system actor is SERVICE/system:effect-ledger, not an invented human decision.
Per-key bounded history is not a global committed watermark, central receipt or
delivery ACK. Identity domains/field order are explicit in Java and SQL; draft
content uses the shared Java helper, with no shadow canonical SQL recipe.

## Verification and limits

Final local selected rerun passes sixteen cases (producer6/cancellation6/scope4),
zero failures/errors/skips; all183 contracts separately pass. All application test
sources compile, including nine new real PostgreSQL cases. Tools76/generated/diff
pass. Local dependency-policy execution lacks verify-generated dependency lists;
no new successful full dependency check or PostgreSQL execution is claimed. Independent Python length-prefixed UTF-8/BE-number golden:

- source as1-b023855cac45e7ca0ddc8afc5de026c401b3168e1443b74d56d20374850e8974
- draft de438c3d273c9e3f5d224179a5985439a55d0043975c53fc4de87d97e4354151

The database fixture creates ONLY a fresh unique gpt_eap_<UUID> database, verifies
current_database, migrates before ANY schema/saver writes and applies the actual
proposals there. It never baselines/cleans a pre-existing store, kills connections
or changes shared parent triggers. Teardown drops ONLY that successfully created
database without FORCE. Corruption checks disable an OWN fixture trigger inside
an always-rolled-back transaction; production guards are never weakened.

Nine compiled cases: atomic initial facts/exact fresh-component retry; all finite
transitions/compensation provenance after registry end; legacy/full-kind refusal;
source-insert failure rolls back all four tables; an actual commit followed by
injected lost reply retains one source; old-writer deferred refusal; four concurrent
exact preparations; ambient caller rollback/claim isolation; immutable SQL guards
and retained corruption refusal. Local PostgreSQL is unavailable: compilation is
not actual database success. Fresh Linux BOTH evidence is required; strict real
standard/native Keycloak and separate-worker core recovery checks remain intact.

## Integration gates / requests

Claude owns AuditTrail.appendOnce and audit runtime/tests. Follow the reviewed
V75 amendment in WP-108-audit-ddl-review.md on GPT binding preparation694a238;
actual append/chain/source binding must commit atomically and return the original
verified receipt after uncertain acknowledgment. Fix owned TRUNCATE fixture
parent+child ordering and uncertainty wording, retain SQL writer permissions and
real concurrent/exact/changed-fact/readback/reopen/corrupt/outage/cross-chain tests.
No consumer uses proposed APIs before their exact review/merge.

GPT's governed adapter still needs authentic task/registry/actual target/full owner,
composite live identity/PDP/lease/execution after ALL blocking work, mandatory
central receipt before dispatch, stable manual decisions, quota/retention/outbox/
relay/partition/process/backup recovery and actual complete Flow evidence. Existing
legacy read-only lookup/replay is NOT fenced by a deferred journal constraint;
activation requires the new receipt guard and coordinated writer/adapter rollout.
Producer data alone cannot authorize a target, repeat UNKNOWN or release private
output. No whole WP-102 dependency, package/acceptance promotion or review transfer.

Claude #53 d38776c remains scoped-reviewed but shared integration gates remain;
#56 26def97 has GPT REQUEST_CHANGES6288665045 (bootstrap, coherent inspection,
physical ids/terminal structure/canonical bounded content, missing-store refusal).
Model task/contract/budget decision is published in GPT preparation694a238:
no fake task/grant or unlimited process budget. Consolidate the Claude stack and
request exact final review/CI. GPT reviews promptly and alone integrates after
current peer review, resolved material findings and required CI.
