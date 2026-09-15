# CAP-29 E2E（临时脚本，gitignored）：全局仓库登记(ADMIN) → 克隆 READY+branches →
# 项目添加仓库关联全局行（不复制） → 手动 fetch 同步新分支 → 工时订阅后 preview 扫服务端克隆 → 被引用删除 409
import json, subprocess, sys, time, urllib.request, urllib.error, datetime, os, shutil

BASE = "http://localhost:8081"
DATA = r"D:\apusic\dev-mind\tmp\cap29-data"
ORIGIN = os.path.join(DATA, "origin.git").replace("\\", "/")
TODAY = datetime.date.today().isoformat()

def sh(*args, cwd=None):
    subprocess.run(args, cwd=cwd, check=True, capture_output=True)

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
    print(f"== {label} -> {st}")
    print((json.dumps(body, ensure_ascii=False, indent=1)[:500] if body is not None else "") + "\n")

def fail(msg):
    print("FAIL: " + msg); sys.exit(1)

# ---- 0 种子远端仓库（file:// 匿名通道，master 分支 + 一次提交） ----
work = os.path.join(DATA, "seed")
shutil.rmtree(work, ignore_errors=True); shutil.rmtree(ORIGIN, ignore_errors=True)
sh("git", "init", "-b", "master", work)
sh("git", "-C", work, "config", "user.email", "admin@example.com")
# 扫描器按平台用户 displayName/email 过滤署名（CAP-28 设计），admin 的 displayName 是「管理员」
sh("git", "-C", work, "config", "user.name", "管理员")
open(os.path.join(work, "README.md"), "w").write("cap29")
sh("git", "-C", work, "add", ".")
sh("git", "-C", work, "commit", "-m", "cap29 init")
sh("git", "clone", "--bare", work, ORIGIN)
ORIGIN_URL = "file:///" + ORIGIN

st, login = call("POST", "/api/auth/login", {"username": "admin", "password": "admin123"})
assert st == 200, login
token = login["accessToken"]

# ---- 1 全局登记 CLONE（管理员） ----
st, repo = call("POST", "/api/repos", {"name": "cap29-origin", "sourceType": "CLONE",
                                       "remoteUrl": ORIGIN_URL}, token)
show("1 登记 CLONE 仓库", st, repo)
assert st in (200, 201), repo
rid = repo["id"]
assert repo["cloneStatus"] == "CLONING", repo
assert "_global" in repo["localPath"].replace("\\", "/"), repo

# ---- 2 轮询克隆完成 → READY + 分支列表 + 默认分支 ----
deadline = time.time() + 90
while time.time() < deadline:
    st, repo = call("GET", f"/api/repos/{rid}", token=token)
    if repo.get("cloneStatus") in ("READY", "FAILED"):
        break
    time.sleep(2)
show("2 克隆终态", st, repo)
assert repo["cloneStatus"] == "READY", repo
assert repo["defaultBranch"] == "master", repo
assert "master" in (repo.get("branches") or []), repo
assert os.path.isdir(os.path.join(repo["localPath"], ".git")), "服务端克隆目录不存在"

# ---- 3 项目添加仓库 = 关联全局行（同 URL 不新建、不复制） ----
st, proj = call("POST", "/api/projects", {"name": "cap29-demo", "sourceType": "CLONE",
                                          "remoteUrl": ORIGIN_URL, "defaultBranch": "master"}, token)
show("3a 创建项目(CLONE 同 URL)", st, proj)
assert st in (200, 201), proj
pid = proj["id"]
assert proj["path"].replace("\\", "/") == repo["localPath"].replace("\\", "/"), \
    f"项目路径应直接关联全局克隆目录: {proj['path']} != {repo['localPath']}"
assert proj["cloneStatus"] == "READY", proj  # 全局行已 READY，镜像立即可用

st, repos_after = call("GET", "/api/repos", token=token)
assert len(repos_after) == 1, f"同 URL 不应新建全局行: {len(repos_after)}"

st, proj2 = call("POST", "/api/projects", {"name": "cap29-demo-2", "sourceType": "CLONE",
                                           "remoteUrl": ORIGIN_URL}, token)
show("3b 第二个项目关联同一仓库", st, proj2)
assert st in (200, 201), proj2
assert len(call("GET", "/api/repos", token=token)[1]) == 1, "多项目共享同一全局行"

# ---- 4 远端新增分支 → 手动 fetch 同步 ----
sh("git", "-C", work, "checkout", "-b", "feature-x")
open(os.path.join(work, "f.txt"), "w").write("x")
sh("git", "-C", work, "add", "."); sh("git", "-C", work, "commit", "-m", "feat x")
sh("git", "-C", work, "push", ORIGIN_URL, "feature-x")
sh("git", "-C", work, "checkout", "master")

st, ack = call("POST", f"/api/repos/{rid}/fetch", {}, token)
show("4a 手动 fetch", st, ack)
assert st == 200, ack
st, repo = call("GET", f"/api/repos/{rid}", token=token)
show("4b fetch 后分支列表", st, repo.get("branches"))
assert "feature-x" in (repo.get("branches") or []), repo
assert repo.get("lastFetchAt"), repo
assert not repo.get("lastFetchError"), repo

# ---- 5 工时订阅 + preview 扫服务端克隆（今天 master 上的 cap29 init 提交） ----
st, _ = call("PUT", f"/api/worklog/repos/{rid}/subscription", {"subscribed": True}, token)
assert st == 200
st, commits = call("GET", f"/api/worklog/git/preview?date={TODAY}", token=token)
show("5 git preview（服务端克隆）", st, commits)
assert st == 200, commits
assert any(c["subject"] == "cap29 init" for c in commits), "preview 应扫到服务端克隆上的提交"

# ---- 6 被项目引用的全局仓库禁止删除 ----
st, err = call("DELETE", f"/api/repos/{rid}", token=token)
show("6 被引用删除应 409", st, err)
assert st == 409, (st, err)

# ---- 7 非 ADMIN 写操作应 403（种一个普通用户） ----
st, _ = call("POST", "/api/auth/users", {"username": "cap29user", "password": "pass12345",
                                         "displayName": "u", "role": "DEVELOPER"}, token)
if st in (200, 201):
    st, login2 = call("POST", "/api/auth/login", {"username": "cap29user", "password": "pass12345"})
    t2 = login2["accessToken"]
    st, body = call("POST", "/api/repos", {"name": "x", "sourceType": "CLONE",
                                           "remoteUrl": "https://x/y.git"}, t2)
    show("7 非 ADMIN 登记应 403", st, body)
    assert st == 403, (st, body)
    st, lst = call("GET", "/api/repos", token=t2)
    assert st == 200, "GET 应对全认证用户开放（订阅页用）"
else:
    print(f"== 7 跳过（用户创建 {st}）")

print("\nALL PASS")
