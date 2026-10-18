# ADR-0010: Keep agent loops in LangGraph4j, not in workflow tasks

**Status:** Accepted  
**Date:** 2026-10-18

## Context

The review of 18 October found the model too workflow-centric. Agents must plan, re-plan, use tools and converse; a workflow task is the wrong place for that loop.

## Decision

Agent reasoning lives in LangGraph4j graphs. The workflow decides when an agent runs, with which authority and what happens with its result. Deterministic steps are used only where a business guarantee needs them.

## Consequences

- Agents can be replaced or extended without touching process definitions.
- Two state models exist (workflow instance, graph thread). M28 requires one canonical progress view, so a reconciliation layer is future work.
