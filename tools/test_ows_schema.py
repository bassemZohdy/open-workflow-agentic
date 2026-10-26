"""OWS 1.0.3 official-vendored-schema tests; NOT engine conformance evidence."""
import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

TOOLS = Path(__file__).resolve().parent
sys.path.insert(0, str(TOOLS))
import ows_schema
from ows_portability import load_json

ROOT = TOOLS.parent
FIXTURES = ROOT / "docs/conformance/fixtures"


class PinnedSchemaTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.schema = ROOT / ows_schema.SCHEMA_RELATIVE
        cls.validator = ows_schema.load_validator(cls.schema)

    def test_vendored_blob_integrity_and_schema_identity(self):
        self.assertEqual(ows_schema.UPSTREAM_GIT_BLOB_SHA1,
                         ows_schema.git_blob_sha1(self.schema.read_bytes()))

    def test_all_fixtures_conform_to_official_draft202012_schema(self):
        files = sorted(FIXTURES.glob("*.json"))
        self.assertGreaterEqual(len(files), 4)
        for file in files:
            with self.subTest(fixture=file.name):
                self.assertEqual([], ows_schema.validate_document(load_json(file), self.validator))

    def test_missing_required_field_and_wrong_dsl_rejected(self):
        original = load_json(FIXTURES / "sequence.json")
        missing = copy.deepcopy(original)
        del missing["document"]["namespace"]
        self.assertTrue(ows_schema.validate_document(missing, self.validator))
        wrong_version = copy.deepcopy(original)
        wrong_version["document"]["dsl"] = "1.0.0"
        self.assertIn("document.dsl", ows_schema.validate_document(wrong_version, self.validator)[0])

    def test_invalid_loop_and_extension_shapes_rejected_by_official_schema(self):
        loop = load_json(FIXTURES / "fork-and-loop.json")
        loop["do"][1]["loop"]["for"]["in"] = [1, 2, 3]
        self.assertTrue(ows_schema.validate_document(loop, self.validator))
        ext = load_json(FIXTURES / "sequence.json")
        ext["use"] = {"extensions": {"foo": {"extend": "set"}}}
        self.assertTrue(ows_schema.validate_document(ext, self.validator))

    def test_duplicate_keys_and_nonfinite_json_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            file = Path(folder) / "bad.json"
            for raw in ('{"document":1,"document":2}', '{"a":NaN}'):
                file.write_text(raw, encoding="utf-8")
                result = ows_schema.check_paths([file], self.validator)
                self.assertFalse(result[0]["valid"])

    def test_corrupted_vendor_fails_before_validation(self):
        with tempfile.TemporaryDirectory() as folder:
            copy_schema = Path(folder) / "workflow.yaml"
            copy_schema.write_bytes(self.schema.read_bytes() + b"\n")
            with self.assertRaisesRegex(Exception, "integrity mismatch"):
                ows_schema.load_validator(copy_schema)

    def test_cli_schema_validation_report(self):
        fixture = FIXTURES / "sequence.json"
        proc = subprocess.run([sys.executable, str(TOOLS / "ows_schema.py"), str(fixture)],
                              text=True, capture_output=True)
        self.assertEqual(0, proc.returncode, proc.stderr)
        payload = json.loads(proc.stdout)
        self.assertEqual("SCHEMA_VALID", payload["status"])
        self.assertEqual("SYNTAX_ONLY_NO_RUNTIME_PROOF", payload["claim"])
        self.assertTrue(payload["results"][0]["valid"])


if __name__ == "__main__":
    unittest.main()
