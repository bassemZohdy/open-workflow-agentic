# ADR-0016: Deliver in vertical slices by scenario, spikes first

**Status:** Proposed  
**Date:** 2026-10-18

## Context

The requirements are broad (64 requirements, 8 scenarios, 39 tests). Several technology facts are unknown: how Quarkus Flow resumes after restart, how LangGraph4j state evolves, how the two are bridged, which policy engine fits, what is usable for A2A.

## Decision

Order the work by milestones that each demonstrate a scenario end to end. Start with five time-boxed spikes (WP-003 to WP-007), each closed by an ADR, before building on their answers. Every work package owns specific acceptance tests; every test has exactly one owner.

## Consequences

- Early, demonstrable value and early discovery of integration problems.
- Horizontal layers (all security, then all agents) are avoided because they delay evidence.
- The plan is data (`docs/plan/work-packages.json`) and is validated: Mandatory requirements covered, one owner per test, no dependency on a later phase.

## Alternatives considered

- Layer by layer build: rejected, it postpones proof of the hard parts.
- Build everything first, test later: rejected, contradicts the evidence-first principle of the requirements.
