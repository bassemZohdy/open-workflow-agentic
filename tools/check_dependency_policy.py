#!/usr/bin/env python3
"""Dependency policy check. Run after `mvn -B verify`.

The parent POM makes maven-dependency-plugin write each module's resolved dependencies to
<module>/target/dependency-list.txt during `verify`. This script reads those files and enforces two rules:

1. Contracts purity (ADR 0017, docs/plan/README.md section 2): oa-contracts has no dependency on its main
   classpath (scope compile, provided, runtime or system). Test-scoped dependencies are allowed.

2. Licence allow-list, decided per scope. Licence names are read from each artifact's POM in the local Maven
   repository (inherited from the parent POM when the artifact declares none) and normalised to identifiers:
     - Apache-2.0, MIT, BSD, EPL-2.0, Public-Domain (also CC0) may be used in any scope.
     - CDDL, GPL-2.0-with-classpath-exception, UPL, MPL-2.0 and LGPL may be used in test scope only.
     - GPL, AGPL, EPL-1.0 and anything unrecognised fail.
   A component that declares several licences passes when one of them is allowed for the scope.

3. Exceptions (tools/dependency-policy-exceptions.json): a reviewed override for one coordinate whose POM
   declares no licence or an unrecognised one. The exception's licence is still checked against the scope, so an
   exception cannot allow a licence the rule forbids. An exception marked "unverified", or one that matches no
   dependency (stale), fails the check.

No network access: it only reads the local repository (~/.m2/repository by default).

Usage: python3 tools/check_dependency_policy.py [--root DIR] [--local-repo DIR] [--exceptions FILE]
Exit status: 0 policy holds, 1 violations, 2 input missing (run `mvn -B verify` first).
"""
import argparse
import json
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

PROJECT_GROUP = "io.github.bassemzohdy.openworkflow"
CONTRACTS_MODULE = "oa-contracts"
MAIN_CLASSPATH_SCOPES = {"compile", "provided", "runtime", "system"}
KNOWN_SCOPES = MAIN_CLASSPATH_SCOPES | {"test"}
ALLOWED_ANY_SCOPE = {"Apache-2.0", "MIT", "MIT-0", "BSD", "EPL-2.0", "Public-Domain"}  # MIT-0: MIT without attribution
ALLOWED_TEST_SCOPE_ONLY = {"CDDL", "GPL-2.0-with-classpath-exception", "UPL", "MPL-2.0", "LGPL"}
DEFAULT_EXCEPTIONS = Path(__file__).resolve().parent / "dependency-policy-exceptions.json"

# Ordered: the first match wins, so specific names come before the general ones they contain.
# Each mapping was checked against the licence name and URL in the artifact's own POM (see the exceptions file
# and the unit tests for the names).
LICENCE_PATTERNS = [
    (r"public domain|\bcc0\b", "Public-Domain"),
    (r"apache.*(2\.0|version 2)|apache-2\.0", "Apache-2.0"),
    (r"\bmit-0\b", "MIT-0"),
    (r"\bmit\b", "MIT"),
    (r"^edl\b|eclipse distribution", "BSD"),  # EDL 1.0 is the BSD-3-Clause text (Eclipse, edl-v10)
    (r"\bbsd\b", "BSD"),
    (r"epl[- ]?2\.0|eclipse public license.*(2\.0|v ?2)", "EPL-2.0"),
    (r"eclipse public license|\bepl[- ]?1", "EPL-1.0"),
    (r"mozilla public license.*2|mpl[- ]?2", "MPL-2.0"),
    (r"common development and distribution|cddl", "CDDL"),
    (r"universal permissive|\bupl\b", "UPL"),
    (r"classpath exception|w/ ?cpe|with-classpath-exception", "GPL-2.0-with-classpath-exception"),
    (r"lesser general public|\blgpl", "LGPL"),
    (r"affero|\bagpl", "AGPL"),
    (r"gnu general public|\bgpl\b|gpl-?\d|general public license", "GPL"),
]


def classify_licence(name):
    """Returns the normalised identifier for a licence name, or None when it is not recognised."""
    lowered = name.lower()
    for pattern, identifier in LICENCE_PATTERNS:
        if re.search(pattern, lowered):
            return identifier
    return None


def allowed_for_scope(identifier, scope):
    if identifier in ALLOWED_ANY_SCOPE:
        return True
    return identifier in ALLOWED_TEST_SCOPE_ONLY and scope == "test"


def _local(tag):
    """Tag name without namespace. Maven POMs may or may not declare the POM namespace."""
    return tag.rsplit("}", 1)[-1]


def _child(element, name):
    if element is None:
        return None
    for child in element:
        if _local(child.tag) == name:
            return child
    return None


def _text(element, name):
    child = _child(element, name)
    return child.text.strip() if child is not None and child.text else ""


def _declared_licences(root):
    licences = _child(root, "licenses")
    if licences is None:
        return []
    return [_text(lic, "name") for lic in licences if _local(lic.tag) == "license" and _text(lic, "name")]


def read_declared_licences(local_repo, group, artifact, version):
    """Licence names of an artifact, inherited from its parent POM when the artifact declares none.

    Returns (names, error). An error means a POM is not in the local repository.
    """
    for _ in range(8):
        pom = Path(local_repo, *group.split("."), artifact, version, f"{artifact}-{version}.pom")
        if not pom.is_file():
            return [], f"POM not found in local repository: {group}:{artifact}:{version}"
        root = ET.parse(pom).getroot()
        names = _declared_licences(root)
        if names:
            return names, None
        parent = _child(root, "parent")
        if parent is None:
            return [], None
        group = _text(parent, "groupId")
        artifact = _text(parent, "artifactId")
        version = _text(parent, "version")
        if not group or not artifact or not version or "${" in version:
            return [], None
    return [], None


def parse_dependency_list(path):
    """Yields (groupId, artifactId, version, scope) for each resolved dependency line.

    Lines look like 'g:a:jar:1.0:compile -- module g.a [auto]' (Maven 3.9). The part after ' -- ' is the
    module name and is ignored; the scope is the last colon-separated field of the coordinates. Header lines
    and anything that is not a coordinate are skipped.
    """
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        coords = raw.strip().partition(" -- ")[0]
        parts = coords.split(":")
        if len(parts) == 5:
            group, artifact, _type, version, scope = parts
        elif len(parts) == 6:
            group, artifact, _type, _classifier, version, scope = parts
        else:
            continue
        if scope not in KNOWN_SCOPES:
            continue
        yield group, artifact, version, scope


def load_exceptions(path):
    """Returns (entries, error). A missing file means no exceptions. Each entry is validated."""
    path = Path(path)
    if not path.is_file():
        return [], None
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as error:
        return [], f"{path}: not valid JSON ({error})"
    if not isinstance(data, dict) or not isinstance(data.get("exceptions"), list):
        return [], f"{path}: expected an object with an 'exceptions' list"
    entries, problems = [], []
    for i, entry in enumerate(data["exceptions"]):
        where = f"{path} entry {i}"
        coords = entry.get("coordinates", "") if isinstance(entry, dict) else ""
        if not isinstance(entry, dict) or len(coords.split(":")) != 3:
            problems.append(f"{where}: 'coordinates' must be group:artifact:version")
            continue
        if not entry.get("licence") or classify_licence(entry["licence"]) is None:
            problems.append(f"{where} ({coords}): 'licence' must be a recognised licence name")
            continue
        scopes = entry.get("scopes")
        if not isinstance(scopes, list) or not scopes or not set(scopes) <= KNOWN_SCOPES:
            problems.append(f"{where} ({coords}): 'scopes' must be a non-empty list of {sorted(KNOWN_SCOPES)}")
            continue
        if entry.get("status") not in ("verified", "unverified"):
            problems.append(f"{where} ({coords}): 'status' must be 'verified' or 'unverified'")
            continue
        if not str(entry.get("justification", "")).strip():
            problems.append(f"{where} ({coords}): 'justification' is required")
            continue
        entries.append(entry)
    return entries, "; ".join(problems) if problems else None


def modules_of(root):
    pom = ET.parse(Path(root, "pom.xml")).getroot()
    modules = _child(pom, "modules")
    if modules is None:
        return []
    return [m.text.strip() for m in modules if _local(m.tag) == "module" and m.text]


def check(root, local_repo, exceptions_path=DEFAULT_EXCEPTIONS):
    """Returns (violations, missing_inputs). Both are lists of human-readable strings."""
    root = Path(root)
    violations, missing = [], []
    exceptions, exception_error = load_exceptions(exceptions_path)
    if exception_error:
        violations.append(exception_error)
    by_coords = {e["coordinates"]: e for e in exceptions}
    used = set()
    checked = set()
    for module in modules_of(root):
        listing = root / module / "target" / "dependency-list.txt"
        if not listing.is_file():
            missing.append(f"{listing} (run `mvn -B verify` first)")
            continue
        for group, artifact, version, scope in parse_dependency_list(listing):
            if group == PROJECT_GROUP:
                continue
            if module == CONTRACTS_MODULE and scope in MAIN_CLASSPATH_SCOPES:
                violations.append(f"{module} must have no main-classpath dependency, but has "
                                  f"{group}:{artifact}:{version} ({scope})")
            key = (group, artifact, version, scope)
            if key in checked:
                continue
            checked.add(key)
            coords = f"{group}:{artifact}:{version}"
            exception = by_coords.get(coords)
            if exception is not None and scope in exception["scopes"]:
                used.add(coords)
                if exception["status"] == "unverified":
                    violations.append(f"{coords} ({scope}): exception is marked unverified; verify its licence "
                                      f"or remove the dependency")
                    continue
                if allowed_for_scope(classify_licence(exception["licence"]), scope):
                    continue
                violations.append(f"{coords} ({scope}): exception licence {exception['licence']!r} is not allowed "
                                  f"in {scope} scope")
                continue
            names, error = read_declared_licences(local_repo, group, artifact, version)
            if error:
                violations.append(f"{module}: {error}")
                continue
            if not names:
                violations.append(f"{coords} ({scope}): no licence declared in its POM; add a reviewed entry to "
                                  f"{Path(exceptions_path).name} if it is acceptable")
                continue
            identifiers = [(n, classify_licence(n)) for n in names]
            if any(i and allowed_for_scope(i, scope) for _, i in identifiers):
                continue
            unknown = [n for n, i in identifiers if i is None]
            if unknown:
                violations.append(f"{coords} ({scope}): unrecognised licence {unknown[0]!r}; add it to "
                                  f"LICENCE_PATTERNS if it is acceptable")
            else:
                found = ", ".join(sorted({i for _, i in identifiers}))
                violations.append(f"{coords} ({scope}): licence {found} not allowed in {scope} scope")
    if not missing:
        for entry in exceptions:
            if entry["coordinates"] not in used:
                violations.append(f"stale exception {entry['coordinates']}: matches no resolved dependency "
                                  f"in its scopes; remove it")
    return violations, missing


def main(argv=None):
    here = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=str(here), help="repository root (default: this checkout)")
    parser.add_argument("--local-repo", default=os.path.expanduser("~/.m2/repository"),
                        help="Maven local repository (default: ~/.m2/repository)")
    parser.add_argument("--exceptions", default=str(DEFAULT_EXCEPTIONS), help="reviewed exceptions file")
    args = parser.parse_args(argv)

    violations, missing = check(args.root, args.local_repo, args.exceptions)
    if missing:
        print("dependency policy: input missing:", file=sys.stderr)
        for item in missing:
            print("  " + item, file=sys.stderr)
        return 2
    if violations:
        print("dependency policy: %d violation(s)" % len(violations))
        for item in violations:
            print("  " + item)
        return 1
    print("dependency policy: holds (oa-contracts is dependency-free on the main classpath; "
          "all licences allowed for their scope)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
