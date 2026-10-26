# WP-103 agent run output freshness (Claude)

Branch `claude/WP-103-agent-output-freshness` from merged main `ae57e4e`. Slice: caller authority is rechecked AFTER the
blocking `agent.run` and immediately before the private plan/draft/review leaves `AgentResource.run`.

## Gap

`run` rechecked the caller after policy, audit and parsing, BEFORE `agent.run`. Nothing protected the materialized output if the
signed token expired, or a delegated grant was revoked or its store became unreadable, while the agent ran.

## Change (production, `AgentResource` only)

- The response map (`threadId`, `topic`, `plan`, `draft`, `review`) is built fully first, then `current(who, ...)` runs as the
  last step, then the map is returned. The before-run `current` call, policy then audit then parse order, default admission,
  thread ownership/derivation and the 401 (`WWW-Authenticate: Bearer error="invalid_token"`) / 503 / scope mappings are unchanged.
- `current` takes the consequence text for the 503. Before the run it is unchanged ("... ; the agent was not run"). After the
  run it is neutral: "the authority could not be checked; the output was withheld", because the agent may have run.
  The 401 text ("the caller's authority is no longer valid") was already neutral.
- The development-header seam is not rechecked (as before). No callbacks, ThreadLocal, replay reset or persisted credentials.

## Tests

New `modules/oa-runtime/src/test/.../runtime/identity/AgentResourceOutputFreshnessTest.java` (9 cases). Real: `AgentResource`,
`CallerContextResolver`, `AgentResourceAdmission`, `ResourceTokenGuard`, `DelegationGrants`/`DelegationGrantStore`, `Pep`.
Doubles: token verifier, policy rule, audit sink, agent, in-memory bindings/replay/call bindings, and a data-source wrapper
that fails every connection (unavailable grant store). The test makes its own private database `oa_agent_out_<32 hex>`
(name validated, `select current_database()` checked before Flyway, dropped in `@AfterAll` without FORCE only if it created it).
"During the run" is the agent double moving the clock / revoking the grant before returning: deterministic and artificial.

Cases: user token valid (output returned, 1 run); user expiry DURING run (401, 1 run, no entity, no private text in any
message); user expiry BEFORE run (401, 0 runs); delegated valid (output, single use consumed once, replay 401, still 1 run);
delegated expiry during run (401, 1 run, withheld, not replayable); grant revoked during run (401, 1 run); grant store down
during run (503, exact neutral text, 1 run); grant revoked before run (401, 0 runs); grant store down before run (503 "agent was
not run", 0 runs).

## Commands and evidence

Java 21 `C:\Users\Bassem\AppData\Local\Codex\runtimes\java21\jdk-21.0.12.1+1`, Maven 3.9.11
`C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.11\bin\mvn.cmd`, PostgreSQL localhost:5432. `DB_URL` pointed at a fresh private
common database `oa_wp103_<32 hex>` (created and verified with `select current_database()`, dropped without FORCE after Maven
exited; a listing for `oa_wp103%`/`oa_agent_out%` afterwards showed none). The shared `agentic` database was not used or reset.

    mvn -B -ntp -pl modules/oa-runtime -am -Dtest=<classes> -Dsurefire.failIfNoSpecifiedTests=false test

- BEFORE (production unchanged, new test present; AgentResourceOutputFreshnessTest, AgentResourceUserFreshnessTest,
  AgentResourceAuthorityTest, AgentResourceBindingAdmissionTest): 22 run, 4 failures, all in the new class. User expiry, delegated
  expiry, grant revoked and grant store down during the run each returned 200 with the private output ("Expected
  WebApplicationException to be thrown, but nothing was thrown"). The 5 other new cases and all 13 existing cases passed.
- AFTER (same four classes plus CallerAuthorityTest, LiveIdentityCheckTest): 47 run, 0 failures, BUILD SUCCESS
  (new 9, UserFreshness 3, Authority 4, BindingAdmission 6, CallerAuthority 11, LiveIdentityCheck 14).
- Not run: `AgentResourceCallerTest` / `AgentResourceDelegatedTest` in `apps/app-runtime` (Quarkus app tests; the Cedar native
  library does not load on this Windows host per earlier slices, and those are outside the reserved paths). Linux CI is required.
  No native case was disabled or changed.

## Scope limits and residual gates

- Late refusal may follow real agent and model work. Nothing here claims zero effects after work began, per-node or provider
  fences, `coherentRoot`, or a fresh PDP decision at output time. Only the private output is withheld.
- Proves signed expiry (user and delegated), delegated grant revocation and an unreadable grant store, through the real
  admission and guard. It does not prove a real slow model or real-HTTP behaviour.
- Exceptions thrown by the agent itself (checkpoint profile, model refusal, policy) are mapped as before and carry no output.
- Whole WP-103 remains partial.
