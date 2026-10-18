# ADR-0014: Bridge workflow to agent over a narrow HTTP contract

**Status:** Accepted as an interim step  
**Date:** 2026-10-18

## Context

No official integration between Quarkus Flow and LangGraph4j exists. The bridge decides whether cohesion is rated 4 or 5.

## Decision

Start with an OWS `call: http` task to `POST /agents/research` (JSON in, JSON out). Replace it with an in-process step once the contract is stable and tests T34 to T37 pass.

## Consequences

- Simple, testable, replaceable on either side.
- Adds a local HTTP hop and requires authority propagation to be designed explicitly (M11).
- Revisit in spike WP-005 and, if replaced, in WP-105 (see the [implementation plan](../plan/README.md)).
