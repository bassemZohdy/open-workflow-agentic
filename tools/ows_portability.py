"""Offline OWS 1.0.3 JSON fixture inventory and round-trip checks for WP-605.

This is intentionally NOT an OWS JSON Schema validator or runtime conformance claim.
Unknown task shapes fail closed. Runtime evidence must be recorded separately.
"""
import argparse
import hashlib
import json
from pathlib import Path


class FixtureError(ValueError):
    """Fixture is not a recognized OWS 1.0.3 inventory shape."""


TASK_KINDS = frozenset({"call", "do", "emit", "for", "fork", "listen", "raise",
                        "run", "set", "switch", "try", "wait"})
RUN_KINDS = frozenset({"workflow", "container", "script", "shell"})


def _unique_object(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise FixtureError(f"duplicate JSON property: {key}")
        obj[key] = value
    return obj


def _invalid_number(value):
    raise FixtureError(f"non-JSON numeric value: {value}")


def load_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"),
                          object_pairs_hook=_unique_object, parse_constant=_invalid_number)
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise FixtureError(f"invalid JSON fixture {path}: {exc}") from exc


def _object(value, at):
    if not isinstance(value, dict):
        raise FixtureError(f"{at}: expected object")
    return value


def _task_list(items, path, features):
    if not isinstance(items, list) or not items:
        raise FixtureError(f"{path}: expected nonempty ordered task list")
    names = set()
    for index, wrapper in enumerate(items):
        wrapper = _object(wrapper, f"{path}[{index}]")
        if len(wrapper) != 1:
            raise FixtureError(f"{path}[{index}]: task must have one name")
        name, body = next(iter(wrapper.items()))
        if not isinstance(name, str) or not name.strip() or name in names:
            raise FixtureError(f"{path}[{index}]: duplicate or empty task name")
        names.add(name)
        where = f"{path}/{name}"
        body = _object(body, where)
        kind = set(TASK_KINDS.intersection(body))
        # For and try tasks contain a nested do field, not a second task type.
        if "for" in kind or "try" in kind:
            kind.discard("do")
        if len(kind) != 1:
            raise FixtureError(f"{where}: expected exactly one known task type, found {sorted(kind)}")
        kind = next(iter(kind))
        features.add(kind)
        if "timeout" in body:
            features.add("task:timeout")
        if "then" in body:
            features.add("task:then")
        if kind == "call":
            target = body["call"]
            if not isinstance(target, str) or not target.strip():
                raise FixtureError(f"{where}: call target must be nonempty text")
            features.add("call:" + target)
        elif kind == "do":
            _task_list(body["do"], where + "/do", features)
        elif kind == "for":
            iterator = _object(body["for"], where + "/for")
            if "in" not in iterator or not isinstance(body.get("do"), list):
                raise FixtureError(f"{where}: for task needs in and ordered do")
            _task_list(body["do"], where + "/do", features)
            if "while" in body:
                features.add("for:while")
        elif kind == "fork":
            config = _object(body["fork"], where + "/fork")
            if "compete" in config:
                if not isinstance(config["compete"], bool):
                    raise FixtureError(f"{where}: fork.compete must be boolean")
                if config["compete"]:
                    features.add("fork:compete")
            _task_list(config.get("branches"), where + "/fork/branches", features)
        elif kind == "run":
            config = _object(body["run"], where + "/run")
            subtype = RUN_KINDS.intersection(config)
            if len(subtype) != 1:
                raise FixtureError(f"{where}: run requires exactly one known process type")
            features.add("run:" + next(iter(subtype)))
        elif kind == "switch":
            cases = body["switch"]
            if not isinstance(cases, list) or not cases:
                raise FixtureError(f"{where}: switch needs cases")
            defaults = 0
            case_names = set()
            for case in cases:
                case = _object(case, where + "/switch")
                if len(case) != 1:
                    raise FixtureError(f"{where}: each switch case needs one name")
                case_name, value = next(iter(case.items()))
                if case_name in case_names:
                    raise FixtureError(f"{where}: duplicate switch case")
                case_names.add(case_name)
                value = _object(value, where + "/switch/" + case_name)
                if "then" not in value or not isinstance(value["then"], str) or not value["then"]:
                    raise FixtureError(f"{where}: switch case needs then directive")
                if "when" not in value:
                    defaults += 1
            if defaults > 1:
                raise FixtureError(f"{where}: more than one default switch case")
        elif kind == "try":
            if "catch" not in body:
                raise FixtureError(f"{where}: try without catch")
            _task_list(body["try"], where + "/try", features)
        elif kind == "listen":
            config = _object(body["listen"], where + "/listen")
            if "to" not in config:
                raise FixtureError(f"{where}: listen without consumption strategy")
            if "foreach" in config:
                iterator = _object(config["foreach"], where + "/listen/foreach")
                features.add("listen:foreach")
                if "do" in iterator:
                    _task_list(iterator["do"], where + "/listen/foreach/do", features)


def inventory(document):
    document = _object(document, "workflow")
    meta = _object(document.get("document"), "document")
    if meta.get("dsl") != "1.0.3":
        raise FixtureError("document.dsl must equal 1.0.3 for this pinned inventory")
    for field in ("namespace", "name", "version"):
        if not isinstance(meta.get(field), str) or not meta[field].strip():
            raise FixtureError(f"document.{field} must be nonempty text")
    features = set()
    _task_list(document.get("do"), "do", features)
    if "use" in document:
        use = _object(document["use"], "use")
        if "extensions" in use:
            extensions = _object(use["extensions"], "use.extensions")
            features.update("extension:" + name for name in extensions)
    return sorted(features)


def canonical_bytes(document):
    # OWS tasks are ordered in JSON arrays; map key ordering here is not task ordering.
    return json.dumps(document, ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False).encode("utf-8")


def compare_roundtrip(original, exported):
    """Semantic JSON equality only; never a runtime execution parity assertion."""
    inventory(original)
    inventory(exported)
    return canonical_bytes(original) == canonical_bytes(exported)


def evaluate(features, capabilities):
    """Unsupported and unverified are distinct; missing declarations never imply support."""
    report = {}
    for runtime, declared in _object(capabilities, "capabilities").items():
        declared = _object(declared, runtime)
        if set(declared) - {"supported", "unsupported"}:
            raise FixtureError(f"{runtime}: unknown capability declaration fields")
        supported = declared.get("supported", [])
        unsupported = declared.get("unsupported", [])
        if not isinstance(supported, list) or not isinstance(unsupported, list) or any(
                not isinstance(x, str) for x in supported + unsupported):
            raise FixtureError(f"{runtime}: capability declarations must be string lists")
        if len(set(supported)) != len(supported) or len(set(unsupported)) != len(unsupported):
            raise FixtureError(f"{runtime}: duplicate capability declaration")
        if set(supported) & set(unsupported):
            raise FixtureError(f"{runtime}: contradictory capability declaration")
        report[runtime] = {
            "supported": sorted(set(features) & set(supported)),
            "unsupported": sorted(set(features) & set(unsupported)),
            "unverified": sorted(set(features) - set(supported) - set(unsupported)),
        }
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("fixture", type=Path, help="OWS 1.0.3 JSON fixture")
    parser.add_argument("--exported", type=Path, help="JSON exported by runtime for semantic comparison")
    parser.add_argument("--capabilities", type=Path, help="explicit support matrix; omission = unverified")
    parser.add_argument("--require-verified", action="store_true", help="fail on any unsupported or unverified feature")
    args = parser.parse_args(argv)
    if args.require_verified and not args.capabilities:
        parser.error("--require-verified requires --capabilities")
    try:
        original = load_json(args.fixture)
        features = inventory(original)
        result = {"workflow": original["document"]["name"], "dsl": "1.0.3",
                  "features": features, "sha256": hashlib.sha256(canonical_bytes(original)).hexdigest(),
                  "claim": "STATIC_INVENTORY_ONLY"}
        success = True
        if args.exported:
            exported = load_json(args.exported)
            result["roundtrip"] = "LOSSLESS_JSON" if compare_roundtrip(original, exported) else "LOSS_DETECTED"
            success = success and result["roundtrip"] == "LOSSLESS_JSON"
        if args.capabilities:
            result["runtimes"] = evaluate(features, load_json(args.capabilities))
            if args.require_verified:
                required = {"quarkus-flow", "langgraph", "google-adk"}
                success = success and required.issubset(result["runtimes"]) and all(
                    not (v["unsupported"] or v["unverified"])
                    for v in result["runtimes"].values())
        print(json.dumps(result, indent=2, sort_keys=True, ensure_ascii=False))
        return 0 if success else 1
    except (FixtureError, OSError, ValueError) as exc:
        parser.exit(2, f"invalid OWS inventory input: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
