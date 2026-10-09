# -*- coding: utf-8 -*-
# CAP-11 发版执行器端到端验证（对运行中的本地应用，localhost:8080）
# 覆盖：LOCAL 执行（渲染模板正文在主库执行）→ git tag v<version>（FR-04）→ 版本自动递增（FR-02）
# → WS 实时（/ws/releases/{id}/stream）→ 失败路径（P0 通知）→ 回滚删 tag（FR-06）→ 通知（FR-07）。
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import websocket  # pip install websocket-client

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/") + "/api"
REPO = "D:/apusic/dev-mind/tmp/cap11-repo"
PASS, FAIL = [], []
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PWD = sys.argv[3] if len(sys.argv) > 3 else "admin123"
TOKEN = None


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


def tags_of():
    out = subprocess.run(["git", "-C", REPO, "tag", "-l"], capture_output=True, text=True).stdout
    return out.split()


# ---------------- 0. 准备独立 git 仓库 ----------------
if os.path.exists(REPO):
    shutil.rmtree(REPO)
os.makedirs(REPO)
subprocess.run(["git", "init", REPO], capture_output=True)
subprocess.run(["git", "-C", REPO, "config", "user.email", "t@t"], capture_output=True)
subprocess.run(["git", "-C", REPO, "config", "user.name", "t"], capture_output=True)
open(os.path.join(REPO, "README.md"), "w").write("cap11 test\n")
subprocess.run(["git", "-C", REPO, "add", "-A"], capture_output=True)
subprocess.run(["git", "-C", REPO, "commit", "-m", "init"], capture_output=True)
check("准备 git 仓库", os.path.isdir(os.path.join(REPO, ".git")))

# ---------------- 0b. 登录（写操作需鉴权，CAP-01） ----------------
code, login = call("POST", "/auth/login", {"username": USER, "password": PWD})
TOKEN = (login.get("token") or login.get("accessToken")) if isinstance(login, dict) else None
check("登录取得 token", code == 200 and bool(TOKEN), f"{code} {str(login)[:120]}")
if not TOKEN:
    sys.exit(1)

# ---------------- 1. 项目 + 模板 + 发版配置 ----------------
proj_name = "cap11-verify-" + str(int(time.time()))
code, proj = call("POST", "/projects", {
    "name": proj_name, "path": REPO, "tags": ["cap11"], "description": "CAP-11 验证项目"})
check("创建项目", code == 200 and proj.get("id"), str(proj)[:120])
PID = proj["id"]

code, tpl = call("POST", "/script-templates", {
    "projectId": PID, "code": "nexus_push", "name": "Nexus 推送(mock)",
    "templateText": '#!/bin/bash\necho "Nexus push: repository=${repository} version=${version} artifact=${artifact}"\n'
                    'mkdir -p target\necho "release:${version}" > target/release-ref.txt\n'
                    'echo "nexus-ref=releases:${version}"\n',
    "params": [
        {"name": "version", "required": True},
        {"name": "artifact", "required": False, "defaultValue": "demo.jar"},
        {"name": "repository", "required": False},
    ],
    "allowed": ["release"]})
check("创建推送模板 nexus_push", code == 200 and tpl.get("id"), str(tpl)[:120])

code, tplb = call("POST", "/script-templates", {
    "projectId": PID, "code": "nexus_push_bad", "name": "坏推送模板",
    "templateText": "#!/bin/bash\necho will-fail\nexit 1\n",
    "params": [], "allowed": ["release"]})
check("创建坏模板 nexus_push_bad", code == 200 and tplb.get("id"), str(tplb)[:120])

code, cfg = call("POST", f"/projects/{PID}/release-config", {
    "nexusRepo": "releases", "scriptTemplateRef": "nexus_push",
    "versionRule": "1.0.0", "executor": "LOCAL"})
check("保存发版配置(LOCAL)", code == 200 and cfg.get("executor") == "LOCAL", str(cfg)[:120])

# ---------------- 1b. 需求 + 工作单元 + 构建（发版必须关联构建；归集依赖 tag 区间 commit 的 WI-<seq> 引用） ----------------
code, req = call("POST", f"/projects/{PID}/requirements", {"title": "登录优化"})
check("创建需求", code == 200 and req.get("id"), str(req)[:120])
RID = req["id"]
code, wi = call("POST", f"/projects/{PID}/requirements/{RID}/work-items",
                {"title": "登录优化实现", "type": "DEVELOPMENT"})
check("创建工作单元", code == 200 and wi.get("seq"), str(wi)[:120])
WI_SEQ = wi["seq"]


def commit_file(name, message):
    open(os.path.join(REPO, name), "w", encoding="utf-8").write(message + "\n")
    subprocess.run(["git", "-C", REPO, "add", "-A"], capture_output=True)
    subprocess.run(["git", "-C", REPO, "commit", "-m", message], capture_output=True)


def trigger_build():
    st, b = call("POST", f"/projects/{PID}/builds", {})
    assert st == 200 and b.get("id"), f"触发构建失败: {st} {str(b)[:160]}"
    deadline = time.time() + 90
    while time.time() < deadline:
        st, b = call("GET", f"/builds/{b['id']}")
        if st == 200 and b["status"] in ("SUCCESS", "FAILED"):
            return b
        time.sleep(1)
    raise TimeoutError("构建未在 90s 内结束")


call("PUT", f"/projects/{PID}/build-config", {"executor": "LOCAL", "concurrencyLimit": 1})
code, stp = call("POST", f"/projects/{PID}/build-steps", {
    "name": "打包", "command": 'echo "artifact=cap11-demo.jar"',
    "sortOrder": 0, "workingDir": "", "location": "LOCAL"})
check("创建构建步骤(登记产物)", code == 200, str(stp)[:120])

commit_file("wi-feature.txt", f"WI-{WI_SEQ} 登录优化实现")
B1 = trigger_build()
check("构建#1 SUCCESS + 产物登记", B1["status"] == "SUCCESS" and B1.get("artifactRef") == "cap11-demo.jar",
      str(B1)[:160])
B1ID = B1["id"]

# 缺 buildId → 400（发版必须关联构建，产物可溯）
code, nobuild = call("POST", "/releases", {"projectId": PID})
check("缺 buildId 创建发版 → 400", code == 400, f"{code} {str(nobuild)[:120]}")

# ---------------- 2. 发版 #1：版本自动 1.0.0，执行成功 + tag ----------------
code, r1 = call("POST", "/releases", {"projectId": PID, "buildId": B1ID})
check("创建发版#1(自动版本)", code == 200 and r1.get("version") == "1.0.0" and r1.get("status") == "PLANNED", str(r1)[:160])
R1 = r1["id"]

code, r1x = call("POST", f"/releases/{R1}/execute")
check("执行发版#1 -> RUNNING", code == 200 and r1x.get("status") == "RUNNING", str(r1x)[:120])

r1f = None
for _ in range(40):
    time.sleep(0.5)
    code, r1f = get(f"/releases/{R1}")
    if code == 200 and r1f.get("status") not in ("PLANNED", "RUNNING"):
        break
check("发版#1 终态 SUCCESS", r1f and r1f.get("status") == "SUCCESS", str(r1f)[:200])
check("发版#1 tag=v1.0.0", r1f and r1f.get("tagName") == "v1.0.0", str(r1f)[:160])
check("发版#1 nexusRef=releases:1.0.0", r1f and r1f.get("nexusRef") == "releases:1.0.0", str(r1f)[:160])
check("发版#1 日志含 [git tag] v1.0.0",
      (get(f"/releases/{R1}/logs")[1] or "").find("[git tag] v1.0.0") >= 0)
check("仓库存在 tag v1.0.0", "v1.0.0" in tags_of())
# LOCAL 执行后目标文件落库
check("LOCAL 执行写了 target/release-ref.txt",
      os.path.isfile(os.path.join(REPO, "target", "release-ref.txt")))
# 首个发版无历史 tag，归集跳过
check("发版#1 日志含[归集]无历史 tag 跳过",
      (get(f"/releases/{R1}/logs")[1] or "").find("[归集] 无历史成功发版 tag") >= 0)

# ---------------- 3. 发版 #2：版本自动 1.0.1 + 需求归集 + 验收中需求自动完结 ----------------
# WI 完结 → 需求 rollup 到 ACCEPTANCE（验收中），发版成功后应被自动完结为 DONE
code, wix = call("PUT", f"/projects/{PID}/requirements/{RID}/work-items/{wi['id']}/status", {"status": "DONE"})
check("工作单元置 DONE", code == 200 and wix.get("status") == "DONE", str(wix)[:120])
code, reqx = get(f"/projects/{PID}/requirements/{RID}")
check("需求 rollup → ACCEPTANCE", code == 200 and reqx.get("status") == "ACCEPTANCE", str(reqx)[:160])

commit_file("wi-fix.txt", f"WI-{WI_SEQ} 修复验收问题")
B2 = trigger_build()
check("构建#2 SUCCESS", B2["status"] == "SUCCESS", str(B2)[:160])

code, r2 = call("POST", "/releases", {"projectId": PID, "buildId": B2["id"]})
check("发版#2 自动版本 1.0.1", code == 200 and r2.get("version") == "1.0.1", str(r2)[:160])
R2 = r2["id"]
call("POST", f"/releases/{R2}/execute")
for _ in range(40):
    time.sleep(0.5)
    code, r2f = get(f"/releases/{R2}")
    if code == 200 and r2f.get("status") not in ("PLANNED", "RUNNING"):
        break
check("发版#2 SUCCESS + tag v1.0.1", r2f.get("status") == "SUCCESS" and "v1.0.1" in tags_of(), str(r2f)[:160])
# 归集：v1.0.0..v1.0.1 区间 commit 含 WI-<seq> → includedWorkItems 带出；验收中需求自动完结 DONE
inc_wi = [w.get("code") for w in (r2f.get("includedWorkItems") or [])]
check("发版#2 归集工作单元 WI-" + str(WI_SEQ), f"WI-{WI_SEQ}" in inc_wi, str(r2f)[:200])
code, reqy = get(f"/projects/{PID}/requirements/{RID}")
check("发版成功 → 验收中需求自动完结 DONE", code == 200 and reqy.get("status") == "DONE", str(reqy)[:160])
check("发版#2 日志含[归集]自动完结",
      (get(f"/releases/{R2}/logs")[1] or "").find("[归集] 需求 REQ-") >= 0)

# ---------------- 4. 发版 #3：WS 实时流 ----------------
code, r3 = call("POST", "/releases", {"projectId": PID, "buildId": B2["id"], "version": "1.0.2"})
R3 = r3["id"]
ws = websocket.create_connection(f"ws://{BASE.split('://', 1)[1].split('/')[0]}/ws/releases/{R3}/stream", timeout=20)
call("POST", f"/releases/{R3}/execute")
frames = []
deadline = time.time() + 30
while time.time() < deadline:
    try:
        f = json.loads(ws.recv())
    except Exception:
        break
    frames.append(f)
    if f.get("type") == "done":
        break
ws.close()
types = [f.get("type") for f in frames]
check("WS 收到 snapshot 帧", "snapshot" in types, str(types)[:120])
check("WS 收到 log 帧", "log" in types, str(types)[:120])
check("WS done 帧 status=SUCCESS",
      any(f.get("type") == "done" and f.get("status") == "SUCCESS" for f in frames), str(frames)[:200])
check("发版#3 终态 SUCCESS + tag v1.0.2", "v1.0.2" in tags_of())

# ---------------- 5. 失败路径：坏模板 -> FAILED + P0 通知 ----------------
call("POST", f"/projects/{PID}/release-config", {
    "nexusRepo": "releases", "scriptTemplateRef": "nexus_push_bad",
    "versionRule": "1.0.0", "executor": "LOCAL"})
code, r4 = call("POST", "/releases", {"projectId": PID, "buildId": B1ID, "version": "2.0.0"})
R4 = r4["id"]
call("POST", f"/releases/{R4}/execute")
for _ in range(40):
    time.sleep(0.5)
    code, r4f = get(f"/releases/{R4}")
    if code == 200 and r4f.get("status") not in ("PLANNED", "RUNNING"):
        break
check("坏模板发版 FAILED", r4f.get("status") == "FAILED", str(r4f)[:200])
check("FAILED 有 errorSummary", r4f.get("errorSummary") is not None, str(r4f)[:200])
call("POST", f"/projects/{PID}/release-config", {
    "nexusRepo": "releases", "scriptTemplateRef": "nexus_push",
    "versionRule": "1.0.0", "executor": "LOCAL"})  # 恢复配置

# ---------------- 6. 回滚：删 tag + ROLLED_BACK ----------------
code, rb = call("POST", f"/releases/{R1}/rollback")
check("回滚发版#1 -> ROLLED_BACK", code == 200 and rb.get("status") == "ROLLED_BACK", str(rb)[:160])
check("回滚后 tag v1.0.0 已删除", "v1.0.0" not in tags_of())
check("回滚后 nexusRef 已移除", rb.get("nexusRef") is None, str(rb)[:160])
check("其他 tag 仍保留", "v1.0.1" in tags_of() and "v1.0.2" in tags_of())

# ---------------- 7. 通知（FR-07） ----------------
code, notifs = get("/notifications")
nlist = notifs if isinstance(notifs, list) else []
rel_notifs = [n for n in nlist if n.get("entityType") == "release"]
ok_success = sum(1 for n in rel_notifs if "发版成功" in (n.get("title") or ""))
ok_fail = sum(1 for n in rel_notifs if "发版失败" in (n.get("title") or "")
              and (n.get("level") == "P0"))
check("通知含成功发版(P1)", ok_success >= 3, f"success={ok_success}")
check("通知含失败发版(P0)", ok_fail >= 1, f"fail={ok_fail}")

# ---------------- 汇总 ----------------
print("\n===== CAP-11 验证汇总 =====")
print(f"PASS {len(PASS)} / FAIL {len(FAIL)}")
if FAIL:
    for f in FAIL:
        print("  FAILED:", f)
    sys.exit(1)
print("全部通过")
