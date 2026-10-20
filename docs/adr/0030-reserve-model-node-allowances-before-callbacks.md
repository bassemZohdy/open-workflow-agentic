# ADR-0030: Reserve model-node allowances before callbacks

Status: Proposed preparation; no CDI/provider activation or whole budget closure

Date: 2026-10-20

## Decision

Bind one model-node reservation to the FIRST resource call and complete canonical
graph-node occurrence. Credential, SDK retry, execution/worker epochs and request
content do not create a new key. Changed request/entry/executor/tokens conflict.
The private producer derives actual loop/branch coordinates and exact ModelCall
request digest; no caller nonce, raw prompt or credential is stored.

Use append-only MODEL allowance facts in the existing PostgreSQL transaction
store. Each reservation holds the full per-call ceiling from the original stored
approved model profile, with the original task cost/count/lifetime. It is reserved
allowance, not measured expenditure. Exception, missing reply, DENY/ERROR,
provider failure or output refusal does not imply free usage or permission to
retry. This first protocol retains the full hold and supplies no refund/settlement.
Provider pricing/maximum charge/retry guarantees and actual usage remain explicit
consumer/approved-configuration gates, not numbers guessed from a string response.

Sibling allocations share the actual legacy parent's immutable MODEL budget
account when a parent exists; otherwise the allocation anchors its own account.
The account pins the real full owner/instance/contract cap/original deadline.
New nodes serialize on the actual registry, recompute retained charge integrity,
and atomically check both leaf cost/call slots and shared parent model cost.
Model-call count is independent of agent-loop iterations. Tool/other spending and
unrepresented parent hierarchies must converge before full task-budget closure.

Only a newly acknowledged reservation INSERT+commit may enter the authorizer and
synchronous provider closure once. invokeOnce never returns a reusable permit.
Historical reads return data; repeat calls refuse even if the first callback
failed or the commit reply was lost. Unknown committed facts remain reconciled
under the SAME node. Raw INSERT/record possession alone cannot invoke a callback.

Authorization prepares Claude's actual PEP/mandatory audit and returns a closure
without calling the provider. The gate rechecks registry/occurrence/allocation,
entry/executor, grant ancestry/delegate, actual parent state/narrowing, account
source and original deadlines, then the private live identity fence AFTER that
blocking preparation and immediately before the closure. It repeats checks after
the provider before releasing private output. No connection is held across either
callback; caller JTA is suspended across ALL hooks, including the first fence.
Local lock/statement timeouts (2s/5s) bound owned database waits and do not close the
caller's transaction. Provider transport timeout/retry enforcement remains owned
consumer work; pre/postchecks do not implement network cancellation.

The call deadline is fixed at reservation: minimum of original allocation/account
lifetime and DB time plus profile call duration explicitly FLOORED to microseconds.
Authorization time counts. It never extends on retry/refresh. The PDP budget input
is the protected ceiling for this call, not a reset task maximum or an unprotected
free-balance snapshot. Other reservations cannot consume that held allowance.

Proposed V79 stays outside Flyway after V78 and the separately prepared V77.
Account/reservation data is immutable and FK-retained. Database assigns amounts,
ordinals, remaining snapshots, timestamps and hashes; callers cannot override
them. Exhaustion uses dedicated SQLSTATE OA001; other SQL failures remain
unavailable/corrupt context, never allowance. [PostgreSQL16 permits explicit
application SQLSTATE codes](https://www.postgresql.org/docs/16/plpgsql-errors-and-messages.html).

## Consequences and verification

This does not implement the actual governed LlmClient/ModelGateway, private async
identity propagation, registered approval/model scope, billing, retry recovery,
complete occurrence production, physical provider at-most-once, graph recovery or
backup/restore. Those must be independently proved before activation. ModelGateway
shared documentation no longer calls an uncertain billed retry automatically safe;
ModelResult.Completed cost must be trusted actual usage, not the reservation cap.

Tests cover value/key/amount/time invariants, actual PostgreSQL transaction and
callback ordering, concurrency, parent aggregate exhaustion, actual commit reply
loss, lifecycle/grant/deadline/interruption/output refusal, JTA/connection bounds,
retained/missing/corrupt metadata and direct-SQL tamper/batch limits. Spy callbacks
prove protocol entry counts, not real provider or signed-identity enforcement.
The handoff records exact executed/pending evidence and remaining adoption gates.
No acceptance status or whole WP-103/WP-202/WP-108 completion is promoted.
