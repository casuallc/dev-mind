# -*- coding: utf-8 -*-
"""CAP-49 问答模型执行体 E2E（chat-mock 流式 SSE + mock embedding，**全程无 runner 节点**）。

运行前提：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. app 独立实例已起（独立 H2 空库 + mock embedding：迁移会种下平台默认向量端点，绑库用例靠它）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18090 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:./tmp/cap49-e2e/devmind;AUTO_SERVER=TRUE \
       --devmind.knowledge.embedding.provider=mock --devmind.knowledge.embedding.dimensions=64"
   `--spring.profiles.active=e2e` 必须带：默认 local profile 指向共享 MySQL。
   也不要拿 cap48 跑过的库来跑本脚本——cap48 会删掉迁移种子的向量端点，绑库索引会 disabled。
3. 本机有 node（WS 侧断言交给 tests/cap49-ws-test.mjs，与 cap23 同姿势）。
4. **不需要 runner jar**：本脚本一个节点都不注册——"无节点也能问答"本身就是被测行为。

覆盖：
  A 建 CHAT 端点（设平台默认）+ **无节点**建 MODEL 问答：视图字段/端点被钉住/首轮跑通
  B WS 流式：多条 text_delta（先到）→ 全量 assistant → result{isError:false,subtype:success} → WAITING_INPUT，
    且 WS 与落库事件逐条一致
  C 多轮装配：历史 user/assistant 齐全、本轮提问在最后、不绑库时无任何注入
  D 绑库注入与前缀剥离：system 带 <knowledge-base> 概览、本轮正文带新 <knowledge-context>、
    历轮注入块已剥离（整个请求体只剩 1 个 </knowledge-context>）
  E 中断：长流中停止生成 → 部分正文保留 + result.subtype=interrupted + 可继续提问
  F 动作语义：suspend/authorize/空闲 interrupt 一律 409；被拒动作在 WS 上只回 notice（不断流）
  G 失败面：MODEL 冲突字段/图片 400、端点停用后提问 409（读历史不受影响）、401 脱敏不炸链路、
    删被进行中问答引用的端点 409、无 CHAT 端点建模型问答 409、Agent 执行体仍必须有节点（无本机回落）
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
WS_BASE = os.environ.get("DEVMIND_WS", "ws://localhost:18090")
HERE = os.path.dirname(os.path.abspath(__file__))
CHAT_PORT = int(os.environ.get("CHAT_MOCK_PORT", "18195"))
CHAT_BASE = f"http://127.0.0.1:{CHAT_PORT}/v1"
FIXTURE = os.path.join(HERE, "fixtures", "chat-mock.py")
WS_HELPER = os.path.join(HERE, "cap49-ws-test.mjs")
passed = 0
failed = 0
TOKEN = None

Q1 = "开场提问：用一句话介绍你自己。"
Q2 = "第二轮问题：列出两条发布检查要点。"
Q3 = "第三轮问题：把上面两轮的内容合成一句话总结。"


def call(method, path, body=None, timeout=60):
    url = BASE + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def _mock_call(path, body=None):
    """对话 mock 控制面（改分片 / 注入故障 / 回看请求）。

    注意：无 body ⇒ GET。控制面里除 /__state 外一律 POST，调用时**必须给 body**（哪怕 {}），
    否则打到 do_GET 上拿 404（本脚本第一版就是这么死的：`_mock_call("/__reset")` 静默变 GET）。
    """
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{CHAT_PORT}{path}", data=data,
                                 method="POST" if body is not None else "GET",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def chunks():
    return _mock_call("/__state")["chunks"]


def chat_requests():
    return _mock_call("/__state")["requests"]


def last_request():
    reqs = chat_requests()
    return reqs[-1] if reqs else {}


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def chat(cid):
    st, c = call("GET", f"/chats/{cid}")
    return c if st == 200 else {}


def wait_chat(cid, pred, timeout=60):
    """轮询问答视图直到断言成立（模型回合是异步的，创建返回时状态还是 RUNNING）"""
    deadline = time.time() + timeout
    c = {}
    while time.time() < deadline:
        c = chat(cid)
        if pred(c):
            return c
        time.sleep(0.5)
    return c


def wait_input(cid, timeout=60):
    return wait_chat(cid, lambda c: c.get("state") == "WAITING_INPUT", timeout)


def events(cid, after_seq=0):
    st, evs = call("GET", f"/chats/{cid}/events?afterSeq={after_seq}")
    if st != 200 or not isinstance(evs, list):
        print(f"    [WARN] 读事件失败: chat={cid} status={st} body={str(evs)[:200]}")
        return []
    return evs


def last_seq(cid):
    return max([e.get("seq") or 0 for e in events(cid)] or [0])


def wait_db_settled(cid, after_seq=0, timeout=20):
    """等本轮事件全部落库。

    ChatEventSaver 是 200ms 批量落库，而问答状态列是即时的——`wait_input` 一返回就读事件，
    常读到"少最后一批（assistant/result）"的中间态。E2E 首版因此偶发假失败（同一份代码两次
    跑出不同结果），故一律轮询到 result 事件出现为止。
    """
    deadline = time.time() + timeout
    evs = []
    while time.time() < deadline:
        evs = events(cid, after_seq)
        if any(e.get("type") == "result" for e in evs):
            return evs
        time.sleep(0.3)
    print(f"    [WARN] 等落库超时: chat={cid} afterSeq={after_seq} 已有类型={[e.get('type') for e in evs]}")
    return evs


def of_type(evs, etype):
    return [e.get("content") for e in evs if e.get("type") == etype]


def payloads(evs, etype):
    return [e.get("payload") or {} for e in evs if e.get("type") == etype]


def ws_run(mode, cid, question):
    node = shutil.which("node")
    if not node:
        check(f"WS {mode} 用例（需要 node）", False, "PATH 里没有 node")
        return {}
    r = subprocess.run([node, WS_HELPER, cid, mode, question, WS_BASE],
                       capture_output=True, timeout=150, encoding="utf-8", errors="replace")
    print("    " + (r.stdout or "").strip().replace("\n", "\n    "))
    if (r.stderr or "").strip():
        print("    [stderr] " + r.stderr.strip()[:400])
    out = {}
    for line in (r.stdout or "").splitlines():
        if line.startswith("WS_JSON "):
            out = json.loads(line[len("WS_JSON "):])
    check(f"WS {mode} 用例收敛", out.get("ok") is True,
          f"exit={r.returncode} json={json.dumps(out, ensure_ascii=False)[:300]}")
    return out


def ask(cid, text):
    """REST 提问 + 等回合收尾 + 等本轮事件落库；返回 (提问前最大 seq, 本轮已落库事件)"""
    before = last_seq(cid)
    call("POST", f"/chats/{cid}/input", {"text": text})
    wait_input(cid)
    return before, wait_db_settled(cid, before)


mock_proc = None
created_chats = []
EP = None
KB = None
try:
    # ---------- 0. 起假对话服务 + 登录 ----------
    mock_log = open(os.path.join(HERE, os.pardir, "tmp", "cap49-chat-mock.log"), "w", encoding="utf-8")
    mock_proc = subprocess.Popen([sys.executable, FIXTURE, str(CHAT_PORT)],
                                 stdout=mock_log, stderr=subprocess.STDOUT)
    time.sleep(1.5)
    try:
        state0 = _mock_call("/__state")
        check("chat-mock 已就绪且默认分片每段超过合并阈值",
              all(len(c) > 24 for c in state0["chunks"]),
              f"{state0['chunks']}")
    except Exception as e:
        check("chat-mock 已就绪", False, str(e))
        sys.exit(1)

    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    # ---------- 1. 建 CHAT 端点并设为平台默认 ----------
    print("\n[A] 建对话端点 + 无节点建模型问答")
    st, ep = call("POST", "/model-endpoints",
                  {"kind": "CHAT", "name": "CAP49-E2E-对话端点", "provider": "openai-compatible",
                   "baseUrl": CHAT_BASE, "apiKey": "sk-e2e-cap49-secret", "model": "fake-chat",
                   "timeoutSeconds": 10})
    EP = (ep or {}).get("id")
    check("建 kind=CHAT 端点", st == 200 and bool(EP), f"{st} {ep}")
    st, _ = call("PUT", f"/model-endpoints/{EP}/default")
    check("设为平台默认对话端点", st == 200, f"{st}")

    st, nodes = call("GET", "/agent-nodes")
    online_nodes = [n for n in (nodes or []) if n.get("status") == "ONLINE"]
    if online_nodes:
        print(f"    [WARN] 实例上有 {len(online_nodes)} 个在线节点，"
              f"「无节点可用」的断言前提不成立（相关用例会跳过）")

    # 无节点 + 不传 modelEndpointId ⇒ 走平台默认 CHAT 端点
    st, c1 = call("POST", "/chats", {"message": Q1, "executor": "MODEL"})
    check("无节点也能建模型问答", st == 200 and bool(c1), f"{st} {c1}")
    cid1 = (c1 or {}).get("id")
    if not cid1:
        sys.exit(1)
    created_chats.append(cid1)
    check("视图回传执行体=MODEL 且端点被钉住（id/名/模型）",
          c1.get("executor") == "MODEL" and c1.get("modelEndpointId") == EP
          and c1.get("modelEndpointName") == "CAP49-E2E-对话端点"
          and c1.get("modelEndpointModel") == "fake-chat", f"{c1}")
    check("模型问答无执行节点（不进节点路由）", not c1.get("agentNodeId"), f"{c1}")

    c = wait_input(cid1)
    check("首轮生成完回到 WAITING_INPUT", c.get("state") == "WAITING_INPUT", f"{c}")
    full1 = "".join(chunks())
    t1 = wait_db_settled(cid1)
    a1 = [e for e in t1 if e.get("type") == "assistant"]
    check("首轮落全量 assistant 且与分片拼接一致",
          bool(a1) and a1[-1].get("content") == full1, f"{[(e.get('type')) for e in t1]}")
    r1 = payloads(t1, "result")
    check("首轮 result{isError:false,subtype:success}",
          bool(r1) and r1[-1].get("isError") is False and r1[-1].get("subtype") == "success",
          f"{r1[-1:]}")
    deltas1 = [e for e in t1 if e.get("type") == "text_delta"]
    check("首轮流式：每段分片各成一条 text_delta（先于 assistant）",
          len(deltas1) == len(chunks())
          and "".join(e.get("content") or "" for e in deltas1) == full1
          and max(e["seq"] for e in deltas1) < a1[-1]["seq"],
          f"deltas={len(deltas1)} chunks={len(chunks())}")
    req1 = last_request()
    check("流式请求：stream=true / 打到 /chat/completions / 不发 max_tokens / 带解密密钥",
          req1.get("stream") is True and (req1.get("path") or "").endswith("/chat/completions")
          and req1.get("hasMaxTokens") is False and req1.get("model") == "fake-chat"
          and req1.get("auth") == "Bearer sk-e2e-cap49-secret", f"{req1}")
    check("首轮 system 提示为模型执行体固定提示（未绑库无 <knowledge-base>）",
          (req1.get("messages") or [{}])[0].get("role") == "system"
          and "<knowledge-base>" not in ((req1.get("messages") or [{}])[0].get("content") or ""),
          f"{(req1.get('messages') or [{}])[0].get('content', '')[:80]}")

    # ---------- 2. WS 流式（无节点） ----------
    print("\n[B] WS 实时流：增量 → 全量 → result → 回 WAITING_INPUT")
    _mock_call("/__reset", {})
    before = last_seq(cid1)
    ws = ws_run("stream", cid1, Q2)
    wd, wa, wr = ws.get("deltas") or [], ws.get("assistant"), ws.get("result") or {}
    check("WS 收到多条 text_delta 且拼接等于全量正文",
          len(wd) == len(chunks()) and "".join(wd) == full1 and wa == full1, f"{wd}")
    check("WS 增量 seq 全部早于 assistant（先增量后全量）",
          bool(ws.get("deltaSeqs")) and bool(ws.get("assistantSeq"))
          and max(ws["deltaSeqs"]) < ws["assistantSeq"], f"{ws.get('deltaSeqs')} vs {ws.get('assistantSeq')}")
    check("WS result{isError:false} 且 subtype=success",
          wr.get("isError") is False and wr.get("subtype") == "success", f"{wr}")
    check("WS 状态机：RUNNING → WAITING_INPUT",
          (ws.get("states") or [])[:1] == ["RUNNING"]
          and (ws.get("states") or [])[-1:] == ["WAITING_INPUT"], f"{ws.get('states')}")
    check("WS 无 error 帧", not (ws.get("errors") or []), f"{ws.get('errors')}")
    check("WS 回显本轮提问", (ws.get("users") or [])[-1:] == [Q2], f"{ws.get('users')}")
    t2 = wait_db_settled(cid1, before)
    check("落库事件与 WS 逐条一致（增量与全量）",
          of_type(t2, "text_delta") == wd and of_type(t2, "assistant") == [wa],
          f"db={of_type(t2, 'text_delta')} ws={wd}")
    check("落库 result 与 WS 一致", payloads(t2, "result")[-1:] == [wr], f"{payloads(t2, 'result')}")

    # ---------- 3. 多轮装配（不绑库） ----------
    print("\n[C] 多轮上下文装配（不绑库：历史齐全、无任何注入）")
    _mock_call("/__reset", {})
    ask(cid1, Q3)
    req3 = last_request()
    msgs = [{"role": m.get("role"), "content": m.get("content") or ""} for m in (req3.get("messages") or [])]
    check("装配形状 = system + 两轮历史 + 本轮提问",
          [m["role"] for m in msgs] == ["system", "user", "assistant", "user", "assistant", "user"],
          f"{[m['role'] for m in msgs]}")
    check("历轮提问与回答逐字进入历史（含首轮创建时的提问）",
          msgs[1]["content"] == Q1 and msgs[2]["content"] == full1
          and msgs[3]["content"] == Q2 and msgs[4]["content"] == full1, f"{msgs[1:5]}")
    check("本轮提问在最后一条且原样（未绑库不注入）", msgs[5]["content"] == Q3, f"{msgs[5]}")
    check("不绑库请求体里没有任何知识注入标记",
          all("<knowledge-context>" not in m["content"] and "<knowledge-base>" not in m["content"]
              for m in msgs), f"{[m['content'][:40] for m in msgs]}")

    # ---------- 4. 绑库问答：注入 + 历轮前缀剥离 ----------
    print("\n[D] 绑库模型问答：system 概览 + 本轮注入 + 历轮注入块剥离")
    st, kb = call("POST", "/knowledge/bases", {
        "name": "E2E CAP49 发布规范库", "description": "CAP49 注入验证",
        "scope": "global", "injectMode": "RAG"})
    KB = (kb or {}).get("id")
    check("创建 RAG 知识库", st == 200 and bool(KB), f"{st} {kb}")
    st, entry = call("POST", "/knowledge/entries", {
        "kbId": KB, "name": "E2E CAP49 发布检查单",
        "contentMd": "发布前必须执行回归套件 regression-suite-49，并逐条核对发布检查单。",
        "status": "active"})
    entry_id = (entry or {}).get("id")
    check("创建知识条目", st == 200 and bool(entry_id), f"{st} {entry}")
    deadline, ready = time.time() + 60, None
    while time.time() < deadline:
        st, e = call("GET", f"/knowledge/entries/{entry_id}")
        if st == 200 and e.get("indexStatus") in ("ready", "failed"):
            ready = e
            break
        time.sleep(1)
    check("条目索引 ready（实例需带 --devmind.knowledge.embedding.provider=mock）",
          (ready or {}).get("indexStatus") == "ready", f"{ready}")
    if (ready or {}).get("indexStatus") != "ready":
        sys.exit(1)

    K1 = "发布检查单里写了什么？"
    K2 = "发布前要执行哪个回归套件？"
    K3 = "把发布检查单的要点总结成一句话。"
    st, c2 = call("POST", "/chats", {"message": K1, "executor": "MODEL", "knowledgeBaseId": KB})
    cid2 = (c2 or {}).get("id")
    check("建绑库模型问答", st == 200 and bool(cid2) and c2.get("knowledgeBaseId") == KB, f"{st} {c2}")
    if not cid2:
        sys.exit(1)
    created_chats.append(cid2)
    wait_input(cid2)

    _mock_call("/__reset", {})
    ask(cid2, K2)
    msgs = (last_request().get("messages") or [])
    sysmsg = (msgs[0].get("content") or "") if msgs else ""
    check("system 带库概览节（<knowledge-base> + 库名）",
          msgs and msgs[0].get("role") == "system"
          and "<knowledge-base>" in sysmsg and "E2E CAP49 发布规范库" in sysmsg, f"{sysmsg[:120]}")
    check("首轮提问无注入痕迹（创建路径不做检索注入）",
          len(msgs) > 1 and msgs[1].get("content") == K1, f"{msgs[1] if len(msgs) > 1 else None}")
    body2 = json.dumps(msgs, ensure_ascii=False)
    check("本轮正文带新 <knowledge-context>（含条目名与命中内容）",
          "<knowledge-context>" in (msgs[-1].get("content") or "")
          and "E2E CAP49 发布检查单" in (msgs[-1].get("content") or ""), f"{(msgs[-1].get('content') or '')[:200]}")
    check("此时整个请求体只有 1 个 </knowledge-context>（= 本轮那一个）",
          body2.count("</knowledge-context>") == 1, f"count={body2.count('</knowledge-context>')}")

    _mock_call("/__reset", {})
    ask(cid2, K3)
    msgs3 = (last_request().get("messages") or [])
    body3 = json.dumps(msgs3, ensure_ascii=False)
    check("第三轮：历轮注入块已剥离，整个请求体仍只有 1 个 </knowledge-context>",
          body3.count("</knowledge-context>") == 1, f"count={body3.count('</knowledge-context>')}")
    check("<knowledge-base> 只出现在 system（首轮概览，不进历史）",
          sum(1 for m in msgs3 if "<knowledge-base>" in (m.get("content") or "")) == 1
          and "<knowledge-base>" in (msgs3[0].get("content") or ""),
          f"{[m['role'] for m in msgs3]}")
    check("第二轮提问以剥离后的原文进入历史（不带检索前缀）",
          any((m.get("content") or "") == K2 for m in msgs3), f"{[(m.get('role'), (m.get('content') or '')[:40]) for m in msgs3]}")
    check("本轮提问带新注入块", "<knowledge-context>" in (msgs3[-1].get("content") or ""), f"{msgs3[-1]}")
    check("第三轮才有的历史：第二轮回答也在（顺序递增）",
          [m.get("role") for m in msgs3][-1] == "user" and len(msgs3) == 6, f"{[m.get('role') for m in msgs3]}")

    # ---------- 5. 中断 ----------
    print("\n[E] 停止生成：保留部分正文、可继续提问")
    held = ["第一段先发出去，随后服务端会停住，此时用户按下停止生成。",
            "第二段不该出现在回答里，因为它在停止之后才被尝试写出。",
            "第三段同样不该出现。"]
    # 先 reset 再配分片：/__reset 会把分片与故障注入一起恢复默认（顺序反了就白配了）
    _mock_call("/__reset", {})
    _mock_call("/__chunks", {"chunks": held, "holdMs": 4000})
    before = last_seq(cid1)
    ws = ws_run("interrupt", cid1, "第四轮问题：长流中断验证。")
    wr = ws.get("result") or {}
    check("中断 result{subtype:interrupted} 且 isError=false",
          wr.get("subtype") == "interrupted" and wr.get("isError") is False, f"{wr}")
    check("WS 在中断前只收到首段增量",
          len(ws.get("deltasAtInterrupt") or []) == 1
          and (ws.get("deltasAtInterrupt") or [""])[0] == held[0], f"{ws.get('deltasAtInterrupt')}")
    t4 = wait_db_settled(cid1, before)
    check("部分正文落库（= 中断前已产出的首段，后两段不出现）",
          of_type(t4, "assistant") == [held[0]], f"{t4 and of_type(t4, 'assistant')}")
    check("中断后会话回到 WAITING_INPUT（没被杀）",
          (ws.get("states") or [])[-1:] == ["WAITING_INPUT"] and chat(cid1).get("state") == "WAITING_INPUT",
          f"{ws.get('states')} {chat(cid1).get('state')}")
    check("中断不产生 error 帧", not (ws.get("errors") or []), f"{ws.get('errors')}")
    _mock_call("/__chunks", {"chunks": chunks(), "holdMs": 0})
    _mock_call("/__reset", {})
    _, t5 = ask(cid1, "第五轮问题：中断之后还能继续提问吗？")
    check("中断后继续提问照常拿到完整回答", of_type(t5, "assistant") == [full1],
          f"{of_type(t5, 'assistant')}")

    # ---------- 6. 动作语义（模型执行体） ----------
    print("\n[F] 动作语义：挂起/授权/空闲中断 409；被拒动作只回 notice")
    st, e = call("POST", f"/chats/{cid1}/suspend")
    check("suspend ⇒ 409（无进程可挂）",
          st == 409 and "挂起" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
    st, e = call("POST", f"/chats/{cid1}/authorize",
                 {"accepted": True, "scope": "once", "requestId": "r-1"})
    check("authorize ⇒ 409（无授权概念）",
          st == 409 and "授权" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
    st, e = call("POST", f"/chats/{cid1}/interrupt")
    check("空闲时 interrupt ⇒ 409（没有正在生成的回答）",
          st == 409 and "没有正在生成" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
    _mock_call("/__reset", {})
    ws = ws_run("notice", cid1, "第六轮问题：notice 之后流还在吗？")
    check("被拒动作只回 notice 帧（没有 error 帧掀掉实时流）",
          bool(ws.get("notices")) and not (ws.get("errors") or []),
          f"notices={ws.get('notices')} errors={ws.get('errors')}")
    check("notice 之后同一连接仍能提问并拿到回答",
          (ws.get("assistant") or "") == "".join(chunks())
          and (ws.get("result") or {}).get("isError") is False, f"{ws.get('assistant')}")

    # ---------- 7. 失败面 ----------
    print("\n[G] 失败面：冲突字段/图片 400、端点停用 409、401 脱敏、引用保护、无端点 409")
    for field, bad in (("scenarioCode", "any-scenario"), ("agentNodeId", "1"),
                       ("permissionMode", "default"), ("model", "claude-x")):
        st, e = call("POST", "/chats", {"message": "x", "executor": "MODEL", field: bad})
        check(f"MODEL + {field} ⇒ 400（不静默忽略）", st == 400, f"{st} {e}")
    st, e = call("POST", "/chats", {"message": "x", "executor": "MODEL", "modelEndpointId": 999999})
    check("MODEL + 不存在的端点 ⇒ 400", st == 400, f"{st} {e}")

    st, e = call("POST", f"/chats/{cid1}/input",
                 {"text": "带图提问", "images": [{"attachmentId": "att-not-exist", "name": "a.png"}]})
    check("MODEL + 图片 ⇒ 400（在附件解析之前就拒）",
          st == 400 and "图片" in json.dumps(e, ensure_ascii=False), f"{st} {e}")

    call("PUT", f"/model-endpoints/{EP}/status", {"status": "disabled"})
    st, e = call("POST", "/chats", {"message": "x", "executor": "MODEL"})
    check("停用后无默认 CHAT 端点 ⇒ 建模型问答 409",
          st == 409 and "通用对话端点" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
    st, e = call("POST", "/chats", {"message": "x", "executor": "MODEL", "modelEndpointId": EP})
    check("停用后显式指定该端点 ⇒ 400", st == 400, f"{st} {e}")

    st, e = call("POST", f"/chats/{cid1}/input", {"text": "端点停用后还能提问吗"})
    check("已钉端点被停用后提问 ⇒ 409（明确说明，不静默换模型）",
          st == 409 and "已停用或删除" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
    check("端点没了也能读历史（列表/详情/事件不因此打不开）",
          st is not None and chat(cid1).get("id") == cid1 and bool(events(cid1)),
          f"{chat(cid1).get('state')}")

    call("PUT", f"/model-endpoints/{EP}/status", {"status": "active"})
    _mock_call("/__reset", {})
    _, t_active = ask(cid1, "端点恢复后再次提问。")
    check("端点恢复后提问正常（会话没被停用动作打死）",
          of_type(t_active, "assistant") == [full1], f"{of_type(t_active, 'assistant')}")

    _mock_call("/__reset", {})
    _mock_call("/__status", {"code": 401})
    _, t401 = ask(cid1, "401 注入下的提问。")
    errs = [c or "" for c in of_type(t401, "error")]
    check("401 ⇒ error 事件可读且报出状态码", bool(errs) and "401" in errs[-1], f"{errs}")
    check("401 ⇒ 错误文本脱敏，不含明文密钥",
          bool(errs) and "***" in errs[-1] and "sk-e2e-cap49-secret" not in errs[-1], f"{errs}")
    check("401 ⇒ result{isError:true}（否则会话永远停在 RUNNING、输入框 disabled）",
          [p.get("isError") for p in payloads(t401, "result")] == [True], f"{payloads(t401, 'result')}")
    check("401 ⇒ 会话未被判死，仍可继续提问", chat(cid1).get("state") == "WAITING_INPUT",
          f"{chat(cid1).get('state')}")
    _mock_call("/__reset", {})
    _mock_call("/__status", {"code": 200})
    _, t_rec = ask(cid1, "故障恢复后的提问。")
    check("401 恢复后照常回答（链路没被一次失败打死）",
          of_type(t_rec, "assistant") == [full1], f"{of_type(t_rec, 'assistant')}")

    # 删除保护：只保护"正在生成"的问答
    _mock_call("/__chunks", {"chunks": held, "holdMs": 6000})
    call("POST", f"/chats/{cid1}/input", {"text": "长流占用中的提问。"})
    time.sleep(2)
    st, e = call("DELETE", f"/model-endpoints/{EP}")
    check("删除被进行中问答引用的端点 ⇒ 409 且点名是哪个问答",
          st == 409 and "问答：" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
    call("POST", f"/chats/{cid1}/interrupt")
    wait_input(cid1)
    _mock_call("/__chunks", {"chunks": chunks(), "holdMs": 0})
    st, e = call("DELETE", f"/model-endpoints/{EP}")
    check("空闲后同端点可删（引用保护只认正在生成的）", st == 200, f"{st} {e}")
    if st == 200:
        EP = None

    if not online_nodes:
        st, e = call("POST", "/chats", {"message": "Agent 也来一次"})
        check("无节点时 Agent 问答仍 409（CAP-34 无本机回落）",
              st == 409 and "执行节点" in json.dumps(e, ensure_ascii=False), f"{st} {e}")
        st, e = call("POST", "/chats", {"message": "x", "agentNodeId": "local"})
        check("agentNodeId=\"local\" 保留值 ⇒ 400", st == 400, f"{st} {e}")

    print(f"\n== CAP-49 E2E: {passed} passed, {failed} failed ==")
finally:
    if mock_proc:
        mock_proc.terminate()
    if EP:
        try:
            call("PUT", f"/model-endpoints/{EP}/status", {"status": "active"})
        except Exception:
            pass
    for c in created_chats:
        try:
            call("DELETE", f"/chats/{c}", timeout=30)
        except Exception:
            pass
    if EP:
        try:
            call("DELETE", f"/model-endpoints/{EP}")
        except Exception:
            pass
    if KB:
        try:
            call("DELETE", f"/knowledge/bases/{KB}?force=true")
        except Exception:
            pass

sys.exit(1 if failed else 0)
