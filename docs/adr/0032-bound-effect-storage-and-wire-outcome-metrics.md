# ADR-0032: Bound short effect transactions and require outcome metrics

Status: Proposed preparation; no governed adapter or consumer activation

Date: 2026-10-20

## Decision

Use one package-private EffectStoreTransactions helper for the explicit effect
producer, delivery, ACK, complete-history and current/compensation checks. Set
READ_COMMITTED for ordinary short work and REPEATABLE_READ for coherent snapshots,
with transaction-local lock_timeout2s and statement_timeout5s. Apply the limits to
the previously unbounded preparation/outcome/queue/ACK paths too. Never alter the
server, caller's transaction, shared DataSource or another session. Legacy raw CDI
ledger construction keeps its existing behavior; coordinated governed adoption and
non-bypass evidence remain gates.

[PostgreSQL16 documents statement and lock limits separately](https://www.postgresql.org/docs/16/runtime-config-client.html):
these are per-statement/per-lock controls, not a whole-operation, connection or
network deadline. Failed writes roll back owned transactions; a timeout after an
external send is still uncertain and reconciles the SAME original effect key.
A local ACK timeout after central commitment leaves pending source work and must
read back the same receipt, never mint another event or dispatch permission.

Require MeterRegistry in the explicit AuditedEffects constructor and pass it to
the internal core constructor. Do not rely on CDI field injection for a manually
constructed object. Preserve the existing fixed outcome labels; counters observe
local transitions/unknown observations, not exactly-once global business totals,
actual model usage or permission to release private output. Cached confirmed
readback does not increment another confirmed transition. Observed outcomes still
count when mandatory audit/private return later refuses.

Make the application pool's five-second acquisition default explicit through
DB_ACQUISITION_TIMEOUT. [Quarkus documents this pool setting](https://quarkus.io/guides/datasource/#jdbc-configuration)
and distinguishes it from driver login behavior. It does not bound all connection
establishment/network steps. [Agroal's acquisition contract](https://agroal.github.io/docs.html)
provides a finite wait when configured; bare pool defaults/custom DataSources
must not be assumed bounded. Deployment overrides require their own validated,
approved finite values. Do not mutate application pool configuration at invocation.

## Consequences and verification

No dependency version, migration, audit identity, effect key, refund or whole
operation budget is changed. The new required constructor argument supersedes the
unmerged four-argument preparation; consumers need this specific interface review.
Claude owns actual signed identity/PDP/central appendOnce/chain producers; GPT owns
this storage/configuration/metrics wiring and coordinated rollout. #55 remains the
sole GPT implementation PR, frozen pending Claude's exact review.

Actual owned-database cases cover post-fence registry locking, outcome locking,
slow source-trigger cancellation, local ACK locking after central DATA fixture
commit, locked metadata reads, actual Agroal pool exhaustion, local-setting reset
on the same physical pooled connection and fixed-label outcome metrics. The
existing whole boundary/source/model/allocation/process/real-provider regressions
must run freshly at the current product. Compilation/pool configuration inspection
alone is not timeout enforcement proof. Central DATA-fixture/target-spy limits
remain; the handoff records exact results and pending gates.

Connection establishment/network/central/physical adapter budgets, whole-call
cancellation, complete lease/current-authority/Flow/manual/partition/restore and
full WP-108/WP-110/WP-112 acceptance remain open. No production activation or
package/acceptance promotion follows from these bounded short transactions.
