# Complete listen-group behavior and durable consumer requirements

Owner: GPT. Reviewer: Claude. Preparation branch gpt/WP-107-listen-groups,
from merged main34b6a52. No additional implementation PR while #48 is active.
No migration is allocated, production consumer activated or acceptance promoted.

## Executed full-workflow observations

SdkListenGroupBehaviorTest runs real WorkflowApplication/ListenExecutor and the
pinned InMemoryEvents broker, not a mock registration future. Four tests pass
locally, zero failures/errors/skips. Four OWS1.0.3 fixtures pass the official
schema/lint validator with no findings. Command:
`mvn -B -pl modules/oa-runtime -am test -Dtest=SdkListenGroupBehaviorTest
-Dsurefire.failIfNoSpecifiedTests=false`.

1. ALL with distinct A/B filters: B arrives before A; the actual completed output
   is B,A, not filter declaration order. Partial ALL does not complete.
2. One event matches both overlapping ALL filters and the actual full workflow
   completes with two memberships of that receipt. This is a pinned delivery
   observation; the specification does not independently settle every overlap
   policy. Do not require a second distinct event merely to avoid fan-out.
3. NEGATIVE CONTROL: identical ANY filters receive one broadcast. The immutable
   completion-time snapshot contains one event, but the instance's output later
   contains it twice because the captured losing callback still runs. A later
   new publication reaches neither registration. Unregistration alone cannot
   fence an already captured loser or freeze completed output.
4. NEGATIVE CONTROL: the first foreach child's real task-start future is held.
   The second child starts and completes before it; then an actual until/stop
   event completes the parent with only the second result. Releasing the first
   child mutates the already-completed result to second,first. The stop event
   itself is excluded. These are genuine FIFO/in-flight-child gaps, not a
   passing serial stream or durable recovery test.

No wall-clock delay selects these outcomes: explicit registration latches,
child start/release futures, synchronous broker publications and completion
snapshots establish their order. The tests characterize the pinned dependency;
a future upgrade that changes these observations requires renewed review.

## Required end state, not the negative controls

OWS1.0.3 requires ordered consumed output and FIFO foreach work; stopping events
are excluded. The production bridge must serialize receipt delivery and child
completion, persist ordered membership/consumption, and exclude captured losers
before their SDK callback. Full group behavior cannot be substituted by a
one-event-only consumer or by sorting results after effects have already run.
The source is the official
[Listen contract](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#listen)
and [consumption strategies](https://github.com/open-workflow-specification/specification/blob/v1.0.3/dsl-reference.md#event-consumption-strategy),
plus the actual pinned SDK1.2.0 source/execution above.

The additive durable model must represent a task group, immutable pinned filter
ordinals/roles (including duplicate filters and until), receipt membership and
arrival/consumption sequence independently. V64's global per-receipt signal
uniqueness cannot be reused unchanged for overlap. ANY winner and all callback
permissions must survive owner handover; receipt/ordinal identity is distinct
from one owner/epoch's attempt. ALL reconstruction starts from the whole durable
uncheckpointed group, never injects events into a stale partial in-memory array.
Streaming registrations need repeated distinct receipt membership, not just one
permission per static filter/epoch. Until must wait for earlier admitted items
and in-flight children before releasing parent completion.

The bridge must bound queued work, avoid JDBC connections across idle waits,
callbacks and independent audit, and renew/check one current task owner. Pin
approved producer bindings, predicates and correlation before selection;
recheck cancellation/deadline/live authority at every release boundary.
Per-item foreach progress needs actual checkpoint/effect proof, not merely the
later parent task-completed marker. The original reader's position-only task
map and repeated child occurrence/iteration identity need explicit loop/parallel
and stream restoration evidence before any production stream activation.

## Next implementation and verification

### Framework-free group and membership contract

InboxListenGroup now describes ONE/ANY/ALL, event or expression until, ordered
filter fingerprints (duplicates retained), and one current InboxTask occurrence.
configurationFingerprint must cover the complete pinned listen/read/foreach
definition PLUS approved producer/predicate/correlation configuration. The
runtime producer still has to compute/validate that snapshot; a supplied hash
is data, never authorization. Group/registration digests use new ig1/ir1
canonical domains, with full task identity, roles and ordinal. They contain no
owner/attempt/resume epoch, and do not rewrite existing single-wait keys.

ANY [] and event-until ANY [] each normalize to one logical wildcard slot,
independent of physical transport registrations. DATA and UNTIL roles remain
distinct even with identical filter fingerprints. Total logical slots are
bounded at128; oversized definitions must be refused before callback/persistence
activation rather than silently losing filters. ONE/ALL cardinality and legal
until shapes are validated. Expression until has no stopping-event slot and
must be evaluated after ordered actual item output by the future coordinator.

classify accepts the trusted selector's complete match set of ONE receipt.
It rejects foreign group/role/ordinal/filter matches; returns all matched ALL
memberships in their within-receipt AST order, or the first matching ANY slot;
and gives UNTIL matches precedence so stopping events never become data output.
It does NOT sort different receipts or assign arrival sequence, evaluate a
predicate, persist a winner, know which ALL slots were already satisfied, or
grant callback/ACK/resume permission. Match records validate ordered distinct
slots of one group/role. Receipt dedup, ordinal progress, ownership, mandatory
audit, ordering and actual checkpoint/effect proof remain store/coordinator work.

Validation: `mvn -B -pl modules/oa-contracts test` BUILD SUCCESS,172 tests,
zero failures/errors/skips, including8 new group cases and existing purity/
schema/key/port tests. They cover duplicate memberships, ANY choice and
stopping-event precedence, wildcard roles, full snapshot/task replay isolation,
foreign/forged matches, bounds and contradictory match carriers. The initial
null-expression test expected the wrong exception class; it now follows the
existing Checks contract (required null -> NullPointerException) while still
requiring refusal. No predicate/DB/engine/authority pass is inferred from these
value tests. The four actual SDK negative controls above remain separate.

### Store and runtime work still required

Implement the durable group/membership/winner/ordered-item contract, a bounded
serialized coordinator, whole-group reconstruction and per-item/parent proof
binding. Only then use the reviewed finalizer/pre-start repair from #48 and
the governed effect/identity interfaces at their evidenced scope. Negative
controls stay clearly labeled; guarded actual-engine tests must prove the OWS
behavior and crash/partition/old-owner/cancellation cases that the default SDK
does not supply. No new migration range is reserved ahead of GPT's V70 work.

Complete event starts, retention/tombstones, manual OPERATION authority,
terminal audit/metrics/traces and full T13 including failed compensation remain
required. WP-107/ADR0025/ADR0026 and acceptance status stay partial/Proposed.
These four in-memory cases contain no PostgreSQL, OS-kill, distributed owner,
production broker or complete conformance evidence.
