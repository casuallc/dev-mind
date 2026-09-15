#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-34 FR-04 服务端重启 reattach + exit 路由的最小复现（前置：后端 :8080 在跑新代码）。

步骤：建节点 → 起 runner(fake) → 建会话 RUNNING → 重启后端（同机新进程，可见日志）
→ runner 重连 hello → 会话应保持 RUNNING（reattach）→ kill → exit 帧应路由回服务端翻 FAILED/DONE。
"""
import json, subprocess, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
PROPS = TMP / "cap34-reattach.properties"
APPLOG = TMP / "cap34-app-restart.log"

def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token: r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")

def wait(cond, what, timeout=40):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v: return v
        time.sleep(1)
    raise AssertionError(f"timeout: {what}")

def health():
    try:
        with urllib.request.urlopen("http://localhost:8080/api/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False

def login():
    l = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    return l.get("token") or l.get("accessToken")

def main():
    tok = login()
    pid_proj = req("GET", "/projects", token=tok)[0]["id"]

    issued = req("POST", "/agent-nodes", {"name": f"e2e-reattach-{int(time.time())}"}, tok)
    node_id, node_token = issued["node"]["id"], issued["token"]
    PROPS.write_text(f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
                     f"workDir={(TMP / 'cap34-reattach-work').as_posix()}\nmaxConcurrent=2\n", encoding="utf-8")
    runner = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(PROPS)],
                              stdout=open(TMP / "cap34-reattach-runner.log", "w", encoding="utf-8"),
                              stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node_id and n["status"] == "ONLINE"), None), "node online")
        s = req("POST", "/sessions", {"taskSpec": "e2e-reattach", "projectId": pid_proj,
                                      "agentNodeId": str(node_id)}, tok)
        sid = s["id"]
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "RUNNING", "RUNNING")
        print(f"[1] session {sid} RUNNING on node {node_id}")

        # 重启后端：杀 DevMindApplication，同目录起新实例（可见日志）
        out = subprocess.run(["jps", "-l"], capture_output=True, text=True).stdout
        bp = next(int(l.split()[0]) for l in out.splitlines() if "DevMindApplication" in l)
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(bp)], capture_output=True)
        time.sleep(3)
        with open(APPLOG, "w") as lf:
            subprocess.Popen('mvn -pl devmind-app spring-boot:run', cwd=ROOT, shell=True,
                             stdout=lf, stderr=subprocess.STDOUT,
                             creationflags=subprocess.CREATE_NEW_PROCESS_GROUP)
        wait(health, "backend back", 150)
        tok = login()
        # runner 重连 + hello 对账需要时间
        time.sleep(8)
        v = req("GET", f"/sessions/{sid}", token=tok)
        assert v.get("status") == "RUNNING", f"reattach 后应 RUNNING: {v.get('status')}"
        print(f"[2] reattach OK: {sid} stays RUNNING after server restart")

        # 用 finish（优雅结束）而非 kill 验证 exit 帧路由：kill 端点本地直接翻 TERMINATED，
        # 不经过 exit 帧，验证不到路由；finish → runner 关 stdin → fake EOF 退出 → exit 上行 → DONE
        req("POST", f"/sessions/{sid}/finish", token=tok)
        st = wait(lambda: (req("GET", f"/sessions/{sid}", token=tok).get("status") in ("DONE", "FAILED"))
                          and req("GET", f"/sessions/{sid}", token=tok).get("status"),
                  "exit routed -> DONE/FAILED", 60)
        print(f"[3] exit frame routed OK: {sid} -> {st}")
    finally:
        subprocess.run(["taskkill", "/F", "/T", "/PID", str(runner.pid)], capture_output=True)
        try:
            req("DELETE", f"/sessions/{sid}", token=tok)
        except Exception:
            pass
        try:
            req("DELETE", f"/agent-nodes/{node_id}", token=tok)
        except Exception:
            pass
    print("PASS")

if __name__ == "__main__":
    main()
