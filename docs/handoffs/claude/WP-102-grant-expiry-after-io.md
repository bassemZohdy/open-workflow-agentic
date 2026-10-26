# WP-102 grant expiry after I/O

Branch `claude/WP-102-grant-expiry-after-io`, on main with #79/#80.

## Defect
`DelegationGrants.check` sampled `now()` once, before a loop of blocking `store.find` reads, and judged the grant and every
ancestor against that stale instant. A grant or ancestor could expire during the reads. `ResourceTokenGuard`'s final token
expiry check does not cover this when the signed token outlives a shorter grant.

## Change
`check` still reads the lineage once (bounded by `MAX_DEPTH`, no retry, no cache, no extra worker). Each read is judged
against a fresh clock, and when the root is reached, expiry of every grant read is judged once more against a fresh clock
taken after the last read, with no further I/O. Outcomes stay typed: depth 0 gives `EXPIRED`, an ancestor gives
`ANCESTOR_INACTIVE`, a missing grant gives `UNKNOWN` (depth 0) or `ANCESTOR_INACTIVE`. Tenant, user, actor, scope and
grant checks in the guard are untouched. No new default allowance.

## Evidence (private PostgreSQL, real rows, `MovableClock` advanced in a store read hook)
Baseline on unchanged production code, `DelegationGrantExpiryAfterIoTest` + `LiveIdentityCheckTest`: 18 run, 4 failures
(direct: grant expires during its own read; child expires while the ancestor is read. Live check: grant shorter than its
token; child expires exactly at the ancestor read). The two positive tests passed.
Corrected: 18 run, 0 failures. With the neighbouring identity tests (`DelegationGrantsTest`, `AgentResourceAuthorityTest`,
`AgentResourceBindingAdmissionTest`, `AdmittedCallWitnessTest`, `LiveIdentityPairingTest`): 74 run, 0 failures.
Covered: shorter grant vs token, exact-expiry refusal, one microsecond before expiry still active, UNKNOWN and
expired-at-start unchanged.

## Not claimed
This is an expiry fence only. It does not make grant status or revocation atomic across the lineage: mixed snapshots,
revocation racing the reads and the composite protocol remain future work. Not full M11/WP-102. The guarded-root
composition is unchanged.

## Design-intake feedback (for the next step)
- A monotone-read or time-budget argument does not satisfy the agreed post-blocking-current-fence requirement. A state
  coherent at the earliest read is not current after later blocking I/O, and status, lease and policy can undergo ABA.
  Positive time budgets cannot stand in for revalidation.
- `NO_RESERVED_BUDGET = 0` must never become positive permission because a policy ignores it. A missing reservation or
  approved model source must refuse.
- `AgentTaskRecord`'s legacy thread formula and the missing call-to-task/lease epoch mapping are real production
  prerequisites. Do not substitute a fake `LeaseFence` or a default mapping.
- Producer contracts and per-run minted control/JDBC bounds are handled separately.
