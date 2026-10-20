import tempfile
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET
from require_keycloak_evidence import CHARACTERIZATION, PROFILES, REPORTS, verify


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


if __name__ == '__main__':
    unittest.main()
