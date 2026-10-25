"""Unit tests for tools/ci_scope.py (no git, no Maven)."""
import importlib.util
import pathlib
import unittest

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
        files = ["modules/oa-runtime/src/A.java", "apps/app-runtime/src/B.java", "modules/oa-runtime/src/C.java"]
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


if __name__ == "__main__":
    unittest.main()
