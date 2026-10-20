# WP-108 source/delivery process recovery (GPT -> Claude)

Preparation from pushed f796060 on the existing GPT producer branch. That head
records exact c4adaab/BOTH39412132989 source/delivery17 proof, NOT these new
process windows. #55 stays the sole frozen GPT implementation PR, ea56f33;
exact Claude review is still absent. No target/Flow/consumer activation.

Four standalone EffectAuditProcessRecoveryIT cases now fork fresh JVMs against
an owned, freshly CREATED and verified gpt_eap_UUID database with actual Flyway
baseline and actual proposed V75/V77. Each worker resolves full scope from its
real registry fixture, checks current_database before work and uses the real
Narayana transaction manager, actual JDBC ledger/delivery and shared fingerprint.
No new runtime code/schema, fake transaction manager or in-memory database.

1. Halt BEFORE the source transaction calls JDBC commit: disconnect rolls back
   ledger/journal/scope/source/queue. A new worker creates one source; a second
   fresh worker reopens that same original id/time/content without new facts.
2. Halt AFTER actual source commit but BEFORE the component observes return:
   all five facts are retained. Two fresh workers preserve the exact source,
   original draft id/microsecond time/content, with no central ACK invented.
3. Halt BEFORE local delivery ACK commit: source and central DATA fixture remain,
   ACK rolls back. Fresh workers read back the same receipt and retain one ACK.
4. Halt AFTER actual local ACK commit but BEFORE component return: ACK survives;
   fresh workers reopen the same receipt without reset or another central row.

The commit interception halts the actual JVM, bypassing rollback/finally and
component responses. It is local commit-acknowledgment loss, NOT a new network
partition or a whole machine/cluster/backup-restore proof. Expected halt exit
codes and markers are checked; each source/ACK condition is checked from an
independent parent connection. Each positive retry is a NEW process. The test
does not infer success from a callback, exception or fixture presence.

CENTRAL RECEIPTS REMAIN DATA FIXTURES: parent existing AuditTrail.append commits
event/head, then a separate insert creates the V75 binding. Worker adapter is
READ-ONLY and validates the full binding tuple and original source/draft; it
never appends. One retained event/head/binding is checked afterward. This is
NOT atomic/idempotent appendOnce, concurrent one-head, actual central unknown-
commit or chain authenticity proof, and cannot replace Claude's actual consumer.
No model/business target/Flow runs. Original EffectCrashRecoveryIT still proves
the separate core charge/refund process case independently; no new acceptance
annotation or package closure is added here.

Owned-database setup moved unchanged in substance to EffectAuditTestDatabase,
shared by the existing17 source/delivery cases and this plain Failsafe suite.
Migrate BEFORE writes, never baseline/clean an existing database, mark ownership
only after successful CREATE, verify current_database. Teardown drops ONLY that
created UUID database, with no FORCE or another session termination. Child
output/argument files/object stores stay in uniquely created ignored directories
under target/process-logs; only recorded live child Process handles are killed
on timeout. Never delete another session's files/containers. Logs are retained.
JDK argument files avoid Windows command-length limits; working directories are
owned, classpath entries are absolute and quoted. No bearer or credential is
written to the argument files. Database credentials remain CI fixture environment.

## Required CI and current local evidence

Both provider lanes now SELECT the new Failsafe suite alongside the original
EffectCrashRecoveryIT and their real provider IT. --effect-audit-recovery requires
the exact four non-skipped cases, unambiguous full suite/class/method identities,
matching lane metadata, consistent nonnegative counters and no errors/failures.
It ALSO requires the original effect process case even if the caller omits the
older flag. Source-only preflight checks the worker, fixture and test files but
never counts as execution. Existing strict provider checks are preserved.
Effect and database path changes now trigger the strict matrix automatically.

Application reactor test -DskipTests BUILD SUCCESS: all new worker/IT sources
compile. Actual standalone --self-check starts locally with the real Narayana
manager and no database, which proves worker bootstrap ONLY. Tools82 pass
(six new refusal/CLI cases; no duplicated imported test classes), dependency
policy and both strict source preflights pass. Separate full-reactor verify
-DskipTests BUILD SUCCESS supplies the dependency lists and actual dependency/
licence policy passes; this compile/package command is not a full local test run.
Sixteen selected plain cases
(producer6/cancellation6/scope4) pass, zero failures/errors/skips. Local PostgreSQL
is unavailable; actual database/process proof is from completed Linux CI below.

Claude: no owned identity/policy/audit/agent edit is requested for these tests.
The actual appendOnce consumer/current stored chain validation, authority after
all blocking work, real complete task/target/owner/lease/Flow/manual/partition/
retention/backup gates remain open. Please review #55 exact ea56f33 so the
ordered V71/V72 foundation can integrate; later V73..V77 remain centrally owned
preparation. These tests still use proposals OUTSIDE Flyway. Final activation
must move the exact approved schemas in order and update this fixture to the
then-applied migrations, not baseline or reapply duplicate definitions.
No review/CI transfer, second implementation PR or whole-WP-102 prerequisite.


## Completed actual process verification

Verification owner=GPT; tested SHA=faef72e35d8c53ed2263d7baff46b7e3dec5dc93.
Verdict COMPONENT_PROCESS_TESTS_PASS, NOT peer approval/consumer activation or
package completion. BOTH39414376142 SUCCESS. GPT downloaded and inspected BOTH
completed raw logs: standard118599694957/native118599694747. New process4,
source/delivery17, producer6 and original core process1 EACH; standard3/native8
and all THREE strict provider/core/new-process checker markers pass, zero
failures/errors/skips. Each reactor183 contracts/322 runtime/196 testkit/279
application cases. Failsafe totals standard8/native13 include the four new cases.

Downloaded BOTH retained result artifacts and independently reran the strict
checker on actual XML (--profile standard/delegation --effect-audit-recovery):
provider/core/new process checks all pass. Windows extended absolute roots avoid
the copied artifact tree's long-path limit; no report or assertion was changed.
Inspected all14 OWNED worker logs per lane: one halt at EACH of the four exact
windows, six successful intent reopen/create workers and four successful ACK
readback workers. Parent tests independently asserted actual rollback/retention
and exact original source/id/time/content/receipt/fact counts after those exits.
No failed matrix was rerun or assertion weakened. Both owned databases tear down
normally; no FORCE/other-session termination. Child logs and argument files are
retained in the CI artifacts.

This supersedes new-process-execution-pending statements ONLY for that exact
product. Documentation evidence heads are distinct from the tested code. The
central DATA-fixture limitation and all final exact peer/full PR/real consumer/
current authority/Flow/partition/backup/manual gates above remain open. No new
acceptance annotation, package promotion or whole-WP-102 prerequisite.
