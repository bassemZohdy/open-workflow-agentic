# ADR-0009: Define processes in OWS YAML

**Status:** Accepted  
**Date:** 2026-10-18

## Context

Process definitions must be inspectable, versioned and portable (M01, H01). The Open Workflow Specification is an open standard with a simple declarative format.

## Decision

Workflow definitions are OWS 1.0 YAML files under `src/main/resources/flow/`, loaded by Quarkus Flow as `WorkflowDefinition` beans. The Java DSL is used only where YAML cannot express a step.

## Consequences

- Definitions diff and review like code and can be validated before publication.
- Portable to other OWS runtimes, subject to vendor extensions and round-trip loss (H01).
- Quarkus Flow conformance to current OWS is claimed, not yet verified here.
