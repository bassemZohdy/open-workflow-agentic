# WP-103: what a failing research node leaves (ResearchRunInspector)

Claude-owned. Updated for the findings recorded on #53/#56 (inspector). Measured with the real graph, a scripted model and
the real checkpoint tables (`ResearchRunInspectorTest`, 24 tests, real PostgreSQL 16, Flyway first).

## Semantics

- Read-only, one snapshot: `REPEATABLE READ`, read-only transaction, query timeout, connection state restored. Nothing here
  runs the graph, calls a model or deserializes state. Mutation check: weakening the isolation to `READ_COMMITTED` fails the
  two race tests (a second run inserted between the count and the read; a state `UPDATE` before the state read).
- Schema first: `to_regclass` plus exact column types of `lg4jthread` / `lg4jcheckpoint`. A missing, partial or wrongly typed
  schema is an `IllegalStateException`, never `NONE`.
- Kinds: `NONE` (no thread row), `PARTIAL` (one thread, chain not finished), `COMPLETE` (one thread, exactly the four
  transitions `__START__ -> plan -> draft -> review -> __END__`), `REPEATED` (more than one thread row), `INCONSISTENT`
  (anything that is not that chain, with a reason code: `too-many-checkpoints`, `checkpoint-outside-graph`,
  `repeated-transition`, `gap-in-graph-order`, `released-before-end`, `unsupported-content-type`,
  `state-size-out-of-bounds`, `state-not-canonical-base64`, `state-not-java-stream`).
- Structure is judged by position in the graph, not by `saved_at` or checkpoint id (a test inserts equal timestamps and ids
  that sort against the graph order). Physical ids (`runId`, `checkpointId`) are returned as stored.
- `COMPLETE` with `released=false` is "terminal-unreleased": the graph finished but the release never committed. The
  terminal state text is returned exactly as stored (bounded, canonical Base64, `ACED0005` header checked, never read as an
  object).
- Measured: re-entering an unreleased finished thread replays three model calls and adds eight checkpoints; the inspector
  then reports `INCONSISTENT` / `repeated-transition`. A consumer must therefore not "run again to finish".
- A node that throws leaves its thread open with the checkpoint written before that node (failure in `plan`: one checkpoint;
  in `draft`: two). A refused model call (see WP-103-model-call-consumer) behaves the same way.

## Blockers and requests (GPT-owned, not done here)

1. **Shared fingerprint.** `ResearchOutputFingerprint` (GPT) is not on main. The inspector returns the stored terminal state;
   it computes no digest. Once the shared helper is merged and reviewed I will call it on exactly those stored bytes. I will
   not add a second digest definition.
2. **Table ownership.** The `lg4j*` tables are created by `PostgresSaver.createTables(true)` at `ResearchAgent` startup, so
   they are outside Flyway. Request: a Flyway migration that owns them (same names and column types the inspector checks),
   then drop `createTables(true)`. Until then tests migrate with Flyway first and only then build a graph; the app's
   `baseline-on-migrate=true` stays as is.
3. **Manual resolution.** `PARTIAL` and `INCONSISTENT` stay UNKNOWN until the manual-resolution operation GPT defines.

## Shared fingerprint adopted (after #57 merged as 6b4cf0d)

`StoredState.fingerprint()` is `ResearchOutputFingerprint.of(contentType, base64)` over the exact stored text; no second
hash exists in the runtime. The 1 MiB pre-read, header/profile, physical-run and current-authorization guards are
unchanged and `createTables(true)` is kept until the reviewed checkpoint migration. A digest is data, not completion
or permission. Test `theCompletedRunsFingerprintIsTheSharedHelperOverTheExactStoredTextAndOnlyThat` runs a real
completed graph, reads the stored column independently by SQL and compares. Current totals: ResearchRunInspectorTest 26,
AuditAppendOnceTest 22, oa-runtime 425/425 (local PostgreSQL 16).
