# Manual effect recovery API

The operator boundary connects verified human requests to the retained manual-effect resolver.
It records the chosen outcome of an already attempted effect whose result is unknown. It does
not call a target, refund a model reservation, resume a workflow, or authorize another attempt.

## Activation

`agentic.effects.manual-resolution.enabled` defaults to `false`. Every request still requires
authentication. A verified caller receives 404 while the boundary is disabled.

An enabled application requires the manual-decision and apply-link tables at startup. The schema
remains the separately reviewed [V80 proposal](../modules/oa-runtime/src/main/resources/db/proposed/V80__manual_effect_decisions.sql).
No new schema is installed by this feature and default Flyway locations remain unchanged. Never
add the whole `db/proposed` directory to Flyway to enable this API: its other proposals are unrelated,
and advancing the shared Flyway version past unapplied lower versions affects later migration ordering.
A deployment needs a reviewed installation/upgrade plan for V80 and its effective database grants
before setting the switch to `true`. The boot check verifies required columns can be read; it is
not an attestation of all constraints, trusted schema ownership or deployed least-privilege roles.

The resolver uses the application's datasource, transaction manager and existing operations PEP.
The datasource needs the existing ledger, registry, scoped provenance and central audit permissions,
plus the V80 decision/apply permissions. The
[resource-provenance template](manual-effect-provenance-roles.md) supplies only its named read grants;
it is not a complete runtime-role grant script. Missing permissions refuse work. Runtime-role deployment
and independent final project review remain separate delivery gates.

## Requests

All operations require a non-delegated, verified **USER** credential for the configured API audience,
the signed `oa.recover` scope, and a current ALLOW for `operation:resolve-effect`. `oa.operate`,
development headers and agent/service credentials cannot resolve an effect. Policy errors refuse;
manual resolution does not use the safe-recovery cancel exception.

`GET /operations/instances/{id}/effects/unknown?limit=50&cursor=...` lists only that verified tenant's
instance. Each item contains `effectId`, `firstSeen` and `lastSeen`. Limits follow the shared
`PageRequest`: 1–200, default 50. Pass the returned `nextCursor` unchanged until it is null.
Pagination is a live view; concurrent resolution can remove items between pages. No snapshot of
all pending work is promised. Neither raw thread/node/idempotency keys nor target/detail data are returned.

Resolve an item with:

```http
POST /operations/instances/{id}/effects/{effectId}/resolve
Authorization: Bearer <current operator access token>
Idempotency-Key: <new ULID for this decision>
Content-Type: application/json

{"outcome":"CONFIRMED","reason":"TARGET_CONFIRMED"}
```

Use `CONFIRMED` with `TARGET_CONFIRMED`, `FAILED` with `TARGET_REJECTED`, or either with
`OPERATOR_VERIFIED`. The operator is asserting a verified historical outcome, not asking the
server to verify the target. Target evidence and its review remain the operator's responsibility.
The response contains only `effectId`, `requestId` and the retained effect `state`.

The idempotency key is an immutable decision identity. Exact retries by the same verified operator
reuse the decision and central audit receipt; changing actor, effect, outcome or reason conflicts.
The application appends the matching central request audit before applying the outcome, rechecks
current authority and policy around blocking work, and retains one immutable apply link. A request
audit is an intent and does not itself mean the ledger outcome changed.

401 requires current authentication, 403 indicates scope/subject/policy refusal, 404 indicates disabled
or unknown tenant-scoped work, 400 indicates malformed input, 409 indicates retained-state conflict,
and 503 indicates unavailable policy/storage/audit or an unconfirmed result. If a response is lost,
or a refusal follows a committed change, do not generate a replacement decision automatically:
inspect and retry the **same** idempotency key and exact content with current authority. Committed
history is not rolled back when output is withheld after expiry. Authorization is re-evaluated on
every retry, including after instance completion.

## Security boundaries and verification scope

| Threat | Control |
| --- | --- |
| A supplied tenant, actor or raw key selects another user's effect | Caller claims come from verified JWTs; lookup joins retained scope to the tenant-scoped registry instance; the public identifier is a fingerprint |
| An agent self-resolves consequential work | Only a verified non-delegated USER with `oa.recover` and PEP ALLOW can enter the resolver |
| A duplicate request changes the asserted result | Immutable decision content, caller binding, central source receipt and unique per-effect apply link |
| An audit failure produces an unaudited manual mutation | The retained resolver requires the exact central receipt before apply; missing audit refuses |
| Caller expiry during blocking I/O releases stale output | Current authority/policy checks before mutation and before response; durable history is retained independently |
| Error handling exposes private target content | Opaque public IDs, minimal responses and fixed error messages; dependency logs contain failure class only |

The HTTP tests use the actual Quarkus endpoint, bundled Cedar policy, transaction manager and
PostgreSQL audit/ledger implementation. Their issuer is a fake serving real test signatures/JWKS,
and V80 is applied only to the private test database. They do not establish real-Keycloak caller
interoperability for this route, deployed roles, supported delegated Flow execution, automatic
effect recovery, or complete T03/WP-108 acceptance. No production switch is enabled by the tests.
