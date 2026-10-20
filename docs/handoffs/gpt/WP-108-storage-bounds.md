# WP-108 storage bounds and explicit metrics (GPT -> Claude)

Follow-up on existing gpt/WP-108-audit-producer from pushed3dd2e44, whose tested
productcd8ef8b/BOTH39438989932 proves the earlier23 boundary cases. That evidence
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
lock_timeout2s/statement_timeout5s to EVERY owned short transaction of the
explicit producer, delivery readiness/source reads/ACK, complete history and
current/compensation reads. Ordinary work uses READ_COMMITTED; coherent snapshots
use REPEATABLE_READ. Commit/rollback restores local settings; do not change the
server, caller's connection/transaction or shared DataSource. Session advisory
claims still require the documented headroom/session-compatible pooling. The
legacy raw/CDI ledger remains internal and unchanged; real adoption/non-bypass
must cover every actual governed caller before activation.

The application explicitly configures pooled connection acquisition through
DB_ACQUISITION_TIMEOUT (default5s). A boot-config check reads the actual Agroal
pool. A separate OWNED real Agroal pool is exhausted to verify a finite failure,
zero target callbacks/no intent and continued caller/pool usability; it is not a
semaphore fake or modification/closure of the application's shared pool.
Custom DataSources and deployment overrides need their own finite approved
acquisition/connection/network contracts. This does NOT bound an entire operation,
all network/driver login steps or an arbitrary central/provider callback.

## Failure and recovery verification

Eight added actual SQL/pool/metrics cases extend boundary23 to31:

- The private PREPARE protocol fence acquires a real caller-owned registry row
  lock AFTER the first current check. The following intent transaction must fail
  SQLSTATE55P03 with zero target calls/facts and the caller transaction still usable.
- A target protocol spy holds the actual ledger row before returning CONFIRMED.
  Outcome persistence times out55P03, leaves INTENDED uncertainty, then the SAME
  key recovers through lookup without another send.
- A scoped owned source trigger sleeps6s; actual statement_timeout5s must produce
  57014 and roll back ALL five intent/journal/scope/source/queue facts, zero target
  calls. Remove ONLY that owned fault trigger/function, then retry succeeds.
- A central DATA fixture commits before holding real local ACK rows. ACK55P03
  leaves both pending; reopen reads the SAME receipt and never blindly sends.
- ACCESS EXCLUSIVE locks on OWNED source/queue tables stop direct delivery/ready
  metadata reads with55P03 before any central consumer call; release and readback work.
- Actual Agroal pool admission2s under exhaustion refuses before target/intent,
  preserves the held connection and permits borrowing after release; application
  pool default is read as5s. No global/main pool is exhausted or reconfigured.
- A single-connection owned pool reuses the SAME backend and original lock/statement
  settings after a scoped transaction, proving SET LOCAL did not leak.
- Explicit metrics count confirmation/compensation transitions with only fixed
  labels, suppress cached duplicate transitions, and retain observations on an
  audit-failed private result. No fake measured costs or sensitive labels.

Author verification=GPT, not Claude peer approval. Local selected17
(cancellation/replay7, producer6, scope4) pass, zero skips; all87 application test
sources compile including31 real SQL/protocol cases. Compilation is not actual
SQL/pool/timeout execution. CURRENT_STORAGE_BOUNDS_CI_PENDING

Fresh current-product full logs/ALL actual XML, exact31+24+15 method sets, all14
owned process-worker logs per lane, actual conformance artifacts and real standard3 /
delegation8/core1/audit-process4/strict checks must be inspected. Source/delivery17,
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
