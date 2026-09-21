#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-21 Agent 节点 E2E：REST 走完 注册→远程会话→事件回传→授权→输入→优雅退出→离线 409→断连对账。
前置：后端已在 :8080 运行（mvn -pl devmind-app spring-boot:run）。"""
import json, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"

def req(method, path, body=None, token=None, expect=200):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token: r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")

def wait(cond, what, timeout=30):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v: return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")

def main():
    # 1. 登录
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login["token"] if isinstance(login, dict) and "token" in login else login["accessToken"]
    print("[1] 登录 OK")

    # 0. 清理上一轮残留（同名节点 409、e2e 会话）
    for n in req("GET", "/agent-nodes", token=tok):
        if str(n.get("name", "")).startswith("e2e-node"):
            req("DELETE", f"/agent-nodes/{n['id']}", token=tok)
    for s in req("GET", "/sessions", token=tok):
        if str(s.get("taskSpec", "")).startswith("e2e"):
            try: req("DELETE", f"/sessions/{s['id']}", token=tok)
            except Exception: pass

    # 2. 注册节点
    issued = req("POST", "/agent-nodes", {"name": f"e2e-node-{int(time.time())}"}, tok)
    node = issued.get("node") or issued
    node_id, node_token = node["id"], issued["token"]
    assert str(node_token).startswith("dmag_"), f"token 前缀异常: {node_token}"
    print(f"[2] 节点注册 OK id={node_id}")

    # 3. 节点离线时创建远程会话 → 409
    e = req("POST", "/sessions", {"taskSpec": "e2e-offline", "agentNodeId": str(node_id)}, tok, expect=409)
    print(f"[3] 离线节点创建会话 -> 409 OK ({e.get('message','')[:60]})")

    # 4. 起 runner（executor=fake）
    workdir = TMP / "runner-work"
    workdir.mkdir(exist_ok=True)
    props = TMP / "agent-e2e.properties"
    props.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\n"
        f"executor=fake\nworkDir={workdir.as_posix()}\nmaxConcurrent=4\n", encoding="utf-8")
    runner = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(props)],
                              stdout=open(TMP / "e2e-runner.log", "w", encoding="utf-8"),
                              stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node_id and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[4] runner 已连接，节点 ONLINE")

        # 5. 创建远程会话 → 事件回传 → WAITING_AUTH（fake agent 会发 permission_request）
        s = req("POST", "/sessions", {"taskSpec": "e2e 远程会话", "agentNodeId": str(node_id)}, tok)
        sid = s["id"]
        assert s.get("agentNodeId") == str(node_id), f"agentNodeId 未回显: {s}"
        print(f"[5] 远程会话已创建 id={sid}")

        st = wait(lambda: (v := req("GET", f"/sessions/{sid}", token=tok))["state"] == "WAITING_AUTH" and v,
                  "会话进入 WAITING_AUTH", 60)
        print("[6] 事件回传 OK，会话 WAITING_AUTH（permission_request 已中继）")

        # 6. 授权 → runner 收到 authorize → fake 继续
        req("POST", f"/sessions/{sid}/authorize", {"accepted": True, "scope": "once", "requestId": "perm-fake"}, tok)
        def auth_replied():
            es = req("GET", f"/sessions/{sid}/events?afterSeq=-1", token=tok)
            return es if any("权限已" in (e.get("content") or "") for e in es) else None
        wait(auth_replied, "授权后 fake 回复事件", 30)
        print("[7] 远程授权 OK（permission_result 下行 + 回复上行）")

        # 7.5 CAP-50：WS 实时流验证（增量先到、全量收口、拼接相等；流式进行中重连的 snapshot 不含增量）
        wsr = subprocess.run(["node", str(ROOT / "tests/cap50-sessions-ws.mjs"), sid, "流式验证", "ws://localhost:8080"],
                             capture_output=True, text=True, encoding="utf-8", timeout=120)
        assert wsr.returncode == 0, f"WS 流式验证失败: {wsr.stdout[-800:]} {wsr.stderr[-300:]}"
        wsj = json.loads(next(l for l in wsr.stdout.splitlines() if l.startswith("WS_JSON "))[len("WS_JSON "):])
        assert len(wsj["deltas"]) >= 2, f"增量太少（没流式）: {wsj['deltas']}"
        assert all(wsj["deltas"]), "实时流里出现空增量"
        assert wsj["assistantSeq"] > max(wsj["deltaSeqs"]), "全量 assistant 的 seq 早于增量"
        assert "text_delta" not in (wsj["probeSnapshotTypes"] or []), "snapshot 里混进了增量"
        print(f"[7.5] WS 流式 OK（{len(wsj['deltas'])} 条增量 → {len(wsj['assistant'])} 字全量收口；"
              f"流式进行中重连 snapshot {len(wsj['probeSnapshotTypes'])} 条，不含增量）")

        # 7. 输入 __exit__ → fake 发 result 退出 → exit 帧 → DONE
        req("POST", f"/sessions/{sid}/input", {"text": "__exit__"}, tok)
        st = wait(lambda: (v := req("GET", f"/sessions/{sid}", token=tok))["state"] in ("DONE", "FAILED") and v,
                  "会话 DONE", 30)
        assert st["state"] == "DONE", f"期望 DONE 实际 {st['state']}"
        print("[8] 输入下行 + exit 帧上行 OK，会话 DONE")

        # 8.5 CAP-50：落库断言（REST 补拉路径）——增量打底、全量收口、拼接相等、无空增量、无噪音泄漏
        evs = req("GET", f"/sessions/{sid}/events?afterSeq=-1", token=tok)
        deltas = pairs = 0
        buf = []
        for e in evs:
            t, c = e.get("type"), e.get("content") or ""
            if t == "text_delta":
                # 改坏解析层会为每个 stream_event 吐一条空增量——这条是最强的前后判别式
                assert c != "", f"落库出现空增量 seq={e['seq']}"
                deltas += 1
                buf.append(c)
            elif t == "assistant":
                assert buf, f"assistant 之前没有增量打底 seq={e['seq']}"
                assert "".join(buf) == c, f"增量拼接 != 全量正文 seq={e['seq']}: {''.join(buf)!r} / {c!r}"
                buf, pairs = [], pairs + 1
        assert deltas >= 4 and pairs >= 2, f"增量/收口样本太少: deltas={deltas} pairs={pairs}"
        blob = json.dumps(evs, ensure_ascii=False)
        for leak in ("（fake 思考", "（子 agent 正文", "stream_event", "partial_json"):
            assert leak not in blob, f"该被整片吞掉的 partial 噪音漏进事件流: {leak}"
        print(f"[8.5] 落库流式 OK（{deltas} 条增量 / {pairs} 次全量收口，无空增量、无噪音泄漏）")

        # 8. 再开一个会话，然后杀掉 runner → 断连对账（节点 OFFLINE + 会话有断连事件，不 FAILED）
        s2 = req("POST", "/sessions", {"taskSpec": "e2e 断连对账", "agentNodeId": str(node_id)}, tok)
        sid2 = s2["id"]
        wait(lambda: (v := req("GET", f"/sessions/{sid2}", token=tok))["state"] in
             ("RUNNING", "WAITING_INPUT", "WAITING_AUTH") and v, "第二个会话启动", 30)
        runner.terminate()
        runner.wait(timeout=15)
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node_id and n["status"] == "OFFLINE"), None),
             "节点 OFFLINE", 60)
        v2 = req("GET", f"/sessions/{sid2}", token=tok)
        assert v2["state"] != "FAILED", f"断连不应直接 FAILED: {v2['state']}"
        def has_disconnect_event():
            return any("断开" in (e.get("content") or "")
                       for e in req("GET", f"/sessions/{sid2}/events?afterSeq=-1", token=tok))
        wait(has_disconnect_event, "断连提示事件", 30)
        print("[9] runner 断连对账 OK（节点 OFFLINE，会话保留并记断连事件）")

        # 9. 清理
        req("POST", f"/sessions/{sid2}/kill", {}, tok)
        req("DELETE", f"/sessions/{sid2}", token=tok)
        req("DELETE", f"/sessions/{sid}", token=tok)
        req("DELETE", f"/agent-nodes/{node_id}", token=tok)
        print("[10] 清理 OK")
        print("\n== E2E 全部通过 ==")
    finally:
        if runner.poll() is None:
            runner.kill()

if __name__ == "__main__":
    main()
