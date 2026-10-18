# ADR-0012: Reach models only through an LlmClient port

**Status:** Accepted  
**Date:** 2026-10-18

## Context

Tests must not need network or API keys, and the model provider must be replaceable (M34, model choice).

## Decision

Agent code calls `LlmClient`. The default implementation uses the Quarkus LangChain4j `ChatModel`; tests replace it with a stub `@Alternative`.

## Consequences

- Deterministic tests and CI without secrets.
- Provider, gateway and model are configuration (`LLM_BASE_URL`, `LLM_MODEL`).
- Tool calling and streaming need richer ports later.
