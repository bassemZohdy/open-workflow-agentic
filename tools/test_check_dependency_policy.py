"""Unit tests for tools/check_dependency_policy.py using synthetic repositories (no network, no Maven)."""
import importlib.util
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


class Fixture:
    """A synthetic checkout: root pom with modules, per-module dependency lists and a fake local repository."""

    def __init__(self, tmp):
        self.root = pathlib.Path(tmp, "repo")
        self.repo = pathlib.Path(tmp, "m2")
        write(self.root / "pom.xml", """\
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modules>
              <module>oa-contracts</module><module>oa-runtime</module>
            </modules></project>""")

    def deps(self, module, lines):
        write(self.root / module / "target" / "dependency-list.txt",
              "The following files have been resolved:\n" + "\n".join("   " + l for l in lines) + "\n")

    def artifact(self, group, artifact, version, licences=None, parent=None):
        licence_xml = ""
        if licences is not None:
            licence_xml = "<licenses>" + "".join(
                f"<license><name>{n}</name></license>" for n in licences) + "</licenses>"
        parent_xml = ""
        if parent is not None:
            parent_xml = (f"<parent><groupId>{parent[0]}</groupId><artifactId>{parent[1]}</artifactId>"
                          f"<version>{parent[2]}</version></parent>")
        write(self.repo.joinpath(*group.split("."), artifact, version, f"{artifact}-{version}.pom"),
              f'<project xmlns="http://maven.apache.org/POM/4.0.0">{parent_xml}{licence_xml}</project>')

    def run(self):
        return policy.check(self.root, self.repo)


class DependencyPolicyTest(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.fx = Fixture(self._tmp.name)
        self.fx.deps("oa-contracts", [])
        self.fx.deps("oa-runtime", [])

    def tearDown(self):
        self._tmp.cleanup()

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
        self.fx.artifact("org.example", "lib", "1.0", ["MIT License"])
        self.fx.deps("oa-runtime", ["org.example:lib:jar:1.0:compile"])
        self.assertEqual(0, policy.main(["--root", str(self.fx.root), "--local-repo", str(self.fx.repo)]))
        self.fx.deps("oa-contracts", ["org.example:lib:jar:1.0:compile"])
        self.assertEqual(1, policy.main(["--root", str(self.fx.root), "--local-repo", str(self.fx.repo)]))
        (self.fx.root / "oa-runtime" / "target" / "dependency-list.txt").unlink()
        self.assertEqual(2, policy.main(["--root", str(self.fx.root), "--local-repo", str(self.fx.repo)]))


if __name__ == "__main__":
    unittest.main()
