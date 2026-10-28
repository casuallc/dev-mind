# CAP-33 FR-08 / CAP-41 FR-04 E2E：日报生成会话按场景 autoFinishSec 自动结束 + 成稿自动回传落镜像。
# 前置：app :8080（含 FR-08 构建）+ tmp runner（fake executor，节点 25=wle2e 亲和节点）在线。
# 全程不调 finish/collect——断言「回合完成 → 空闲 N 秒 → 自动结束 → 回传落镜像」无人值守链路。
import json
import time
import urllib.request
import urllib.error
import pathlib
import datetime
import sys

BASE = 'http://localhost:8080/api'
HOME = pathlib.Path.home() / 'worklog' / 'wle2e'
AUTO_FINISH_SEC = 5


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


_, r = call('POST', '/auth/login', {'username': 'wle2e', 'password': 'wle2e123'})
user = r['accessToken']

# 0. runner 在线
ws = {}
for _ in range(30):
    _, ws = call('GET', '/worklog/workspace', token=user)
    if ws.get('nodeOnline'):
        break
    time.sleep(1)
ok(ws.get('nodeOnline') is True, 'runner online')

# 1. 场景预设：worklog-daily 设 autoFinishSec=5（补丁语义，只传该字段），断言往返
_, scenarios = call('GET', '/scenarios', token=user)
daily_sc = next((s for s in scenarios if s['code'] == 'worklog-daily'), None)
ok(daily_sc is not None, 'scenario worklog-daily seeded')
ok(daily_sc.get('autoFinishSec') == 60,
   f"seeded autoFinishSec=60 (got {daily_sc.get('autoFinishSec')})")
call('PUT', f"/scenarios/{daily_sc['id']}", {'autoFinishSec': AUTO_FINISH_SEC}, user)
_, scenarios = call('GET', '/scenarios', token=user)
daily_sc = next(s for s in scenarios if s['code'] == 'worklog-daily')
ok(daily_sc.get('autoFinishSec') == AUTO_FINISH_SEC, 'autoFinishSec=5 roundtrip')

try:
    # 2. 素材 + 触发日报生成（force：脚本可重跑）
    today = datetime.date.today().isoformat()
    call('POST', '/worklog/entries', {'workDate': today, 'title': 'E2E 自动结束素材', 'hours': 0.5,
                                      'entryType': 'DEV', 'content': 'FR-08 自动结束链路'}, user)
    _, ack = call('POST', '/worklog/daily/generate', {'date': today, 'force': True}, user)
    sid = ack.get('sessionId')
    ok(sid, f'daily generate accepted, session={sid}')

    # 3. 会话运行期间预写成稿（fake 不写 .devmind/output，runner 同机由脚本代写，同 e2e-cap41c）
    outdir = HOME / '.devmind' / 'output'
    for _ in range(30):
        if HOME.is_dir():
            break
        time.sleep(1)
    outdir.mkdir(parents=True, exist_ok=True)
    daily_md = '# 2026 E2E 日报（自动结束）\n\n- FR-08 无人值守链路验证\n\n工时合计：0.5h\n'
    (outdir / f'daily-{today}.md').write_text(daily_md, encoding='utf-8')

    # 4. 只轮询不调 finish：fake（bypass）回合完成出 result → WAITING_INPUT → 空闲 5s 自动结束
    final = None
    deadline = time.time() + 60
    while time.time() < deadline:
        _, so = call('GET', f'/sessions/{sid}', token=user)
        if so.get('status') in ('DONE', 'FINISHED', 'FAILED', 'KILLED'):
            final = so.get('status')
            break
        time.sleep(1)
    ok(final == 'DONE', f'session auto-finished without manual finish (status={final})')

    # 5. 成稿随 exit 自动回传 → 镜像落库
    daily = None
    for _ in range(30):
        _, d0 = call('GET', f'/worklog/daily?date={today}', token=user)
        if d0 and d0.get('sessionId') == sid:
            daily = d0
            break
        time.sleep(1)
    ok(daily is not None, 'daily mirror row appeared (auto upload on exit)')
    ok(daily['contentMd'].replace('\r\n', '\n') == daily_md, 'mirror content == uploaded output file')
    ok(daily['status'] == 'DRAFT', 'mirror status DRAFT')
finally:
    # 6. 恢复种子值（脚本可重跑/不污染环境）
    call('PUT', f"/scenarios/{daily_sc['id']}", {'autoFinishSec': 60}, user)
    _, scenarios = call('GET', '/scenarios', token=user)
    restored = next(s for s in scenarios if s['code'] == 'worklog-daily')
    ok(restored.get('autoFinishSec') == 60, 'autoFinishSec restored to 60')

print('ALL PASS')
