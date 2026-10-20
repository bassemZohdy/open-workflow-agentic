"""Manual DATA process proof requires every actual window, current lane and all earlier strict gates."""
import contextlib
import io
import tempfile
from pathlib import Path
from unittest import TestCase, mock
import xml.etree.ElementTree as ET
from require_keycloak_evidence import (MANUAL_RECOVERY, MANUAL_SOURCE_FILES, PROFILES, REPORTS,
                                      main, verify_effect_manual_recovery)
import test_keycloak_evidence as legacy
import test_effect_audit_evidence as audit
import test_effect_http_evidence as http


class EffectManualEvidenceTest(TestCase):
    def report(self, root, profile='standard'):
        test_class, required = MANUAL_RECOVERY
        suite = ET.Element('testsuite', name=test_class, tests=str(len(required)), failures='0', errors='0', skipped='0')
        props = ET.SubElement(suite, 'properties')
        ET.SubElement(props, 'property', name='keycloak.profile', value=profile)
        for name in sorted(required):
            ET.SubElement(suite, 'testcase', classname=test_class, name=name)
        path = root / REPORTS / f'TEST-{test_class}.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(path)
        return path, suite

    def test_preflight_requires_worker_test_database_and_schema_without_proving_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in MANUAL_SOURCE_FILES:
                self.assertTrue(verify_effect_manual_recovery(root, 'standard', preflight=True))
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('// preflight fixture only')
            self.assertEqual([], verify_effect_manual_recovery(root, 'standard', preflight=True))
            self.assertTrue(verify_effect_manual_recovery(root, 'standard'))

    def test_every_window_outcome_and_current_profile_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for profile in PROFILES:
                self.report(root, profile)
                self.assertEqual([], verify_effect_manual_recovery(root, profile))
            for omitted in MANUAL_RECOVERY[1]:
                path, suite = self.report(root)
                for case in suite.findall('testcase'):
                    if case.get('name') == omitted:
                        suite.remove(case)
                suite.set('tests', '7')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_manual_recovery(root, 'standard'), omitted)

    def test_case_skips_failures_errors_and_inconsistent_counters_refuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for tag in ('skipped', 'error', 'failure'):
                path, suite = self.report(root)
                ET.SubElement(suite.find('testcase'), tag)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_manual_recovery(root, 'standard'), tag)
            for key, value in [('tests', '7'), ('skipped', '1'), ('errors', '-1'), ('failures', '1'), ('tests', 'bad')]:
                path, suite = self.report(root)
                suite.set(key, value)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_manual_recovery(root, 'standard'), (key, value))

    def test_exact_class_suite_profile_and_unambiguous_cases_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for mutation in ('suite', 'class', 'profile', 'duplicate'):
                path, suite = self.report(root)
                if mutation == 'suite':
                    suite.set('name', 'other.' + MANUAL_RECOVERY[0])
                elif mutation == 'class':
                    suite.find('testcase').set('classname', 'other.' + MANUAL_RECOVERY[0])
                elif mutation == 'profile':
                    suite.find('./properties/property').set('value', 'delegation')
                else:
                    ET.SubElement(suite, 'testcase', **suite.find('testcase').attrib)
                    suite.set('tests', '9')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_manual_recovery(root, 'standard'), mutation)
            path, _ = self.report(root)
            path.with_name('TEST-copy.' + MANUAL_RECOVERY[0] + '.xml').write_bytes(path.read_bytes())
            self.assertTrue(verify_effect_manual_recovery(root, 'standard'))

    def test_malformed_oversized_or_empty_evidence_never_counts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for content in ('<testsuite>', 'x' * 5_000_001, '<testsuite tests="0"/>'):
                path, _ = self.report(root)
                path.write_text(content)
                self.assertTrue(verify_effect_manual_recovery(root, 'standard'))

    def test_cli_preserves_real_provider_core_audit_and_http_gates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            args = ['require_keycloak_evidence.py', '--root', str(root), '--profile', 'standard', '--effect-manual-recovery']
            self.report(root)
            fixtures = [None, lambda: legacy.KeycloakEvidenceTest().report(root, 'standard'),
                        lambda: legacy.EffectRecoveryEvidenceTest().report(root, 'standard'),
                        lambda: audit.EffectAuditEvidenceTest().report(root, 'standard'),
                        lambda: http.EffectHttpEvidenceTest().report(root, 'standard')]
            for step, fixture in enumerate(fixtures):
                if fixture:
                    fixture()
                with mock.patch('sys.argv', args), contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0 if step == 4 else 1, main())
