# WP-107 receipt repair before restored SDK work starts

Owner: GPT. Reviewer: Claude. Date: 2026-10-20. Successor to the frozen #46
89b0f0d and ACK-before-advance50ce5af; no additional active implementation PR.

## Authoritative historical repair

JdbcInboxCheckpointRecovery reads actual immutable checkpoint_receipt rows;
uncheckpointed reservations never count. It validates the pinned registry
tenant/instance/full owner/definition/engine, application, task identity, exact
dispatch/owner attribution, immutable wait digest and retained message fingerprint.
Historical V67 proofs with all-null ownership remain consumption evidence, never
ownership grants. A conflicting/cancelled/expired disposition with proof fails
closed instead of silently starting over it. Already-ACKed receipts need no new
payload read or repeated audit.

Read/lock and update transactions are short. The independent mandatory audit
finishes between them, with no recovery-store connection/transaction retained;
the second locked read must identify the same actual proof before ACK. ACK alone
does not grant resume or dispatch authority. Each SQL statement has a5s query
timeout. repairBatch accepts1..128; the production gate uses64. More work returns
false and is retried on another pass, without a resume epoch or SDK start.

The existing Reconciler still holds its own session advisory claim while a batch
runs. Its caller therefore needs that claim connection PLUS one active recovery/
audit connection: two, plus other lifecycle/HTTP/ACK-finalizer headroom. The repair
store itself retains no connection across audit. This does not claim a one-total-
connection reconciler or change transaction-mode PgBouncer restrictions on its
existing session claims. No connection is retained through a partial listen.

## Existing recovery path, identity and readiness

RestoreCheckpointGate is injected into the real Reconciler. Both pending-start
adoption and restored RUNNING/WAITING paths require it before startRestored. The
existing ResumeGate checks identity BEFORE and AFTER receipt audit/storage;
registry cancellation/pinned scope is refreshed after that authority I/O too.
No epoch increments or start occurs on incomplete repair or refusal. A prepared
STARTING instance denied reauthorization stays STARTING with a held audit; it
does not attempt the forbidden STARTING->WAITING transition or execute anyway.

InboxRestoreCheckpointGate preserves the checkpoint-health latch: a repair
storage/audit/proof error marks recovery required, holds readiness/new starts and
still allows required cancellation cleanup. Later successful SQL repair does
not clear that failed process's latch; restart after fixing storage/audit, then
repair before starting restored work. A normal partial batch is pending work,
not a false successful resume. The original reader implementation is retained.
Existing isolated engine fixtures have an explicit ALLOW repair seam; the real
CDI constructor always receives the concrete gate.

## Evidence, clearly scoped

Prior exact50ce5af workflow39373064706 SUCCESS both lanes: actual SDK/JPA11,
owned dispatch10, writer5, standard3/native8, zero failures/errors/skips. This
proves the three asynchronous ACK/late-owner cases introduced there, not this
new pre-start repair. Main#40 merge7c36d17 postmerge39372837340 SUCCESS.

Local new validation:25 selected plain cases pass (including recovery-required
health), application reactor test-compile passes, and the two-listen fixture is
OWS1.0.3 schema/lint valid. Initial missing Set import and a test-loop extra
parenthesis were corrected; final compile passes with original assertions.

Five new PostgreSQL recovery cases compile. Every consumption proof comes from
the actual SDK callback + original JPA writer; no proof row is manufactured:
uncheckpointed reservation stays unacknowledged; two retained proofs drain in
one-entry batches without repeated audits; wrong app/tenant/full owner refuses
before audit; audit failure retains proof and health latch until a fresh process
gate; original JpaInstanceReader plus real FlowWorkflowEngine/Reconciler repairs
ACKs before the next restored task and does not redeliver completed listens.
The historical fixture uses the original pre-finalizer supported writer path
to retain real checkpoint-before-ACK gaps, then closes the old write gate.
That is COMPONENT restart, not an OS-kill/partition or live old-owner proof.

Five additional lifecycle cases compile with real registry/epoch persistence and
the existing explicit engine double: partial batch, receipt failure, authority
loss during repair, cancellation during repair, and denied initial PENDING start.
Their engine double is not relabeled as actual SDK evidence. New database/reader
execution remains pending CI;25 local cases do not establish these10 SQL cases.

## Full remaining end state

No event consumer/router is activated. Durable complete all/any membership and
overlap/fan-out, stable duplicate ordinals, any winner, FIFO/foreach/until, pinned
producer bindings/predicates/correlation, bounded coordinator/renewals, all
later continuation/effect/cleanup ownership, process/partition/old-owner windows,
loop/parallel reader behavior, manual OPERATION policy, event starts, retention/
tombstones, terminal audit/metrics/tracing and full T13 (failed compensation)
remain gates. Identity exchange/propagation into actual live production Flow
steps remains the original WP-102/M11 consumer gate; core/provider interfaces
are merged and usable. This repair is not complete distributed ownership or
complete recovery merely because a stored receipt ACK succeeds.
