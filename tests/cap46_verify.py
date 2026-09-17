# -*- coding: utf-8 -*-
"""CAP-46 知识库 AI 会话 E2E（fake runner + mock embedding，无需外部服务）。

运行前提：
1. 依赖已构建：`mvn -q install -DskipTests`（需要 devmind-agent-runner.jar）。
2. app 独立实例已起（18090 + 独立 H2 + mock embedding），例如：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18090 --spring.datasource.url=jdbc:h2:file:./tmp/cap46-db/devmind --devmind.knowledge.embedding.provider=mock"
3. 本机有 node（fake 执行器内置 fake-agent.js）。runner 由本脚本自起（executor=fake，连 18090）。

覆盖：建 RAG 库+条目等索引 ready → 绑库起问答（ChatView 回传 knowledgeBaseId、库不存在 400）
→ 事件流断言库概览节（<knowledge-base>，fake 回显初始 prompt）→ 两轮输入均注入
<knowledge-context>（来源：条目名）→ 不绑库问答断言事件流无注入。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18090/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap46-ws"
PROPS = TMP / "cap46-runner.properties"
passed = 0
failed = 0
TOKEN = None


def call(method, path, body=None):
    url = BASE + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def wait_event(chat_id, needle, timeout=45, after_seq=0):
    """轮询事件流，返回第一条 content 含 needle 的事件（含已落库历史）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, evs = call("GET", f"/chats/{chat_id}/events?afterSeq={after_seq}")
        if st == 200:
            for e in (evs or []):
                if needle in (e.get("content") or ""):
                    return e
        time.sleep(1)
    return None


def all_events(chat_id):
    st, evs = call("GET", f"/chats/{chat_id}/events?afterSeq=0")
    return evs if st == 200 and isinstance(evs, list) else []


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


if not RUNNER_JAR.is_file():
    print(f"缺少 {RUNNER_JAR}，先 mvn -q install -DskipTests")
    sys.exit(1)

# ---------- 0. 登录 ----------
st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
if not TOKEN:
    sys.exit(1)

# ---------- 1. runner 节点（executor=fake） ----------
st, issued = call("POST", "/agent-nodes", {"name": f"e2e-cap46-{int(time.time())}"})
node = (issued or {}).get("node") or {}
node_id, node_token = node.get("id"), (issued or {}).get("token")
check("注册 runner 节点", st == 200 and node_id and node_token, f"{st} {issued}")
if not node_id:
    sys.exit(1)

shutil.rmtree(WS, ignore_errors=True)
PROPS.write_text(
    f"serverUrl=ws://localhost:18090/ws/agent\ntoken={node_token}\nexecutor=fake\n"
    f"workDir={(TMP / 'cap46-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
    f"maxConcurrent=4\ngcDays=7\n", encoding="utf-8")
runner_log = open(TMP / "cap46-runner.log", "w", encoding="utf-8")
runner = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(PROPS)],
                          stdout=runner_log, stderr=subprocess.STDOUT)

created_chats = []
kb_id = None
try:
    deadline = time.time() + 60
    online = False
    while time.time() < deadline:
        st, nodes = call("GET", "/agent-nodes")
        if st == 200 and any(n.get("id") == node_id and n.get("status") == "ONLINE"
                             for n in (nodes or [])):
            online = True
            break
        time.sleep(1)
    check("runner 节点上线", online, "60s 内未 ONLINE（看 tmp/cap46-runner.log）")
    if not online:
        sys.exit(1)

    # ---------- 2. 建 RAG 库 + 条目，等索引 ready ----------
    st, kb = call("POST", "/knowledge/bases", {
        "name": "E2E CAP46 研发规范库", "description": "E2E 规范集合",
        "scope": "global", "injectMode": "RAG"})
    kb_id = (kb or {}).get("id")
    check("创建 RAG 知识库", st == 200 and bool(kb_id), f"{st} {kb}")

    st, entry = call("POST", "/knowledge/entries", {
        "kbId": kb_id, "name": "E2E 发布检查单",
        "contentMd": "发布前必须执行回归套件 regression-suite-46，并核对发布检查单全部条目。",
        "status": "active"})
    entry_id = (entry or {}).get("id")
    check("创建知识条目", st == 200 and bool(entry_id), f"{st} {entry}")

    deadline = time.time() + 60
    ready = None
    while time.time() < deadline:
        st, e = call("GET", f"/knowledge/entries/{entry_id}")
        if st == 200 and e.get("indexStatus") in ("ready", "failed"):
            ready = e
            break
        time.sleep(1)
    check("条目索引 ready", ready is not None and ready.get("indexStatus") == "ready", f"{ready}")

    # ---------- 3. 绑库问答：创建 + 负例 ----------
    st, bad = call("POST", "/chats", {
        "message": "x", "knowledgeBaseId": 999999, "agentNodeId": str(node_id)})
    check("绑定不存在知识库 400", st == 400, f"{st} {bad}")

    st, chat = call("POST", "/chats", {
        "message": "发布前要做哪些检查？", "knowledgeBaseId": kb_id,
        "agentNodeId": str(node_id)})
    cid = (chat or {}).get("id")
    check("绑库问答创建成功", st == 200 and bool(cid), f"{st} {chat}")
    check("ChatView 回传 knowledgeBaseId", (chat or {}).get("knowledgeBaseId") == kb_id,
          f"{chat}")
    created_chats.append(cid)

    # ---------- 4. 启动注入：事件流出现库概览节（fake 回显初始 prompt） ----------
    ev = wait_event(cid, "<knowledge-base>") if cid else None
    check("事件流含库概览节 <knowledge-base>", ev is not None,
          f"{[(e.get('type'), (e.get('content') or '')[:60]) for e in all_events(cid)]}")
    if ev:
        content = ev.get("content") or ""
        check("概览含库名/描述/条目清单",
              "E2E CAP46 研发规范库" in content and "E2E 规范集合" in content
              and "- E2E 发布检查单" in content, content[:300])

    # ---------- 5. 每轮检索注入：两轮输入均出现 <knowledge-context> ----------
    st, _ = call("POST", f"/chats/{cid}/input", {"text": "发布检查有哪些步骤"})
    ev1 = wait_event(cid, "<knowledge-context>")
    check("第一轮输入注入 <knowledge-context>", ev1 is not None,
          f"{[(e.get('type'), (e.get('content') or '')[:60]) for e in all_events(cid)]}")
    check("注入块带来源标注",
          any("（来源：E2E 发布检查单）" in (e.get("content") or "") for e in all_events(cid)),
          "未找到（来源：E2E 发布检查单）")

    seq_after = max((e.get("seq") or 0) for e in all_events(cid))
    st, _ = call("POST", f"/chats/{cid}/input", {"text": "回归套件怎么跑"})
    ev2 = wait_event(cid, "<knowledge-context>", after_seq=seq_after)
    check("第二轮输入再次注入 <knowledge-context>", ev2 is not None,
          f"afterSeq={seq_after} 后无注入事件")

    # ---------- 6. 不绑库问答：事件流无任何注入 ----------
    st, plain = call("POST", "/chats", {
        "message": "普通问答 CAP46 首轮", "agentNodeId": str(node_id)})
    pid_ = (plain or {}).get("id")
    check("普通问答创建成功", st == 200 and bool(pid_), f"{st} {plain}")
    check("普通问答 knowledgeBaseId 为空",
          (plain or {}).get("knowledgeBaseId") is None, f"{plain}")
    created_chats.append(pid_)
    if pid_:
        call("POST", f"/chats/{pid_}/input", {"text": "发布检查有哪些步骤"})
        ev_plain = wait_event(pid_, "发布检查有哪些步骤")
        check("普通问答输入有回显", ev_plain is not None)
        injected = [e for e in all_events(pid_)
                    if "<knowledge-context>" in (e.get("content") or "")
                    or "<knowledge-base>" in (e.get("content") or "")]
        check("普通问答事件流无任何知识注入", not injected,
              f"{[(e.get('type'), (e.get('content') or '')[:80]) for e in injected]}")

    # ---------- 收尾 ----------
    print(f"\n== CAP-46 E2E: {passed} passed, {failed} failed ==")
finally:
    kill_tree(runner.pid)
    runner_log.close()
    for c in created_chats:
        try:
            call("DELETE", f"/chats/{c}")
        except Exception:
            pass
    if kb_id:
        try:
            call("DELETE", f"/knowledge/bases/{kb_id}?force=true")
        except Exception:
            pass
    try:
        call("DELETE", f"/agent-nodes/{node_id}")
    except Exception:
        pass

sys.exit(1 if failed else 0)
