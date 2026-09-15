#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-34 FR-04/05/07/08 E2E 验证（前置：后端 :8080 已用新代码启动，runner jar 已重建）。

覆盖：
 1. hello 新字段：protocolVersion=2 / toolchain 非空 / labels 配置覆盖服务端值（D4）/ workspaceBytes 上报
 2. 旧 runner 兼容：存量旧版 runner 重连新服务端不炸（protocolVersion 缺省）
 3. 标签调度：requiredLabels 命中创建成功；无命中 409；显式节点标签不符 409
 4. runner 强杀重启：孤儿进程回收 + 服务端会话判 FAILED
 5. 服务端重启：存活会话 hello 对账 reattach（exit 帧仍可路由）
 6. 工作区 GC：gcDays=0 + 预制超龄 chat 沙箱被删除
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap34-ws"
PROPS = TMP / "cap34-runner.properties"

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
    raise AssertionError(f"超时等待: {what}")

def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)

def start_runner():
    return subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(PROPS)],
                            stdout=open(TMP / "cap34-runner.log", "a", encoding="utf-8"),
                            stderr=subprocess.STDOUT)

def backend_pid():
    out = subprocess.run(["jps", "-l"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "DevMindApplication" in line:
            return int(line.split()[0])
    return None

def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    # 会话必须挂项目——restoreOnStartup 会删除无项目存量会话（重启场景下 404 的根因）
    projects = req("GET", "/projects", token=tok)
    assert projects, "需要至少一个项目"
    PID = projects[0]["id"]
    print(f"[0] 使用项目 {PID}")

    # 清理上一轮残留
    for n in req("GET", "/agent-nodes", token=tok):
        if str(n.get("name", "")).startswith("e2e-cap34"):
            req("DELETE", f"/agent-nodes/{n['id']}", token=tok)
    for s in req("GET", "/sessions", token=tok):
        if str(s.get("taskSpec", "")).startswith("e2e-cap34"):
            try: req("DELETE", f"/sessions/{s['id']}", token=tok)
            except Exception: pass

    # 预制超龄 chat 沙箱（无 pid 文件、非活跃 → GC 应删）
    shutil.rmtree(WS, ignore_errors=True)
    stale = WS / "_chat" / "c-gc-old"
    stale.mkdir(parents=True)
    (stale / "draft.txt").write_text("x", encoding="utf-8")

    # 1. 建节点（服务端先设一个 labels 值，验证 runner 配置覆盖）
    issued = req("POST", "/agent-nodes", {"name": f"e2e-cap34-{int(time.time())}", "labels": "serveredit"}, tok)
    node, node_token = issued["node"], issued["token"]
    node_id = node["id"]
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap34-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=4\nlabels=mvn,linux\ngcDays=0\ngcIntervalMinutes=1\ngcInitialDelayMinutes=0\n",
        encoding="utf-8")
    runner = start_runner()
    backend_restarted = False
    try:
        view = wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                                  if n["id"] == node_id and n["status"] == "ONLINE"
                                  and n.get("protocolVersion") == 2), None), "节点上线且 hello v2")
        assert view.get("labels") == "mvn,linux", f"runner 配置 labels 应覆盖服务端值: {view.get('labels')}"
        tc = json.loads(view.get("toolchain") or "{}")
        assert "git" in tc, f"toolchain 应含 git: {tc}"
        assert view.get("workspaceBytes") is not None, "workspaceBytes 应已上报"
        print(f"[1] hello 新字段 OK protocolVersion=2 labels={view['labels']} toolchain={tc} workspaceBytes={view['workspaceBytes']}")

        # 2. 旧 runner 兼容（环境里常驻的旧版 runner 重连新服务端不炸）
        others = [n for n in req("GET", "/agent-nodes", token=tok)
                  if n["id"] != node_id and n["status"] == "ONLINE"]
        for o in others:
            assert o.get("protocolVersion") in (None, 2), f"旧 runner hello 不应炸: {o}"
            print(f"[2] 旧 runner 兼容 OK: {o['name']} protocolVersion={o.get('protocolVersion')}")
        if not others:
            print("[2] 无其他在线旧 runner，跳过兼容观察")

        # 3. 标签调度
        s_ok = req("POST", "/sessions", {"taskSpec": "e2e-cap34 标签命中", "projectId": PID,
                                         "requiredLabels": "mvn"}, tok)
        print(f"[3a] requiredLabels=mvn 命中节点创建会话 OK id={s_ok['id']} node={s_ok.get('agentNodeId')}")
        e = req("POST", "/sessions", {"taskSpec": "e2e-cap34 无命中", "projectId": PID,
                                      "requiredLabels": "no-such-label"}, tok, expect=409)
        print(f"[3b] 无满足标签 -> 409 OK ({e.get('message','')[:50]})")
        e = req("POST", "/sessions", {"taskSpec": "e2e-cap34 显式不符", "projectId": PID,
                                      "agentNodeId": str(node_id),
                                      "requiredLabels": "windows"}, tok, expect=409)
        print(f"[3c] 显式节点标签不符 -> 409 OK ({e.get('message','')[:50]})")

        # 4. runner 强杀重启：孤儿回收 + 会话判 FAILED
        s_orphan = req("POST", "/sessions", {"taskSpec": "e2e-cap34 孤儿", "projectId": PID,
                                             "agentNodeId": str(node_id)}, tok)
        wait(lambda: req("GET", f"/sessions/{s_orphan['id']}", token=tok).get("status") == "RUNNING",
             "孤儿会话 RUNNING") or None
        sid_orphan = s_orphan["id"]
        # 读 pid 文件记下 fake 孤儿进程
        pid_files = list(WS.glob(f"_chat/*/.runner-pid")) + list(WS.glob(f"*/*/*/.runner-pid"))
        orphan_pids = []
        for pf in pid_files:
            try: orphan_pids.append(int(pf.read_text().splitlines()[0]))
            except Exception: pass
        kill_tree(runner.pid)
        runner.wait()
        time.sleep(2)
        runner = start_runner()  # 重启：先对账回收孤儿，再 hello
        wait(lambda: req("GET", f"/sessions/{sid_orphan}", token=tok).get("status") == "FAILED",
             f"会话 {sid_orphan} 判 FAILED", 60)
        for op in orphan_pids:
            r = subprocess.run(["tasklist", "/FI", f"PID eq {op}"], capture_output=True, text=True)
            assert str(op) not in r.stdout, f"孤儿进程 {op} 应被回收"
        print(f"[4] runner 强杀重启 OK：会话 {sid_orphan} 判 FAILED，孤儿进程 {orphan_pids} 已回收")

        # 5. 服务端重启 reattach
        s_live = req("POST", "/sessions", {"taskSpec": "e2e-cap34 reattach", "projectId": PID,
                                           "agentNodeId": str(node_id)}, tok)
        wait(lambda: req("GET", f"/sessions/{s_live['id']}", token=tok).get("status") == "RUNNING",
             "reattach 会话 RUNNING") or None
        sid_live = s_live["id"]
        pid = backend_pid()
        assert pid, "未找到后端进程"
        kill_tree(pid)
        time.sleep(3)
        subprocess.Popen(["cmd", "/c", "mvn", "-q", "-pl", "devmind-app", "spring-boot:run"], cwd=ROOT,
                         stdout=open(TMP / "cap34-app-restart.log", "w", encoding="utf-8"),
                         stderr=subprocess.STDOUT,
                         creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
        backend_restarted = True
        wait(lambda: _health(), "后端重启恢复", 120)
        login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
        tok = login.get("token") or login.get("accessToken")
        # runner 断线重连 → hello activeSessions 含 s_live → 服务端 reattach（保持 RUNNING）
        time.sleep(5)
        v = req("GET", f"/sessions/{sid_live}", token=tok)
        assert v.get("status") == "RUNNING", f"reattach 后应仍 RUNNING: {v.get('status')}"
        print(f"[5] 服务端重启 reattach OK：会话 {sid_live} 保持 RUNNING")
        # exit 帧可路由：finish（非 kill——kill 端点本地直接翻 TERMINATED 不经过 exit 帧）
        # → runner 关 stdin → fake EOF 退出 → exit 上行 → 状态翻转 DONE/FAILED
        req("POST", f"/sessions/{sid_live}/finish", token=tok)
        st = wait(lambda: req("GET", f"/sessions/{sid_live}", token=tok).get("status") in ("DONE", "FAILED")
                          and req("GET", f"/sessions/{sid_live}", token=tok).get("status"),
                  "exit 帧路由收口", 40)
        print(f"[5b] reattach 后 exit 路由 OK：{sid_live} -> {st}")

        # 6. GC：预制超龄 chat 沙箱已删；workspaceBytes 持续上报
        assert not stale.exists(), f"GC 应删除超龄 chat 沙箱 {stale}"
        print("[6] 工作区 GC OK：超龄 chat 沙箱已删除")
    finally:
        kill_tree(runner.pid)
        # 清理（后端被我重启过的话仍在后台跑着新实例——留给后续使用）
        try:
            for s in req("GET", "/sessions", token=tok):
                if str(s.get("taskSpec", "")).startswith("e2e-cap34"):
                    try: req("DELETE", f"/sessions/{s['id']}", token=tok)
                    except Exception: pass
            req("DELETE", f"/agent-nodes/{node_id}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
    print("全部通过")

def _health():
    try:
        with urllib.request.urlopen("http://localhost:8080/api/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False

if __name__ == "__main__":
    main()
