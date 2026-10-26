# WP-605 — OWS 1.0.3 portability inventory

**Status: static test fixtures only. No engine conformance has been proven.**

## Pinned source-shape correction — 2026-10-26

Checked the [official OWS 1.0.3 JSON Schema](https://open-workflow-specification.org/schemas/1.0.3/workflow.json): `for.in` is a **runtime expression string**, not a literal JSON array; `use.extensions` is an **array of single-name objects**, not a mapping. The original draft static inventory allowed invalid shapes. The corrected inventory now rejects these and the fixture uses `.items`; a targeted negative test covers both. This is an exact-shape correction only, **not** full schema validation.

Validated independently with Python 3 locally: `python -m unittest discover -s tools -p 'test_ows_portability.py' -v` — **9 passed** (no skips); no Maven/Quarkus/Google ADK engine or GitHub CI run is claimed.


This is a deliberately isolated increment: no runtime, identity, CI, migration, shared contract, or active work-package changes.

- Fixture inputs: docs/conformance/fixtures/*.json — OWS 1.0.3 JSON examples of sequence, conditional/nested tasks, fork and iteration.
- Offline tool: tools/ows_portability.py — limited task-shape inventory, strict duplicate JSON key refusal, source/export semantic comparison and explicit supported/unsupported/unverified status.
- Capability matrix: docs/conformance/runtime-capabilities.json — all features begin UNVERIFIED. Absence of evidence is not support.
- Tests: tools/test_ows_portability.py — negative and positive controls for format drift, unknown task shapes, duplicate names, and strict runtime support gating.

Commands (repository root):

    python3 -m unittest discover -s tools -p 'test_ows_portability.py' -v
    python3 tools/ows_portability.py docs/conformance/fixtures/fork-and-loop.json --capabilities docs/conformance/runtime-capabilities.json
    python3 tools/ows_portability.py docs/conformance/fixtures/sequence.json --exported docs/conformance/fixtures/sequence.json

An explicit --require-verified check is EXPECTED TO FAIL until runtime evidence exists. Static JSON round-trip equality is NOT execution equivalence, OWS schema validation, persistence recovery, YAML import/export fidelity or acceptance closure.

## Still required before closing WP-605

1. Pin actual OWS 1.0.3 JSON Schema by source digest and validate every fixture against it. The static inventory is not a full schema parser.
2. For Quarkus Flow, LangGraph and Google ADK, document explicit OWS import/export adapters and supported feature subsets, then run same deterministic branches, nested workflows, parallel behavior, loops and restart/checkpoint cases. Unsupported operations must return explicit errors.
3. Compare runtime-produced JSON exports for loss; inventory vendor extensions and unsupported features. Do not transform unknown features silently.
4. Record exact runtimes/SDK versions, process CI evidence and failed tests. Do not claim full enterprise portability without live identity, checkpoint/recovery, human actions and effect safety checks.
5. Only update tracked WP-605 status after peer-reviewed runtime-level evidence; no other agent branch or active implementation PR is touched.

Official DSL reference: https://github.com/open-workflow-specification/specification/blob/main/dsl-reference.md . Upstream main can evolve beyond 1.0.3; pin that exact version for schema validation.
