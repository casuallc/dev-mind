# CAP-41 M2 E2E：种子（skill/场景）+ 模板设置 + 日报/周报生成走真实会话 + 回传落镜像
# 前置：app :8080（M2 构建）+ tmp runner（fake executor，节点 25=wle2e 亲和节点）在线。
# fake runner 不写 .devmind/output —— 由本脚本在会话运行期间直接写入（runner 同机），
# 会话退出时 OutputUploader 自动回传，验证服务端镜像链路。
import json
import time
import urllib.request
import urllib.error
import pathlib
import datetime
import sys

BASE = 'http://localhost:8080/api'
HOME = pathlib.Path.home() / 'worklog' / 'wle2e'


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


def drive_session(sid, token, timeout=90):
    """fake-agent hold 模式：喂一条输入，授权 permission_request，最后 finish。"""
    call('POST', f'/sessions/{sid}/input', {'text': 'go'}, token)
    deadline = time.time() + timeout
    while time.time() < deadline:
        _, so = call('GET', f'/sessions/{sid}', token=token)
        st = so.get('status')
        if st == 'WAITING_AUTH':
            call('POST', f'/sessions/{sid}/authorize',
                 {'accepted': True, 'scope': 'once', 'requestId': 'perm-fake'}, token)
        time.sleep(1)
    call('POST', f'/sessions/{sid}/finish', token=token)


_, r = call('POST', '/auth/login', {'username': 'wle2e', 'password': 'wle2e123'})
user = r['accessToken']

# 0. runner 在线
for _ in range(30):
    _, ws = call('GET', '/worklog/workspace', token=user)
    if ws.get('nodeOnline'):
        break
    time.sleep(1)
ok(ws.get('nodeOnline') is True, 'runner online')

# 1. FR-04 种子：GLOBAL skill worklog + 场景 worklog-daily/worklog-weekly
_, skills = call('GET', '/skills?scope=GLOBAL&keyword=worklog', token=user)
wl_skill = [s for s in skills['items'] if s['name'] == 'worklog']
ok(len(wl_skill) == 1, f"skill 'worklog' seeded (id={wl_skill[0]['id'] if wl_skill else '-'})")
_, scenarios = call('GET', '/scenarios', token=user)
daily_sc = next((s for s in scenarios if s['code'] == 'worklog-daily'), None)
weekly_sc = next((s for s in scenarios if s['code'] == 'worklog-weekly'), None)
ok(daily_sc is not None and weekly_sc is not None, 'scenarios worklog-daily/worklog-weekly seeded')
ok(wl_skill[0]['id'] in (daily_sc['skillIds'] or []), 'daily scenario binds worklog skill')
ok(daily_sc['permissionMode'] == 'bypassPermissions', 'daily scenario permissionMode=bypassPermissions')

# 2. FR-05 模板设置：自定义模板往返 + 内置默认查询
CUSTOM = '# 自定义E2E模板 {{date}}\n条目：{{entries}}\n提交：{{commits}}\n标记: E2E-TPL-MARK'
call('PUT', '/worklog/settings', {'dailyTemplateMd': CUSTOM}, user)
_, s = call('GET', '/worklog/settings', token=user)
ok(s.get('dailyTemplateMd') == CUSTOM, 'custom daily template roundtrip')
_, d = call('GET', '/worklog/settings/templates/default', token=user)
ok('{{date}}' in d['dailyTemplateMd'] and '{{weekRange}}' in d['weeklyTemplateMd'], 'default templates endpoint')
call('PUT', '/worklog/settings', {'dailyTemplateMd': '  '}, user)
_, s = call('GET', '/worklog/settings', token=user)
ok(s.get('dailyTemplateMd') is None, 'blank template resets to default (null)')
call('PUT', '/worklog/settings', {'dailyTemplateMd': CUSTOM}, user)

# 3. 素材：今天一条工作条目
today = datetime.date.today().isoformat()
call('POST', '/worklog/entries', {'workDate': today, 'title': 'E2E 素材条目', 'hours': 1.0,
                                  'entryType': 'DEV', 'content': 'M2 链路验证'}, user)
print(f'material entry created for {today}')

# 4. FR-03 日报生成 -> 创建真实 worklog 会话（force=true：脚本可重跑，覆盖既有 DRAFT 镜像）
_, ack = call('POST', '/worklog/daily/generate', {'date': today, 'force': True}, user)
sid = ack.get('sessionId')
ok(sid, f'daily generate accepted, session={sid}')
ok(ack.get('reused') is False, 'not reused')
_, sd = call('GET', f'/sessions/{sid}', token=user)
ok(sd.get('projectId') == ws['projectId'], 'session attached to WORKLOG project')
ok('E2E-TPL-MARK' in (sd.get('taskSpec') or ''), 'rendered custom template in session taskSpec')
ok('E2E 素材条目' in (sd.get('taskSpec') or ''), 'entry material in prompt')

# 5. FR-06 回传落镜像：会话运行期间写 .devmind/output/daily-<today>.md，退出自动回传
outdir = HOME / '.devmind' / 'output'
for _ in range(30):
    if HOME.is_dir():
        break
    time.sleep(1)
outdir.mkdir(parents=True, exist_ok=True)
daily_md = '# 2026 E2E 日报\n\n- 完成 M2 生成链路验证\n\n工时合计：1h\n'
(outdir / f'daily-{today}.md').write_text(daily_md, encoding='utf-8')
drive_session(sid, user)

daily = None
for _ in range(30):
    _, d0 = call('GET', f'/worklog/daily?date={today}', token=user)
    # 脚本可重跑：行可能来自上一轮会话，等本次会话的镜像更新落地
    if d0 and d0.get('sessionId') == sid:
        daily = d0
        break
    time.sleep(1)
ok(daily is not None, 'daily mirror row appeared')
# Windows 下 write_text 文本模式 \n→\r\n，比较前归一化
ok(daily['contentMd'].replace('\r\n', '\n') == daily_md, 'mirror content == uploaded output file')
ok(daily['status'] == 'DRAFT', 'mirror status DRAFT')
ok(daily.get('sessionId') == sid, 'mirror sessionId recorded')

# 6. 幂等：非 force 再生成 -> reused=true 不起会话
_, ack2 = call('POST', '/worklog/daily/generate', {'date': today}, user)
ok(ack2.get('reused') is True and not ack2.get('sessionId'), 're-generate without force -> reused')

# 7. 周报链：本周一（今天即周一也在本周内），素材=今日日报
week_start = (datetime.date.today() - datetime.timedelta(days=datetime.date.today().weekday())).isoformat()
iso = datetime.date.fromisoformat(week_start).isocalendar()
weekly_md = '## 上周总结\n\n- E2E 周报总结\n\n## 下周计划\n\n- 继续 M3\n'
_, ack3 = call('POST', '/worklog/weekly/generate', {'weekStart': week_start, 'force': True}, user)
wsid = ack3.get('sessionId')
ok(wsid, f'weekly generate accepted, session={wsid}')
(outdir / f'weekly-{iso.year}-W{iso.week:02d}.md').write_text(weekly_md, encoding='utf-8')
drive_session(wsid, user)

weekly = None
for _ in range(30):
    _, w0 = call('GET', f'/worklog/weekly?weekStart={week_start}', token=user)
    if w0 and w0.get('sessionId') == wsid:
        weekly = w0
        break
    time.sleep(1)
ok(weekly is not None, 'weekly mirror row appeared')
ok(weekly['summaryMd'].replace('\r\n', '\n') == '- E2E 周报总结', f"weekly summary split ok: {weekly['summaryMd']!r}")
ok(weekly['nextPlanMd'].replace('\r\n', '\n') == '- 继续 M3', 'weekly nextPlan split ok')

# 8. 清理模板自定义（回退默认，避免影响后续使用）
call('PUT', '/worklog/settings', {'dailyTemplateMd': ''}, user)

print('ALL PASS')
