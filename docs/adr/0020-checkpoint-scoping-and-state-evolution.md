# ADR-0020: Checkpoint scoping and state evolution

**Status:** Proposed  
**Date:** 2026-10-18

## Context

WP-004 (Spike B, [findings](../spikes/WP-004-langgraph4j-durability.md)) tested LangGraph4j 1.9.5 checkpoints against the requirements in ADR-0010 and ADR-0011. Five results decide how agent threads are named, how their state is serialized, and how they are retained:

- Interrupts and resume work in one process and across `kill -9` (Q1, Q2). A human edit at an interrupt is applied before resume and survives a restart (Q10).
- The saver's thread name is a free string. A delimiter-joined scope (`tenant|user|instance`) collides, and one scope reads the other's checkpoint (Q5). The V1 thread name is `VARCHAR(255)`. The saver checks no access, so whoever presents a thread id reads its state (Q5).
- The default state serializer writes values with Java serialization. Adding a field to a value class with no pinned `serialVersionUID` makes old checkpoints fail to load. Renaming a value class fails to load. Renaming a state key fails inside the node that reads it (Q4).
- Each checkpoint stores the whole state, so storage and reads grow quadratically with the number of steps. Rows are never deleted (Q3).
- Resuming a parent whose child subgraph was interrupted fails on V1 and V2 (Q8).
- A node killed mid-run runs again on resume (Q2). External effects therefore need the effect ledger (ADR-0011, WP-108).

The current `ResearchAgent` uses V1 with `ObjectStreamStateSerializer` and no thread scoping beyond the id it is given.

## Decision

1. **Thread id is derived, not typed.** The checkpoint thread name is `ws1-` followed by the SHA-256 of a length-prefixed canonical form of `(tenant, user, workflow instance)`. The result is 68 characters and injective over the tuple (the length-prefixed form is injective; the hash is not needed for that, it fixes the length). It is never built by joining raw user input. The `agent_task` row in the `task` schema (WP-105) stores the tenant, owner, instance and this thread name, with a unique constraint on the thread name. The runtime checks that row before any read, write or resume. The saver is not the authority for ownership.
2. **Use the V2 saver for agent threads.** `PostgresSaverV2` records interruptions (`is_interrupted`), which V1 does not. Agents that use interrupts are not placed inside subgraphs until the subgraph resume failure (Q8) is resolved upstream or worked around.
3. **Serialize state as plain text over explicit state records.** Replace `ObjectStreamStateSerializer` with a JSON serializer. Values in state are JSON-typed (strings, numbers, lists, maps, or records serialized by Jackson with explicit names). No value class is Java-serialized. Every checkpoint carries `schemaVersion` in the state map. Each schema version has an upcaster that runs before the graph starts. Evolution rules:
   - Add a key: give it a default in the reader. Old checkpoints load.
   - Remove a key: keep reading it for one release and drop it on write.
   - Rename a key: expand (write both, read either), then contract in a later release.
   - Never rename or move a value class without an upcaster.
   These rules must be verified for JSON in WP-201 (the Jackson variant was not tested in WP-004).
4. **Pin in-flight runs.** `agent_task` records the graph version and state schema version of each run (WP-302). A new version starts new threads only. Old graph and state classes stay deployable until their threads are released or upcast.
5. **Bound growth.** Large content (documents, long drafts, retrieved sources) is stored outside state, referenced by id, and written through the memory or effect store. The runtime keeps the latest checkpoint and the interrupt points for each thread. A scheduled job deletes checkpoint rows of released threads after a retention period, because the saver never deletes them. Growth is measured per release with the Q3 test.
6. **Nodes are idempotent under re-execution.** A node that calls a system of record does so through the effect ledger (WP-108), keyed by thread name and node id.
7. **Cancellation.** A hard cancel uses `cancel(true)` and only for nodes that handle interruption. A soft cancel stops subsequent nodes but lets the running node finish.

## Consequences

- Scope collisions by thread name are not possible by construction, and the ownership check is in the runtime, where audit can see it (identity and data requirements M11 and M24 in the plan's grouping).
- The `agent_task` table is the single place for thread ownership, pinning and status. It is also the place to find threads for retention.
- Replacing the serializer changes how every new checkpoint is written, and old Java-serialized checkpoints stay readable only through a compatibility reader. The owner needs to confirm that no checkpoint written by the current serializer must be kept. If one must, the compatibility reader is part of WP-201.
- JSON state has to be designed as data. Typed value classes are not stored inside state. This is a restriction on agent authors, enforced by the state schema.
- Subgraph composition is limited for interruptible agents. Multi-agent designs (WP-207) use flat graphs or one thread per specialist, coordinated by the workflow.
- Growth needs a retention job and an external store for large content. This adds work to WP-105 and WP-206.

## Alternatives considered

- **Keep `ObjectStreamStateSerializer` and pin `serialVersionUID`.** Rejected. Pinning fixes additive changes only. Renames and class moves still fail, and Java serialization ties stored data to Java class names.
- **Hash the scope but skip the `agent_task` ownership check.** Rejected. The hash prevents collisions but does not stop a caller who knows the id from reading it. Ownership needs a check outside the saver.
- **One database schema or database per tenant now.** Deferred. It is compatible with this decision (ADR-0011 allows splitting later), but it does not fix collisions inside a schema, and it costs operations work the proof of concept does not need.
- **Use subgraphs for specialists and rely on their interrupts.** Rejected for now. The subgraph resume failure (Q8) is observed on both savers.
- **Keep all state in the checkpointer, with no external store.** Rejected. Quadratic growth (Q3) makes large content in state too expensive.
