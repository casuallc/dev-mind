# -*- coding: utf-8 -*-
"""CAP-08 构建执行器端到端验证。
前置：后端 :8080 已起；tests/fixtures/TestSshServer(2222) 已起（远程构建用）。
覆盖：构建配置、本地多步骤构建（顺序/上下文 env/artifact 登记）、并发 409、失败路径、
远程构建（SSH + 模板白名单 + commit/branch 上下文）、历史筛选、WS 日志实时流。
"""
import json
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = "http://localhost:8080/api"
ROOT = pathlib.Path(r"D:/apusic/dev-mind")
passed = 0
failed = 0

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")


def call(method, path, body=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=40) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def call_text(path):
    with urllib.request.urlopen(quote(BASE + path, safe=":/?&=%,.-"), timeout=40) as r:
        return r.status, r.read().decode("utf-8", "replace")


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def git(args):
    out = subprocess.run(["git", "-C", str(ROOT)] + args, capture_output=True, text=True)
    return out.stdout.strip() if out.returncode == 0 else None


def replace_steps(pid, steps):
    for s in (call("GET", f"/projects/{pid}/build-steps")[1] or []):
        call("DELETE", f"/projects/{pid}/build-steps/{s['id']}")
    for i, st in enumerate(steps):
        st = dict(st)
        st["sortOrder"] = i
        st["workingDir"] = st.get("workingDir", "")
        st["location"] = st.get("location", "LOCAL")
        r = call("POST", f"/projects/{pid}/build-steps", st)
        assert r[0] == 200, f"建步骤失败: {r}"


def wait_build(bid, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, b = call("GET", f"/builds/{bid}")
        if st == 200 and b["status"] in ("SUCCESS", "FAILED"):
            return b
        time.sleep(1)
    raise TimeoutError(f"构建 #{bid} 未在 {timeout}s 内结束")


def trigger_and_wait(pid, body, expect_status):
    st, b = call("POST", f"/projects/{pid}/builds", body)
    check(f"触发构建 #{b.get('id')}", st == 200 and b.get("id"), f"{st} {b}")
    b = wait_build(b["id"])
    check(f"构建 #{b['id']} 终态 {expect_status}", b["status"] == expect_status, str(b))
    return b


def main():
    # ---------- 0. 项目（复用 CAP07 测试项目：真实 git 仓库 D:/apusic/dev-mind） ----------
    st, projects = call("GET", "/projects")
    proj = next((p for p in (projects or []) if p.get("name") == "CAP07 测试"), None)
    if not proj:
        st, proj = call("POST", "/projects", {
            "name": "CAP07 测试", "path": str(ROOT).replace("/", "\\"), "defaultBranch": "master", "tags": []})
        check("创建项目", st == 200 and proj, f"{st} {proj}")
    pid = proj["id"]
    print(f"  [项目 {pid}]")

    # ---------- 0b. 清理上次遗留（含卡死 QUEUED 的历史构建） ----------
    for s in (call("GET", f"/projects/{pid}/servers")[1] or []):
        call("DELETE", f"/projects/{pid}/servers/{s['id']}")
    for t in (call("GET", f"/script-templates?projectId={pid}")[1] or []):
        call("DELETE", f"/script-templates/{t['id']}")
    for b in (call("GET", f"/builds?projectId={pid}")[1] or []):
        if b["status"] in ("QUEUED", "FAILED", "SUCCESS"):
            call("DELETE", f"/builds/{b['id']}")
    replace_steps(pid, [])

    # ---------- 1. 构建配置（FR-02） ----------
    st, cfg = call("PUT", f"/projects/{pid}/build-config", {"executor": "LOCAL", "concurrencyLimit": 1, "remoteServerId": None})
    check("保存构建配置 LOCAL", st == 200 and cfg["executor"] == "LOCAL", f"{st} {cfg}")
    st, cfg = call("GET", f"/projects/{pid}/build-config")
    check("读取构建配置", st == 200 and cfg["executor"] == "LOCAL" and cfg["concurrencyLimit"] == 1, f"{st} {cfg}")
    st, bad = call("PUT", f"/projects/{pid}/build-config", {"executor": "KUBERNETES"})
    check("非法 executor → 400", st == 400, f"{st}")
    st, bad = call("PUT", f"/projects/{pid}/build-config", {"executor": "REMOTE"})
    check("REMOTE 未指定服务器 → 400", st == 400, f"{st}")

    # ---------- 2. 本地多步骤构建（FR-01/03/04） ----------
    head = git(["rev-parse", "HEAD"])
    branch = git(["symbolic-ref", "--short", "HEAD"])
    replace_steps(pid, [
        {"name": "准备", "command": "echo 准备阶段"},
        {"name": "打包", "command": 'echo "BUILD_BRANCH=${BUILD_BRANCH} BUILD_COMMIT=${BUILD_COMMIT:0:7}"\necho "artifact=cap08-local.jar"'},
        {"name": "收尾", "command": "echo step3-ok"},
    ])
    b = trigger_and_wait(pid, {}, "SUCCESS")
    check("本地构建 exitCode=0", b["exitCode"] == 0, str(b))
    check("产物登记 artifactRef", b["artifactRef"] == "cap08-local.jar", str(b))
    check("本地构建记录分支", branch and b["branch"] == branch, f"{b.get('branch')} vs {branch}")
    check("本地构建记录 commit", head and b["commit"] == head, f"{b.get('commit')} vs {head}")
    st, logs = call_text(f"/builds/{b['id']}/logs")
    for frag in ["准备阶段", "打包", "step3-ok", "artifact=cap08-local.jar", f"BUILD_BRANCH={branch}", "步骤 3/3"]:
        check(f"日志含 {frag[:30]}", st == 200 and frag in (logs or ""), f"{st}")

    # ---------- 3. 并发上限 409（FR-07） ----------
    replace_steps(pid, [{"name": "慢构建", "command": "sleep 4; echo slow-done"}])
    st, a = call("POST", f"/projects/{pid}/builds", {})
    check("触发慢构建 A", st == 200, f"{st}")
    st, dup = call("POST", f"/projects/{pid}/builds", {})
    check("并发超限 → 409", st == 409, f"{st} {dup}")
    wait_build(a["id"])
    print("  [慢构建 A 结束]")

    # ---------- 4. 失败路径（FR-06） ----------
    replace_steps(pid, [{"name": "必然失败", "command": "echo before-fail\nexit 7"}])
    b = trigger_and_wait(pid, {}, "FAILED")
    check("失败构建 exitCode=7", b["exitCode"] == 7, str(b))
    check("失败错误摘要含 exit=7", "exit=7" in (b["errorSummary"] or ""), str(b["errorSummary"]))
    st, logs = call_text(f"/builds/{b['id']}/logs")
    check("失败日志含执行痕迹", "before-fail" in (logs or ""), f"{st}")

    # ---------- 5. 远程构建（FR-02 经 SSH + 模板白名单） ----------
    st, ssh = call("POST", f"/projects/{pid}/servers", {
        "name": "本地SSH", "env": "test", "accessType": "ssh",
        "accessConfig": json.dumps({"host": "127.0.0.1", "port": 2222, "username": "test",
                                    "authType": "password", "password": "testpw"}, ensure_ascii=False),
        "capabilities": ["build", "deploy", "exec", "logs", "health"], "enabled": True})
    ssh_id = ssh.get("id") if ssh else None
    check("注册 SSH 服务器", st == 200 and ssh_id, f"{st} {ssh}")
    st, tpl = call("POST", "/script-templates", {
        "projectId": pid, "code": "build", "name": "远程构建",
        "templateText": 'echo "remote-build start"\necho "branch=${branch} commit=${commit}"\necho "artifact=cap08-remote.jar"',
        "params": [{"name": "branch", "required": False, "label": "分支"},
                   {"name": "commit", "required": False, "label": "提交"}],
        "allowed": ["build"]})
    check("创建 build 模板", st == 200, f"{st} {tpl}")
    st, cfg = call("PUT", f"/projects/{pid}/build-config", {"executor": "REMOTE", "remoteServerId": ssh_id, "concurrencyLimit": 1})
    check("切换 REMOTE 配置", st == 200 and cfg["executor"] == "REMOTE", f"{st} {cfg}")
    # 未配置模板（command 不在白名单）→ 404
    replace_steps(pid, [{"name": "幽灵模板", "command": "ghost-template"}])
    st, ghost = call("POST", f"/projects/{pid}/builds", {})
    g = wait_build(ghost["id"])
    check("远程模板白名单外 → FAILED", st == 200 and g["status"] == "FAILED", str(g))
    # 正常远程构建，显式传 commit/branch 上下文
    replace_steps(pid, [{"name": "远程部署", "command": "build"}])
    b = trigger_and_wait(pid, {"commit": "abc12345", "branch": "main"}, "SUCCESS")
    check("远程构建 executor=REMOTE", b["executor"] == "REMOTE", str(b))
    check("远程产物登记", b["artifactRef"] == "cap08-remote.jar", str(b))
    st, logs = call_text(f"/builds/{b['id']}/logs")
    check("远程日志含上下文", "branch=main commit=abc12345" in (logs or ""), f"{st}")

    # ---------- 6. WS 实时流（FR-05） ----------
    call("PUT", f"/projects/{pid}/build-config", {"executor": "LOCAL", "concurrencyLimit": 1, "remoteServerId": None})
    replace_steps(pid, [{"name": "慢构建", "command": "sleep 5; echo ws-live-line"}])
    st, b = call("POST", f"/projects/{pid}/builds", {})
    ws_script = ROOT / "tmp" / "cap08-ws-test.mjs"
    r = subprocess.run(["node", str(ws_script), str(b["id"]), "SUCCESS"],
                        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=40)
    out = r.stdout + r.stderr
    check("WS 收到日志帧+done", r.returncode == 0 and "WS_OK" in out, out[-300:])
    wait_build(b["id"])

    # ---------- 7. 历史筛选（FR-07） ----------
    st, all_builds = call("GET", f"/builds?projectId={pid}")
    check("历史列表含记录", st == 200 and len(all_builds) >= 5, f"{st} n={len(all_builds or [])}")
    st, ok_builds = call("GET", f"/builds?projectId={pid}&status=SUCCESS")
    check("历史按状态筛选 SUCCESS", st == 200 and all(x["status"] == "SUCCESS" for x in ok_builds), f"{st}")
    st, miss = call("GET", "/builds/999999")
    check("不存在构建 → 404", st == 404, f"{st}")

    # ---------- 清理 ----------
    for s in (call("GET", f"/projects/{pid}/servers")[1] or []):
        call("DELETE", f"/projects/{pid}/servers/{s['id']}")
    for t in (call("GET", f"/script-templates?projectId={pid}")[1] or []):
        call("DELETE", f"/script-templates/{t['id']}")
    replace_steps(pid, [])
    call("PUT", f"/projects/{pid}/build-config", {"executor": "LOCAL", "concurrencyLimit": 1, "remoteServerId": None})

    print(f"\n== CAP-08 验证结果: {passed} passed, {failed} failed ==")
    return failed == 0


if __name__ == "__main__":
    sys.exit(0 if main() else 1)
