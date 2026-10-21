# WP-107 review N1/N2: selected-membership completeness candidate

Reviewer=GPT (author verification, not peer approval).
Reviewed product SHA:c2972fa00972978e98173b172385625f0237e349.
Historical final review candidate5522bce89876b6f1a0105f8b10221007db050757 passed BOTH39519320909; current-main reconciliation below supersedes its pending review request.
Parent is exact Claude-reviewed #55 ea56f3319d0e896d54703d9f4d857252906ed2a4.
#55 stays at that head while this separate GPT candidate is reviewed; no new PR.
Verdict: author verification PASS; source/application-test compilation/tools76 and exact BOTH real PostgreSQL16 cases PASS. Exact final candidate peer review and required PR checks remain pending.

## Finding and additive correction

Claude review6304103326 APPROVEs the scoped parent and independently demonstrates
N1: direct SQL can retain matches0/1, insert only membership0 and advance the
ALL group. GPT read confirms the old guard requires a current item and at least
one member, not coverage of every retained match. N2 is the wording follow-up.
This candidate addresses the finding before integration.

New V72_1__require_complete_selected_membership.sql is additive Flyway72.1,
after72 and before the already reserved73/74; V71/V72 and all applied migrations
are unchanged. An immediate AFTER UPDATE guard verifies every retained match
for the new sequence has its same-role/ordinal/fingerprint membership at that
sequence, or an EARLIER EXCLUSIVE membership. Group digest/receipt role must
match. Earlier nonexclusive ANY memberships cannot substitute. The existing
BEFORE guard retains owner/registry/claim/pinned-deadline/capture checks; the new
read-only AFTER guard adds coverage and aborts the statement with42501 on failure.
No callback permission, predicate truth or execution authority is introduced.

Review also found that an intentionally committed raw-SQL pending item could
appear in selected() without a group advance. Bounded reads now stop at the
retained group's last finalized sequence, and replayed offer refuses an item
beyond it. Pending evidence remains retained; no row is deleted or silently
repaired. Existing already-finalized malformed historical data is not rewritten
or certified by this migration; validate/quarantine legacy history before actual
consumer adoption. No complete hostile-DB-writer or full recovery claim.

## Actual cases added

Five new real PostgreSQL test cases make ListenGroupStoreTest16 total:

- Partial ALL direct SQL advancement receives42501; rollback leaves zero item/
  member rows and no locks, and the exact same receipt succeeds through Java.
- ALL may omit a slot when an earlier exclusive membership already owns it.
- A new ANY receipt cannot borrow an earlier NONexclusive membership to cover
  a declared match; rollback preserves history and a valid new selection succeeds.
- ALL-until may reuse an earlier exclusive UNTIL slot.
- A deliberately committed unfinalized raw item remains stored but is not
  selected and cannot be replayed as a finalized selection.

These are scoped DATA storage checks with trusted match/filter fixtures, not
actual selector/predicate/transport callbacks, checkpoint ACKs or T13.

## Ownership, evidence and review gate

Changed paths: applied migration directory (new72.1 only), JdbcListenGroups.java,
ListenGroupStoreTest.java, this handoff, bounded review/storage wording, source
tracking/CHANGELOG/generated TODO. GPT owns these paths. No Claude branch or
shared checkout is edited. Proposed/applied V73/V74/V75 contracts are unchanged.

Local Java25/release21 application reactor test -DskipTests compiles all app
sources, including16 store cases; it does NOT execute PostgreSQL/Quarkus tests.
Tools76/generated/diff checks pass. No local DB/native/provider proof claimed.
Exact product BOTH execution and inspection are recorded below. No old green
result or parent Claude approval is transferred to the new code. Request Claude
review of the final exact candidate SHA; only then fast-forward
#55 to that same commit, await current required PR checks and integrate. No force
push, second implementation PR or package/acceptance promotion. WP108 resumes
through small reviewed prerequisite increments after this gate clears.

## Exact product Linux/real-provider verification

Reviewer=GPT, author verification of productc2972fa00972978e98173b172385625f0237e349.
[BOTH39518381586](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39518381586)
SUCCESS, attempt1/no rerun; standard job118955956470/delegation118955956575.
Each full Maven verify log records contracts192/runtime328/testkit196/app278,
and ListenGroupStoreTest16 with zero failures/errors/skips. Failsafe standard4
(real Keycloak3/core process1), delegation9 (real Keycloak8/core process1), all
zero failures/errors/skips. This is actual Linux PostgreSQL execution of the
five new cases plus retained eleven, not local compilation or skipped provider
tests. Exact source tests remain unchanged after this tested product.

The parent workflow archives only failsafe XML/process logs, not Surefire XML.
SQL16 evidence is therefore the complete actual job logs and exact suite count;
there is no claim to have inspected archived individual SQL method XML. Actual
two failsafe suites per lane were parsed, counters/cases/zero skips and exact
method names compared with current source. Both current strict checker reruns
with --profile standard/delegation --effect-recovery pass. Four process-worker
logs per profile are retained and empty because these older workers are silent;
the actual passing process case asserts halt/recovery exit codes77/0, retained
ledger states and one charge/one refund, not log messages. Later audit/HTTP/manual
recovery preparation is outside this candidate's parent and is not claimed.

| Profile | Artifact ID | Bytes | SHA256 |
| --- | --- | --- | --- |
| standard |12310256673|33645|00c407802c45a50f3483ed5f2e8e4d4b148ef665c868179ae097567b15dffdd3|
| delegation |12310615691|33836|34488c055821928cc000c930dedb2070dc09a93d6145234f96ecf99c599d9624|

Actual archive bytes/digests match GitHub metadata; bounded size/CRC/safe paths
checked. Commands: mvn -B -pl apps/app-runtime -am verify with selected real
Keycloak IT,EffectCrashRecoveryIT, required=true and exact profile/server; current
tools/require_keycloak_evidence.py rerun against actual downloaded reports;
independent XML parsing/log suite-counter checks. Local tools76/generated/diff
pass. Verdict PASS for this scoped correction's author execution verification;
current final-head Claude review and required PR build/test remain integration
gates. No new implementation PR, merge, deployment or acceptance promotion.

## Current-main reconciliation after Claude integration

GPT approved exact Claude #53 a71fb1f (6304838496) after independent review and
exact full39519874483/BOTH39519877815 SUCCESS, then merged it as main2f10662.
Serialized tracking and the concrete central-audit handoff are on mainc20d37f.
This candidate now normally merges that current main. V71/V72/72.1,
JdbcListenGroups and all16 membership tests are unchanged; no old applied bytes
are edited. Tracking conflicts are resolved from both source JSON histories and
generated documents regenerated, retaining each owner's evidence and gates.

Historical exact5522bce/BOTH39519320909 SUCCESS was independently inspected:
standard118959011161/delegation118959011494, SQL16/contracts192/runtime328/
testkit196/app278 EACH in complete logs, Failsafe4/9. Actual archives12311126868
(33630 bytes,SHA25646c099e65b93123dc2d0830b0f4092fbd3f5120f3c355c562bde3f2283085047)
and12310632292 (33814 bytes,SHA256f357969cca82e5ce84ebd33d8444615d4bf8db523c38fb786be093c7a8b11d92)
match GitHub metadata/digests/CRC/safe paths. Exact archived provider/core methods
and both current strict checker reruns pass, zero skips. SQL evidence remains
full-log/suite-count because that workflow archives no Surefire XML.

These results are NOT transferred to the combined main/candidate head. Fresh
combined-head compilation, strict execution and final exact Claude review remain
required. #55 stays at ea56f33; review request6304631674 for the older5522bce
must be superseded by the final refreshed candidate request. No second PR or
package promotion. Current main's model/inspector defaults and outstanding
protected-budget/current-authority/output/actual Flow gates remain unchanged.
