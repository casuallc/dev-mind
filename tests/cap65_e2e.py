#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-65 节点文件浏览器 E2E（前置：mvn -q install -DskipTests 出 runner jar；app 起隔离实例）。

链路：真 runner jar（协议 v18，executor=fake 即可——文件帧不碰 claude）连隔离实例，
REST /api/agent-nodes/{id}/files/** 全操作打穿 WS file 帧与 HTTP 中转。

验收覆盖（CAP-65 §6）：
  [4]  list 一层（目录优先、跳 .git） / 中文 write→read 回读一致
  [5]  rename 改名 / 冲突与非法名 409
  [6]  二进制 read 409 / >512KB write 400 / 逃逸（..、绝对路径、白名单外 root）409
  [7]  delete 文件、空目录、非空目录无 recursive 409 → recursive=true 连子树删
  [8]  5MB 二进制 upload→download sha256 对账 + Content-Disposition UTF-8 文件名
  [9]  64MB 上传进行中 list 照常响应（中转不阻塞 WS 小帧）
  [10] >100MB 上传 409（前端同款上限服务端终判）
  [11] 白名单清空 → 409；恢复后可用
  [12] 老 runner（裸 WS 握手 + hello protocolVersion=17）→ 409 指引升级 v18
  [13] 离线节点 → 409 不在线
  [14] 非 ADMIN（DEVELOPER）GET/POST 一律 403

默认打本机 :8080；隔离实例用 E2E_BASE 覆盖（如 http://localhost:8090/api，起法见 tests/README.md）。
"""
import base64, hashlib, http.client, json, os, shutil, socket, struct, subprocess, sys, threading, time
import urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
FIX = TMP / "cap65-files"          # 白名单 fixture 根（runner 与本脚本同机，直接铺文件）
ROOT_A = FIX / "root a"            # 带空格
ROOT_B = FIX / "root-b"
WS_DIR = TMP / "cap65-ws"
PROPS = TMP / "cap65-runner.properties"
MARK = f"e2e-cap65-{int(time.time())}"
CAP_TEXT = 512 * 1024
CAP_TRANSFER = 100 * 1024 * 1024


def req(method, path, body=None, token=None, expect=200, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            payload = resp.read()
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            if raw:
                return payload, resp.headers
            return json.loads(payload.decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return (e.read(), e.headers) if raw else json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def multipart_post(path, file_path, send_name, token, expect=200, payload_bytes=None):
    """流式 multipart 上传（大文件不进内存）；payload_bytes 给定时替代文件内容。"""
    boundary = "----cap65e2eboundary"
    pre = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
           f"filename=\"{send_name}\"\r\nContent-Type: application/octet-stream\r\n\r\n").encode()
    tail = f"\r\n--{boundary}--\r\n".encode()
    size = len(payload_bytes) if payload_bytes is not None else os.path.getsize(file_path)
    total = len(pre) + size + len(tail)
    u = urllib.parse.urlparse(BASE + path)
    conn = http.client.HTTPConnection(u.hostname, u.port, timeout=300)
    conn.putrequest("POST", u.path + "?" + u.query)
    conn.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
    conn.putheader("Content-Length", str(total))
    conn.putheader("Authorization", "Bearer " + token)
    conn.endheaders()
    conn.send(pre)
    if payload_bytes is not None:
        conn.send(payload_bytes)
    else:
        with open(file_path, "rb") as fh:
            while True:
                chunk = fh.read(256 * 1024)
                if not chunk:
                    break
                conn.send(chunk)
    conn.send(tail)
    resp = conn.getresponse()
    body = resp.read()
    assert resp.status == expect, f"POST {path} -> {resp.status}, expect {expect}: {body[:300]}"
    conn.close()
    return json.loads(body.decode() or "null")


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


def files_qs(root, path=""):
    return f"root={urllib.parse.quote(root)}&path={urllib.parse.quote(path)}"


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


# ---------------- 裸 WS 老 runner（protocolVersion=17，只为触发版本门控） ----------------
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

    # ---- fixture：两个白名单根（root a 带空格），预铺目录/文件/.git/二进制 ----
    shutil.rmtree(FIX, ignore_errors=True)
    (ROOT_A / "子 目录").mkdir(parents=True)
    (ROOT_A / ".git").mkdir()
    (ROOT_A / "zdir").mkdir()
    (ROOT_A / "说明.txt").write_text("初始内容", encoding="utf-8")
    (ROOT_A / "blob.bin").write_bytes(b"\x00\x01\x02\x00binary")
    (ROOT_A / "非空目录" / "inner").mkdir(parents=True)
    (ROOT_A / "非空目录" / "inner" / "叶.txt").write_text("leaf", encoding="utf-8")
    ROOT_B.mkdir(parents=True)
    root_a = ROOT_A.as_posix()
    root_b = ROOT_B.as_posix()

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    nid = node["id"]
    req("PUT", f"/agent-nodes/{nid}", {"fileRoots": [root_a, root_b]}, tok)
    offline = req("POST", "/agent-nodes", {"name": MARK + "-offline"}, tok)
    legacy = req("POST", "/agent-nodes", {"name": MARK + "-legacy"}, tok)
    req("PUT", f"/agent-nodes/{legacy['node']['id']}", {"fileRoots": [root_a]}, tok)

    shutil.rmtree(WS_DIR, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap65-runner-work').as_posix()}\nworkspaceRoot={WS_DIR.as_posix()}\n"
        f"maxConcurrent=1\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap65-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    dev_uid = legacy_sock = None
    try:
        view = wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                                  if n["id"] == nid and n["status"] == "ONLINE"), None),
                    "节点上线", 40)
        print(f"[1] runner 上线（protocolVersion={view.get('protocolVersion')}）")
        assert view.get("protocolVersion") == 18, f"hello 应上报协议 v18: {view.get('protocolVersion')}"

        # [4] list 一层：目录优先、跳过 .git
        lst = req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a)}", token=tok)
        names = [e["name"] for e in lst["entries"]]
        assert ".git" not in names, f".git 应被跳过: {names}"
        dirs = [e["name"] for e in lst["entries"] if e["dir"]]
        assert names[:len(dirs)] == dirs, f"目录应排前: {names}"
        assert "子 目录" in dirs and "说明.txt" in names, names
        e_txt = next(e for e in lst["entries"] if e["name"] == "说明.txt")
        assert e_txt["size"] > 0 and e_txt["mtime"], f"条目应带 size/mtime: {e_txt}"
        print(f"[4a] list OK（{len(names)} 项，目录优先，跳过 .git）")

        # [4] 中文 write → read 回读一致（写进带空格的子目录）
        text = "第一行 中文\n第二行 🎯 emoji\n"
        req("PUT", f"/agent-nodes/{nid}/files/content",
            {"root": root_a, "path": "子 目录/说明 文件.txt", "content": text}, tok)
        back = req("GET", f"/agent-nodes/{nid}/files/read?{files_qs(root_a, '子 目录/说明 文件.txt')}", token=tok)
        assert back["content"] == text, f"回读不一致: {back['content']!r}"
        assert (ROOT_A / "子 目录" / "说明 文件.txt").read_text(encoding="utf-8") == text
        print("[4b] 中文 write→read 回读一致 OK")

        # [5] rename：正常 / 目标已存在 409 / 非法名 409 / 根目录 409
        req("POST", f"/agent-nodes/{nid}/files/rename",
            {"root": root_a, "path": "说明.txt", "newName": "改名 后.md"}, tok)
        assert not (ROOT_A / "说明.txt").exists() and (ROOT_A / "改名 后.md").exists()
        r = req("POST", f"/agent-nodes/{nid}/files/rename",
                {"root": root_a, "path": "blob.bin", "newName": "改名 后.md"}, tok, expect=409)
        assert "已存在" in json.dumps(r, ensure_ascii=False), r
        req("POST", f"/agent-nodes/{nid}/files/rename",
            {"root": root_a, "path": "blob.bin", "newName": "a/b"}, tok, expect=409)
        req("POST", f"/agent-nodes/{nid}/files/rename",
            {"root": root_a, "path": "", "newName": "x"}, tok, expect=409)
        print("[5] rename OK（改名/冲突/非法名/根目录拒绝）")

        # [6] 二进制 read 409 / >512KB write 400 / 三类逃逸 409
        r = req("GET", f"/agent-nodes/{nid}/files/read?{files_qs(root_a, 'blob.bin')}", token=tok, expect=409)
        assert "二进制" in json.dumps(r, ensure_ascii=False), r
        req("PUT", f"/agent-nodes/{nid}/files/content",
            {"root": root_a, "path": "big.txt", "content": "x" * (CAP_TEXT + 1)}, tok, expect=400)
        for bad_path in ("../", "..", "子 目录/../../outside.txt", "/etc/passwd", "D:/Windows/x.txt"):
            req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a, bad_path)}", token=tok, expect=409)
        req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a + '/子 目录')}", token=tok, expect=409)
        req("GET", f"/agent-nodes/{nid}/files/list?{files_qs('D:/not-whitelisted')}", token=tok, expect=409)
        lst_b = req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_b)}", token=tok)
        assert lst_b["entries"] == [], lst_b
        print("[6] 二进制/超限/逃逸（..、绝对路径、白名单外 root）全拒 OK；第二根目录可用")

        # [7] delete：文件、空目录、非空目录 recursive 门控、根目录拒绝
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "改名 后.md", "recursive": False}, tok)
        assert not (ROOT_A / "改名 后.md").exists()
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "zdir", "recursive": False}, tok)
        assert not (ROOT_A / "zdir").exists()
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "非空目录", "recursive": False}, tok, expect=409)
        assert (ROOT_A / "非空目录" / "inner" / "叶.txt").exists(), "拒绝时不应误删"
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "非空目录", "recursive": True}, tok)
        assert not (ROOT_A / "非空目录").exists()
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "", "recursive": True}, tok, expect=409)
        print("[7] delete OK（文件/空目录/非空 recursive 门控/根目录拒绝）")

        # [8] 5MB 二进制 upload → download sha256 对账 + RFC5987 中文文件名
        src = TMP / "cap65-upload-src.bin"
        src.write_bytes(hashlib.sha256(b"cap65").digest() * (5 * 1024 * 1024 // 32))
        name_cn = "上传 文件 5mb.bin"
        multipart_post(f"/agent-nodes/{nid}/files/upload?{files_qs(root_a, '子 目录')}",
                       str(src), name_cn, tok)
        landed = ROOT_A / "子 目录" / name_cn
        assert landed.exists() and sha256_of(landed) == sha256_of(src), "上传落位 sha256 不符"
        body, headers = req("GET", f"/agent-nodes/{nid}/files/download?{files_qs(root_a, '子 目录/' + name_cn)}",
                            token=tok, raw=True)
        assert hashlib.sha256(body).hexdigest() == sha256_of(src), "下载 sha256 不符"
        cd = headers.get("Content-Disposition", "")
        assert "UTF-8''" in cd and urllib.parse.quote(name_cn) in cd, f"中文文件名应走 RFC5987: {cd}"
        print("[8] 5MB upload→download sha256 对账 OK，中文文件名 RFC5987 不乱码")

        # [9] 64MB 上传进行中，list 小帧照常（中转不阻塞 WS）
        big_ok = TMP / "cap65-64mb.bin"
        with open(big_ok, "wb") as fh:
            fh.seek(64 * 1024 * 1024 - 1)
            fh.write(b"x")
        up_err = []
        t = threading.Thread(target=lambda: multipart_post(
            f"/agent-nodes/{nid}/files/upload?{files_qs(root_a, '')}", str(big_ok), "big64.bin", tok),
            daemon=True)
        t.start()
        t0 = time.time()
        lst2 = req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a)}", token=tok)
        elapsed = time.time() - t0
        assert elapsed < 15, f"中转期间 list 被阻塞（{elapsed:.1f}s）"
        t.join(120)
        assert not up_err and (ROOT_A / "big64.bin").exists(), "64MB 上传应成功"
        print(f"[9] 64MB 中转期间 list {elapsed:.1f}s 响应 OK（WS 不被大文件阻塞）")
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "big64.bin", "recursive": False}, tok)

        # [10] >100MB 上传 409（服务端终判，不等 runner）
        big_no = TMP / "cap65-101mb.bin"
        with open(big_no, "wb") as fh:
            fh.seek(CAP_TRANSFER)  # 100MB+1
            fh.write(b"x")
        multipart_post(f"/agent-nodes/{nid}/files/upload?{files_qs(root_a, '')}",
                       str(big_no), "big101.bin", tok, expect=409)
        assert not (ROOT_A / "big101.bin").exists(), "超限文件不应落位"
        print("[10] >100MB 上传 409 OK")

        # [11] 白名单清空 → 409；恢复 → 可用
        req("PUT", f"/agent-nodes/{nid}", {"fileRoots": []}, tok)
        r = req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a)}", token=tok, expect=409)
        assert "白名单" in json.dumps(r, ensure_ascii=False), r
        req("PUT", f"/agent-nodes/{nid}", {"fileRoots": [root_a, root_b]}, tok)
        req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a)}", token=tok)
        print("[11] 白名单清空 409 / 恢复可用 OK")

        # [12] 老 runner（v17）→ 409 指引升级
        legacy_sock = ws_connect_v17(legacy["token"])
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == legacy["node"]["id"] and n["status"] == "ONLINE"
                           and n.get("protocolVersion") == 17), None),
             "v17 节点上线", 30)
        r = req("GET", f"/agent-nodes/{legacy['node']['id']}/files/list?{files_qs(root_a)}",
                token=tok, expect=409)
        assert "v18" in json.dumps(r, ensure_ascii=False), r
        print("[12] runner 协议 v17 → 409 指引升级 OK")

        # [13] 离线节点 → 409 不在线
        r = req("GET", f"/agent-nodes/{offline['node']['id']}/files/list?{files_qs(root_a)}",
                token=tok, expect=409)
        assert "不在线" in json.dumps(r, ensure_ascii=False), r
        print("[13] 离线节点 409 OK")

        # [14] 非 ADMIN（DEVELOPER）：GET/POST 一律 403（GET 也敏感——可读白名单内任意文件）
        dev = req("POST", "/auth/users",
                  {"username": MARK, "displayName": "cap65 dev", "password": "dev12345", "role": "DEVELOPER"}, tok)
        dev_uid = dev.get("id") or dev.get("user", {}).get("id")
        dev_login = req("POST", "/auth/login", {"username": MARK, "password": "dev12345"})
        dev_tok = dev_login.get("token") or dev_login.get("accessToken")
        req("GET", f"/agent-nodes/{nid}/files/list?{files_qs(root_a)}", token=dev_tok, expect=403)
        req("GET", f"/agent-nodes/{nid}/files/download?{files_qs(root_a, 'blob.bin')}", token=dev_tok, expect=403)
        req("POST", f"/agent-nodes/{nid}/files/delete",
            {"root": root_a, "path": "blob.bin", "recursive": False}, dev_tok, expect=403)
        req("PUT", f"/agent-nodes/{nid}", {"fileRoots": ["/x"]}, dev_tok, expect=403)
        print("[14] 非 ADMIN GET/POST/PUT 一律 403 OK")
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
        shutil.rmtree(FIX, ignore_errors=True)
        for f in ("cap65-upload-src.bin", "cap65-64mb.bin", "cap65-101mb.bin"):
            (TMP / f).unlink(missing_ok=True)
    print("全部通过")


if __name__ == "__main__":
    main()
