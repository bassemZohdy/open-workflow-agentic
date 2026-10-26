# WP-108: bounded checkpoint consumer (Claude owner report)

Base: main `79da38e` (PR70). This is an independent increment. It claims no recovery fix and no merge readiness:
GPT must still review all evidence, and run `40246336207` stays unresolved.

## What changed

- `ResearchAgent` no longer calls `createTables(true)`; the saver is built with `createTables(false)`. It never calls
  Flyway or runs creation, adoption, repair or fallback SQL.
- New `CheckpointProfileVerifier` (read-only, public only) mirrors the V75.1 catalog invariants against the live catalog:
  namespace and unqualified resolution, relation kind/persistence/row security/rules, triggers, inheritance, extra
  constraints, exact columns (type, nullability, default, generated, identity, collation), keys (including the FK
  action/match), the three named indexes (access method, key order, sort options, predicate, operator classes,
  collation, nulls-not-distinct) and the unique-index counts. It does not read Flyway history and does not select
  any checkpoint row or state.
- New `CheckpointProfileException` carries a fixed `Reason` code only (`unavailable`, `namespace`, `missing`,
  `shadowed`, `locked`, `relation`, `unsupported-object`, `columns`, `keys`, `indexes`, `uniqueness`). It is an
  `IllegalStateException`, so existing inspector refusals keep their type.
- Verification points: (1) `@Observes StartupEvent` on `ResearchAgent` (a lazy `@ApplicationScoped` `@PostConstruct`
  alone would not run at boot); (2) `build()`, before the saver exists; (3) `run()`, before any node or provider call.
- `ResearchRunInspector` calls the same verifier inside its existing single REPEATABLE READ read-only transaction (no
  second connection); the old private `requireSchema` is removed. Snapshot tests are unchanged and pass.
- `AgentResource` maps `CheckpointProfileException` to 503 with `the checkpoint store is not ready (<code>); the agent was
  not run`. No SQL, catalog value or database message is shown. The default legacy admission, DS/credentials and
  authority/producer/backend gates are untouched.

## Lock semantics

The first statement of the check is `LOCK TABLE public.lg4jthread, public.lg4jcheckpoint IN ACCESS SHARE MODE NOWAIT`
(before any snapshot). It does not conflict with ordinary ROW EXCLUSIVE writers (tested, both with a pending writer and
with a writer committing while the check's lock is held) and does conflict with ACCESS EXCLUSIVE DDL, which is refused
at once as `locked` (tested, under 3 seconds) and also with an `ALTER TABLE` made to wait (55P03 by `lock_timeout`).
The V75.1 writer-refusal rule is not carried over.

## Tests (all on private PG16 databases; Windows, Java 21.0.12.1, Maven 3.9.11)

- `apps/app-runtime/.../CheckpointProfileConsumerTest` (new, plain JUnit, 26 observations): all 15 merged
  `mutations.json` entries applied after a successful 75.1 migration, each with an exact expected reason, with the
  Flyway history asserted still successful (current-history drift), and the inspector refusing the same store;
  special controls for namespace (`shadow,public`, absent schema, dropped `public`), temp-relation shadowing, partial
  store, inheritance (child and parent), rules, unlogged tables, garbage rows never decoded, history deleted but profile
  intact (accepted), statement recording (lock first, one connection, no DML/DDL, no `state_data`), ROW EXCLUSIVE writer,
  held-lock writer/DDL, ACCESS EXCLUSIVE refusal, and the exact reader and writer roles from
  `deploy/sql/research-checkpoint-grants.sql` (a role with no table access gets only `unavailable`).
- `modules/oa-runtime/.../ResearchAgentCheckpointProfileTest` (new, 7): unmigrated and partial stores are refused and
  nothing is created; positive run; drift after build refused before any provider call and no run entered; startup
  observer; inspector refusal; search path without `public`.
- `AgentResourceAuthorityTest` +1 (503 with only the fixed code, zero graph runs). `AgentTestDatabase` gains a private
  database helper (`privateDatabase`); no oa-testkit dependency. `ResearchRunInspectorTest` no longer relies on the
  saver creating tables (the existing 25 tests are otherwise unchanged and pass).
- Results: `CheckpointProfileConsumerTest` 26/26; `ResearchAgentCheckpointProfileTest` 7/7; `ResearchRunInspectorTest`
  25/25; `AgentResource*Test` all pass. Whole `oa-runtime` plain run: 454 run, 42 errors, all Windows-environment:
  39 Cedar native (`CedarPolicyDecisionPoint` init), 1 Cedar `UnsatisfiedLinkError`, 2 symlink-privilege
  `FileSystemException` in `FileSecretProviderTest`. No failure involves this change.

## Not run / not claimed

- No Quarkus (`@QuarkusTest`) or process tests, nativeCedar or full build on Windows. Existing app tests that boot the
  application (for example `AgentResourceCallerTest`) now rely on a migrated database at startup; they are unrun here.
- Real boot ordering (Flyway before the `StartupEvent` observer) is expected from Quarkus but not proven by me; GPT's
  packaged-process migration-first and current-history refusal proof owns that.

## Material risks

1. A drifted store now stops application start (the observer throws). That is the intended fail-closed behavior but
   changes startup for any deployment whose public checkpoint tables are not V75.1-compatible; GPT should confirm it
   prefers failing start over a not-ready flag.
2. Verification is bounded to its own transaction and connection. It cannot certify other pooled connections' search
   paths or stop later administrator DDL, and the check-to-run gap is not closed (the lock is released before the graph
   runs). `run()` verifies, then `AgentResource.current()` has already judged authority just before.
3. Each run adds one short read-only round trip (a lock plus six catalog queries).
4. The expected-reason table in the test must be extended deliberately when `mutations.json` gains an entry (the test
   fails otherwise).
5. The query checks need PostgreSQL 15 or later (`indnullsnotdistinct`); the pinned database is 16.

