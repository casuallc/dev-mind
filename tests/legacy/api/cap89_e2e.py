# -*- coding: utf-8 -*-
r"""CAP-89 工作日志存储回 DB E2E（自起隔离 app + fake runner，全程无需外部服务）。

运行前提：
  1. `mvn -q install -DskipTests`（devmind-app 走 .m2 jar + devmind-agent-runner.jar）；
  2. JDK 21（JAVA_HOME 未设时回落 Eclipse Adoptium 本机路径）。

脚本自起：隔离 app（:18099，--spring.profiles.active=e2e 独立 H2，三个定时开关关掉，
--devmind.worklog.repo-root 指向预植的存量仓目录）+ fake runner（executor=fake，验生成起会话）。
跑完两者都杀掉，不留残留。

仓→DB 回迁判存跳过的「已有行」用 H2 URL INIT 预植 daily_reports（SPRING_DATASOURCE_URL
环境变量宽松绑定——INIT 含空格塞不进 spring-boot.run.arguments，`\;` 分隔多语句、
INSERT ... WHERE NOT EXISTS 保证每连接幂等）。

验收覆盖（CAP-89 §验收）：
  [1] 存量仓日报/周报/条目启动回迁 → GET 读回（周报拆 summary/nextPlan 两列）
  [2] DB 已有行的日期仓文件被跳过（判存不覆盖，DB 是事实源）
  [3] 条目 uuid 主键/hours/source/sha 回迁保真
  [4] 条目 CRUD 纯 DB：建/改/删（uuid 直删）；旧按日期寻址 DELETE 404
  [5] 日报手填/保存落库往返；无素材 generate → 400（预检读 DB）
  [6] workspace 视图瘦身（无 repoKey/remoteUrl）；push/resolve/仓列表端点下线（404/405）
  [7] 有素材 generate → 起会话（fake runner；素材预检 DB 通路）
  [8] 重启 app：迁移二跑零新增（条目/日报数量不变，判存幂等）
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
PORT = int(os.environ.get("E2E_PORT", "18099"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap89-e2e"
REPOS = TMP / "repos"                        # 存量服务端仓根（迁移数据源）
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
APP_LOG = TMP / "app.log"
RUNNER_LOG = TMP / "runner.log"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe") if (Path(JAVA_HOME) / "bin/java.exe").is_file() else "java"

TODAY = __import__("datetime").date.today().isoformat()
MIG_DATE = "2026-09-14"                      # 周一，与 weekly W38 同周
SKIP_DATE = "2026-09-15"                     # DB 预植行的日期（仓文件应被跳过）
WEEK_START = "2026-09-07"                    # ISO 2026-W37 周一
ENTRY_ID_1 = "0123456789abcdef0123456789abcdef"
ENTRY_ID_2 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

# DB 预植「已有行」（H2 URL INIT）：2026-09-15 日报库中已有 → 仓内同日期文件迁移时跳过
INIT_SQL = (
    "CREATE TABLE IF NOT EXISTS daily_reports("
    "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id VARCHAR(64) NOT NULL, work_date DATE NOT NULL,"
    " content_md CLOB, session_id VARCHAR(16), created_at TIMESTAMP, updated_at TIMESTAMP)"
    "\\;INSERT INTO daily_reports(id,user_id,work_date,content_md,created_at,updated_at)"
    " SELECT 9001,'admin',DATE '2026-09-15','DB 已有内容（仓文件不应覆盖）',"
    "TIMESTAMP '2026-09-15 09:00:00',TIMESTAMP '2026-09-15 09:00:00'"
    " WHERE NOT EXISTS (SELECT 1 FROM daily_reports WHERE id=9001)"
)

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
    """兜底：按监听端口找 PID 杀（mvn fork 的 app JVM 可能逃出 cmd 进程树）。"""
    out = subprocess.run(["netstat", "-ano"], capture_output=True).stdout.decode("utf-8", "replace")
    for line in out.splitlines():
        if f":{PORT}" in line and "LISTENING" in line:
            pid = line.split()[-1]
            subprocess.run(["taskkill", "/F", "/PID", pid], capture_output=True)


APP_ARGS = (
    f'--server.port={PORT} '
    f'--devmind.project.workspace-root={(TMP / "repositories").as_posix()} '
    f'--devmind.worktree.root={(TMP / "worktrees").as_posix()} '
    f'--devmind.attachment.root-dir={(TMP / "attachments").as_posix()} '
    f'--devmind.worklog.repo-root={REPOS.as_posix()} '
    f'--devmind.worklog.daily-enabled=false '
    f'--devmind.worklog.weekly-enabled=false '
    f'--devmind.worklog.git-import-enabled=false'
)


def app_env():
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    # 数据源走环境变量宽松绑定：INIT 含空格/分号，塞不进 spring-boot.run.arguments
    env["SPRING_DATASOURCE_URL"] = (
        f'jdbc:h2:file:{(TMP / "db/devmind").as_posix()};AUTO_SERVER=TRUE;INIT={INIT_SQL}')
    return env


def start_app():
    log = open(APP_LOG, "w", encoding="utf-8")
    # mvn 必须 cmd /c 起（Windows 上 mvn 是 cmd 脚本）；arguments 值均无空格
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{APP_ARGS}"')
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, env=app_env())
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


def login():
    return req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]


def wait_migration(token, timeout=60):
    """ApplicationRunner 回迁在 health 可用前后完成，轮询 GET 日报直到迁入行可读。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            d = req("GET", f"/worklog/daily?date={MIG_DATE}", token=token)
            if d and "迁移日报" in (d.get("contentMd") or ""):
                return True
        except Exception:
            pass
        time.sleep(1)
    return False


def seed_repo():
    """预植存量仓（personal-admin/admin/{daily,weekly,entries}），模拟 CAP-74 年代数据。"""
    base = REPOS / "personal-admin" / "admin"
    (base / "daily").mkdir(parents=True, exist_ok=True)
    (base / "weekly").mkdir(parents=True, exist_ok=True)
    (base / "entries").mkdir(parents=True, exist_ok=True)
    (base / "daily" / f"{MIG_DATE}.md").write_text("# 迁移日报\n- 完成回迁验证\n", encoding="utf-8")
    (base / "daily" / f"{SKIP_DATE}.md").write_text("仓内版本（应被 DB 已有行挡住）\n", encoding="utf-8")
    (base / "weekly" / "2026-W37.md").write_text(
        "## 上周总结\n- 完成 A\n- 推进 B\n\n## 下周计划\n- 继续 B\n", encoding="utf-8")
    (base / "entries" / f"{MIG_DATE}.md").write_text(
        f"# {MIG_DATE} 工作条目\n\n"
        f"<!-- devmind:entry id={ENTRY_ID_1} type=MEETING hours=1.5 source=MANUAL"
        f" repo=- sha=- req=REQ-1 jira=- created=2026-09-14T02:30:00Z -->\n"
        "## 需求评审\n讨论了排期\n\n"
        f"<!-- devmind:entry id={ENTRY_ID_2} type=DEV hours=0.5 source=GIT"
        f" repo=7 sha=abc123def req=- jira=- created=- -->\n"
        "## 提交条目\n",
        encoding="utf-8")


def drive_session(sid, token, timeout=90):
    """fake-agent：喂一条输入 → WAITING_INPUT 即回合完成 → finish（不产出 .devmind/output）。"""
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


def _force_rmtree(path):
    """Windows 下 git 对象文件只读，裸 rmtree 会静默残留——只读位必须先去掉。"""
    def _onerror(func, p, _exc):
        os.chmod(p, 0o666)
        func(p)
    if os.path.exists(path):
        shutil.rmtree(path, onerror=_onerror)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    if not RUNNER_JAR.is_file():
        print(f"缺少 {RUNNER_JAR}，先 mvn -q install -DskipTests")
        sys.exit(1)

    _force_rmtree(TMP)
    TMP.mkdir(parents=True, exist_ok=True)
    seed_repo()
    kill_port_strays()  # 上次残留

    app, app_log = start_app()
    runner, runner_log, node_id = None, None, None
    token = None
    try:
        token = login()
        print(f"[0] app 已起（:{PORT}），admin 登录成功")

        # ---------- [1] 存量仓回迁：日报/周报/条目读回 ----------
        check("回迁落库（迁移日报可读）", wait_migration(token), f"看 {APP_LOG}")
        w = req("GET", f"/worklog/weekly?weekStart={WEEK_START}", token=token)
        check("周报拆两列（summary/nextPlan）",
              w is not None and "完成 A" in (w.get("summaryMd") or "")
              and "继续 B" in (w.get("nextPlanMd") or "")
              and "上周总结" not in (w.get("summaryMd") or ""),
              json.dumps(w, ensure_ascii=False)[:300])

        # ---------- [2] DB 已有行判存跳过（仓文件不覆盖） ----------
        d = req("GET", f"/worklog/daily?date={SKIP_DATE}", token=token)
        check("DB 已有行不被仓文件覆盖",
              d is not None and "DB 已有内容" in (d.get("contentMd") or "")
              and "仓内版本" not in (d.get("contentMd") or ""),
              json.dumps(d, ensure_ascii=False)[:200])

        # ---------- [3] 条目回迁保真 ----------
        page = req("GET", f"/worklog/entries?from={MIG_DATE}&to={MIG_DATE}", token=token)
        items = {e.get("id"): e for e in page.get("items", [])}
        check("回迁条目 2 条（uuid 主键）", page.get("total") == 2 and ENTRY_ID_1 in items
              and ENTRY_ID_2 in items, json.dumps(page, ensure_ascii=False)[:300])
        e1 = items.get(ENTRY_ID_1, {})
        check("条目属性保真（type/hours/req/正文）",
              e1.get("entryType") == "MEETING" and e1.get("hours") == 1.5
              and e1.get("requirementId") == "REQ-1" and e1.get("title") == "需求评审",
              json.dumps(e1, ensure_ascii=False)[:200])
        e2 = items.get(ENTRY_ID_2, {})
        check("git 条目保真（source/sha/repoId）",
              e2.get("source") == "GIT" and e2.get("commitSha") == "abc123def"
              and e2.get("repoId") == 7,
              json.dumps(e2, ensure_ascii=False)[:200])

        # ---------- [4] 条目 CRUD 纯 DB ----------
        c = req("POST", "/worklog/entries",
                {"workDate": TODAY, "title": "CAP-89 E2E 条目", "content": "正文",
                 "hours": 2.0, "entryType": "DEV"}, token)
        cid = c.get("id")
        check("新建条目返回 uuid", isinstance(cid, str) and len(cid) == 32, str(c))
        upd = req("PUT", f"/worklog/entries/{cid}",
                  {"workDate": TODAY, "title": "CAP-89 E2E 条目（改）", "hours": 3.0}, token)
        check("更新条目（同 id 生效）", upd.get("id") == cid and upd.get("hours") == 3.0, str(upd))
        req("DELETE", f"/worklog/entries/{cid}", token=token)
        gone = req("GET", f"/worklog/entries?from={TODAY}&to={TODAY}&keyword=CAP-89", token=token)
        check("uuid 直删生效", gone.get("total") == 0, json.dumps(gone)[:200])
        req("DELETE", f"/worklog/entries/{TODAY}/{cid}", token=token, expect=404)
        check("旧按日期寻址 DELETE 已下线（404）", True)

        # ---------- [5] 日报手填/保存落库 + 无素材 generate 400 ----------
        nd = req("POST", "/worklog/daily", {"date": TODAY}, token)
        check("手动创建日报", nd is not None and nd.get("workDate") == TODAY, str(nd)[:200])
        sd = req("PUT", f"/worklog/daily/{TODAY}", {"contentMd": "# 手写日报\n- 落库验证"}, token)
        check("保存日报落库回读", "落库验证" in (sd.get("contentMd") or ""), str(sd)[:200])
        d2 = req("GET", f"/worklog/daily?date={TODAY}", token=token)
        check("GET 读回保存内容", "落库验证" in (d2.get("contentMd") or ""), str(d2)[:200])
        # 周报同日报路径：先手动创建再保存两列（PUT 对不存在行 404）
        req("POST", "/worklog/weekly", {"weekStart": monday_of_today()}, token)
        sw = req("PUT", f"/worklog/weekly/{monday_of_today()}",
                 {"summaryMd": "- 本周总结", "nextPlanMd": "- 下周计划"}, token)
        check("周报保存两列回读",
              sw.get("summaryMd") == "- 本周总结" and sw.get("nextPlanMd") == "- 下周计划",
              json.dumps(sw, ensure_ascii=False)[:200])
        # 明天无任何条目/提交 → 素材预检 400（读 DB 口径）
        tomorrow = __import__("datetime").date.fromisoformat(TODAY) + __import__("datetime").timedelta(days=1)
        r400 = req("POST", "/worklog/daily/generate", {"date": tomorrow.isoformat(), "force": True},
                   token, expect=400)
        check("无素材 generate 400", "素材" in json.dumps(r400, ensure_ascii=False), str(r400)[:160])

        # ---------- [6] workspace 瘦身 + 仓链路端点下线 ----------
        ws = req("GET", "/worklog/workspace", token=token)
        check("workspace 无仓字段", "repoKey" not in ws and "remoteUrl" not in ws
              and "lastCommitAt" not in ws and "exists" in ws, json.dumps(ws)[:200])
        req("POST", "/worklog/workspace/push", {}, token, expect=404)
        check("push 端点下线（404）", True)
        req("GET", "/worklog/repos/mine", token=token, expect=404)
        check("仓列表端点下线（404）", True)
        st = req("GET", "/worklog/settings", token=token)
        check("settings 无远端/活动仓字段",
              "remoteUrl" not in st and "remoteBranch" not in st and "activeRepoKey" not in st,
              json.dumps(st)[:200])

        # ---------- [7] 有素材 generate → 起会话（fake runner；预检读 DB） ----------
        req("POST", "/worklog/entries",
            {"workDate": TODAY, "title": "生成素材条目", "hours": 1.0, "entryType": "DEV"}, token)
        issued = req("POST", "/agent-nodes", {"name": f"e2e-cap89-{int(time.time())}"}, token)
        node_id, node_token = issued["node"]["id"], issued["token"]
        req("POST", f"/agent-nodes/{node_id}/default", None, token)
        props = TMP / "runner.properties"
        props.write_text(
            f"serverUrl=ws://localhost:{PORT}/ws/agent\ntoken={node_token}\nexecutor=fake\n"
            f"workDir={(TMP / 'runner-work').as_posix()}\nworkspaceRoot={(TMP / 'ws').as_posix()}\n"
            f"worklogRoot={(TMP / 'worklog-ws').as_posix()}\nmaxConcurrent=4\ngcDays=7\n"
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
        # 今天已有手填日报（[5] 落库）→ force=true 显式覆盖起会话
        ack = req("POST", "/worklog/daily/generate", {"date": TODAY, "force": True}, token)
        sid = ack.get("sessionId")
        check("有素材 force generate 起会话（预检 DB 通路）",
              bool(sid) and ack.get("reused") is False, str(ack))
        drive_session(sid, token)
        check("会话收尾无异常（无产出不落库）", True)

        # ---------- [8] 重启：迁移二跑判存幂等 ----------
        stop_app(app, app_log)
        app, app_log = start_app()
        token = login()
        time.sleep(3)  # ApplicationRunner 二跑窗口
        page2 = req("GET", f"/worklog/entries?from={MIG_DATE}&to={MIG_DATE}", token=token)
        check("重启后回迁条目不重复（仍 2 条）", page2.get("total") == 2,
              json.dumps(page2)[:200])
        d3 = req("GET", f"/worklog/daily?date={SKIP_DATE}", token=token)
        check("重启后 DB 已有行仍不被覆盖", "DB 已有内容" in (d3.get("contentMd") or ""),
              str(d3)[:200])
        d4 = req("GET", f"/worklog/daily?date={MIG_DATE}", token=token)
        check("重启后迁移日报仍唯一可读", d4 is not None and "迁移日报" in (d4.get("contentMd") or ""),
              str(d4)[:200])
        t = req("GET", f"/worklog/entries?from={TODAY}&to={TODAY}", token=token)
        check("重启后手写条目仍在（1 条生成素材）", t.get("total") == 1, json.dumps(t)[:200])

        print(f"\n== CAP-89 E2E: {len(passed)} passed, {len(failed)} failed ==")
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


def monday_of_today():
    d = __import__("datetime").date.fromisoformat(TODAY)
    return (d - __import__("datetime").timedelta(days=d.weekday())).isoformat()


if __name__ == "__main__":
    main()
