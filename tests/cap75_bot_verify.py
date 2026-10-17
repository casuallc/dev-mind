# -*- coding: utf-8 -*-
"""CAP-75 M2+M3+M5+M6 双向机器人 E2E：fake 适配器驱动全路由 + 飞书出站回推到本地 mock +
个人身份流（/bindme 取码 → 门禁 → 绑定放行）+ 运维端点断言 + M5 聊天开发闭环
（出站图片 / 入站图片 / /status /stop / /wi /design）+ M6 运行闭环
（/flow 状态推进与二次确认 / /release 发版与空态）。

运行前提（仿 cap49）：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. 起隔离实例（独立 H2 空库 + e2e profile——fake 适配器与 /api/e2e/bot 端点仅 e2e 装配；
   不带 --spring.profiles.active=e2e 则默认 local profile 抢共享 MySQL）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18097 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:./tmp/cap75-e2e/devmind;AUTO_SERVER=TRUE"
   注意 JVM 参数别传 MSYS 形态路径（会解析成 D:\\d\\...），必要时 cygpath -m。
3. 不需要 runner 节点、不需要 node——chat-mock 与 feishu-mock 都是本脚本自起的 python 进程，
   跑完自清理（finally terminate）。

覆盖：
  A /help 与未知指令回复
  B event_id 重发去重（同 eventId 第二次被吞，只回一次）
  C 群聊免打扰：未 @ 机器人不回，@ 才回
  D /bind 项目（含无参查当前绑定）→ /build、/deploy 空态回复 → /session 无节点 409 失败文案
    （有记录的构建状态格式见 BotRouterServiceTest 单测，E2E 起真构建要 git+节点，不划算）
  D2 研发主线（M4 FR-15）：API 建需求 → /reqs 列出 → /req req-1 详情（兼容小写/无前缀编号）
    → /dev REQ-1 起会话（无节点 409 失败文案，证明需求已解析、挂在会话创建阶段失败）
  E 裸文本问答：懒建 MODEL 问答 → chat-mock 全量正文回推；/new 重开后下一轮仍是新问答；
    绑定问答在平台侧被删 → 续问自愈重建（不回「问答不存在」，本轮消息不丢，绑定换挂新问答）
  F 飞书出站：建 ENABLED 飞书集成（baseUrl 指向本地 mock）→ 注入 channel=feishu 消息
    → 断言 mock 收到 tenant_access_token 与 im/v1 messages/{id}/reply 回推（正文含帮助文本、
    Authorization 头正确）。长连接收事件方向不在 E2E 范围（要真飞书租户），归一化由
    FeishuBotAdapterNormalizeTest 单测覆盖。
  G M3 个人身份流（feishu channel——门禁只作用于支持身份映射的渠道，fake 不受门禁）：
    /bindme 回复身份码+指引 → 未绑定发 /build 门禁拒绝、问答放行 →
    admin 经 /api/me/platform-accounts 绑 open_id → /whoami 命中个人绑定 → /build 过门禁
  H M3 运维端点：/api/bot/admin/interactions 含 OK/DEDUPED/REJECTED 行且过滤生效；
    /bindings 可见；/api/admin/user-platform-accounts?type=FEISHU 含刚绑定的身份码；
    非 ADMIN 访问两类管理端点均 403
  I M5 出站图片（FR-16）：multipart 上传附件 → 建含 ![](/api/attachments/<id>/raw)
    引用的需求 → /req 详情回「附 1 张需求截图」且 /e2e/bot/images 捕获该图
  J M5 入站图片（FR-17）：注入 imageKey 消息 → 「已收到截图」回执 + 附件库出现
    「bot 用户 u-e2e 发送的截图」行 + 交互日志记 IMAGE 指令；feishu 未绑定用户发图
    → 门禁拒绝（与敏感操作同口径）。截图→会话消费挂 attachmentIds 由单测覆盖
    （E2E 无 runner，起会话恒 409，消费不可观测）
  K M5 /status /stop 空态：无成功会话 → 「暂无开发会话」/「没有运行中的会话」
  L M5 /wi /design：API 建工作单元 → /wi 列出；无方案 → /design 回「暂无方案」
  M M6 运行闭环（FR-21/22/23）：/flow 无目标回当前状态 → 非终态直接推进 → 完结走
    二次确认（回复「确认」落库）→ 取消走二次确认（回复「取消」丢弃且状态不变，
    经 API 复核）→ DONE 撞未完结工作单元提前给 force 提示；/release 无参空态 +
    缺省构建无带产物成功构建的明确文案。FR-20 终态推送与 /stop 确认流在本 E2E
    不可观测（无 runner → 起会话恒 409 → 无 session.completed 事件），由单测覆盖。
"""
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("E2E_BASE", "http://localhost:18097/api")
HERE = os.path.dirname(os.path.abspath(__file__))
CHAT_PORT = int(os.environ.get("CHAT_MOCK_PORT", "18196"))
FEISHU_PORT = int(os.environ.get("FEISHU_MOCK_PORT", "18197"))
CHAT_BASE = f"http://127.0.0.1:{CHAT_PORT}/v1"
FEISHU_BASE = f"http://127.0.0.1:{FEISHU_PORT}"
CHAT_FIXTURE = os.path.join(HERE, "fixtures", "chat-mock.py")
passed = 0
failed = 0
TOKEN = None
# 每次运行唯一后缀：eventId/会话/项目名全带它——同库重跑时底座 event_id 内存去重
# （1h TTL）不会把第二轮的消息全吞掉，脚本可重复执行
RUN = str(int(time.time()))


def cid(name):
    return f"{name}-{RUN}"


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


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


# ---------------- bot 驱动与回复断言 ----------------

def send(chat, text, event_id, chat_type="p2p", mentioned=False, channel=None,
         message_id=None, sender="u-e2e", image_key=None):
    event_id = f"{event_id}-{RUN}"
    body = {"externalChatId": cid(chat), "chatType": chat_type, "senderId": sender,
            "text": text, "mentioned": mentioned, "eventId": event_id,
            "messageId": message_id or ("m-" + event_id)}
    if channel:
        body["channel"] = channel
    if image_key:
        body["imageKey"] = image_key
    st, resp = call("POST", "/e2e/bot/message", body)
    if st != 200:
        check(f"注入消息 {event_id}", False, f"{st} {resp}")


def images(chat):
    st, rs = call("GET", f"/e2e/bot/images?chatId={cid(chat)}")
    return rs if st == 200 and isinstance(rs, list) else []


def upload_attachment(filename, content, content_type, description=""):
    """手工拼 multipart（urllib 无原生支持）：POST /attachments，返回 (status, view)"""
    boundary = f"----cap75e2e{RUN}"
    body = b""

    def field(name, value):
        return (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\""
                f"\r\n\r\n{value}\r\n").encode("utf-8")

    body += field("scope", "SHARED")
    if description:
        body += field("description", description)
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"{filename}\"\r\nContent-Type: {content_type}\r\n\r\n").encode("utf-8")
    body += content + b"\r\n"
    body += f"--{boundary}--\r\n".encode("utf-8")
    req = urllib.request.Request(
        BASE + "/attachments", data=body, method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 "Authorization": "Bearer " + TOKEN})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def replies(chat):
    st, rs = call("GET", f"/e2e/bot/replies?chatId={cid(chat)}")
    return rs if st == 200 and isinstance(rs, list) else []


def wait_reply(chat, n=1, timeout=30):
    """回复走底座虚拟线程，是异步的：轮询到第 n 条出现"""
    deadline = time.time() + timeout
    rs = []
    while time.time() < deadline:
        rs = replies(chat)
        if len(rs) >= n:
            return rs
        time.sleep(0.3)
    return rs


# ---------------- 飞书开放平台本地 mock ----------------

feishu_requests = []  # {method, path, auth, body}


class FeishuMockHandler(BaseHTTPRequestHandler):
    def _json(self, obj, code=200):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8", "replace") if length else ""
        feishu_requests.append({"method": "POST", "path": self.path,
                                "auth": self.headers.get("Authorization"), "body": body})
        if self.path == "/open-apis/auth/v3/tenant_access_token/internal":
            self._json({"code": 0, "msg": "ok",
                        "tenant_access_token": "t-e2e-token", "expire": 7200})
        elif self.path.startswith("/open-apis/im/v1/messages"):
            self._json({"code": 0, "msg": "ok", "data": {"message_id": "om_resp_1"}})
        else:
            self._json({"code": 0, "msg": "ok", "data": {}})

    def log_message(self, *a):
        pass


def wait_feishu(pred, timeout=20):
    deadline = time.time() + timeout
    while time.time() < deadline:
        hit = [r for r in feishu_requests if pred(r)]
        if hit:
            return hit
        time.sleep(0.3)
    return []


mock_proc = None
feishu_srv = None
try:
    # ---------- 0. 起 chat-mock + feishu-mock + 登录 ----------
    os.makedirs(os.path.join(HERE, os.pardir, "tmp"), exist_ok=True)
    mock_log = open(os.path.join(HERE, os.pardir, "tmp", "cap75-chat-mock.log"), "w", encoding="utf-8")
    mock_proc = subprocess.Popen([sys.executable, CHAT_FIXTURE, str(CHAT_PORT)],
                                 stdout=mock_log, stderr=subprocess.STDOUT)
    feishu_srv = ThreadingHTTPServer(("127.0.0.1", FEISHU_PORT), FeishuMockHandler)
    threading.Thread(target=feishu_srv.serve_forever, daemon=True).start()
    time.sleep(1.5)

    st, login = call("POST", "/auth/login", {"username": os.environ.get("E2E_USER", "admin"),
                                             "password": os.environ.get("E2E_PASS", "admin123")})
    TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    # 问答走 MODEL 执行体：建 CHAT 端点（指向 chat-mock）并设平台默认
    st, ep = call("POST", "/model-endpoints",
                  {"kind": "CHAT", "name": f"CAP75-E2E-对话端点-{RUN}", "provider": "openai-compatible",
                   "baseUrl": CHAT_BASE, "apiKey": "sk-e2e-cap75", "model": "fake-chat",
                   "timeoutSeconds": 10})
    EP = (ep or {}).get("id")
    check("建 kind=CHAT 端点", st == 200 and bool(EP), f"{st} {ep}")
    st, _ = call("PUT", f"/model-endpoints/{EP}/default")
    check("设为平台默认对话端点", st == 200, f"{st}")

    call("POST", "/e2e/bot/reset")

    # ---------- A. /help 与未知指令 ----------
    print("\n[A] 指令路由：/help 与未知指令")
    send("c-help", "/help", "e-help-1")
    rs = wait_reply("c-help")
    check("/help 回复可用指令", bool(rs) and "可用指令" in rs[0]["text"], f"{rs}")
    send("c-help", "/nope", "e-nope-1")
    rs = wait_reply("c-help", 2)
    check("未知指令回复提示", len(rs) >= 2 and "未知指令" in rs[1]["text"], f"{rs}")

    # ---------- B. event_id 重发去重 ----------
    print("\n[B] event_id 重发去重")
    send("c-dup", "/help", "e-dup-1")
    send("c-dup", "/help", "e-dup-1")
    rs = wait_reply("c-dup", 1)
    check("首次事件有回复", bool(rs), f"{rs}")
    time.sleep(2.5)
    rs = replies("c-dup")
    check("同 eventId 重发被吞（仍只有 1 条回复）", len(rs) == 1, f"n={len(rs)} {rs}")

    # ---------- C. 群聊免打扰 ----------
    print("\n[C] 群聊只响应 @机器人")
    send("c-grp", "/help", "e-grp-1", chat_type="group", mentioned=False)
    time.sleep(2.5)
    check("群聊未 @ 不回", not replies("c-grp"), f"{replies('c-grp')}")
    send("c-grp", "/help", "e-grp-2", chat_type="group", mentioned=True)
    rs = wait_reply("c-grp")
    check("群聊 @ 后正常回复", bool(rs) and "可用指令" in rs[0]["text"], f"{rs}")

    # ---------- D. 绑定 + 项目操作 ----------
    print("\n[D] /bind /build /deploy /session")
    # 项目创建强制校验 git 仓库路径：spring-boot:run 的 cwd 是 devmind-app 模块目录，".." 即仓库根
    PNAME = f"CAP75-E2E-机器人项目-{RUN}"
    st, proj = call("POST", "/projects", {"name": PNAME, "path": ".."})
    PID = (proj or {}).get("id")
    check("创建项目", st == 200 and bool(PID), f"{st} {proj}")

    send("c-ops", "/build", "e-nobind-1")
    rs = wait_reply("c-ops")
    check("未绑定先 /build → 提示先绑定", bool(rs) and "请先" in rs[0]["text"], f"{rs}")

    send("c-ops", f"/bind {PID}", "e-bind-1")
    rs = wait_reply("c-ops", 2)
    check("/bind 绑定成功", len(rs) >= 2 and "已绑定项目" in rs[1]["text"]
          and PNAME in rs[1]["text"], f"{rs[-1:]}")

    send("c-ops", "/bind", "e-bind-2")
    rs = wait_reply("c-ops", 3)
    check("/bind 无参查当前绑定", len(rs) >= 3 and "当前绑定项目" in rs[2]["text"], f"{rs[-1:]}")

    send("c-ops", "/build", "e-build-1")
    send("c-ops", "/deploy", "e-deploy-1")
    rs = wait_reply("c-ops", 5)
    texts = [r["text"] for r in rs]
    check("/build 空态回复", any("暂无构建记录" in t for t in texts), f"{texts}")
    check("/deploy 空态回复", any("暂无部署记录" in t for t in texts), f"{texts}")

    send("c-ops", "/session 加个登录页", "e-sess-1")
    rs = wait_reply("c-ops", 6)
    check("/session 无可用节点 → 失败文案（不静默）",
          len(rs) >= 6 and "操作失败" in rs[5]["text"], f"{rs[-1:]}")

    # ---------- D2. 研发主线指令（M4 FR-15） ----------
    print("\n[D2] /reqs /req /dev（研发主线）")
    st, req1 = call("POST", f"/projects/{PID}/requirements",
                    {"title": f"登录加验证码-{RUN}", "description": "支持图形验证码与滑块"})
    check("API 建需求", st == 200 and (req1 or {}).get("code") == "REQ-1", f"{st} {req1}")

    send("c-ops", "/reqs", "e-reqs-1")
    rs = wait_reply("c-ops", 7)
    check("/reqs 列出未完结需求",
          len(rs) >= 7 and "REQ-1" in rs[6]["text"] and "登录加验证码" in rs[6]["text"],
          f"{rs[-1:]}")

    send("c-ops", "/req req-1", "e-req-1")
    rs = wait_reply("c-ops", 8)
    check("/req 按编号出详情（兼容 req-1 小写形态）",
          len(rs) >= 8 and "REQ-1" in rs[7]["text"] and "滑块" in rs[7]["text"], f"{rs[-1:]}")

    send("c-ops", "/dev REQ-1 先做图形验证码", "e-dev-1")
    rs = wait_reply("c-ops", 9)
    check("/dev 需求已解析、起会话撞无节点 409 → 失败文案（不静默）",
          len(rs) >= 9 and "操作失败" in rs[8]["text"], f"{rs[-1:]}")

    # ---------- E. 问答 ----------
    print("\n[E] 裸文本问答（MODEL 执行体懒建 + /new 重开 + 绑定问答被删自愈重建）")
    with urllib.request.urlopen(f"http://127.0.0.1:{CHAT_PORT}/__state", timeout=10) as r:
        full_answer = "".join(json.loads(r.read())["chunks"])
    check("chat-mock 就绪", bool(full_answer), "chunks 为空")

    send("c-qa", "你好，介绍一下你自己", "e-qa-1")
    rs = wait_reply("c-qa", 1, timeout=90)
    check("问答回答回推（= chat-mock 全量正文）",
          bool(rs) and full_answer in rs[0]["text"], f"{rs[-1:] if rs else None}")

    send("c-qa", "/new", "e-new-1")
    rs = wait_reply("c-qa", 2)
    check("/new 回复已重开", len(rs) >= 2 and "已重开问答" in rs[1]["text"], f"{rs[-1:]}")

    send("c-qa", "再来一轮", "e-qa-2")
    rs = wait_reply("c-qa", 3, timeout=90)
    check("重开后新一轮照常回答", len(rs) >= 3 and full_answer in rs[2]["text"], f"{rs[-1:]}")

    # 绑定问答在平台侧被删 → 续问自愈重建（不得回「问答不存在」，本轮消息不丢）
    st, bl = call("GET", "/bot/admin/bindings")
    chat_id = next((b.get("chatSessionId") for b in (bl or [])
                    if b.get("channel") == "fake" and b.get("externalChatId") == cid("c-qa")), None)
    check("群绑定上挂着问答会话", bool(chat_id), f"{bl}")
    if chat_id:
        st, _ = call("DELETE", f"/chats/{chat_id}")
        check("平台侧删除绑定问答", st == 200, f"{st}")
        send("c-qa", "会话被删后再问", "e-qa-3")
        rs = wait_reply("c-qa", 4, timeout=90)
        check("问答被删后自愈重建（本轮消息不丢）",
              len(rs) >= 4 and full_answer in rs[3]["text"], f"{rs[-1:] if rs else None}")
        st, bl2 = call("GET", "/bot/admin/bindings")
        new_id = next((b.get("chatSessionId") for b in (bl2 or [])
                       if b.get("channel") == "fake" and b.get("externalChatId") == cid("c-qa")), None)
        check("绑定换挂新问答会话", bool(new_id) and new_id != chat_id, f"{new_id} vs {chat_id}")

    # ---------- F. 飞书出站回推 ----------
    print("\n[F] 飞书出站：集成 baseUrl 指向本地 mock，断言 reply 请求到达")
    # 多飞书应用并存口径：无 bot 槽的文档专用集成必须被机器人网关跳过（botEnabled 过滤）
    st, doc_only = call("POST", "/integrations", {
        "type": "FEISHU", "name": f"CAP75-E2E-飞书文档专用-{RUN}",
        "baseUrl": "http://127.0.0.1:18199",  # 无服务监听：若误选它，回推必失败
        "authType": "BASIC", "username": "cli_docs_only", "token": "secret_docs_only"})
    check("创建文档专用飞书集成（无 bot 槽）", st == 200, f"{st} {doc_only}")

    st, integ = call("POST", "/integrations", {
        "type": "FEISHU", "name": f"CAP75-E2E-飞书-{RUN}", "baseUrl": FEISHU_BASE,
        "authType": "BASIC", "username": "cli_e2e_bot", "token": "secret_e2e_bot",
        "configJson": json.dumps({"bot": {"defaultActor": "admin"}})})
    check("创建 ENABLED 飞书集成（baseUrl=本地 mock）",
          st == 200 and (integ or {}).get("type") == "FEISHU", f"{st} {integ}")

    send("oc_e2e_1", "/help", "fe-1", channel="feishu", message_id="om_e2e_1")
    hits = wait_feishu(lambda r: r["path"].startswith("/open-apis/im/v1/messages/om_e2e_1/reply"))
    check("mock 收到 im/v1 reply 回推", bool(hits),
          f"requests={[r['path'] for r in feishu_requests]}")
    if hits:
        body = json.loads(hits[0]["body"] or "{}")
        check("reply 正文含帮助文本", "可用指令" in json.dumps(body, ensure_ascii=False),
              f"{body}")
        check("reply content 为内嵌 JSON 字符串（飞书真机契约）",
              isinstance(body.get("content"), str), f"{body}")
        check("reply 带 tenant_token 鉴权头", hits[0]["auth"] == "Bearer t-e2e-token",
              f"{hits[0]['auth']}")
    check("mock 先收到 tenant_access_token 申请",
          bool([r for r in feishu_requests
                if r["path"] == "/open-apis/auth/v3/tenant_access_token/internal"]),
          f"{[r['path'] for r in feishu_requests]}")

    # ---------- G. M3 个人身份流：/bindme 取码 → 门禁拒绝 → 绑定放行 ----------
    print("\n[G] M3 个人身份流（feishu channel；fake channel 不支持身份映射，门禁不拦）")
    ALICE = f"ou_e2e_{RUN}"
    INT_ID = (integ or {}).get("id")
    E2E_USER = os.environ.get("E2E_USER", "admin")

    def feishu_text(hit):
        """reply 请求体 {msg_type, content}；飞书真机要求 content 为**内嵌 JSON 字符串**
        （对象形态真机必拒——本函数对对象形态返回标记串让断言失败，勿兼容放宽）"""
        body = json.loads(hit["body"] or "{}")
        content = body.get("content")
        if not isinstance(content, str):
            return f"<content 非字符串形态: {type(content).__name__}>"
        return json.loads(content).get("text", "")

    def wait_feishu_text(message_id, timeout=30):
        hits = wait_feishu(lambda r: r["path"] == f"/open-apis/im/v1/messages/{message_id}/reply",
                           timeout=timeout)
        return feishu_text(hits[0]) if hits else ""

    send("oc_e2e_id", "/bindme", "g-bm-1", channel="feishu",
         message_id=f"om_g_bm_{RUN}", sender=ALICE)
    t = wait_feishu_text(f"om_g_bm_{RUN}")
    check("/bindme 回复身份码与绑定指引", ALICE in t and "第三方账号" in t, t)

    send("oc_e2e_id", "/build", "g-gate-1", channel="feishu",
         message_id=f"om_g_gate_{RUN}", sender=ALICE)
    t = wait_feishu_text(f"om_g_gate_{RUN}")
    check("未绑定发 /build → 门禁拒绝文案", "需要先绑定个人身份" in t, t)

    send("oc_e2e_id", "什么是蓝绿部署", "g-qa-1", channel="feishu",
         message_id=f"om_g_qa_{RUN}", sender=ALICE)
    t = wait_feishu_text(f"om_g_qa_{RUN}", timeout=90)
    check("未绑定问答放行（门禁不拦问答）", full_answer in t, t[:200])

    st, pa = call("PUT", f"/me/platform-accounts/{INT_ID}", {"username": ALICE})
    check("平台侧绑定飞书身份码（设置→第三方账号，authType 强制 IDENTITY）",
          st == 200 and (pa or {}).get("authType") == "IDENTITY", f"{st} {pa}")

    send("oc_e2e_id", "/whoami", "g-wa-1", channel="feishu",
         message_id=f"om_g_wa_{RUN}", sender=ALICE)
    t = wait_feishu_text(f"om_g_wa_{RUN}")
    check("/whoami 命中个人绑定", "个人绑定" in t and ALICE in t, t)

    send("oc_e2e_id", "/build", "g-gate-2", channel="feishu",
         message_id=f"om_g_gate2_{RUN}", sender=ALICE)
    t = wait_feishu_text(f"om_g_gate2_{RUN}")
    check("绑定后 /build 过门禁（落到未绑项目提示而非门禁文案）",
          "需要先绑定个人身份" not in t and "请先" in t, t)

    # ---------- H. M3 运维端点：交互日志 / 群绑定 / 用户映射 + 权限 ----------
    print("\n[H] M3 运维端点（/api/bot/admin/** 与 /api/admin/user-platform-accounts，仅 ADMIN）")
    # DEDUPED 行走 botExecutor 异步落库，轮询等其出现
    items = []
    deadline = time.time() + 30
    while time.time() < deadline:
        st, page = call("GET", "/bot/admin/interactions?size=100")
        if st == 200:
            items = (page or {}).get("items") or []
            if {"OK", "DEDUPED", "REJECTED"} <= {r.get("status") for r in items}:
                break
        time.sleep(0.5)
    statuses = {r.get("status") for r in items}
    check("交互日志分页可查且含 OK 行", "OK" in statuses, f"{statuses}")
    check("交互日志含 DEDUPED 行（B 节重发）", "DEDUPED" in statuses, f"{statuses}")
    check("交互日志含 REJECTED 行（G 节门禁）", "REJECTED" in statuses, f"{statuses}")
    check("REJECTED 行记了指令/发送者/渠道",
          any(r.get("command") == "build" and r.get("senderId") == ALICE
              and r.get("channel") == "feishu" for r in items),
          f"{[r for r in items if r.get('status') == 'REJECTED']}")

    st, page = call("GET", "/bot/admin/interactions?channel=feishu&status=REJECTED")
    filtered = (page or {}).get("items") or []
    check("channel+status 过滤生效",
          st == 200 and bool(filtered)
          and all(r.get("channel") == "feishu" and r.get("status") == "REJECTED"
                  for r in filtered),
          f"{st} n={len(filtered)}")

    st, bs = call("GET", "/bot/admin/bindings")
    check("群绑定列表含本项目会话",
          st == 200 and any(b.get("externalChatId") == cid("c-ops") for b in (bs or [])),
          f"{st} {[b.get('externalChatId') for b in (bs or [])]}")

    st, maps = call("GET", "/admin/user-platform-accounts?type=FEISHU")
    check("用户映射含刚绑定的身份码",
          st == 200 and any(m.get("externalUsername") == ALICE
                            and m.get("username") == E2E_USER for m in (maps or [])),
          f"{st} {maps}")

    DEV = f"cap75dev{RUN}"
    st, _ = call("POST", "/auth/users",
                 {"username": DEV, "password": "dev123456", "role": "DEVELOPER",
                  "displayName": "E2E开发者"})
    check("建 DEVELOPER 用户", st == 200, f"{st}")
    st, dlogin = call("POST", "/auth/login", {"username": DEV, "password": "dev123456"})
    dev_token = (dlogin or {}).get("token") or (dlogin or {}).get("accessToken")
    check("DEVELOPER 登录", st == 200 and bool(dev_token), f"{st}")
    admin_token = TOKEN
    TOKEN = dev_token
    st403a, _ = call("GET", "/bot/admin/interactions")
    st403b, _ = call("GET", "/admin/user-platform-accounts?type=FEISHU")
    TOKEN = admin_token
    check("非 ADMIN 访问交互日志 403", st403a == 403, f"{st403a}")
    check("非 ADMIN 访问用户映射 403", st403b == 403, f"{st403b}")

    # ---------- I. M5 出站图片：/req 详情把需求描述里的截图逐张回推（FR-16） ----------
    print("\n[I] M5 出站图片：附件引用 → /req 回推图片")
    PNG = b"\x89PNG\r\n\x1a\n" + bytes(range(64))
    FNAME = f"e2e-shot-{RUN}.png"
    st, att = upload_attachment(FNAME, PNG, "image/png", description="E2E 出站图片")
    AID = (att or {}).get("attachmentId") if isinstance(att, dict) else None
    check("multipart 上传附件", st == 200 and bool(AID) and len(AID) == 32, f"{st} {att}")

    st, req2 = call("POST", f"/projects/{PID}/requirements",
                    {"title": f"页面报错截图修复-{RUN}",
                     "description": f"复现步骤见图\n![截图](/api/attachments/{AID}/raw)"})
    check("建带附件引用的需求 REQ-2", st == 200 and (req2 or {}).get("code") == "REQ-2",
          f"{st} {req2}")

    send("c-ops", "/req REQ-2", "i-req-1")
    rs = wait_reply("c-ops", 10)
    check("/req 详情提示附图", len(rs) >= 10 and "附 1 张需求截图" in rs[9]["text"],
          f"{rs[-1:] if rs else None}")
    imgs = []
    deadline = time.time() + 20
    while time.time() < deadline and not imgs:
        imgs = images("c-ops")
        if not imgs:
            time.sleep(0.3)
    check("fake 捕获 1 张出站图片（原字节/原名/原类型）",
          len(imgs) == 1 and imgs[0]["size"] == len(PNG)
          and imgs[0]["fileName"] == FNAME and imgs[0]["contentType"] == "image/png",
          f"{imgs}")

    # ---------- J. M5 入站图片：发截图给 bot → 存平台附件 + 门禁（FR-17） ----------
    print("\n[J] M5 入站图片：截图收执 + 附件落库 + feishu 未绑定门禁")
    send("c-img", None, "j-img-1", image_key="img_v3_e2e_a")
    rs = wait_reply("c-img")
    check("收到截图回执（暂存 1 张）",
          bool(rs) and "已收到截图（暂存 1 张" in rs[0]["text"], f"{rs}")

    st, atts = call("GET", "/attachments?scope=SHARED")
    check("附件库出现 bot 截图行（SHARED 域）",
          st == 200 and any("bot 用户 u-e2e 发送的截图" in (a.get("description") or "")
                            for a in (atts or [])),
          f"{st} {[(a.get('originalName'), a.get('description')) for a in (atts or [])]}")

    deadline = time.time() + 20
    img_rows = []
    while time.time() < deadline:
        st, page = call("GET", "/bot/admin/interactions?size=200")
        img_rows = [r for r in ((page or {}).get("items") or [])
                    if r.get("command") == "IMAGE" and r.get("status") == "OK"]
        if img_rows:
            break
        time.sleep(0.5)
    check("交互日志记 IMAGE 指令（OK）", bool(img_rows), f"{img_rows}")

    IMG_SENDER = f"ou_img_{RUN}"
    send("oc_e2e_img", None, "j-gate-1", channel="feishu",
         message_id=f"om_j_gate_{RUN}", sender=IMG_SENDER, image_key="img_v3_e2e_gate")
    t = wait_feishu_text(f"om_j_gate_{RUN}")
    check("feishu 未绑定发图 → 门禁拒绝文案（同敏感操作）", "需要先绑定个人身份" in t, t)

    # ---------- K. M5 会话控制空态：/status /stop（FR-18） ----------
    print("\n[K] M5 /status /stop（无成功会话的空态）")
    send("c-ops", "/status", "k-status-1")
    rs = wait_reply("c-ops", 11)
    check("/status 空态回复", len(rs) >= 11 and "暂无开发会话" in rs[10]["text"],
          f"{rs[-1:] if rs else None}")

    send("c-ops", "/stop", "k-stop-1")
    rs = wait_reply("c-ops", 12)
    check("/stop 无运行中会话 → 明确提示（不静默）",
          len(rs) >= 12 and "没有运行中的会话" in rs[11]["text"],
          f"{rs[-1:] if rs else None}")

    # ---------- L. M5 主线查看：/wi /design（FR-19） ----------
    print("\n[L] M5 /wi /design（研发主线查看）")
    # 注意：D2 的 /dev REQ-1 已按 CAP-38 自动建了一个 DEVELOPMENT 工作单元（WI-1，
    # create 无事务，409 不回滚），所以这里新建的是 WI-2，/wi 共 2 个
    st, wi = call("POST", f"/projects/{PID}/requirements/{req1['id']}/work-items",
                  {"title": f"登录接口-{RUN}", "type": "DEVELOPMENT"})
    check("API 建工作单元", st == 200 and (wi or {}).get("code") == "WI-2", f"{st} {wi}")

    send("c-ops", "/wi REQ-1", "l-wi-1")
    rs = wait_reply("c-ops", 13)
    check("/wi 列出工作单元（含 /dev 自动建的 WI-1）",
          len(rs) >= 13 and "工作单元（2 个）" in rs[12]["text"]
          and "WI-1" in rs[12]["text"] and "WI-2" in rs[12]["text"],
          f"{rs[-1:] if rs else None}")

    send("c-ops", "/design REQ-1", "l-design-1")
    rs = wait_reply("c-ops", 14)
    check("/design 空态回复（暂无方案）",
          len(rs) >= 14 and "暂无方案" in rs[13]["text"], f"{rs[-1:] if rs else None}")

    # ---------- M. M6 运行闭环：/flow /release（FR-22 / FR-23 + FR-21 二次确认） ----------
    print("\n[M] M6 /flow /release（状态推进 / 二次确认 / 发版空态）")
    # REQ-2 无工作单元：完结不会被平台 409 挡；REQ-1 有 2 个未完结 WI，用来验 force 提示
    send("c-ops", "/flow REQ-2", "m-flow-1")
    rs = wait_reply("c-ops", 15)
    check("/flow 无目标 → 当前状态 + 可选目标手册",
          len(rs) >= 15 and "当前状态：草稿（DRAFT）" in rs[14]["text"]
          and "可推进到：" in rs[14]["text"] and "/flow REQ-2 <目标状态>" in rs[14]["text"],
          f"{rs[-1:] if rs else None}")

    send("c-ops", "/flow REQ-2 验收", "m-flow-2")
    rs = wait_reply("c-ops", 16)
    check("/flow 非终态直接推进（不打扰确认）",
          len(rs) >= 16 and "REQ-2 状态已更新：验收（ACCEPTANCE）" in rs[15]["text"],
          f"{rs[-1:] if rs else None}")

    send("c-ops", "/flow REQ-2 完结", "m-flow-3")
    rs = wait_reply("c-ops", 17)
    check("/flow 推进到完结 → 二次确认（FR-21）",
          len(rs) >= 17 and "危险操作确认" in rs[16]["text"]
          and "推进到 完结（DONE）" in rs[16]["text"], f"{rs[-1:] if rs else None}")
    # 确认 = 原指令重放（同 chat 同 sender），身份重新装配
    send("c-ops", "确认", "m-flow-4")
    rs = wait_reply("c-ops", 18)
    check("回复「确认」→ 完结落库",
          len(rs) >= 18 and "REQ-2 状态已更新：完结（DONE）" in rs[17]["text"],
          f"{rs[-1:] if rs else None}")

    send("c-ops", "/flow REQ-1 取消", "m-flow-5")
    rs = wait_reply("c-ops", 19)
    check("/flow 取消 → 二次确认", len(rs) >= 19 and "危险操作确认" in rs[18]["text"],
          f"{rs[-1:] if rs else None}")
    # 取消要"没落库"：拿平台当前状态做参照（/dev REQ-1 已按 CAP-38 推着需求进了开发中，
    # 所以基准不是草稿，只能前后比对）
    st, page = call("GET", f"/projects/{PID}/requirements?size=50")
    before = next((r.get("status") for r in ((page or {}).get("items") or [])
                   if r.get("code") == "REQ-1"), None)
    check("取消前取到 REQ-1 基准状态（非终态）",
          st == 200 and before not in (None, "DONE", "CANCELLED"), f"{st} {before}")
    send("c-ops", "取消", "m-flow-6")
    rs = wait_reply("c-ops", 20)
    check("回复「取消」→ 丢弃不落库",
          len(rs) >= 20 and "已取消，操作未执行" in rs[19]["text"],
          f"{rs[-1:] if rs else None}")
    st, page = call("GET", f"/projects/{PID}/requirements?size=50")
    after = next((r.get("status") for r in ((page or {}).get("items") or [])
                  if r.get("code") == "REQ-1"), None)
    check("取消后 REQ-1 状态未变（未被推进到取消）", after == before,
          f"{st} before={before} after={after}")

    send("c-ops", "/flow REQ-1 完结", "m-flow-7")
    rs = wait_reply("c-ops", 21)
    check("DONE 撞未完结工作单元 → 提前给 force 提示（不白确认一轮）",
          len(rs) >= 21 and "还有 2 个未完结工作单元" in rs[20]["text"]
          and "/flow REQ-1 完结 force" in rs[20]["text"], f"{rs[-1:] if rs else None}")

    send("c-ops", "/release", "m-rel-1")
    rs = wait_reply("c-ops", 22)
    check("/release 无参 → 空态（暂无发版记录）",
          len(rs) >= 22 and "暂无发版记录" in rs[21]["text"], f"{rs[-1:] if rs else None}")

    send("c-ops", "/release 1.0.0", "m-rel-2")
    rs = wait_reply("c-ops", 23)
    check("/release 缺省构建无带产物成功构建 → 明确文案（不静默）",
          len(rs) >= 23 and "操作失败" in rs[22]["text"]
          and "没有带产物的成功构建" in rs[22]["text"], f"{rs[-1:] if rs else None}")

    # FR-20 终态推送与 /stop 确认流在本 E2E 不可观测：无 runner → 起会话恒 409 → 无
    # session.completed 事件（前者由 BotSessionWatchListenerTest 覆盖，后者 BotRouterServiceTest）

    print(f"\n== CAP-75 M2+M3+M5+M6 bot E2E: {passed} passed, {failed} failed ==")
finally:
    if mock_proc:
        mock_proc.terminate()
    if feishu_srv:
        feishu_srv.shutdown()

sys.exit(1 if failed else 0)
