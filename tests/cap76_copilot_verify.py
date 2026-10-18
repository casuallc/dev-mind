# -*- coding: utf-8 -*-
"""CAP-76 平台智能体网关 E2E（自起隔离 app + 内嵌假 OpenAI 服务 + fake bot 适配器）。

运行前提：
  1. `mvn -q install -DskipTests`；
  2. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium jdk-21.0.12.101。

脚本自起隔离 app（:18096，--spring.profiles.active=e2e 独立 H2，仓根/工作区落
tmp/cap76-e2e/，三个 worklog 定时开关关掉），假 OpenAI 服务内嵌在 :18198——
同一进程里既能回原生 tools/tool_calls（非流式），也能回 SSE（kill switch 回落 CAP-75）。
跑完 app 与 mock 都清理，不留残留。

验收覆盖（CAP-76 §11 E2E）：
  [1] 只读：REQ-1 什么状态 → get_requirement 执行，回执正确且有工具审计；
  [2] 写：建需求 → 生成确认单；确认前需求不存在；文本确认后落库；
  [3] 越权：VIEWER 发起写 → 拒绝且无确认单；
  [4] 他人确认 → 查不到该短码、不执行，原确认单仍 PENDING；
  [5] /cancel 后 CANCELLED 且再确认不执行；超时后 EXPIRED 且不执行；
  [6] 卡片回调 → 执行且 updateCard 被调用（按钮 value 带确认单 id）；
  [7] DANGEROUS（需求置 DONE）：裸「确认」被拒，完整短码才执行；
  [8] kill switch → 裸文本回落 CAP-75 问答（流式）；
  [9] 管理 API：非 ADMIN 403，ADMIN 200。

阶段划分（同一套 H2 顺序重启）：
  A. enabled=true / default-actor=admin / allow-default-actor-writes=true → 场景 1~7、9
  B. enabled=true / default-actor=viewer → 场景 3
  C. enabled=false → 场景 8
"""
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("E2E_PORT", "18096"))
MOCK_PORT = int(os.environ.get("CAP76_MOCK_PORT", "18198"))
BASE = f"http://localhost:{PORT}/api"
MOCK_BASE = f"http://127.0.0.1:{MOCK_PORT}/v1"
TMP = ROOT / "tmp" / "cap76-e2e"

# JDK 21（mvn 与 app 都要）
JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

CHAT = "c1"
U1 = "u1"
U2 = "u2"

passed, failed = [], []
TOKEN = None
PID = None          # 项目 id
REQ1_ID = None      # REQ-1 的 id（假模型回填给 update_requirement_status）
_event_seq = 0


# ----------------------------------------------------------------------
# 假 OpenAI 服务（tools/tool_calls + SSE）
# ----------------------------------------------------------------------

MOCK = {"requirementId": "", "requests": []}


def _mock_decision(messages):
    """按最后一条消息决定：tool 结果 → 收口；否则按用户文本选工具或直接回答。"""
    last = messages[-1] if messages else {}
    if last.get("role") == "tool":
        return ("answer", "工具执行结果：\n" + str(last.get("content") or ""))
    text = str(last.get("content") or "")
    if "标记为完成" in text:
        return ("tool", "update_requirement_status",
                {"requirementId": MOCK["requirementId"], "status": "DONE"})
    if "REQ-1 什么状态" in text:
        return ("tool", "get_requirement", {"seq": 1})
    if "建一个需求" in text:
        title = text.split("建一个需求", 1)[1].strip() or "未命名需求"
        return ("tool", "create_requirement", {"title": title})
    return ("answer", "这是问答回答，来自假模型。")


class MockHandler(BaseHTTPRequestHandler):

    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        pass

    def _send(self, code, obj):
        raw = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self):
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                size_line = self.rfile.readline().strip()
                if not size_line:
                    break
                size = int(size_line.split(b";")[0], 16)
                if size == 0:
                    self.rfile.readline()
                    break
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
            raw = b"".join(chunks)
        else:
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
        try:
            return json.loads(raw.decode("utf-8")) if raw else {}
        except Exception:
            return {}

    def do_GET(self):
        if urllib.parse.urlparse(self.path).path == "/__state":
            self._send(200, MOCK)
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        if path == "/__config":
            body = self._body()
            if "requirementId" in body:
                MOCK["requirementId"] = body["requirementId"]
            self._send(200, {"ok": True})
            return
        if path == "/__reset":
            MOCK["requests"] = []
            self._send(200, {"ok": True})
            return
        if not path.endswith("/chat/completions"):
            self._send(404, {"error": "not found"})
            return

        body = self._body()
        messages = body.get("messages") or []
        stream = bool(body.get("stream"))
        MOCK["requests"].append({
            "model": body.get("model"),
            "stream": stream,
            "hasTools": bool(body.get("tools")),
            "lastRole": (messages[-1] or {}).get("role") if messages else None,
        })

        decision = _mock_decision(messages)
        kind, payload = decision[0], decision[1]
        extra = decision[2] if len(decision) > 2 else None

        if stream:
            self._sse(body, payload if kind == "answer" else "流式端不执行工具")
            return
        if kind == "tool":
            name, args = payload, extra
            self._send(200, {
                "id": "chatcmpl-mock-tool",
                "object": "chat.completion",
                "model": body.get("model"),
                "choices": [{
                    "index": 0,
                    "finish_reason": "tool_calls",
                    "message": {
                        "role": "assistant",
                        "content": "",
                        "tool_calls": [{
                            "id": "call-mock-" + str(int(time.time() * 1000) % 100000),
                            "type": "function",
                            "function": {"name": name,
                                         "arguments": json.dumps(args, ensure_ascii=False)},
                        }],
                    },
                }],
                "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5},
            })
            return
        self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": body.get("model"),
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": payload}}],
            "usage": {"prompt_tokens": 3, "completion_tokens": 2, "total_tokens": 5},
        })

    def _sse(self, body, answer):
        chunks = [answer[:max(1, len(answer) // 2)], answer[max(1, len(answer) // 2):]]
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Transfer-Encoding", "chunked")
            self.end_headers()
            for piece in chunks:
                payload = {"id": "chatcmpl-mock", "object": "chat.completion.chunk",
                           "model": body.get("model"),
                           "choices": [{"index": 0, "delta": {"content": piece},
                                        "finish_reason": None}]}
                self._write_chunk("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n")
            self._write_chunk("data: [DONE]\n\n")
            self._write_chunk("")
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass

    def _write_chunk(self, text):
        raw = text.encode("utf-8")
        self.wfile.write(b"%X\r\n" % len(raw) + raw + b"\r\n")
        self.wfile.flush()


class _QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError, ConnectionAbortedError)):
            return  # 客户端（Java HttpClient 连接池）先关连接，属正常
        super().handle_error(request, client_address)


def start_mock():
    server = _QuietServer(("127.0.0.1", MOCK_PORT), MockHandler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


def stop_mock(server):
    try:
        server.shutdown()
        server.server_close()
    except Exception:
        pass


# ----------------------------------------------------------------------
# 通用工具
# ----------------------------------------------------------------------

def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + ("" if cond else f"  {detail}"))


def api(method, path, body=None, token=None, expect=200, timeout=60):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method,
                                     headers={"Content-Type": "application/json; charset=utf-8"})
    auth = token if token is not None else TOKEN
    if auth:
        request.add_header("Authorization", "Bearer " + auth)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return resp.status, json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", "replace")
        if e.code != expect:
            raise AssertionError(f"{method} {path} -> {e.code} (expect {expect}): {payload[:400]}")
        try:
            return e.code, json.loads(payload or "null")
        except json.JSONDecodeError:
            return e.code, payload


def wait_health(timeout=180):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://localhost:{PORT}/api/health", timeout=3) as r:
                if r.status == 200:
                    return True
        except Exception:
            pass
        time.sleep(2)
    return False


def wait_port_free(timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            urllib.request.urlopen(f"http://localhost:{PORT}/api/health", timeout=2)
        except Exception:
            return True
        time.sleep(1)
    return False


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def kill_port_strays():
    out = subprocess.run(["netstat", "-ano"], capture_output=True).stdout.decode("utf-8", "replace")
    for line in out.splitlines():
        if f":{PORT}" in line and "LISTENING" in line:
            pid = line.split()[-1]
            subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)


def _force_rmtree(path):
    def _onerror(func, p, _exc):
        try:
            os.chmod(p, 0o666)
        except Exception:
            pass
        try:
            func(p)
        except Exception:
            pass
    if os.path.exists(path):
        shutil.rmtree(path, onerror=_onerror)


# ----------------------------------------------------------------------
# 起停隔离 app
# ----------------------------------------------------------------------

def app_args(phase):
    common = (
        f'--server.port={PORT} '
        f'--spring.datasource.url=jdbc:h2:file:{(TMP / "db" / "devmind").as_posix()};AUTO_SERVER=TRUE '
        f'--devmind.project.workspace-root={(TMP / "repositories").as_posix()} '
        f'--devmind.worktree.root={(TMP / "worktrees").as_posix()} '
        f'--devmind.attachment.root-dir={(TMP / "attachments").as_posix()} '
        f'--devmind.worklog.repo-root={(TMP / "worklog").as_posix()} '
        f'--devmind.worklog.daily-enabled=false '
        f'--devmind.worklog.weekly-enabled=false '
        f'--devmind.worklog.git-import-enabled=false '
        f'--devmind.copilot.max-turns-per-minute=1000 '
        f'--devmind.copilot.confirm-ttl-minutes=1'
    )
    if phase == "C":
        return common + ' --devmind.copilot.enabled=false'
    actor = "viewer" if phase == "B" else "admin"
    return common + (
        ' --devmind.copilot.enabled=true'
        f' --devmind.bot.default-actor={actor}'
        ' --devmind.copilot.allow-default-actor-writes=true'
    )


def start_app(phase):
    log_path = TMP / f"app-{phase.lower()}.log"
    log = open(log_path, "w", encoding="utf-8")
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
    # cron 含空格，塞不进 spring-boot.run.arguments；走环境变量宽松绑定
    env["DEVMIND_COPILOT_ACTION_SWEEP_CRON"] = "*/5 * * * * *"
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{app_args(phase)}"')
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_health():
        log.close()
        raise AssertionError(f"app({phase}) 180s 内未起来（看 {log_path}）")
    print(f"[app-{phase}] 已起（:{PORT}）")
    return proc, log


def stop_app(proc, log):
    kill_tree(proc.pid)
    time.sleep(2)
    kill_port_strays()
    log.close()
    wait_port_free()


# ----------------------------------------------------------------------
# bot 驱动与查询辅助
# ----------------------------------------------------------------------

def send(text, sender=U1, chat=CHAT):
    global _event_seq
    _event_seq += 1
    api("POST", "/e2e/bot/message", {
        "channel": "fake",
        "externalChatId": chat,
        "senderId": sender,
        "text": text,
        "eventId": f"cap76-evt-{_event_seq}",
    })


def replies(chat=CHAT):
    _, data = api("GET", f"/e2e/bot/replies?chatId={urllib.parse.quote(chat)}")
    return data or []


def cards():
    _, data = api("GET", "/e2e/bot/cards")
    return data or []


def wait_reply(contains, base=0, chat=CHAT, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for r in replies(chat)[base:]:
            if contains in (r.get("text") or ""):
                return r
        time.sleep(0.4)
    raise AssertionError(f"{timeout}s 内未等到回复包含 {contains!r}；最近回复="
                         + repr([r.get("text") for r in replies(chat)[-6:]]))


def wait_card(predicate, base=0, timeout=60, what="卡片"):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for c in cards()[base:]:
            if predicate(c):
                return c
        time.sleep(0.4)
    raise AssertionError(f"{timeout}s 内未等到{what}；最近卡片="
                         + repr([(c.get("kind"), c.get("title")) for c in cards()[-8:]]))


def send_and_wait_reply(text, contains, sender=U1, chat=CHAT, timeout=60):
    base = len(replies(chat))
    send(text, sender=sender, chat=chat)
    return wait_reply(contains, base=base, chat=chat, timeout=timeout)


def admin_actions(**params):
    query = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
    _, data = api("GET", "/copilot/admin/actions?" + query, token=TOKEN)
    return data or {"items": [], "total": 0}


def action_by_code(code):
    page = admin_actions(keyword=code, size=50)
    for item in page.get("items") or []:
        if item.get("shortCode") == code:
            return item
    return None


def wait_action_status(code, status, timeout=60):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = action_by_code(code)
        if last and last.get("status") == status:
            return last
        time.sleep(1)
    raise AssertionError(f"{timeout}s 内确认单 {code} 未变为 {status}；最后={last}")


def write_confirmation(text, expect_title="创建需求", sender=U1, chat=CHAT):
    """发一条写请求，等到确认卡，返回 (code, card)。"""
    base = len(cards())
    send(text, sender=sender, chat=chat)
    card = wait_card(lambda c: (c.get("title") or "").startswith("待确认")
                     and expect_title in (c.get("title") or ""), base=base, what="确认卡")
    m = re.search(r"待确认 \[([A-Za-z0-9-]+)\]", card["title"])
    if not m:
        raise AssertionError(f"确认卡标题里没有短码: {card['title']}")
    return m.group(1), card


def requirement_by_keyword(keyword):
    _, page = api("GET", f"/projects/{PID}/requirements?keyword="
                  + urllib.parse.quote(keyword) + "&size=50", token=TOKEN)
    return (page or {}).get("items") or []


def login(username, password="admin123"):
    status, body = api("POST", "/auth/login", {"username": username, "password": password})
    return (body or {}).get("token") or (body or {}).get("accessToken")


# ----------------------------------------------------------------------
# 场景
# ----------------------------------------------------------------------

def scenario_1_read():
    print("\n[1] 只读：REQ-1 什么状态 → get_requirement")
    base = len(cards())
    send("REQ-1 什么状态")
    card = wait_card(lambda c: "REQ-1" in (c.get("markdown") or "")
                     and "DRAFT" in (c.get("markdown") or ""), base=base, what="只读回答卡")
    check("回复含 REQ-1 与状态 DRAFT", True, "")

    _, page = api("GET", "/copilot/admin/turns?channel=fake&actor=admin&keyword="
                  + urllib.parse.quote("REQ-1 什么状态"), token=TOKEN)
    turns = (page or {}).get("items") or []
    check("对话审计落 turn", len(turns) >= 1, str(page))
    detail = api("GET", f"/copilot/admin/turns/{turns[0]['id']}", token=TOKEN)[1] if turns else {}
    calls = (detail or {}).get("toolCalls") or []
    get_call = next((c for c in calls if c.get("toolName") == "get_requirement"), None)
    check("工具调用审计 get_requirement=OK",
          bool(get_call) and get_call.get("status") == "OK", str(calls))
    check("只读回答卡标题", card.get("title") == "Dev-Mind 智能体", str(card.get("title")))


def scenario_2_write():
    print("\n[2] 写：建需求 → 确认单 → 未确认不落库 → 文本确认后创建")
    code, card = write_confirmation("建一个需求 登录流程联调")
    check("生成确认卡（非危险模板 + 两按钮）",
          card.get("template") == "WARNING" and card.get("buttons") == 2, str(card))
    check("确认前需求不存在", len(requirement_by_keyword("登录流程联调")) == 0,
          str(requirement_by_keyword("登录流程联调")))
    action = action_by_code(code)
    check("确认单为 PENDING",
          bool(action) and action.get("status") == "PENDING", str(action))

    send_and_wait_reply(f"确认 {code}", "已创建需求")
    rows = requirement_by_keyword("登录流程联调")
    check("确认后需求已落库", len(rows) == 1, str(rows))
    check("确认单 SUCCEEDED", (action_by_code(code) or {}).get("status") == "SUCCEEDED")


def scenario_4_other_person_confirm():
    print("\n[4] 他人确认 → 不执行、原确认单仍 PENDING")
    code, _ = write_confirmation("建一个需求 他人确认用例")
    reply = send_and_wait_reply(f"确认 {code}", "确认单不存在或已过期", sender=U2)
    check("他人确认被拒", "确认单不存在或已过期，请重新发起" in (reply.get("text") or ""),
          str(reply))
    check("原确认单仍 PENDING", (action_by_code(code) or {}).get("status") == "PENDING")
    check("需求仍不存在", len(requirement_by_keyword("他人确认用例")) == 0)
    # 清理：发起人取消，避免干扰后续裸「确认」的待确认单计数
    send_and_wait_reply(f"/cancel {code}", "已取消")


def scenario_5_cancel_and_expiry():
    print("\n[5] /cancel 与过期")
    code, _ = write_confirmation("建一个需求 取消用例")
    reply = send_and_wait_reply(f"/cancel {code}", "已取消")
    check("取消回执", f"已取消 [{code}]，操作未执行" in (reply.get("text") or ""), str(reply))
    check("确认单 CANCELLED", (action_by_code(code) or {}).get("status") == "CANCELLED")
    again = send_and_wait_reply(f"确认 {code}", "已取消")
    check("已取消再确认不执行", f"确认单 [{code}] 已取消" in (again.get("text") or ""), str(again))
    check("取消后需求不存在", len(requirement_by_keyword("取消用例")) == 0)

    code2, _ = write_confirmation("建一个需求 过期用例")
    print("  …等待 1 分钟 TTL + 5 秒级清扫（最长 120s）")
    action = wait_action_status(code2, "EXPIRED", timeout=120)
    check("过期后确认单 EXPIRED", action.get("status") == "EXPIRED", str(action))
    reply = send_and_wait_reply(f"确认 {code2}", "已过期")
    check("过期再确认不执行",
          f"确认单 [{code2}] 已过期，请重新发起" in (reply.get("text") or ""), str(reply))
    check("过期需求不存在", len(requirement_by_keyword("过期用例")) == 0)


def scenario_6_card_callback():
    print("\n[6] 卡片回调 → 执行 + updateCard")
    code, card = write_confirmation("建一个需求 卡片回调用例")
    action = action_by_code(code)
    check("确认卡按钮数 = 2", card.get("buttons") == 2, str(card))
    check("确认单可查到", bool(action), str(action))

    base = len(cards())
    api("POST", "/e2e/bot/card-action", {
        "channel": "fake",
        "externalChatId": CHAT,
        "senderId": U1,
        "messageId": "om-card-6",
        "eventId": f"cap76-card-evt-{int(time.time() * 1000)}",
        "value": {"devmind": "copilot_confirm", "id": action["id"]},
    })
    updated = wait_card(lambda c: c.get("kind") == "UPDATE"
                        and c.get("messageId") == "om-card-6", base=base, what="卡片原地更新")
    check("卡片回调执行成功且原地更新",
          updated.get("title") == f"执行成功 [{code}]", str(updated))
    check("确认单 SUCCEEDED", (action_by_code(code) or {}).get("status") == "SUCCEEDED")
    check("卡片回调用例需求已落库", len(requirement_by_keyword("卡片回调用例")) == 1)


def scenario_7_dangerous():
    print("\n[7] DANGEROUS：需求置 DONE → 裸确认被拒 → 完整短码执行")
    code, card = write_confirmation("把需求 REQ-1 标记为完成", expect_title="变更需求状态")
    check("危险确认卡 DANGER 且只有取消按钮",
          card.get("template") == "DANGER" and card.get("buttons") == 1, str(card))
    check("危险确认单 risk=DANGEROUS",
          (action_by_code(code) or {}).get("risk") == "DANGEROUS")

    reply = send_and_wait_reply("确认", "危险操作请回复")
    check("裸「确认」被拒",
          f"危险操作请回复：确认 {code}" in (reply.get("text") or ""), str(reply))
    check("裸「确认」未执行", (action_by_code(code) or {}).get("status") == "PENDING")

    reply = send_and_wait_reply(f"确认 {code}", "状态已变更为")
    check("完整短码执行", "需求 REQ-1 状态已变更为 DONE" in (reply.get("text") or ""), str(reply))
    _, detail = api("GET", f"/projects/{PID}/requirements/{REQ1_ID}", token=TOKEN)
    check("REQ-1 实际状态 DONE", (detail or {}).get("status") == "DONE", str(detail))


def scenario_9_admin_rbac():
    print("\n[9] 管理 API：非 ADMIN 403 / ADMIN 200")
    status, _ = api("GET", "/copilot/admin/tools", token=TOKEN)
    check("ADMIN 可读工具目录", status == 200)
    api("POST", "/auth/users", {"username": "cap76dev", "displayName": "CAP76 开发",
                                "password": "dev123456", "role": "DEVELOPER"}, token=TOKEN)
    dev_token = login("cap76dev", "dev123456")
    check("DEVELOPER 登录成功", bool(dev_token))
    status, _ = api("GET", "/copilot/admin/tools", token=dev_token, expect=403)
    check("DEVELOPER 访问管理 API 403", status == 403)
    status, _ = api("GET", "/copilot/admin/health", token=TOKEN)
    check("ADMIN 健康检查 200", status == 200)
    # FR-32：/copilot/health 登录用户可读（非 ADMIN）
    status, body = api("GET", "/copilot/health", token=dev_token)
    check("DEVELOPER 健康检查 200", status == 200 and isinstance(body, dict), f"{status} {body}")
    if isinstance(body, dict):
        check("健康检查含开关/工具数", "enabled" in body and int(body.get("tools") or 0) > 0, str(body))


def scenario_3_viewer_denied():
    print("\n[3] 越权：VIEWER 发起写 → 拒绝且无确认单")
    base = len(cards())
    send("建一个需求 越权测试")
    card = wait_card(lambda c: "当前角色无权调用工具" in (c.get("markdown") or "")
                     or "无权调用工具" in (c.get("markdown") or ""),
                     base=base, what="越权拒绝回答卡")
    check("回复含无权提示", "无权调用工具" in (card.get("markdown") or ""), str(card))
    page = admin_actions(actor="viewer", toolName="create_requirement", size=50)
    check("VIEWER 无确认单", (page.get("total") or 0) == 0, str(page))
    _, turns = api("GET", "/copilot/admin/turns?channel=fake&actor=viewer&keyword="
                   + urllib.parse.quote("越权测试"), token=TOKEN)
    rows = (turns or {}).get("items") or []
    check("越权轮次有审计", len(rows) >= 1, str(turns))
    if rows:
        _, detail = api("GET", f"/copilot/admin/turns/{rows[0]['id']}", token=TOKEN)
        rejected = [c for c in (detail or {}).get("toolCalls") or []
                    if c.get("status") == "REJECTED"]
        check("工具调用记录 REJECTED/PERMISSION_DENIED",
              bool(rejected) and "PERMISSION_DENIED" in (rejected[0].get("error") or ""),
              str(detail))
    check("越权需求不存在", len(requirement_by_keyword("越权测试")) == 0)


def scenario_8_kill_switch():
    print("\n[8] kill switch → 裸文本回落 CAP-75 问答")
    base_cards = len(cards())
    reply = send_and_wait_reply("随便聊两句，讲个笑话", "这是问答回答")
    check("回落 CAP-75 问答（流式整段拼接）",
          "这是问答回答，来自假模型。" in (reply.get("text") or ""), str(reply))
    check("未生成卡片", len(cards()) == base_cards,
          str([(c.get("kind"), c.get("title")) for c in cards()[base_cards:]]))


# ----------------------------------------------------------------------
# main
# ----------------------------------------------------------------------

def main():
    global TOKEN, PID, REQ1_ID
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    _force_rmtree(TMP)
    (TMP / "db").mkdir(parents=True, exist_ok=True)
    kill_port_strays()

    mock = start_mock()
    print(f"[mock] 假 OpenAI 已起（:{MOCK_PORT}）")
    app = None
    app_log = None
    try:
        app, app_log = start_app("A")
        TOKEN = login("admin")
        check("admin 登录", bool(TOKEN))
        if not TOKEN:
            sys.exit(1)

        # 基础数据：项目 + REQ-1 + 平台默认 CHAT 端点
        _, project = api("POST", "/projects", {"name": f"CAP76-E2E-{int(time.time())}",
                                               "path": str(ROOT).replace("\\", "/")}, token=TOKEN)
        PID = (project or {}).get("id")
        _, requirement = api("POST", f"/projects/{PID}/requirements",
                             {"title": "CAP76 只读验收需求"}, token=TOKEN)
        REQ1_ID = (requirement or {}).get("id")
        check("项目与 REQ-1 已建", bool(PID) and (requirement or {}).get("code") == "REQ-1",
              f"{PID} {requirement}")
        MOCK["requirementId"] = REQ1_ID
        _, endpoint = api("POST", "/model-endpoints",
                          {"kind": "CHAT", "name": f"CAP76-E2E-对话-{int(time.time())}",
                           "provider": "openai-compatible", "baseUrl": MOCK_BASE,
                           "apiKey": "sk-e2e-cap76", "model": "fake-chat",
                           "timeoutSeconds": 10}, token=TOKEN)
        api("PUT", f"/model-endpoints/{(endpoint or {}).get('id')}/default", token=TOKEN)
        check("平台默认 CHAT 端点已设", bool((endpoint or {}).get("id")))
        send_and_wait_reply(f"/bind {PID}", "已绑定项目")
        # 越权用例的用户要在 phase B 之前建好；ADMIN 才能建
        api("POST", "/auth/users", {"username": "viewer", "displayName": "CAP76 只读",
                                    "password": "viewer123", "role": "VIEWER"}, token=TOKEN)

        scenario_1_read()
        scenario_2_write()
        scenario_4_other_person_confirm()
        scenario_5_cancel_and_expiry()
        scenario_6_card_callback()
        scenario_7_dangerous()
        scenario_9_admin_rbac()

        # ---------- phase B：VIEWER ----------
        stop_app(app, app_log)
        app, app_log = start_app("B")
        TOKEN = login("admin")
        scenario_3_viewer_denied()

        # ---------- phase C：kill switch ----------
        stop_app(app, app_log)
        app, app_log = start_app("C")
        TOKEN = login("admin")
        scenario_8_kill_switch()
    finally:
        if app is not None and app_log is not None:
            stop_app(app, app_log)
        stop_mock(mock)
        print("\n清理完成：app 与 mock 均已停止")

    print(f"\n通过 {len(passed)} 项，失败 {len(failed)} 项")
    if failed:
        print("失败项：")
        for name in failed:
            print("  - " + name)
        sys.exit(1)


if __name__ == "__main__":
    main()
