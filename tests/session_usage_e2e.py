#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""会话用量账本 E2E（前置：后端已用新代码启动，runner jar 已重建 mvn -q install -DskipTests）。

链路：fake runner（result 帧带 total_cost_usd + usage 四项 token，与真实 claude 同 schema）
→ 起会话/问答 → /finish（EOF 触发 fake 发 result）→ 用量累计落 sessions/chat_sessions 账本列。
断言：turnCount=1、costUsd=0.0123、input/output/cacheRead/cacheCreation 与 fake 常量一致；
会话（挂项目）与问答（无项目）两条链路都过——内核共享，入账是各模块自己的 addUsage。

默认打本机 :8080；起隔离实例时用 E2E_BASE 覆盖（如 http://localhost:8090/api，见 tests/README.md）。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "usage-ledger-ws"
ORIGIN = TMP / "usage-ledger-origin"
PROPS = TMP / "usage-ledger-runner.properties"
MARK = f"e2e-usage-{int(time.time())}"

# 与 devmind-common/src/main/resources/session/fake-agent.js 的 USAGE/resultFrame 常量对齐
FAKE_COST = 0.0123
FAKE_USAGE = {"inputTokens": 1200, "outputTokens": 340,
              "cacheReadTokens": 5600, "cacheCreationTokens": 700}


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


def wait(cond, what, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def assert_ledger(view, label):
    """finish 后恰好 1 个回合入账（fake 只在 EOF 发 result），数值与 fake 常量逐项相等。"""
    assert view.get("turnCount") == 1, f"{label} turnCount 应为 1: {view.get('turnCount')}"
    assert abs((view.get("costUsd") or 0) - FAKE_COST) < 1e-9, f"{label} costUsd 不符: {view.get('costUsd')}"
    for k, v in FAKE_USAGE.items():
        assert view.get(k) == v, f"{label} {k} 应为 {v}: {view.get(k)}"


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    # CLONE 项目要 git 远端：tmp 里起一个最小 origin（cap52 同款姿势）
    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)

    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# usage ledger e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)

    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    print(f"[1] 项目就绪 {pid}")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'usage-ledger-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "usage-ledger-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    sid = cid = None
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[1] runner 上线")

        # ---- 链路 A：项目会话 ----
        s = req("POST", "/sessions", {"taskSpec": f"{MARK} 会话", "projectId": pid}, tok)
        sid = s["id"]
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "RUNNING" or None,
             "会话 RUNNING", 60)
        req("POST", f"/sessions/{sid}/input", {"text": "你好"}, tok)
        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
             "会话 DONE", 90)
        v = req("GET", f"/sessions/{sid}", token=tok)
        assert_ledger(v, "会话")
        print(f"[2] 会话 {sid} 账本 OK（turnCount=1, costUsd={v['costUsd']}, "
              f"in={v['inputTokens']}/out={v['outputTokens']}/cacheR={v['cacheReadTokens']}/cacheW={v['cacheCreationTokens']}）")

        # ---- 链路 B：通用问答（无项目）----
        c = req("POST", "/chats", {"message": f"{MARK} 问答"}, tok)
        cid = c["id"]
        wait(lambda: req("GET", f"/chats/{cid}", token=tok).get("status") == "RUNNING" or None,
             "问答 RUNNING", 60)
        req("POST", f"/chats/{cid}/finish", token=tok)
        wait(lambda: req("GET", f"/chats/{cid}", token=tok).get("status") == "DONE" or None,
             "问答 DONE", 90)
        v = req("GET", f"/chats/{cid}", token=tok)
        assert_ledger(v, "问答")
        print(f"[3] 问答 {cid} 账本 OK（turnCount=1, costUsd={v['costUsd']}）")
    finally:
        try:
            # 先删资源再杀 runner：会话删除要节点在线以释放工作区
            if sid:
                req("DELETE", f"/sessions/{sid}", token=tok)
            if cid:
                req("DELETE", f"/chats/{cid}", token=tok)
            req("POST", f"/agent-nodes/{node['id']}/unset-default", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
            req("DELETE", f"/projects/{pid}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
        kill_tree(runner.pid)
    print("全部通过")


if __name__ == "__main__":
    main()
