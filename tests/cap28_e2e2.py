# CAP-28 E2E 续（git 导入 + 日报/周报生成确认）
import json, time, urllib.request, datetime

BASE = "http://localhost:8081"
TODAY = datetime.date.today().isoformat()
REAL_SHA = "d5f7cb1ce1f81def028ed83612badda2aac6f6e6"
REAL_SUBJECT = "feat(session): CAP-28 one-shot 会话 SPI"

def call(method, path, body=None, token=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read().decode("utf-8")
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw

def show(label, st, body):
    print(f"== {label} → {st}")
    print(json.dumps(body, ensure_ascii=False, indent=1)[:800] if body is not None else "(null)")

_, login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
token = login["accessToken"]

show("1 git 导入真实提交", *call("POST", "/api/worklog/git/import",
      {"date": TODAY, "items": [{"repoId": 1, "commitSha": REAL_SHA, "subject": REAL_SUBJECT, "hours": 0.5}]}, token))
show("2 重复导入应全跳过", *call("POST", "/api/worklog/git/import",
      {"date": TODAY, "items": [{"repoId": 1, "commitSha": REAL_SHA, "subject": REAL_SUBJECT, "hours": 0.5}]}, token))

show("3 触发日报生成", *call("POST", "/api/worklog/daily/generate", {"date": TODAY}, token))
daily = None
for _ in range(40):
    time.sleep(2)
    _, daily = call("GET", f"/api/worklog/daily?date={TODAY}", token=token)
    if daily:
        break
show("4 日报草稿(fake)", 200, daily)
if daily:
    show("5 确认日报", *call("PUT", f"/api/worklog/daily/{daily['id']}", {"status": "CONFIRMED"}, token))
    st, b = call("POST", "/api/worklog/daily/generate", {"date": TODAY, "force": True}, token)
    time.sleep(3)
    _, chk = call("GET", f"/api/worklog/daily?date={TODAY}", token=token)
    print(f"== 6 已确认 force 重生成 → 提交{st}，日报状态仍为 {chk and chk['status']}")
    time.sleep(3)

monday = (datetime.date.today() - datetime.timedelta(days=datetime.date.today().weekday())).isoformat()
show("7 触发周报生成", *call("POST", "/api/worklog/weekly/generate", {"weekStart": monday}, token))
weekly = None
for _ in range(40):
    time.sleep(2)
    _, weekly = call("GET", f"/api/worklog/weekly?weekStart={monday}", token=token)
    if weekly:
        break
show("8 周报草稿(fake)", 200, weekly)

print("E2E-2 DONE")
