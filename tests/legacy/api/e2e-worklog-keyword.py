# 工作日志筛选交互优化 E2E（临时脚本，gitignored）：entries keyword 标题模糊查询
import json, datetime, urllib.request

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

st, login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
token = login["accessToken"]
print("== 登录 ->", st)

titles = ["修复登录超时问题", "联调支付接口", "登录页样式调整"]
for t in titles:
    st, _ = call("POST", "/api/worklog/entries",
                 {"workDate": TODAY, "title": t, "entryType": "DEV", "hours": 1}, token)
print("== 建 3 条条目 ->", st)

st, page = call("GET", f"/api/worklog/entries?from={TODAY}&to={TODAY}", token=token)
print("== 无 keyword 全部 ->", st, "total=", page["total"], "totalMinutes=", page["totalMinutes"])
assert page["total"] == 3, page

import urllib.parse
kw = urllib.parse.quote("登录")
st, page = call("GET", f"/api/worklog/entries?from={TODAY}&to={TODAY}&keyword={kw}", token=token)
got = [i["title"] for i in page["items"]]
print("== keyword=登录 ->", st, "total=", page["total"], got, "totalMinutes=", page["totalMinutes"])
assert page["total"] == 2 and all("登录" in t for t in got), page

kw = urllib.parse.quote("支付")
st, page = call("GET", f"/api/worklog/entries?from={TODAY}&to={TODAY}&keyword={kw}", token=token)
got = [i["title"] for i in page["items"]]
print("== keyword=支付 ->", st, "total=", page["total"], got)
assert page["total"] == 1 and got[0] == "联调支付接口", page

kw = urllib.parse.quote("不存在的关键词")
st, page = call("GET", f"/api/worklog/entries?from={TODAY}&to={TODAY}&keyword={kw}", token=token)
print("== keyword 无命中 ->", st, "total=", page["total"])
assert page["total"] == 0, page

print("ALL PASS")
