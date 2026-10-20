# WP-103 irreversible entry and outcome SQL: executable proposal

GPT centrally reserves V76 after V75; do not allocate/renumber it independently.
Actual proposal: modules/oa-runtime/src/main/resources/db/proposed/V76__resource_execution_facts.sql.
It is OUTSIDE Flyway, not applied. V71/V72 (#55), V73/V74 binding foundations,
V75 source binding and exact interface/consumer reviews integrate in order.
No applied migration, Claude-owned path, second implementation PR or Java
consumer of the unmerged execution contract is added here.

The proposal recomputes rc1 and the complete call contract from retained full
Unicode scope fields with agreed canonical UTF-8/int8 bytes. A forged-looking
hash is not trusted. One immutable resource_execution row per CALL key retains
re1 entry identity, complete contract fingerprint, positive execution epoch,
first credential-binding reference, entering executor UUID and database entry
time. A new credential, executor or epoch cannot create another row for that
call. UPDATE/DELETE/TRUNCATE cannot erase entry uncertainty. No outcome means
possibly in progress/unresolved, NEVER proven nonapplication or a fresh start.

The new-entry guard locks and compares current registry tenant/FULL owner kind
and id, definition/version/engine, active status and unexpired wait deadline,
recomputes full source data contract, compares the exact binding's call and
signed-time metadata, and uses database time after row-lock waits. This is
structural entry data, NOT verified JWT/grant-chain/PDP/actual SDK occurrence/
registered-target authority, live lease ownership or the final dispatch fence.

Immutable resource_execution_outcome retains exact entry/profile/approved-target
fingerprint/physical run UUID/checkpoint UUID/media/content fingerprint and DB
observation time. It has one outcome per entry and unique physical references
within the same approved target/backend profile. Independent copied stores may
contain identical UUIDs, so uniqueness is not assumed global across targets.
The duplicated target field is checked against the retained call. Parent FKs
retain call/binding/entry facts. Agent checkpoint stores may be remote/mutable:
these UUIDs are data references, not an SQL FK or proof of a terminal checkpoint.

Historical outcomes may be recorded after registry termination/deadline/credential
expiry: retaining an observed fact is not releasing private output or advancing
Flow. The trusted inspector, exact representation helper and current composite
identity/registry/target/entry fence remain mandatory. No raw state/result/body,
credential, bearer or private authority handle is stored here.

ResourceExecutionSchemaTest executes the ACTUAL proposed DDL in UUID-named
schemas. It copies actual table column types and explicit trusted test fixture
registry/call/binding rows; it does not copy parent triggers/FKs, and no shared
parent is disabled, altered or cleared. ALL cloned DDL/DML is rolled back. Fake
registry fixtures finish normally afterward. Six cases cover full Unicode
canonical/entry/outcome roundtrip and immutability; another metadata credential/
epoch/forged entry refusal; corrupt full source keys/owner/coordinates/target;
current registry/deadline/binding-time mismatches; historical observation and
scoped one-result association; missing/changed result metadata and parent FK
retention. Physical run/checkpoint UUIDs are explicit DATA fixtures, not graph
runs. No concurrent JDBC fresh-callback, actual committed reply-loss, process
restart, private output, authority or whole-workflow success is inferred.

Local application reactor test -DskipTests BUILD SUCCESS: all application test
sources compile. New six SQL cases require fresh Linux execution, recorded at
its exact head. Existing V75 proposal4a45515/BOTH39399264703 proves cloned source
schema6/binding17/group11/process/providers only, not this V76 addition.

Next runtime gate must own one fresh durable NO_ENTRY->ENTERED callback after
known commit acknowledgment, mandatory idempotent audit and current composite
fence AFTER blocking work. Replay/unknown commit may only inspect/read retained
facts, never invoke another graph. ResourceExecutionEntry/Fence/Outcome/helper
are separate preparationb349cc5, not merged. Entry storage alone does not make
them capabilities. Audit source/outbox sequencing for these entry facts still
needs its own explicit source journal; never guess an effect.event sequence.
Current approved target/actual SDK complete business occurrence, stable governed
UNKNOWN decisions, exact graph inspection/output, concurrent credentials,
commit-loss/process/partition/old-owner/cancellation/restore and full workflow401
and package/acceptance gates remain partial. #55 remains the sole frozen GPT PR.

## Actual proposed-schema execution

reviewer=GPT; verified SHA=d489000706ae904527bc74aa043cab8d8524e234.
Verdict: PASS six proposed V76 schema cases; runtime/activation/package gates
remain OPEN. BOTH39404240486 SUCCESS. GPT downloaded and inspected completed
standard118566165129/native118566165396 logs: ResourceExecutionSchemaTest6,
AuditSourceBindingSchemaTest6, binding17/group11/core process recovery1 in each
lane, standard3/native8 and strict checker output pass, zero failures/errors/
skips. Reactor totals193 contracts/333 runtime/196 testkit/304 application cases.
This supersedes new-SQL-pending statements for that EXACT proposal product. It
is still outside Flyway and not a callback, graph, private-output or commit-loss
proof. A data duplicate INSERT failure is not proof of one actual graph entry.

The next WP-108 producer investigation confirms JdbcEffectLedger.journal writes
inside the fact transaction but discards the generated effect.event id/time.
The atomic producer must retain stable source/draft id/time/content in THAT
transaction and bind newly trusted full-owner scope; it must not guess legacy
ws1 provenance or manufacture another draft after an uncertain commit. Required
interfaces AuditSource/AuditReceipt/canonical helper are already merged in #54;
no whole WP-102 completion gate is introduced. Consumer appendOnce/mandatory
audit acknowledgment and current authority remain separate adoption gates.
