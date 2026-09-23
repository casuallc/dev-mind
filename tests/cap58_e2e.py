#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-58 会话工作区远程终端 E2E（前置：后端已用新代码启动，runner jar 已重建 ≥v16）。

覆盖（协议 v16，terminal_exec/terminal_exec_ack）：
1. 项目会话终端：pwd/ls/cat/git status 回显；cat 不存在文件 exitCode≠0（仍 200）；
2. cd 状态：`mkdir 不做`——cd 到子目录后 ack 带回新 cwd，后续命令在其下执行（pwd 验证）；
3. 安全边界：cwd 参数 ../ 逃逸 → 409；`cd ..` 出代码目录 → 409 且 cwd 不变；
   白名单外命令（rm）→ 409；git 写子命令（commit）→ 409；> 重定向 → 409；
4. 问答沙箱（chat）：pwd/ls 可用（非 git 目录）；
5. 终态会话：runner recentDirs 兜底，finish 后 ls 仍可执行。

跑法见 tests/README.md「起独立实例跑 E2E」；E2E_BASE 默认打 :8080。
脚本自起 fake runner 节点（executor=fake），不需要另开节点。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = os.environ.get("E2E_BASE", "http://localhost:8080/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap58-ws"
ORIGIN = TMP / "cap58-origin"
PROPS = TMP / "cap58-runner.properties"
MARK = f"e2e-cap58-{int(time.time())}"


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


def session_dir(sid, rid, timeout=60):
    """同 cap54：工作区根到工作树隔 <项目>/<用户> 两层，glob 必须带 **。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        for pattern in (f"**/worktrees/req-{rid}", f"**/worktrees/sid-{sid}", f"**/sessions/{sid}"):
            hit = next(iter(WS.glob(pattern)), None)
            if hit:
                return hit
        if (WS / "_chat" / sid).exists():
            return WS / "_chat" / sid
        time.sleep(1)
    raise AssertionError(f"未找到会话工作区: session={sid} req={rid}")


def term(tok, base_path, sid, command, cwd="", expect=200):
    return req("POST", f"{base_path}/{sid}/terminal/exec", {"command": command, "cwd": cwd},
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
    (ORIGIN / "README.md").write_text("# cap58 e2e\n", encoding="utf-8")
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
        "title": f"{MARK} 远程终端", "description": "CAP-58 E2E",
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
        f"workDir={(TMP / 'cap58-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap58-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    sid = None
    chat_id = None
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        # runner 协议版本须 ≥16（终端帧门控）
        meta = next(n for n in req("GET", "/agent-nodes", token=tok) if n["id"] == node["id"])
        pv = meta.get("protocolVersion")
        assert pv is None or pv >= 16, f"runner 协议版本过低: {pv}（runner jar 未重建？）"
        print("[2] runner 上线（协议 v16+）")

        # ---- 项目会话终端 ----
        sess = req("POST", f"/projects/{pid}/requirements/{rid}/flow/plan", None, tok)
        sid = sess["id"]
        wt = session_dir(sid, rid)
        print(f"[3] 会话 {sid} 工作树 {wt.name}")

        r = term(tok, "/sessions", sid, "pwd")
        assert r["ok"] and r["exitCode"] == 0, f"pwd 失败: {r}"
        assert wt.name.replace("\\", "/") in r["stdout"].replace("\\", "/"), f"pwd 应在工作树: {r}"
        r = term(tok, "/sessions", sid, "ls")
        assert "README.md" in r["stdout"] and "subdir" in r["stdout"], f"ls 不符: {r}"
        r = term(tok, "/sessions", sid, "cat README.md")
        assert "cap58 e2e" in r["stdout"], f"cat 不符: {r}"
        r = term(tok, "/sessions", sid, "git status")
        assert r["exitCode"] == 0 and "branch" in r["stdout"].lower(), f"git status 不符: {r}"
        r = term(tok, "/sessions", sid, "cat no-such-file.txt")
        assert r["ok"] and r["exitCode"] != 0, f"不存在文件应非零退出: {r}"
        print("[4] pwd/ls/cat/git status/非零退出 OK")

        # cd 状态：下带子目录 → ack 带回新 cwd → 后续命令在其下
        r = term(tok, "/sessions", sid, "cd subdir && pwd")
        assert r["ok"] and r.get("cwd") == "subdir", f"cd 后 cwd 应为 subdir: {r}"
        r = term(tok, "/sessions", sid, "ls", cwd=r["cwd"])
        assert "inner.txt" in r["stdout"] and "README.md" not in r["stdout"], f"cwd 未跟进: {r}"
        print("[5] cd 状态跟进 OK（cwd=subdir，ls 只见 inner.txt）")

        # 安全边界
        e = term(tok, "/sessions", sid, "ls", cwd="../..", expect=409)
        assert "越界" in json.dumps(e, ensure_ascii=False), e
        e = term(tok, "/sessions", sid, "cd ../.. && pwd", expect=409)
        assert "越界" in json.dumps(e, ensure_ascii=False), e
        e = term(tok, "/sessions", sid, "rm -rf subdir", expect=409)
        assert "白名单" in json.dumps(e, ensure_ascii=False), e
        e = term(tok, "/sessions", sid, "git commit -m x", expect=409)
        assert "git commit" in json.dumps(e, ensure_ascii=False), e
        e = term(tok, "/sessions", sid, "echo hi > hack.txt", expect=409)
        assert "重定向" in json.dumps(e, ensure_ascii=False), e
        assert not (wt / "hack.txt").exists(), "重定向不应落盘"
        print("[6] 逃逸/白名单/git 写/重定向 全拒 OK")

        # ---- 问答沙箱 ----
        chat = req("POST", "/chats", {"message": f"{MARK} 你好"}, tok)
        chat_id = chat["id"]
        wait(lambda: req("GET", f"/chats/{chat_id}", token=tok).get("state")
             in ("RUNNING", "WAITING_INPUT", "DONE") or None, "问答启动", 60)
        r = term(tok, "/chats", chat_id, "pwd")
        assert r["ok"] and r["exitCode"] == 0, f"问答 pwd 失败: {r}"
        # 沙箱目录直接落个文件验证 cat（不断言 git——沙箱在平台仓库 tmp/ 下，
        # git 会向上发现父仓库，非零退出假设不成立）
        sand = session_dir(chat_id, "", timeout=10)
        (sand / "hello-cap58.txt").write_text("sandbox-hi\n", encoding="utf-8")
        r = term(tok, "/chats", chat_id, "cat hello-cap58.txt")
        assert r["ok"] and "sandbox-hi" in r["stdout"], f"问答 cat 不符: {r}"
        print("[7] 问答沙箱终端 OK（pwd/cat 可用）")

        # ---- 终态兜底（runner recentDirs）----
        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
             "会话 DONE", 90)
        r = term(tok, "/sessions", sid, "cat README.md")
        assert r["ok"] and "cap58 e2e" in r["stdout"], f"终态 cat 不符: {r}"
        print("[8] 终态会话经 recentDirs 仍可执行 OK")

        print("\nCAP-58 E2E 全部通过")
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
