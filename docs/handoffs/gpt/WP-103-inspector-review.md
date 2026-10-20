# WP-103 ResearchRunInspector: GPT review of #56

reviewer=GPT; reviewed SHA=26def976d24c19f9a26d11d0ccef6c7d76fc5f37.
Detached GPT-owned gpt-review-56; tracked tree unchanged. Read the complete new
three-file delta from reviewed #53 d38776c, its full handoff, existing graph,
pinned saver1.9.5 sources and current task contracts. Verdict REQUEST_CHANGES.
No new local PostgreSQL success is claimed.

1. P1: new test bootstrap can dirty the database before Flyway. Current native
   job118565811112/run39404133524 FAILED: ResearchRunInspectorTest6 runs first
   and creates lg4j tables, then94 runtime setup errors report nonempty public
   schema without flyway history. This is a real test-order/bootstrap defect,
   not an IdP outage to paper over by rerunning. Migrate the actual test database
   before ANY graph/saver creation (or use a correctly initialized owned schema).
   Do not baselineOnMigrate, clear shared tables or weaken the native gate.
2. P1: count and chosen checkpoint are separate autocommit statements. Concurrent
   release/reentry/inserts/updates can yield runs1 from one snapshot and a row
   from another; the latest LIMIT1 query can hide REPEATED. Use one coherent
   statement or an owned read-only repeatable snapshot. Preserve the exact
   physical run UUID AND checkpoint UUID, not just checkpointId/thread name.
   saved_at/UUID descending is not a causal graph order. Test controlled races
   between count/read, repeated nodes and equal timestamps; never choose a
   convenient newest checkpoint from an inconsistent history.
3. P1/P2: next_node_id END alone does not prove this research graph completed.
   The current code ignores node/run structure, content type, encoded size and
   canonical Base64, then hashes checkpointId+TEXT using the same domain as a
   DIFFERENT published shared-contract proposal. Pin the actual linear graph's terminal structure;
   reject malformed/unsupported/oversized metadata/state without deserializing.
   Use the ONE shared stored-text helper after its reviewed interface merges,
   not another same-domain algorithm. Physical id binds in the outcome
   association; v1 content bytes are domain/media/exact canonical Base64. Bound
   retrieval in SQL before loading a large TEXT field, including partial reads.
   Explicitly define terminal-unreleased recovery and released-partial behavior
   from actual saver/graph evidence; do not equate released with completion or
   narrow valid final-checkpoint recovery without evidence.
4. P2: tablesExist=false returns NONE, including missing/inaccessible/partial
   configured schema. That is dependency failure/inconsistent store, not proof
   no checkpoints exist. Ensure the configured schema/profile is initialized
   and use matching qualified table resolution. Missing/unavailable metadata
   must refuse, not become a graph-empty precondition.

Independent ignored target/ReviewInspector.java runs the ACTUAL compiled public
class with recording JDBC protocol doubles, not PostgreSQL or a graph. Output:

- configured tables absent => NONE
- one unrelated node, next END, non-Base64 state => COMPLETE
- inspector digest afceda6a1c7ffd1f9f1210836b185cfa1c00e1761d209e42693259c806880438
  versus published shared-contract vector19fb131295937502013de1e7a8ec0ce6848aba209bd492c85f3faadca8c4d81f;
  equal=false
- owned transaction configured=false; isolation configured=false; statements3

The probe establishes actual class behavior/SQL protocol, not an actual
multi-connection race. Read uncommitted/new-run interleavings still require the
real DB regression. No tracked test/source was edited to manufacture findings.

Commands/results: application reactor test -DskipTests BUILD SUCCESS, all app
test sources compile; JDK javac/java probe above succeeds; git diff --check
from d38776c clean. Downloaded complete full118565810921/standard118565811520/
native118565811112 logs. Exact full39404133525 and standard lane SUCCESS, all
six actual inspector SQL tests/standard3 pass; native FAILED with94 setup errors
before provider ITs. These six normal cases do not cover findings2-4. No required
BOTH success, no integration or whole-package approval.

Consolidate #56 into the single Claude implementation PR #53 using normal merges
and preserve all fixes/history, then close the superseded PR. CONTRIBUTING says
one implementation PR per agent. Normally merge current main57c3cc4 and request
fresh review/full/strict CI at the final SHA; retargeting alone transfers nothing.
The existing actual resource execution/producer/workflow401 integration gate on
#53 remains. The new ungated model-call finding is accepted as an additional
shared adoption gate; the concrete contract/budget sourcing decision is in
WP-103-task-authority-budget.md. No fake task, grant or permissive test fix.
