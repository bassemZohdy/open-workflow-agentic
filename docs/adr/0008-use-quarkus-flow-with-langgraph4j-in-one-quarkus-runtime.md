# ADR-0008: Use Quarkus Flow with LangGraph4j in one Quarkus runtime

**Status:** Accepted for the proof of concept  
**Date:** 2026-10-18

## Context

The technology evaluation (option 1 of four) scored Quarkus Flow plus embedded LangGraph4j highest on a Java-only constraint, mainly because of stack cohesion (both run in one Quarkus application on Quarkus LangChain4j), the OWS workflow standard and a light footprint. The score is a desk score; no Mandatory gate has been proven.

## Decision

Build the first implementation as a single Quarkus application: Quarkus Flow for process control and durable execution, LangGraph4j for agent loops, Quarkus LangChain4j for models and tools.

## Consequences

- One build, one runtime, one set of configuration and observability.
- No official Quarkus Flow to LangGraph4j integration exists, so the bridge is ours (cohesion 4, not 5).
- `quarkus-flow-langchain4j` is Preview and `langchain4j-agentic` is experimental; neither is relied on.
- The decision is reversible: the workflow-to-agent contract is narrow (ADR 0010).

## Alternatives considered

- LangGraph.js in a TypeScript project.
- Pure Java: Spring Boot, Spring AI and the OWS SDK.
- open-workflow-agent (Python).
These remain valid and are scored in the evaluation report.
