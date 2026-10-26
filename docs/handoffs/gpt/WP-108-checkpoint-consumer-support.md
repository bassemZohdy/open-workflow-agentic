# WP-108: current-profile consumer support

This prerequisite extracts 15 existing malformed-profile SQL mutations into
`apps/app-runtime/src/test/resources/checkpoint-profile/mutations.json`.
Their identifiers and decoded SQL strings match the merged lifecycle test exactly.
The migration test still executes its same ten methods and 24 observations. The
nine remaining observations use special fixture setup or positive/locking/saver
behavior; the JSON does not claim to replace them. Applied migrations are unchanged.

Claude may consume the JSON from its app-runtime agent tests. Apply these synthetic
mutations only in owned private fixtures, never as runtime validation or repair.
There is no new production API or module dependency.

## Consumer boundaries agreed by GPT

- The existing `agentic.agent.checkpoint-schema=public` is the intended contract;
  only public is supported. Preserve the existing configured data source and both
  Flyway settings (`migrate-at-start=true`, existing `baseline-on-migrate=true`).
  Do not call Flyway or execute migration/creation/adoption SQL from ResearchAgent.
- Actual current-profile checks belong at startup after migration, at graph build,
  and before graph/provider work on every run. A lazy PostConstruct alone does not
  satisfy startup verification. Claude owns its agent StartupEvent observer if
  needed; GPT will independently prove real migration-first boot order in the
  app lifecycle suite after the owned implementation is available.
- Verify the actual configured namespace and unqualified saver resolution, not
  history rows alone. Reuse a single verifier in the inspector without introducing
  nested connections or releasing its coherent read transaction before checking.
- Read-only ACCESS SHARE NOWAIT can exclude concurrent ACCESS EXCLUSIVE DDL during
  the check, but does not conflict with an ordinary ROW EXCLUSIVE writer. Test
  exclusive-lock refusal and concurrent-writer compatibility separately. The V75.1
  adoption writer-refusal rule must not be incorrectly transferred to runtime reads.
- A check is bounded to its transaction/connection. It cannot certify all pooled
  connection search paths or prevent later administrator DDL; do not claim that.
  No checkpoint row/state decoding, repair, fallback or invented empty store.
- Keep the default legacy admission port unsatisfied. Profile compatibility is not
  current caller authority, an execution acknowledgement, or permission to retry.
  Credentials/role adoption and the actual V76 backend/current fence remain gated.
- Claude may add an owned test-local private database helper. Do not add an
  oa-testkit dependency or copy application tests into runtime production sources.

Recovery run40246336207 remains an unresolved intermittent defect. Diagnostics68/69
are merged; the bounded twenty-run Linux investigation is independently collecting
logs. This fixture extraction neither fixes nor weakens that test, and does not
activate the runtime consumer or promote any package/acceptance status.
