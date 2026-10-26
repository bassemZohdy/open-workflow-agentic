# WP-108: V76 entry backend and governed dispatch — implementation intake

**Prepared:** 2026-10-26  
**Producer/writer:** GPT (lifecycle, contracts, SQL and integration); **peer reviewer:** Claude  
**Current base:** `15bcdc8026fe03f14122378e27d994f540cf08be`  
**Implementation state:** **NOT STARTED**. This document stages the next reviewed slice; it is not code, passing tests, an authority grant, or package acceptance.

## 1. Prerequisite and authoritative evidence

- PR [#73](https://github.com/bassemZohdy/open-workflow-agentic/pull/73) is merged: the verified identity path mints an opaque one-call admission witness. This witness alone never starts a graph.
- PR [#74](https://github.com/bassemZohdy/open-workflow-agentic/pull/74) is the sole open implementation PR, providing **V76 immutable data only**. Current head `3f37b3ba2a37981f2a99ad497227dab198719531`: full CI run `40265793674` passed 175 suites / 1262 cases, zero failures/errors/skips. **Claude exact-head review is still pending. No merge or backend activation until that review passes.**
- V73/V74 call/binding/occurrence storage, V75 central audit source receipts, V75.1 checkpoint profile ownership and startup verifier have already merged. Their existence does not authorize execution or private output release.
- Intermittent packaged-process identity recovery remains unresolved: [issue #75](https://github.com/bassemZohdy/open-workflow-agentic/issues/75).
- **Production identity-profile decision remains open:** Keycloak 26.8.1 actor-bearing delegation is a documented **preview** feature, while fully supported standard token exchange does not by itself establish the same `act` chain. Confirm the standard-only production requirement or explicitly accept the preview profile before claiming enterprise readiness: [issue #76](https://github.com/bassemZohdy/open-workflow-agentic/issues/76), [Keycloak official release notes](https://www.keycloak.org/docs/26.8.1/release_notes/).

## 2. Invariant and non-goals

**One original canonical resource call -> at most one irreversible entry -> at most one live graph dispatch.** The logical call key is stable across fresh credentials, retry attempts, different owners, restart epochs and a lost database reply. A unique row and an empty outcome are *not* proof a graph did or did not run.

This next slice must **not**:
- wire production legacy `ResourceInvocations` admission through the historical JDBC record;
- turn a newly constructed `ResourceExecutionEntry`, a stored admission witness, or the presence/absence of an outcome into a capability;
- persist bearer tokens, raw model output, request payloads or a Java authority object;
- infer a terminal run from the latest checkpoint or silently fall back to a different saver profile;
- re-attempt a graph for `ENTERED` or an unresolved/unknown commit without an authoritative same-call reconciliation decision.

## 3. Implementation order and write ownership

1. **DATA backend (GPT, after #74 merge):** introduce a narrowly scoped JDBC operation against `task.resource_execution`, using a short owned transaction, no network I/O under its DB lock, and the exact original V74 occurrence. Surface **retained fact / definitely new committed row / indeterminate reply** separately; API return types must not be publicly convertible to graph-run permission. Conflict with another epoch/binding/target must not allocate a new logical call.
2. **Live authority (Claude-owned caller/identity/PEP):** provide the exact producer hook for a verified signed caller, immutable full owner/tenant/target and task/lease/registry snapshot; recheck signed expiration, grant chain/revocation, requested scopes and PDP decision after every blocking boundary. Missing or expired authority is denied. GPT requests this through the existing cross-area handoff rather than editing identity/policy code.
3. **Dispatch bridge (GPT lifecycle, Claude agent):** a private non-serializable, single-call context binds the verified witness, server-trusted pinned workload target, original V74 call, executing task/owner lease and mandatory central audit receipt. Only the *newly acknowledged* committed-entry callback, and only after a fresh post-commit live fence, may invoke a graph. No ambient ThreadLocal, mutable workflow input or agent checkpoint stores the context. Enforce queue/worker bounds, cancellation and close-on-termination.
4. **Terminal outcome (GPT association, Claude read-only inspector):** check exact physical run and checkpoint UUIDs, pinned serializer/profile, terminal structure and unchanged stored representation in one consistent read; then associate an immutable V76 outcome. Recheck current authority before **any** private output release. Persist historical facts after cancellation but withhold output.
5. **Reconciliation (GPT lifecycle):** after an unknown database commit reply or process death, read same original call and its audit/physical checkpoint evidence. Never call a fresh graph merely because the caller did not receive a reply. Unsupported or uncertain cases move to explicit recovery/manual investigation; no automatic second entry.

## 4. Deterministic acceptance matrix for the first backend/dispatch PR

| Failure injection or race | Required assertion |
|---|---|
| Entry transaction fails before commit | Zero graph calls; no authority inferred |
| Commit succeeds but reply is lost | Original call has one entry; caller gets UNKNOWN/refusal, **zero second dispatch** |
| Two workers concurrently submit the same original call | One database entry and at most one acknowledged callback; loser cannot run |
| New credential or incremented resume epoch | Same original call key; no second entry/callback |
| Registry/owner/lease/task changes while database I/O blocks | Post-wait fence denies; no graph or output release |
| Grant expires or is revoked during policy/receipt work | Post-work verified-caller/PDP fence denies |
| Audit append fails or acknowledgement is unknown | No graph callback; preserve committed immutable evidence |
| Server-trusted workload/approved target binding missing or mismatched | Deny even with otherwise valid token/witness |
| Context absent, already consumed, cancelled or outlives worker | Deny, do not resurrect, no credential serialisation |
| Empty/missing/drifted/foreign checkpoint profile | Deny; never infer a fresh call or release stored private output |
| Outcome association races with cancellation | Retain historical immutable outcome; deny current private release |
| JVM/process kill after graph target effect before acknowledgement | Same effect idempotency key and manual UNKNOWN pathway, no duplicated business effect |

Every test must use real PostgreSQL locks/faults where appropriate and the **actual** Flow/agent SDK path for dispatch claims. Mutation/negative controls must demonstrate that removing the intended guard fails at the intended assertion; a child FK or an unrelated denial is not a sufficient proof.

## 5. Evidence, merge and reporting rules

- Keep one **implementation PR** open at a time. Do not add unrelated staging changes to #74 or claim its already inspected CI for a new commit.
- Use source-controlled reproducible test commands, actual XML and provider/process evidence, zero skipped intended cases, and the exact tested tree/commit. Run core and strict real-Keycloak profiles for cross-boundary changes.
- Claude must independently review the **exact final implementation head** and record PASS or material findings. Passing author CI cannot replace that review.
- WP-108 remains **partial** until actual governed Flow/effect/manual integration and crash/lost-ack behavior satisfy its owned acceptance gates. T03's existing component-level automation is not whole-package completion. WP-102 remains partial for the live asynchronous Flow identity/authority propagation path.
- Refresh `docs/plan/work-packages.json` + generated TODO/traceability only after merged, evidenced implementation. This preparatory document alone does not change status.

## 6. Related references

- [Integration queue](INTEGRATION-NEXT.md)
- [V76 schema intake](WP-108-v76-facts.md) (on PR #74 until merged)
- [Execution-entry contracts](WP-108-execution-contract-integration.md)
- [Identity and caller restrictions](../../identity.md)
- [Effect/unknown-outcome contract](WP-108.md)
