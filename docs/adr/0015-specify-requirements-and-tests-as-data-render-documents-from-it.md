# ADR-0015: Specify requirements and tests as data, render documents from it

**Status:** Accepted  
**Date:** 2026-10-18

## Context

The requirements and the 39 acceptance tests come from an evaluation report and will change. Hand-edited copies drift.

## Decision

`docs/requirements/requirements.json` is the single source. `tools/render_requirements.py` generates the requirements, test-case and traceability documents. Test implementation status lives in `test-status.json`. CI runs the renderer in `--check` mode.

## Consequences

- Documents cannot silently diverge from the data.
- Changes to requirements are reviewed as JSON diffs.
