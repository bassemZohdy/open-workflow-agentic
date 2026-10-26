# WP-103 resource task allocation contract — isolated proposal adoption

Baseline: main2284d106db01548c2b620c5ad8744aa25fe692f1.
Writer GPT; reviewer Claude. These JDK-only values and port are DATA, without a bean,
store, migration, approved profile producer, budget reservation or execution permission.
Only six explicitly selected contract/test files from gpt/WP-103-execution-contract
are adopted; the stale implementation/schema stack is not merged.

The allocation identity binds the FIRST canonical resource call. Changed task,
profile, target/request or parent data changes its full fingerprint at that same
identity, so a future store must reject conflicting retries. Full typed owner equality
is mandatory. The optional parent is an actual retained parent, never an inferred
legacy thread; the value cannot verify its database existence/authority.

The profile retains configuration identity and call ceilings. Its name does not
prove registry approval. Per-call ceilings must fit the task, but multiplying the
maximum calls by the ceiling need not fit aggregate task cost: V79 durable reservation
must enforce remaining aggregate budget before each call. Agent iterations and
model calls are distinct limits. No zero/negative ceiling becomes permission.

The committed allocation lifetime uses exact PostgreSQL microseconds and cannot
exceed original task wall clock. Reads and exact repeats do not renew it or budget.
Unknown store/commit/JTA failures must refuse and reconcile the SAME identity.
No current main code consumes these contracts; default refusal is unchanged.

V78/V79/V80 remain reserved and unapplied. The separate migration plan must preserve
historical unallocated executions, enforce new writes and reconcile role/profile
provenance before activation. Resource-call checkpoint, legacy parent and effect
threads remain distinct until an explicit reviewed production association exists.
No whole WP-103/WP-108 or acceptance closure is claimed.

Validation: actual Java21 complete oa-contracts suite passes209 tests, zero failures/errors/skips, including9 allocation cases and17 existing task cases. Exact Claude review and required CI remain pending.
