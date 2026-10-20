# WP-103: actual occurrence persistence boundary (preparation)

reviewer=GPT; preparation branch gpt/WP-103-resource-binding-store. No second
implementation PR; #54 remains the sole GPT PR. No acceptance/package promotion.

The pinned serverlessworkflow-persistence-api7.35.3.Final AbstractPersistenceInstanceWriter
returns an already-completed future for taskStarted WITHOUT persisting a task.
The quarkus-flow-jpa1.2.0 JpaInstanceWriter uses actual transactions for completed/
retried task snapshots. Therefore CheckpointingWriter/PersistenceCommitGate
TASK_STARTED success is NOT a durable pre-dispatch occurrence reservation. The
existing inbox completion proof is not replaced or extended by a start callback.

PersistenceOccurrenceBoundaryTest uses the actual SDK, injected JPA writer,
CheckpointingWriter/gate and PostgreSQL storage through a fresh transaction. It
adds a non-secret bounded string marker at a task start. The negative control
expects a successful start observation/in-memory marker with NO task row/marker
in initial workflow metadata. The positive control first completes a set task,
then waits at a listen; its actual stored CompletedTaskInfo retains that marker.
Normal completion and persisted cleanup are awaited before owned application
shutdown. An explicit borrowed-writer proxy prevents closing shared runtime
services. The broker is test-owned in memory; this is actual SQL retrieval, NOT
a separate-process restart, production transport or complete occurrence producer.

Local: mvn -B -pl apps/app-runtime -am test -DskipTests BUILD SUCCESS, all application
test sources compile. Both complete workflow fixtures validate against bundled
OWS1.0.3 schema. The new two SQL cases require fresh Linux execution; earlier
BOTH39393353409 proves exactb051776's17 binding SQL cases, not these additions.

Pinned-source inspection also shows TaskMetadata snapshots durable instance
metadata unless the VALUE CLASS has MetaTransient. Never put bearer tokens,
credentials or private authority handles in this metadata. A plain bounded
coordinate reference is data only. WorkflowPersistenceInstance seeds counters
from completed/retried snapshots; neither volatile parent counters nor a start
notification establishes a trusted complete business occurrence across restore.
A producer still needs its own durable logical-entry reservation, genuine
leaf/enclosing-loop/goto coordinates, actual completion checkpoint association,
restore repair, current full-owner/registry/target/execution/authority and
mandatory audit gates. No outbound call or binding activation is implemented.

Sources were read from the pinned Maven source artifacts in ignored target;
no POM change, SDK upgrade or third-party source copy is committed. V74 guards
already retained complete occurrences; it does not derive their coordinates.
V75 remains reserved for Claude's audit source-binding DDL proposal.
