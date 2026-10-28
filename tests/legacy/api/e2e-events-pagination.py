#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""会话/问答事件 REST 分页（limit + beforeSeq）E2E 验证。

背景：单会话内容过多时首屏一次拉全量事件很慢。后端 events 端点改为「取最近一页 +
beforeSeq 向前翻页」（sessions 原本默认 2000 条封顶、chats 原本无上限全量取）；
前端首屏只取最近 200 条，「加载更早消息」按 beforeSeq 翻页。本脚本钉死后端分页语义。

前置：app 已起（E2E_BASE 可指隔离实例，默认 :8080；隔离实例起法见 tests/README.md），
runner jar 已构建（mvn -q install -DskipTests）。脚本自建 fake runner 节点，跑完自清理。

覆盖：
 1. sessions：四轮对话攒事件 → 默认拉取=全量（<2000 时）→ limit=4 首页=全量末尾 4 条
    → beforeSeq 链式翻页拼接 == 全量（seq 逐一相等、无重叠无缺口）→ 越界 beforeSeq 空页
 2. chats：同款断言 + 旧调用形态（只传 afterSeq、不带 limit/beforeSeq）兼容
 3. payloadMax=8 预览截断（tool_use.toolInput / tool_result.content 截断 + previewTruncated
    标记）→ 单条端点 /{kind}/{id}/events/{seq} 取全文 → 不带 payloadMax 原样返回
 4. 会话列表：keyword 服务端搜索（命中/不命中）+ ETag 条件拉取（二刷 304 空体）
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parents[3]
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "events-page-ws"
ORIGIN = TMP / "events-page-origin"
PROPS = TMP / "events-page-runner.properties"
MARK = f"e2e-evpage-{int(time.time()) % 1000000}"
PAGE = 4


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
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")


def req_raw(method, path, token, headers=None):
    """带响应头/状态码的原始 GET（ETag/304 断言要拿 ETag 头与空体）。"""
    r = urllib.request.Request(BASE + path, method=method)
    r.add_header("Authorization", "Bearer " + token)
    for k, v in (headers or {}).items():
        r.add_header(k, v)
    try:
        with urllib.request.urlopen(r) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


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


def events(tok, kind, cid, **params):
    qs = "&".join(f"{k}={v}" for k, v in params.items())
    return req("GET", f"/{kind}/{cid}/events?{qs}", token=tok)


def settled_events(tok, kind, cid):
    """等事件落库稳定（saver 异步 flush）：连续两次计数不变才返回。"""
    prev = -1
    for _ in range(30):
        evs = events(tok, kind, cid, afterSeq=-1)
        if len(evs) == prev and prev > 0:
            return evs
        prev = len(evs)
        time.sleep(1)
    raise AssertionError(f"{kind}/{cid} 事件数不收敛")


def assert_pagination(tok, kind, cid):
    """limit 分页 + beforeSeq 链式翻页 == 默认全量。"""
    full = settled_events(tok, kind, cid)
    n = len(full)
    assert n >= PAGE * 2, f"{kind}/{cid} 事件太少不足以验证翻页: {n}"
    seqs = [e["seq"] for e in full]
    assert seqs == sorted(seqs), "全量返回应升序"

    first_page = events(tok, kind, cid, afterSeq=-1, limit=PAGE)
    assert [e["seq"] for e in first_page] == seqs[-PAGE:], \
        f"{kind} limit={PAGE} 应取最近一页: {[e['seq'] for e in first_page]} vs {seqs[-PAGE:]}"

    collected = list(first_page)
    page = first_page
    hops = 0
    while len(page) == PAGE:
        page = events(tok, kind, cid, afterSeq=-1, limit=PAGE, beforeSeq=page[0]["seq"])
        collected = page + collected
        hops += 1
        assert hops <= n // PAGE + 2, "翻页不收敛（疑似死循环）"
    assert [e["seq"] for e in collected] == seqs, \
        f"{kind} 翻页拼接应与全量一致（{len(collected)} vs {n}）"

    empty = events(tok, kind, cid, afterSeq=-1, limit=PAGE, beforeSeq=seqs[0])
    assert empty == [], f"{kind} beforeSeq=最小 seq 应为空页: {empty}"
    print(f"  [{kind}] {cid} 全量 {n} 条，{hops} 次翻页拼接一致，越界空页 OK")
    return n


def assert_payload_preview(tok, kind, cid):
    """payloadMax 预览截断 + 单条全文端点。fake runner 的 tool_result 正文 'step 1 output'
    （13 字符）、toolInput 是 JSON 串，payloadMax=8 两者都必截断。"""
    trunc = events(tok, kind, cid, afterSeq=-1, payloadMax=8)
    tr = next((e for e in trunc if e["type"] == "tool_result"), None)
    tu = next((e for e in trunc if e["type"] == "tool_use"), None)
    assert tr and tu, f"{kind}/{cid} 缺 tool 事件，无法验证截断"
    assert tr["content"] == "step 1 o…", f"tool_result 应截为 8 字+省略号: {tr['content']!r}"
    assert tr["payload"].get("previewTruncated") is True, "截断必须打 previewTruncated 标记"
    assert tu["payload"]["toolInput"].endswith("…") and len(tu["payload"]["toolInput"]) == 9, \
        f"toolInput 应截为 8 字+省略号: {tu['payload']['toolInput']!r}"
    assert tu["payload"].get("previewTruncated") is True

    full_tr = req("GET", f"/{kind}/{cid}/events/{tr['seq']}", token=tok)
    assert full_tr["content"] == "step 1 output", f"单条端点必须给全文: {full_tr['content']!r}"
    full_tu = req("GET", f"/{kind}/{cid}/events/{tu['seq']}", token=tok)
    assert "step 1" in full_tu["payload"]["toolInput"] and not full_tu["payload"].get("previewTruncated"), \
        "单条端点不受 payloadMax 影响"

    plain = events(tok, kind, cid, afterSeq=-1)
    assert next(e for e in plain if e["type"] == "tool_result")["content"] == "step 1 output", \
        "不带 payloadMax 应原样返回"
    print(f"  [{kind}] {cid} payloadMax 截断/标记 + 单条全文 + 兼容形态 OK")


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
    (ORIGIN / "README.md").write_text("# events pagination e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True,
                   capture_output=True, env=env)

    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    print(f"[1] 项目就绪 {pid}")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node_id, node_token = issued["node"]["id"], issued["token"]
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http', 'ws', 1).removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'events-page-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=4\ngcDays=7\ngitProxyEnabled=false\n", encoding="utf-8")
    logf = open(TMP / "events-page-runner.log", "w", encoding="utf-8")
    runner = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(PROPS)],
                              stdout=logf, stderr=subprocess.STDOUT)

    sid = cid = None
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node_id and n["status"] == "ONLINE"), None), "节点上线")
        print(f"[2] runner 节点上线 id={node_id}")

        # ---------- sessions ----------
        sess = req("POST", "/sessions", {
            "projectId": pid, "taskSpec": f"{MARK} 翻页验证会话",
            "agentNodeId": node_id, "permissionMode": "bypassPermissions",
        }, tok)
        sid = sess["id"]
        for i in range(4):
            wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "WAITING_INPUT"
                         or None, f"会话第 {i + 1} 轮落定", 60)
            if i < 3:
                req("POST", f"/sessions/{sid}/input", {"text": f"第 {i + 2} 轮"}, tok)
        print(f"[3] 会话 {sid} 四轮落定")
        assert_pagination(tok, "sessions", sid)
        assert_payload_preview(tok, "sessions", sid)

        # 会话列表：keyword 服务端搜索（列表已瘦身截断，客户端过滤会漏全文命中，故下沉）
        hits = req("GET", f"/sessions?projectId={pid}&keyword={MARK}", token=tok)
        assert any(s["id"] == sid for s in hits), f"keyword={MARK} 应命中会话: {hits}"
        miss = req("GET", f"/sessions?projectId={pid}&keyword=zzz-no-such-kw", token=tok)
        assert miss == [], f"不命中应空列表: {miss}"
        print("[3b] 会话列表 keyword 搜索 OK")

        # ETag 条件拉取：首拉拿 ETag，带 If-None-Match 二刷数据没变 → 304 空体
        st, hdrs, body = req_raw("GET", f"/sessions?projectId={pid}", tok)
        etag = hdrs.get("ETag") or hdrs.get("etag")
        assert st == 200 and etag, f"列表应带 ETag: status={st} headers={list(hdrs)}"
        st2, _, body2 = req_raw("GET", f"/sessions?projectId={pid}", tok, {"If-None-Match": etag})
        assert st2 == 304 and body2 == b"", f"未变化应 304 空体: status={st2} len={len(body2)}"
        print(f"[3c] 会话列表 ETag/304 OK（etag={etag}）")

        # ---------- chats ----------
        chat = req("POST", "/chats", {
            "message": f"{MARK} 第一轮", "agentNodeId": node_id,
            "permissionMode": "bypassPermissions",
        }, tok)
        cid = chat["id"]
        for i in range(3):
            wait(lambda: req("GET", f"/chats/{cid}", token=tok).get("status") == "WAITING_INPUT"
                         or None, f"问答第 {i + 1} 轮落定", 60)
            if i < 2:
                req("POST", f"/chats/{cid}/input", {"text": f"第 {i + 2} 轮"}, tok)
        print(f"[4] 问答 {cid} 三轮落定")
        assert_pagination(tok, "chats", cid)
        assert_payload_preview(tok, "chats", cid)

        # 旧调用形态兼容：只传 afterSeq（不带 limit/beforeSeq）→ <2000 时返回全量
        legacy = events(tok, "chats", cid, afterSeq=-1)
        assert len(legacy) >= PAGE * 2, f"chats 旧形态应返回全量: {len(legacy)}"
        print("[5] 旧调用形态（仅 afterSeq）兼容 OK")

        print("PASS: 事件分页（limit + beforeSeq）sessions/chats 全链路一致")
    finally:
        # 先结束再删、杀 runner 在最后：活动会话被节点引用删不掉（409），
        # 而 DELETE 又要联系节点收口，runner 先死同样删不掉
        try:
            if sid:
                req("POST", f"/sessions/{sid}/kill", None, tok, expect=200)
            if cid:
                req("POST", f"/chats/{cid}/kill", None, tok, expect=200)
            if sid:
                wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status")
                     in ("KILLED", "DONE", "FAILED", "TERMINATED") or None, "会话终态", 30)
            if cid:
                wait(lambda: req("GET", f"/chats/{cid}", token=tok).get("status")
                     in ("KILLED", "DONE", "FAILED", "TERMINATED") or None, "问答终态", 30)
            if sid:
                req("DELETE", f"/sessions/{sid}", token=tok)
            if cid:
                req("DELETE", f"/chats/{cid}", token=tok)
            req("DELETE", f"/agent-nodes/{node_id}", token=tok)
            req("DELETE", f"/projects/{pid}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
        kill_tree(runner.pid)
        logf.close()
        shutil.rmtree(WS, ignore_errors=True)
        shutil.rmtree(ORIGIN, onexc=_force_remove)
        print("[cleanup] runner 已杀、工作区已清")


if __name__ == "__main__":
    main()
