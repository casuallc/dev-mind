# CAP-41 M3 E2E：worklog 空间绑定远端仓库 + 手动 push（协议 v6 worklog_push 帧）
# 前置：app :8080 新版已起。脚本自建节点并 Popen 起 runner（tmp/runner-m3 独立 jar），
# 独立用户 wlm3 确保亲和到本次节点；远端用 file:// bare 库（免凭证通道）。
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8080/api'
ROOT = pathlib.Path(__file__).resolve().parent.parent
TMP = ROOT / 'tmp'  # 运行产物落 tmp/（gitignored）
RUNNER_DIR = TMP / 'runner-m3'
ORIGIN = TMP / 'e2e-m3' / 'origin.git'
JAVA21 = r'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
E2E_USER = {'username': 'wlm3' + str(int(time.time()))[-6:], 'password': 'wlm3123456',
            'role': 'DEVELOPER', 'displayName': 'M3推送'}


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


def git(*args, cwd=None):
    r = subprocess.run(['git', *(str(a) for a in args)], cwd=cwd or TMP,
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if r.returncode != 0:
        raise AssertionError(f'git {args} failed: {r.stdout}{r.stderr}')
    return (r.stdout + r.stderr).strip()


# 1. 管理员：注册节点 + 设默认 + 写 runner 配置（worklogRoot 指向 tmp，不碰真实 home）
admin = login('admin', 'admin123')
_, issued = call('POST', '/agent-nodes', {'name': 'e2e-m3-' + str(int(time.time())), 'labels': ''}, admin)
node_id = issued['node']['id']
call('POST', f'/agent-nodes/{node_id}/default', token=admin)
RUNNER_DIR.mkdir(exist_ok=True)
props = (
    'serverUrl=ws://localhost:8080/ws/agent\n'
    f"token={issued['token']}\n"
    'executor=fake\n'
    f"workspaceRoot={(RUNNER_DIR / 'workspaces').as_posix()}\n"
    f"worklogRoot={(RUNNER_DIR / 'worklog').as_posix()}\n"
)
(RUNNER_DIR / 'agent.properties').write_text(props, encoding='utf-8')
print(f'node id={node_id} default; runner config written')

# 2. 起 runner（独立目录的 jar，构建覆盖不影响运行中进程）
runner = subprocess.Popen(
    [JAVA21, '-jar', str(RUNNER_DIR / 'devmind-agent-runner.jar'), str(RUNNER_DIR / 'agent.properties')],
    stdout=open(RUNNER_DIR / 'runner.log', 'w', encoding='utf-8'),
    stderr=subprocess.STDOUT)
try:
    # 3. E2E 用户 + 空间亲和到本次节点
    try:
        call('POST', '/auth/users', E2E_USER, admin)
        print('e2e user created')
    except AssertionError:
        print('e2e user exists, reuse')
    user = login(E2E_USER['username'], E2E_USER['password'])
    _, ws = call('POST', '/worklog/workspace/ensure', {}, user)
    pid = ws['projectId']
    ok(str(ws['agentNodeId']) == str(node_id),
       f"affinity node={ws['agentNodeId']} == this-run node={node_id}")

    for _ in range(40):
        _, wsn = call('GET', '/worklog/workspace', token=user)
        if wsn.get('nodeOnline'):
            break
        time.sleep(1)
    ok(wsn.get('nodeOnline') is True, 'runner online')

    # 4. 先绑定 file:// bare 远端；未物化工作区时 push → 200 但 ok=false「未初始化」
    ORIGIN.parent.mkdir(exist_ok=True)
    if not ORIGIN.exists():
        git('init', '--bare', '-b', 'main', ORIGIN)
    _, sv = call('PUT', '/worklog/settings',
                 {'remoteUrl': ORIGIN.as_uri(), 'remoteBranch': 'main'}, user)
    ok(sv.get('remoteUrl') == ORIGIN.as_uri(), f"settings bound: {sv.get('remoteUrl')}")
    _, pre = call('POST', '/worklog/workspace/push', {}, user)
    ok(pre.get('ok') is False and '未初始化' in (pre.get('error') or ''),
       f"push before materialize -> ok=false: {pre.get('error')}")

    # 5. 起一个 fake worklog 会话物化持久工作区
    _, s = call('POST', '/sessions', {'taskSpec': 'E2E M3: init workspace', 'projectId': pid}, user)
    wdir = RUNNER_DIR / 'worklog' / E2E_USER['username']
    for _ in range(20):
        if (wdir / '.git').exists():
            break
        time.sleep(1)
    ok((wdir / '.git').is_dir(), f'runner materialized {wdir}/.git')

    # 6. push → 远端可见骨架提交
    _, ack = call('POST', '/worklog/workspace/push', {}, user)
    ok(ack.get('ok') is True, f"push ok: {ack.get('detail')}")
    readme = git('show', 'main:README.md', cwd=ORIGIN)
    ok('工作日志空间' in readme, 'origin main has skeleton README')

    # 6. 幂等再推（up-to-date 也算成功）
    _, ack2 = call('POST', '/worklog/workspace/push', {}, user)
    ok(ack2.get('ok') is True, f"second push ok (up-to-date): {ack2.get('detail')}")
    ok((wdir / '.git' / 'config').read_text(encoding='utf-8').count('oauth2') == 0,
       'no token residue in .git/config')

    # 7. 解绑 → push 400
    call('PUT', '/worklog/settings', {'remoteUrl': ''}, user)
    st, r = call('POST', '/worklog/workspace/push', {}, user, expect=400)
    ok(st == 400 and '未绑定' in json.dumps(r, ensure_ascii=False), 'unbound push -> 400')

    # 8. 绑 http URL 但无个人 PAT → 409 引导
    call('PUT', '/worklog/settings', {'remoteUrl': 'https://git-no-such-host.example.com/u/w.git'}, user)
    st, r = call('POST', '/worklog/workspace/push', {}, user, expect=409)
    ok(st == 409 and '个人访问令牌' in json.dumps(r, ensure_ascii=False),
       f'no PAT -> 409: {json.dumps(r, ensure_ascii=False)[:120]}')

    # 9. ssh URL 设置校验 400
    st, r = call('PUT', '/worklog/settings', {'remoteUrl': 'git@example.com:a/b.git'}, user, expect=400)
    ok(st == 400, 'ssh url -> 400')

    print('ALL PASS')
finally:
    runner.terminate()
    try:
        runner.wait(timeout=10)
    except subprocess.TimeoutExpired:
        runner.kill()
