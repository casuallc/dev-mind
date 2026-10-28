#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""usage_records 逐回合账本 + 模型筛选/ tokens 排序 E2E
（前置：mvn -q install -DskipTests 出 runner jar；后端已用新代码启动）。

链路同 cap67_usage_stats_e2e.py（fake runner 固定用量常量，1 会话挂项目/需求/model + 1 问答），
断言新增能力：
- /usage/records：2 行时间倒序、归属快照（owner/model）、标题带出、tokensPerSec=170.0
  （=340*1000/2000 fake 常量）、source/model 过滤、未知 source 400、分页 size=1 翻页；
- owner 强制：非 admin 传 userId=admin 也被忽略 → 0 行；admin 传 userId=路人 → 0 行；
- /usage/hourly：24 桶、当前小时 turns/tokens 覆盖两回合（账本只有上线后数据，用 >= 兼容 dev 库）；
- 四老端点 model 过滤：summary/breakdown/daily/top 各自收窄，未知模型全零不报错；
- top 排序键 = tokens（两行同 tokens 时 createdAt 倒序，问答后起排前）。

默认打本机 :8080；隔离实例用 E2E_BASE 覆盖（起法见 tests/README.md）。
无双计对账由 cap67_usage_stats_e2e.py 复跑守住（累计列口径不变）。
"""
import datetime
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parents[3]
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "usage-records-ws"
ORIGIN = TMP / "usage-records-origin"
PROPS = TMP / "usage-records-runner.properties"
MARK = f"e2e-usagerec-{int(time.time())}"

# 与 devmind-common/src/main/resources/session/fake-agent.js 的 USAGE/resultFrame 常量对齐
FAKE_COST = 0.0123
FAKE_IN, FAKE_OUT, FAKE_CR, FAKE_CW = 1200, 340, 5600, 700
FAKE_DURATION_MS = 2000
FAKE_TOK_PER_SEC = FAKE_OUT * 1000.0 / FAKE_DURATION_MS  # 170.0


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
    (ORIGIN / "README.md").write_text("# usage records e2e\n", encoding="utf-8")
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

    req("POST", "/auth/users", {
        "username": MARK, "displayName": "账本路人", "password": "recpass123", "role": "DEVELOPER"}, tok)
    u2 = req("POST", "/auth/login", {"username": MARK, "password": "recpass123"})
    tok2 = u2.get("token") or u2.get("accessToken")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'usage-records-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "usage-records-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    sid = cid = None
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

        # ---- records：两行倒序 + 归属快照 + 派生速度 ----
        pv = req("GET", f"/usage/records?{q}", token=tok)
        assert pv["total"] == 2 and len(pv["items"]) == 2, f"records 总数: {pv}"
        r_chat, r_sess = pv["items"][0], pv["items"][1]
        assert r_chat["id"] > r_sess["id"], f"时间倒序（id 递减）: {[r['id'] for r in pv['items']]}"
        assert r_sess["source"] == "SESSION" and r_sess["sessionId"] == sid, f"会话行: {r_sess}"
        assert r_sess["owner"] == "admin" and r_sess["model"] == "sonnet-e2e", f"会话归属快照: {r_sess}"
        assert MARK in (r_sess["title"] or "") and "\\n" not in (r_sess["title"] or ""), \
            f"会话标题带出: {r_sess['title']}"
        assert r_sess["requirementId"] == rid and r_sess["projectId"] == pid, f"会话归属: {r_sess}"
        assert r_sess["durationMs"] == FAKE_DURATION_MS, f"耗时: {r_sess}"
        for k, v in (("inputTokens", FAKE_IN), ("outputTokens", FAKE_OUT),
                     ("cacheReadTokens", FAKE_CR), ("cacheCreationTokens", FAKE_CW)):
            assert r_sess[k] == v, f"会话行 {k}: {r_sess[k]} != {v}"
        close(r_sess["costUsd"], FAKE_COST, "会话行 costUsd（照常住账，前端不展示）")
        close(r_sess["tokensPerSec"], FAKE_TOK_PER_SEC, "会话行 tokensPerSec")
        assert r_chat["source"] == "CHAT" and r_chat["sessionId"] == cid, f"问答行: {r_chat}"
        assert r_chat["owner"] == "admin" and r_chat["model"] is None, f"问答归属快照: {r_chat}"
        assert MARK in (r_chat["title"] or ""), f"问答标题带出: {r_chat['title']}"
        print("[4] records 明细对账 OK")

        # ---- records：source/model 过滤 + 未知 source 400 + 分页 ----
        pv_s = req("GET", f"/usage/records?{q}&source=SESSION", token=tok)
        assert pv_s["total"] == 1 and pv_s["items"][0]["source"] == "SESSION", f"source 过滤: {pv_s}"
        req("GET", f"/usage/records?{q}&source=OTHER", token=tok, expect=400)
        pv_m = req("GET", f"/usage/records?{q}&model=sonnet-e2e", token=tok)
        assert pv_m["total"] == 1 and pv_m["items"][0]["sessionId"] == sid, f"model 过滤: {pv_m}"
        pv_p1 = req("GET", f"/usage/records?{q}&size=1&page=0", token=tok)
        pv_p2 = req("GET", f"/usage/records?{q}&size=1&page=1", token=tok)
        assert pv_p1["total"] == 2 and len(pv_p1["items"]) == 1 and len(pv_p2["items"]) == 1, \
            f"分页: {pv_p1['total']}"
        assert pv_p1["items"][0]["id"] > pv_p2["items"][0]["id"], "翻页顺序稳定"
        print("[5] records 过滤/分页 OK")

        # ---- owner 强制：非 admin 传 userId=admin 被忽略；admin 按路人过滤为空 ----
        pv2 = req("GET", f"/usage/records?{q}&userId=admin", token=tok2)
        assert pv2["total"] == 0, f"非 admin 应强制本人: {pv2}"
        pv3 = req("GET", f"/usage/records?{q}&userId={MARK}", token=tok)
        assert pv3["total"] == 0, f"admin 按路人过滤应为空: {pv3}"
        print("[6] records owner 强制 OK")

        # ---- hourly：24 桶，当前小时覆盖两回合（账本只有新数据，>= 兼容 dev 库今日存量） ----
        hourly = req("GET", "/usage/hourly", token=tok)
        assert len(hourly) == 24, f"hourly 桶数: {len(hourly)}"
        assert hourly[0]["hour"] == "00" and hourly[23]["hour"] == "23", "桶序"
        cur = hourly[datetime.datetime.now().hour]
        assert cur["turns"] >= 2, f"当前小时 turns: {cur}"
        assert cur["tokens"] >= (FAKE_IN + FAKE_OUT) * 2, f"当前小时 tokens: {cur}"
        print(f"[7] hourly OK（当前桶 turns={cur['turns']} tokens={cur['tokens']}）")

        # ---- 四老端点 model 过滤 ----
        s_m = req("GET", f"/usage/summary?{q}&model=sonnet-e2e", token=tok)
        assert s_m["sessionCount"] == 1 and s_m["chatCount"] == 0, f"summary model 过滤: {s_m}"
        s_x = req("GET", f"/usage/summary?{q}&model=gpt-x", token=tok)
        assert s_x["turnCount"] == 0, f"未知模型应全零: {s_x}"
        bd = req("GET", f"/usage/breakdown?dim=model&{q}&model=sonnet-e2e", token=tok)
        assert len(bd) == 1 and bd[0]["key"] == "sonnet-e2e", f"breakdown model 过滤: {bd}"
        dl = req("GET", "/usage/daily?days=1&model=sonnet-e2e", token=tok)
        assert dl[0]["tokens"] == FAKE_IN + FAKE_OUT, f"daily model 过滤: {dl}"
        tp = req("GET", f"/usage/top?limit=10&{q}&model=sonnet-e2e", token=tok)
        assert len(tp) == 1 and tp[0]["source"] == "SESSION", f"top model 过滤: {tp}"
        print("[8] 四老端点 model 过滤 OK")

        # ---- top tokens 排序：两行同 tokens → createdAt 倒序（问答后起排前） ----
        top = req("GET", f"/usage/top?limit=10&{q}", token=tok)
        assert len(top) == 2, f"top 行数: {top}"
        assert top[0]["source"] == "CHAT" and top[1]["source"] == "SESSION", \
            f"同 tokens 按创建时间倒序: {[r['source'] for r in top]}"
        print("[9] top tokens 排序 OK")
    finally:
        try:
            # 先删资源再杀 runner：会话删除要节点在线以释放工作区
            if sid:
                req("DELETE", f"/sessions/{sid}", token=tok)
            if cid:
                req("DELETE", f"/chats/{cid}", token=tok)
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
