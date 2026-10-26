# Task allocation migration plan — proposed, 2026-10-27

## Current state

Baseline: main `2284d106db01548c2b620c5ad8744aa25fe692f1`, migrations through V77.
Execution facts may legitimately have no task allocation. V76 stores historical data,
not permission. The preparation branch `gpt/WP-103-execution-contract` proposes V78
allocation and V79 model reservations, but neither migration is activated.
Its validated foreign key from every resource execution to an allocation would
reject existing unallocated records. Legacy task threads also differ from canonical
resource-call threads; neither identity can be inferred from the other.

## Target state and strategy

Use expand/contract: introduce explicit immutable allocation data, then reviewed
writers/readers, then enforce new execution admission at the production root.
GPT owns contracts, schema and lifecycle; Claude owns approved agent/model sources
and policy consumers. Constructor-valid profiles remain data until independently
approved. No fabricated historical task, grant, profile or reservation is permitted.

### Phase 1: compatible expansion

Selected expansion: add an allocation foreign key on execution with `NOT VALID`.
PostgreSQL enforces it for new referencing writes while retaining pre-existing
unallocated facts. During migration, block concurrent execution inserts while taking
an immutable inventory of the exact exempt historical call keys and adding the
constraint in the same transaction. Inventory rows are historical classification,
never permission; guard their update/delete/truncate. Do not later validate the FK
while exempt rows remain. Dropping the FK or reopening unallocated writes is a stop
condition, not routine rollback. Measure the lock duration in rehearsal; deployment
must fail rather than take an unbounded lock.

The preparation is stale and must not be merged wholesale. Extract/rework only the
required files onto current main, updating V77 assumptions and exercising actual
V76/V77 plus migration A1–A7. V78, V79 and V80 remain reserved, with V79 explicitly
dependent on the revised V78 allocation shape. `db/proposed/**` must stay outside
Flyway locations until the reviewed activation. Pin new function search paths and
resolve catalog types/builtins consistently with V76/V77; test hostile search paths.
Applied V76/V77 checksum equality is an activation requirement, not just rollback
rehearsal evidence.

### Phase 2: producer and consumer adoption

Implement the canonical allocation writer with full scope, contract, approved
profile and grant ancestry checks. Require stable idempotency and conflicting retry
refusal. Coordinate Claude fixtures and consumers before making allocation mandatory
for newly admitted executions. A task allocation is insufficient without fresh
identity, registry, workload, lease, policy and audit checks. Model reservations are
a separate V79 change; a positive process counter cannot substitute for a durable hold.

Use separate restricted allocation/reservation writer roles. Executor credentials
must have no INSERT/UPDATE/DELETE/TRUNCATE rights on allocation, budget or reservation
facts and cannot assume those roles; PUBLIC receives no mutation grant. A trusted
producer must resolve the exact retained profile configuration from an independently
approved registry source before using the writer. That approved-source interface
and deployable role/grant procedure need an owned handoff and tests before activation;
a matching JSON hash or writer role alone proves neither approval nor live authority.

The FK supplies existence only. Before execution admission, require an unexpired
allocation and current grant after blocking work, and prove expired allocations
with valid FKs cannot call a target/model. Historical exempt calls cannot receive
new allocations under the proposed insert guard and cannot acquire V79 reservations.
No attempt to retrofit such rows is planned.

A supplied parent must retain its actual stored legacy task thread and full owner;
a parent whose thread differs from its own canonical stored-task derivation is
refused. The legacy thread omits owner kind, so every association must also compare
full owner kind/id. Do not reinterpret that parent thread as a canonical resource-call
checkpoint or effect thread. Production adoption needs an explicit retained mapping
and negative mismatch tests; no remapping or version change is implied here.

### Phase 3: reconciliation and cutover

Inventory historical unallocated entries by exact key and preserve their original
values. No backfill is planned without independently evidenced provenance. Historical rows stay exempt and read-only under this plan; any different disposition requires a separate reviewed migration. Do not validate the global foreign key while exemptions remain.
Production cutover requires zero newly admitted unallocated executions, zero
conflicting mappings, and all negative admission cases passing. No production
cutover or irreversible reconciliation has been approved or executed by this plan.

## Rollback and stop conditions

Stop on any dropped/modified historical fact, invented authority, conflicting retry,
or newly admitted unallocated execution. Disable the new producer/consumer path and
retain additive facts for reconciliation. Preserve writes made after cutover; an
old database backup alone is not a rollback strategy. Prefer forward repair after
committed allocations/reservations. Destructive cleanup or constraint contraction
requires a separate concrete review and recorded disposition.

## Verification plan

All rows below are planned, not executed evidence. Use private PostgreSQL 16 databases
and Java 21, then exact peer review and required Linux CI before merging activation.

| ID | Verification | Acceptance threshold | Owner |
|---|---|---|---|
| A1 | Upgrade V77 containing unallocated execution history | 100% original rows/values retained; no fabricated allocations | GPT |
| A2 | Empty database migration and partial/conflicting schema | Supported shape succeeds; conflicting shape rolls back without partial migration | GPT |
| A3 | Concurrent exact/conflicting allocation retries | One exact immutable allocation; every conflicting retry refused | GPT |
| A4 | Missing/expired/revoked grant and wrong full owner/profile | Zero admitted writes/calls | GPT + Claude |
| A5 | Old historical row presented as execution permission | Zero target/model calls | GPT + Claude |
| A6 | Failure after allocation/reservation commit with lost reply | Exact retry readback, no duplicate allocation/hold; post-cutover writes retained | GPT |
| A7 | Activation and failure/rollback rehearsal | V76/V77 checksums and historical row identities unchanged | GPT |
| A8 | Thread/parent/full-owner mismatch at production association | Zero target/model calls; no inferred remapping | GPT + Claude |
| A9 | New execution missing allocation; old exempt row | New insert rejected; exact old inventory/readback unchanged | GPT |
| A10 | Expired allocation with valid FK, revoked grant, historical V79 reservation | Every execution/reservation refused | GPT + Claude |
| A11 | Executor and PUBLIC mutation/role escalation | Every allocation/budget/reservation mutation and writer-role assumption refused | GPT |
| A12 | Proposed migrations and hostile function search paths | V78/V79 absent from Flyway history before activation; pinned functions cannot resolve attacker objects | GPT |
| A13 | Migration racing an execution writer | Exact exemption inventory; zero post-boundary unallocated inserts; bounded lock wait | GPT |

This proposal closes no work package or acceptance test. Issues #75 (recovery) and
#76 (production identity profile) remain separate open decisions/evidence gaps.
