"""A green provider/core run cannot substitute for the four new process commit windows."""
import contextlib
import io
import tempfile
from pathlib import Path
from unittest import TestCase, mock
import xml.etree.ElementTree as ET
from require_keycloak_evidence import (AUDIT_RECOVERY, AUDIT_SOURCE_FILES, PROFILES, REPORTS,
                                      main, verify_effect_audit_recovery)
import test_keycloak_evidence as legacy


class EffectAuditEvidenceTest(TestCase):
    def report(self, root, profile='standard'):
        test_class, required = AUDIT_RECOVERY
        suite = ET.Element('testsuite', name=test_class, tests=str(len(required)), failures='0', errors='0', skipped='0')
        props = ET.SubElement(suite, 'properties')
        ET.SubElement(props, 'property', name='keycloak.profile', value=profile)
        for name in sorted(required):
            ET.SubElement(suite, 'testcase', classname=test_class, name=name)
        path = root / REPORTS / f'TEST-{test_class}.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(path)
        return path, suite

    def test_preflight_requires_worker_fixture_and_test_but_not_execution(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for name in AUDIT_SOURCE_FILES:
                self.assertTrue(verify_effect_audit_recovery(root, 'standard', preflight=True))
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('// preflight fixture only')
            self.assertEqual([], verify_effect_audit_recovery(root, 'standard', preflight=True))
            self.assertTrue(verify_effect_audit_recovery(root, 'standard'))

    def test_all_four_real_case_names_and_current_lane_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for profile in PROFILES:
                self.report(root, profile)
                self.assertEqual([], verify_effect_audit_recovery(root, profile))
            for omitted in AUDIT_RECOVERY[1]:
                path, suite = self.report(root)
                for case in suite.findall('testcase'):
                    if case.get('name') == omitted:
                        suite.remove(case)
                suite.set('tests', '3')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_audit_recovery(root, 'standard'), omitted)

    def test_case_skip_failure_error_and_suite_counters_refuse(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for tag in ('skipped', 'error', 'failure'):
                path, suite = self.report(root)
                ET.SubElement(suite.find('testcase'), tag)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_audit_recovery(root, 'standard'), tag)
            for key, value in [('tests', '0'), ('tests', '3'), ('skipped', '1'), ('errors', '-1'), ('failures', '1')]:
                path, suite = self.report(root)
                suite.set(key, value)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_audit_recovery(root, 'standard'), (key, value))

    def test_exact_suite_class_and_provider_not_just_similar_suffix(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for mutation in ('suite', 'class', 'profile'):
                path, suite = self.report(root)
                if mutation == 'suite':
                    suite.set('name', 'other.' + AUDIT_RECOVERY[0])
                elif mutation == 'class':
                    suite.find('testcase').set('classname', 'other.' + AUDIT_RECOVERY[0])
                else:
                    suite.find('./properties/property').set('value', 'delegation')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_audit_recovery(root, 'standard'), mutation)

    def test_duplicate_case_and_report_are_ambiguous(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path, suite = self.report(root)
            ET.SubElement(suite, 'testcase', **suite.find('testcase').attrib)
            suite.set('tests', '5')
            ET.ElementTree(suite).write(path)
            self.assertTrue(verify_effect_audit_recovery(root, 'standard'))
            path, _ = self.report(root)
            path.with_name('TEST-copy.' + AUDIT_RECOVERY[0] + '.xml').write_bytes(path.read_bytes())
            self.assertTrue(verify_effect_audit_recovery(root, 'standard'))

    def test_cli_new_flag_cannot_drop_original_process_or_real_provider_gates(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            args = ['require_keycloak_evidence.py', '--root', str(root), '--profile', 'standard', '--effect-audit-recovery']
            self.report(root)
            with mock.patch('sys.argv', args), contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, main())
            legacy.KeycloakEvidenceTest().report(root, 'standard')
            with mock.patch('sys.argv', args), contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(1, main())
            legacy.EffectRecoveryEvidenceTest().report(root, 'standard')
            with mock.patch('sys.argv', args), contextlib.redirect_stderr(io.StringIO()), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(0, main())
