# WP-103 stable resource occurrence: actual SDK evidence

GPT producer investigation, 2026-10-20. Production dispatch/occurrence producer
still missing. These tests activate no credential/network/SQL/graph execution.

SdkResourceOccurrenceTest runs three complete workflows through PINNED SDK
7.35.3.Final reader/application/executors/listeners, not mocked TaskContext
counters. Capture immutable scalar positions/counters and explicit numeric loop
indexes at entry, never input/body/token or live mutable parent references.

1. Try/retry enters the same failing task twice: iteration1->2, retryAttempt0->1,
   same position and fixture logicalOccurrence. Naively copying that live counter
   into ResourceCallScope changes rc1/thread on retry.
2. Genuine two-element foreach: child iteration1->2, retryAttempt0 both times,
   indexes0->1, parent task iteration1 both times. Dropping all coordinates
   collapses distinct business work.
3. Retrying an enclosing try revisits foreach index0; BOTH leaf and for-parent
   counters change1->2, retryAttempt0->1. A completed child set executes again
   before the deterministic failure. Hashing all live parent counters is also
   unsafe. This is in-memory SDK behavior, not a restored JPA conformance proof.

Independent command: mvn -B -pl modules/oa-runtime -am test
-Dtest=SdkResourceOccurrenceTest -Dsurefire.failIfNoSpecifiedTests=false:
BUILD SUCCESS,3 tests, zero failures/errors/skips. Extracted exact Java text
blocks under ignored target/ and ran the open-workflow-dsl bundled
scripts/validate_workflow.py --no-lint on all three: schema valid, zero findings.
No extracted SDK source/probe is committed; no acceptance tag or promotion.

Source: AbstractTaskExecutor.apply restores context then incIteration(position)
on re-entry before execution. TryExecutor re-enters its task list;
DefaultRetryExecutor has separate tryRetryCount. ForExecutor mutates the explicit
at index independently of its task-entry counter. The tests cover interactions.

## Producer/storage correction required before activation

ResourceCallScope is TRUSTED-producer data. Keep existing rc1/ef1/ws1 identifiers;
never copy a new live task-entry counter into a retry invocation. Retain original
accepted attribution and recover that SAME scope/thread for refreshed credentials.
logicalOccurrence must distinguish complete stable business work, including
genuine enclosing/leaf loop or goto occurrences. Retry/resume/worker/jti epochs
are not business coordinates.

Next GPT implementation: a separate domain-separated occurrence lookup excludes
the volatile counter and target/request while covering full trusted tenant/owner/
registry/application/engine/definition/task/complete business occurrence. Bind it
immutably to the first accepted ResourceCallScope/call key. Producer reads that
canonical scope before binding a fresh token; changing a counter cannot create
another mapping. Changed target/request conflicts. A genuine new business
occurrence needs a distinct durable coordinate. Readback is DATA, never current
authority, ownership, execution/replay allowance or a result.

Use a centrally sequenced ADDITIVE migration after V73; do not edit applied/
tested schema or rewrite rc1. Refuse conflicting historical occurrences instead
of choosing a winner/deleting data. SQL guards prevent direct-writer bypass;
readback recomputes fingerprints. Publish a reviewed shared producer lookup,
preserving Claude's issuer+jti admission port. No migration allocated by this
document alone. Full occurrence/restore/execution/result/owner protocol, current
authority/audit/credentials and actual workflow401 remain open; lookup alone
does not close them.

IssuedToken does not currently expose signed iat. Producer must obtain verified
claims through the merged TokenVerifier (transient issued bearer), never infer
iat as expiry-minus300 or wall time. No synthetic timestamp/Claude API edit needed.

## Separate combined CI evidence

Exact6fbb9fbf471d73b4daeca55dc0590cf0ad6db71a passed BOTH39388831952. GPT inspected
finished standard118515146162/native118515146729 logs: interruption6, actual SQL
effects16/group11/binding12, original process1, standard3/native8 and strict
checks pass, zero failures/errors/skips. The three NEW observations are later
and were NOT executed by that run. Final-head full/peer/ordered integration stay
required. #51 exact7cbb4c9 remains sole GPT PR awaiting Claude review.
