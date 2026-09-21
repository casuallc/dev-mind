# CAP-42 E2E：每用户固定工作区 + 手动收口（协议 v7 workspaceOwner / workspace_finalize 帧）
# 前置：app :8080 新版已起，devmind-agent-runner/target/devmind-agent-runner.jar 已构建。
# 脚本自建节点并 Popen 起 runner（tmp/runner-cap42 独立 jar 副本），独立 E2E 用户确保
# 工作区归属隔离；远端用 file:// bare 库（免凭证通道）。运行产物全部落 tmp/（gitignored）。
#
# 覆盖：
#   1. 会话工作区落 <proj>/<user>/{main,work} 固定布局；结束（finish）不 push 不删；
#   2. 占用冲突：同 (项目,用户) 未收口开新会话 → 409 引导收口；
#   3. finalize：合并到基线 + push 基线与会话分支 + 删 worktree + workspace_state=FINALIZED；
#      重复收口 409；
#   4. 负例：脏工作区不 discard → 409；discard 后合并冲突 → 409 工作区保留；
#      本地解冲突后重试收口成功；
#   5. 删除会话释放固定工作区（FR-09，协议 v9 workspace_release）：目录+本地分支释放、
#      远端不动、占用锁解开可立即再开新会话；节点离线时删除 409 阻断（fail-visible）。
import json
import os
import pathlib
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

BASE = 'http://localhost:8080/api'
ROOT = pathlib.Path(__file__).resolve().parent.parent
TMP = ROOT / 'tmp'
RUNNER_DIR = TMP / 'runner-cap42'
WS = RUNNER_DIR / 'workspaces'
ORIGIN = TMP / 'e2e-cap42' / 'origin.git'
SEED = TMP / 'e2e-cap42' / 'seed'
JAR = ROOT / 'devmind-agent-runner' / 'target' / 'devmind-agent-runner.jar'
JAVA21 = r'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot\bin\java.exe'
MARK = 'cap42-' + str(int(time.time()))[-6:]
S1_CHANGE = 'change-s1 ' + MARK  # origin 跨轮复用，产出内容须每轮唯一否则 add 后无 diff
E2E_USER = {'username': 'u' + str(int(time.time()))[-8:], 'password': 'cap42123456',
            'role': 'DEVELOPER', 'displayName': 'CAP42 E2E'}


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
            raise AssertionError(f'{method} {path} -> {e.code} (expect {expect}): {payload[:400]}')
        try:
            return e.code, json.loads(payload or 'null')
        except json.JSONDecodeError:
            return e.code, payload


def ok(cond, msg):
    print(('PASS ' if cond else 'FAIL ') + msg)
    if not cond:
        sys.exit(1)


def wait(cond, what, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f'超时等待: {what}')


def login(u, p):
    _, r = call('POST', '/auth/login', {'username': u, 'password': p})
    return r.get('accessToken') or r.get('token')


def git(*args, cwd=None, check=True):
    r = subprocess.run(['git', *(str(a) for a in args)], cwd=cwd or TMP,
                       capture_output=True, text=True, encoding='utf-8', errors='replace')
    if check and r.returncode != 0:
        raise AssertionError(f'git {args} failed: {r.stdout}{r.stderr}')
    return (r.stdout + r.stderr).strip()


def commit(cwd, msg):
    git('add', '-A', cwd=cwd)
    git('-c', 'user.email=e2e@t', '-c', 'user.name=e2e', 'commit', '-m', msg, cwd=cwd)


def rm_rf(p):
    """Windows 下 git 对象只读（0444），rmtree 默认删不掉 → 去只读后重试。"""
    def _fix(fn, path, _exc):
        os.chmod(path, 0o777)
        fn(path)
    shutil.rmtree(p, onexc=_fix)


def wait_terminal(sid, token):
    return wait(lambda: (lambda s: s if s.get('status') in
                ('DONE', 'FAILED', 'TERMINATED', 'SUSPENDED') else None)
                (call('GET', f'/sessions/{sid}', token=token)[1]),
                f'会话 {sid} 终态', 60)


if not JAR.exists():
    sys.exit(f'runner jar 不存在: {JAR}（先 mvn -pl devmind-agent-runner -am package -DskipTests）')

# 0. 管理员：建节点 + 设默认 + runner 配置（workspaceRoot 指向 tmp）
admin = login('admin', 'admin123')
_, issued = call('POST', '/agent-nodes', {'name': MARK, 'labels': ''}, admin)
node_id = issued['node']['id']
call('POST', f'/agent-nodes/{node_id}/default', token=admin)
RUNNER_DIR.mkdir(parents=True, exist_ok=True)
shutil.copy(JAR, RUNNER_DIR / 'devmind-agent-runner.jar')
(RUNNER_DIR / 'agent.properties').write_text(
    'serverUrl=ws://localhost:8080/ws/agent\n'
    f"token={issued['token']}\n"
    'executor=fake\n'
    f'workspaceRoot={WS.as_posix()}\n',
    encoding='utf-8')
runner = subprocess.Popen(
    [JAVA21, '-jar', str(RUNNER_DIR / 'devmind-agent-runner.jar'), str(RUNNER_DIR / 'agent.properties')],
    stdout=open(RUNNER_DIR / 'runner.log', 'w', encoding='utf-8'),
    stderr=subprocess.STDOUT)
try:
    wait(lambda: next((n for n in call('GET', '/agent-nodes', token=admin)[1]
                       if n['id'] == node_id and n['status'] == 'ONLINE'), None),
         '节点上线', 40)
    print(f'[0] 节点 {node_id} + runner 上线')

    # 1. E2E 用户 + file:// bare 远端（种 README 到 main）
    call('POST', '/auth/users', E2E_USER, admin)
    user = login(E2E_USER['username'], E2E_USER['password'])
    ORIGIN.parent.mkdir(parents=True, exist_ok=True)
    if not ORIGIN.exists():
        git('init', '--bare', '-b', 'main', ORIGIN)
    rm_rf(SEED)
    git('clone', ORIGIN, SEED)
    (SEED / 'README.md').write_text('# cap42 e2e ' + MARK + '\n', encoding='utf-8')
    commit(SEED, 'init')
    git('push', 'origin', 'main', cwd=SEED)

    # 2. 项目（CLONE file:// 远端）待 READY
    _, proj = call('POST', '/projects', {
        'name': MARK, 'sourceType': 'CLONE',
        'remoteUrl': ORIGIN.as_uri(), 'defaultBranch': 'main', 'tags': [],
    }, user)
    pid = proj['id']
    wait(lambda: call('GET', f'/projects/{pid}', token=user)[1].get('cloneStatus') == 'READY' or None,
         '项目克隆 READY', 90)
    print(f'[1] 项目 {pid} READY')

    uroot = WS / pid / E2E_USER['username']

    # 3. 会话 1：固定布局落盘（缓存 main + 固定 worktree work）
    _, s1 = call('POST', '/sessions', {'projectId': pid, 'taskSpec': 'CAP-42 E2E 会话1'}, user)
    sid1 = s1['id']
    work1 = uroot / 'work'
    wait(lambda: (work1 / '.git').exists() or None, '固定 worktree 物化', 30)
    ok((uroot / 'main' / '.git').is_dir(), f'克隆缓存落 {uroot}/main')
    ok(s1.get('workspaceState') == 'OPEN', f'新会话 workspaceState=OPEN: {s1.get("workspaceState")}')
    (work1 / 'code.txt').write_text(S1_CHANGE + '\n', encoding='utf-8')
    commit(work1, 'work s1')
    print(f'[2] 会话1 {sid1} 固定工作区就绪 + 提交一笔')

    # 4. finish：不 push 不删（工作区保留、远端无会话分支）
    call('POST', f'/sessions/{sid1}/finish', token=user)
    wait_terminal(sid1, user)
    ok(work1.is_dir(), 'finish 后固定 worktree 保留')
    ls = git('ls-remote', ORIGIN, f'refs/heads/feature/{sid1}')
    ok(ls == '', 'finish 后远端无会话分支（未自动 push）')

    # 5. 占用冲突：未收口开新会话 → 409 引导收口
    st, r = call('POST', '/sessions', {'projectId': pid, 'taskSpec': 'CAP-42 E2E 占用冲突'}, user, expect=409)
    ok(st == 409 and '收口' in json.dumps(r, ensure_ascii=False),
       f'占用冲突 409: {json.dumps(r, ensure_ascii=False)[:120]}')

    # 6. 手动收口：合并 + push + 删 worktree + FINALIZED
    _, ack = call('POST', f'/sessions/{sid1}/finalize', {}, user)
    ok(ack.get('ok') is True, f"finalize ok: {ack.get('detail')}")
    ok(git('show', 'main:code.txt', cwd=ORIGIN).strip() == S1_CHANGE, '基线含会话产出 code.txt')
    ok(git('ls-remote', ORIGIN, f'refs/heads/feature/{sid1}') != '', '会话分支已推送远端（diff 链路）')
    ok(not work1.exists(), '收口后固定 worktree 已删')
    _, v1 = call('GET', f'/sessions/{sid1}', token=user)
    ok(v1.get('workspaceState') == 'FINALIZED', f"workspaceState=FINALIZED: {v1.get('workspaceState')}")
    st, r = call('POST', f'/sessions/{sid1}/finalize', {}, user, expect=409)
    ok(st == 409, '重复收口 409')
    print('[3] 会话1 收口闭环 OK')

    # 7. 会话 2：脏工作区 + 合并冲突两负例
    _, s2 = call('POST', '/sessions', {'projectId': pid, 'taskSpec': 'CAP-42 E2E 会话2'}, user)
    sid2 = s2['id']
    work2 = uroot / 'work'
    wait(lambda: (work2 / '.git').exists() or None, '会话2 worktree 物化', 30)
    # 会话分支改 README（与基线冲突方向）；基线同时被他人推进
    (work2 / 'README.md').write_text('session-change\n', encoding='utf-8')
    commit(work2, 'work s2')
    git('pull', '--rebase', 'origin', 'main', cwd=SEED)  # 吸收会话1收口合入的提交
    (SEED / 'README.md').write_text('other-change\n', encoding='utf-8')
    commit(SEED, 'other')
    git('push', 'origin', 'main', cwd=SEED)
    # 留一个未提交脏文件
    (work2 / 'dirty.txt').write_text('uncommitted\n', encoding='utf-8')
    call('POST', f'/sessions/{sid2}/finish', token=user)
    wait_terminal(sid2, user)

    # 7a. 脏工作区不 discard → 409「未提交改动」，现场保留
    st, r = call('POST', f'/sessions/{sid2}/finalize', {}, user, expect=409)
    ok(st == 409 and '未提交改动' in json.dumps(r, ensure_ascii=False),
       f'脏工作区 409: {json.dumps(r, ensure_ascii=False)[:120]}')
    ok((work2 / 'dirty.txt').exists(), '失败后脏文件保留')

    # 7b. discard 后撞合并冲突 → 409「冲突」，工作区保留
    st, r = call('POST', f'/sessions/{sid2}/finalize', {'discardChanges': True}, user, expect=409)
    ok(st == 409 and '冲突' in json.dumps(r, ensure_ascii=False),
       f'合并冲突 409: {json.dumps(r, ensure_ascii=False)[:150]}')
    ok(work2.is_dir() and not (work2 / 'dirty.txt').exists(), '冲突失败：工作区保留且脏文件已被 discard')
    ok(git('show', 'main:README.md', cwd=ORIGIN).strip() == 'other-change', '冲突时基线未被污染')

    # 7c. 本地解冲突（merge FETCH_HEAD 进会话分支）后重试收口 → 成功
    git('fetch', ORIGIN.as_uri(), 'main', cwd=work2)
    git('merge', 'FETCH_HEAD', cwd=work2, check=False)  # 冲突
    (work2 / 'README.md').write_text('resolved\n', encoding='utf-8')
    commit(work2, 'resolve conflict')
    _, ack2 = call('POST', f'/sessions/{sid2}/finalize', {}, user)
    ok(ack2.get('ok') is True, f"解冲突后重试收口 ok: {ack2.get('detail')}")
    ok(git('show', 'main:README.md', cwd=ORIGIN).strip() == 'resolved', '基线含解冲突结果')
    ok(not work2.exists(), '会话2 收口后 worktree 已删')

    # 8. 删除会话释放固定工作区（FR-09）：目录与本地分支释放、远端不动、同 (项目,用户)
    #    可立即再开新会话——不释放则目录成孤儿，该用户在该项目永久开不了新会话
    _, s3 = call('POST', '/sessions', {'projectId': pid, 'taskSpec': 'CAP-42 E2E 会话3（删除释放）'}, user)
    sid3 = s3['id']
    work3 = uroot / 'work'
    wait(lambda: (work3 / '.git').exists() or None, '会话3 worktree 物化', 30)
    ok(work3.is_dir(), '删除前固定 worktree 在')
    call('POST', f'/sessions/{sid3}/finish', token=user)
    wait_terminal(sid3, user)
    # 带一个未提交脏文件：释放是丢弃语义，不该被它挡住（与收口不同，无 discardChanges 开关）
    (work3 / 'wip.txt').write_text('discarded\n', encoding='utf-8')
    call('DELETE', f'/sessions/{sid3}', token=user)
    ok(not work3.exists(), '删除会话后固定 worktree 已释放')
    ok(git('branch', '--list', f'feature/{sid3}', cwd=uroot / 'main') == '',
       '本地会话分支已释放（残留分支同样会留成半成品）')
    ok(git('ls-remote', ORIGIN, f'refs/heads/feature/{sid3}') == '', '释放不 push：远端无会话分支')
    call('GET', f'/sessions/{sid3}', token=user, expect=404)
    # 占用锁已解：同 (项目,用户) 立刻能再开会话（此前正是卡在这里永久死锁）
    _, s4 = call('POST', '/sessions', {'projectId': pid, 'taskSpec': 'CAP-42 E2E 会话4（释放后可开）'}, user)
    work4 = uroot / 'work'
    wait(lambda: (work4 / '.git').exists() or None, '会话4 worktree 物化', 30)
    ok(work4.is_dir(), '释放后同用户同项目可立即开新会话')
    call('POST', f'/sessions/{s4["id"]}/finish', token=user)
    wait_terminal(s4['id'], user)
    print('[4] 删除会话释放固定工作区 OK')

    # 9. 节点离线时删除必须 409 阻断（fail-visible）：绝不静默跳过释放留下孤儿目录
    runner.terminate()
    runner.wait(timeout=20)
    wait(lambda: next((n for n in call('GET', '/agent-nodes', token=admin)[1]
                       if n['id'] == node_id and n['status'] != 'ONLINE'), None), '节点转 OFFLINE', 40)
    st, r = call('DELETE', f'/sessions/{s4["id"]}', token=user, expect=409)
    # 提示须落在真实原因上：断连会清协议版本记录，先判版本会误报「版本过低，请升级 runner」
    ok(st == 409 and '不在线' in json.dumps(r, ensure_ascii=False),
       f'节点离线删除 409（报「不在线」而非版本过低）: {json.dumps(r, ensure_ascii=False)[:180]}')
    call('GET', f'/sessions/{s4["id"]}', token=user)  # 记录保留，未半删
    ok(work4.is_dir(), '节点离线：目录与会话记录都保留（可待节点上线后重试）')
    print('[5] 节点离线删除 fail-visible OK')

    print('ALL PASS')
finally:
    runner.terminate()
    try:
        runner.wait(timeout=10)
    except subprocess.TimeoutExpired:
        runner.kill()
