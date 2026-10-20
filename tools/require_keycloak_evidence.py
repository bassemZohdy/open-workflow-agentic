#!/usr/bin/env python3
"""Require passing real Java provider tests and optional process recovery; missing/skipped tests never count."""
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
EFFECT_SOURCE = 'apps/app-runtime/src/test/java/io/github/bassemzohdy/openworkflow/agentic/effect/EffectCrashRecoveryIT.java'
EFFECT_RECOVERY = ('io.github.bassemzohdy.openworkflow.agentic.effect.EffectCrashRecoveryIT', {
    'processDeathAfterTargetCommitDoesNotDuplicateEffectOrCompensation'})
AUDIT_RECOVERY = ('io.github.bassemzohdy.openworkflow.agentic.effect.EffectAuditProcessRecoveryIT', {
    'deathBeforeSourceCommitRollsBackAllFactsAndFreshWorkersCreateOneSource',
    'deathAfterSourceCommitRetainsOriginalDraftAcrossFreshWorkers',
    'deathBeforeLocalAckCommitLeavesPendingAndFreshWorkersAcknowledgeSameReceipt',
    'deathAfterLocalAckCommitRetainsAcknowledgmentAcrossFreshWorkers'})
AUDIT_SOURCE_FILES = tuple('apps/app-runtime/src/test/java/io/github/bassemzohdy/openworkflow/agentic/effect/' + name
                          for name in ('EffectAuditProcessRecoveryIT.java', 'EffectAuditProcessWorker.java', 'EffectAuditTestDatabase.java'))
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
    return _reports(root, profile, test_class, required)


def verify_effect_recovery(root, profile, preflight=False):
    """Require the actual process-death charge/refund test alongside the selected real provider."""
    if preflight:
        source = root / EFFECT_SOURCE
        return [] if source.is_file() else [f'Missing process recovery test source: {source}']
    return _reports(root, profile, *EFFECT_RECOVERY, strict=True)


def verify_effect_audit_recovery(root, profile, preflight=False):
    """Require all four actual source/ACK before/after process-death windows, never fixture presence alone."""
    if preflight:
        return [f'Missing audit process recovery source: {root / name}'
                for name in AUDIT_SOURCE_FILES if not (root / name).is_file()]
    return _reports(root, profile, *AUDIT_RECOVERY, strict=True)


def _reports(root, profile, test_class, required, strict=False):
    reports = list((root / REPORTS).glob(f'TEST-*{test_class}.xml'))
    if not reports:
        return [f'No failsafe XML for {test_class}; missing/disabled tests do not establish execution evidence.']
    seen, errors = set(), []
    if strict and len(reports) != 1:
        errors.append(f'{test_class}: expected one unambiguous report')
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
            if strict:
                if suite.tag != 'testsuite' or suite.get('name') != test_class:
                    errors.append(f'{report}: unexpected suite identity')
                counters = {name: int(suite.attrib[name]) for name in ('tests', 'failures', 'errors', 'skipped')}
                if counters['tests'] != len(cases) or any(value < 0 for value in counters.values()):
                    errors.append(f'{report}: inconsistent suite counters')
                identities = [(case.get('classname'), case.get('name')) for case in cases]
                if len(identities) != len(set(identities)):
                    errors.append(f'{report}: duplicate test case identity')
            for case in cases:
                classname = case.get('classname', '')
                valid_class = classname == test_class if strict else classname.endswith('.' + test_class)
                if not valid_class:
                    errors.append(f'{report}: unexpected test class')
                    continue
                name = case.get('name', '').split('[', 1)[0]
                if any(case.find(tag) is not None for tag in ('failure', 'error', 'skipped')):
                    errors.append(f'{test_class}#{name}: failed, errored or skipped')
                else:
                    seen.add(name)
        except (OSError, ET.ParseError, ValueError, KeyError) as exc:
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
    parser.add_argument('--effect-recovery', action='store_true',
                        help='Also require non-skipped real process-death effect/compensation recovery evidence')
    parser.add_argument('--effect-audit-recovery', action='store_true',
                        help='Also require all source/ACK commit-death windows AND the original effect recovery case')
    args = parser.parse_args()
    errors = verify(args.root, args.profile, args.preflight, args.characterization)
    if args.effect_recovery or args.effect_audit_recovery:
        errors += verify_effect_recovery(args.root, args.profile, args.preflight)
    if args.effect_audit_recovery:
        errors += verify_effect_audit_recovery(args.root, args.profile, args.preflight)
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    kind = 'characterization (WP-102 still partial)' if args.characterization else 'strict contract'
    print(f'Keycloak {args.profile} {kind}: ' + ('Java test source present' if args.preflight else 'required Java cases passed'))
    if args.effect_recovery or args.effect_audit_recovery:
        print('Effect process recovery: ' + ('Java test source present' if args.preflight else 'required Java case passed'))
    if args.effect_audit_recovery:
        print('Effect audit process recovery: ' + ('Java worker/test sources present' if args.preflight else 'all four Java commit-death cases passed'))
    return 0


if __name__ == '__main__':
    sys.exit(main())
