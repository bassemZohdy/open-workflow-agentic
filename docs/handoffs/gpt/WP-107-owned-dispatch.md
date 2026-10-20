# WP-107 owned dispatch storage successor

Owner/producer: GPT. Reviewer/consumer handoff: Claude. Date: 2026-10-20.

Branch `gpt/WP-107-owned-dispatch` follows exact #44
`a6bcccf53f408fb9adcad1b7c948bd73840d3e9b`. #44's required full CI39367327487
passed, including its seventh real SDK reservation-wins-deadline case. Its exact
Claude review remains pending. This successor is pushed for preservation and
manual Linux execution, with no duplicate implementation PR while #44 is active.

## Contract and paths

`JdbcOwnedInbox` provides matching/reservation/ACK primitives for the future task
coordinator. Registry -> task lease -> delivery/message locks fence matching and
reservations; time is checked after blocking reads. The same connection reads
the actual immutable checkpoint proof. Transactions close before audit, and
authority is rechecked after audit before permission returns. No callback or
idle partial all-listen holds a connection. This addresses #43 M1's legacy
two-checkout budget in the new path; the old inert storage port remains available
for its component tests, not for production registration ownership.

V69 adds task digest/owner/epoch to delivery rows and prevents a second attempt
within an epoch or ownership downgrade. V64..V68 are untouched. Actual JPA
checkpoint proof now requires the exact owned reservation as well as the live
task claim, receipt and dispatch attempt. All seven actual SDK cases use this
new storage path. No proof is manufactured by callback return or test doubles.

One permission per registration/epoch is deliberate: after an ambiguous commit
reply or failed audit, the attempt remains visible but retry returns no second
permission. Recovery must take a new epoch and reconstruct the WHOLE unfinished
task; do not inject a replay into an existing partial all-listen's futures/output.
An in-time committed reservation still wins its original wait deadline (#43 M3),
while cancellation or lost lease rejects the checkpoint. Historical consumption
can ACK after completion/lease loss without granting continuation authority.

Files: runtime/event/{JdbcOwnedInbox,JdbcInboxTaskLease,JdbcEventInbox,
JdbcInboxDispatch,JdbcInboxCheckpointProof}; migration V69; application event
OwnedInboxDispatchTest and AtomicInboxCheckpointTest; proposed ADR0026; source
plan/projections. No Claude-owned identity/policy tests or leaf implementations
are changed.

## Evidence and failure cases

Local application reactor test-compile passes. Ten new real-PostgreSQL component
cases compile: one permission and no false ACK; old-owner match/reserve refusal;
new epoch retains original receipt/deadline; failed audit; actual committed SQL
followed by injected JDBC lost reply; expiry during audit; expiry after a proven
message-row lock wait rolls back binding; cancellation during audit; expired
unmatched wait; SQL/legacy ownership bypass refusal; one-checkout budget and no
transaction spanning audit. The listed scenarios share ten test methods.

The lost-reply proxy commits against the REAL database before throwing SQL08006;
it proves JDBC ambiguity handling, not TCP partition or process death. A bounded
data-source wrapper refuses concurrent second checkout; audit opens a real SQL
connection only after the storage transaction has closed. Actual SDK/JPA cases
separately prove atomic receipt/task commit and rollback, delayed-expiry fencing,
task handover, reservation-wins-deadline, retained proof and restart-before-ACK.

Database execution of this successor is pending Linux CI. Local compile is not
relabeled as database evidence, and prior #44 CI does not validate V69. No T13
acceptance annotation, count or status is promoted. reviewer=GPT self-inspection
is not reciprocal approval; reviewed SHA and executed CI will be recorded after
the pushed commit exists.

## Gates retained before production

Implement the actual SDK consumer/coordinator with canonical CloudEvent decoding,
pinned producer bindings and predicate correlation; stable duplicate-filter
ordinals; whole all/any membership and durable any winner; serialized callbacks;
bounded renewals/executors; nonblocking ACK-before-advance; pre-restore ACK repair;
whole-task recovery and owner loss AFTER checkpoint. Prove real process/partition
windows, loop/parallel restore (including the pinned reader's position-only map),
governed foreach effects, event starts, retention/tombstones, terminal disposition
audit/tracing and manual OPERATION policy. #40 L1 mixed advisory-lock/cancellation
ordering remains an explicit gate, as does distributed cleanup/continuation
authority. Complete T13 includes failed compensation, not just these primitives.

WP-107 remains partial and ADR0025/0026 Proposed. No production consumer is wired.

## Mixed checkpoint/cancellation ordering follow-up

The next focused case for #40 L1 compiles against the actual SDK/JPA writer. A
real PostgreSQL lease-row lock stalls its checkpoint AFTER it owns the OACP
advisory lock and registry lock. pg_blocking_pids plus pg_locks proves those
facts. A real registry cancellation command then waits behind that writer; a
second blocking observation proves the command contends rather than bypassing
it. Releasing the lease row lets the earlier checkpoint win; the command commits
before calling real SDK cancellation/cleanup. Assertions require bounded
completion, no deadlock/global checkpoint poison, no SDK rows after cleanup,
retained immutable consumption and historical ACK, and successful independent
work using the same writer/gate. The following workflow task is held by a test
lifecycle future, not an external side effect.

This adds an eighth actual SDK case without changing the first seven or product
code. Local reactor test-compile passes. Its database execution remains pending;
the seven-case V69 baseline at34a342a runs separately in39370100027 and cannot
prove this new interleaving. The complementary cancellation-before-checkpoint
and cleanup-before-late-write cases remain required and unchanged. This local
ordering case does not prove cross-process cancellation/cleanup ownership or
the committed-cancel-request/before-engine-call crash window; retain those gates.
