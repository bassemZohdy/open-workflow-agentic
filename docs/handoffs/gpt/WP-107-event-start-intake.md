# WP-107 event-triggered start: identity and policy intake

Requester and proposed lifecycle/storage producer: GPT. Identity, policy and
audit producer: Claude. Status: **proposal only; not approved, implemented or
activated**. WP-107, WP-105, T13 and T16 remain partial/open.

## Observed boundary on main `c07e02c`

`runtime/event/EventIngress` authorizes a deployment-bound producer and commits
an `InboxMessage` for an already existing registry instance. `inbox.message`
has a non-null foreign key to `task.instance(id)` (V64). This interface cannot
persist an event before creating the instance it will start; an event's own
instance ID or correlation extension is not trusted start authority.

`runtime/lifecycle/InstanceService.start(StartRequest, InsertHook)` inserts an
instance and invokes the hook in the same insert transaction, before launch.
The hook runs only on a newly created row, not on a duplicate. The registry key
is unique per tenant; `StartRequest.fingerprint()` covers definition, version
and canonical input, but not owner or a delegated grant. These are existing
facts, not proof that a replay with a changed principal or grant is safe. An
engine start and a later audit result can fail after the insert commits.

## Proposed contract for owner review

1. A server-controlled binding identifies a single permitted producer, tenant,
   source/type, pinned definition/version, input projection and effective run
   owner. Do not derive any of these authority facts from arbitrary event
   extensions, caller-provided target IDs or workflow state. Binding changes,
   version coexistence and maximum input size need explicit rules. Existing
   receive routes target instances and must not be silently repurposed.
2. Claude supplies the verified caller, exact action/resource/scope vocabulary,
   policy and mandatory audit requirements for this start. The identity layer
   decides how an asynchronous run obtains current delegated authority without
   persisting or replaying a bearer. DEV_HEADERS/UNVERIFIED cannot become
   production start authority. A producer claim, policy allow or receipt alone
   is not a reusable permission.
3. GPT proposes an explicitly versioned, domain-separated identity over the
   trusted tenant and CloudEvent source/id, with collision-safe canonical
   encoding and a key fitting `StartRequest`'s 128-character limit. The
   retained contract must bind the exact envelope fingerprint, route/binding
   version, definition/version, input, full owner and authority provenance.
   Same identity and same retained contract is an idempotent retry; changed
   source/id content, owner, route, definition, input or authority provenance
   conflicts. Agree whether and how an authorized retry can use refreshed
   credentials for the same principal/grant without silently rebinding the run.
4. GPT owns an additive receipt/start binding schema and its role/grant and
   upgrade tests. Allocate a migration only after checking the current main and
   reserved V78–V81 proposals; do not rewrite applied migrations. Evaluate
   inserting the receipt and immutable start binding in the `InsertHook` so
   instance, initial history/outbox and event attribution commit together.
   This must handle concurrent duplicates and the hook-not-called-on-replay
   path explicitly: read back and compare the retained binding rather than
   assuming the lifecycle request fingerprint includes owner or grant.
5. Mandatory audit and current authority must be rechecked after blocking
   policy, database and audit operations and before any launch or release. A
   separately committed audit is not atomically bound merely because the
   instance hook ran. Specify the durable source/receipt and uncertain-commit
   behavior before implementation. Crash or lost acknowledgement must recover
   the same retained instance/receipt identity; never generate a new logical
   start or silently replay a side effect. Preserve readiness failure behavior.

No claim is made that a standalone lookup followed by a recheck removes all
revocation or lease races. The producer and consumer must agree on actual
transaction/fence boundaries, deadlines and safe behavior when authority cannot
be established. This proposal adds no schema, public endpoint, CDI consumer,
policy permission or start implementation.

## Required evidence before activation

- Same event redelivery, concurrent duplicates and restart/lost-commit-reply:
  one retained logical start, one receipt binding and no second launch/effect.
- Changed content, full subject kind/id, tenant, route/pin or delegated grant
  under the same identity: refusal without rebinding or leaking another
  principal's private result. Same-key lifecycle retries must prove the binding
  comparison even though the insert hook does not run again.
- Unknown/unapproved source/type/route, malformed/oversized event, stale or
  revoked caller/grant, policy denial/timeout, unavailable mandatory audit and
  missing approved binding: no new instance, receipt or engine launch.
- Expiry or revocation while policy, audit, storage or duplicate readback blocks;
  concurrent cancellation, recovery and duplicate delivery; launch uncertainty
  and process death on each side of commit. Assert authoritative persisted facts,
  no duplicate effects, bounded calls and no credential in state, logs or audit.
- Real PostgreSQL transactional/concurrent tests and actual Flow restart tests,
  plus the identity owner's denial and supported-provider evidence; independent
  exact-head review and required CI before merge or activation.

Affected producer paths are `runtime/event/{EventIngress,JdbcEventInbox}` and
`runtime/lifecycle/{InstanceService,InstanceStore,StartRequest}` plus additive
schema/configuration and app integration tests. These are observations and a
proposal, not permission to change Claude-owned identity/policy/audit paths.
