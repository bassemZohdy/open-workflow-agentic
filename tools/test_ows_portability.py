"""Dependency-free static WP-605 fixture regression tests (NOT runtime conformance)."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "tools" / "ows_portability.py"
FIXTURES = ROOT / "docs" / "conformance" / "fixtures"
_spec = importlib.util.spec_from_file_location("ows_portability", SCRIPT)
mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(mod)


class InventoryTests(unittest.TestCase):
    def test_all_fixtures_have_explicit_features_and_lossless_json(self):
        names = sorted(FIXTURES.glob("*.json"))
        self.assertGreaterEqual(len(names), 3)
        for fixture in names:
            with self.subTest(fixture=fixture.name):
                original = mod.load_json(fixture)
                features = mod.inventory(original)
                self.assertTrue(features)
                self.assertTrue(mod.compare_roundtrip(original, json.loads(mod.canonical_bytes(original))))
                self.assertEqual(features, sorted(set(features)))

    def test_nested_structure_inventory(self):
        obj = mod.load_json(FIXTURES / "fork-and-loop.json")
        self.assertEqual(["for", "fork", "fork:compete", "set"], mod.inventory(obj))

    def test_official_1_0_3_for_expression_and_extension_list_shapes(self):
        doc = mod.load_json(FIXTURES / "fork-and-loop.json")
        self.assertEqual(".items", doc["do"][1]["loop"]["for"]["in"])
        doc["do"][1]["loop"]["for"]["in"] = [1, 2, 3]
        with self.assertRaises(mod.FixtureError):
            mod.inventory(doc)
        doc = mod.load_json(FIXTURES / "sequence.json")
        doc["use"] = {"extensions": [{"custom-step": {"description": "pass"}}]}
        self.assertIn("extension:custom-step", mod.inventory(doc))
        for invalid in ({"custom-step": {}}, [{"x": {}, "y": {}}], [{"x": {}}, {"x": {}}]):
            doc["use"]["extensions"] = invalid
            with self.assertRaises(mod.FixtureError):
                mod.inventory(doc)

    def test_unknown_task_and_mixed_task_kinds_fail_closed(self):
        doc = mod.load_json(FIXTURES / "sequence.json")
        doc["do"][0]["begin"] = {"javascript": "println()"}
        with self.assertRaises(mod.FixtureError):
            mod.inventory(doc)
        doc["do"][0]["begin"] = {"set": {}, "call": "http"}
        with self.assertRaises(mod.FixtureError):
            mod.inventory(doc)

    def test_duplicate_json_properties_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder) / "bad.json"
            p.write_text('{"a":1,"a":2}', encoding="utf-8")
            with self.assertRaises(mod.FixtureError):
                mod.load_json(p)

    def test_nested_duplicate_names_refused(self):
        doc = mod.load_json(FIXTURES / "sequence.json")
        doc["do"].append(doc["do"][0])
        with self.assertRaises(mod.FixtureError):
            mod.inventory(doc)

    def test_mismatch_and_unknown_capability_do_not_claim_portability(self):
        doc = mod.load_json(FIXTURES / "sequence.json")
        other = json.loads(json.dumps(doc))
        other["do"][0]["begin"]["set"]["status"] = "changed"
        self.assertFalse(mod.compare_roundtrip(doc, other))
        # Python equality alone incorrectly treats JSON true and 1 as equal.
        other = json.loads(json.dumps(doc))
        other["do"][0]["begin"]["set"]["status"] = True
        doc["do"][0]["begin"]["set"]["status"] = 1
        self.assertFalse(mod.compare_roundtrip(doc, other))
        self.assertEqual({"supported": [], "unsupported": [], "unverified": ["set"]},
                         mod.evaluate(["set"], {"LangGraph": {}})["LangGraph"])
        self.assertEqual(["set"], mod.evaluate(["set"], {"runtime": {"unsupported": ["set"]}})["runtime"]["unsupported"])
        with self.assertRaises(mod.FixtureError):
            mod.evaluate(["set"], {"runtime": {"supported": ["set"], "unsupported": ["set"]}})

    def test_cli_strict_requires_explicit_support(self):
        fixture = FIXTURES / "sequence.json"
        capability = ROOT / "docs" / "conformance" / "runtime-capabilities.json"
        cmd = [sys.executable, str(SCRIPT), str(fixture), "--capabilities", str(capability)]
        result = subprocess.run(cmd, text=True, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        payload = json.loads(result.stdout)
        self.assertEqual("STATIC_INVENTORY_ONLY", payload["claim"])
        self.assertTrue(all(v["unverified"] for v in payload["runtimes"].values()))
        strict = subprocess.run(cmd + ["--require-verified"], text=True, capture_output=True)
        self.assertEqual(1, strict.returncode)
        missing = subprocess.run([sys.executable, str(SCRIPT), str(fixture), "--require-verified"],
                                 text=True, capture_output=True)
        self.assertNotEqual(0, missing.returncode)
        with tempfile.TemporaryDirectory() as folder:
            empty = Path(folder) / "empty.json"
            empty.write_text("{}", encoding="utf-8")
            no_providers = subprocess.run([sys.executable, str(SCRIPT), str(fixture),
                                           "--capabilities", str(empty), "--require-verified"],
                                          text=True, capture_output=True)
            self.assertEqual(1, no_providers.returncode)

    def test_cli_exported_drift_fails(self):
        fixture = FIXTURES / "sequence.json"
        with tempfile.TemporaryDirectory() as folder:
            exported = Path(folder) / "exported.json"
            obj = mod.load_json(fixture)
            obj["do"] = list(reversed(obj["do"]))
            exported.write_text(json.dumps(obj), encoding="utf-8")
            result = subprocess.run([sys.executable, str(SCRIPT), str(fixture),
                                     "--exported", str(exported)], capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertEqual("LOSS_DETECTED", json.loads(result.stdout)["roundtrip"])


if __name__ == "__main__":
    unittest.main()
