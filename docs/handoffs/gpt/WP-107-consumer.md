# WP-107 actual SDK consumer successor

Owner: GPT. Reviewer: Claude. Date: 2026-10-20.

Preparation branch `gpt/WP-107-sdk-consumer` in the exclusive sibling
gpt-wp107-consumer follows #46 exact89b0f0d. #46 remains the single active GPT
implementation PR; no duplicate PR is opened for this preparation. The frozen
#40 integration and #46 implementation heads are unchanged. Consumer activation
requires reviewed/merged owned storage and the full gates below.

## Complete stored CloudEvent decoding

StoredCloudEventDecoder reconstructs the WHOLE envelope persisted by authorized
ingress in InboxMessage.dataJson. It uses the bounded strict StructuredEvent
parser, verifies source/id/type against the durable receipt, and refuses legacy
payload-only or malformed/ambiguous/deep JSON. No synthesized envelope wraps the
already complete envelope as data. Subject/time/schema/content type/extensions,
JSON/null/absent/text/binary data survive. Decode independently for each callback;
stored receipt/fingerprint stay unchanged and no callback data object is shared.

Tenant, target and correlation continue to come from the durable receipt and
registered occurrence. Event extensions named tenant/instanceid/correlation are
untrusted data, preserved for predicates but never routing/authority inputs.
Decoding grants neither reservation nor callback/continuation authority.

The first local regression exposed JsonFormat5.0.0's default JSON mapper rounding
1.000000000000000000001 to1.0. The parser now uses its existing bounded, precise
JSON tree for JSON data after the SDK validates the envelope. Binary data_base64
still uses the SDK decoder. Durable canonical envelope bytes and fingerprint
semantics are unchanged; the new returned CloudEvent retains the actual number.
This is a tested data conversion correction, not a provider/runtime delivery pass.

Validation: local mvn -B -pl modules/oa-runtime -am test
-Dtest=StructuredEventTest,StoredCloudEventDecoderTest
-Dsurefire.failIfNoSpecifiedTests=false -> BUILD SUCCESS, decoder6/parser2,
zero failures/errors/skips. Original failure: decoder numeric assertion only;
all assertions retained after correction. These are plain unit tests with the
actual pinned CloudEvents library, not PostgreSQL/process/SDK persistence proof.

## End-state contract still required

Use the pinned SDK EventConsumer registration SPI and capture full ListenTask
membership before releasing any callback. Persist stable task/group identities,
duplicate-filter ordinals, original deadlines and durable all/any selection.
Every registration of one occurrence shares its task owner/epoch. A new epoch
reconstructs the WHOLE uncheckpointed task rather than adding replay to the old
partial all array. Serialize callbacks per task because the pinned all-listen
output array is mutable. Any winner must make its losers inert durably.

SDK predicates evaluate bounded, route-approved candidates outside SQL; only
the exact approved candidate can be matched/reserved. Current deployment producer
bindings and pinned definition/version must authorize consumed routes. Bindings,
raw workflow/user event metadata and in-memory object identity cannot substitute
for runtime tenant/registry/task authority. Event starts have their own guarded
contract and must not fall back to an unbound listenToAll path.

Bound coordinator/executor/renewal capacity and use only short owned-storage
transactions. No idle partial listen or callback/audit keeps a connection. Renew
one lease per complete task, fence lost epochs, preserve accepted-reservation
deadline semantics and do not re-arm original deadlines on restart. Unknown
commit/audit outcomes must not return/repeat permission in the same epoch.

Integrate nonblocking proof ACK BEFORE allowing actual checkpoint advancement;
repair committed receipts before restore can advance. A checkpoint fence alone
cannot undo an earlier external side effect. Governed foreach/effect boundaries
and distributed continuation/cleanup authority are mandatory. Prove actual
process/partition windows, owner loss AFTER checkpoint, whole all/any vectors,
loop/parallel restore including the pinned reader's position-only map, manual
OPERATION policy, starts, retention/tombstones and terminal audit/metrics/tracing.
The committed-cancel-request/before-engine-call crash window remains separate
from local mixed-order cancellation. Full T13 includes failed compensation.

No production consumer/coordinator is wired by this decoder. WP-107 remains
partial, ADR0025/0026 Proposed; no acceptance marker/count/status is promoted.

## Pinned registration behavior and group-storage boundary

Two additional plain unit cases execute the actual pinned EventRegistrationInfo,
TypeEventRegistrationBuilder and InMemoryEvents SPI (literal predicates need no
workflow/correlation context). A single event completes BOTH identical all
registrations, invoking each callback. For any, the first future unregisters all
registrations, yet the captured CopyOnWrite broadcast snapshot still invokes
the second callback; a later publication invokes neither. Unregistration alone
cannot make an already captured loser inert. Both tests pass, along with
decoder6/parser2:10/10 non-skipped. They characterize registration delivery, not
full workflow completion, persistence or T13. An initial generic unregister
method-reference compile mismatch was corrected to the actual Type registration
cast; no behavioral assertion was weakened.

V64 intentionally gives inbox.signal a global unique receipt binding. Applying
that per-registration representation unchanged to overlapping all filters would
not reproduce the observed fan-out. Design an additive durable group/membership
representation and prove conformance before activation; do not reinterpret a
receipt ID as filter ordinal, require two distinct receipts merely to sidestep
overlap, or equate unregistration with a durable any-winner fence. Historical
single-wait signal/proof rows remain retained, with their evidenced scope.

The [OWS1.0.3 Listen reference](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#listen)
requires ordered consumed output and serial FIFO foreach processing. Group
reconstruction must retain arrival/consumption order rather than silently
reordering outputs by filter ordinal. The specification does not itself settle
every overlap/delivery detail above; the two SPI tests are the concrete pinned
observations used for this integration design. Foreach, until and parallel/loop
restore still need their full actual-engine failure/ordering cases.

## Asynchronous ACK before checkpoint advancement

CheckpointFinalizer snapshots immutable work BEFORE the mutable SDK context is
submitted to the actual JPA writer. Only actual writer success starts the
prepared asynchronous stage; the existing watchdog covers both. Success
observers and the protected SDK future wait for finalization. Snapshot/write/ACK
failure, rejection or timeout cannot release progress, including late success.
Trusted cancellation still holds obsolete work without poisoning independent
ABORTED cleanup. The original reader/writer strategy and exclusions remain.

The production handlers compose InboxCheckpointFinalizer: unmarked operations
complete immediately; trusted marked TASK_COMPLETED operations use two workers
and at most16 queued jobs. No caller/event-loop thread blocks on ACK. Saturation
fails closed through the same readiness gate. Each worker performs the actual
proof/audit/ACK on short transactions, then a fresh registry/task-owner check
AFTER that blocking work. No connection spans independent audit or idle waiting.
The worker needs one connection at a time; reserve headroom for two active
finalizations plus lifecycle/audit and other components. The watchdog may hold
a queued/late finalization even if its later historical ACK succeeds.

The three-argument writer constructor retains compatibility for isolated callers;
the actual CDI producer explicitly installs the finalizer. Its prepare captures
trusted marker values rather than reading later-mutated task/metadata objects.
No public event field installs markers. This wires the checkpoint hook, NOT a
production event consumer/router or a pre-restore repair coordinator.

Local checks:24 selected plain cases pass (writer5/gate8/actual SDK lifecycle1,
decoder6/parser2/registration SPI2), zero skips. Application reactor test-compile
passes. Three additional real-SDK/JPA cases compile: block the actual ACK audit
after task/proof commit and prove next-task wait; fail ACK and repair later
without resurrecting the failed continuation; change owner AFTER checkpoint
during ACK and prove historical ACK grants no old continuation. Existing eight
SDK cases are unchanged in their assertions. These11 database cases await
current Linux execution, not inferred from #46's eight-case pass.

Pre-restore ACK repair, full group/membership/fan-out/winner/FIFO/foreach/until,
new-owner reconstruction, every later continuation/effect fence, process and
partition proof, loop/parallel reader handling and complete T13 remain open.
The after-ACK owner check is a local boundary check, not a lease spanning every
later observer/effect. No marker-free task is treated as consumed event evidence.

Exact50ce5af executed in39373064706 SUCCESS BOTH lanes: actual SDK/JPA11,
owned10, writer5, native8/standard3, all zero skips. Logs independently inspected;
the three ACK/after-checkpoint-owner cases are now executed, not compile-only.
The next repair increment and its separate scoped evidence/remaining gates are
in [WP-107-recovery.md](WP-107-recovery.md). Do not transfer this11-case pass
to the new original-reader/pre-start-repair or lifecycle failure cases.
