# -*- coding: utf-8 -*-
"""CAP-78 飞书对话式需求开发全流程 E2E（M1 copilot 工具主路径）。

组合两套既有 harness：
- cap76：自起隔离 app（e2e profile 独立 H2）+ 内嵌假 OpenAI（原生 tools/tool_calls）
  + fake bot /e2e/bot/message 注入 + 确认单链路；
- cap52：file:// origin git 仓 + CLONE 项目 + 真 runner（executor=fake），
  [flow:plan]/[flow:dev] 会话真拉起、真 worktree、真 merge+push。

运行前提：
  1. `mvn -q install -DskipTests`（app 依赖与 devmind-agent-runner.jar）；
  2. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium jdk-21.0.12.101。

验收（CAP-78 §6 E2E 场景 1/2/3/4/6；M2 斜杠指令场景 5 不在本脚本）：
  [1] 对话式「给 REQ-1 开启 AI 规划」→ WRITE 确认单（预览点名 REQ-1、首规划无「整体重跑」）
      → 他人确认被拒（场景 6c）→ 本人文本确认 → [flow:plan] 会话真拉起
      （sessions 行 requirementId 正确、taskSpec 首行 [flow:plan]、需求 → ANALYZING）；
  [2] 规划会话预写三份产出 → REST finish → 分析/方案/WI 登记 + [flow:dev] 开发会话自动启动
      （CAP-52 链路回归：飞书入口不破坏既有流程）；
  [3] 「结束 REQ-1 的会话」→ finish_session（REQ 编号解析出开发会话，预览点名 sid+RUNNING）
      → DONE；「继续刚才的会话」→ resume_session → RUNNING；
  [4] 「收口 REQ-1」→ DANGEROUS 确认单（卡片无确认按钮、预览含危险操作/REQ-1/基线分支）
      → 裸「确认」被拒 → 完整短码 → 需求全部会话 workspaceState=FINALIZED
      （真 runner 真 git merge --ff + push file:// origin）；
  [6] 门禁：phase B VIEWER 发起写 → 拒绝且无确认单；
      phase C（allow-default-actor-writes=false）未绑定身份写 → /bindme 引导且无确认单。

阶段（同一套 H2 顺序重启，runner 全程在线自动重连）：
  A. default-actor=admin  + allow-default-actor-writes=true  → 场景 1~4、6c
  B. default-actor=viewer + allow-default-actor-writes=true  → 场景 6b
  C. default-actor=admin  + allow-default-actor-writes=false → 场景 6a

跑完清理：app / runner / mock 全杀，不留残留。
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
PORT = int(os.environ.get("E2E_PORT", "18100"))
MOCK_PORT = int(os.environ.get("CAP78_MOCK_PORT", "18200"))
BASE = f"http://localhost:{PORT}/api"
MOCK_BASE = f"http://127.0.0.1:{MOCK_PORT}/v1"
TMP = ROOT / "tmp" / "cap78-e2e"
WS = TMP / "runner-ws"           # runner workspaceRoot（会话工作区落这里）
ORIGIN = TMP / "origin"          # file:// git 仓（项目远端）
PROPS = TMP / "runner.properties"
RUNNER_JAR = ROOT / "devmind-agent-runner" / "target" / "devmind-agent-runner.jar"

# JDK 21（mvn、app、runner 都要）
JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe")

CHAT = "c1"
U1 = "u1"     # 发起人（phase A 未绑定但 allow-default-actor-writes → 按 admin 执行）
U2 = "u2"     # 他人（确认必须拒）
U3 = "u3"     # 未绑定身份（phase C）

passed, failed = [], []
TOKEN = None
PID = None          # 项目 id
REQ1_ID = None      # REQ-1 内部 id
PLAN_SID = None     # [flow:plan] 会话 id
DEV_SID = None      # [flow:dev] 会话 id
_event_seq = 0


# ----------------------------------------------------------------------
# 假 OpenAI 服务（tools/tool_calls + SSE；与 cap76 同款，决策表换成 CAP-78 话术）
# ----------------------------------------------------------------------

MOCK = {"requests": []}


def _mock_decision(messages):
    """按最后一条消息决定：tool 结果 → 收口；否则按用户文本选工具或直接回答。"""
    last = messages[-1] if messages else {}
    if last.get("role") == "tool":
        return ("answer", "工具执行结果：\n" + str(last.get("content") or ""))
    text = str(last.get("content") or "")
    if "开启 AI 规划" in text or "开启AI规划" in text:
        return ("tool", "start_requirement_plan", {"requirementId": "REQ-1"})
    if "结束 REQ-1 的会话" in text:
        return ("tool", "finish_session", {"sessionId": "REQ-1"})
    if "继续刚才的会话" in text:
        return ("tool", "resume_session", {"sessionId": "REQ-1"})
    if "收口" in text:
        return ("tool", "finalize_requirement_workspace", {"requirementId": "REQ-1"})
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
                            "function": {"name": payload,
                                         "arguments": json.dumps(extra, ensure_ascii=False)},
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


def wait(cond, what, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


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
# 起停隔离 app / runner
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
        f'--devmind.copilot.max-turns-per-minute=1000'
    )
    if phase == "B":
        return common + (' --devmind.copilot.enabled=true'
                         ' --devmind.bot.default-actor=viewer'
                         ' --devmind.copilot.allow-default-actor-writes=true')
    if phase == "C":
        return common + (' --devmind.copilot.enabled=true'
                         ' --devmind.bot.default-actor=admin'
                         ' --devmind.copilot.allow-default-actor-writes=false')
    return common + (' --devmind.copilot.enabled=true'
                     ' --devmind.bot.default-actor=admin'
                     ' --devmind.copilot.allow-default-actor-writes=true')


def start_app(phase):
    log_path = TMP / f"app-{phase.lower()}.log"
    log = open(log_path, "w", encoding="utf-8")
    env = dict(os.environ)
    env["JAVA_HOME"] = JAVA_HOME
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


def start_runner():
    if not RUNNER_JAR.exists():
        raise AssertionError(f"runner jar 不存在（先 mvn -q install -DskipTests）: {RUNNER_JAR}")
    issued = api("POST", "/agent-nodes", {"name": f"cap78-e2e-{int(time.time())}"})[1]
    node, node_token = issued["node"], issued["token"]
    api("POST", f"/agent-nodes/{node['id']}/default")
    PROPS.write_text(
        f"serverUrl=ws://localhost:{PORT}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        # 本机常驻 runner 服务占着默认 git 代理端口 18377，E2E 实例关掉（cap74 同款）
        f"gitProxyEnabled=false\n"
        f"workDir={(TMP / 'runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner_log = open(TMP / "runner.log", "w", encoding="utf-8")
    runner = subprocess.Popen([JAVA, "-jar", str(RUNNER_JAR), str(PROPS)],
                              stdout=runner_log, stderr=subprocess.STDOUT)
    wait(lambda: next((n for n in api("GET", "/agent-nodes")[1]
                       if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
         "节点上线", 40)
    print(f"[runner] 已上线（node={node['id']}，executor=fake）")
    return runner, runner_log, node


def stop_runner(runner, runner_log):
    kill_tree(runner.pid)
    time.sleep(1)
    runner_log.close()


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
        "eventId": f"cap78-evt-{_event_seq}",
    })


def replies(chat=CHAT):
    _, data = api("GET", f"/e2e/bot/replies?chatId={urllib.parse.quote(chat)}")
    return data or []


def cards():
    _, data = api("GET", "/e2e/bot/cards")
    return data or []


def wait_reply(contains, base=0, chat=CHAT, timeout=90):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for r in replies(chat)[base:]:
            if contains in (r.get("text") or ""):
                return r
        time.sleep(0.4)
    raise AssertionError(f"{timeout}s 内未等到回复包含 {contains!r}；最近回复="
                         + repr([r.get("text") for r in replies(chat)[-6:]]))


def wait_card(predicate, base=0, timeout=90, what="卡片"):
    deadline = time.time() + timeout
    while time.time() < deadline:
        for c in cards()[base:]:
            if predicate(c):
                return c
        time.sleep(0.4)
    raise AssertionError(f"{timeout}s 内未等到{what}；最近卡片="
                         + repr([(c.get("kind"), c.get("title")) for c in cards()[-8:]]))


def send_and_wait_reply(text, contains, sender=U1, chat=CHAT, timeout=90):
    base = len(replies(chat))
    send(text, sender=sender, chat=chat)
    return wait_reply(contains, base=base, chat=chat, timeout=timeout)


def write_confirmation(text, expect_title, sender=U1, chat=CHAT):
    """发一条写请求，等到确认卡，返回 (code, card)。"""
    base = len(cards())
    send(text, sender=sender, chat=chat)
    card = wait_card(lambda c: (c.get("title") or "").startswith("待确认")
                     and expect_title in (c.get("title") or ""), base=base, what="确认卡")
    m = re.search(r"待确认 \[([A-Za-z0-9-]+)\]", card["title"])
    if not m:
        raise AssertionError(f"确认卡标题里没有短码: {card['title']}")
    return m.group(1), card


def admin_actions(**params):
    query = "&".join(f"{k}={urllib.parse.quote(str(v))}" for k, v in params.items())
    _, data = api("GET", "/copilot/admin/actions?" + query)
    return data or {"items": [], "total": 0}


def action_by_code(code):
    page = admin_actions(keyword=code, size=50)
    for item in page.get("items") or []:
        if item.get("shortCode") == code:
            return item
    return None


def login(username, password="admin123"):
    _, body = api("POST", "/auth/login", {"username": username, "password": password}, token=None)
    return (body or {}).get("token") or (body or {}).get("accessToken")


# ----------------------------------------------------------------------
# 流程辅助（cap52 同款）
# ----------------------------------------------------------------------

def overview():
    return api("GET", f"/projects/{PID}/requirements/{REQ1_ID}/overview")[1]


def sessions_of(task_spec_marker):
    return [s for s in overview()["sessions"]
            if (s.get("taskSpec") or "").startswith(task_spec_marker)]


def session_dir(sid, rid, timeout=60):
    """定位会话工作区：<runnerWS>/<proj>/<owner>/worktrees/req-<rid>（`**` 必须带上，
    工作区根到工作树之间隔着 <项目>/<归属用户> 两层）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for pattern in (f"**/worktrees/req-{rid}", f"**/worktrees/sid-{sid}"):
            hit = next(iter(WS.glob(pattern)), None)
            if hit:
                return hit
        time.sleep(1)
    raise AssertionError(f"未找到会话工作区: session={sid} req={rid}")


def write_output(sid, rid, name, content):
    """模拟 agent 写产出：把 .devmind/output/<name> 预写进会话工作区。"""
    out = session_dir(sid, rid) / ".devmind" / "output"
    out.mkdir(parents=True, exist_ok=True)
    (out / name).write_text(content, encoding="utf-8")


def finish_via_rest(sid, what):
    api("POST", f"/sessions/{sid}/finish")
    wait(lambda: api("GET", f"/sessions/{sid}")[1].get("status") == "DONE" or None,
         f"{what} DONE", 90)


def session_view(sid):
    return api("GET", f"/sessions/{sid}")[1]


# ----------------------------------------------------------------------
# 场景
# ----------------------------------------------------------------------

def scenario_1_plan():
    """场景 1（+6c）：对话式开启 AI 规划全链。"""
    global PLAN_SID
    print("\n[1] 对话式「给 REQ-1 开启 AI 规划」→ 确认单 → 文本确认 → [flow:plan] 会话拉起")
    code, card = write_confirmation("给 REQ-1 开启 AI 规划", "开启 AI 规划")
    check("规划确认卡 WARNING + 两按钮",
          card.get("template") == "WARNING" and card.get("buttons") == 2, str(card))
    md = card.get("markdown") or ""
    check("预览点名 REQ-1", "REQ-1" in md, md[:300])
    check("首规划预览无「整体重跑」", "整体重跑" not in md, md[:300])

    # 场景 6c：他人确认被拒，原单仍 PENDING
    reply = send_and_wait_reply(f"确认 {code}", "确认单不存在或已过期", sender=U2)
    check("他人确认被拒", "确认单不存在或已过期，请重新发起" in (reply.get("text") or ""),
          str(reply))
    action = action_by_code(code)
    check("原确认单仍 PENDING", bool(action) and action.get("status") == "PENDING", str(action))

    # 本人文本确认 → 真起规划会话
    send_and_wait_reply(f"确认 {code}", "启动 AI 规划会话")
    check("确认单 SUCCEEDED", (action_by_code(code) or {}).get("status") == "SUCCEEDED")

    plan = wait(lambda: sessions_of("[flow:plan]") or None, "[flow:plan] 会话出现", 60)
    PLAN_SID = plan[0]["id"]
    detail = wait(lambda: session_view(PLAN_SID)
                  if session_view(PLAN_SID).get("status") == "RUNNING" else None,
                  "规划会话 RUNNING", 60)
    check("sessions 行 requirementId 正确", detail.get("requirementId") == REQ1_ID, str(detail))
    check("taskSpec 首行 [flow:plan]",
          (detail.get("taskSpec") or "").startswith("[flow:plan]"),
          (detail.get("taskSpec") or "")[:120])
    check("规划会话无 workItemId（直挂需求）", not detail.get("workItemId"), str(detail))
    req_view = api("GET", f"/projects/{PID}/requirements/{REQ1_ID}")[1]
    check("需求 → ANALYZING", req_view.get("status") == "ANALYZING", str(req_view.get("status")))


def scenario_2_plan_outputs():
    """场景 2：规划会话三份产出 → 登记 + 自动开发会话（CAP-52 链路回归）。"""
    global DEV_SID
    print("\n[2] 规划会话三份产出 → 分析/方案/WI 登记 + [flow:dev] 自动启动")
    write_output(PLAN_SID, REQ1_ID, "analysis.md",
                 "# 需求分析\n\n## 影响面\n告警阈值校验（CAP78-E2E-MARK）\n")
    write_output(PLAN_SID, REQ1_ID, "design.md",
                 "# 方案\n\n## 总体思路\n阈值表 + 校验注解（CAP78-DESIGN-MARK）\n")
    write_output(PLAN_SID, REQ1_ID, "wi-plan.json", json.dumps([
        {"type": "DEVELOPMENT", "title": "后端阈值校验", "spec": "改 alert_rule 表与接口",
         "dependsOn": []},
        {"type": "DEVELOPMENT", "title": "前端阈值表单", "spec": "配置页表单", "dependsOn": [0]},
    ], ensure_ascii=False))
    finish_via_rest(PLAN_SID, "规划会话")

    wait(lambda: next((d for d in overview()["docs"] if d["kind"] == "analysis"), None),
         "分析文档登记", 30)
    design = wait(lambda: next((d for d in api(
        "GET", f"/projects/{PID}/requirements/{REQ1_ID}/designs")[1]
        if d.get("docId")), None), "方案登记", 30)
    check("方案内容落文档库", "CAP78-DESIGN-MARK" in api(
        "GET", f"/documents/{design['docId']}")[1]["contentMd"], "")
    wis = wait(lambda: overview()["workItems"] or None, "固化工作单元", 30)
    check("WI 清单固化 2 条", [w["title"] for w in wis] == ["后端阈值校验", "前端阈值表单"],
          str(wis))

    dev = wait(lambda: sessions_of("[flow:dev]") or None, "自动开发会话", 60)
    DEV_SID = dev[0]["id"]
    dev_detail = wait(lambda: session_view(DEV_SID)
                      if session_view(DEV_SID).get("status") == "RUNNING" else None,
                      "开发会话 RUNNING", 60)
    check("开发会话 taskSpec 带整份清单",
          "工作单元清单（共 2 条" in (dev_detail.get("taskSpec") or "")
          and "后端阈值校验" in dev_detail.get("taskSpec"),
          (dev_detail.get("taskSpec") or "")[:300])
    check("开发会话复用需求工作树",
          session_dir(DEV_SID, REQ1_ID) == session_dir(PLAN_SID, REQ1_ID), "")


def scenario_3_finish_resume():
    """场景 3：「结束 REQ-1 的会话」→ DONE；「继续刚才的会话」→ RUNNING。"""
    print("\n[3] 对话式结束/继续会话（REQ 编号解析 → 开发会话）")
    code, card = write_confirmation("结束 REQ-1 的会话", "优雅结束会话")
    md = card.get("markdown") or ""
    check("finish 预览点名目标会话 sid 与状态",
          DEV_SID[:8] in md and "RUNNING" in md, md[:400])
    send_and_wait_reply(f"确认 {code}", "优雅结束指令")
    wait(lambda: session_view(DEV_SID).get("status") == "DONE" or None, "开发会话 DONE", 90)
    check("finish 后开发会话 DONE", True, "")

    code2, card2 = write_confirmation("继续刚才的会话", "继续会话")
    md2 = card2.get("markdown") or ""
    check("resume 预览点名目标会话", DEV_SID[:8] in md2, md2[:400])
    send_and_wait_reply(f"确认 {code2}", "已继续会话")
    wait(lambda: session_view(DEV_SID).get("status") == "RUNNING" or None,
         "开发会话 resume 后 RUNNING", 90)
    check("resume 后开发会话 RUNNING", True, "")

    # 场景 4 前置：收口要求目标会话非活跃（「会话仍在运行中，请先结束会话再收口」）
    finish_via_rest(DEV_SID, "开发会话（收口前置）")


def scenario_4_finalize():
    """场景 4：「收口 REQ-1」→ DANGEROUS 无卡片按钮 → 文本短码 → FINALIZED。"""
    print("\n[4] 对话式收口（DANGEROUS：卡片无确认按钮，文本短码执行）")
    code, card = write_confirmation("收口 REQ-1", "收口需求工作区")
    check("危险确认卡 DANGER 且只有取消按钮",
          card.get("template") == "DANGER" and card.get("buttons") == 1, str(card))
    md = card.get("markdown") or ""
    check("预览含危险操作/REQ-1/基线分支",
          "危险操作" in md and "REQ-1" in md and "基线分支：main" in md, md[:500])
    check("确认单 risk=DANGEROUS", (action_by_code(code) or {}).get("risk") == "DANGEROUS")

    reply = send_and_wait_reply("确认", "危险操作请回复")
    check("裸「确认」被拒", f"危险操作请回复：确认 {code}" in (reply.get("text") or ""),
          str(reply))
    check("裸「确认」未执行", (action_by_code(code) or {}).get("status") == "PENDING")

    send_and_wait_reply(f"确认 {code}", "工作区已收口")
    check("确认单 SUCCEEDED", (action_by_code(code) or {}).get("status") == "SUCCEEDED")
    wait(lambda: session_view(DEV_SID).get("workspaceState") == "FINALIZED" or None,
         "开发会话 workspaceState=FINALIZED", 30)
    check("开发会话 workspaceState=FINALIZED", True, "")
    plan_view = session_view(PLAN_SID)
    check("同需求规划会话一并 FINALIZED",
          plan_view.get("workspaceState") == "FINALIZED", str(plan_view.get("workspaceState")))


def scenario_6b_viewer_denied():
    """场景 6b：VIEWER 发起写 → 拒绝且无确认单（phase B）。"""
    print("\n[6b] VIEWER 发起写 → 拒绝且无确认单")
    base = len(cards())
    send("给 REQ-1 开启 AI 规划")
    card = wait_card(lambda c: "无权调用工具" in (c.get("markdown") or ""),
                     base=base, what="越权拒绝回答卡")
    check("回复含无权提示", "无权调用工具" in (card.get("markdown") or ""), str(card))
    page = admin_actions(actor="viewer", toolName="start_requirement_plan", size=50)
    check("VIEWER 无确认单", (page.get("total") or 0) == 0, str(page))


def scenario_6a_unbound_denied():
    """场景 6a：未绑定身份写工具被拒（phase C，allow-default-actor-writes=false）。"""
    print("\n[6a] 未绑定身份发起写 → /bindme 引导且无确认单")
    before = [a for a in (admin_actions(toolName="start_requirement_plan", size=50)
                          .get("items") or []) if a.get("status") == "PENDING"]
    base = len(replies())
    send("给 REQ-1 开启 AI 规划", sender=U3)
    reply = wait_reply("写操作需要先绑定个人身份", base=base)
    check("回复含 /bindme 引导", "/bindme" in (reply.get("text") or ""), str(reply))
    after = [a for a in (admin_actions(toolName="start_requirement_plan", size=50)
                         .get("items") or []) if a.get("status") == "PENDING"]
    check("未产生新确认单", len(after) == len(before), f"before={before} after={after}")


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
    runner = None
    runner_log = None
    node = None
    try:
        # ---- 基础数据：file:// origin + CLONE 项目 + REQ-1 ----
        env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
                   GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
        ORIGIN.mkdir(parents=True)
        subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
        (ORIGIN / "README.md").write_text("# cap78 e2e\n", encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
        subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True,
                       capture_output=True, env=env)

        app, app_log = start_app("A")
        TOKEN = login("admin")
        check("admin 登录", bool(TOKEN))
        if not TOKEN:
            sys.exit(1)

        proj = api("POST", "/projects", {
            "name": f"CAP78-E2E-{int(time.time())}", "sourceType": "CLONE",
            "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
        })[1]
        PID = proj["id"]
        wait(lambda: api("GET", f"/projects/{PID}")[1].get("cloneStatus") == "READY" or None,
             "项目克隆 READY", 90)
        reqm = api("POST", f"/projects/{PID}/requirements",
                   {"title": "CAP78 对话式流程验收", "description": "告警阈值可配置"})[1]
        REQ1_ID = reqm["id"]
        check("项目与 REQ-1 已建", bool(PID) and reqm.get("code") == "REQ-1", f"{PID} {reqm}")

        _, endpoint = api("POST", "/model-endpoints",
                          {"kind": "CHAT", "name": f"CAP78-E2E-对话-{int(time.time())}",
                           "provider": "openai-compatible", "baseUrl": MOCK_BASE,
                           "apiKey": "sk-e2e-cap78", "model": "fake-chat",
                           "timeoutSeconds": 10})
        api("PUT", f"/model-endpoints/{(endpoint or {}).get('id')}/default")
        check("平台默认 CHAT 端点已设", bool((endpoint or {}).get("id")))
        send_and_wait_reply(f"/bind {PID}", "已绑定项目")
        # phase B 的 default-actor 用户；ADMIN 才能建
        api("POST", "/auth/users", {"username": "viewer", "displayName": "CAP78 只读",
                                    "password": "viewer123", "role": "VIEWER"})

        runner, runner_log, node = start_runner()

        scenario_1_plan()
        scenario_2_plan_outputs()
        scenario_3_finish_resume()
        scenario_4_finalize()

        # ---------- phase B：VIEWER ----------
        stop_app(app, app_log)
        app, app_log = start_app("B")
        TOKEN = login("admin")
        scenario_6b_viewer_denied()

        # ---------- phase C：未绑定身份门禁 ----------
        stop_app(app, app_log)
        app, app_log = start_app("C")
        TOKEN = login("admin")
        scenario_6a_unbound_denied()

        # 业务数据清理（runner 节点与项目；app 还开着时做）
        try:
            api("POST", f"/agent-nodes/{node['id']}/unset-default")
            api("DELETE", f"/agent-nodes/{node['id']}")
            api("DELETE", f"/projects/{PID}")
        except Exception as ex:
            print(f"[cleanup-data] {ex}")
    finally:
        if app is not None and app_log is not None:
            stop_app(app, app_log)
        if runner is not None and runner_log is not None:
            stop_runner(runner, runner_log)
        stop_mock(mock)
        print("\n清理完成：app / runner / mock 均已停止")

    print(f"\n通过 {len(passed)} 项，失败 {len(failed)} 项")
    if failed:
        print("失败项：")
        for name in failed:
            print("  - " + name)
        sys.exit(1)


if __name__ == "__main__":
    main()
