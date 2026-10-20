# WP-108 manual DATA process-death recovery preparation

Owner: GPT. Reviewer/consumer: Claude. Preparation; actual operator/authority and
central/Flow activation remain gated. Sole GPT implementation PR #55 stays frozen
ea56f3319d0e896d54703d9f4d857252906ed2a4 pending exact Claude review.

## Change and scope

EffectManualProcessRecoveryIT drives actual standalone EffectManualProcessWorker
JVMs against a freshly created, ownership-verified PostgreSQL database. The
worker uses the real Narayana transaction manager and existing trusted raw
JdbcEffectManualDecisions. It has no fake transaction manager, public operator,
credential, actual policy/evidence verifier or business-target invocation.
Production code and applied/proposed migrations remain unchanged from the
verified storage/readiness foundation. This test does not activate V80/Flow/CDI.

Eight nonparameterized cases require both explicit CONFIRMED and authoritative
refusal FAILED DATA outcomes at three exact death windows:

| Window | Committed history after death | Fresh-process recovery |
| --- | --- | --- |
| AFTER_SOURCE, before ledger outcome/queue closure | Original three sources/intent/open queue; no manual decision/source/delivery | First exact decision commits once |
| BEFORE_COMMIT, after all short transaction work | Same rollback of decision/outcome/closure/journal/source/pending delivery | First exact decision commits once |
| AFTER_COMMIT, before any storage reply/claim cleanup | Original decision, original MANUAL draft/time/source, outcome and closure retained together | Same decision read/record is unchanged |

The other two cases cover a confirmed UNDO decision and a terminal registry.
Committed undo DATA never marks the original compensated by itself; the separate
existing compensation protocol must check the exact linked confirmed undo and
audit receipts before COMPENSATED. Terminal-run history persists, but ordinary
AuditedEffects refuses execution/output. Targets/private authority in these two
component checks are explicit spies; central receipts are TWO-COMMIT DATA fixtures,
not actual atomic appendOnce, authenticated operator or complete recovery proof.

## Attribution, loss and observation

Parent seeds only the original scoped attempted/queued intent (or separately
confirmed original plus queued undo). Child receives the SAME manual id, expected
latest journal id, scope identity, outcome and logical purpose. It reconstructs
the original full operator/PolicyRef/evidence/authorization fixture tuple from
actual registry data, not an epoch or new request. These are deliberately DATA
references, never signed identity/authoritative evidence/allow proof.

After the configured halt, independent parent reads verify all fact/source/
delivery/decision cardinalities, state, full original history prefix and queue
timestamps. No central ACK is invented. Then a fresh RECORD worker, fresh FIND
worker and another fresh RECORD worker must preserve the exact original tuple,
journal id, draft id/time/content and closure; no second manual fact/source.
Rolled-back sequence gaps are allowed; contiguous global ids are not assumed.

There are32 actual workers per lane: eight halts,16 RECORD reopens and eight FIND
readbacks, with distinct manual-worker.log files. Expected halts are AFTER_SOURCE2/
BEFORE_COMMIT2/AFTER_COMMIT4, covering both outcomes plus undo/terminal. Old14
worker.log and13 HTTP process logs remain separate. Exit codes97/98/99 and exact
markers must match; an unrelated crash cannot satisfy a configured death window.

Each observed real JDBC connection records its backend PID. After each worker's
actual exit, the parent waits for those PIDs under that exact owned database to
disappear from pg_stat_activity, using fresh autocommit observations and a finite
deadline. This synchronizes real server cleanup; it never terminates a backend,
removes a lock, retries a failed mutation or restarts a timed-out observation.
Claim/transaction sessions are observed independently, so asynchronous post-death
cleanup cannot make the next worker spuriously race a still-held claim/row lock.

The database is a fresh CREATE gpt_eap_UUID; all workers verify current_database.
Flyway precedes proposed V75/V77/V80 and writes. Drop only that successfully
created database without FORCE or session termination. Child args/logs/object
stores live in unique owned target/process-logs/gpt-manual-* directories; stop
only recorded child process handles and retain files. CI-only credentials stay in
fixture environment, not arguments, payloads, fingerprints or logs.

## CI and verification at publication

Both pinned real Keycloak lanes now select EffectManualProcessRecoveryIT. New
--effect-manual-recovery requires all eight exact class/method identities,
unambiguous consistent non-skipped XML and the matching actual profile, PLUS all
prior real provider/core1/source-ACK4/HTTP7 gates. Preflight requires worker/test/
database/V80 source presence only, never execution. Six added parser controls
cover incomplete windows, skip/error/failure/counter corruption, wrong identity/
profile, ambiguity, malformed/oversized XML and CLI gate preservation.

Author verification=GPT, not peer approval. Local all93 app test sources compile
with Maven reactor test-compile -DskipTests. Standalone worker --self-check boots
the actual Narayana manager; tools94, both strict source preflights and authored
diff/generated checks pass. No local PostgreSQL/provider/process execution claim.
Fresh exact Linux BOTH execution/complete reports/process-log inspection pending.
Prior e1c2d84/BOTH39457012587 storage26/HTTP7 evidence is NOT new manual-process8
proof and no approval/CI is transferred to this source.

## Explicit handoff and remaining gates

Claude: consume the existing manual-storage handoff and reuse the existing
unmerged #53 CallerAuthority/RESOLVE_EFFECT/operations.resolve-effect interfaces
after reviewed integration. Actual signed/current operator/full scope/oa.recover,
authoritative evidence retention/validation, original policy attribution/final
mutation-result fencing after blocking work and mandatory real atomic central
consumer remain required. No raw DATA record/readback supplies those permissions.

Review frozen #55 before later ordered GPT preparation can land. This increment
tests manual STORAGE process-loss, not an authenticated operator request or full
Flow/manual process/old-owner/partition/restore/correction/retention. T03/WP-108,
exact peer/final PR and production adoption remain open. No second GPT PR, merge,
public endpoint, activation or package/acceptance promotion. Tracking stays11
completed/13 partial/29 not started; acceptance3/39.
