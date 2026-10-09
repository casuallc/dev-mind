# -*- coding: utf-8 -*-
"""回归全绿 → 需求验收联动 E2E（test.completed 事件 → rollup ACCEPTANCE + 待验收通知）。
前置：后端实例（默认 http://localhost:8080，argv1 可换；建议按 tests/README.md 起独立 e2e 实例 :8090）。
无需外部 fixture：http 用例直接打应用自身 /api/health。
覆盖：活跃 WI 时绿跑不联动 → WI 完结 rollup ACCEPTANCE → 绿跑广播 REQUIREMENT_ACCEPTANCE_READY
→ 失败跑不广播 → 无 workItemId 的项目级跑不联动。
用法: python tests/cap10_acceptance_e2e.py [base] [user] [pwd]
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/") + "/api"
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PWD = sys.argv[3] if len(sys.argv) > 3 else "admin123"
TOKEN = None
PASS, FAIL = [], []


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print(f"[PASS] {name}")
    else:
        FAIL.append(name)
        print(f"[FAIL] {name} {detail}")


def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN:
        r.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode()
            ctype = resp.headers.get_content_type()
            return resp.status, (json.loads(txt) if txt and ctype.startswith("application/json") else txt)
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def get(path):
    return call("GET", path)


def wait_run(run_id, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        code, r = get(f"/test-runs/{run_id}")
        if code == 200 and isinstance(r, dict) and r.get("status") in ("SUCCESS", "FAILED"):
            return r
        time.sleep(1)
    raise TimeoutError(f"测试运行 #{run_id} 未在 {timeout}s 内出终态")


def acceptance_ready_notifs(pid):
    code, notifs = get("/notifications")
    nlist = notifs if isinstance(notifs, list) else []
    return [n for n in nlist if n.get("eventType") == "REQUIREMENT_ACCEPTANCE_READY"
            and n.get("projectId") == pid]


# ---------------- 0. 登录 ----------------
code, login = call("POST", "/auth/login", {"username": USER, "password": PWD})
TOKEN = (login.get("token") or login.get("accessToken")) if isinstance(login, dict) else None
check("登录取得 token", code == 200 and bool(TOKEN), f"{code} {str(login)[:120]}")
if not TOKEN:
    sys.exit(1)

APP_BASE = BASE.rsplit("/api", 1)[0]  # 用例直打应用自身健康端点

# ---------------- 1. 项目 + 需求 + 工作单元 ----------------
import pathlib
import shutil
import subprocess
REPO = pathlib.Path(r"D:/apusic/dev-mind/tmp/cap10-acc-repo")
if REPO.exists():
    shutil.rmtree(REPO)
REPO.mkdir(parents=True)
subprocess.run(["git", "init", str(REPO)], capture_output=True)
subprocess.run(["git", "-C", str(REPO), "config", "user.email", "t@t"], capture_output=True)
subprocess.run(["git", "-C", str(REPO), "config", "user.name", "t"], capture_output=True)
(REPO / "README.md").write_text("acc e2e\n", encoding="utf-8")
subprocess.run(["git", "-C", str(REPO), "add", "-A"], capture_output=True)
subprocess.run(["git", "-C", str(REPO), "commit", "-m", "init"], capture_output=True)

proj_name = "acceptance-e2e-" + str(int(time.time()))
code, proj = call("POST", "/projects", {
    "name": proj_name, "path": str(REPO).replace("\\", "/"), "tags": ["e2e"],
    "description": "验收联动验证项目"})
check("创建项目", code == 200 and isinstance(proj, dict) and proj.get("id"), str(proj)[:120])
PID = proj["id"]

code, req = call("POST", f"/projects/{PID}/requirements", {"title": "验收联动需求"})
check("创建需求", code == 200 and req.get("id"), str(req)[:120])
RID = req["id"]
code, wi = call("POST", f"/projects/{PID}/requirements/{RID}/work-items",
                {"title": "联动工作单元", "type": "DEVELOPMENT"})
check("创建工作单元", code == 200 and wi.get("id"), str(wi)[:120])
WID = wi["id"]
code, req0 = get(f"/projects/{PID}/requirements/{RID}")
check("WI 活跃 → 需求 IN_PROGRESS", code == 200 and req0.get("status") == "IN_PROGRESS", str(req0)[:160])

# ---------------- 2. 测试套件（http 用例打应用自身 /api/health） ----------------
code, suite = call("POST", f"/projects/{PID}/test-suites", {"name": "健康套件", "kind": "api"})
check("创建套件", code == 200 and suite.get("id"), str(suite)[:120])
SID = suite["id"]
code, s2 = call("PUT", f"/test-suites/{SID}/cases", [{
    "name": "health-200", "kind": "http", "method": "GET", "path": "/api/health",
    "params": {}, "headers": {}, "body": None, "expected": {"status": 200}, "enabled": True}])
check("套件加 http 用例", code == 200 and len(s2.get("cases") or []) == 1, str(s2)[:160])


def run_test(work_item_id):
    st, run = call("POST", "/tests/runs", {"projectId": PID, "suiteIds": [SID],
                                           "workItemId": work_item_id, "baseUrl": APP_BASE})
    assert st == 200 and run.get("id"), f"触发测试失败: {st} {str(run)[:160]}"
    return wait_run(run["id"])


# ---------------- 3. WI 活跃时绿跑 → 不联动（回归绿 ≠ 开发完结） ----------------
r1 = run_test(WID)
check("WI 活跃时绿跑 SUCCESS", r1["status"] == "SUCCESS", f"{r1['status']} {r1.get('errorSummary')}")
code, req1 = get(f"/projects/{PID}/requirements/{RID}")
check("需求仍 IN_PROGRESS（不联动）", code == 200 and req1.get("status") == "IN_PROGRESS", str(req1)[:160])
check("无待验收通知", len(acceptance_ready_notifs(PID)) == 0, str(acceptance_ready_notifs(PID))[:160])

# ---------------- 4. WI 完结 → rollup ACCEPTANCE；绿跑 → 待验收通知 ----------------
code, wix = call("PUT", f"/projects/{PID}/requirements/{RID}/work-items/{WID}/status", {"status": "DONE"})
check("工作单元置 DONE", code == 200 and wix.get("status") == "DONE", str(wix)[:120])
code, req2 = get(f"/projects/{PID}/requirements/{RID}")
check("rollup → ACCEPTANCE", code == 200 and req2.get("status") == "ACCEPTANCE", str(req2)[:160])

r2 = run_test(WID)
check("绿跑 SUCCESS", r2["status"] == "SUCCESS", f"{r2['status']} {r2.get('errorSummary')}")
ready = acceptance_ready_notifs(PID)
check("广播 REQUIREMENT_ACCEPTANCE_READY", len(ready) == 1, f"count={len(ready)}")
if ready:
    check("通知标题=回归通过 · 需求待验收", ready[0].get("title") == "回归通过 · 需求待验收",
          str(ready[0])[:200])
    check("通知关联需求实体", ready[0].get("entityType") == "REQUIREMENT"
          and ready[0].get("entityId") == RID, str(ready[0])[:200])
code, req3 = get(f"/projects/{PID}/requirements/{RID}")
check("需求保持 ACCEPTANCE", code == 200 and req3.get("status") == "ACCEPTANCE", str(req3)[:160])

# ---------------- 5. 失败跑 → 不广播 ----------------
code, s3 = call("PUT", f"/test-suites/{SID}/cases", [{
    "name": "health-200", "kind": "http", "method": "GET", "path": "/api/health",
    "params": {}, "headers": {}, "body": None, "expected": {"status": 500}, "enabled": True}])
r3 = run_test(WID)
check("故意失败跑 FAILED", r3["status"] == "FAILED", f"{r3['status']}")
check("失败跑不新增待验收通知", len(acceptance_ready_notifs(PID)) == 1,
      f"count={len(acceptance_ready_notifs(PID))}")

# ---------------- 6. 项目级跑（无 workItemId）不联动 ----------------
call("PUT", f"/test-suites/{SID}/cases", [{
    "name": "health-200", "kind": "http", "method": "GET", "path": "/api/health",
    "params": {}, "headers": {}, "body": None, "expected": {"status": 200}, "enabled": True}])
st, run4 = call("POST", "/tests/runs", {"projectId": PID, "suiteIds": [SID], "baseUrl": APP_BASE})
assert st == 200 and run4.get("id"), f"触发项目级测试失败: {st} {str(run4)[:160]}"
r4 = wait_run(run4["id"])
check("项目级跑 SUCCESS", r4["status"] == "SUCCESS", f"{r4['status']}")
check("项目级跑不新增待验收通知", len(acceptance_ready_notifs(PID)) == 1,
      f"count={len(acceptance_ready_notifs(PID))}")

# ---------------- 汇总 ----------------
print("\n===== 验收联动验证汇总 =====")
print(f"PASS {len(PASS)} / FAIL {len(FAIL)}")
if FAIL:
    for f in FAIL:
        print("  FAILED:", f)
    sys.exit(1)
print("全部通过")
