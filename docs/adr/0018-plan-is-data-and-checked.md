# ADR-0018: Keep the implementation plan as validated data

**Status:** Proposed  
**Date:** 2026-10-18

## Context

A plan in prose drifts from the requirements and tests it is meant to cover.

## Decision

`docs/plan/work-packages.json` is the source. `tools/render_requirements.py` generates `work-packages.md`, adds work-package columns to the requirement and test documents, and fails CI when coverage rules are broken.

## Consequences

- Coverage of every Mandatory requirement and every test is checked automatically.
- Sizes are relative; the critical path is computed, not asserted.
