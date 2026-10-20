# ADR-0029: Retain approved resource-task allocations

Status: Proposed; explicit preparation, consumer and budget gates remain

Date: 2026-10-20

## Context and decision

Claude's model-boundary finding on #56 identifies a real missing source: an
HTTP topic/thread and route allow do not provide an approved AgentTaskContract
or authoritative remaining budget. The existing AgentTaskRecord retains legacy
ws1(tenant, owner.id, instance) ownership. A governed ResourceCallScope has a
different full-owner invocation thread; replacing one with the other fabricates
ownership and breaks both contracts.

Retain a separate immutable ResourceTaskAllocation for the FIRST canonical
resource call. It binds the full scope, actual parent task when present, approved
AgentTaskContract and approved registered model profile. Its ra1 identity excludes
mutable approval content; changed task/grant/profile/target/request conflicts at
the same original call. Content fingerprints bind every contract field and model
profile bound with strict UTF-8 length prefixes, ordered allow-lists and fixed
64-bit numbers. They establish data consistency, not approval or permission.

The allocation pins one child contract id, actual compatible issuing/narrowed
grant, full owner, config/profile version, registered agent/model, finite model
call count, per-call monetary ceiling, timeout and output-token limit. The task
still has its independent loop/wall-clock/cost limits. Calls are not inferred
from iterations. An external price/retry guarantee and real approved configuration
must justify the per-call ceiling before use; recording a number proves neither.

Proposed V78 remains outside Flyway, after the separate V77 audit preparation.
The explicit, unannotated JdbcResourceTaskAllocations implementation has no CDI
activation. New inserts serialize on the actual registry, validate the full
canonical call and occurrence, real grant ancestry/subject/agent, and actual
parent ownership/legacy thread/narrowing. Database time follows lock waits.
The original deadline is the minimum of task lifetime, actual grant ancestry,
remaining parent lifetime and the current registry deadline. Repeats return the
original record, including after completion/revocation, with no time/budget reset.

All reads/writes suspend caller JTA and use short owned transactions. One joined
statement reads the allocation and retained call/occurrence coherently; missing
metadata refuses. Unknown commit or resume replies throw and reconcile the same
allocation. No fresh permission or callback is returned. Exact JSON shape checks
refuse ignored fields/coercions; no raw bearer, prompt, checkpoint or private
runtime context is stored. Producers should use an approved bounded request
fingerprint reference as the goal when the real request exceeds contract bounds.

One validated FK requires allocation before NEW execution entry. If historical
V76 entries lack allocations, proposed V78 activation fails: preserve facts and
obtain a reviewed historical-data decision. No deletion, fake grant/task, guessed
backfill, NOT VALID bypass or rewrite of applied migrations is supplied.

## Alternatives and consequences

Reusing AgentTaskRecord with a governed thread violates its ownership invariant.
Deriving a new task on token refresh resets authority/budget. Keeping only a
fingerprint loses the approved contract needed by the PEP. A mutable shared row
allows approval replacement and makes recovery ambiguous. Retain full immutable
allocation data and separate later operational budget reservations instead.

The extra retained record needs foreign-key retention and explicit archival/
restore policy. Goal/owner/config data is confidential; use bounded approved
references, datasource access controls and current authorization before exposing
it. Fingerprints are not secrecy or storage authenticity. Direct calls still need
their own genuine authorized allocation and remain refused until supplied.

## Verification and adoption gates

Local contract and preflight/codec tests execute; proposed SQL/store tests are
compiled for real PostgreSQL CI. The handoff records exact current results and
never treats compilation as database execution. Actual concurrent creation,
changed approval, committed-reply loss, caller JTA rollback, parent/grant/clock
refusals, historical data, corruption, retention and deployment-precondition
cases must pass on the final product SHA. No independent second PR while #55
awaits review; exact peer review and required final CI precede activation.

Durable atomic model-node reservations, unknown-charge retention, live composite
identity/registry/parent/model fence, mandatory audit, private async context,
non-bypassable model gateway, full occurrence producer, graph/process/restore
and actual governed HTTP success remain separate gates. No model invocation,
whole WP-103/WP-202/WP-108 closure or acceptance promotion follows.
