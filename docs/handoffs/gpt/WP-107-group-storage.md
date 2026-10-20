# Durable listen-group selection storage

Owner: GPT. Reviewer: Claude. Preparation on gpt/WP-107-listen-groups;
#48 remains the only active GPT implementation PR, frozen at e2e9b89.
Normal foundation merge0c14528 retains prepared V70 before centrally allocated
V71/V72; V64..V71 are unchanged by V72. Effect foundation must integrate before V71.
The dependency is migration ordering only: group storage uses already merged
V68/V69 task ownership, not an unmerged identity/provider interface.

## What this stores

JdbcListenGroups registers the exact InboxListenGroup, full producer-provided
configuration JSON and first timeout/deadline. It binds exact UTF-8 snapshot
bytes to the domain-separated configuration fingerprint; snapshots must be
objects, with duplicate/trailing JSON refused and a256KiB limit. The trusted
producer still owns canonicalization, pinned definition/read/foreach, approved
producer/predicate/correlation contents and excluding all credentials. This
storage is not a workflow validator, matching predicate or authorization API.
Timeouts use exact milliseconds,1ms through30days; retries cannot extend them.

V71 adds listen_group, listen_item and listen_member. One retained receipt has
one ordered item but may satisfy multiple ALL memberships, including duplicate
filters. Exclusive DATA slots (ONE/ALL) and UNTIL slots cannot be reassigned;
streaming ANY can select many receipts on one data ordinal. Until-role matches
take precedence over overlapping data matches. Capture closes for a selected
ANY winner, all required ALL slots, or required stopping-event memberships.
capture_complete means INPUT SELECTION only, never task completion, processed
foreach work, consumed checkpoint proof or acknowledgement. Expression-until
remains open until actual ordered output/predicate proof is implemented.

Sequence is durable SELECTION COMMIT order, not event timestamp or a claim
that this API alone enforces ingress FIFO. The future single-owner selector/
coordinator must offer eligible candidates in required arrival order. Paged
history preserves that selection sequence, not filter-ordinal sorting across
receipts. A new owner can load the original snapshot/deadline and all selected
membership through bounded128-row pages; it never consults mutable deployment
defaults to reinterpret old matches. Selecting owner/epoch are historical
write-time attribution, not immutable epoch-history foreign keys or current
callback authority. Recovery must create separately fenced future attempts.

## Transaction, integrity and authority boundaries

Operations take registry -> live task-claim -> group -> retained-message locks
using the existing same-connection lease helper. Fresh ownership is checked
after blocking reads/writes. Each new selection rechecks stored receipt content,
fingerprint, tenant/instance and retention. Selection plus memberships/counter/
capture status commit together. Independent mandatory audit runs AFTER releasing
the store connection/transaction. No connection spans idle ALL waits, callbacks
or audit. A working operation needs one connection, plus separately committed
audit; the existing lease service also has its own short transactions.
Statements have5s query timeouts; history checks current ownership between items
and after the page rather than returning stale authority after arbitrary work.

SQL guards retain immutable snapshots/deadlines and append-only selection/
membership history, validate current task/registry authority on writes, enforce
monotone sequence and pinned slot identities, and derive exclusive membership
from the registered strategy. Capture status must match stored memberships;
expression capture cannot be fabricated by input selection. These controls
protect ordinary writes, not a database administrator disabling triggers.
Metadata/hash values do not authorize a caller or prove predicate truth.

This API returns Recorded selection/history ONLY. It has no callback, dispatch
reservation, SDK mutation, checkpoint or ACK method. Audit failure or an unknown
commit reply may leave visible selected input, but return no callback permission.
Idempotent readback retains the same item/order; it is not safe to treat it as
permission to repeat an effect. Future dispatch must audit and revalidate live
policy/lease/deadline after all blocking work. Selection alone has not won a
dispatch deadline. Historical input may still be read after its group deadline
under a current claim; that does not authorize late execution.

## Verification and remaining full scope

Implementation audit found an additional V71 gap: its member INSERT guard
validated a pinned slot but did not close membership additions after a
selection committed. A later valid-but-unmatched slot could therefore extend
that historical receipt. V72 replaces only that guard function, leaving the
pushed/possibly-applied V71 untouched. It requires the item's live selecting
owner, an open group/current not-yet-advanced sequence, and membership in both
the pinned registration AND the original item's complete match set. Java
readback independently rejects members outside the retained match set and
malformed/changed original match carriers. This is an implementation audit,
not the mandatory independent Claude approval.

The new eleventh PostgreSQL case attempts a pinned but previously unmatched
late insertion in a transaction that always rolls back; it must receive42501
and leave the original selection/capture state unchanged. The initial ten
cases atbaadd56/39378344724 do not cover this missing late-insert path. All11
compile with the final application reactor. Current V72 execution remains
pending its own Linux run; no baseline success is transferred to the fix.

Two additional plain runtime cases require ZERO database/audit access for
duplicate/trailing/non-object snapshot JSON, changed snapshot fingerprint and
unsupported timeout precision. Together with group9/task2, actual SDK group4,
telemetry5/decoder6/SPI2,30 selected plain cases pass, zero skips. There is no
local PostgreSQL listener or usable Docker daemon; SQL runtime evidence comes
from the isolated Linux jobs, not these in-process validation cases.

Final local application reactor test-compile passes. An initial pair of invalid
compound var declarations in the new test was corrected; assertions stay.
28 selected plain cases pass (group9/task2, actual SDK group4, telemetry5,
decoder6, registration SPI2), zero skips. These do not execute PostgreSQL.

The baseline ten actual PostgreSQL cases: overlapping ALL without
legacy signal/proof; ordered fresh-store pages; concurrent ANY selection;
stream ordinal reuse and stop precedence; expression capture never falsely
complete; owner handover with historical configuration/attribution; changed
snapshot/deadline/group/receipt/scope refusal; failed audit outside locks;
unknown real commit reply with retained readback; direct SQL immutability and
sequence/deadline refusal. Match sets and external receipt payloads are explicit
component fixtures, not proof of predicate, ingress authorization or actual SDK
consumption. No fabricated checkpoint receipt is inserted.

No production consumer/router/ServiceLoader is activated. The full work in
WP-107-groups.md remains: complete pinned selector including nested strategy
semantics, durable callback attempts/winners and processed-item proof, bounded
FIFO coordinator/renewals, actual group/foreach/until SDK integration, every
later ownership/effect/cleanup boundary, original-reader loop/parallel and
whole-group process/partition/old-owner/cancellation restoration. Event starts,
retention/tombstone/archival (history is retained), manual OPERATION, central
audit delivery/commit-ambiguity contract, terminal metrics/traces and full T13
including failed compensation remain acceptance gates. Database growth/quotas
and archival are not solved by bounded per-operation pages. WP-107 stays partial.
