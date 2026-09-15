# CAP-28 E2E（临时脚本，gitignored）：登录 → 仓库登记/订阅 → 条目 CRUD → git 预览/导入 → 日报生成/确认
import json, time, urllib.request, datetime, sys

BASE = "http://localhost:8081"
TODAY = datetime.date.today().isoformat()

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
    print(json.dumps(body, ensure_ascii=False, indent=1)[:600] if body is not None else "")

st, login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
token = login["accessToken"]

show("1 登记全局仓库", *call("POST", "/api/worklog/repos",
      {"name": "dev-mind", "localPath": "D:/apusic/dev-mind"}, token))
st, repos = call("GET", "/api/worklog/repos", token=token)
repo_id = repos[0]["id"]
show("2 重复登记应409", *call("POST", "/api/worklog/repos",
      {"name": "dup", "localPath": "D:/apusic/dev-mind"}, token))
show("3 非git目录应400", *call("POST", "/api/worklog/repos",
      {"name": "bad", "localPath": "C:/Windows/Temp"}, token))
show("4 勾选订阅", *call("PUT", f"/api/worklog/repos/{repo_id}/subscription",
      {"subscribed": True}, token))
show("5 仓库列表(带subscribed)", *call("GET", "/api/worklog/repos", token=token))

show("6 手动补录条目", *call("POST", "/api/worklog/entries",
      {"workDate": TODAY, "title": "项目支持：协助排查线上问题", "entryType": "SUPPORT", "hours": 1.5}, token))
st, entries = call("GET", f"/api/worklog/entries?from={TODAY}&to={TODAY}", token=token)
show("7 当日条目列表", st, entries)

st, commits = call("GET", f"/api/worklog/git/preview?date={TODAY}", token=token)
show("8 git 预览", st, commits[:3] if isinstance(commits, list) else commits)
if isinstance(commits, list) and commits:
    items = [{"repoId": c["repoId"], "commitSha": c["sha"], "subject": c["subject"], "hours": 0.25}
             for c in commits if not c["alreadyImported"]][:3]
    if items:
        show("9 导入 git 提交", *call("POST", "/api/worklog/git/import",
              {"date": TODAY, "items": items}, token))
        show("9b 重复导入应全跳过", *call("POST", "/api/worklog/git/import",
              {"date": TODAY, "items": items}, token))
else:
    print("== 9 当日无匹配提交（author 过滤），跳过导入用例")

show("10 设置读取", *call("GET", "/api/worklog/settings", token=token))
show("11 设置更新", *call("PUT", "/api/worklog/settings", {"dailyMinutesTarget": 480}, token))

show("12 触发日报生成(异步)", *call("POST", "/api/worklog/daily/generate", {"date": TODAY}, token))
daily = None
for _ in range(30):
    time.sleep(2)
    st, daily = call("GET", f"/api/worklog/daily?date={TODAY}", token=token)
    if daily:
        break
show("13 日报草稿", st, daily)
if daily:
    show("14 确认日报", *call("PUT", f"/api/worklog/daily/{daily['id']}", {"status": "CONFIRMED"}, token))
    show("15 已确认后 force 重生成应409", *call("POST", "/api/worklog/daily/generate",
          {"date": TODAY, "force": True}, token))
    # 等 409 任务位释放
    time.sleep(2)

monday = (datetime.date.today() - datetime.timedelta(days=datetime.date.today().weekday())).isoformat()
show("16 触发周报生成(本周)", *call("POST", "/api/worklog/weekly/generate", {"weekStart": monday}, token))
weekly = None
for _ in range(30):
    time.sleep(2)
    st, weekly = call("GET", f"/api/worklog/weekly?weekStart={monday}", token=token)
    if weekly:
        break
show("17 周报草稿", st, weekly)

print("E2E DONE")
