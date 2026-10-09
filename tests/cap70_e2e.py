#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-70 服务端出口反向隧道 E2E（前置：mvn -q install -DskipTests 出 runner jar；app 起隔离实例）。

链路：真 runner jar（协议 v21，executor=fake——隧道帧不碰 claude）连隔离实例并自动起
/ws/agent-tunnel 隧道；本脚本直接打服务端内嵌 SOCKS5（127.0.0.1:18089）驱动 OPEN 帧，
runner 侧拨回本机 echo 服务，端到端验证规则→隧道→relay 全链。

验收覆盖（CAP-70 §6 / FR 编号）：
  [1]  FR-03 规则 CRUD：建/查/改/排序 + host 规范化（大小写/空白/端口剥离入库）
  [2]  FR-03 越权：非 ADMIN（DEVELOPER）GET/POST/PUT/DELETE 一律 403
  [3]  FR-01/08 隧道状态：/egress-rules/status 报节点 supportsTunnel/tunnelOnline
  [4]  FR-02 未命中规则 → SOCKS rep=2（not-allowed，白名单语义不放行未配目标）
  [5]  FR-07 命中但节点离线 → rep=4 快速失败（不挂起、不静默直连）
  [6]  FR-01/04 命中且在线 → rep=0，经隧道到本机 echo 服务双向 relay 全链（含二进制）
  [7]  删规则即失效：服务端路由快照同步重建，新 OPEN rep=2
       （runner 侧 allowedHosts 二次校验为单测覆盖，此端只验权威侧）
  [8]  FR-09 老 runner（裸 WS 握手 protocolVersion=17 上线）：status supportsTunnel=false，
       规则引用它 → SOCKS rep=4 快速失败

默认打本机 :8080；隔离实例用 E2E_BASE 覆盖（如 http://localhost:8090/api，起法见 tests/README.md）。
SOCKS 端口可用 E2E_SOCKS_PORT 覆盖（默认 18089，须与 app 的 devmind.egress.socks-port 一致）。
"""
import base64, json, os, shutil, socket, struct, subprocess, threading, time
import urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
SOCKS_PORT = int(os.environ.get("E2E_SOCKS_PORT", "18089"))
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS_DIR = TMP / "cap70-ws"
PROPS = TMP / "cap70-runner.properties"
MARK = f"e2e-cap70-{int(time.time())}"
ECHO_PORT = 18171
# 规则指向的 host：runner 与本脚本同机，拨 127.0.0.1 即回本机 echo
RULE_IP = "127.0.0.1"


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            payload = resp.read()
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(payload.decode() or "null")
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


# ---------------- 本机 echo 服务（runner 侧 OPEN 的拨号目标） ----------------
def start_echo(port):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port))
    srv.listen(8)

    def serve():
        while True:
            try:
                conn, _ = srv.accept()
            except OSError:
                return

            def relay(c):
                try:
                    while True:
                        data = c.recv(8192)
                        if not data:
                            break
                        c.sendall(data)
                except OSError:
                    pass
                finally:
                    try:
                        c.close()
                    except OSError:
                        pass

            threading.Thread(target=relay, args=(conn,), daemon=True).start()

    threading.Thread(target=serve, daemon=True).start()
    return srv


# ---------------- 最小 SOCKS5 客户端（无认证，domain 地址形态 = socks5h 语义） ----------------
def socks_connect(host, port, timeout=15):
    """返回 (rep, socket)；rep!=0 时 socket 已关闭。"""
    s = socket.create_connection(("127.0.0.1", SOCKS_PORT), timeout=timeout)
    s.sendall(b"\x05\x01\x00")
    resp = s.recv(2)
    assert resp == b"\x05\x00", f"SOCKS greeting 被拒: {resp!r}"
    hb = host.encode()
    s.sendall(b"\x05\x01\x00\x03" + bytes([len(hb)]) + hb + struct.pack("!H", port))
    head = b""
    while len(head) < 4:
        head += s.recv(4 - len(head))
    rep = head[1]
    if rep != 0:
        s.close()
        return rep, None
    # 跳过 BND.ADDR+BND.PORT（服务端固定 0.0.0.0:0 → ATYP=1 共 6 字节）
    rest = b""
    while len(rest) < 6:
        rest += s.recv(6 - len(rest))
    return rep, s


# ---------------- 裸 WS 老 runner（protocolVersion=17，只为触发版本门控，同 cap65 先例） ----------------
def ws_connect_v17(node_token):
    u = urllib.parse.urlparse(BASE)
    sock = socket.create_connection((u.hostname, u.port), timeout=10)
    key = base64.b64encode(os.urandom(16)).decode()
    handshake = (f"GET /ws/agent?token={urllib.parse.quote(node_token)} HTTP/1.1\r\n"
                 f"Host: {u.hostname}:{u.port}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                 f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n")
    sock.sendall(handshake.encode())
    resp = b""
    while b"\r\n\r\n" not in resp:
        resp += sock.recv(4096)
    assert b"101" in resp.split(b"\r\n")[0], f"WS 握手失败: {resp[:200]}"
    hello = json.dumps({"type": "hello", "os": "linux", "capabilities": "claude",
                        "version": "0.0.0-legacy", "protocolVersion": 17,
                        "activeSessions": []}).encode()
    mask = os.urandom(4)
    n = len(hello)
    head = struct.pack("!BB", 0x81, 0x80 | (n if n < 126 else 126))
    if n >= 126:
        head += struct.pack("!H", n)
    sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(hello)))
    return sock


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    echo = start_echo(ECHO_PORT)
    print(f"[0] 本机 echo 服务 :{ECHO_PORT} 已起")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    nid = node["id"]
    offline = req("POST", "/agent-nodes", {"name": MARK + "-offline"}, tok)
    legacy = req("POST", "/agent-nodes", {"name": MARK + "-legacy"}, tok)

    # ---- 起真 runner（v21 隧道自动连） ----
    shutil.rmtree(WS_DIR, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap70-runner-work').as_posix()}\nworkspaceRoot={WS_DIR.as_posix()}\n"
        f"maxConcurrent=1\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap70-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    print(f"[1] runner pid={runner.pid}（executor=fake，隧道 v21）")
    legacy_sock = dev_uid = None

    try:
        # ---- [3] 隧道状态：节点上线且隧道在线 ----
        def tunnel_up():
            try:
                st = req("GET", "/egress-rules/status", token=tok)
            except AssertionError:
                return None
            for row in st:
                if row.get("nodeId") == nid and row.get("supportsTunnel") and row.get("tunnelOnline"):
                    return row
            return None

        row = wait(tunnel_up, "节点隧道上线", timeout=90)
        assert row.get("protocolVersion", 0) >= 21, row
        print(f"[3] /egress-rules/status：节点 {nid} 隧道在线（协议 v{row['protocolVersion']}）")

        # ---- [1] 规则 CRUD ----
        rule = req("POST", "/egress-rules",
                   {"hostPattern": "  127.0.0.1:9999 ", "nodeId": nid, "remark": "cap70 e2e"}, tok)
        rid = rule["id"]
        # host 规范化：小写 + 空白/端口剥离入库
        assert rule["hostPattern"] == "127.0.0.1", rule
        assert rule["enabled"] is True and rule["tunnelOnline"] is True, rule
        lst = req("GET", "/egress-rules", token=tok)
        assert any(r["id"] == rid for r in lst)
        rule = req("PUT", f"/egress-rules/{rid}", {"hostPattern": "127.0.0.1", "nodeId": nid,
                                                    "sort": 5, "remark": "updated"}, tok)
        assert rule["sort"] == 5 and rule["remark"] == "updated", rule
        # 引用不存在节点 → 400
        req("POST", "/egress-rules", {"hostPattern": "x.local", "nodeId": 999999}, tok, expect=400)
        print(f"[1] 规则 CRUD OK（id={rid}，host 规范化入库，非法节点 400）")

        # ---- [6] 命中且在线：SOCKS → 隧道 → echo 双向 relay ----
        rep, s = socks_connect(RULE_IP, ECHO_PORT)
        assert rep == 0, f"SOCKS CONNECT 应成功，rep={rep}"
        payload = b"cap70-tunnel-e2e\x00\xffbinary"
        s.sendall(payload)
        got = b""
        while len(got) < len(payload):
            chunk = s.recv(8192)
            assert chunk, "隧道回包中断"
            got += chunk
        assert got == payload, f"echo 不一致: {got!r}"
        s.close()
        print("[6] 隧道端到端 relay OK（SOCKS → runner → echo 回环一致，含二进制）")

        # ---- [4] 未命中规则 → rep=2 ----
        rep, _ = socks_connect("no-such-rule.invalid", 443)
        assert rep == 2, f"未命中规则应 rep=2，实际 {rep}"
        print("[4] 未命中规则 → rep=2（not-allowed）OK")

        # ---- [5] 命中但节点离线 → rep=4 快速失败 ----
        req("POST", "/egress-rules",
            {"hostPattern": "offline.cap70.local", "nodeId": offline["node"]["id"]}, tok)
        t0 = time.time()
        rep, _ = socks_connect("offline.cap70.local", 443)
        assert rep == 4, f"离线节点应 rep=4，实际 {rep}"
        assert time.time() - t0 < 10, "命中不可用必须快速失败（不挂起）"
        print("[5] 命中但节点离线 → rep=4 快速失败 OK")

        # ---- [7] 删规则即失效：服务端路由快照重建，新 OPEN rep=2 ----
        req("DELETE", f"/egress-rules/{rid}", token=tok)
        wait(lambda: socks_connect(RULE_IP, ECHO_PORT, timeout=5)[0] == 2,
             "规则删除后 OPEN 被拒", timeout=30)
        print("[7] 删规则 → 路由快照重建生效（rep=2）OK")

        # ---- [8] 老 runner（v17 上线）：supportsTunnel=false，规则引用 → rep=4 ----
        legacy_sock = ws_connect_v17(legacy["token"])
        legacy_id = legacy["node"]["id"]
        wait(lambda: next((r for r in req("GET", "/egress-rules/status", token=tok)
                           if r.get("nodeId") == legacy_id and r.get("nodeStatus") == "ONLINE"
                           and r.get("protocolVersion") == 17), None),
             "v17 节点上线", 30)
        st = req("GET", "/egress-rules/status", token=tok)
        legacy_row = next(r for r in st if r.get("nodeId") == legacy_id)
        assert legacy_row.get("supportsTunnel") is False and legacy_row.get("tunnelOnline") is False, legacy_row
        req("POST", "/egress-rules",
            {"hostPattern": "legacy.cap70.local", "nodeId": legacy_id}, tok)
        rep, _ = socks_connect("legacy.cap70.local", 443)
        assert rep == 4, f"老 runner 规则应 rep=4，实际 {rep}"
        print("[8] 协议 v17 节点：supportsTunnel=false + 引用即 rep=4 OK")

        # ---- [2] 非 ADMIN 一律 403（含 GET：规则表即内网出口白名单） ----
        dev = req("POST", "/auth/users",
                  {"username": MARK, "displayName": "cap70 dev", "password": "dev12345",
                   "role": "DEVELOPER"}, tok)
        dev_uid = dev.get("id") or dev.get("user", {}).get("id")
        dev_login = req("POST", "/auth/login", {"username": MARK, "password": "dev12345"})
        dev_tok = dev_login.get("token") or dev_login.get("accessToken")
        for m, p, b in [("GET", "/egress-rules", None), ("GET", "/egress-rules/status", None),
                        ("POST", "/egress-rules", {"hostPattern": "x.local", "nodeId": nid}),
                        ("PUT", "/egress-rules/1", {"hostPattern": "x.local", "nodeId": nid}),
                        ("DELETE", "/egress-rules/1", None)]:
            req(m, p, b, dev_tok, expect=403)
        print("[2] DEVELOPER 全方法 403 OK")

        print("\nCAP-70 E2E 全部通过 ✔")
    finally:
        try:
            if legacy_sock:
                legacy_sock.close()
            if dev_uid:
                req("PUT", f"/auth/users/{dev_uid}", {"status": "DISABLED"}, tok)
            for n in (node, offline["node"], legacy["node"]):
                req("DELETE", f"/agent-nodes/{n['id']}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
        kill_tree(runner.pid)
        echo.close()


if __name__ == "__main__":
    main()
