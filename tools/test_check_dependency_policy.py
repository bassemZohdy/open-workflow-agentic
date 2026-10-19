"""Unit tests for tools/check_dependency_policy.py using synthetic repositories (no network, no Maven)."""
import importlib.util
import json
import pathlib
import tempfile
import textwrap
import unittest

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("check_dependency_policy", HERE / "check_dependency_policy.py")
policy = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(policy)


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(text), encoding="utf-8")


def real(coords, scope, module="x.y"):
    """A dependency-list line in the exact format Maven 3.9 writes: 'g:a:type:version:scope -- module m'."""
    return f"{coords}:{scope} -- module {module}"


class Fixture:
    """A synthetic checkout: root pom with modules, per-module dependency lists and a fake local repository."""

    def __init__(self, tmp):
        self.tmp = pathlib.Path(tmp)
        self.root = self.tmp / "repo"
        self.repo = self.tmp / "m2"
        self.exceptions = self.tmp / "no-exceptions.json"  # does not exist: no exceptions unless a test writes one
        write(self.root / "pom.xml", """\
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modules>
              <module>oa-contracts</module><module>oa-runtime</module>
            </modules></project>""")

    def deps(self, module, lines):
        write(self.root / module / "target" / "dependency-list.txt",
              "The following files have been resolved:\n" + "\n".join("   " + l for l in lines) + "\n")

    def artifact(self, group, artifact, version, licences=None, parent=None, namespace=True):
        licence_xml = ""
        if licences is not None:
            licence_xml = "<licenses>" + "".join(
                f"<license><name>{n}</name></license>" for n in licences) + "</licenses>"
        parent_xml = ""
        if parent is not None:
            parent_xml = (f"<parent><groupId>{parent[0]}</groupId><artifactId>{parent[1]}</artifactId>"
                          f"<version>{parent[2]}</version></parent>")
        ns = ' xmlns="http://maven.apache.org/POM/4.0.0"' if namespace else ""
        write(self.repo.joinpath(*group.split("."), artifact, version, f"{artifact}-{version}.pom"),
              f"<project{ns}>{parent_xml}{licence_xml}</project>")

    def exceptions_file(self, data):
        write(self.exceptions, json.dumps(data) if not isinstance(data, str) else data)

    def run(self):
        return policy.check(self.root, self.repo, self.exceptions)


class DependencyPolicyTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.fx = Fixture(self._tmp.name)
        self.fx.deps("oa-contracts", [])
        self.fx.deps("oa-runtime", [])

    def tearDown(self):
        self._tmp.cleanup()

    # ---- the rules from the original 12 tests (plain line format, unchanged assertions)

    def test_permissive_runtime_dependency_passes(self):
        self.fx.artifact("org.example", "lib", "1.0", ["The Apache Software License, Version 2.0"])
        self.fx.deps("oa-runtime", ["org.example:lib:jar:1.0:compile"])
        violations, missing = self.fx.run()
        self.assertEqual([], violations)
        self.assertEqual([], missing)

    def test_copyleft_is_allowed_in_test_scope_only(self):
        self.fx.artifact("org.example", "weak", "1.0", ["GNU Lesser General Public License"])
        self.fx.deps("oa-runtime", ["org.example:weak:jar:1.0:test"])
        self.assertEqual([], self.fx.run()[0])

        self.fx.deps("oa-runtime", ["org.example:weak:jar:1.0:compile"])
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("LGPL not allowed in compile scope", violations[0])

    def test_dual_licence_passes_when_one_licence_is_allowed(self):
        self.fx.artifact("jakarta.example", "api", "2.0",
                         ["Eclipse Public License v2.0", "GNU General Public License, version 2 with the "
                          "GNU Classpath Exception"])
        self.fx.deps("oa-runtime", ["jakarta.example:api:jar:2.0:compile"])
        self.assertEqual([], self.fx.run()[0])

    def test_gpl_without_an_allowed_alternative_fails(self):
        self.fx.artifact("org.example", "gpl", "1.0", ["GNU General Public License, version 3"])
        self.fx.deps("oa-runtime", ["org.example:gpl:jar:1.0:compile"])
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("GPL not allowed", violations[0])

    def test_unrecognised_licence_name_fails_and_says_what_to_add(self):
        self.fx.artifact("org.example", "odd", "1.0", ["Some Bespoke Licence"])
        self.fx.deps("oa-runtime", ["org.example:odd:jar:1.0:compile"])
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("unrecognised licence 'Some Bespoke Licence'", violations[0])

    def test_licence_is_inherited_from_the_parent_pom(self):
        self.fx.artifact("org.example", "parent", "3.0", ["MIT License"])
        self.fx.artifact("org.example", "child", "3.0", None, parent=("org.example", "parent", "3.0"))
        self.fx.deps("oa-runtime", ["org.example:child:jar:3.0:compile"])
        self.assertEqual([], self.fx.run()[0])

    def test_missing_pom_is_a_violation(self):
        self.fx.deps("oa-runtime", ["org.example:ghost:jar:9.9:compile"])
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("POM not found", violations[0])

    def test_contracts_with_any_main_classpath_dependency_fails(self):
        self.fx.artifact("org.example", "lib", "1.0", ["MIT License"])
        self.fx.deps("oa-contracts", ["org.example:lib:jar:1.0:compile"])
        violations, _ = self.fx.run()
        self.assertTrue(any("oa-contracts must have no main-classpath dependency" in v for v in violations),
                        violations)

    def test_contracts_may_use_test_dependencies(self):
        self.fx.artifact("org.junit.jupiter", "junit-jupiter", "5.13.0", ["Eclipse Public License v2.0"])
        self.fx.deps("oa-contracts", ["org.junit.jupiter:junit-jupiter:jar:5.13.0:test"])
        self.assertEqual([], self.fx.run()[0])

    def test_project_modules_are_not_checked_against_the_repository(self):
        self.fx.deps("oa-runtime", ["io.github.bassemzohdy.openworkflow:oa-contracts:jar:0.1.0-SNAPSHOT:compile"])
        self.assertEqual([], self.fx.run()[0])

    def test_missing_dependency_list_is_reported_as_missing_input(self):
        (self.fx.root / "oa-runtime" / "target" / "dependency-list.txt").unlink()
        _, missing = self.fx.run()
        self.assertEqual(1, len(missing))
        self.assertIn("mvn -B verify", missing[0])

    def test_main_returns_distinct_exit_codes(self):
        args = ["--root", str(self.fx.root), "--local-repo", str(self.fx.repo), "--exceptions", str(self.fx.exceptions)]
        self.fx.artifact("org.example", "lib", "1.0", ["MIT License"])
        self.fx.deps("oa-runtime", ["org.example:lib:jar:1.0:compile"])
        self.assertEqual(0, policy.main(args))
        self.fx.deps("oa-contracts", ["org.example:lib:jar:1.0:compile"])
        self.assertEqual(1, policy.main(args))
        (self.fx.root / "oa-runtime" / "target" / "dependency-list.txt").unlink()
        self.assertEqual(2, policy.main(args))

    # ---- real Maven 3.9 line format: the scope is followed by ' -- module ...'

    def test_real_format_contracts_compile_dependency_is_detected(self):
        # Regression: the scope field used to be 'compile -- module x', so this rule never fired.
        self.fx.artifact("org.example", "lib", "1.0", ["MIT License"])
        self.fx.deps("oa-contracts", [real("org.example:lib:jar:1.0", "compile", "org.example.lib [auto]")])
        violations, _ = self.fx.run()
        self.assertTrue(any("oa-contracts must have no main-classpath dependency" in v for v in violations),
                        violations)

    def test_real_format_contracts_runtime_and_provided_dependencies_are_detected(self):
        self.fx.artifact("org.example", "lib", "1.0", ["MIT License"])
        for scope in ("runtime", "provided"):
            self.fx.deps("oa-contracts", [real("org.example:lib:jar:1.0", scope)])
            violations, _ = self.fx.run()
            self.assertTrue(any("main-classpath" in v for v in violations), (scope, violations))

    def test_real_format_contracts_test_dependency_passes(self):
        self.fx.artifact("org.junit.jupiter", "junit-jupiter", "5.13.0", ["Eclipse Public License v2.0"])
        self.fx.deps("oa-contracts", [real("org.junit.jupiter:junit-jupiter:jar:5.13.0", "test")])
        self.assertEqual([], self.fx.run()[0])

    def test_real_format_scope_is_parsed_for_the_licence_rule(self):
        # A copyleft licence in test scope passes only if the scope is read correctly.
        self.fx.artifact("org.example", "weak", "1.0", ["GNU Lesser General Public License"])
        self.fx.deps("oa-runtime", [real("org.example:weak:jar:1.0", "test")])
        self.assertEqual([], self.fx.run()[0])
        self.fx.deps("oa-runtime", [real("org.example:weak:jar:1.0", "compile")])
        self.assertEqual(1, len(self.fx.run()[0]))

    def test_header_and_blank_lines_are_ignored(self):
        self.fx.artifact("org.example", "lib", "1.0", ["MIT License"])
        self.fx.deps("oa-runtime", ["", real("org.example:lib:jar:1.0", "compile")])
        self.assertEqual([], self.fx.run()[0])

    # ---- licence normalisation

    def test_normalisation_maps_known_spellings_to_one_identifier(self):
        cases = {
            "EPL 2.0": "EPL-2.0",
            "EPL-2.0": "EPL-2.0",
            "Eclipse Public License v2.0": "EPL-2.0",
            "Eclipse Public License - v 2.0": "EPL-2.0",
            "Eclipse Public License - v 1.0": "EPL-1.0",
            "EDL 1.0": "BSD",
            "Eclipse Distribution License - v 1.0": "BSD",
            "BSD-2-Clause": "BSD",
            "BSD License 3": "BSD",
            "Public Domain": "Public-Domain",
            "Public Domain, per Creative Commons CC0": "Public-Domain",
            "Universal Permissive License, Version 1.0": "UPL",
            "GPL2 w/ CPE": "GPL-2.0-with-classpath-exception",
            "GNU General Public License, version 2 with the GNU Classpath Exception":
                "GPL-2.0-with-classpath-exception",
            "GPL-2.0-with-classpath-exception": "GPL-2.0-with-classpath-exception",
            "CDDL + GPLv2 with classpath exception": "CDDL",
            "MIT-0": "MIT-0",
            "MIT License": "MIT",
            "GNU Affero General Public License v3": "AGPL",
            "GNU General Public License, version 3": "GPL",
            "GNU Lesser General Public License": "LGPL",
            "Mozilla Public License 2.0": "MPL-2.0",
            "Apache 2": "Apache-2.0",
        }
        for name, expected in cases.items():
            with self.subTest(name=name):
                self.assertEqual(expected, policy.classify_licence(name))

    def test_short_apache_name_is_only_the_exact_name(self):
        # "Apache 2" is the whole name (fizzed/maven-parent). A longer name that merely contains it is not matched.
        self.assertEqual("Apache-2.0", policy.classify_licence("Apache 2"))
        self.assertIsNone(policy.classify_licence("Apache 2 with a bespoke clause"))

    def test_jne_style_short_apache_name_inherited_from_the_parent_passes(self):
        # Mirrors com.fizzed:jne: the artifact declares no licence and its parent says "Apache 2".
        self.fx.artifact("com.example", "shortname-parent", "4.5.3", ["Apache 2"])
        self.fx.artifact("com.example", "shortname-child", "4.5.3", None,
                         parent=("com.example", "shortname-parent", "4.5.3"))
        self.fx.deps("oa-runtime", ["com.example:shortname-child:jar:4.5.3:compile"])
        self.assertEqual([], self.fx.run()[0])

    def test_unknown_names_are_not_classified(self):
        self.assertIsNone(policy.classify_licence("Some Bespoke Licence"))

    def test_mit_0_and_its_other_name_are_allowed_in_any_scope(self):
        for licence in ("MIT-0", "MIT No Attribution", "mit no attribution"):
            for scope in ("compile", "test"):
                with self.subTest(licence=licence, scope=scope):
                    self.assertEqual("MIT-0", policy.classify_licence(licence))
                    self.assertTrue(policy.allowed_for_scope("MIT-0", scope))
                    self.fx.artifact("org.example", "m0", "1.0", [licence])
                    self.fx.deps("oa-runtime", [real("org.example:m0:jar:1.0", scope)])
                    self.assertEqual([], self.fx.run()[0])

    def test_mit_look_alikes_are_not_mit_and_fail(self):
        # Names that only start with "MIT" must not be read as MIT, which is allowed.
        for licence in ("MIT-NOT", "MIT-LIKE", "MIT-0-NOT", "mit-not", "MIT-LICENSE-NOT"):
            with self.subTest(licence=licence):
                self.assertIsNone(policy.classify_licence(licence))
                self.fx.artifact("org.example", "lookalike", "1.0", [licence])
                self.fx.deps("oa-runtime", [real("org.example:lookalike:jar:1.0", "compile")])
                violations, _ = self.fx.run()
                self.assertEqual(1, len(violations), violations)
                self.assertIn(f"unrecognised licence {licence!r}", violations[0])

    def test_mit_semantics_are_unchanged_for_real_mit_names(self):
        for licence in ("MIT", "MIT License", "MIT-License", "The MIT License", "The MIT License (MIT)", "MIT/X11"):
            with self.subTest(licence=licence):
                self.assertEqual("MIT", policy.classify_licence(licence))

    def test_new_permissive_names_pass_in_any_scope(self):
        for licence in ("Public Domain, per Creative Commons CC0", "EDL 1.0", "EPL 2.0", "MIT-0"):
            with self.subTest(licence=licence):
                self.fx.artifact("org.example", "p", "1.0", [licence])
                self.fx.deps("oa-runtime", [real("org.example:p:jar:1.0", "compile")])
                self.assertEqual([], self.fx.run()[0])

    def test_test_only_licences_fail_in_compile_scope(self):
        for licence in ("CDDL + GPLv2 with classpath exception", "Universal Permissive License, Version 1.0",
                        "GNU General Public License, version 2 with the GNU Classpath Exception",
                        "Mozilla Public License 2.0"):
            with self.subTest(licence=licence):
                self.fx.artifact("org.example", "t", "1.0", [licence])
                self.fx.deps("oa-runtime", [real("org.example:t:jar:1.0", "compile")])
                self.assertEqual(1, len(self.fx.run()[0]))

    def test_test_only_licences_pass_in_test_scope(self):
        for licence in ("CDDL + GPLv2 with classpath exception", "Universal Permissive License, Version 1.0",
                        "GNU General Public License, version 2 with the GNU Classpath Exception"):
            with self.subTest(licence=licence):
                self.fx.artifact("org.example", "t", "1.0", [licence])
                self.fx.deps("oa-runtime", [real("org.example:t:jar:1.0", "test")])
                self.assertEqual([], self.fx.run()[0])

    def test_gpl_agpl_and_epl1_fail_in_every_scope(self):
        for licence in ("GNU General Public License, version 3", "GNU Affero General Public License v3",
                        "Eclipse Public License - v 1.0"):
            for scope in ("compile", "test"):
                with self.subTest(licence=licence, scope=scope):
                    self.fx.artifact("org.example", "g", "1.0", [licence])
                    self.fx.deps("oa-runtime", [real("org.example:g:jar:1.0", scope)])
                    self.assertEqual(1, len(self.fx.run()[0]))

    def test_dual_epl1_with_apache_passes_through_apache(self):
        # Mirrors io.vertx artifacts: EPL-1.0 is not allowed, but Apache-2.0 is, and either one is enough.
        self.fx.artifact("io.example", "vertx-like", "4.5", ["The Apache Software License, Version 2.0",
                                                             "Eclipse Public License - v 1.0"])
        self.fx.deps("oa-runtime", [real("io.example:vertx-like:jar:4.5", "compile")])
        self.assertEqual([], self.fx.run()[0])

    def test_pom_without_namespace_is_read(self):
        # Some published POMs (aopalliance, javax.inject, tagsoup) have no XML namespace.
        self.fx.artifact("org.example", "plain", "1.0", ["Apache License 2.0"], namespace=False)
        self.fx.deps("oa-runtime", [real("org.example:plain:jar:1.0", "compile")])
        self.assertEqual([], self.fx.run()[0])

    def test_pom_without_namespace_inherits_its_parent_licence(self):
        self.fx.artifact("org.example", "parent", "1.0", ["MIT License"], namespace=False)
        self.fx.artifact("org.example", "child", "1.0", None, parent=("org.example", "parent", "1.0"),
                         namespace=False)
        self.fx.deps("oa-runtime", [real("org.example:child:jar:1.0", "compile")])
        self.assertEqual([], self.fx.run()[0])

    # ---- reviewed exceptions

    def test_exception_allows_an_artifact_with_no_declared_licence(self):
        self.fx.artifact("org.example", "bare", "1.0", None)
        self.fx.deps("oa-runtime", [real("org.example:bare:jar:1.0", "test")])
        self.assertEqual(1, len(self.fx.run()[0]))
        self.fx.exceptions_file({"exceptions": [{
            "coordinates": "org.example:bare:1.0", "licence": "Apache License 2.0", "scopes": ["test"],
            "status": "verified", "justification": "Checked against the project source headers."}]})
        self.assertEqual([], self.fx.run()[0])

    def test_exception_scope_is_respected(self):
        self.fx.artifact("org.example", "bare", "1.0", None)
        self.fx.deps("oa-runtime", [real("org.example:bare:jar:1.0", "compile")])
        self.fx.exceptions_file({"exceptions": [{
            "coordinates": "org.example:bare:1.0", "licence": "Apache License 2.0", "scopes": ["test"],
            "status": "verified", "justification": "Test only."}]})
        violations, _ = self.fx.run()
        self.assertTrue(any("no licence declared" in v for v in violations), violations)

    def test_exception_cannot_allow_a_licence_the_scope_forbids(self):
        self.fx.artifact("org.example", "bare", "1.0", None)
        self.fx.deps("oa-runtime", [real("org.example:bare:jar:1.0", "compile")])
        self.fx.exceptions_file({"exceptions": [{
            "coordinates": "org.example:bare:1.0", "licence": "GNU General Public License, version 3",
            "scopes": ["compile"], "status": "verified", "justification": "Attempt to bypass."}]})
        violations, _ = self.fx.run()
        self.assertTrue(any("exception licence" in v and "not allowed" in v for v in violations), violations)

    def test_unverified_exception_fails(self):
        self.fx.artifact("org.example", "bare", "1.0", None)
        self.fx.deps("oa-runtime", [real("org.example:bare:jar:1.0", "test")])
        self.fx.exceptions_file({"exceptions": [{
            "coordinates": "org.example:bare:1.0", "licence": "Apache License 2.0", "scopes": ["test"],
            "status": "unverified", "justification": "Could not read a licence from the artifact."}]})
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("marked unverified", violations[0])

    def test_unknown_artifact_still_fails_with_an_exceptions_file_present(self):
        self.fx.artifact("org.example", "bare", "1.0", None)
        self.fx.deps("oa-runtime", [real("org.example:bare:jar:1.0", "compile"),
                                    real("org.example:unlisted:jar:2.0", "compile")])
        self.fx.artifact("org.example", "unlisted", "2.0", None)
        self.fx.exceptions_file({"exceptions": [{
            "coordinates": "org.example:bare:1.0", "licence": "Apache License 2.0", "scopes": ["compile"],
            "status": "verified", "justification": "Reviewed."}]})
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("org.example:unlisted:2.0", violations[0])

    def test_stale_exception_fails(self):
        self.fx.exceptions_file({"exceptions": [{
            "coordinates": "org.example:gone:1.0", "licence": "Apache License 2.0", "scopes": ["test"],
            "status": "verified", "justification": "No longer a dependency."}]})
        violations, _ = self.fx.run()
        self.assertEqual(1, len(violations))
        self.assertIn("stale exception org.example:gone:1.0", violations[0])

    def test_malformed_exceptions_file_fails(self):
        for bad in ("{not json", json.dumps({"exceptions": [{"coordinates": "a:b:1", "licence": "MIT",
                                                            "scopes": ["test"], "status": "verified"}]}),
                    json.dumps({"exceptions": [{"coordinates": "a:b", "licence": "MIT", "scopes": ["test"],
                                                "status": "verified", "justification": "x"}]}),
                    json.dumps({"exceptions": [{"coordinates": "a:b:1", "licence": "Unknown Thing",
                                                "scopes": ["test"], "status": "verified",
                                                "justification": "x"}]}),
                    json.dumps({"exceptions": [{"coordinates": "a:b:1", "licence": "MIT", "scopes": ["bogus"],
                                                "status": "verified", "justification": "x"}]})):
            with self.subTest(bad=bad[:40]):
                self.fx.exceptions_file(bad)
                entries, error = policy.load_exceptions(self.fx.exceptions)
                self.assertIsNotNone(error)
                self.assertEqual([], entries)
                violations, _ = self.fx.run()
                self.assertTrue(any("exceptions" in v or "not valid JSON" in v for v in violations), violations)

    def test_missing_exceptions_file_means_no_exceptions(self):
        self.fx.artifact("org.example", "bare", "1.0", None)
        self.fx.deps("oa-runtime", [real("org.example:bare:jar:1.0", "test")])
        self.assertFalse(self.fx.exceptions.exists())
        self.assertEqual(1, len(self.fx.run()[0]))

    def test_the_committed_exceptions_file_is_valid(self):
        entries, error = policy.load_exceptions(HERE / "dependency-policy-exceptions.json")
        self.assertIsNone(error)
        self.assertIsInstance(entries, list)


if __name__ == "__main__":
    unittest.main()
