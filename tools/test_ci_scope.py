"""Unit tests for tools/ci_scope.py (no git, no Maven)."""
import importlib.util
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import call, patch

HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("ci_scope", HERE / "ci_scope.py")
scope = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scope)


class CiScopeTest(unittest.TestCase):

    def test_prose_only_skips_maven(self):
        self.assertEqual(("docs", []), scope.decide(["docs/handoffs/claude/X.md", "README.md"]))

    def test_generated_document_sources_are_never_prose(self):
        self.assertEqual("full", scope.decide(["docs/plan/work-packages.json"])[0])
        self.assertEqual("full", scope.decide(["docs/requirements/test-status.json"])[0])
        self.assertEqual("full", scope.decide(["docs/handoffs/x.md", "docs/plan/work-packages.json"])[0])

    def test_source_in_one_module_builds_that_module(self):
        self.assertEqual(("modules", ["modules/oa-runtime"]),
                         scope.decide(["modules/oa-runtime/src/main/java/A.java", "docs/handoffs/x.md"]))

    def test_several_modules_are_listed_once_sorted(self):
        files = ["modules/oa-runtime/src/main/java/A.java", "apps/app-runtime/src/main/java/B.java",
                 "modules/oa-runtime/src/main/java/C.java"]
        self.assertEqual(("modules", ["apps/app-runtime", "modules/oa-runtime"]), scope.decide(files))

    def test_build_files_force_a_full_build(self):
        for f in ["pom.xml", "modules/oa-runtime/pom.xml", ".github/workflows/build.yml", "tools/ci_scope.py",
                  "docker-compose.yml", "Makefile"]:
            self.assertEqual("full", scope.decide([f])[0], f)

    def test_unknown_or_empty_means_full(self):
        self.assertEqual("full", scope.decide([])[0])
        self.assertEqual("full", scope.decide([""])[0])
        self.assertEqual("full", scope.decide(["somewhere/else.txt"])[0])

    def test_executable_document_fixtures_require_full_build(self):
        for path in ["docs/handoffs/probe.java", "docs/fixtures/schema.sql", "docs/examples/run.yml",
                     "docs/handoffs/evidence.json", "docs/plan", "docs/requirements"]:
            self.assertEqual(("full", []), scope.decide([path]), path)

    def test_module_build_and_unknown_paths_require_full_build(self):
        for path in ["modules/oa-runtime/.mvn/jvm.config", "modules/oa-runtime/build.sh",
                     "apps/app-runtime/Dockerfile.native", "modules/unsafe name/src/A.java"]:
            self.assertEqual(("full", []), scope.decide([path]), path)

    def test_acceptance_test_changes_require_full_build(self):
        self.assertEqual(("full", []), scope.decide(["modules/oa-testkit/src/test/java/AcceptanceTest.java"]))

    def test_acceptance_gate_and_unknown_source_roots_require_full_build(self):
        for path in ["modules/oa-testkit/src/main/java/acceptance/AcceptanceStatus.java",
                     "modules/oa-runtime/src/test/java/A.java", "modules/oa-runtime/src/it/java/A.java"]:
            self.assertEqual(("full", []), scope.decide([path]), path)

    def test_failed_diff_falls_back_to_full(self):
        with patch.object(scope, "changed_files", side_effect=subprocess.CalledProcessError(1, "git")):
            with patch("builtins.print") as output:
                self.assertEqual(0, scope.main(["ci_scope.py", "missing-base"]))
            self.assertEqual([call("mode=full"), call("modules=")], output.call_args_list)

    def test_renamed_build_file_keeps_original_path_in_scope(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True, text=True)
            git("init")
            git("config", "user.email", "scope-test@example.invalid")
            git("config", "user.name", "Scope Test")
            (root / "pom.xml").write_text("<project/>\n", encoding="utf-8")
            git("add", ".")
            git("commit", "-m", "baseline")
            base = git("rev-parse", "HEAD").stdout.strip()
            (root / "docs").mkdir()
            git("mv", "pom.xml", "docs/pom.md")
            git("commit", "-m", "rename")
            result = subprocess.run([sys.executable, str(HERE / "ci_scope.py"), base],
                                    cwd=root, check=True, capture_output=True, text=True)
            self.assertEqual("mode=full\nmodules=\n", result.stdout)


if __name__ == "__main__":
    unittest.main()
