# -*- coding: utf-8 -*-
r"""CAP-93 AI 生成 OKR（草稿确认）E2E（自起隔离 app + fake runner，全程无需外部服务）。

运行前提：`mvn -q install -DskipTests`（devmind-app 走 .m2 jar + devmind-agent-runner.jar）；
JDK 21（JAVA_HOME 未设时回落本机 Adoptium 路径）。
脚本自起隔离 app（:18107，--spring.profiles.active=e2e 独立 H2，定时开关全关）+ fake runner
（executor=fake，worklogRoot 落 tmp/）。fake 不写 .devmind/output —— 会话运行期间由脚本预写
产物（runner 同机），退出时 OutputUploader 自动回传，验证服务端镜像→草稿链路。跑完全杀。

验收覆盖（CAP-93 §9）：
  [1] 场景种子 worklog-okr-suggest 存在
  [2] 生成链路：suggest(QUARTER) 起会话 → 预写 okr-suggest-<period>.md → 草稿落库
      （O/KR 两级、权重、父O#id 解析）→ prompt 携带素材与年度 O 清单
  [3] 采纳：编辑后（删一条 KR）两阶段建 O→KR，季度 O 挂指定年度 O；重复采纳 400
  [4] 幂等：目标周期已有目标非 force → reused=true 不起会话
  [5] 重生成 force：草稿（含已采纳）被覆盖回 DRAFT；丢弃 → DISCARDED；重复丢弃幂等
      （「已采纳不可丢弃 400」由单测覆盖：草稿按 (user,period) upsert，重生成即覆盖采纳态）
  [6] 护栏：无素材周期 400；非法 scope 400
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("E2E_PORT", "18107"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap93-e2e"
APP_LOG = TMP / "app.log"
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
RUNNER_LOG = TMP / "runner.log"
WORKLOG_WS = TMP / "worklog-ws"          # runner worklogRoot（会话工作区 {worklogRoot}/<owner>）

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe") if (Path(JAVA_HOME) / "bin/java.exe").is_file() else "java"

TODAY = date.today()
Y = TODAY.year
Q = (TODAY.month - 1) // 3 + 1
# 目标周期 = 下一季度（素材取当前季度）；NQ_YEAR = 下一季度所属年
NQ = f"{Y}-Q{Q + 1}" if Q < 4 else f"{Y + 1}-Q1"
NQ_YEAR = Y if Q < 4 else Y + 1
EMPTY_YEAR = str(Y + 5)                  # 无素材的远年（400 用）

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
            subprocess.run(["taskkill", "/F", "/PID", line.split()[-1]], capture_output=True)


def _force_rmtree(path):
    def _onerror(func, p, _exc):
        os.chmod(p, 0o666)
        func(p)
    if os.path.exists(path):
        shutil.rmtree(path, onerror=_onerror)


APP_ARGS = (
    f'--server.port={PORT} '
    f'--devmind.project.workspace-root={(TMP / "repositories").as_posix()} '
    f'--devmind.worktree.root={(TMP / "worktrees").as_posix()} '
    f'--devmind.attachment.root-dir={(TMP / "attachments").as_posix()} '
    f'--devmind.worklog.repo-root={(TMP / "repos").as_posix()} '
    f'--devmind.worklog.daily-enabled=false '
    f'--devmind.worklog.weekly-enabled=false '
    f'--devmind.worklog.git-import-enabled=false'
)


def start_app():
    log = open(APP_LOG, "w", encoding="utf-8")
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["SPRING_DATASOURCE_URL"] = f'jdbc:h2:file:{(TMP / "db/devmind").as_posix()};AUTO_SERVER=TRUE'
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{APP_ARGS}"')
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=env)
    if not wait_health():
        log.close()
        raise AssertionError(f"app 180s 内未起来（看 {APP_LOG}）")
    return proc, log


def stop_app(proc, log):
    kill_tree(proc.pid)
    time.sleep(2)
    kill_port_strays()
    log.close()
    wait_port_free()


def drive_session(sid, token, timeout=90):
    """fake-agent：喂一条输入 → WAITING_INPUT 即回合完成 → finish（CAP-33：worklog 场景
    带 60s 自动结束，见到 WAITING_INPUT 立即 finish，不无限轮询）。"""
    req("POST", f"/sessions/{sid}/input", {"text": "go"}, token)
    deadline = time.time() + timeout
    while time.time() < deadline:
        st = req("GET", f"/sessions/{sid}", token=token).get("status")
        if st == "WAITING_INPUT":
            break
        if st == "WAITING_AUTH":
            req("POST", f"/sessions/{sid}/authorize",
                {"accepted": True, "scope": "once", "requestId": "perm-fake"}, token)
        time.sleep(1)
    req("POST", f"/sessions/{sid}/finish", token=token)


def write_okr_output(sid, period, content):
    """模拟 agent 写产出：worklog 持久工作区 {worklogRoot}/admin/.devmind/output/okr-suggest-<period>.md"""
    target = WORKLOG_WS / "admin"
    deadline = time.time() + 60
    while time.time() < deadline and not target.is_dir():
        time.sleep(1)
    assert target.is_dir(), f"未找到 worklog 会话工作区: {target}（看 {RUNNER_LOG}）"
    out = target / ".devmind" / "output"
    out.mkdir(parents=True, exist_ok=True)
    (out / f"okr-suggest-{period}.md").write_text(content, encoding="utf-8")


def wait_draft(token, period, sid, timeout=60):
    """轮询草稿：等本次会话的镜像落地（sessionId 对得上才算，防重跑拿到上一轮草稿）。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        d = req("GET", f"/worklog/goals/suggest/draft?period={period}", token=token)
        if d and d.get("exists") and d.get("sessionId") == sid:
            return d
        time.sleep(2)
    return None


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not RUNNER_JAR.is_file():
        print(f"缺少 {RUNNER_JAR}，先 mvn -q install -DskipTests")
        sys.exit(1)

    _force_rmtree(TMP)
    TMP.mkdir(parents=True, exist_ok=True)
    kill_port_strays()

    app, app_log = start_app()
    runner, runner_log, node_id, token = None, None, None, None
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        print(f"[0] app 已起（:{PORT}），admin 登录成功（目标季度 {NQ}，年度 {NQ_YEAR}）")

        # ---------- [1] 场景种子 ----------
        scenarios = req("GET", "/scenarios", token=token)
        okr_sc = next((s for s in scenarios if s.get("code") == "worklog-okr-suggest"), None)
        check("场景 worklog-okr-suggest 已种子", okr_sc is not None,
              json.dumps([s.get("code") for s in scenarios], ensure_ascii=False)[:300])

        # ---------- 素材 + runner ----------
        year_o = req("POST", "/worklog/goals",
                     {"scope": "YEAR", "period": str(NQ_YEAR), "kind": "OBJECTIVE",
                      "title": "年度目标：平台化跃迁"}, token)
        req("POST", "/worklog/entries",
            {"workDate": TODAY.isoformat(), "title": "CAP93素材条目", "hours": 2.0,
             "entryType": "DEV"}, token)

        issued = req("POST", "/agent-nodes", {"name": f"e2e-cap93-{int(time.time())}"}, token)
        node_id, node_token = issued["node"]["id"], issued["token"]
        req("POST", f"/agent-nodes/{node_id}/default", None, token)
        props = TMP / "runner.properties"
        props.write_text(
            f"serverUrl=ws://localhost:{PORT}/ws/agent\ntoken={node_token}\nexecutor=fake\n"
            f"workDir={(TMP / 'runner-work').as_posix()}\nworkspaceRoot={(TMP / 'ws').as_posix()}\n"
            f"worklogRoot={WORKLOG_WS.as_posix()}\nmaxConcurrent=4\ngcDays=7\n"
            # 本机常驻 runner 占着默认 gitProxyPort 18377，隔离实例必须关掉 git 代理
            f"gitProxyEnabled=false\n",
            encoding="utf-8")
        runner_log = open(RUNNER_LOG, "w", encoding="utf-8")
        runner = subprocess.Popen([JAVA, "-jar", str(RUNNER_JAR), str(props)],
                                  stdout=runner_log, stderr=subprocess.STDOUT)
        online = False
        for _ in range(60):
            nodes = req("GET", "/agent-nodes", token=token)
            if any(n.get("id") == node_id and n.get("status") == "ONLINE" for n in (nodes or [])):
                online = True
                break
            time.sleep(1)
        check("runner 节点上线", online, f"看 {RUNNER_LOG}")
        if not online:
            raise AssertionError("runner 未上线，生成链路无法验证")
        req("POST", "/worklog/workspace/ensure", {}, token)

        # ---------- [2] 生成链路：suggest → 预写产物 → 草稿 ----------
        ack = req("POST", "/worklog/goals/suggest",
                  {"scope": "QUARTER", "period": NQ, "hint": "重点是平台化"}, token)
        sid = ack.get("sessionId")
        check("suggest 起会话", bool(sid) and ack.get("reused") is False, str(ack))
        sd = req("GET", f"/sessions/{sid}", token=token)
        spec = sd.get("taskSpec") or ""
        check("prompt 携带素材（条目/年度O清单/补充说明）",
              "CAP93素材条目" in spec and f"O#{year_o['id']}" in spec and "重点是平台化" in spec,
              spec[:400])

        write_okr_output(sid, NQ,
                         "- [O] 交付平台化改造（权重 50%）（父O#" + str(year_o["id"]) + "）\n"
                         "  - [KR] 完成核心链路迁移（权重 30%）\n"
                         "  - [KR] 自动化覆盖率提升到 80%\n"
                         "- [O] 稳定性提升（权重 40%）\n"
                         "  - [KR] 线上故障数下降 50%\n")
        drive_session(sid, token)
        draft = wait_draft(token, NQ, sid)
        check("草稿落库（exists + sessionId 追溯）", draft is not None, f"看 {APP_LOG}/{RUNNER_LOG}")
        if draft is None:
            raise AssertionError("草稿未落库，后续用例无法继续")
        objs = draft.get("objectives") or []
        check("草稿解析 2 个 O", len(objs) == 2, json.dumps(draft, ensure_ascii=False)[:400])
        o1 = objs[0] if objs else {}
        check("O 权重与父O#id 解析",
              o1.get("weight") == 50 and o1.get("parentYearGoalId") == year_o["id"]
              and len(o1.get("krs") or []) == 2,
              json.dumps(o1, ensure_ascii=False)[:300])
        check("草稿状态 DRAFT + scope 推导 QUARTER",
              draft.get("status") == "DRAFT" and draft.get("scope") == "QUARTER", str(draft)[:200])

        # ---------- [3] 采纳（编辑后：删掉 O1 的第二条 KR） ----------
        adopt_body = [
            {"title": o1["title"], "weight": o1["weight"], "parentYearGoalId": o1["parentYearGoalId"],
             "krs": [o1["krs"][0]]},
            {"title": objs[1]["title"], "weight": objs[1]["weight"], "krs": objs[1]["krs"]},
        ]
        r = req("POST", "/worklog/goals/suggest/adopt",
                {"draftId": draft["id"], "objectives": adopt_body}, token)
        check("采纳创建 4 项（2O+2KR，删掉 1KR）", r.get("created") == 4, json.dumps(r)[:200])
        created_o = next((g for g in r["goals"] if g["title"] == "交付平台化改造"), None)
        check("季度 O 挂指定年度 O", created_o and created_o.get("parentId") == year_o["id"],
              json.dumps(created_o)[:200])
        tree = req("GET", f"/worklog/goals/tree?year={NQ_YEAR}", token=token)
        qs = next((q for q in tree["quarters"] if q["period"] == NQ), None)
        check("目标树季度区含新 OKR（2O，KR 数 1+1）",
              qs is not None and len(qs["objectives"]) == 2
              and sorted(len(n["krs"]) for n in qs["objectives"]) == [1, 1],
              json.dumps(qs, ensure_ascii=False)[:300])
        d_after = req("GET", f"/worklog/goals/suggest/draft?period={NQ}", token=token)
        check("采纳后草稿置 ADOPTED", d_after.get("status") == "ADOPTED", str(d_after)[:200])
        req("POST", "/worklog/goals/suggest/adopt",
            {"draftId": draft["id"], "objectives": adopt_body}, token, expect=400)
        check("重复采纳 400", True)

        # ---------- [4] 幂等：目标周期已有目标非 force → reused ----------
        ack2 = req("POST", "/worklog/goals/suggest", {"scope": "QUARTER", "period": NQ}, token)
        check("已有目标非 force reused=true（不起会话）",
              ack2.get("reused") is True and not ack2.get("sessionId"), json.dumps(ack2)[:200])

        # ---------- [5] force 重生成覆盖草稿回 DRAFT → 丢弃 ----------
        ack3 = req("POST", "/worklog/goals/suggest",
                   {"scope": "QUARTER", "period": NQ, "force": True}, token)
        sid2 = ack3.get("sessionId")
        check("force 重生成起会话", bool(sid2), str(ack3))
        write_okr_output(sid2, NQ,
                         "- [O] 重生成目标（权重 100%）\n  - [KR] 重生成的关键结果\n")
        drive_session(sid2, token)
        draft2 = wait_draft(token, NQ, sid2)
        check("重生成覆盖草稿回 DRAFT（内容换新）",
              draft2 is not None and draft2.get("status") == "DRAFT"
              and (draft2.get("objectives") or [{}])[0].get("title") == "重生成目标",
              json.dumps(draft2, ensure_ascii=False)[:300])
        req("POST", "/worklog/goals/suggest/discard", {"draftId": draft2["id"]}, token)
        d_dis = req("GET", f"/worklog/goals/suggest/draft?period={NQ}", token=token)
        check("丢弃置 DISCARDED", d_dis.get("status") == "DISCARDED", str(d_dis)[:200])
        # 「已采纳不可丢弃 400」由 OkrSuggestServiceTest 覆盖——草稿按 (user,period) upsert，
        # 上面 force 重生成已把采纳态行覆盖回 DRAFT，E2E 内不存在 ADOPTED 行可弃
        req("POST", "/worklog/goals/suggest/discard", {"draftId": draft2["id"]}, token)
        check("重复丢弃幂等（DISCARDED 再弃仍 200）", True)

        # ---------- [6] 护栏 ----------
        req("POST", "/worklog/goals/suggest", {"scope": "YEAR", "period": EMPTY_YEAR}, token,
            expect=400)
        check("无素材周期 suggest 400", True)
        req("POST", "/worklog/goals/suggest", {"scope": "MONTH", "period": NQ}, token, expect=400)
        check("非法 scope 400", True)

        print(f"\n== CAP-93 E2E: {len(passed)} passed, {len(failed)} failed ==")
        if failed:
            print("FAILED: " + " | ".join(failed))
    finally:
        if runner is not None:
            kill_tree(runner.pid)
        if runner_log is not None and not runner_log.closed:
            runner_log.close()
        if node_id and token:
            try:
                req("DELETE", f"/agent-nodes/{node_id}", token=token)
            except Exception:
                pass
        stop_app(app, app_log)

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
