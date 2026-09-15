# CAP-41 M1 E2E 续跑：wle2e 用户 worklog 会话链（runner 已以节点 25 在线）
import json
import time
import urllib.request
import urllib.error
import pathlib
import sys

BASE = 'http://localhost:8080/api'


def call(method, path, body=None, token=None, expect=200):
    req = urllib.request.Request(
        BASE + path,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={'Content-Type': 'application/json', **({'Authorization': 'Bearer ' + token} if token else {})},
    )
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, json.loads(r.read().decode() or 'null')
    except urllib.error.HTTPError as e:
        payload = e.read().decode()
        if e.code != expect:
            raise AssertionError(f'{method} {path} -> {e.code} (expect {expect}): {payload[:300]}')
        return e.code, payload


def ok(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        sys.exit(1)


_, r = call('POST', '/auth/login', {'username': 'wle2e', 'password': 'wle2e123'})
user = r['accessToken']

_, ws = call('GET', '/worklog/workspace', token=user)
pid = ws['projectId']
ok(ws['exists'] is True, f"workspace exists, affinity node={ws['agentNodeId']}")

for _ in range(40):
    _, wsn = call('GET', '/worklog/workspace', token=user)
    if wsn.get('nodeOnline'):
        break
    time.sleep(1)
ok(wsn.get('nodeOnline') is True, 'runner online')

_, s = call('POST', '/sessions', {'taskSpec': 'E2E: write a worklog entry', 'projectId': pid}, user)
sid = s['id']
print(f'session created {sid}')

home = pathlib.Path.home() / 'worklog' / 'wle2e'
for _ in range(20):
    if (home / '.git').exists():
        break
    time.sleep(1)
ok((home / '.git').is_dir(), f'runner prepared {home}/.git')
ok((home / 'README.md').is_file(), 'skeleton README.md exists')
ok((home / 'daily').is_dir() and (home / 'weekly').is_dir() and (home / 'entries').is_dir(),
   'daily/weekly/entries dirs exist')

# fake-agent 为 hold 模式：发一条输入驱动其跑完流程
call('POST', f'/sessions/{sid}/input', {'text': 'go'}, user)
for _ in range(120):
    _, so = call('GET', f'/sessions/{sid}', token=user)
    if so.get('status') in ('FINISHED', 'FAILED', 'KILLED'):
        break
    if so.get('status') == 'WAITING_AUTH':
        # fake-agent 固定发一轮 permission_request（fake-agent.js:72），授权放行继续
        call('POST', f'/sessions/{sid}/authorize', {'accepted': True, 'scope': 'once', 'requestId': 'perm-fake'}, user)
    time.sleep(1)
print(f"session final status {so.get('status')}")
ok(so.get('status') == 'FINISHED', 'fake session finished')

# 骨架 git 初始提交验证
import subprocess
log = subprocess.run(['git', '-C', str(home), 'log', '--oneline'], capture_output=True, text=True)
ok('skeleton' in log.stdout or len(log.stdout.strip()) > 0, f'git log: {log.stdout.strip()[:80]}')

print('ALL PASS')
