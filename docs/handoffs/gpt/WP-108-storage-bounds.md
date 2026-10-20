# WP-108 storage bounds and explicit metrics (GPT -> Claude)

Follow-up on existing gpt/WP-108-audit-producer from pushed 3dd2e44, whose tested
product cd8ef8b/BOTH39438989932 proves the earlier 23 boundary cases. That evidence
is not transferred to these new limits/metrics/31-case sources. #55 remains the
sole GPT implementation PR, frozen ea56f3319d0e896d54703d9f4d857252906ed2a4 with exact
Claude review absent. No new migration, dependency version, CDI/Flow/adapter or
central-consumer activation. Read proposed ADR0032 and prior boundary handoff.

## Exact change and adoption contract

The unmerged explicit constructor now REQUIRES
AuditedEffects(dataSource, transactionManager, idempotentAuditSink, telemetry,
meterRegistry). No silent four-argument fallback. The matching internal core
constructor gets the actual registry rather than expecting field injection to
happen on a new object. Real outcome observations use the existing metric
agentic.effects.outcomes with ONLY fixed outcome labels. Cached confirmation
readback does not count another transition; retained confirmation counts even
if later audit/output fails. This is local observability, not global exactly-once
business counts, measured model cost or dispatch permission.

Package-private EffectStoreTransactions applies actual PostgreSQL SET LOCAL
lock_timeout 2s / statement_timeout 5s to EVERY owned short transaction of the
explicit producer, delivery readiness/source reads/ACK, complete history and
current/compensation reads. Ordinary work uses READ_COMMITTED; coherent snapshots
use REPEATABLE_READ. Commit/rollback restores local settings; do not change the
server, caller's connection/transaction or shared DataSource. Session advisory
claims still require the documented headroom/session-compatible pooling. The
legacy raw/CDI ledger remains internal and unchanged; real adoption/non-bypass
must cover every actual governed caller before activation.

The application explicitly configures pooled connection acquisition through
DB_ACQUISITION_TIMEOUT (default 5s). A boot-config check reads the actual Agroal
pool. A separate OWNED real Agroal pool is exhausted to verify a finite failure,
zero target callbacks/no intent and continued caller/pool usability; it is not a
semaphore fake or modification/closure of the application's shared pool.
Custom DataSources and deployment overrides need their own finite approved
acquisition/connection/network contracts. This does NOT bound an entire operation,
all network/driver login steps or an arbitrary central/provider callback.

## Failure and recovery verification

Eight added actual SQL/pool/metrics cases extend boundary 23 to 31:

- The private PREPARE protocol fence acquires a real caller-owned registry row
  lock AFTER the first current check. The following intent transaction must fail
  SQLSTATE 55P03 with zero target calls/facts and the caller transaction still usable.
- A target protocol spy holds the actual ledger row before returning CONFIRMED.
  Outcome persistence times out55P03, leaves INTENDED uncertainty, then the SAME
  key recovers through lookup without another send.
- A scoped owned source trigger sleeps 6s; actual statement_timeout 5s must produce
  57014 and roll back ALL five intent/journal/scope/source/queue facts, zero target
  calls. Remove ONLY that owned fault trigger/function, then retry succeeds.
- A central DATA fixture commits before holding real local ACK rows. ACK55P03
  leaves both pending; reopen reads the SAME receipt and never blindly sends.
- ACCESS EXCLUSIVE locks on OWNED source/queue tables stop direct delivery/ready
  metadata reads with 55P03 before any central consumer call; release and readback work.
- Actual Agroal pool admission 2s under exhaustion refuses before target/intent,
  preserves the held connection and permits borrowing after release; application
  pool default is read as 5s. No global/main pool is exhausted or reconfigured.
- A single-connection owned pool reuses the SAME backend and original lock/statement
  settings after a scoped transaction, proving SET LOCAL did not leak.
- Explicit metrics count confirmation/compensation transitions with only fixed
  labels, suppress cached duplicate transitions, and retain observations on an
  audit-failed private result. No fake measured costs or sensitive labels.

Author verification=GPT (self-check; Claude peer approval remains required).
Exact tested product SHA=5833b42c387fa0ae23d119f106179b1553846a33. Local selected 17
(cancellation/replay 7, producer 6, scope 4) pass, zero skips; all 87 application test
sources compile including 31 real SQL/protocol cases. Compilation is not actual
SQL/pool/timeout execution. [BOTH39444287751](https://github.com/bassemZohdy/open-workflow-agentic/actions/runs/39444287751)
is SUCCESS at EXACT 5833b42. Standard executes in attempt 2 job 118706058406;
delegation executes in attempt 1 job 118701975288 (carried-success 118706060671 in
attempt 2). Both completed full logs inspected. Each runs contracts 212 / runtime 351 /
testkit 196 / app unit 391; boundary 31 / source-delivery 17 / model 24 / allocation 15,
producer 6 / cancellation-replay 7 / conformance 7 / binding 17 / proposed execution 6 / source 6
pass, zero failures/errors/skips. Exact current source method sets match ALL 31 /
24 / 15 cases. Actual standard 3 / delegation 8 / core process 1 / audit-process 4 EACH and
all three strict checker markers pass; failsafe totals standard 8 / delegation 13.

All EIGHT added cases execute actual PostgreSQL/Agroal/metrics assertions, including
55P03 registry/outcome/ACK/metadata lock refusal, 57014 source statement cancellation
and all-five-table rollback, retained SAME-key reconciliation and caller-connection
usability, same-backend local-setting reset, real acquisition failure and fixed-label
transition counters. Delegation case durations: registry2.309s/outcome2.413s/source
statement5.488s/ACK2.345s/two metadata waits4.144s/Agroal2.029s/settings0.030s/
metrics1.047s. Those are observed case durations, NOT a whole-call SLA or network
budget. Both profiles independently pass all exact cases; targets/fences remain spies.

Downloaded current artifacts by EXACT id, not ambiguous duplicate profile names:
standard 12275807406 SHA256 7703f20acc6739aea89400223d5643cc4abb45a39d5be508fb53686ab4580932;
delegation 12275731151 SHA256 46bcadb06ec2a3a839c2b1a3476d95c85374813028a8f27ed12f568b8d645b16.
Actual downloaded ZIP bytes match each GitHub-recorded digest, CRCs pass and
archive paths stay within owned target directories. ALL 158 actual XML suites per
lane parsed with consistent counters/unique identities and zero failures/errors/
skips; strict checker independently rerun with matching --profile plus
--effect-recovery --effect-audit-recovery. All 14 owned worker logs per lane checked:
EACH source/ACK before/after commit halt once, six intent reopen/create workers and
four ACK readbacks. These actual process regressions remain required and passing.

Actual conformance reports/48 outputs/74 exports/two writer diagnostics checked per
lane, with ONLY the two bounded known wrong fork-order fingerprints admitted;
42 sources/76 attempts/30 MATCH/18 DIVERGED remains NOT full semantic conformance.

Current local 212 contracts and selected 17 pass; all 87 app test sources compile.
All-eight-module mvn -B verify -DskipTests compiles/packages and actual dependency
policy holds from fresh lists. That skipped command is NOT a full local suite.
Tools 82/generated and authored increment git diff --check from 3dd2e44 pass. No
new dependency/version, applied migration rewrite or acceptance annotation.

Failure retained: standard attempt 1 job 118701974861 failed before app compilation/
tests resolving unchanged pinned Failsafe3.5.3 POM from Central: Connection reset.
Its artifact 12273594837 contains 98 passing module suites and NO app/IT evidence;
full failed log and actual 98 XML inspected. Primary POM HEAD returned 200 from this
host at 15:06 UTC, not a statement about runner connectivity. After the whole attempt
was terminal, reran ONLY that failed standard job ONCE, with unchanged pins/source/
selectors/assertions. Native attempt 1 success is carried, not claimed rerun.
Standard attempt 2 above executes all required cases. No assertion failure was
retried unchanged and no absent/skipped provider evidence was counted as success.

Verdict: preparation verified; integration HOLD for exact Claude/final PR/actual
consumer and production adoption. SQL limits, default pool admission and explicit
metric observations are proved for this component. Whole-call/network/driver and
custom-source/config override budgets, real runtime factory/consumer/composite,
complete Flow/manual/partition/restore/observability and package closure remain
open. #55 is still frozen ea56f33 without Claude review; no merge/activation or
package/acceptance promotion. Tracking 11 completed / 13 partial / 29 not started,
acceptance 3/39 unchanged.

The current-product checks above supersede SQL/pool/timeout execution pending
for this EXACT product; earlier proof is not transferred. Full logs/ALL actual XML,
exact31+24+15 method sets,14 worker logs, conformance and required provider/core/
audit-process checks are inspected. Source/delivery17,
model24/allocation15 and earlier process evidence are not substituted for fresh
execution. Both original source/ACK process modes and all required provider gates
remain enabled. No new acceptance annotation or work-package promotion.

Central receipts remain TWO-COMMIT DATA fixtures (existing append, then binding),
with protocol-spy targets/private fences. They do not prove atomic central
appendOnce/chain authenticity, actual signed identity/PDP/registered target, physical
business/provider calls, complete occurrence/task/lease/Flow or backup/partition.
Claude: review the REQUIRED constructor argument, retain real current authority/
mandatory decision audit/central consumer requirements and provide their owned
actual implementation/tests. GPT still owns whole-call/pool/transport integration,
complete composite and rollout; these per-statement bounds do not close WP-110 or
WP-112. #53 d38776c/#56 26def97 findings/consolidation and exact frozen #55 review
remain. No whole-WP-102 start dependency or second GPT implementation PR.
