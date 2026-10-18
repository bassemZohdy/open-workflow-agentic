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
