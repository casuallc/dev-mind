#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-67 用量统计 E2E（前置：mvn -q install -DskipTests 出 runner jar；后端已用新代码启动）。

链路：fake runner 固定用量常量（见 session_usage_e2e.py）→ 起 1 会话（挂项目+需求+model）
+ 1 问答（model 空）→ /finish 入账 → 对账 /api/usage 四端点：
- summary：from=测试起点过滤后恰好 sessionCount=1/chatCount=1，成本/token 逐项等于 2×fake 常量；
- breakdown：requirement（标签富化 + 未归属问答桶）/project/model（空模型归「默认」）/user；
- 权限：非 admin dim=user 403；非 admin summary 强制本人（显式传 userId=admin 也被忽略 → 全零）；
- daily：今日点 cost/tokens/turns 精确对账（隔离实例空库，days=1）；
- top：两行成本降序，会话行带出需求标题。

默认打本机 :8080；隔离实例用 E2E_BASE 覆盖（起法见 tests/README.md，空库断言依赖隔离库）。
"""
import datetime
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap67-usage-ws"
ORIGIN = TMP / "cap67-usage-origin"
PROPS = TMP / "cap67-usage-runner.properties"
MARK = f"e2e-cap67-{int(time.time())}"

# 与 devmind-common/src/main/resources/session/fake-agent.js 的 USAGE/resultFrame 常量对齐
FAKE_COST = 0.0123
FAKE_IN, FAKE_OUT, FAKE_CR, FAKE_CW = 1200, 340, 5600, 700


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


def close(a, b, label):
    assert abs(a - b) < 1e-9, f"{label}: {a} != {b}"


def find(rows, pred, label):
    row = next((r for r in rows if pred(r)), None)
    assert row is not None, f"breakdown 缺行: {label} -> {rows}"
    return row


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)

    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap67 usage stats e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)

    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    reqmt = req("POST", f"/projects/{pid}/requirements", {"title": f"{MARK} 需求"}, tok)
    rid = reqmt["id"]
    print(f"[1] 项目 {pid} + 需求 {rid} 就绪")

    # 非 admin 用户：验证 owner 强制与 dim=user 403
    req("POST", "/auth/users", {
        "username": MARK, "displayName": "cap67 路人", "password": "cap67pass", "role": "DEVELOPER"}, tok)
    u2 = req("POST", "/auth/login", {"username": MARK, "password": "cap67pass"})
    tok2 = u2.get("token") or u2.get("accessToken")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap67-usage-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap67-usage-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    sid = cid = None
    uid = None
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[2] runner 上线")

        # from 过滤起点：只统计本测试产生的行（dev 库有历史数据时也能精确对账）
        from_iso = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")

        s = req("POST", "/sessions", {
            "taskSpec": f"{MARK} 会话", "projectId": pid, "requirementId": rid, "model": "sonnet-e2e"}, tok)
        sid = s["id"]
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "RUNNING" or None,
             "会话 RUNNING", 60)
        req("POST", f"/sessions/{sid}/input", {"text": "你好"}, tok)
        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
             "会话 DONE", 90)

        c = req("POST", "/chats", {"message": f"{MARK} 问答"}, tok)
        cid = c["id"]
        wait(lambda: req("GET", f"/chats/{cid}", token=tok).get("status") == "RUNNING" or None,
             "问答 RUNNING", 60)
        req("POST", f"/chats/{cid}/finish", token=tok)
        wait(lambda: req("GET", f"/chats/{cid}", token=tok).get("status") == "DONE" or None,
             "问答 DONE", 90)
        print(f"[3] 会话 {sid} + 问答 {cid} 入账完成")

        q = f"from={urllib.parse.quote(from_iso)}"

        # ---- summary：两源合并，逐项对账 ----
        s = req("GET", f"/usage/summary?{q}", token=tok)
        assert s["sessionCount"] == 1 and s["chatCount"] == 1, f"summary 计数: {s}"
        assert s["turnCount"] == 2, f"summary turnCount: {s}"
        close(s["costUsd"], FAKE_COST * 2, "summary costUsd")
        for k, v in (("inputTokens", FAKE_IN), ("outputTokens", FAKE_OUT),
                     ("cacheReadTokens", FAKE_CR), ("cacheCreationTokens", FAKE_CW)):
            assert s[k] == v * 2, f"summary {k}: {s[k]} != {v * 2}"
        print("[4] summary 对账 OK")

        # ---- breakdown：四维 ----
        rows = req("GET", f"/usage/breakdown?dim=requirement&{q}", token=tok)
        r1 = find(rows, lambda r: r["key"] == rid, "需求行")
        assert r1["label"] == f"{MARK} 需求" and r1["projectId"] == pid, f"需求行富化: {r1}"
        assert r1["sessionCount"] == 1 and r1["chatCount"] == 0, f"需求行计数: {r1}"
        bucket = find(rows, lambda r: r["key"] is None and "问答" in (r["label"] or ""), "未归属问答桶")
        assert bucket["chatCount"] == 1, f"问答桶: {bucket}"

        rows = req("GET", f"/usage/breakdown?dim=project&{q}", token=tok)
        p1 = find(rows, lambda r: r["key"] == pid, "项目行")
        assert p1["label"] == MARK and p1["sessionCount"] == 1, f"项目行: {p1}"

        rows = req("GET", f"/usage/breakdown?dim=model&{q}", token=tok)
        m1 = find(rows, lambda r: r["key"] == "sonnet-e2e", "模型行")
        assert m1["sessionCount"] == 1 and m1["chatCount"] == 0, f"模型行: {m1}"
        m0 = find(rows, lambda r: r["key"] is None, "默认模型行")
        assert m0["label"] == "默认" and m0["chatCount"] == 1, f"默认模型行: {m0}"

        rows = req("GET", f"/usage/breakdown?dim=user&{q}", token=tok)
        u1 = find(rows, lambda r: r["key"] == "admin", "用户行")
        assert u1["sessionCount"] == 1 and u1["chatCount"] == 1, f"用户行: {u1}"
        close(u1["costUsd"], FAKE_COST * 2, "用户行 costUsd")
        print("[5] breakdown 四维对账 OK")

        # ---- 权限：非 admin dim=user 403；非 admin summary 强制本人 ----
        req("GET", f"/usage/breakdown?dim=user&{q}", token=tok2, expect=403)
        s2 = req("GET", f"/usage/summary?{q}&userId=admin", token=tok2)
        assert s2["sessionCount"] == 0 and s2["chatCount"] == 0 and s2["turnCount"] == 0, \
            f"非 admin 应强制本人（传 userId=admin 被忽略）: {s2}"
        print("[6] 权限口径 OK")

        # ---- daily：今日点精确对账（隔离空库；days=1 只有今日一行） ----
        daily = req("GET", "/usage/daily?days=1", token=tok)
        assert len(daily) == 1, f"daily 行数: {daily}"
        today = daily[0]
        assert today["date"] == datetime.date.today().isoformat(), f"daily 日期: {today}"
        close(today["costUsd"], FAKE_COST * 2, "daily costUsd")
        assert today["tokens"] == (FAKE_IN + FAKE_OUT) * 2, f"daily tokens: {today}"
        assert today["turns"] == 2, f"daily turns: {today}"
        print("[7] daily 对账 OK")

        # ---- top：两源成本降序 + 需求标题带出 ----
        top = req("GET", f"/usage/top?limit=10&{q}", token=tok)
        assert len(top) == 2, f"top 行数: {top}"
        close(top[0]["costUsd"], FAKE_COST, "top[0] cost")
        close(top[1]["costUsd"], FAKE_COST, "top[1] cost")
        t_sess = find(top, lambda r: r["source"] == "SESSION", "top 会话行")
        assert t_sess["id"] == sid and t_sess["requirementTitle"] == f"{MARK} 需求", f"top 会话行: {t_sess}"
        t_chat = find(top, lambda r: r["source"] == "CHAT", "top 问答行")
        assert t_chat["id"] == cid, f"top 问答行: {t_chat}"
        print("[8] top 对账 OK")
    finally:
        try:
            # 先删资源再杀 runner：会话删除要节点在线以释放工作区
            if sid:
                req("DELETE", f"/sessions/{sid}", token=tok)
            if cid:
                req("DELETE", f"/chats/{cid}", token=tok)
            # 无删除用户端点：置 DISABLED 即可（用户名带时间戳不冲突）
            uid = next((u["id"] for u in req("GET", "/auth/users", token=tok)
                        if u["username"] == MARK), None)
            if uid:
                try:
                    req("PUT", f"/auth/users/{uid}", {"status": "DISABLED"}, tok)
                except Exception as ex:
                    print(f"[cleanup] 停用用户失败: {ex}")
            req("POST", f"/agent-nodes/{node['id']}/unset-default", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
            req("DELETE", f"/projects/{pid}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
        kill_tree(runner.pid)
    print("全部通过")


if __name__ == "__main__":
    main()
