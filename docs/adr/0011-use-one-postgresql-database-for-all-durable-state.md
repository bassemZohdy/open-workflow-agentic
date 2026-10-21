# ADR-0011: Use one PostgreSQL database for all durable state

**Status:** Accepted for the proof of concept  
**Date:** 2026-10-18

## Context

Workflow instances, agent checkpoints and scheduled tasks all need crash-safe persistence (M03, M04).

## Decision

Use one PostgreSQL database: `quarkus-flow-jpa` for workflow state, LangGraph4j `PostgresSaver` for graph checkpoints, db-scheduler for timers. Schema for the scheduler comes from Flyway; the other modules create their own tables.

## Consequences

- One backup, restore and failover story; simple local setup.
- Components share a failure domain. Splitting databases later is a configuration change, not a redesign.
- Hibernate `database.generation=update` is used for the bootstrap only and must be replaced by migrations before production.

## Resource attribution integration increment

V73 retains complete resource-call contracts and verified issuer/jti attribution
in the same database and short transaction. V74 anchors each complete business
occurrence to its first retained call, independently of changing SDK entry counters.
Both migrations preserve existing rows and keys; conflicting legacy occurrences
fail migration rather than selecting or deleting a winner. Historical attribution
is data, not authority to dispatch or execute a graph. The actual durable occurrence
producer, current caller/PDP/target/execution fences and audited result recovery
remain separate integration gates. Reserved audit binding V75 follows these
migrations; it is not included or activated by this increment.
