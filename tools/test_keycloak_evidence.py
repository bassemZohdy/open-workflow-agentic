import tempfile
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from require_keycloak_evidence import CHARACTERIZATION, PROFILES, REPORTS, verify, EFFECT_RECOVERY, EFFECT_SOURCE, verify_effect_recovery


class KeycloakEvidenceTest(unittest.TestCase):
    def report(self, root, profile, missing=None, bad=None, recorded_profile=None, characterization=False):
        test_class, required = CHARACTERIZATION if characterization else PROFILES[profile]
        suite = ET.Element('testsuite')
        props = ET.SubElement(suite, 'properties')
        ET.SubElement(props, 'property', name='keycloak.profile', value=recorded_profile or profile)
        for name in required - ({missing} if missing else set()):
            case = ET.SubElement(suite, 'testcase', classname='test.identity.' + test_class, name=name)
            if bad:
                ET.SubElement(case, bad)
        folder = root / REPORTS
        folder.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(folder / f'TEST-test.identity.{test_class}.xml')

    def test_missing_sources_and_reports_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            for profile in PROFILES:
                self.assertTrue(verify(Path(tmp), profile, preflight=True))
                self.assertTrue(verify(Path(tmp), profile))

    def test_actual_required_passes_are_accepted_for_each_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            for profile in PROFILES:
                self.report(Path(tmp), profile)
                self.assertEqual([], verify(Path(tmp), profile))

    def test_skipped_failed_or_errored_cases_never_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            for tag in ('skipped', 'failure', 'error'):
                self.report(Path(tmp), 'delegation', bad=tag)
                self.assertTrue(verify(Path(tmp), 'delegation'))

    def test_incomplete_and_wrong_profile_reports_fail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.report(root, 'delegation', missing='subjectExpiryBoundsIssuedCredential')
            self.assertTrue(verify(root, 'delegation'))
            self.report(root, 'delegation', recorded_profile='standard')
            self.assertTrue(verify(root, 'delegation'))

    def test_characterization_never_substitutes_for_strict_contract(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for profile in PROFILES:
                self.report(root, profile, characterization=True)
                self.assertEqual([], verify(root, profile, characterization=True))
                self.assertTrue(verify(root, profile))

    def test_characterization_requires_all_cases_non_skipped_and_correct_profile(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for bad in ('skipped', 'failure', 'error'):
                self.report(root, 'delegation', bad=bad, characterization=True)
                self.assertTrue(verify(root, 'delegation', characterization=True))
            self.report(root, 'delegation', missing=next(iter(CHARACTERIZATION[1])), characterization=True)
            self.assertTrue(verify(root, 'delegation', characterization=True))
            self.report(root, 'delegation', recorded_profile='standard', characterization=True)
            self.assertTrue(verify(root, 'delegation', characterization=True))


class EffectRecoveryEvidenceTest(unittest.TestCase):
    def report(self, root, profile='standard', method=None):
        test_class, required = EFFECT_RECOVERY
        suite = ET.Element('testsuite', name=test_class, tests='1', failures='0', errors='0', skipped='0')
        props = ET.SubElement(suite, 'properties')
        ET.SubElement(props, 'property', name='keycloak.profile', value=profile)
        ET.SubElement(suite, 'testcase', classname=test_class, name=method or next(iter(required)))
        path = root / REPORTS / f'TEST-{test_class}.xml'
        path.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(suite).write(path)
        return path, suite

    def test_missing_source_or_execution_is_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.assertTrue(verify_effect_recovery(root, 'standard', preflight=True))
            self.assertTrue(verify_effect_recovery(root, 'standard'))
            source = root / EFFECT_SOURCE
            source.parent.mkdir(parents=True)
            source.write_text('// Explicit preflight fixture only, not execution evidence')
            self.assertEqual([], verify_effect_recovery(root, 'standard', preflight=True))
            self.assertTrue(verify_effect_recovery(root, 'standard'))

    def test_required_non_skipped_process_case_is_accepted_in_each_provider_lane(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for profile in PROFILES:
                self.report(root, profile)
                self.assertEqual([], verify_effect_recovery(root, profile))

    def test_case_skip_failure_and_error_refuse_even_with_zero_suite_counters(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for tag in ('skipped', 'failure', 'error'):
                path, suite = self.report(root)
                ET.SubElement(suite.find('testcase'), tag)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_recovery(root, 'standard'), tag)

    def test_exact_suite_class_method_and_provider_metadata_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for mutation in ('suite', 'class', 'method', 'profile'):
                path, suite = self.report(root)
                if mutation == 'suite':
                    suite.set('name', 'other.EffectCrashRecoveryIT')
                elif mutation == 'class':
                    suite.find('testcase').set('classname', 'other.EffectCrashRecoveryIT')
                elif mutation == 'method':
                    suite.find('testcase').set('name', 'aDifferentTest')
                else:
                    suite.find('./properties/property').set('value', 'delegation')
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_recovery(root, 'standard'), mutation)

    def test_suite_counters_must_be_present_consistent_and_nonnegative(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            for counter, value in (('tests', '0'), ('tests', '2'), ('skipped', '1'), ('errors', '-1'),
                                   ('failures', '1'), ('tests', 'bad'), ('skipped', None)):
                path, suite = self.report(root)
                if value is None:
                    del suite.attrib[counter]
                else:
                    suite.set(counter, value)
                ET.ElementTree(suite).write(path)
                self.assertTrue(verify_effect_recovery(root, 'standard'), (counter, value))

    def test_duplicate_cases_or_reports_are_ambiguous_and_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path, suite = self.report(root)
            case = suite.find('testcase')
            ET.SubElement(suite, 'testcase', **case.attrib)
            suite.set('tests', '2')
            ET.ElementTree(suite).write(path)
            self.assertTrue(verify_effect_recovery(root, 'standard'))
            path, _ = self.report(root)
            path.with_name('TEST-copy.' + EFFECT_RECOVERY[0] + '.xml').write_bytes(path.read_bytes())
            self.assertTrue(verify_effect_recovery(root, 'standard'))

    def test_malformed_oversized_or_empty_reports_never_count(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            path, suite = self.report(root)
            path.write_text('<testsuite>')
            self.assertTrue(verify_effect_recovery(root, 'standard'))
            path.write_text('x' * 5_000_001)
            self.assertTrue(verify_effect_recovery(root, 'standard'))
            suite.remove(suite.find('testcase'))
            suite.set('tests', '0')
            ET.ElementTree(suite).write(path)
            self.assertTrue(verify_effect_recovery(root, 'standard'))

    def test_provider_and_recovery_reports_do_not_substitute_for_each_other(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            self.report(root)
            self.assertTrue(verify(root, 'standard'))
            for path in (root / REPORTS).glob('*.xml'):
                path.unlink()
            KeycloakEvidenceTest().report(root, 'standard')
            self.assertTrue(verify_effect_recovery(root, 'standard'))


if __name__ == '__main__':
    unittest.main()
