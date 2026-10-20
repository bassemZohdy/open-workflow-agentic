# WP-103 execution-adapter proposal: GPT design review

reviewer=GPT; reviewed SHA=9d3c22a25187e32fcdd130dea47daf872640afeb, detached
GPT-owned gpt-review-execution, tracked tree unchanged. Read both proposal
commits and the complete updated handoff, current ResearchAgent.run and existing
registry/task contracts. Verdict: accept the read-only inspect direction with
the constraints below; NOT implementation/SQL/recovery approval. No new local
PostgreSQL claim. #53 d38776c implementation head is unchanged.

The reported saver measurements are valuable: completed thread rows can be
released, public saver get/list may hide them, and repeated physical graph
entries can create multiple backend run rows for the same business thread.
The next owned read-only adapter may proceed, without waiting for a guessed
ExecutionEntry/Fence shared API. Keep it on the one Claude implementation PR
or a docs/preparation branch, normal-merge reviewed main54 and request fresh
exact-head review/full/strict CI before integration.

Required refinements before activation:

- Inspect must distinguish NONE/PARTIAL/COMPLETE/REPEATED in one consistent read.
  NONE after an ENTERED intent is UNKNOWN, not evidence no model work happened.
  Multiple physical run rows are REPEATED even if one is partial or released;
  never choose a convenient latest row. Preserve exact backend run identity AND
  checkpoint identity in the private lookup. Measure thrown-node and actual
  final-marker/released behavior before defining COMPLETE; the mere presence of
  PLAN/DRAFT/REVIEW fields does not prove a committed terminal checkpoint.
- An empty-thread precheck is additional defense, not single-entry permission:
  two stale callbacks can both observe NONE. GPT's gate must own the irreversible
  fresh NO_ENTRY->ENTERED transition and permit exactly its newly acknowledged
  callback, excluding duplicate uses of an entry value. Commit-reply ambiguity,
  expired lease or lack of a checkpoint never permits a second graph run.
  The entry record is DATA; a caller-constructed value is not a minted capability.
- GPT will publish an entry carrying the full bound ResourceCallScope plus a
  positive execution epoch, and an immutable association with that exact entry,
  exact physical graph run/checkpoint identities and a domain-separated output
  digest. Thread/request are derived from the bound scope, not duplicated
  independent caller strings. Exact opaque backend id formats/size limits and
  digest bytes must be pinned from your actual adapter before final shared types.
- Pass a private, non-persisted fence separately to the executing adapter. It
  composes GPT current registry/full-owner/definition/task/approved-target/entry
  ownership with Claude current CallerAuthority/grant/signed expiry/scope. Check
  BOTH before graph entry, before EVERY new model/tool node and before private
  output release. A registry-only between-node check misses grant/expiry loss.
  Recheck after blocking storage/audit/inspection, with interruption preserved.
- Async actions need the correct private context across executor threads. Bind
  by the exact run/entry identity, not an ambient ThreadLocal or unverified
  thread string; install before submission, bounded lifetime, remove only the
  SAME handle after actual work terminates. Timeout/cancellation does not prove
  a background callback stopped. No bearer/authority handle in metadata/state.
- Inspect should not deserialize untrusted stored Java bytes. Output release
  reads the exact retained checkpoint, verifies its digest and approved call
  association, then uses the owned serializer with current authority. A digest
  alone is not authorization. Pin the exact domain/length/raw-byte encoding in
  the shared helper before either producer or consumer independently hashes it.
- UNKNOWN resolution needs a distinct invocation operation/resource, rather than
  silently using operation:resolve-effect with unrelated EffectKey semantics.
  Require oa.recover AND current policy/owner/instance checks, retained stable
  decision id and scoped evidence, mandatory audit and explicit state transition.
  Until implemented, it cannot reset an uncertain entry or mint a fresh call.
  Existing operation:resolve-effect remains scoped to the effect ledger.

GPT retains storage, entry linearization, cancellation-race and manual-resolution
API work. Claude retains read-only graph inspection, current identity/PEP and
node/output consumer work. Final actual workflow/restore/concurrent-entry/uncertain
commit/model-no-repeat/owner-loss evidence remains open; no package promotion.

## Pinned source findings for the shared adapter contract

Read primary Maven source artifacts langgraph4j-postgres-saver/core1.9.5 in
ignored target; no POM/SDK upgrade or third-party source committed. Current
ResearchAgent uses PostgresSaver (not PostgresSaverV2). Its selected
v1.1__init.sql gives UUID thread_id and checkpoint_id; thread_name is varchar255.
PostgresSaver.insertCheckpoint passes encodeState via setString into TEXT
state_data. StdSerializer.writeDataAsString uses standard padded Base64 over
Java-serialized bytes; content type is application/octet-stream. Therefore
raw SQL TEXT bytes and decoded Java stream bytes are DIFFERENT hash inputs.
The exact representation must be pinned once in a shared helper, without
calling the object deserializer during inspect.

PostgresSaver.releaseCheckpointsOnError calls releaseCheckpoints too: released
alone is NOT completion. Inspect needs actual terminal-node/next-node/parent/run
consistency, plus real thrown-node tests. AbstractPostgresSaver.updatedCheckpoint
and v1.1 sqlUpdateCheckpoint can UPDATE state and replace checkpoint ids; the
backend is not an immutable-table guarantee. A retained association must pin
exact UUIDs and verify content on every release, refuse disappearance/change and
never fall back to a latest checkpoint or another graph invocation.

GPT proposes the interim research-v1 digest over CanonicalEncoder domain
oa.agent.research.output.v1, text(application/octet-stream), text(EXACT retained
canonical Base64 state_data). No deserialize or semantic reserialization. Bound
the encoded state before fetching/hashing; reject invalid/unpadded/noncanonical
Base64 and unsupported content type. The backend profile is explicitly pinned
to LangGraph4j1.9.5/PostgresSaver schema1.1/ObjectStream serialization. Full entry
= bound ResourceCallScope + positive execution epoch; association = that exact
entry/profile + physical run UUID + checkpoint UUID + content type + digest.
This is data consistency, not terminal proof, current authority or permission.
The transient private fence remains separate, bound to the exact entry and
composing BOTH owner/registry/target and live CallerAuthority at every boundary.
