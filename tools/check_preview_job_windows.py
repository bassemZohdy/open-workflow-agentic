"""Actual native process-family checks; no skipped cases counted as Windows evidence."""
from pathlib import Path
import concurrent.futures
import json
import hashlib
import os
import subprocess
import sys
from preview_job_windows import OwnedJob

if os.name != 'nt':
    sys.exit('Run these physical process-family checks on Windows')

ROOT = Path(__file__).resolve().parents[1]
report = {'sourceSha': os.environ.get('GITHUB_SHA') or subprocess.check_output(
    ['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(), 'platform': sys.platform,
    'python': sys.version.split()[0], 'workingTreeDirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
    'sourceUtf8LfSha256': {name: hashlib.sha256((ROOT/'tools'/name).read_text(encoding='utf-8').encode()).hexdigest()
                          for name in ['preview_job_windows.py','check_preview_job_windows.py']}, 'cases': []}
output = ROOT / 'target' / 'observability' / 'preview-processes.json'
output.parent.mkdir(parents=True, exist_ok=True)
try:
    for exits in (False, True):
        job = OwnedJob(); parent = sentinel = None; executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        try:
            sentinel = subprocess.Popen([sys.executable, '-c', 'import time;time.sleep(60)'],
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW)
            child_code = 'import time;time.sleep(60)'
            parent_code = ('import subprocess,sys;child=subprocess.Popen([sys.executable,"-c",' + repr(child_code) +
                '],stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);print(child.pid,flush=True);' +
                ('sys.exit(0)' if exits else 'child.wait()'))
            parent = job.start([sys.executable, '-c', parent_code], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            descendant = int(executor.submit(parent.stdout.readline).result(timeout=10).strip())
            if exits:
                assert parent.wait(timeout=10) == 0
            members = job.members(); assert descendant in members and sentinel.pid not in members
            if not exits:
                assert parent.pid in members
            summary = job.close(); assert summary['membersAfterStop'] == []
            assert sentinel.poll() is None
            parent.wait(timeout=10)
            report['cases'].append({'case': 'leader-exited' if exits else 'leader-alive', 'passed': True,
                'descendantInJobBeforeStop': True, 'remainingMembersAfterStop': [], 'unrelatedSentinelSurvived': True})
        finally:
            job.close()
            if parent and parent.poll() is None:
                parent.terminate(); parent.wait(timeout=10)
            if sentinel and sentinel.poll() is None:
                sentinel.terminate(); sentinel.wait(timeout=10)
            executor.shutdown(wait=True)
finally:
    output.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
assert len(report['cases']) == 2
print('PASS: native family cleanup with live/exited leaders; descendants stopped and unrelated sentinels survived')
