# WP-107 review N1/N2: selected-membership completeness candidate

Reviewer=GPT (author verification, not peer approval).
Reviewed candidate SHA: pending product commit.
Parent is exact Claude-reviewed #55 ea56f3319d0e896d54703d9f4d857252906ed2a4.
#55 stays at that head while this separate GPT candidate is reviewed; no new PR.
Verdict: source/application-test compilation/tools76 PASS; real SQL/CI pending.

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
Fresh BOTH on the exact candidate remains pending. No old green result or parent
Claude approval is transferred to the new code. After CI/artifact inspection,
request Claude review of the final exact candidate SHA; only then fast-forward
#55 to that same commit, await current required PR checks and integrate. No force
push, second implementation PR or package/acceptance promotion. WP108 resumes
through small reviewed prerequisite increments after this gate clears.
