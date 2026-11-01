#!/usr/bin/env python3
"""Check actual metric tests, Prometheus scenarios and dashboard query syntax; no deployment/approval claim."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'deploy' / 'observability'
METRICS = {'agentic_outbox_unpublished', 'agentic_outbox_failed',
           'agentic_outbox_unpublished_oldest_age_seconds',
           'agentic_outbox_observation_healthy', 'agentic_outbox_observation_age_seconds'}
METHODS = {'failedFirstObservationIsUnknownAndFailureAttemptsAreCachedAcrossAllGauges',
           'successfulSampleIsRetainedButMarkedUnhealthyAndAgesUntilRecovery',
           'aBlockedObservationDoesNotStartASecondDatabaseReadOnConcurrentScrapes',
           'realPrometheusRegistryExportsUnknownFailureAndRecoveryWithExactNamesAndNoPrivateLabels'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--promtool', required=True)
    args = parser.parse_args()
    promtool = str(Path(args.promtool).resolve())
    version = subprocess.check_output([promtool, '--version'], text=True)
    if 'version 3.15.0 ' not in version:
        raise ValueError('Expected pinned promtool3.15.0')
    assets = {p.name: json.loads(p.read_text(encoding='utf-8')) for p in ASSETS.glob('*.json')}
    if set(assets) != {'prometheus.example.json', 'outbox-rules.json', 'outbox-rules.test.json', 'outbox-dashboard.json'}:
        raise ValueError('Unexpected or missing monitoring assets')
    scenarios = assets['outbox-rules.test.json']['tests']
    if len(scenarios) != 8 or len({s['name'] for s in scenarios}) != 8:
        raise ValueError('Require eight distinct actual rule scenarios')
    rules = assets['outbox-rules.json']['groups'][0]['rules']
    if len(rules) != 5 or len({r['alert'] for r in rules}) != 5:
        raise ValueError('Require five distinct operational alerts')
    for scenario in scenarios:
        if {r['alertname'] for r in scenario['alert_rule_test']} != {r['alert'] for r in rules}:
            raise ValueError('Each scenario must check every alert, including expected non-firing alerts')
    if sum(len(s.get('promql_expr_test', [])) for s in scenarios) != 9:
        raise ValueError('Require eight evaluated dashboard-value cases and the separate NaN comparison')
    for rule in rules:
        if rule['labels'] != {'severity': 'warning', 'owner': 'runtime-operations'}:
            raise ValueError('These candidates are owned warnings, not an approved paging/SLO policy')
        if not rule['annotations']['runbook_url'].startswith('https://github.com/bassemZohdy/open-workflow-agentic/blob/main/docs/operations-observability.md#'):
            raise ValueError('Missing owned runbook')
    dashboard = assets['outbox-dashboard.json']
    queries = [t['expr'] for p in dashboard['panels'] for t in p['targets']]
    if len(queries) != 6 or len({p['id'] for p in dashboard['panels']}) != 6:
        raise ValueError('Require six unique dashboard panels')
    # These are database-wide observations repeated by replicas. Summing them would double-count rows.
    if any('sum(' in q or 'vector(0)' in q or 'or 0' in q for q in queries):
        raise ValueError('Do not add replica observations or turn missing telemetry into healthy zeros')
    all_queries = '\n'.join(queries + [r['expr'] for r in rules])
    import re
    if set(re.findall(r'agentic_[a-z_]+', all_queries)) != METRICS:
        raise ValueError('Unexpected or missing metric names')
    for q in queries[3:]:
        if 'observation_healthy' not in q or '== 1' not in q or 'up{' not in q or 'observation_age_seconds' not in q or '<= 120' not in q:
            raise ValueError('Queue views require a reachable target and healthy observation within the candidate freshness window')
    runbook = (ROOT / 'docs' / 'operations-observability.md').read_text(encoding='utf-8')
    for rule in rules:
        anchor = rule['annotations']['runbook_url'].rsplit('#', 1)[1]
        if '<a id="' + anchor + '"></a>' not in runbook:
            raise ValueError('Missing runbook anchor ' + anchor)

    subprocess.run([promtool, 'check', 'config', 'prometheus.example.json'], cwd=ASSETS, check=True)
    subprocess.run([promtool, 'test', 'rules', 'outbox-rules.test.json'], cwd=ASSETS, check=True)
    target = ROOT / 'target'
    target.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='gpt-observability-', dir=target) as temporary:
        directory = Path(temporary).resolve()
        if not directory.is_relative_to(target.resolve()):
            raise ValueError('Temporary validation must stay in owned target')
        query_rules = {'groups': [{'name': 'dashboard-syntax', 'rules': [
            {'record': f'agentic_dashboard_query_{i}', 'expr': q.replace('$job', 'open-workflow-agentic').replace('$instance', 'runtime:8080')}
            for i, q in enumerate(queries)]}]}
        path = directory / 'dashboard-queries.json'
        path.write_text(json.dumps(query_rules), encoding='utf-8')
        subprocess.run([promtool, 'check', 'rules', str(path)], check=True)

    reports = list((ROOT / 'modules' / 'oa-runtime' / 'target' / 'surefire-reports').glob('TEST-*OutboxMetricsTest.xml'))
    if len(reports) != 1:
        raise ValueError('Missing or ambiguous actual OutboxMetricsTest XML')
    suite = ET.parse(reports[0]).getroot()
    cases = suite.findall('testcase')
    if suite.get('tests') != '4' or len(cases) != 4 or {c.get('name') for c in cases} != METHODS:
        raise ValueError('Require four exact nonzero metric test cases')
    if any(suite.get(k) != '0' for k in ('failures', 'errors', 'skipped')) or any(
            c.find(k) is not None for c in cases for k in ('failure', 'error', 'skipped')):
        raise ValueError('Failed/skipped metric cases do not prove the monitoring contract')
    samples = ROOT / 'modules' / 'oa-runtime' / 'target' / 'observability'
    sample_hashes = {}
    for name in ('metrics-initial.prom', 'metrics-recovered.prom'):
        path = samples / name
        raw = path.read_bytes()
        if not raw:
            raise ValueError('Missing actual Prometheus registry output')
        subprocess.run([promtool, 'check', 'metrics'], input=raw, check=True)
        sample_hashes[name] = hashlib.sha256(raw).hexdigest()
    import os
    report = {'schemaVersion': 1, 'productSha': os.environ.get('GITHUB_SHA') or subprocess.check_output(
        ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
        'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
        'promtoolVersion': version.strip(), 'metricMethods': sorted(METHODS),
        'ruleScenarios': [s['name'] for s in scenarios], 'dashboardQueries': len(queries), 'queryValueEvaluations': 9,
        'sourceSha256': {name: hashlib.sha256((ASSETS / name).read_bytes()).hexdigest() for name in sorted(assets)},
        'metricSampleSha256': sample_hashes,
        'producerSha256': hashlib.sha256((ROOT / 'modules/oa-runtime/src/main/java/io/github/bassemzohdy/openworkflow/agentic/runtime/outbox/OutboxMetrics.java').read_bytes()).hexdigest(),
        'scope': 'Metric producer/export, rule evaluation and query syntax; no deployed collector, Grafana rendering, SLO, peer or package approval'}
    output = target / 'observability' / 'evidence.json'
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print('PASS: actual metric4, rule scenarios8 (all five alerts each), dashboard queries6; scoped preparation only')


if __name__ == '__main__':
    main()
