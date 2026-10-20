"""Actual HTTP evidence cannot be replaced by spies, passing provider tests or earlier process suites."""
import contextlib
import io
import tempfile
from pathlib import Path
from unittest import TestCase, mock
import xml.etree.ElementTree as ET
from require_keycloak_evidence import (HTTP_RECOVERY, HTTP_SOURCE_FILES, PROFILES, REPORTS,
                                      main, verify_effect_http_recovery)
import test_keycloak_evidence as legacy
import test_effect_audit_evidence as audit


class EffectHttpEvidenceTest(TestCase):
    def report(self, root, profile='standard'):
        test_class, required = HTTP_RECOVERY
        suite = ET.Element('testsuite', name=test_class, tests=str(len(required)), failures='0', errors='0', skipped='0')
        props = ET.SubElement(suite, 'properties')
        ET.SubElement(props, 'property', name='keycloak.profile', value=profile)
        for name in sorted(required):
            ET.SubElement(suite, 'testcase', classname=test_class, name=name)
        path = root / REPORTS / f'TEST-{test_class}.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(path)
        return path, suite

    def test_preflight_presence_does_not_prove_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in HTTP_SOURCE_FILES:
                self.assertTrue(verify_effect_http_recovery(root, 'standard', preflight=True))
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('// preflight fixture only')
            self.assertEqual([], verify_effect_http_recovery(root, 'standard', preflight=True))
            self.assertTrue(verify_effect_http_recovery(root, 'standard'))

    def test_every_actual_case_and_current_lane_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for profile in PROFILES:
                self.report(root, profile)
                self.assertEqual([], verify_effect_http_recovery(root, profile))
            for omitted in HTTP_RECOVERY[1]:
                path, suite = self.report(root)
                for case in suite.findall('testcase'):
                    if case.get('name') == omitted:
                        suite.remove(case)
                suite.set('tests', '6')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_http_recovery(root, 'standard'), omitted)

    def test_skips_failures_errors_and_inconsistent_counters_refuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for tag in ('skipped', 'error', 'failure'):
                path, suite = self.report(root)
                ET.SubElement(suite.find('testcase'), tag)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_http_recovery(root, 'standard'), tag)
            for key, value in [('tests', '6'), ('skipped', '1'), ('errors', '-1'), ('failures', '1'), ('tests', 'bad')]:
                path, suite = self.report(root)
                suite.set(key, value)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_http_recovery(root, 'standard'), (key, value))

    def test_exact_identity_and_unambiguous_reports_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for mutation in ('suite', 'class', 'profile', 'duplicate'):
                path, suite = self.report(root)
                if mutation == 'suite':
                    suite.set('name', 'other.' + HTTP_RECOVERY[0])
                elif mutation == 'class':
                    suite.find('testcase').set('classname', 'other.' + HTTP_RECOVERY[0])
                elif mutation == 'profile':
                    suite.find('./properties/property').set('value', 'delegation')
                else:
                    ET.SubElement(suite, 'testcase', **suite.find('testcase').attrib)
                    suite.set('tests', '8')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_http_recovery(root, 'standard'), mutation)
            path, _ = self.report(root)
            path.with_name('TEST-copy.' + HTTP_RECOVERY[0] + '.xml').write_bytes(path.read_bytes())
            self.assertTrue(verify_effect_http_recovery(root, 'standard'))

    def test_malformed_oversized_and_empty_reports_refuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for content in ('<testsuite>', 'x' * 5_000_001, '<testsuite tests="0"/>'):
                path, _ = self.report(root)
                path.write_text(content)
                self.assertTrue(verify_effect_http_recovery(root, 'standard'))

    def test_cli_preserves_provider_core_and_audit_process_gates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            args = ['require_keycloak_evidence.py', '--root', str(root), '--profile', 'standard', '--effect-http-recovery']
            self.report(root)
            fixtures = [None, lambda: legacy.KeycloakEvidenceTest().report(root, 'standard'),
                        lambda: legacy.EffectRecoveryEvidenceTest().report(root, 'standard'),
                        lambda: audit.EffectAuditEvidenceTest().report(root, 'standard')]
            for step, fixture in enumerate(fixtures):
                if fixture:
                    fixture()
                with mock.patch('sys.argv', args), contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0 if step == 3 else 1, main())
