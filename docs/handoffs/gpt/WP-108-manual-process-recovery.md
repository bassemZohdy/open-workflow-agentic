# WP-108 manual DATA process-death recovery preparation

Owner: GPT. Reviewer/consumer: Claude. Storage-process preparation verified; actual operator/authority and
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
Fresh exact Linux BOTH execution/complete inspection is verified below; it was
pending at initial publication.
Prior e1c2d84/BOTH39457012587 storage26/HTTP7 evidence is NOT new manual-process8
proof and no approval/CI is transferred to this source.

## Verified exact-source execution

reviewer=GPT (AUTHOR verification only, not peer approval); reviewed/tested SHA
**fd69ee157ed6edcde7f9fc9eea48b4912466d5af**. Verdict: manual STORAGE process
preparation PASS; integration HOLD for exact peer/final PR and actual operator/
central/current-authority/Flow adoption. Later handoff/tracking commit changes no
code and is not mislabeled as the tested source.

[BOTH39460706991](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39460706991)
is terminal SUCCESS at that exact SHA, first attempt/no rerun. Both completed
full raw logs downloaded/inspected: standard118759125008/native118759125559.
EACH executes contracts220/runtime351/testkit196/application unit417 including
manual storage26/contract8/boundary31/source17/model24/allocation15. Failsafe
counts standard23/native28: NEW manual process8/HTTP7/source-ACK4/core1 EACH,
real standard3/native8. All five strict evidence markers pass; failures/errors/
skips0. No assertion was retried unchanged or weakened.

Actual archives downloaded by exact id, raw bytes independently matched to
GitHub SHA256, CRC and safe owned extraction paths verified:

| Lane / artifact | Bytes | SHA256 |
| --- | ---: | --- |
| standard12283356951 | 2540735 | 641087e6868617d8b735e6abc96323c1e0afc1241b33b0c4722806682682574e |
| native12282014878 | 2537536 | 87d0010f46f6a6bc324fe72415e3d3bebf150a4104393ffb70d109dd1d2f162d |

ALL162 actual XML per lane parsed: positive consistent suite/case counters,
unique identities and no failure/error/skip. Exact current process8/storage26/
contract8/HTTP7/boundary31/model24/allocation15 method sets match source. Reran
`python tools/require_keycloak_evidence.py --root <actual-lane> --profile
<standard|delegation> --effect-recovery --effect-audit-recovery
--effect-http-recovery --effect-manual-recovery` against EACH actual artifact,
not preflight/synthetic XML.

Inspected all32 manual-worker.log plus their actual argument files per lane:
eight distinct ORIGINAL decision tuples, four fresh workers each, exact same
scope/id/history/outcome/purpose across RECORD/FIND/RECORD;16 record/eight read
markers. Exact halt matrix: ROOT CONFIRMED/FAILED at AFTER_SOURCE and BEFORE_COMMIT
once each; ROOT CONFIRMED AFTER_COMMIT twice (ordinary+terminal), ROOT FAILED once,
UNDO CONFIRMED once. AFTER_SOURCE2/BEFORE_COMMIT2/AFTER_COMMIT4 match planned exits.
All56 real backend PID observations have valid positive distinct per-worker
counts (two per record/halt, one per find). Executed parent assertions wait for
those sessions' disappearance and independently check database state, sources,
queue timestamps, full decision/draft/time/content and no extra mutation.

Also inspected old14 worker logs and13 HTTP logs per lane, retaining all prior
source/ACK/HTTP halt/reopen markers. Each lane has one owned WAIT_CHARGE cleanup
InterruptedException with committed/release files; timeout/business assertions
pass. Standalone fixture bootstrap diagnostics are retained, not hidden or called
application/test failures. Actual bounded conformance reports/48 outputs/74
exports/two known diagnostics checked per lane; only the two already bounded
wrong fork-order fingerprints projected, still30 MATCH/18 DIVERGED. No complete
semantic-conformance or operator/authority/central proof transfers.

Local93-source compilation/real standalone Narayana self-check/tools94/preflights/
dependency/generated/authored diff pass. Compared main runtime/contracts/schema
and acceptance-status trees to prior97e5e54: unchanged. Storage8 process evidence
closes only these raw DATA commit-death windows; full governed manual request/
Flow/old-owner/partition/restore/correction/retention/T03/WP-108 remain open.
Publication handoff: [comment6296240827](https://github.com/bassemZohdy/open-workflow-agentic/pull/54#issuecomment-6296240827).

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
