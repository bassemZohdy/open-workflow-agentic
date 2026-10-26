# WP-103 audit API output freshness (Claude)

Branch `claude/WP-103-audit-output-freshness` from merged main `5f5a229`. Slice: signed-identity recheck AFTER the blocking
`AuditQuery` / `ChainVerifier` work and immediately before private output leaves `AuditResource`.

## Gap

`AuditResource` rechecked the signed identity after policy, audit and parsing, BEFORE query or verification. Nothing
protected the output if the token expired while the query or verification ran, so a page, an event, an existence signal
(404) or a verification result left after expiry.

## Change (production, `AuditResource` only)

`current(who)` (unchanged helper: 401 on expiry or authority loss, 503 when it cannot be decided, dev header seam not
rechecked) is now also called as the LAST step, after DTO materialization/aggregation (`EventPage`, `EventView`
optional, `VerifyResult` are fully built first), immediately before returning the prepared result: events page, single
event (including before the not-found result, so there is no existence signal), and both verification forms. The before-I/O checks,
the policy then audit order, defaults and 401/503 mapping are unchanged. No callbacks, no ThreadLocal.

## Tests (Claude audit reserved path)

`modules/oa-runtime/src/test/.../runtime/audit/AuditResourceOutputFreshnessTest.java` (9 cases). Real resolver, access, PEP,
resource, `AuditQuery`, `ChainVerifier`, `AuditTrail` on real PostgreSQL in a private throwaway database
(`oa_audit_fresh_<32 hex>`; the name pattern is validated, `select current_database()` must equal it BEFORE Flyway
migration and any append, a `created` flag is kept, and only a database this test actually created is dropped, without
FORCE, after its connections are closed; the shared database is never touched or reset). The one double: a `DataSource` wrapper that advances the test clock past the token expiry when a connection is
closed, i.e. after the data was read and before the resource returns.

Cases: events page, one event, unknown event, one-chain verify, all-chains verify each read across expiry (expect 401 and
that the store really was read); valid caller gets the real page/event/verification; missing event still 404 for a valid
caller; already-expired token and bad arguments (400) still open zero connections (before-I/O refusals preserved).

## Commands and evidence

Java 21 `C:\Users\Bassem\AppData\Local\Codex\runtimes\java21\jdk-21.0.12.1+1`, Maven 3.9.11
`C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.11\bin\mvn.cmd`, PostgreSQL on localhost:5432 (default
`DB_URL`/`DB_USER`/`DB_PASSWORD`):

    mvn -B -ntp -pl modules/oa-runtime -am -Dtest=AuditResourceOutputFreshnessTest,AuditResourceFreshnessTest,AuditQueryTest,ChainVerifierTest -Dsurefire.failIfNoSpecifiedTests=false test

- BEFORE (production unchanged, new test present): 30 run, 5 failures. Page, event, one-chain and all-chains verify
  returned 200 and the unknown event returned 404 after expiry (leak); the 4 positive/before-I/O cases passed.
- AFTER: 30 run, 0 failures (new 9, existing AuditResourceFreshnessTest 5, AuditQueryTest 6, ChainVerifierTest 10).
  Re-run after the review corrections: 30 run, 0 failures; AuditVerifiedCallerApiTest 7/7.
- App level: `-pl apps/app-runtime -am -Dtest=AuditVerifiedCallerApiTest,AuditCallerDefaultDenyTest`:
  AuditVerifiedCallerApiTest 7/7 pass. AuditCallerDefaultDenyTest 2 of 3 fail with 500 from
  `UnsatisfiedLinkError ... com.cedarpolicy.value.EntityTypeName.parseEntityTypeName` (Cedar native library does not
  load on this Windows host, raised in policy evaluation before any audit read). Observed loader failure; baseline not
  run, so it is not established that this change is unrelated. Linux CI is required. Known unsupported native tests
  were not rerun here.

## Scope limits and residual gates

- Proves signed expiry only. UNAVAILABLE (503) and revoked reasons come from the delegated recheck, which the resolver never
  issues for this API (delegated tokens are refused there), so they are not reachable through the real resolver; the
  mapping code is unchanged and covered only by the existing before-I/O path.
- Not claimed: fresh PDP decision at output time, emergency/safe-recovery state, tenant filtering of audit rows,
  actual-root/async authority, issue 75, or whole WP-103 (remains partial).
- Data-source wrapper timing is deterministic but artificial: it models a read that outlives the token, not a real slow query.
