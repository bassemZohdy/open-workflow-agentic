#!/usr/bin/env python3
"""Decides how much of the build a pull request needs, from the files it changes.

Prints `mode=<full|modules|docs>` and, for `modules`, `modules=<comma list for mvn -pl>` (GITHUB_OUTPUT format).

  docs     only Markdown prose changed outside the generated-doc sources: skip Maven.
  modules  only production source under modules/<m>/ or apps/<a>/ changed: build those modules, what they need (-am) and what
           depends on them (-amd). Everything else keeps running as before.
  full     anything else (pom.xml, .github, tools, compose, docs/plan, docs/requirements, unknown paths), no
           changed files, or any doubt. Pushes to main are always full: this script is only used for pull requests.

When unsure the answer is `full`. Generated docs, compose and Python tests run in every mode.
The resolved-dependency policy needs all Maven outputs and runs with the full build.
"""
import re
import subprocess
import sys

MODULE = re.compile(r"^((?:modules|apps)/[A-Za-z0-9_-]+)/src/main/")
# Prose that no build step reads. docs/plan and docs/requirements feed the generated-document check, so they are
# not here. Handoffs are read by no build step.
PROSE = re.compile(r"^(?:docs/(?!plan(?:/|$)|requirements(?:/|$)).+\.md|[^/]+\.md)$")
# Files inside a module that change the build, not just the source.
MODULE_BUILD = re.compile(r"/(pom\.xml|Dockerfile|\.mvn/)")


def decide(changed):
    files = [f for f in changed if f]
    if not files:
        return "full", []
    if all(PROSE.match(f) for f in files):
        return "docs", []
    modules = set()
    for f in files:
        if PROSE.match(f):
            continue
        m = MODULE.match(f)
        if not m or MODULE_BUILD.search(f) or m.group(1) == "modules/oa-testkit":
            return "full", []
        modules.add(m.group(1))
    return "modules", sorted(modules)


def changed_files(base):
    out = subprocess.run(["git", "diff", "--no-renames", "--name-only", f"{base}...HEAD"], check=True, capture_output=True,
                         text=True).stdout
    return out.splitlines()


def main(argv):
    if len(argv) != 2:
        print("usage: ci_scope.py <base-ref>", file=sys.stderr)
        return 2
    try:
        mode, modules = decide(changed_files(argv[1]))
    except (subprocess.CalledProcessError, OSError):
        mode, modules = "full", []
    print(f"mode={mode}")
    print(f"modules={','.join(modules)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
