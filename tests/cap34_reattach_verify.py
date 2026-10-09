#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-34 FR-04 服务端重启 reattach + exit 路由的最小复现（前置：后端 :8080 在跑新代码）。

步骤：建节点 → 起 runner(fake) → 建会话 RUNNING 并攒够历史事件 → 重启后端（同机新进程）
→ runner 重连 hello → 会话应保持活动态（reattach）：
  [2] reattach 后 REST 历史 seq 续接（"节点已重连" 日志 seq = 重启前 maxSeq+1，无撞号）
  [3] WS snapshot 仍含重启前历史（活动态会话前端只走 snapshot，不补历史=会话页空白）
  [4] 重启后继续输入，新事件 seq 单调递增（运行时 seq 从 DB 最大值续编）
  [5] finish → exit 帧应路由回服务端翻 DONE/FAILED
"""
import json, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8", errors="replace")

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

def events(sid, tok):
    return req("GET", f"/sessions/{sid}/events?afterSeq=-1", token=tok)

def snapshot(sid):
    """经 WS 探针取 snapshot 帧（= 前端活动态会话的唯一历史来源）。"""
    out = subprocess.run(
        ["node", str(ROOT / "tests/cap34-snapshot-probe.mjs"), sid],
        capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=20).stdout
    for line in out.splitlines():
        if line.startswith("SNAP_JSON "):
            return json.loads(line[len("SNAP_JSON "):])
    raise AssertionError(f"snapshot 探针未收敛: {out.strip()[:300]}")

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

        # 攒够重启前历史：等 fake 执行体的步骤事件落库（assistant 含"正在执行任务"），记录基线
        MARK = "正在执行任务"
        evs0 = wait(lambda: (lambda es: es if len(es) >= 6 and any(
            MARK in (e.get("content") or "") for e in es) else None)(events(sid, tok)),
            "pre-restart history events", 60)
        m0 = max(e["seq"] for e in evs0)
        print(f"    重启前历史 {len(evs0)} 条, maxSeq={m0}")

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
        # runner 重连 + hello 对账需要时间；收敛判据 = "节点已重连"日志落库（reattach 完成）
        evs1 = wait(lambda: (lambda es: es if any(
            "节点已重连" in (e.get("content") or "") for e in es) else None)(events(sid, tok)),
            "reattach log persisted", 90)
        v = req("GET", f"/sessions/{sid}", token=tok)
        assert v.get("status") in ("RUNNING", "WAITING_INPUT", "WAITING_AUTH"), \
            f"reattach 后应保持活动态: {v.get('status')}"
        print(f"[2] reattach OK: {sid} stays {v.get('status')} after server restart")

        # seq 续接：重启后第一条新事件（重连日志）必须接在 DB 最大值之后，且全程无撞号。
        # 基线不能取重启前读到的 m0——假执行体在重启窗口内仍在吐事件，历史最大值会前移；
        # 不变量 = 重连日志 seq 恰为其余事件最大 seq + 1（续接而非从 1 重编）。
        seqs = [e["seq"] for e in evs1]
        assert len(seqs) == len(set(seqs)), f"事件 seq 撞号: {sorted(seqs)}"
        reconn = next(e for e in evs1 if "节点已重连" in (e.get("content") or ""))
        hist_max = max(e["seq"] for e in evs1 if e["seq"] != reconn["seq"])
        assert reconn["seq"] == hist_max + 1, \
            f"重连日志 seq={reconn['seq']} 应为历史最大值 {hist_max}+1（续接而非重编）"
        print(f"    seq 续接 OK: 重连日志 seq={reconn['seq']} = 历史最大值({hist_max})+1, 无撞号")

        # WS snapshot（活动态会话前端唯一历史来源）必须仍含重启前历史，且 maxSeq 已含重连日志
        snap = snapshot(sid)
        assert any(MARK in e["content"] for e in snap["events"]), \
            f"snapshot 丢失重启前历史: {[(e['seq'], e['type']) for e in snap['events']]}"
        assert snap["maxSeq"] >= reconn["seq"], \
            f"snapshot maxSeq={snap['maxSeq']} 应>={reconn['seq']}"
        print(f"[3] snapshot OK: {snap['count']} 条回放含重启前历史, maxSeq={snap['maxSeq']}")

        # 重启后继续交互：新事件 seq 必须单调续增（运行时 seq 从 DB 最大值续编，不从 1 重编）
        req("POST", f"/sessions/{sid}/input", {"text": "重启后继续"}, tok)
        evs2 = wait(lambda: (lambda es: es if any(
            "收到：重启后继续" in (e.get("content") or "") and e["seq"] > reconn["seq"]
            for e in es) else None)(events(sid, tok)), "post-restart reply", 60)
        seqs2 = [e["seq"] for e in evs2]
        assert len(seqs2) == len(set(seqs2)), f"交互后事件 seq 撞号: {sorted(seqs2)}"
        print(f"[4] 重启后交互 OK: 回复事件 seq 单调续增 (max={max(seqs2)})")

        # 用 finish（优雅结束）而非 kill 验证 exit 帧路由：kill 端点本地直接翻 TERMINATED，
        # 不经过 exit 帧，验证不到路由；finish → runner 关 stdin → fake EOF 退出 → exit 上行 → DONE
        req("POST", f"/sessions/{sid}/finish", token=tok)
        st = wait(lambda: (req("GET", f"/sessions/{sid}", token=tok).get("status") in ("DONE", "FAILED"))
                          and req("GET", f"/sessions/{sid}", token=tok).get("status"),
                  "exit routed -> DONE/FAILED", 60)
        print(f"[5] exit frame routed OK: {sid} -> {st}")
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
