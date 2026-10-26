# WP-605 — OWS 1.0.3 portability inventory

**Status: static test fixtures only. No engine conformance has been proven.**

## Pinned source-shape correction — 2026-10-26

Checked the [official OWS 1.0.3 schema in YAML form](https://github.com/open-workflow-specification/specification/blob/v1.0.3/schema/workflow.yaml): `for.in` is a **runtime expression string**, not a literal JSON array; `use.extensions` is an **array of single-name objects**, not a mapping. The original draft static inventory allowed invalid shapes. The corrected inventory now rejects these and the fixture uses `.items`; a targeted negative test covers both. This is an exact-shape correction only, **not** full schema validation.

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

An explicit --require-verified check is EXPECTED TO FAIL until runtime evidence exists. Static JSON round-trip equality is NOT execution equivalence, persistence recovery, YAML import/export fidelity or acceptance closure.

## Official pinned schema validator (new isolated increment)

The byte-identical upstream schema is vendored at `vendor/ows-1.0.3-workflow.yaml`
from the upstream immutable [v1.0.3 tag](https://github.com/open-workflow-specification/specification/tree/v1.0.3/schema).
The upstream and vendored Git blob SHA-1 are both
`0bd5cc42c73f639c2fd3956c753ea115cd8e646d` (verified with GitHub's content hash).
The source is **YAML encoding of JSON Schema Draft 2020-12**, not a separate JSON file.
The new offline validator rejects any changed blob before parsing, validates the
pinned DSL string `1.0.3`, and invokes the Draft 2020-12 engine.

For an offline Python 3 environment with dependencies available:

```bash
python3 -m pip install -r docs/conformance/requirements.txt
python3 -m unittest discover -s tools -p 'test_ows_schema.py' -v
python3 tools/ows_schema.py docs/conformance/fixtures/*.json
```

`tools/test_ows_schema.py` provides seven cases covering exact byte-integrity,
all fixture schemas, missing required properties, wrong DSL, invalid loop/extension
shapes, duplicate/nonfinite JSON, schema tampering and CLI results.

**Evidence limitation:** the vendor blob identity and standalone Python syntax checks
are independently verified. The complete official-schema test execution on a checked-out
repo and downstream runner-level conformance are still pending. No `SCHEMA_VALID`
fixture result or PR review is claimed until the tests actually run against this blob.

## Still required before closing WP-605

1. **Vendored schema and validation implementation are prepared.** Run the full pinned-schema test suite on a checkout and retain independent result evidence, then have Claude review the exact head. The static inventory remains only a supplementary check.
2. For Quarkus Flow, LangGraph and Google ADK, document explicit OWS import/export adapters and supported feature subsets, then run same deterministic branches, nested workflows, parallel behavior, loops and restart/checkpoint cases. Unsupported operations must return explicit errors.
3. Compare runtime-produced JSON exports for loss; inventory vendor extensions and unsupported features. Do not transform unknown features silently.
4. Record exact runtimes/SDK versions, process CI evidence and failed tests. Do not claim full enterprise portability without live identity, checkpoint/recovery, human actions and effect safety checks.
5. Only update tracked WP-605 status after peer-reviewed runtime-level evidence; no other agent branch or active implementation PR is touched.

Official DSL reference: https://github.com/open-workflow-specification/specification/blob/main/dsl-reference.md . Upstream main can evolve beyond 1.0.3; pin that exact version for schema validation.
