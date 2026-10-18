#!/usr/bin/env python3
"""Dependency policy check. Run after `mvn -B verify`.

The parent POM makes maven-dependency-plugin write each module's resolved dependencies to
<module>/target/dependency-list.txt during `verify`. This script reads those files and enforces two rules:

1. Contracts purity (ADR 0017, docs/plan/README.md section 2): oa-contracts has no dependency on its main
   classpath (scope compile, provided, runtime or system). Test-scoped dependencies are allowed.

2. Licence allow-list, decided per scope and read from the POMs in the local Maven repository:
     - Apache-2.0, MIT, BSD and EPL-2.0 may be used in any scope.
     - MPL-2.0, CDDL and LGPL may be used in test scope only.
     - Anything else, or a component with no recognisable declared licence, fails.
   A component that declares several licences passes when one of them is allowed for the scope, so a dual licence
   such as "EPL-2.0 OR GPL-2.0 with classpath exception" passes through EPL-2.0.

No network access: it only reads the local repository (~/.m2/repository by default).

Usage: python3 tools/check_dependency_policy.py [--root DIR] [--local-repo DIR]
Exit status: 0 policy holds, 1 violations, 2 input missing (run `mvn -B verify` first).
"""
import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

POM_NS = "{http://maven.apache.org/POM/4.0.0}"
PROJECT_GROUP = "io.github.bassemzohdy.openworkflow"
CONTRACTS_MODULE = "oa-contracts"
MAIN_CLASSPATH_SCOPES = {"compile", "provided", "runtime", "system"}
ALLOWED_ANY_SCOPE = {"Apache-2.0", "MIT", "BSD", "EPL-2.0"}
ALLOWED_TEST_SCOPE_ONLY = {"MPL-2.0", "CDDL", "LGPL"}

# Checked in order against the lower-cased licence name as written in the POM.
LICENCE_PATTERNS = [
    (r"apache.*(2\.0|version 2)", "Apache-2.0"),
    (r"\bmit\b", "MIT"),
    (r"eclipse distribution", "BSD"),  # Eclipse Distribution License 1.0 is BSD-3-Clause
    (r"\bbsd\b", "BSD"),
    (r"eclipse public license.*(2\.0|v ?2)", "EPL-2.0"),
    (r"eclipse public license", "EPL-1.0"),
    (r"mozilla public license.*2", "MPL-2.0"),
    (r"common development and distribution|cddl", "CDDL"),
    (r"lesser general public|lgpl", "LGPL"),
    (r"classpath exception", "GPL-with-classpath-exception"),
    (r"gnu general public|\bgpl\b|general public license", "GPL"),
]


def classify_licence(name):
    """Returns the SPDX-style identifier for a licence name, or None when it is not recognised."""
    lowered = name.lower()
    for pattern, identifier in LICENCE_PATTERNS:
        if re.search(pattern, lowered):
            return identifier
    return None


def allowed_for_scope(identifier, scope):
    if identifier in ALLOWED_ANY_SCOPE:
        return True
    return identifier in ALLOWED_TEST_SCOPE_ONLY and scope == "test"


def read_declared_licences(local_repo, group, artifact, version):
    """Licence names of an artifact, inherited from its parent POM when the artifact declares none.

    Returns (names, error). An error means the POM is not in the local repository.
    """
    for _ in range(8):
        pom = Path(local_repo, *group.split("."), artifact, version, f"{artifact}-{version}.pom")
        if not pom.is_file():
            return [], f"POM not found in local repository: {group}:{artifact}:{version}"
        root = ET.parse(pom).getroot()
        names = [n.text.strip() for n in root.findall(f"{POM_NS}licenses/{POM_NS}license/{POM_NS}name")
                 if n.text and n.text.strip()]
        if names:
            return names, None
        parent = root.find(f"{POM_NS}parent")
        if parent is None:
            return [], None
        group = (parent.findtext(f"{POM_NS}groupId") or "").strip()
        artifact = (parent.findtext(f"{POM_NS}artifactId") or "").strip()
        version = (parent.findtext(f"{POM_NS}version") or "").strip()
        if not group or not artifact or not version or "${" in version:
            return [], None
    return [], None


def parse_dependency_list(path):
    """Yields (groupId, artifactId, version, scope) for each resolved dependency line."""
    for raw in Path(path).read_text(encoding="utf-8").splitlines():
        parts = raw.strip().split(":")
        if len(parts) == 5:
            group, artifact, _type, version, scope = parts
        elif len(parts) == 6:
            group, artifact, _type, _classifier, version, scope = parts
        else:
            continue
        yield group, artifact, version, scope


def modules_of(root):
    pom = ET.parse(Path(root, "pom.xml")).getroot()
    return [m.text.strip() for m in pom.findall(f"{POM_NS}modules/{POM_NS}module") if m.text]


def check(root, local_repo):
    """Returns (violations, missing_inputs). Both are lists of human-readable strings."""
    root = Path(root)
    violations, missing = [], []
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
            names, error = read_declared_licences(local_repo, group, artifact, version)
            if error:
                violations.append(f"{module}: {error}")
                continue
            if not names:
                violations.append(f"{group}:{artifact}:{version} ({scope}): no licence declared in its POM")
                continue
            identifiers = [(n, classify_licence(n)) for n in names]
            if any(i and allowed_for_scope(i, scope) for _, i in identifiers):
                continue
            unknown = [n for n, i in identifiers if i is None]
            if unknown:
                violations.append(f"{group}:{artifact}:{version} ({scope}): unrecognised licence "
                                  f"{unknown[0]!r}; add it to LICENCE_PATTERNS if it is acceptable")
            else:
                found = ", ".join(sorted({i for _, i in identifiers}))
                violations.append(f"{group}:{artifact}:{version} ({scope}): licence {found} not allowed "
                                  f"in {scope} scope")
    return violations, missing


def main(argv=None):
    here = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--root", default=str(here), help="repository root (default: this checkout)")
    parser.add_argument("--local-repo", default=os.path.expanduser("~/.m2/repository"),
                        help="Maven local repository (default: ~/.m2/repository)")
    args = parser.parse_args(argv)

    violations, missing = check(args.root, args.local_repo)
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
