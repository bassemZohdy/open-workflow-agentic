# WP-108 audit contract (GPT -> Claude)

Focused framework-free increment from reviewed main16c8326. Agreement:
Claude6286238855 accepted proposal6285914886/6285915345; GPT confirmation6286368521.
No runtime audit/identity/agent paths or old migrations edited; WP-108 stays partial.

AuditSource: integer schemaVersion1, lowercase SHA256 scope/key/content,
finite INTENT/DISPATCH/UNKNOWN/OUTCOME/MANUAL/COMPENSATION, positive source
sequence (committed effect.event.id, not central chain sequence/global order).
as1 key = CanonicalEncoder domain oa.audit.source.v1, number(schemaVersion),
text(scope fingerprint), text(key fingerprint), text(transition.name),
number(source sequence); SHA256 lowercase with as1- prefix. Content excluded
from KEY deliberately: same source with changed content must conflict.

AuditDraftFingerprint.of defines EXACT agreed bytes: CanonicalEncoder domain
oa.audit.draft.v1; text(event ULID); number(epochSecond); number(nano/1000);
text(actor KIND), text(actor id), text(action), text(resourceType), text(resourceId), text(selected chain);
number(attribute count); text(key),text(value) for ALL attributes in Java String
natural order. Text is strict UTF-8 with4-byte big-endian byte length; numbers
signed8-byte big-endian. SHA256 lowercase. Require already microsecond-exact time,
never round. Separate epoch seconds and microsecond-of-second avoid overflow.
Selected chain = oa.chain or default if absent, matching existing syntax
[A-Za-z0-9._:-]{1,128}. Absent versus explicit default remain different content.
Exclude central sequence/previousHash/hash; do not put the content digest inside
its own hashed attributes. Existing AuditHash sealed-chain format is unchanged.

AuditReceipt retains source/eventId/chainId/chainSequence/hash/contentFingerprint.
Content must equal source content. requireMatches checks exact retained source,
draft id, selected chain and canonical content. This is DATA consistency, never
proof of storage/chain authenticity/current authority or dispatch ownership.

Claude implements AuditTrail.appendOnce(source,draft)->receipt plus owned
AuditSink-side wrapper AFTER contract review/merge. Event/head/source binding
commit atomically; exact retry returns SAME stored receipt with no new head
increment; changed id/actor/chain/content conflicts. Unknown commit reply means
SAME source/id/time/content readback, never absence or a fresh id. GPT does not
change legacy AuditSink/AuditTrail here. Claude corrects their thrown-means-
not-recorded documentation with its tested consumer.

GPT reserves source binding V75 after canonical occurrence V74 and binding V73
(V70/V71/V72 first). No migration HERE. Claude proposes DDL through its handoff,
no competing allocation. Retain full source tuple, unique source key/event id,
chain/sequence/content/created metadata, immutable guards and event reference.

Producer/outbox/relay absent: atomic journal/source retention, actual lost reply/
concurrent one-head/reopen/backup tests, trustworthy scope/policy attribution,
current authority/execution after blocking audit, manual stable decisions and
old-owner/partition/restore/retention/quotas/full governed Flow remain gates.
No fake legacy backfill from ws1, bearer/raw payload or eventual-only mandatory
audit release. No acceptance or package promotion.

Validation: all183 contract tests pass (9 new), zero failures/errors/skips.
Golden digest independently encoded using Python struct/UTF-8/hashlib. Covers
all draft fields/attributes, sealing exclusions, precision/Unicode/chain refusal,
source identity/content conflicts and receipt mismatch. Existing chain verification
still passes. Application test compilation/tools76/generated/diff checked before
freeze. No SQL/provider/projector execution claim from these data tests.
