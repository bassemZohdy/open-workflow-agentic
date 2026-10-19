"""Checks prevent stale tracking and false completion claims from reaching main."""
import copy
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('render_progress', ROOT / 'tools/render_progress.py')
progress = importlib.util.module_from_spec(spec)
spec.loader.exec_module(progress)


class ProgressValidationTest(unittest.TestCase):
    def setUp(self):
        self.plan = json.loads((ROOT / 'docs/plan/work-packages.json').read_text())
        source = json.loads((ROOT / 'docs/requirements/requirements.json').read_text())
        self.requirements = {r['id']: r for k in ('mandatory', 'high', 'optional') for r in source[k]}
        self.status = json.loads((ROOT / 'docs/requirements/test-status.json').read_text())
        self.tests_by_req, self.owners_by_req = {}, {}
        for scenario in source['scenarios']:
            for test in scenario['tests']:
                for rid in test['verifies']:
                    self.tests_by_req.setdefault(rid, []).append(test['id'])
        for w in self.plan['work_packages']:
            for rid in w['requirements']:
                self.owners_by_req.setdefault(rid, []).append(w['id'])

    def errors(self):
        return progress.validate_progress(ROOT, self.plan, self.requirements, self.status,
                                          self.tests_by_req, self.owners_by_req)

    def test_current_plan_has_complete_non_conflicting_assignments(self):
        self.assertEqual([], self.errors())
        owned = progress.assignments(self.plan)
        self.assertEqual('GPT', owned['WP-108']['owner'])
        self.assertEqual('Claude', owned['WP-102']['owner'])

    def test_a_package_cannot_be_silently_duplicated_between_agents(self):
        gpt, claude = self.plan['execution_plan']['agents']
        claude['backlog'].append(copy.deepcopy(gpt['backlog'][0]))
        self.assertIn('WP-108: duplicate implementation owner', self.errors())

    def test_unfinished_work_cannot_disappear_from_both_backlogs(self):
        self.plan['execution_plan']['agents'][0]['backlog'].pop(0)
        self.assertTrue(any('unassigned unfinished packages' in e and 'WP-108' in e for e in self.errors()))

    def test_an_agent_cannot_review_its_own_work(self):
        self.plan['execution_plan']['agents'][0]['reviewer'] = 'GPT'
        self.assertIn('GPT: reviewer must be the other agent', self.errors())

    def test_cross_agent_wait_cycle_is_rejected(self):
        self.plan['execution_plan']['agents'][0]['backlog'][0]['after'].append('WP-501')
        self.assertTrue(any('execution dependency cycle' in e for e in self.errors()))

    def test_tool_gateway_cannot_skip_the_enterprise_adapter_handoff(self):
        task = next(t for a in self.plan['execution_plan']['agents'] for t in a['backlog']
                    if t['wp'] == 'WP-204')
        task['after'].remove('WP-113')
        self.assertTrue(any('WP-204: missing unfinished prerequisite gates' in e for e in self.errors()))

    def test_a_second_writer_cannot_reserve_an_existing_shared_path(self):
        self.plan['execution_plan']['write_areas'].append({
            'area': 'Claude lifecycle hook', 'owner': 'Claude', 'rule': 'Direct edit',
            'paths': ['modules/oa-runtime/src/main/java/io/github/bassemzohdy/openworkflow/'
                      'agentic/runtime/lifecycle/Reconciler.java']})
        self.assertTrue(any('overlapping write areas' in e for e in self.errors()))

    def test_an_unproven_partial_package_cannot_become_a_baseline_exception(self):
        self.plan['execution_plan']['baseline_dependencies'].append('WP-102')
        self.assertTrue(any('only the evidenced WP-105 core' in e for e in self.errors()))

    def test_remaining_real_identity_gate_cannot_be_declared_complete(self):
        w = next(w for w in self.plan['work_packages'] if w['id'] == 'WP-102')
        w['progress']['status'] = 'completed'
        self.assertTrue(any('WP-102: completed work package has unfinished' in e for e in self.errors()))

    def test_completed_work_package_cannot_hide_regressed_acceptance(self):
        self.status['T01']['status'] = 'planned'
        self.assertTrue(any('WP-104: completed work package requires automated acceptance test T01' in e
                            for e in self.errors()))

    def test_unknown_requirement_cannot_gain_implementation_evidence(self):
        self.plan['requirement_progress']['M999'] = copy.deepcopy(self.plan['requirement_progress']['M01'])
        self.assertIn('unknown requirement progress M999', self.errors())

    def test_ci_check_rejects_stale_todo_without_overwriting_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            checkout = Path(tmp) / 'repo'
            shutil.copytree(ROOT, checkout, ignore=shutil.ignore_patterns('.git', 'target', '__pycache__'))
            todo = checkout / 'TODO.md'
            stale = todo.read_text() + '\n- [ ] Untracked manual edit\n'
            todo.write_text(stale)
            result = subprocess.run([sys.executable, 'tools/render_requirements.py', '--check'],
                                    cwd=checkout, capture_output=True, text=True)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('TODO.md', result.stdout + result.stderr)
            self.assertEqual(stale, todo.read_text())


if __name__ == '__main__':
    unittest.main()
