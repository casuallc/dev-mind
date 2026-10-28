# -*- coding: utf-8 -*-
"""CAP-80 通用异步任务框架 E2E（自起隔离 app，无需 runner——任务在服务端虚拟线程执行）。

运行前提：
  1. `mvn -q install -DskipTests`（devmind-app 可 spring-boot:run）；
  2. 本机有 node（WS 验证走 tests/legacy/ws/cap80-ws-test.mjs，Node 22+ 内置 WebSocket）；
  3. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium jdk-21.0.12.101 本机路径。

脚本自起：隔离 app（:18098，--spring.profiles.active=e2e 独立 H2 落 tmp/cap80-e2e/），
e2e profile 的 E2eSeedConfig 预置五个任务（即时回显 / 30s 长睡眠实时窗口 / 10s 固定间隔
/ 重试失败×2 / 不可重试失败）。跑完杀进程，不留残留。

验收覆盖（CAP-80 §验收）：
  [1] 种子任务全部可见（列表 + 形态/状态字段）
  [2] 即时任务被 tick 认领执行：run SUCCESS、日志含 echo 行、任务 DONE（M1 内核）
  [3] WS 实时 attach：RUNNING 窗口连接 /ws/async-tasks/{runId} 收到 log/done 帧（M3）
  [4] WS 快照回放：终态 run 连上先收 snapshot（含历史日志）再收 done
  [5] 重试：attempt 1 FAILED → 退避重排 attempt 2 → 仍 FAILED → 轮次结算任务 DONE（FR-04）
  [6] 不可重试失败：一次 FAILED 即终，任务 DONE，不产生 attempt 2
  [7] 生命周期：pause → trigger 409 → resume → trigger 200 → cancel（QUEUED 级联 CANCELLED，
      终态后再 trigger 409）（FR-10）
  [8] 权限：非 admin 列表只见本人（种子属主是 local→空）、他人任务详情/run 详情 404、
      控制操作 403（SecurityConfig POST 规则）
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

ROOT = Path(__file__).resolve().parents[3]
PORT = int(os.environ.get("E2E_PORT", "18098"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap80-e2e"
APP_LOG = TMP / "app.log"
WS_SCRIPT = ROOT / "tests" / "legacy" / "ws" / "cap80-ws-test.mjs"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

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
            pid = line.split()[-1]
            subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)


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


def stop_app(proc, log):
    kill_tree(proc.pid)
    time.sleep(2)
    kill_port_strays()
    log.close()
    wait_port_free()


def find_task(token, name):
    page = req("GET", "/async-tasks?size=100", token=token)
    for t in page["items"]:
        if t["name"] == name:
            return t
    return None


def poll_task_status(token, task_id, want, timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        t = req("GET", f"/async-tasks/{task_id}", token=token)
        if t["status"] == want:
            return t
        time.sleep(1)
    return req("GET", f"/async-tasks/{task_id}", token=token)


def task_runs(token, task_id):
    return req("GET", f"/async-tasks/{task_id}/runs?size=50", token=token)["items"]


def poll_runs(token, task_id, pred, timeout=40):
    deadline = time.time() + timeout
    runs = []
    while time.time() < deadline:
        runs = task_runs(token, task_id)
        if pred(runs):
            return runs
        time.sleep(1)
    return runs


def ws_check(run_id, mode):
    p = subprocess.run(["node", str(WS_SCRIPT), str(PORT), run_id, mode],
                       capture_output=True, timeout=60)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    return "WS_OK" in out, out


def main():
    if TMP.exists():
        shutil.rmtree(TMP, ignore_errors=True)
    TMP.mkdir(parents=True, exist_ok=True)

    proc, log = start_app()
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]

        # [1] 种子任务可见
        t_immediate = find_task(token, "e2e-即时回显")
        t_live = find_task(token, "e2e-实时窗口")
        t_retry = find_task(token, "e2e-重试失败")
        t_nonretry = find_task(token, "e2e-不可重试")
        t_periodic = find_task(token, "e2e-周期回显")
        check("1 种子任务五个全部登记可见",
              all([t_immediate, t_live, t_retry, t_nonretry, t_periodic]))
        check("1 周期任务形态 FIXED_DELAY 且 ACTIVE",
              t_periodic and t_periodic["scheduleKind"] == "FIXED_DELAY"
              and t_periodic["status"] == "ACTIVE")

        # [2] 即时任务跑成功
        t = poll_task_status(token, t_immediate["id"], "DONE", timeout=40)
        check("2 即时任务执行完成转 DONE", t["status"] == "DONE", f"status={t['status']}")
        runs = task_runs(token, t_immediate["id"])
        ok_run = next((r for r in runs if r["status"] == "SUCCESS"), None)
        check("2 即时任务 run SUCCESS 且日志含 echo 完成",
              ok_run and "echo 完成" in (ok_run.get("logs") or ""))
        check("2 任务 runCount=1（一次性 max_runs=1）", t["runCount"] == 1)

        # [3] WS 实时 attach（30s 窗口任务此刻应在 RUNNING）
        live_runs = poll_runs(token, t_live["id"], lambda rs: any(r["status"] == "RUNNING" for r in rs), timeout=20)
        live_run = next((r for r in live_runs if r["status"] == "RUNNING"), None)
        if live_run is None:
            # 已跑完则退化为快照断言（慢机器上 30s 可能耗尽）
            live_run = next((r for r in live_runs if r["status"] == "SUCCESS"), None)
        check("3 实时窗口任务产生了 run", live_run is not None, f"runs={[(r['status']) for r in live_runs]}")
        if live_run and live_run["status"] == "RUNNING":
            ok, out = ws_check(live_run["id"], "live")
            check("3 WS 实时流收到 log/done 帧", ok, out[-300:])
            t2 = poll_task_status(token, t_live["id"], "DONE", timeout=45)
            check("3 实时窗口任务终态 DONE", t2["status"] == "DONE", f"status={t2['status']}")
        elif live_run:
            ok, out = ws_check(live_run["id"], "replay")
            check("3 WS（实时窗口已关闭，退化快照回放）", ok, out[-300:])

        # [4] WS 快照回放（终态 run）
        if ok_run:
            ok, out = ws_check(ok_run["id"], "replay")
            check("4 终态 run WS 先 snapshot（含日志）后 done", ok, out[-300:])

        # [5] 重试链：两个 FAILED attempt 后任务 DONE
        retry_runs = poll_runs(token, t_retry["id"],
                               lambda rs: len([r for r in rs if r["status"] == "FAILED"]) >= 2, timeout=60)
        failed_attempts = sorted([r["attempt"] for r in retry_runs if r["status"] == "FAILED"])
        check("5 重试任务产生 attempt 1→2 两个 FAILED run", failed_attempts == [1, 2],
              f"attempts={failed_attempts}")
        check("5 两次尝试同轮次（runSeq 不变）",
              len({r["runSeq"] for r in retry_runs}) == 1)
        t = poll_task_status(token, t_retry["id"], "DONE", timeout=20)
        check("5 重试耗尽后任务 DONE", t["status"] == "DONE", f"status={t['status']}")
        err = next((r for r in retry_runs if r["status"] == "FAILED"), None)
        check("5 失败摘要落 error 列", err and "echo 故意失败" in (err.get("error") or ""))

        # [6] 不可重试：一次 FAILED 即终
        nr_runs = poll_runs(token, t_nonretry["id"],
                            lambda rs: any(r["status"] == "FAILED" for r in rs), timeout=40)
        check("6 不可重试失败只跑一次", len(nr_runs) == 1 and nr_runs[0]["attempt"] == 1,
              f"runs={[(r['attempt'], r['status']) for r in nr_runs]}")
        t = poll_task_status(token, t_nonretry["id"], "DONE", timeout=20)
        check("6 不可重试任务 DONE", t["status"] == "DONE", f"status={t['status']}")

        # [7] 生命周期：pause/trigger-409/resume/trigger-200/cancel 级联
        pid = t_periodic["id"]
        req("POST", f"/async-tasks/{pid}/pause", token=token)
        t = req("GET", f"/async-tasks/{pid}", token=token)
        check("7 pause 后 PAUSED", t["status"] == "PAUSED", f"status={t['status']}")
        req("POST", f"/async-tasks/{pid}/trigger", token=token, expect=409)
        check("7 PAUSED 任务 trigger 409", True)
        req("POST", f"/async-tasks/{pid}/resume", token=token)
        t = req("GET", f"/async-tasks/{pid}", token=token)
        check("7 resume 后 ACTIVE 且 nextRunAt 就位",
              t["status"] == "ACTIVE" and t.get("nextRunAt") is not None)
        req("POST", f"/async-tasks/{pid}/trigger", token=token)
        check("7 ACTIVE 任务 trigger 200", True)
        req("POST", f"/async-tasks/{pid}/cancel", token=token)
        t = req("GET", f"/async-tasks/{pid}", token=token)
        check("7 cancel 后 CANCELLED 且 nextRunAt 清空",
              t["status"] == "CANCELLED" and t.get("nextRunAt") is None,
              f"status={t['status']} nextRunAt={t.get('nextRunAt')}")
        req("POST", f"/async-tasks/{pid}/trigger", token=token, expect=409)
        check("7 终态任务再 trigger 409", True)
        pruns = task_runs(token, pid)
        open_runs = [r for r in pruns if r["status"] in ("QUEUED", "RUNNING")]
        check("7 cancel 级联：无滞留未终态 run", len(open_runs) == 0,
              f"open={[(r['status']) for r in open_runs]}")

        # [8] 权限：非 admin 只见本人、他人详情 404、控制 403
        req("POST", "/auth/users",
            {"username": "cap80viewer", "password": "viewer123", "role": "DEVELOPER"}, token=token)
        viewer = req("POST", "/auth/login", {"username": "cap80viewer", "password": "viewer123"})["accessToken"]
        page = req("GET", "/async-tasks?size=100", token=viewer)
        check("8 非 admin 列表按 actor 过滤（种子属主非本人 → 空）", page["total"] == 0,
              f"total={page['total']}")
        req("GET", f"/async-tasks/{t_immediate['id']}", token=viewer, expect=404)
        check("8 非 admin 看他人任务详情 404", True)
        req("GET", f"/async-task-runs/{ok_run['id']}", token=viewer, expect=404)
        check("8 非 admin 看他人 run 详情 404", True)
        req("POST", f"/async-tasks/{t_immediate['id']}/trigger", token=viewer, expect=403)
        check("8 非 admin 控制操作 403（过滤器链 ADMIN 规则）", True)
        run_detail = req("GET", f"/async-task-runs/{ok_run['id']}", token=token)
        check("8 admin 可查 run 详情（含日志）", "echo 完成" in (run_detail.get("logs") or ""))

        # 执行记录横查（admin）
        all_runs = req("GET", "/async-task-runs?size=100", token=token)
        check("8 执行记录横查可见全部 run", all_runs["total"] >= 6, f"total={all_runs['total']}")
        req("GET", "/async-task-runs?size=100", token=viewer, expect=403)
        check("8 非 admin 横查执行记录 403", True)
    finally:
        stop_app(proc, log)

    print(f"\n== 结果：{len(passed)} PASS / {len(failed)} FAIL")
    if failed:
        print("失败项：")
        for f in failed:
            print(f"  - {f}")
        sys.exit(1)


if __name__ == "__main__":
    main()
