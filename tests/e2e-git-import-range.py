# Git 导入范围扫描 E2E（临时脚本，gitignored）：preview from/to + 按提交日落条目 + 幂等
import json, datetime, urllib.request, urllib.parse

BASE = "http://localhost:8081"
TODAY = datetime.date.today()
FROM = (TODAY - datetime.timedelta(days=3)).isoformat()
TO = TODAY.isoformat()

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

st, login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
token = login["accessToken"]
print("== login ->", st)

st, repo = call("POST", "/api/repos",
                {"name": "dev-mind", "sourceType": "LOCAL", "localPath": "D:/apusic/dev-mind"}, token)
print("== register repo ->", st)
repo_id = repo["id"] if st == 200 else None
if repo_id is None:
    st, repos = call("GET", "/api/worklog/repos", token=token)
    repo_id = [r for r in repos if r["name"] == "dev-mind"][0]["id"]
st, _ = call("PUT", f"/api/worklog/repos/{repo_id}/subscription", {"subscribed": True}, token)
print("== subscribe ->", st)

# 范围参数校验
st, b = call("GET", f"/api/worklog/git/preview?from={TO}&to={FROM}", token=token)
print("== to<from 应400 ->", st); assert st == 400, b
far = (TODAY + datetime.timedelta(days=70)).isoformat()
st, b = call("GET", f"/api/worklog/git/preview?from={TO}&to={far}", token=token)
print("== 跨度>62天 应400 ->", st); assert st == 400, b

st, preview = call("GET", f"/api/worklog/git/preview?from={FROM}&to={TO}", token=token)
commits = preview["commits"]
print("== preview range ->", st, "commits=", len(commits),
      [c["sha"][:7] for c in commits][:6], "diags=", len(preview["repos"]))
assert st == 200 and len(commits) >= 2, preview

# 导入前 2 条，date 取提交实际日期
items = [{"repoId": c["repoId"], "commitSha": c["sha"], "subject": c["subject"],
          "date": c["committedAt"][:10]} for c in commits[:2]]
st, r = call("POST", "/api/worklog/git/import", {"items": items}, token)
print("== import ->", st, r); assert st == 200 and r["created"] == 2, r

st, r = call("POST", "/api/worklog/git/import", {"items": items}, token)
print("== re-import 幂等 ->", st, r); assert r["created"] == 0 and r["skipped"] == 2, r

st, page = call("GET", f"/api/worklog/entries?from={FROM}&to={TO}", token=token)
by_sha = {e["commitSha"][:7]: e for e in page["items"] if e.get("commitSha")}
print("== entries ->", st, "total=", page["total"])
for it in items:
    e = by_sha[it["commitSha"][:7]]
    assert e["workDate"] == it["date"], (e["workDate"], it["date"])
    assert e["source"] == "GIT"
print("== 条目归属日 = 提交实际日期 OK")

# 重复导入后 preview 应标记 alreadyImported
st, preview2 = call("GET", f"/api/worklog/git/preview?from={FROM}&to={TO}", token=token)
flags = {c["sha"][:7]: c["alreadyImported"] for c in preview2["commits"]}
assert all(flags[it["commitSha"][:7]] for it in items), flags
print("== alreadyImported 标记 OK")
print("ALL PASS")
