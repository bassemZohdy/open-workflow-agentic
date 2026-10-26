# WP-103 allocation expansion rehearsal — V78 remains proposed

Writer GPT; reviewer Claude. Baseline main9c61d219b8b2916bf2bcc60465815415e86ffa6c
(implementation5f5a229). This implements a bounded, private-database rehearsal of Phase1
in [the reviewed migration plan](WP-103-allocation-migration-plan.md). It does not
activate V78, install a store/approved source, deploy roles/credentials or grant execution.

## Old/new baseline and selected change

Actual applied baseline is V77. The stale gpt/WP-103-execution-contract V78 is used
only as selected SQL provenance; its redirect-schema tests/store/other stack are not
merged. The SQL is reworked in db/proposed/V78__resource_task_allocation.sql, outside
Flyway's db/migration location. All applied V76/V77 bytes/checksums remain unchanged.
V79/V80 stay reserved; V79 depends on this allocation shape and is not carried here.

The previous validated FK would reject existing unallocated execution facts. The
proposal now locks task.resource_execution against writers, records the exact historical
call set and original entry/contract/epoch/executor/time in an immutable inventory,
and installs the allocation FK NOT VALID in the SAME owned transaction. New references
require allocation; historical facts remain readable and unchanged. Inventory is DATA,
not an exemption from runtime authority. No old allocation/task/profile/grant is invented.
The existing allocation insert guard still refuses retrofit after an execution exists.

A row-locking writer that commits during the migration wait must be included. The
rehearsal therefore requires an owned READ COMMITTED transaction and refuses another
isolation level before DDL, rather than silently weakening it. Caller-local search path
is pinned for DDL/type lookup; every new function also pins runtime catalog lookup.
Lock wait is capped at2s and each statement at5s, retaining any stricter nonzero limit.
Local settings reset at commit/rollback. These are statement/lock bounds, not a whole
migration/production I/O deadline. A bounded wait failure rolls back the whole proposal.

## Verification plans/results for this scope

Actual Java21 / PostgreSQL16.15. Every schema case creates a UUID database, checks
current_database before migration and drops only that owned database. The Maven run
also uses a fresh verified UUID common database, dropped without FORCE after exit.

| Plan IDs | Executed checks | Threshold/result |
|---|---|---|
| A1/A7 | Upgrade actual V77 containing execution history; no fabricated allocation; original full rows/checksums preserved | exact equality; zero fake allocations |
| A9 | New unallocated entry versus exact explicit metadata allocation | missing FK23503; matching metadata permits DATA only |
| A1/A9 | Immutable inventory INSERT/UPDATE/DELETE/TRUNCATE and unchanged parent UPDATE | actual intended table42501, original history unchanged |
| A2/A7 | Conflicting existing allocation table | original marker retained; no partial functions/inventory |
| A7/A12 | Flyway exclusion and checksum/history validation | no pending/applicable V78/V79; old76/77 checksums equal |
| A12 | Hostile public sha256 and temporary text type; remove runtime pin in a rollback-only control | valid pinned digest equals shared JDK; missing pin refuses or changes digest; restored |
| A13 | Actual writer holds an uncommitted execution while migration waits on table lock, then commits | exact writer key included; no invented row |
| A13 | Actual table lock held beyond inherited100ms limit | SQL55P03 under3s; old history and no partial schema |
| A13 | Existing100ms/500ms limits and caller namespace | never widened; restored after commit |
| A13 | SERIALIZABLE migration input | SQL25001 before DDL; caller must select a reviewed migration profile |

Initial family39 failed one assertion identifying the protected table: reuse of the old
resource-call immutable function refused writes with42501 but did not name the new
inventory. A dedicated catalog-pinned immutable guard now identifies that exact table;
the corrected family passes39 (contracts9 + app30: proposal9 / V76 facts20 / default1),
zero failures/errors/skips. The validated-FK control (remove ONLY NOT VALID) produces the intended actual FK error
on the preserved historical row. Restoring the original SQL bytes then passes the
complete39-case family again, including the unchanged parent UPDATE guard. The
runtime function-pin control also executes inside the successful9-case suite and is
restored by rollback. Local logs are
 target/allocation-upgrade-{author,author-final,validated-fk-negative,restored-final}.log.

## Remaining activation/cutover/rollback gates

This is partial verification, not all A1–A13 or complete V78 approval. Still required:
full parent/narrowing/expired/revoked/cyclic/mismatched grant and profile negative cases,
all actual caller fixtures/consumers, a durable allocation writer with exact retry and
lost-reply/JTA semantics, explicit canonical/legacy/effect thread associations, separately
approved model configuration/source, restricted allocation/reservation writer roles and
executor denial/role-assumption proof (A11), V79 reservation/budget/lease checks and
actual runtime freshness after blocking work. The FK supplies existence only; an
expired allocation with a valid FK is not live permission.

PUBLIC mutation grants are revoked on the proposed tables, but this is not a full
writer-role/default-privilege/deployment proof. No credentials are installed here.
No automatic validation while historical exemptions remain, no DROP to reopen new
unallocated writes, no destructive cleanup or historical backfill is authorized by
this rehearsal. Production cutover still requires the original plan's quantitative
new-write/reconciliation gates and recorded review. Preserve post-cutover committed
writes and prefer forward repair; restoring an old backup alone is not reconciliation.

Required exact peer/current Linux CI/archive inspection remain pending. No work-package,
acceptance, actual-root/async recovery or production profile decision is closed.
