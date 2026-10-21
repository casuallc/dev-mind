# -*- coding: utf-8 -*-
"""CAP-80 M4 任务管理 + runner 执行 E2E（自起隔离 app + 真实 runner，exec 帧族全链）。

运行前提：
  1. `mvn -q install -DskipTests`（devmind-app 可 spring-boot:run、runner jar 已构建）；
  2. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium jdk-21.0.12.101 本机路径。

脚本自起：隔离 app（:18099，--spring.profiles.active=e2e 独立 H2 落 tmp/cap80-runner-e2e/）
+ 真实 runner（executor=fake 不影响 exec 帧；execAllowlist=echo 过非空防线，命令全走 shell
内建豁免）。跑完杀双进程，不留残留。

验收覆盖（CAP-80 §9 M4 段）：
  [1] types 端点：builtin.shell 标 RUNNER、builtin.echo 标 LOCAL
  [2] REST 创建 IMMEDIATE（echo）→ 执行 DONE / run SUCCESS
  [3] REST 创建 DELAY（未来时刻）→ ACTIVE 且 nextRunAt 就位；cancel 清场
  [4] REST 创建 FIXED_DELAY / CRON 合法形态 → 200；非法 interval/cron/payload/type → 400
  [5] RUNNER 类任务指定不存在节点 → 400
  [6] builtin.shell 指定在线节点执行成功：exit 0 → run SUCCESS、runner 行流落库可查
  [7] 非零退出按策略重试：attempt 1→2 两个 FAILED（同轮次）→ 任务 DONE
  [8] 无可用节点（未指定且无平台默认）→ run FAILED 且 error 可读
  [9] 编辑：终态任务 409；ACTIVE 任务改名 200 且 nextRunAt 重排
  [10] LOCAL 类任务带 agentNodeId 创建 → 忽略不落库
  [11] 非 admin：POST/PUT/types 全 403
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("E2E_PORT", "18099"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap80-runner-e2e"
APP_LOG = TMP / "app.log"
RUNNER_LOG = TMP / "runner.log"
PROPS = TMP / "agent.properties"
RUNNER_JAR = ROOT / "devmind-agent-runner" / "target" / "devmind-agent-runner.jar"
MARK = f"cap80m4-{int(time.time())}"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe")

passed, failed = [], []


def check(name, cond, detail=""):
    (passed if cond else failed).append(name)
    print(f"  {'PASS' if cond else 'FAIL'}  {name}" + ("" if cond else f"  {detail}"))


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method,
                               headers={"Content-Type": "application/json"})
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        payload = e.read().decode("utf-8", "replace")
        if e.code != expect:
            raise AssertionError(f"{method} {path} -> {e.code} (expect {expect}): {payload[:400]}")
        try:
            return json.loads(payload or "null")
        except json.JSONDecodeError:
            return payload


def wait_health(timeout=150):
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
            subprocess.run(["taskkill", "/F", "/PID", line.split()[-1]], capture_output=True)


def start_app():
    log = open(APP_LOG, "w", encoding="utf-8")
    args = (
        f"--server.port={PORT} "
        f"--spring.datasource.url=jdbc:h2:file:{(TMP / 'db/devmind').as_posix()};AUTO_SERVER=TRUE "
        f"--devmind.task.tick-delay-ms=2000 "
        f"--devmind.task.tick-initial-delay-ms=2000"
    )
    # mvn 必须 cmd /c 起（Windows 上 mvn 是 cmd 脚本）；参数值均无空格
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{args}"')
    env = dict(os.environ, JAVA_HOME=JAVA_HOME)
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_health():
        log.close()
        raise AssertionError(f"app 150s 内未起来（看 {APP_LOG}）")
    return proc, log


def start_runner(node_token):
    PROPS.write_text(
        f"serverUrl=ws://localhost:{PORT}/ws/agent\n"
        f"token={node_token}\n"
        f"executor=fake\n"
        # 本机常驻 runner 服务已占默认 gitProxyPort（18377）→ 本用例不需要 git 代理，显式关闭
        f"gitProxyEnabled=false\n"
        f"execAllowlist=echo\n"
        f"workDir={(TMP / 'runner-work').as_posix()}\n"
        f"workspaceRoot={(TMP / 'runner-ws').as_posix()}\n"
        f"maxConcurrent=2\n",
        encoding="utf-8")
    log = open(RUNNER_LOG, "w", encoding="utf-8")
    proc = subprocess.Popen([JAVA, "-jar", str(RUNNER_JAR), str(PROPS)],
                            stdout=log, stderr=subprocess.STDOUT)
    return proc, log


def poll_task(token, task_id, want, timeout=40):
    deadline = time.time() + timeout
    t = None
    while time.time() < deadline:
        t = req("GET", f"/async-tasks/{task_id}", token=token)
        if t["status"] == want:
            return t
        time.sleep(1)
    return t


def poll_runs(token, task_id, pred, timeout=60):
    deadline = time.time() + timeout
    runs = []
    while time.time() < deadline:
        runs = req("GET", f"/async-tasks/{task_id}/runs?size=50", token=token)["items"]
        if pred(runs):
            return runs
        time.sleep(1)
    return runs


def main():
    if not RUNNER_JAR.exists():
        raise AssertionError("runner jar 缺失，先 mvn -q install -DskipTests")
    if TMP.exists():
        shutil.rmtree(TMP, ignore_errors=True)
    TMP.mkdir(parents=True, exist_ok=True)

    app, app_log = start_app()
    runner, runner_log = None, None
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]

        # 节点 + runner（不设平台默认：无节点用例依赖「无默认→409」）
        issued = req("POST", "/agent-nodes", {"name": MARK}, token)
        node, node_token = issued["node"], issued["token"]
        runner, runner_log = start_runner(node_token)
        deadline = time.time() + 40
        online = False
        while time.time() < deadline:
            nodes = req("GET", "/agent-nodes", token=token)
            if any(n["id"] == node["id"] and n["status"] == "ONLINE" for n in nodes):
                online = True
                break
            time.sleep(1)
        if not online:
            raise AssertionError(f"runner 40s 内未上线（看 {RUNNER_LOG}）")
        node_id = str(node["id"])
        print(f"[0] app + runner 上线（节点 {node_id}）")

        # [1] types 端点
        types = {t["type"]: t for t in req("GET", "/async-tasks/types", token=token)}
        check("1 types 含 builtin.shell 标 RUNNER",
              types.get("builtin.shell", {}).get("executor") == "RUNNER",
              f"types={list(types)}")
        check("1 types 含 builtin.echo 标 LOCAL",
              types.get("builtin.echo", {}).get("executor") == "LOCAL")

        # [2] REST 创建 IMMEDIATE（echo LOCAL）
        t2 = req("POST", "/async-tasks", {
            "name": f"{MARK}-即时", "type": "builtin.echo",
            "payload": json.dumps({"message": MARK}), "scheduleKind": "IMMEDIATE",
        }, token)
        t = poll_task(token, t2["id"], "DONE")
        check("2 REST 创建即时任务跑完转 DONE", t and t["status"] == "DONE",
              f"status={t and t['status']}")

        # [3] REST 创建 DELAY（+2h 不触发，验形态后取消）
        run_at = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(time.time() + 7200))
        t3 = req("POST", "/async-tasks", {
            "name": f"{MARK}-定时", "type": "builtin.echo",
            "payload": "{}", "scheduleKind": "DELAY", "runAt": run_at,
        }, token)
        t = req("GET", f"/async-tasks/{t3['id']}", token=token)
        check("3 DELAY 任务 ACTIVE 且 nextRunAt 就位",
              t["status"] == "ACTIVE" and t.get("nextRunAt") is not None,
              f"status={t['status']} nextRunAt={t.get('nextRunAt')}")
        req("POST", f"/async-tasks/{t3['id']}/cancel", token=token)

        # [4] FIXED_DELAY / CRON 合法 + 四类 400
        t4a = req("POST", "/async-tasks", {
            "name": f"{MARK}-间隔", "type": "builtin.echo", "payload": "{}",
            "scheduleKind": "FIXED_DELAY", "intervalMs": 3600000,
        }, token)
        check("4 FIXED_DELAY 创建成功且 ACTIVE",
              req("GET", f"/async-tasks/{t4a['id']}", token=token)["status"] == "ACTIVE")
        t4b = req("POST", "/async-tasks", {
            "name": f"{MARK}-cron", "type": "builtin.echo", "payload": "{}",
            "scheduleKind": "CRON", "cronExpr": "0 0 3 * * *",
        }, token)
        check("4 CRON 创建成功且 ACTIVE",
              req("GET", f"/async-tasks/{t4b['id']}", token=token)["status"] == "ACTIVE")
        req("POST", "/async-tasks", {"name": "x", "type": "no.such.type", "payload": "{}"},
            token, expect=400)
        check("4 未知 type 400", True)
        req("POST", "/async-tasks", {"name": "x", "type": "builtin.echo", "payload": "{not json"},
            token, expect=400)
        check("4 非法 payload JSON 400", True)
        req("POST", "/async-tasks", {"name": "x", "type": "builtin.echo", "payload": "{}",
                                     "scheduleKind": "FIXED_DELAY", "intervalMs": 0},
            token, expect=400)
        check("4 非法 intervalMs 400", True)
        req("POST", "/async-tasks", {"name": "x", "type": "builtin.echo", "payload": "{}",
                                     "scheduleKind": "CRON", "cronExpr": "not a cron"},
            token, expect=400)
        check("4 非法 cron 400", True)

        # [5] RUNNER 类指定不存在节点 400
        req("POST", "/async-tasks", {
            "name": "x", "type": "builtin.shell",
            "payload": json.dumps({"command": "echo hi"}), "agentNodeId": "99999999",
        }, token, expect=400)
        check("5 builtin.shell 指定不存在节点 400", True)

        # [6] builtin.shell 指定在线节点执行成功
        shell_mark = f"CAP80-RUNNER-MARK-{int(time.time())}"
        t6 = req("POST", "/async-tasks", {
            "name": f"{MARK}-shell", "type": "builtin.shell",
            "payload": json.dumps({"command": f"echo {shell_mark}"}),
            "scheduleKind": "IMMEDIATE", "agentNodeId": node_id,
        }, token)
        check("6 任务记录持久化 agentNodeId",
              req("GET", f"/async-tasks/{t6['id']}", token=token).get("agentNodeId") == node_id)
        runs = poll_runs(token, t6["id"], lambda rs: any(r["status"] == "SUCCESS" for r in rs))
        ok_run = next((r for r in runs if r["status"] == "SUCCESS"), None)
        check("6 builtin.shell 在 runner 上 exit 0 → run SUCCESS", ok_run is not None,
              f"runs={[(r['status'], r.get('error')) for r in runs]}")
        detail = req("GET", f"/async-task-runs/{ok_run['id']}", token=token) if ok_run else {}
        check("6 runner 行流实时入任务日志（marker 落库）",
              shell_mark in (detail.get("logs") or ""), f"logs={(detail.get('logs') or '')[-200:]}")

        # [7] 非零退出按策略重试（exit 3，maxAttempts=2）
        t7 = req("POST", "/async-tasks", {
            "name": f"{MARK}-shell-fail", "type": "builtin.shell",
            "payload": json.dumps({"command": "echo oops\nexit 3"}),
            "scheduleKind": "IMMEDIATE", "agentNodeId": node_id,
            "retryMaxAttempts": 2, "retryBackoff": "FIXED", "retryIntervalMs": 2000,
        }, token)
        runs = poll_runs(token, t7["id"],
                         lambda rs: len([r for r in rs if r["status"] == "FAILED"]) >= 2, timeout=90)
        attempts = sorted(r["attempt"] for r in runs if r["status"] == "FAILED")
        check("7 非零退出产生 attempt 1→2 两个 FAILED run", attempts == [1, 2],
              f"attempts={attempts}")
        check("7 两次尝试同轮次（runSeq 不变）", len({r["runSeq"] for r in runs}) == 1)
        t = poll_task(token, t7["id"], "DONE", timeout=20)
        check("7 重试耗尽后任务 DONE", t and t["status"] == "DONE", f"status={t and t['status']}")
        err = next((r for r in runs if r["status"] == "FAILED"), {})
        check("7 失败摘要落 error 列（含退出码）", "exit=3" in (err.get("error") or ""),
              f"error={err.get('error')}")

        # [8] 无可用节点（未指定 + 无平台默认）→ FAILED 且 error 可读
        t8 = req("POST", "/async-tasks", {
            "name": f"{MARK}-shell-no-node", "type": "builtin.shell",
            "payload": json.dumps({"command": "echo hi"}),
            "scheduleKind": "IMMEDIATE", "retryMaxAttempts": 1,
        }, token)
        runs = poll_runs(token, t8["id"], lambda rs: any(r["status"] == "FAILED" for r in rs), timeout=40)
        fail_run = next((r for r in runs if r["status"] == "FAILED"), None)
        check("8 无可用节点 run FAILED 且 error 可读",
              fail_run is not None and "无可用执行节点" in (fail_run.get("error") or ""),
              f"runs={[(r['status'], r.get('error')) for r in runs]}")

        # [9] 编辑：终态 409 / ACTIVE 改名 200 且重排
        req("PUT", f"/async-tasks/{t2['id']}",
            {"name": "x", "type": "builtin.echo", "payload": "{}"}, token, expect=409)
        check("9 终态任务编辑 409", True)
        req("PUT", f"/async-tasks/{t4a['id']}", {
            "name": f"{MARK}-间隔-改名", "type": "builtin.echo", "payload": "{}",
            "scheduleKind": "FIXED_DELAY", "intervalMs": 7200000,
        }, token)
        t = req("GET", f"/async-tasks/{t4a['id']}", token=token)
        check("9 ACTIVE 任务编辑 200：改名生效且 nextRunAt 重排",
              t["name"].endswith("改名") and t.get("nextRunAt") is not None
              and t.get("intervalMs") == 7200000,
              f"name={t['name']} intervalMs={t.get('intervalMs')} nextRunAt={t.get('nextRunAt')}")
        req("POST", f"/async-tasks/{t4a['id']}/cancel", token=token)
        req("POST", f"/async-tasks/{t4b['id']}/cancel", token=token)

        # [10] LOCAL 类带 agentNodeId → 忽略不落库
        t10 = req("POST", "/async-tasks", {
            "name": f"{MARK}-local-ignore", "type": "builtin.echo", "payload": "{}",
            "scheduleKind": "DELAY", "runAt": run_at, "agentNodeId": node_id,
        }, token)
        check("10 LOCAL 任务 agentNodeId 忽略不落库",
              req("GET", f"/async-tasks/{t10['id']}", token=token).get("agentNodeId") is None)
        req("POST", f"/async-tasks/{t10['id']}/cancel", token=token)

        # [11] 非 admin 写面 403
        req("POST", "/auth/users",
            {"username": "cap80m4viewer", "password": "viewer123", "role": "DEVELOPER"}, token=token)
        viewer = req("POST", "/auth/login",
                     {"username": "cap80m4viewer", "password": "viewer123"})["accessToken"]
        req("POST", "/async-tasks", {"name": "x", "type": "builtin.echo", "payload": "{}"},
            viewer, expect=403)
        check("11 非 admin 创建任务 403", True)
        req("PUT", f"/async-tasks/{t2['id']}",
            {"name": "x", "type": "builtin.echo", "payload": "{}"}, viewer, expect=403)
        check("11 非 admin 编辑任务 403", True)
        req("GET", "/async-tasks/types", token=viewer, expect=403)
        check("11 非 admin 查 types 403", True)
    finally:
        if runner:
            kill_tree(runner.pid)
        if runner_log:
            runner_log.close()
        kill_tree(app.pid)
        time.sleep(2)
        kill_port_strays()
        app_log.close()
        wait_port_free()

    print(f"\n== 结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print(f"  - {f}")
        sys.exit(1)


if __name__ == "__main__":
    main()
