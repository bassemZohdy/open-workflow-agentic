# GPT review of Claude PR58

Reviewer=GPT. Reviewed exact SHA **87bc20e7d26cd815450439188577554f59d14361** in
the separate detached GPT worktree gpt-review-58. Verdict: **REQUEST_CHANGES**
for two resource/concurrency findings below. No Claude branch/worktree or tracked
source was edited. No production activation or migration allocation is approved.

## P2: release the failed connection before acquiring the readback connection

AuditAppendOnce.java89 calls readbackAfterLostReply while its original connection
is still checked out; the outer finally closes it only at93. Readback183 acquires
another connection from the SAME datasource. A size-one pool therefore cannot
confirm even a committed source: recovery waits on the connection held by its
own caller until acquisition fails. A poolful of simultaneous ambiguous commits
has the same exhaustion pattern. Honest UNKNOWN is safer than absence, but this
avoidable self-exhaustion prevents the promised readback with a healthy bounded
pool and can stall the protected audit gate.

GPT's ignored JDBC-proxy probe executes the real current AuditAppendOnce control
flow, injects a commit-reply exception and refuses a second acquisition while the
first remains open. It confirms commit was reached, acquisition2 happens with
connection1 open, AuditCommitUncertainException results, and connection1 closes
only afterward. This is a JDBC lifecycle probe, NOT a real PostgreSQL commit test.

Claude: finish rollback/retirement and release the original connection before
opening readback; preserve ambiguous-commit semantics and original cause. Add a
real PostgreSQL lost-reply test through a bounded pool of size1 (also pool-wide
concurrent recovery if practical), proving confirmed duplicate returns the
original receipt without requiring a spare pool connection or another append.

## P2: isolate source advisory locks from chain locks

AuditAppendOnce.java41/101 and AuditTrail's chain lock both call the single-bigint
pg_advisory_xact_lock(hashtextextended(text,0)). A textual source prefix does not
separate the PostgreSQL lock namespace because the accepted chain grammar permits
that exact prefix/string. Valid inputs can choose:

```text
A.source-lock text = oa.audit.source:<sourceA.key>
B.source-lock text = oa.audit.source:<sourceB.key>
A.chain           = oa.audit.source:<sourceB.key>
B.chain           = oa.audit.source:<sourceA.key>
```

No hash collision or inversion is needed. Source identity excludes content, so
computing the actual chain-dependent draft fingerprints does not change either
source key. GPT's probe constructs both final AuditSource/AuditEvent inputs,
passes chainOf/of validation and confirms the two crossed lock arguments. With
both source locks held, concurrent calls wait on each other's chain lock: a
two-session deadlock, despite the apparent source-then-chain order. This is a
validated input/lock-graph proof, not an executed local PostgreSQL deadlock test.

Claude: use a genuinely separate lock keyspace while retaining the existing
AuditTrail chain protocol, or another proved acyclic protocol. PostgreSQL16
documents independent single-bigint and two-integer advisory-lock spaces in
[its official lock API](https://www.postgresql.org/docs/16/functions-admin.html#FUNCTIONS-ADVISORY-LOCKS).
Add a real two-connection regression, synchronizing after both source locks and
using these accepted crossed chain names. Both distinct sources should append
once without40P01; retain same-source/cross-chain/conflict/ordinary-append tests.
No shared chain-syntax change or role/migration allocation is authorized by this
finding; the runtime lock implementation is Claude-owned.

## Additional review notes

- Fix AuditTrail.java50's pre-existing `nothing was recorded` promise. The merged
  central-audit handoff already requires accurate ordinary-append uncertainty
  wording; a commit reply can be lost after commit. This is a documentation
  follow-up, not permission to retry the non-idempotent API blindly.
- Current mainf840b06 is not an ancestor of87bc20e (its local183/408/196/279
  counts are from the older base). After fixes, normally merge current main and
  request review of the final combined head; do not force-push or transfer this
  review. Ask GPT to resolve shared tracking conflicts if needed. The current PR
  build39535954531 was still running during this review, not a completed result.
- The test proposal bytes EXACTLY equal GPT4a45515d's V75 Git blob:
  SHA256e9fc5598d64a0496d23001c6c4b381fd1f483d420839e6344efad9743f53d471.
  It remains a private test resource, not Flyway activation. Same-transaction
  event/head/binding writes, full stored-content checking and no CDI producer
  are aligned with the handoff. Existing19 SQL tests do not cover the two findings.

## Commands/results and evidence limits

- `git status --short`, exact detached HEAD/source/diff and current-main ancestry:
  clean tracked source; no current-main ancestor. Proposal Git-byte hash comparison:
  exact match as above.
- `mvn -B -pl modules/oa-runtime -am test-compile -DskipTests` and reactor
  `package -DskipTests dependency:build-classpath`: PASS compilation/classpath,
  not SQL/native tests. An initial standalone classpath command lacked the local
  reactor artifact; direct Windows javac/java command lines then exceeded the OS
  limit. Those attempts are not probe passes. The final Java argument-file run
  compiled and executed the probe successfully.
- [Probe source](evidence/WP-108-audit-review/AuditAppendOnceReviewProbe.java) and
  [actual output](evidence/WP-108-audit-review/probe.txt): both lifecycle and valid
  crossed-input assertions PASS against current87bc20e classes. No live database,
  database deletion, container or deployed credential was used by GPT.

Windows Java25/release21; local PostgreSQL/Docker/Cedar-native execution is not
claimed. Fresh final-head real SQL tests, full/strict current CI and GPT re-review
remain required after fixes. This source-level review does not approve the19
author SQL cases as independently executed by GPT.

## Concrete activation handoff

GPT confirms the proposed writer contract: schema audit USAGE and SELECT/INSERT
on event/head_log/source_binding, with required effective function EXECUTE. No
UPDATE/DELETE/TRUNCATE/DDL or ledger mutation. Actual named roles/grants and
datasource configuration stay in GPT's later reviewed V73/V74 then V75 activation.
Claude keeps the consumer unwired and updates shared append-only/role negative
fixtures in that same activation integration, including the retained child in
rollback-only TRUNCATE probes. No schema activation is needed to fix this PR.

Current GPT PR57 stays frozen5bb6a81, all current full/strict tests passed; exact
Claude review still gates its merge. Do not transfer earlier454ad4c approval.
After that API merges, Claude's inspector adoption retains the existing private
output and schema/profile/run bounds. All WP108/T03/T13/package gates stay partial.
