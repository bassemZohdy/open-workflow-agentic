# WP-004 Spike B: LangGraph4j durability

**Status:** Closed for the questions below · **Date:** 2026-10-18 · **Version under test:** LangGraph4j 1.9.5 (same line as the root pom)

Code: [spikes/spike-b-langgraph4j](../../spikes/spike-b-langgraph4j) (standalone Maven project, not in the root modules). Decision: [ADR-0020](../adr/0020-checkpoint-scoping-and-state-evolution.md).

## How to read the evidence

- **verified-by-test**: a test in the spike ran it on this machine (Java 21, Postgres 16 on localhost, database `spike_b`). Test names are given.
- **read-in-source**: read in the 1.9.5 source jars (`langgraph4j-core`, `langgraph4j-postgres-saver`, `async-generator`) or the module POMs on Maven Central. Not run.
- **unknown**: not tested and not settled by source reading.

**Docs were not read.** The LangGraph4j documentation pages (bsorrentino.github.io/langgraph4j and the GitHub README) were blocked for WebFetch during this spike, so no claim here rests on the docs. Everything is either a test or a reading of the 1.9.5 source.

**Scope note.** The brief refers to `oa-runtime`. This repository has one Maven module, so the current agent is `src/main/java/.../agent/ResearchAgent.java`. It uses `PostgresSaver` (V1), `ObjectStreamStateSerializer` and a `DataSource`. Findings below apply to that code as it stands.

**Exact versions.** langgraph4j-core and langgraph4j-postgres-saver 1.9.5; async-generator 5.1.0; jackson-databind 2.21.1 (core declares it `provided`, so the spike adds it); PostgreSQL JDBC 42.7.4; HikariCP 5.1.0; JUnit 5.11.4.

## Answers

| # | Question | Answer | Evidence | Confidence |
|---|---|---|---|---|
| 1 | Interrupt before/after a node and resume, same process | **Yes** | `Q01_interruptBefore_resumeSameProcess`, `Q01_interruptAfter_resumeSameProcess`, `Q01_dynamicInterruptFromNode_resumeAfterApproval`, `Q01_stateAfterInterruptIsVisibleToCaller`. Resume runs only the remaining nodes. A dynamic `InterruptibleAction` that is still unapproved interrupts again on resume. | verified-by-test |
| 2 | Same across a process restart (`kill -9`, same thread id) | **Yes for V1 and V2**, with at-least-once for in-flight nodes | `Q02_killDuringNode_resumeReRunsOnlyTheInFlightNode`: plan is not re-run, draft (in flight at the kill) is. `Q02_killWhileInterrupted_resumeCompletes_V1`. `Q02_killWhileInterrupted_interruptFlagPersisted_V2`: V2 writes `is_interrupted`. V1 has no such column (`Q02_killWhileInterrupted_V1_hasNoInterruptFlag`). | verified-by-test |
| 3 | Checkpoint size and growth per step | **Partly.** Works, but storage and read cost are superlinear in steps and nothing is pruned | `Q03_checkpointGrowthAndReadAmplification_javaSerializer` and `..._jsonSerializerComparison`. Table below. Rows persist after completion, and the history API returns nothing for a released thread (`Q06_snapshotsStreamCarriesCheckpointIds`). | verified-by-test (measured on synthetic prose, about 380 characters per step) |
| 4 | Changing the state class between versions | **Partly.** Additive changes with a pinned `serialVersionUID` load. Renames and unpinned shape changes fail, at load time or in a node | Eight variants in `Q04StateEvolutionTest`. Outcomes below. | verified-by-test (Java serialization only; Jackson not tested) |
| 5 | Thread-id scoping (user + instance + tenant) | **No** for a delimiter-joined id. **Yes** for a length-prefixed hashed id, with an ownership check outside the saver | `Q05_naiveDelimiterEncoding_collidesAndLeaksState`: `acme|x|y|9` is the same thread for two scopes, and one reads the other's draft. `Q05_proposedEncoding_isInjectiveForTheCollisionPair_andFixedLength`. `Q05_v1ThreadNameLongerThan255_isRejectedByTheDatabase`. `Q05_prefixIdsDoNotLeakState`. `Q05_anyoneWithTheThreadIdCanReadTheCheckpoint`: the saver has no access control. | verified-by-test |
| 6 | Streaming events from a run | **Yes** | `Q06_valuesStreamEmitsEachNodeWhenItCompletes`: each node output arrives when its node completes, not in a batch. The stream also emits `__START__` and `__END__` markers. `Q06_snapshotsStreamCarriesCheckpointIds`: `SNAPSHOTS` mode yields one checkpoint id per step. | verified-by-test |
| 7 | Cancelling a running graph | **Partly.** Stream ends at once, but cancel does not stop a running node unless asked to | `Q07_cancelWithoutInterrupt_runningNodeKeepsRunning_reviewDoesNotStart`: `cancel(false)` ends the stream in about 1 ms, the running draft keeps going and finishes, review does not start. `Q07_cancelWithInterrupt_runningNodeIsInterrupted_andThreadResumes`: `cancel(true)` interrupts the sleeping node (no `DONE` after 3 s), and resume re-runs `draft`. | verified-by-test |
| 8 | Parallel branches and subgraphs with checkpoints | **Parallel: yes. Subgraph resume after a child interrupt: no** | `Q08_parallelBranches_eachRunsOnce_joinRunsOnce`, `Q08_parallelBranches_interruptBeforeJoin_resumeRunsJoinOnce`. Subgraph: `Q08_subgraphInterruptInsideChild_propagatesToParent` passes. Resume fails on V1 (`Q08_subgraphInterruptInsideChild_resumeParentCompletes_V1`, disabled) with `active Thread '<thread>_subgraph_sub' not found`. The subgraph thread row is already `is_released = true` while the parent is interrupted. Resume fails on V2 too (`Q08_..._V2_probe`, disabled) with `No LG4JThread found for thread_id '<thread>_subgraph_sub'`. | verified-by-test |
| 9 | DB connections; `DataSource` or own | **Takes a `DataSource`.** Each operation takes a connection and returns it; a run never holds one across a node | `Q09_sequentialRun_neverHoldsMoreThanOneConnection`: peak 1 open connection in a 20-step run. `Q09_parallelBranches_completeOnPoolOfOne`: parallel run completes on a pool of one. Two connections per checkpoint write (load, then insert), 405 for 200 steps. Without a `DataSource`, the builder creates an unpooled `PGSimpleDataSource` from host/port (`Q09_builderWithoutDataSource_runsEndToEnd` runs it end to end). | verified-by-test (pool behaviour); read-in-source (unpooled default) |
| 10 | Human edit of state at an interrupt, applied before resume | **Yes** | `Q10_editAtInterrupt_isUsedByTheResumedNode`: the reviewed node sees the edited draft, and `updateState` adds a checkpoint, so the history keeps the edit. `Q10_editSurvivesKill9BeforeResume`: the edit is applied in a second JVM after the first was killed. | verified-by-test (`asNode` variant not tested) |
| 11 | `langgraph4j-opentelemetry` and `langgraph4j-ag-ui-sdk` at 1.9.5 | **Yes, both resolve and run.** OpenTelemetry API 1.55.0, AG-UI `java-server` 0.1.1 | Both are 1.9.5 on Maven Central with parent 1.9.5 and `langgraph4j-core` 1.9.5. `Q11_openTelemetryHook_runsWithCheckpointingGraph`: the wrap hook works in a checkpointing graph with interrupt and resume. `Q11_agUiStepHook_emitsStepEventsInPlainStream`: two `AGUINodeOutput` step events per node in a plain stream. | verified-by-test (resolve and run); read-in-source (what they record) |

### Q3 measurements

Synthetic state: topic 100 characters, plan 500 characters, and a draft that grows by 380 characters of varied prose per step. Each run uses a fresh schema. Saver V1. `stateBytes` is `pg_column_size` of the stored `state_data` (compressed). `charsRead` and `rowsRead` count what the saver fetched during the run.

| Serializer | Steps | Checkpoint rows | Stored text chars | Stored bytes (compressed) | Table total | Rows read | Chars read | Connections |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Java stream (base64) | 10 | 11 | 39,864 | 19,407 | 147 KB | 91 | 187 K | 25 |
| Java stream (base64) | 50 | 51 | 702,052 | 221,637 | 385 KB | 1,431 | 12.7 M | 105 |
| Java stream (base64) | 200 | 201 | 10,405,476 | 2,775,874 | 3.1 MB | 20,706 | 709 M | 405 |
| JSON (Jackson) | 10 | 11 | 28,888 | 13,159 | 98 KB | 91 | 135 K | 25 |
| JSON (Jackson) | 50 | 51 | 521,608 | 141,928 | 295 KB | 1,431 | 9.4 M | 105 |
| JSON (Jackson) | 200 | 201 | 7,784,409 | 1,872,282 | 2.2 MB | 20,706 | 530 M | 405 |

What this shows:

- Rows grow linearly (one per step, plus START). Stored and read text grows quadratically: 200 vs 50 steps is about 14.8 times more stored text, against 4 times for linear growth. The saver reloads the whole thread on each `put` and `get`, so rows read is about n²/2 (`rowsRead` 20,706 at 200 steps).
- The Java-stream serializer is 34 to 38 percent larger than JSON in stored text, and 47 to 56 percent larger in compressed bytes, on this data (base64 over the Java stream format).
- Nothing is deleted. The 200-step run leaves 201 rows after the thread is released.
- The 380-character chunk per step is an assumption about the workload, not a measurement of real agent output. The shape of the curve does not depend on it; the absolute sizes do.

### Q4 outcomes

Checkpoint written by v1 classes (`plan` writes `finding`, interrupted before `work`), resumed by each variant in a new JVM. Java serialization, `ObjectStreamStateSerializer`.

| Variant | Change | Outcome |
|---|---|---|
| v1 (control) | none | `RESULT summary=work on evidence-1` |
| addStateKey | node reads new key `severity` with default | loads; `severity=low` |
| renameStateKey | `finding` renamed to `evidence`, no fallback | loads; `work` fails: `IllegalStateException: missing finding` |
| renameStateKeyFallback | read `evidence`, fall back to `finding` | loads; old checkpoints work |
| addPojoFieldPinned | value class gains a field, `serialVersionUID = 1L` kept | loads; new field `null` |
| removePojoFieldPinned | value class loses a field, `serialVersionUID = 1L` kept | loads; removed field dropped silently |
| addPojoFieldUnpinned | value class gains a field, no `serialVersionUID` | **fails at load**: `InvalidClassException ... local class incompatible` |
| renameClass | value class renamed | **fails at load**: `ClassNotFoundException: ...$Finding` |

Failure mode: a load failure is reported as the error of the resume run, and the run produces no result. Whether `stateOf` or `getStateHistory` also fail on such a thread was not tested. The renamed-key case is different: the run starts and fails inside the node that reads the missing key. Silently wrong results were not observed in any variant.

## What this means for the plan

Work packages are from [work-packages.md](../plan/work-packages.md). Nothing was edited there; the changes below are proposals for the owner.

1. **WP-201 Agent runtime and graph catalog (assumes "checkpointing with state-schema versioning").** The assumption is wrong for the defaults. `ObjectStreamStateSerializer` writes values with Java serialization, so any value class change breaks old checkpoints (Q4). The work package needs a plain-text serializer over explicit state DTOs, a `schemaVersion` in every checkpoint, and an upcaster chain. The current `ResearchAgent` still uses the Java-stream serializer and V1; this is a risk in code that exists today.
2. **WP-201 and WP-207 (subgraphs for supervisors and specialists).** Newly risky. A subgraph that is interrupted cannot be resumed on 1.9.5 with either saver (Q8). Multi-agent designs must not put an interruptible specialist behind a subgraph node. Use flat nodes, or separate threads per specialist coordinated by the workflow.
3. **WP-208 Interactive human-agent collaboration (durable interrupts that survive restart).** Holds for redirect (Q10) and restart (Q2), but only on V2, which records the interruption (`is_interrupted`). V1 records nothing beyond the last checkpoint. WP-208 should use V2, or keep its own interrupt record.
4. **WP-105 Run lifecycle and canonical progress.** Two new requirements. (a) Checkpoints are never deleted and the history API hides them after completion, so `agent_task` must be the status source, and a retention job must be added (Q3, Q6). (b) A node that is killed mid-run runs again on resume (Q2), so any node with an external effect must go through the effect ledger (WP-108). The assumption that the graph gives exactly-once effects is wrong.
5. **WP-302 Version pinning of in-flight work.** Pinning is required, not optional. Q4 shows that a running checkpoint cannot follow a code change unless the state class is version-tolerant. Pin the graph and state version in `agent_task`, and keep the old classes deployable until those threads finish, or upcast.
6. **WP-112 Observability.** `langgraph4j-opentelemetry` records serialized state on span events (read in source: `OTELWrapCallTraceHook`). That is personal or sensitive data in traces by default. Redaction needs to be a deliberate setting (Q11).
7. **WP-109 Timers and deadlines.** Cancellation is cooperative for running nodes (Q7). A deadline must use `cancel(true)` and nodes must handle interruption and be idempotent.
8. **ADR-0014 and WP-005 (workflow-to-agent bridge).** No change to the HTTP contract. The bridge must not depend on subgraph resume (Q8).

## Open questions

1. Jackson (`JsonSpikeSerializer`) under add, remove and rename: not tested. Only size was measured (Q3, Q4).
2. V2 saver after release: the `LG4JCheckpoint` foreign key is commented out in the V2 schema, so release may leave rows behind. Not measured.
3. Two JVMs resuming the same thread at once. The saver's lock is an in-process `ReentrantLock`. No cross-process lease or fencing was tested.
4. After `cancel(false)`, does the completed node's checkpoint get written? Not tested (the test stopped before resume).
5. `updateState(..., asNode)`: only the default form was tested.
6. Failure during a checkpoint write (database lost between the node and the insert). Not tested.
7. The subgraph failure (Q8) needs an upstream answer before any multi-agent design depends on it. Whether the failure is in the release logic or the thread naming was not established.
8. The OpenTelemetry module's spans were not inspected (no exporter configured). The state-on-span behaviour is read in source only.
9. The documentation pages are unread (blocked, see above).

## Commands run

```
cd spikes/spike-b-langgraph4j
mvn dependency:tree                         # resolves langgraph4j 1.9.5, postgres-saver, Hikari
mvn dependency:sources                      # source jars read for the APIs used
mvn test -Dtest=Q01InterruptResumeTest      # 4 tests, pass
mvn test -Dtest=Q02RestartKill9Test         # 4 tests, pass (child JVMs killed with kill -9)
mvn test -Dtest=Q03CheckpointGrowthTest     # 2 tests, pass; measurements above
mvn test -Dtest='Q05*,Q06*,Q07*,Q08*,Q09*,Q10*'
mvn test -Dtest=Q04StateEvolutionTest       # 8 tests, pass
mvn test -Dtest=Q11OptionalModulesTest      # 2 tests, pass
mvn test                                    # full suite: 40 run, 0 failures, 0 errors, 2 skipped (disabled Q8 resume tests)
```

Database: `spike_b` on localhost:5432, schemas `q01`..`q11`, `q02v2`, `q03_*`, `q08v2`, `q09own` (public). Database `agentic` was not touched.
