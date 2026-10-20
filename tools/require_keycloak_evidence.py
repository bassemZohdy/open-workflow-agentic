#!/usr/bin/env python3
"""Require real, passing Java Keycloak tests; an absent or skipped test is never provider evidence."""
import argparse
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

PROFILES = {
    'standard': ('KeycloakStandardIT', {
        'realIssuerSignatureAndAudienceAreVerified', 'actorlessExchangeIsRejected'}),
    'delegation': ('KeycloakDelegationIT', {
        'nativeDelegationPreservesAuthorityBounds', 'invalidActorIsRejected',
        'revokedOrExpiredGrantIsRejected', 'subjectExpiryBoundsIssuedCredential',
        'issuerKeyRotationIsHandled', 'delegationRequiresPermissionAndConsent'}),
}
SOURCE = 'apps/app-runtime/src/test/java/io/github/bassemzohdy/openworkflow/agentic/identity'
REPORTS = 'apps/app-runtime/target/failsafe-reports'
CHARACTERIZATION = ('KeycloakInteropIT', {
    'theVerifierAcceptsAUserTokenFromKeycloakAndReadsItsTenantAndScopes',
    'theWorkloadTokenOfTheAgentClientIsAnAgentOfItsOwnTenant',
    'aTokenForAnotherAudienceIsRefusedByTheVerifier',
    'theClientGetsADelegatedTokenOnlyWhereKeycloakRecordsTheActor',
    'aScopeTheUserNeverHeldIsRefusedByKeycloakAsInvalidScope',
    'anUnregisteredAudienceIsInvalidClientNotInvalidTarget',
    'anExpiredSubjectTokenIsRefusedAsInvalidRequestNotInvalidGrant',
    'aSubjectTokenOfALoggedOutSessionIsRefused',
    'theActorTokenIsIgnoredByStandardExchangeAndCheckedByDelegation',
    'theExchangedTokenHasTheScopesAskedForAndALifetimeOf300SecondsEvenForAShortSubjectToken',
})


def verify(root, profile, preflight=False, characterization=False):
    test_class, required = CHARACTERIZATION if characterization else PROFILES[profile]
    if preflight:
        source = root / SOURCE / f'{test_class}.java'
        if not source.is_file():
            return [f'Missing {source}: Claude must supply the real Java integration test; WP-102 stays partial.']
        return []
    reports = list((root / REPORTS).glob(f'TEST-*{test_class}.xml'))
    if not reports:
        return [f'No failsafe XML for {test_class}; missing/disabled tests do not establish interoperability.']
    seen, errors = set(), []
    for report in reports:
        try:
            if report.stat().st_size > 5_000_000:
                raise ValueError('report exceeds 5 MB')
            suite = ET.parse(report).getroot()
            if any(int(suite.get(name, '0')) > 0 for name in ('failures', 'errors', 'skipped')):
                errors.append(f'{report}: suite reports failures, errors or skips')
            properties = {p.get('name'): p.get('value') for p in suite.findall('./properties/property')}
            if properties.get('keycloak.profile') != profile:
                errors.append(f'{report}: keycloak.profile does not match {profile}')
            cases = suite.findall('testcase')
            if not cases:
                errors.append(f'{report}: zero test cases')
            for case in cases:
                if not case.get('classname', '').endswith('.' + test_class):
                    errors.append(f'{report}: unexpected test class')
                    continue
                name = case.get('name', '').split('[', 1)[0]
                if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                    errors.append(f'{test_class}#{name}: failed, errored or skipped')
                else:
                    seen.add(name)
        except (OSError, ET.ParseError, ValueError) as exc:
            errors.append(f'{report}: cannot read evidence ({exc})')
    errors += [f'{test_class}: required passing case missing: {name}' for name in sorted(required - seen)]
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', choices=PROFILES, required=True)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--preflight', action='store_true')
    parser.add_argument('--characterization', action='store_true',
                        help='Check known-behavior evidence only; never establishes the strict WP-102 contract')
    args = parser.parse_args()
    errors = verify(args.root, args.profile, args.preflight, args.characterization)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    kind = 'characterization (WP-102 still partial)' if args.characterization else 'strict contract'
    print(f'Keycloak {args.profile} {kind}: ' + ('Java test source present' if args.preflight else 'required Java cases passed'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
