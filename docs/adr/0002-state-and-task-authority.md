# ADR-0002: State and task authority

**Status:** Proposed (requirement-level, D02)  
**Date:** 2026-10-18

## Context

Workflow engines, agent runtimes and schedulers each keep progress. A single global database would couple them; no model would allow silent divergence.

## Decision

One canonical execution progress model with crash-safe reconciliation, not one mandatory global database.

## Consequences

M28 asks for one canonical progress model with crash-safe reconciliation. The engine's own history may serve as that model.
