"""Offline pinned Open Workflow Specification 1.0.3 schema validation (WP-605).

Only schema syntax is validated; engine behavior and portability remain UNVERIFIED.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys

from ows_portability import FixtureError, load_json

SCHEMA_RELATIVE = Path("docs/conformance/vendor/ows-1.0.3-workflow.yaml")
UPSTREAM_GIT_BLOB_SHA1 = "0bd5cc42c73f639c2fd3956c753ea115cd8e646d"
SCHEMA_ID = "https://serverlessworkflow.io/schemas/1.0.3/workflow.yaml"
PINNED_VERSION = "1.0.3"


def git_blob_sha1(data: bytes) -> str:
    return hashlib.sha1(b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()


def load_validator(schema_path: Path):
    """Validate bytes against upstream Git object ID BEFORE YAML parsing."""
    try:
        import jsonschema
        import yaml
    except ImportError as exc:
        raise FixtureError("Install docs/conformance/requirements.txt for offline schema validation") from exc
    raw = schema_path.read_bytes()
    if git_blob_sha1(raw) != UPSTREAM_GIT_BLOB_SHA1:
        raise FixtureError(f"pinned OWS schema integrity mismatch: {schema_path}")
    try:
        schema = yaml.safe_load(raw)
    except yaml.YAMLError as exc:
        raise FixtureError("pinned OWS schema cannot be parsed") from exc
    if not isinstance(schema, dict) or schema.get("$id") != SCHEMA_ID or schema.get("$schema") != "https://json-schema.org/draft/2020-12/schema":
        raise FixtureError("pinned OWS schema identity/dialect does not match 1.0.3")
    try:
        jsonschema.Draft202012Validator.check_schema(schema)
    except jsonschema.exceptions.SchemaError as exc:
        raise FixtureError(f"invalid pinned OWS schema: {exc.message}") from exc
    return jsonschema.Draft202012Validator(schema)


def validate_document(document: dict, validator) -> list[str]:
    if not isinstance(document, dict) or not isinstance(document.get("document"), dict):
        return ["document: a workflow document object is required"]
    if document["document"].get("dsl") != PINNED_VERSION:
        return [f"document.dsl: expected pinned {PINNED_VERSION}"]
    errors = sorted(validator.iter_errors(document), key=lambda err: (tuple(map(str, err.absolute_path)), err.message))
    return [f"/{'/'.join(map(str, err.absolute_path)) or '<root>'}: {err.message}" for err in errors]


def check_paths(paths: list[Path], validator):
    results = []
    for path in paths:
        try:
            doc = load_json(path)
            errors = validate_document(doc, validator)
        except (FixtureError, OSError) as exc:
            errors = [str(exc)]
        results.append({"path": str(path), "valid": not errors, "errors": errors})
    return results


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixtures", nargs="+", type=Path, help="OWS 1.0.3 JSON fixtures")
    parser.add_argument("--schema", type=Path, default=Path(__file__).resolve().parents[1] / SCHEMA_RELATIVE)
    args = parser.parse_args(argv)
    try:
        validator = load_validator(args.schema)
        results = check_paths(args.fixtures, validator)
    except (FixtureError, OSError) as exc:
        print(json.dumps({"status": "INVALID_SCHEMA", "reason": str(exc)}, sort_keys=True))
        return 2
    print(json.dumps({"status": "SCHEMA_VALID" if all(row["valid"] for row in results) else "INVALID_FIXTURE",
                      "schema_git_blob": UPSTREAM_GIT_BLOB_SHA1, "claim": "SYNTAX_ONLY_NO_RUNTIME_PROOF",
                      "results": results}, indent=2, sort_keys=True))
    return 0 if all(row["valid"] for row in results) else 1


if __name__ == "__main__":
    sys.exit(main())
