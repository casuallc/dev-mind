# CAP-41 M1 E2E：WORKLOG 项目懒创建 + 操作守卫 + worklog 会话起 runner 持久工作区
# 前置：本地 app :8080 已起。脚本自建节点并写 tmp/runner/agent.properties（executor=fake）。
# 会话链用独立用户 wle2e（其 WORKLOG 项目亲和到本次新节点），admin 项目只做守卫断言。
import json
import time
import urllib.request
import urllib.error
import pathlib
import sys

BASE = 'http://localhost:8080/api'
TMP = pathlib.Path(__file__).resolve().parent.parent / 'tmp'  # 运行产物落 tmp/（gitignored）
E2E_USER = {'username': 'wle2e', 'password': 'wle2e123', 'role': 'DEVELOPER', 'displayName': '日志E2E'}


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
        try:
            return e.code, json.loads(payload or 'null')
        except json.JSONDecodeError:
            return e.code, payload


def ok(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        sys.exit(1)


def login(u, p):
    _, r = call('POST', '/auth/login', {'username': u, 'password': p})
    return r['accessToken']


# 1. 管理员登录 + 注册节点 + 设默认 + 写 runner 配置
admin = login('admin', 'admin123')
_, issued = call('POST', '/agent-nodes', {'name': 'e2e-cap41-' + str(int(time.time())), 'labels': ''}, admin)
node_id = issued['node']['id']
call('POST', f'/agent-nodes/{node_id}/default', token=admin)
(TMP / 'runner').mkdir(exist_ok=True)
props = (
    'serverUrl=ws://localhost:8080/ws/agent\n'
    f"token={issued['token']}\n"
    'executor=fake\n'
    f"workspaceRoot={(TMP / 'runner' / 'workspaces').as_posix()}\n"
)
(TMP / 'runner' / 'agent.properties').write_text(props, encoding='utf-8')
print(f'node id={node_id} registered as default; runner config written')

# 2. admin 侧：ensure（幂等）+ 操作守卫
_, aws = call('POST', '/worklog/workspace/ensure', {}, admin)
apid = aws['projectId']
ok(aws['exists'] is True and aws['path'] == 'worklog://admin', f"admin worklog project ok ({apid})")
st, r = call('PUT', f'/projects/{apid}', {'name': '工作日志', 'path': 'D:/elsewhere'}, admin, expect=400)
ok(st == 400, 'change path -> 400')
st, r = call('PUT', f'/projects/{apid}', {'name': '工作日志', 'status': 'ARCHIVED'}, admin, expect=409)
ok(st == 409, 'archive -> 409')
st, r = call('PUT', f'/projects/{apid}', {'name': '工作日志', 'agentNodeId': '999999'}, admin, expect=409)
ok(st == 409, 'change affinity node -> 409')
st, r = call('DELETE', f'/projects/{apid}', token=admin, expect=409)
ok(st == 409, 'delete -> 409')
st, r = call('PUT', f'/projects/{apid}', {'name': '工作日志', 'tags': ['log']}, admin)
ok(st == 200, 'rename/tags -> 200')
_, projects = call('GET', '/projects', token=admin)
ok(any(p.get('kind') == 'WORKLOG' and p['id'] == apid for p in projects), 'WORKLOG visible in project list')

# 3. E2E 用户（已存在则直接登录）
try:
    call('POST', '/auth/users', E2E_USER, admin)
    print('e2e user created')
except AssertionError:
    print('e2e user exists, reuse')
user = login(E2E_USER['username'], E2E_USER['password'])

# 4. 用户侧 ensure -> 亲和到本次新节点
_, ws = call('POST', '/worklog/workspace/ensure', {}, user)
ok(ws['exists'] is True and ws['path'] == f"worklog://{E2E_USER['username']}", f"user workspace ok ({ws['path']})")
_, ws = call('POST', '/worklog/workspace/ensure', {}, user)
pid = ws['projectId']
fresh = str(ws['agentNodeId']) == str(node_id)
print(f"affinity node={ws['agentNodeId']} (this-run node={node_id}, fresh={fresh})")

# 5. 等 runner 上线（仅当亲和节点即本 runner 节点时才能起会话）
if fresh:
    for _ in range(40):
        _, wsn = call('GET', '/worklog/workspace', token=user)
        if wsn.get('nodeOnline'):
            break
        time.sleep(1)
    ok(wsn.get('nodeOnline') is True, 'runner online')
    _, s = call('POST', '/sessions', {'taskSpec': 'E2E: write a worklog entry', 'projectId': pid}, user)
    sid = s['id']
    print(f'session created {sid}')

    home = pathlib.Path.home() / 'worklog' / E2E_USER['username']
    for _ in range(20):
        if (home / '.git').exists():
            break
        time.sleep(1)
    ok((home / '.git').is_dir(), f'runner prepared {home}/.git')
    ok((home / 'README.md').is_file(), 'skeleton README.md exists')
    ok((home / 'daily').is_dir() and (home / 'weekly').is_dir() and (home / 'entries').is_dir(),
       'daily/weekly/entries dirs exist')

    for _ in range(30):
        _, so = call('GET', f'/sessions/{sid}', token=user)
        if so.get('status') in ('FINISHED', 'FAILED', 'KILLED'):
            break
        time.sleep(1)
    print(f"session final status {so.get('status')}")
    ok(so.get('status') == 'FINISHED', 'fake session finished')
else:
    print('SKIP session chain: project affinity pinned to a previous-run node (by design 409 on change)')

print('ALL PASS')
