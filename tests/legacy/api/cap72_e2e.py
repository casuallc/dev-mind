#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-72 执行链自动化项目级开关 E2E（前置：app 起隔离实例，见 tests/README.md）。

链路：建项目（默认两开关关）→ 配构建步骤 → WI 翻 DONE 不触发构建 →
打开 autoBuildOnWiDone → 新 WI 翻 DONE 自动建构建单 → 更新不带开关字段保持原值 →
再关掉 → 新 WI 翻 DONE 不触发。

验收覆盖（CAP-72 §验收 1/2/4/5；验收 3 部署链路由 PipelineOrchestratorSwitchTest 覆盖——
E2E 里构造「构建成功」要真跑完构建，成本高且不影响闸门逻辑）：
  [1] 新项目默认关：GET 回读 autoBuildOnWiDone/autoDeployTestOnBuild 均为 false
  [2] 开关关：WI(DEVELOPMENT) 翻 DONE → 等 4s → 无构建记录
  [3] 开关开：新 WI 翻 DONE → 自动建构建单（record 带 workItemId）
  [4] 更新不传开关字段 = 保持原值（不回退关闭）
  [5] 再关闭：新 WI 翻 DONE → 不再触发

默认打本机 :8080；隔离实例用 E2E_BASE 覆盖（如 http://localhost:8090/api）。
注意：构建触发前服务端会对项目主库 git fetch（CAP-26），所以 fixture 仓库必须带
可用的 origin——脚本自建 file:// bare 库，落在 tmp/cap72-e2e/。
跑前先 taskkill 残留 :8080 dev app（不改就是在测老代码）。
"""
import json, os, shutil, subprocess, sys, time
import urllib.request, urllib.error

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
MARK = f"cap72-{int(time.time())}"
# 路径按次唯一：项目按仓库路径查重（409），重跑不能撞上次残留的项目行
FIXTURE = os.path.join(ROOT, "tmp", f"cap72-e2e-{MARK}")

# Windows 控制台默认 GBK，✓ 等字符会炸 UnicodeEncodeError
sys.stdout.reconfigure(encoding="utf-8", errors="replace")


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def git(*args, cwd=None):
    subprocess.run(["git", *args], cwd=cwd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def make_fixture_repo():
    """file:// bare 库 + 工作克隆（带 origin/master 可 fetch/rev-parse）"""
    shutil.rmtree(FIXTURE, ignore_errors=True)
    os.makedirs(FIXTURE, exist_ok=True)
    bare = os.path.join(FIXTURE, "remote.git")
    work = os.path.join(FIXTURE, "repo")
    git("init", "--bare", "-b", "master", bare)
    git("init", "-b", "master", work)
    with open(os.path.join(work, "a.txt"), "w") as f:
        f.write("cap72\n")
    git("add", ".", cwd=work)
    git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-m", "init", cwd=work)
    git("remote", "add", "origin", bare, cwd=work)
    git("push", "-u", "origin", "master", cwd=work)
    return work


def list_builds(token, pid):
    return req("GET", f"/builds?projectId={pid}", token=token)


def wait_builds(token, pid, n, timeout=15, what=""):
    t0 = time.time()
    while time.time() - t0 < timeout:
        rows = list_builds(token, pid)
        if len(rows) >= n:
            return rows
        time.sleep(0.5)
    raise AssertionError(f"超时等待构建记录: {what}（现有 {len(list_builds(token, pid))} 条）")


def flip_done(token, pid, rid, wi):
    req("PUT", f"/projects/{pid}/requirements/{rid}/work-items/{wi}/status",
        {"status": "DONE"}, token=token)


def main():
    token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
    print(f"[0] 登录 admin 成功（标记 {MARK}）")

    work = make_fixture_repo()
    p = req("POST", "/projects", {"name": MARK, "path": work, "defaultBranch": "master"}, token=token)
    pid = p["id"]
    # [1] 默认关
    assert p.get("autoBuildOnWiDone") is False and p.get("autoDeployTestOnBuild") is False, \
        f"新项目开关应默认关: {p}"
    print("[1] 新项目两开关默认关 ✓")

    req("POST", f"/projects/{pid}/build-steps",
        {"sortOrder": 0, "name": "noop", "command": "echo cap72", "location": "LOCAL"}, token=token)
    r = req("POST", f"/projects/{pid}/requirements", {"title": f"需求-{MARK}"}, token=token)
    rid = r["id"]

    # [2] 开关关：WI 翻 DONE 不触发
    wi1 = req("POST", f"/projects/{pid}/requirements/{rid}/work-items",
              {"type": "DEVELOPMENT", "title": f"WI1-{MARK}"}, token=token)["id"]
    flip_done(token, pid, rid, wi1)
    time.sleep(4)  # 事件同步派发，4s 足够；空即证闸门拦住
    assert list_builds(token, pid) == [], "开关关时不得产生构建记录"
    print("[2] 开关关：WI DONE 未触发构建 ✓")

    # [3] 打开自动构建：新 WI 翻 DONE 自动触发
    req("PUT", f"/projects/{pid}", {"name": MARK, "autoBuildOnWiDone": True}, token=token)
    wi2 = req("POST", f"/projects/{pid}/requirements/{rid}/work-items",
              {"type": "DEVELOPMENT", "title": f"WI2-{MARK}"}, token=token)["id"]
    flip_done(token, pid, rid, wi2)
    rows = wait_builds(token, pid, 1, what="开关开后自动构建")
    assert rows[0].get("workItemId") == wi2, f"构建单应挂 WI2: {rows[0]}"
    print("[3] 开关开：WI DONE 自动建构建单 ✓")

    # [4] 更新不带开关字段 = 保持
    req("PUT", f"/projects/{pid}", {"name": MARK, "description": "只改描述"}, token=token)
    p2 = req("GET", f"/projects/{pid}", token=token)
    assert p2.get("autoBuildOnWiDone") is True, f"不传开关字段应保持原值: {p2}"
    print("[4] 更新不传开关字段保持原值 ✓")

    # [5] 再关闭：新 WI 翻 DONE 不再触发
    req("PUT", f"/projects/{pid}", {"name": MARK, "autoBuildOnWiDone": False}, token=token)
    wi3 = req("POST", f"/projects/{pid}/requirements/{rid}/work-items",
              {"type": "DEVELOPMENT", "title": f"WI3-{MARK}"}, token=token)["id"]
    flip_done(token, pid, rid, wi3)
    time.sleep(4)
    assert len(list_builds(token, pid)) == 1, "关闭后不得再触发构建"
    print("[5] 关闭后 WI DONE 不再触发 ✓")

    shutil.rmtree(FIXTURE, ignore_errors=True)
    print("\n全部通过 ✓")


if __name__ == "__main__":
    main()
