# WP-107 owned dispatch storage successor

Owner/producer: GPT. Reviewer/consumer handoff: Claude. Date: 2026-10-20.

Integration update: #46 exact89b0f0d merged main34b6a52 after
reviewer=Claude6284086888/6284089490 APPROVE (independent full Maven verify,
no blocking findings), required full39371032850 and real matrix39370993287
SUCCESS. #44 previously merged into #40 after its own exact peer/CI gates.
Earlier pending-review statements below describe their historical stage.
Whole consumer/effect/partition/full T13 scope remains partial.

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

## Executed V69 baseline and observed transaction correction

Exact34a342a workflow39370100027 FAILED both lanes (229 application cases each).
OwnedInboxDispatchTest10/10 passed in each, zero skips, including real PostgreSQL
migration and ambiguity/budget cases. AtomicInboxCheckpointTest7 had one
blocking-observation failure and two positive timeouts; InboxTaskLeaseTest7 had
one actual JTA error. Root cause: the new package-private requireCurrent helper
inherited class NOT_SUPPORTED. CDI self-interception suspended the caller's JTA,
so Agroal rejected its already-enlisted connection (Enlisted connection used
without active transaction). SUPPORTS on that helper preserves the existing
caller-owned JTA or the local caller-owned JDBC transaction, as appropriate.
Neither creates/commits another transaction. All original assertions remain.

The mixed-order successor578d3c2 had not completed; its superseded39370406321
was cancelled by GPT after this reproducible common cause was identified. That
is not a pass. New corrected-head execution and exact reciprocal review are
required; no provider evidence is claimed from these failed/cancelled runs.

GPT merged #44 exacta6bcccf into #40 as2d8761f after Claude6283480485 APPROVE
and required full39367327487 SUCCESS. Review follow-ups are explicit in ADR0026:
the checkpoint fence does not undo a callback's external effect; unreceived
post-audit claims delay another owner at most their original30s expiry; receipt
epoch fields are write-time fenced historical attribution, not an immutable
epoch-history FK. No material finding was waived and #44's approved head was
kept unchanged. This storage successor can now have the single active GPT
implementation PR; WP-107/full T13 remain partial.

## Corrected owned storage executed on real PostgreSQL and SDK

Exact89b0f0d39c9fe5dae9d24bb488427a668f12a6c5 workflow39370993287 SUCCESS
in BOTH lanes. GPT inspected complete run logs and head metadata: actual SDK/JPA
checkpoint8, owned dispatch10, lease7, protocol9, selection6, late cancellation2,
binding7 each lane; native Keycloak8 and standard3. Every listed class has zero
failures/errors/skips, and both strict provider evidence checkers pass. This
verifies the SUPPORTS correction and the eighth mixed-order cancellation case,
rather than transferring the failed34a342a or cancelled578d3c2 result.

The tested checkpoint case proves actual advisory/registry ownership plus a
blocked real cancellation command, then earlier checkpoint completion, cleanup,
retained proof/ACK and healthy independent work. It does not prove the separate
committed-cancel-request/before-engine-call crash window or distributed cleanup.
Full required #46 build39371032850 and exact Claude review still gate integration.
Published #46 head stays frozen; this evidence is recorded on its consumer
successor without invalidating the current review target. Parent #40 exactdbe3bd8
full39370961537 and matrix39370996254 are also SUCCESS; its exact Claude review
still gates main integration. No package or complete T13 status is promoted.
