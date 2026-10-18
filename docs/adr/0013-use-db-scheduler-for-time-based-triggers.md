# ADR-0013: Use db-scheduler for time-based triggers

**Status:** Accepted for the proof of concept  
**Date:** 2026-10-18

## Context

Schedules must survive restarts and run once across replicas. The scheduler choice was re-evaluated as a framework, not custom code.

## Decision

Use the Quarkiverse `quarkus-db-scheduler` extension (version 0.0.2) backed by the shared PostgreSQL database.

## Consequences

- Clustered, persistent schedules without another service.
- The extension is young (0.0.x). Scheduled starts of workflow instances are not yet proven (see the proof-of-concept plan).
