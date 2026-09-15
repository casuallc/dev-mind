# -*- coding: utf-8 -*-
# CAP-23 项目仓库从 Git 克隆 端到端验证（对运行中的本地应用，localhost:8080）
# 覆盖：ssh 拒绝 → file:// 匿名克隆项目（目录布局/状态机/默认分支探测/日志/主库镜像/.git/config 无 token）
# → 多库 FAILED → 改地址重试到 READY → WS /ws/repo-clones 实时帧（node 子脚本）。
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

sys.stdout.reconfigure(encoding="utf-8")

BASE = "http://localhost:8080/api"
HERE = os.path.dirname(os.path.abspath(__file__))
TMP = os.path.join(HERE, "..", "tmp")  # 运行产物仍落 tmp/（gitignored）
BARE_SRC = os.path.join(TMP, "cap23-src")
BARE = os.path.join(TMP, "cap23-bare")
# spring-boot:run 的工作目录是 devmind-app/，工作区跟随启动目录
WORKSPACE = os.path.abspath(os.path.join(TMP, "..", "devmind-app", "data", "repositories"))
PASS, FAIL = [], []
TOKEN = None


def check(name, cond, detail=""):
    if cond:
        PASS.append(name)
        print(f"[PASS] {name}")
    else:
        FAIL.append(name)
        print(f"[FAIL] {name} {detail}")


def call(method, path, body=None, expect_json=True):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    if data is not None:
        r.add_header("Content-Type", "application/json")
    if TOKEN:
        r.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            txt = resp.read().decode()
            return resp.status, (json.loads(txt) if txt and expect_json else txt)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def file_url(p):
    return "file:///" + p.replace("\\", "/").replace("//", "/")


def wait_clone(project_id, timeout=120):
    """轮询项目主库镜像 cloneStatus 直到非 CLONING，返回最终状态"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, p = call("GET", f"/projects/{project_id}")
        if st == 200 and p.get("cloneStatus") not in (None, "CLONING"):
            return p
        time.sleep(2)
    return p


def wait_repo(project_id, repo_id, timeout=120):
    deadline = time.time() + timeout
    repo = {}
    while time.time() < deadline:
        st, repos = call("GET", f"/projects/{project_id}/repos")
        if st == 200:
            repo = next((r for r in repos if r["id"] == repo_id), {})
            if repo.get("cloneStatus") not in (None, "CLONING"):
                return repo
        time.sleep(2)
    return repo


# ---------------- 0. 准备裸仓库（main 分支，一个提交） ----------------
def force_rmtree(p):
    # Windows 上 .git/objects 只读，需先去只读位
    def _onexc(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)
    shutil.rmtree(p, onexc=_onexc)


for d in (BARE_SRC, BARE):
    if os.path.exists(d):
        force_rmtree(d)
os.makedirs(BARE_SRC)
subprocess.run(["git", "init", "-b", "main", BARE_SRC], capture_output=True)
subprocess.run(["git", "-C", BARE_SRC, "config", "user.email", "t@t"], capture_output=True)
subprocess.run(["git", "-C", BARE_SRC, "config", "user.name", "t"], capture_output=True)
open(os.path.join(BARE_SRC, "README.md"), "w", encoding="utf-8").write("cap23 e2e\n")
subprocess.run(["git", "-C", BARE_SRC, "add", "-A"], capture_output=True)
subprocess.run(["git", "-C", BARE_SRC, "commit", "-m", "init"], capture_output=True)
subprocess.run(["git", "clone", "--bare", BARE_SRC, BARE], capture_output=True)
check("prepare bare repo", os.path.isdir(os.path.join(BARE, "refs")))

# ---------------- 1. 登录 ----------------
st, body = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
TOKEN = body.get("token") or body.get("accessToken") if st == 200 and isinstance(body, dict) else None
check("login admin", TOKEN is not None, f"status={st} body={body}")

# ---------------- 2. ssh 协议拒绝 ----------------
st, body = call("POST", "/projects", {
    "name": "cap23-ssh-reject", "sourceType": "CLONE",
    "remoteUrl": "git@gitlab.example.com:g/r.git", "tags": []})
check("reject ssh remote", st == 400, f"status={st} body={body}")

# ---------------- 3. file:// 匿名克隆创建项目 ----------------
st, proj = call("POST", "/projects", {
    "name": "cap23-e2e", "sourceType": "CLONE",
    "remoteUrl": file_url(BARE), "defaultBranch": "", "tags": []})
check("create CLONE project", st == 200, f"status={st} body={proj}")
pid = proj.get("id", "") if st == 200 else ""
check("initial status CLONING", proj.get("cloneStatus") == "CLONING", str(proj))
check("path in workspace", pid and proj.get("path", "").replace("\\", "/").endswith(f"data/repositories/{pid}/main"),
      str(proj.get("path")))

proj = wait_clone(pid)
check("clone reaches READY", proj.get("cloneStatus") == "READY", str(proj))
check("defaultBranch auto-detected", proj.get("defaultBranch") == "main", str(proj.get("defaultBranch")))

clone_dir = os.path.join(WORKSPACE, pid, "main")
check("workspace dir cloned", os.path.isdir(os.path.join(clone_dir, ".git")), clone_dir)
if os.path.isdir(clone_dir):
    url = subprocess.run(["git", "-C", clone_dir, "config", "remote.origin.url"],
                         capture_output=True, text=True).stdout.strip()
    check("remote url clean (no token)", url == file_url(BARE), url)

st, repos = call("GET", f"/projects/{pid}/repos")
primary = next((r for r in repos if r.get("primary")), {}) if st == 200 else {}
check("primary repo READY + clonedAt", primary.get("cloneStatus") == "READY" and primary.get("clonedAt"),
      str(primary))

st, logs = call("GET", f"/projects/{pid}/repos/{primary.get('id')}/clone/logs")
check("clone logs recorded", st == 200 and "git clone" in logs.get("logs", ""),
      str(logs)[:200])

# ---------------- 4. 多库：FAILED → 改地址重试 READY ----------------
st, bad = call("POST", f"/projects/{pid}/repos", {
    "name": "bad-repo", "sourceType": "CLONE",
    "remoteUrl": file_url(os.path.join(TMP, "cap23-not-exist")), "role": "DOCS"})
check("add bad CLONE repo", st == 200, f"status={st} body={bad}")
bad = wait_repo(pid, bad.get("id"))
check("bad repo FAILED with error", bad.get("cloneStatus") == "FAILED" and bad.get("cloneError"),
      str(bad))
check("bad repo path under project dir",
      bad.get("path", "").replace("\\", "/").startswith(f"data/repositories/{pid}/".replace("/", "/"))
      or f"{pid}" in bad.get("path", ""), str(bad.get("path")))

st, fixed = call("PUT", f"/projects/{pid}/repos/{bad['id']}", {
    "name": "bad-repo", "sourceType": "CLONE", "remoteUrl": file_url(BARE), "role": "DOCS"})
check("update remoteUrl", st == 200, f"status={st} body={fixed}")
st, trig = call("POST", f"/projects/{pid}/repos/{bad['id']}/clone")
check("retry clone accepted", st == 200, f"status={st} body={trig}")
fixed = wait_repo(pid, bad["id"])
check("retry reaches READY", fixed.get("cloneStatus") == "READY", str(fixed))

# ---------------- 5. WS 实时帧（node 子脚本） ----------------
st, wsrepo = call("POST", f"/projects/{pid}/repos", {
    "name": "ws-repo", "sourceType": "CLONE", "remoteUrl": file_url(BARE), "role": "CONFIG"})
check("add ws CLONE repo", st == 200, f"status={st} body={wsrepo}")
if st == 200:
    node = shutil.which("node")
    r = subprocess.run([node, os.path.join(HERE, "cap23-ws-test.mjs"), str(wsrepo["id"])],
                       capture_output=True, timeout=60, encoding="utf-8", errors="replace")
    print(r.stdout.strip())
    check("WS snapshot+log+done frames", "WS_OK" in r.stdout, r.stdout + r.stderr)
    wsrepo = wait_repo(pid, wsrepo["id"])
    check("ws repo READY", wsrepo.get("cloneStatus") == "READY", str(wsrepo))

# ---------------- 6. 清理 ----------------
call("DELETE", f"/projects/{pid}")

print(f"\n===== PASS {len(PASS)} / FAIL {len(FAIL)} =====")
if FAIL:
    print("FAILURES:", FAIL)
    sys.exit(1)
