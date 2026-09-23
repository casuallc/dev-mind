#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-59 远程终端增强 E2E（前置：后端已用新代码启动，runner jar 已重建 ≥v17）。

覆盖（协议 v17，持久 shell / terminal_complete / terminal_cancel）：
1. env 保持：export FOO 后 echo $FOO 有值（CAP-58 单条进程模型必为空）；
2. cd 保持：单发 `cd subdir`，下条 pwd 仍在 subdir（不依赖前端 cwd 重放——
   故意传 cwd="" 验证 runner 侧 cwd 权威）；
3. cd 越界拉回：`cd ../..` → 409，随后 pwd 已被强制拉回代码目录内；
4. Tab 补全：文件候选（README.md）、cd 只补目录（subdir/ 带斜杠）、首词补命令（pwd）；
   问答沙箱 /chats 对称端点可用；
5. 取消：`tail -f` 长跑命令执行中 POST cancel → exec 秒级收口 cancelled=true、exit 130；
   shell 随杀自动重启（env 丢失 = 重启佐证），下条命令正常。

跑法见 tests/README.md「起独立实例跑 E2E」；E2E_BASE 默认打 :8080。
脚本自起 fake runner 节点（executor=fake），不需要另开节点。
"""
import json, os, shutil, subprocess, sys, threading, time, urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap59-ws"
ORIGIN = TMP / "cap59-origin"
PROPS = TMP / "cap59-runner.properties"
MARK = f"e2e-cap59-{int(time.time())}"


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


def term(tok, base_path, sid, command, cwd="", expect=200):
    return req("POST", f"{base_path}/{sid}/terminal/exec", {"command": command, "cwd": cwd},
               token=tok, expect=expect)


def complete(tok, base_path, sid, input_line, cwd="", expect=200):
    return req("POST", f"{base_path}/{sid}/terminal/complete", {"input": input_line, "cwd": cwd},
               token=tok, expect=expect)


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
    (ORIGIN / "subdir").mkdir()
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap59 e2e\n", encoding="utf-8")
    (ORIGIN / "subdir" / "inner.txt").write_text("inner\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)

    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    reqm = req("POST", f"/projects/{pid}/requirements", {
        "title": f"{MARK} 终端增强", "description": "CAP-59 E2E",
    }, tok)
    rid = reqm["id"]
    print(f"[1] 项目 {pid} + 需求 {reqm['code']} 就绪")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl={BASE.replace('http://', 'ws://').removesuffix('/api')}/ws/agent\n"
        f"token={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap59-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap59-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    sid = None
    chat_id = None
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        meta = next(n for n in req("GET", "/agent-nodes", token=tok) if n["id"] == node["id"])
        pv = meta.get("protocolVersion")
        assert pv is not None and pv >= 17, f"runner 协议版本过低: {pv}（runner jar 未重建？）"
        print("[2] runner 上线（协议 v17+）")

        sess = req("POST", f"/projects/{pid}/requirements/{rid}/flow/plan", None, tok)
        sid = sess["id"]
        print(f"[3] 会话 {sid} 就绪")

        # ---- 1. env 保持 ----
        r = term(tok, "/sessions", sid, "export FOO=cap59bar")
        assert r["ok"], f"export 失败: {r}"
        r = term(tok, "/sessions", sid, "echo $FOO", cwd="")
        assert "cap59bar" in r["stdout"], f"env 未跨命令保持（持久 shell 未生效？）: {r}"
        print("[4] env 跨命令保持 OK")

        # ---- 2. cd 保持（runner cwd 权威：故意不带前端 cwd）----
        r = term(tok, "/sessions", sid, "cd subdir")
        assert r["ok"] and r.get("cwd") == "subdir", f"cd 后 cwd 应为 subdir: {r}"
        r = term(tok, "/sessions", sid, "pwd", cwd="")  # 前端 cwd 故意给空，shell 状态仍应在 subdir
        assert "subdir" in r["stdout"].replace("\\", "/"), f"cd 未保持（runner 权威失效）: {r}"
        assert r.get("cwd") == "subdir", f"ack cwd 应继续是 subdir: {r}"
        r = term(tok, "/sessions", sid, "cd ..")
        assert r.get("cwd") == "", f"cd .. 应回根: {r}"
        print("[5] cd 跨命令保持 OK（runner 侧 cwd 权威，前端空 cwd 不影响）")

        # ---- 3. cd 越界拉回 ----
        e = term(tok, "/sessions", sid, "cd ../..", expect=409)
        assert "越界" in json.dumps(e, ensure_ascii=False), e
        r = term(tok, "/sessions", sid, "pwd", cwd="")
        assert r["ok"] and r.get("cwd") == "", f"越界后 shell 应被拉回代码目录根: {r}"
        print("[6] cd 越界 → 409 + shell 强制拉回 OK")

        # ---- 4. Tab 补全 ----
        r = complete(tok, "/sessions", sid, "cat READ")
        assert r["ok"] and "README.md" in r["candidates"], f"文件补全不符: {r}"
        assert r["word"] == "READ", f"word 应为 READ: {r}"
        r = complete(tok, "/sessions", sid, "cd sub")
        assert r["ok"] and "subdir/" in r["candidates"], f"cd 目录补全（带/）不符: {r}"
        assert all(c.endswith("/") for c in r["candidates"]), f"cd 应只补目录: {r}"
        r = complete(tok, "/sessions", sid, "pw")
        assert r["ok"] and "pwd" in r["candidates"], f"首词命令补全不符: {r}"
        print("[7] Tab 补全 OK（文件/目录带斜杠/首词命令）")

        # ---- 5. 取消（tail -f 长跑 + cancel → 130 + shell 重启）----
        box = {}

        def run_tail():
            box["r"] = term(tok, "/sessions", sid, "tail -f README.md")

        t = threading.Thread(target=run_tail, daemon=True)
        t.start()
        time.sleep(3)  # 等 tail -f 进入执行
        req("POST", f"/sessions/{sid}/terminal/cancel", None, tok)
        t.join(20)
        assert "r" in box, "tail -f 未在 cancel 后收口（20s 超时）"
        r = box["r"]
        assert r["ok"] and r.get("cancelled") and r["exitCode"] == 130, f"取消收口不符: {r}"
        # shell 随杀重启 → env 丢失（重启佐证），命令正常
        r = term(tok, "/sessions", sid, "echo x$FOO")
        assert r["ok"] and "cap59bar" not in r["stdout"], f"取消后 shell 应已重启（env 应丢）: {r}"
        print("[8] Ctrl+C 取消 OK（exit 130 + shell 自动重启）")

        # ---- 问答沙箱对称（complete 端点）----
        chat = req("POST", "/chats", {"message": f"{MARK} 你好"}, tok)
        chat_id = chat["id"]
        wait(lambda: req("GET", f"/chats/{chat_id}", token=tok).get("state")
             in ("RUNNING", "WAITING_INPUT", "DONE") or None, "问答启动", 60)
        r = complete(tok, "/chats", chat_id, "pw")
        assert r["ok"] and "pwd" in r["candidates"], f"问答补全不符: {r}"
        print("[9] 问答沙箱 complete 对称端点 OK")

        print("\nCAP-59 E2E 全部通过")
    finally:
        kill_tree(runner.pid)
        try:
            if sid:
                req("DELETE", f"/sessions/{sid}", token=tok)
        except Exception:
            pass
        try:
            if chat_id:
                req("DELETE", f"/chats/{chat_id}", token=tok)
        except Exception:
            pass
        try:
            req("DELETE", f"/projects/{pid}", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
        except Exception:
            pass


if __name__ == "__main__":
    main()
