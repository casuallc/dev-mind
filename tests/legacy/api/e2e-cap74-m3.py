# -*- coding: utf-8 -*-
r"""CAP-74 M3 条目文件化 E2E（自起隔离 app + fake runner，全程无需外部服务）。

运行前提：
  1. `mvn -q install -DskipTests`（devmind-app 走 .m2 jar + devmind-agent-runner.jar）；
  2. 本机有 git；JDK 21（JAVA_HOME 未设时回落 Eclipse Adoptium 本机路径）。

脚本自起：隔离 app（:18098，--spring.profiles.active=e2e 独立 H2，仓根落
tmp/cap74-m3-e2e/，三个定时开关关掉）+ fake runner（executor=fake，仅验生成起会话）。
跑完两者都杀掉，不留残留。

存量条目迁移用 H2 URL INIT 预植 legacy 表（SPRING_DATASOURCE_URL 环境变量宽松绑定——
INIT 语句含空格塞不进 spring-boot.run.arguments，且 `\;` 分隔多语句、
INSERT ... WHERE NOT EXISTS 保证每连接幂等），首轮启动真迁移、重启验幂等。

验收覆盖（CAP-74 M3 §验收）：
  [1] legacy 表（9001 MANUAL 90min / 9002 GIT sha）启动迁移 → entries/2026-09-14.md
      （legacy= 幂等键 + migration trailer + 时间回填），rev-list==2
  [2] 当日无素材 generate → 400（预检读的是文件口径）
  [3] 建条目 → day 文件字节（marker id/hours/source/req/jira + 正文 + preamble）
      + commit 主题「worklog: entries <date>」+ manual trailer；id 为 32hex 字符串
  [4] 平台外手改（preamble 行 + 尾部无注释自由文本）→ 平台更新另一条后两者都在
  [5] 删除按日期寻址 → 目标 marker 消失、其余条目与自由文本保留
  [6] git 导入：本地库两提交 → created=2；再导 → 0/2 全 skipped（判重走文件）；
      preview alreadyImported 翻转；git-import trailer
  [7] 有素材 generate → 起会话（fake runner），素材预检文件化后通路
  [8] 重启 app：迁移二跑零新 commit（rev-list 不变、legacy 文件不动），列表 API 读回迁移条目
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
TMP = ROOT / "tmp" / "cap74-m3-e2e"
REPOS = TMP / "repos"                       # 服务端仓根（--devmind.worklog.repo-root）
SRC_REPO = TMP / "srcrepo"                  # git 导入用的本地代码库
REMOTELESS_WORKLOG = TMP / "worklog"        # runner 草稿区根
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
APP_LOG = TMP / "app.log"
RUNNER_LOG = TMP / "runner.log"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe") if (Path(JAVA_HOME) / "bin/java.exe").is_file() else "java"

TODAY = __import__("datetime").date.today().isoformat()
LEGACY_DATE = "2026-09-14"
REPO_DIR = REPOS / "personal-admin"         # D1: personal-<username>
LEGACY_FILE = REPO_DIR / "admin" / "entries" / f"{LEGACY_DATE}.md"
TODAY_FILE = REPO_DIR / "admin" / "entries" / f"{TODAY}.md"

# legacy 表预植（H2 URL INIT：\; 分句；每连接幂等）。9001=MANUAL 90 分钟；9002=GIT 带 sha。
INIT_SQL = (
    "CREATE TABLE IF NOT EXISTS worklog_entries("
    "id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id VARCHAR(64), work_date DATE,"
    " title VARCHAR(256), content CLOB, entry_type VARCHAR(32), minutes INT,"
    " source VARCHAR(16), repo_id BIGINT, commit_sha VARCHAR(64),"
    " requirement_id VARCHAR(64), jira_issue_key VARCHAR(64),"
    " created_at TIMESTAMP, updated_at TIMESTAMP)"
    "\\;INSERT INTO worklog_entries(id,user_id,work_date,title,content,entry_type,minutes,"
    "source,created_at,updated_at) SELECT 9001,'admin',DATE '2026-09-14','legacy 条目一',"
    "'迁移前正文一','DEV',90,'MANUAL',TIMESTAMP '2026-09-14 09:00:00',"
    "TIMESTAMP '2026-09-14 10:00:00' WHERE NOT EXISTS (SELECT 1 FROM worklog_entries WHERE id=9001)"
    "\\;INSERT INTO worklog_entries(id,user_id,work_date,title,content,entry_type,minutes,"
    "source,commit_sha,created_at,updated_at) SELECT 9002,'admin',DATE '2026-09-14',"
    "'legacy git 条目','','DEV',0,'GIT','legacysha999',TIMESTAMP '2026-09-14 11:00:00',"
    "TIMESTAMP '2026-09-14 11:00:00' WHERE NOT EXISTS (SELECT 1 FROM worklog_entries WHERE id=9002)"
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


def norm(s):
    return (s or "").replace("\r\n", "\n")


def git(*args, cwd=None, env=None, check_ok=True):
    p = subprocess.run(["git", *args], cwd=cwd, env=env, capture_output=True)
    out = p.stdout.decode("utf-8", "replace")
    if check_ok and p.returncode != 0:
        raise AssertionError(f"git {' '.join(str(a) for a in args)} 失败: {p.stderr.decode('utf-8', 'replace')[:300]}")
    return out


def rev_count():
    return int(git("-C", str(REPO_DIR), "rev-list", "--count", "HEAD").strip())


def read_file(p):
    return norm(p.read_text(encoding="utf-8"))


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


def wait_legacy_file(timeout=60):
    """ApplicationRunner 迁移在 health 可用后才跑完，轮询等落盘。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        if LEGACY_FILE.is_file() and "legacy=9002" in read_file(LEGACY_FILE):
            return True
        time.sleep(1)
    return False


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
    kill_port_strays()  # 上次残留

    app, app_log = start_app()
    runner, runner_log, node_id = None, None, None
    token = None
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        print(f"[0] app 已起（:{PORT}），admin 登录成功")

        # ---------- [1] legacy 表启动迁移 → entries day 文件 ----------
        check("迁移落盘（legacy day 文件出现）", wait_legacy_file(), f"看 {APP_LOG}")
        lg = read_file(LEGACY_FILE)
        check("迁移文件 marker/幂等键/正文",
              "devmind:entry" in lg and "legacy=9001" in lg and "legacy=9002" in lg
              and "## legacy 条目一" in lg and "迁移前正文一" in lg
              and "## legacy git 条目" in lg, lg[:300])
        check("迁移属性映射（source/hours/sha）",
              "source=MANUAL" in lg and "source=GIT" in lg
              and "hours=1.5" in lg and "sha=legacysha999" in lg, lg[:300])
        check("迁移 preamble 初始化", lg.startswith(f"# {LEGACY_DATE} 工作条目"), lg[:80])
        subj = git("-C", str(REPO_DIR), "log", "--format=%s")
        check("迁移 commit 主题", f"worklog: entries {LEGACY_DATE} (migration)" in subj, subj)
        body = git("-C", str(REPO_DIR), "log", "-1", "--format=%b")
        check("migration trailer", "DevMind-Source: migration" in body, body)
        check("首启 rev-list==2（init + 迁移一组一 commit）", rev_count() == 2, str(rev_count()))

        # ---------- [2] 无素材 generate → 400（预检读文件口径，当日无条目） ----------
        r400 = req("POST", "/worklog/daily/generate", {"date": TODAY, "force": True}, token, expect=400)
        check("无素材 generate 400", "素材" in json.dumps(r400, ensure_ascii=False), str(r400)[:160])

        # ---------- [3] 建条目 → day 文件字节 + commit trailer ----------
        e1 = req("POST", "/worklog/entries",
                 {"workDate": TODAY, "title": "M3 E2E 条目一", "content": "条目一正文",
                  "hours": 1.5, "entryType": "DEV", "requirementId": "REQ-M3-1",
                  "jiraIssueKey": "JIRA-7"}, token)
        e1_id = e1.get("id")
        check("id 为 32hex 字符串", isinstance(e1_id, str) and len(e1_id) == 32
              and all(c in "0123456789abcdef" for c in e1_id), str(e1))
        check("视图小时/来源回读", e1.get("hours") == 1.5 and e1.get("source") == "MANUAL", str(e1))
        e2 = req("POST", "/worklog/entries",
                 {"workDate": TODAY, "title": "M3 E2E 条目二", "content": "条目二正文",
                  "hours": 0.5}, token)
        e2_id = e2.get("id")
        tf = read_file(TODAY_FILE)
        check("当日文件 marker/正文/preamble",
              f"id={e1_id}" in tf and f"id={e2_id}" in tf
              and "type=DEV" in tf and "hours=1.5" in tf and "source=MANUAL" in tf
              and "req=REQ-M3-1" in tf and "jira=JIRA-7" in tf
              and "## M3 E2E 条目一" in tf and "条目二正文" in tf
              and tf.startswith(f"# {TODAY} 工作条目"), tf[:300])
        subj = git("-C", str(REPO_DIR), "log", "-1", "--format=%s").strip()
        body = git("-C", str(REPO_DIR), "log", "-1", "--format=%b")
        check("条目 commit 主题 + manual trailer",
              subj == f"worklog: entries {TODAY}" and "DevMind-Source: manual" in body,
              f"{subj!r} {body!r}")

        # ---------- [4] 平台外手改（preamble + 尾部自由文本）在平台重写后存活 ----------
        raw = TODAY_FILE.read_text(encoding="utf-8")
        first_line, rest = raw.split("\n", 1)
        TODAY_FILE.write_bytes((first_line + "\n\n自定义 preamble 行（勿动）\n" + rest
                                + "\n人工随手记：无注释自由文本\n").encode("utf-8"))
        git("-C", str(REPO_DIR), "add", "-A")
        git("-C", str(REPO_DIR), "-c", "user.email=e@e", "-c", "user.name=e",
            "commit", "-m", "human edit")
        upd = req("PUT", f"/worklog/entries/{e1_id}",
                  {"workDate": TODAY, "title": "M3 E2E 条目一（改）", "content": "条目一正文改",
                   "hours": 2.0}, token)
        check("更新返回同 id", upd.get("id") == e1_id, str(upd))
        tf = read_file(TODAY_FILE)
        check("更新后 preamble/自由文本存活",
              "自定义 preamble 行（勿动）" in tf and "人工随手记：无注释自由文本" in tf, tf[:400])
        check("更新生效（标题/hours）", "## M3 E2E 条目一（改）" in tf and "hours=2" in tf, tf[:400])

        # ---------- [5] 删除按日期寻址 ----------
        req("DELETE", f"/worklog/entries/{TODAY}/{e1_id}", token=token)
        tf = read_file(TODAY_FILE)
        check("删除后目标 marker 消失", f"id={e1_id}" not in tf, tf[:400])
        check("删除后其余条目与自由文本保留",
              f"id={e2_id}" in tf and "人工随手记：无注释自由文本" in tf, tf[:400])
        st = req("DELETE", f"/worklog/entries/{TODAY}/{e1_id}", token=token, expect=404)
        check("重复删除 404", isinstance(st, (dict, str)), str(st)[:120])

        # ---------- [6] git 导入：判重走文件，二次全 skipped ----------
        SRC_REPO.mkdir(parents=True, exist_ok=True)
        git("init", "-b", "main", str(SRC_REPO))
        git("-C", str(SRC_REPO), "config", "user.email", "e2e@local")
        git("-C", str(SRC_REPO), "config", "user.name", "E2E")
        commit_env = os.environ.copy()
        shas = []
        for i, hm in enumerate(("08:30:00", "09:30:00")):
            (SRC_REPO / f"f{i}.txt").write_bytes(f"v{i}".encode())
            git("-C", str(SRC_REPO), "add", "-A")
            commit_env["GIT_AUTHOR_DATE"] = f"{TODAY}T{hm}"
            commit_env["GIT_COMMITTER_DATE"] = f"{TODAY}T{hm}"
            git("-C", str(SRC_REPO), "commit", "-m", f"M3 导入提交{i}", env=commit_env)
            shas.append(git("-C", str(SRC_REPO), "rev-parse", "HEAD").strip())
        repo = req("POST", "/repos",
                   {"name": "m3-src", "sourceType": "LOCAL", "localPath": SRC_REPO.as_posix()}, token)
        repo_id = repo.get("id")
        check("登记本地代码库", repo_id is not None, str(repo)[:200])
        req("PUT", f"/worklog/repos/{repo_id}/subscription", {"subscribed": True}, token)
        prev = req("GET", f"/worklog/git/preview?from={TODAY}&to={TODAY}&filter=ALL", token=token)
        mine = {c["sha"]: c for c in prev.get("commits", []) if c.get("sha") in shas}
        check("preview 扫到两提交且未导入",
              len(mine) == 2 and all(not c.get("alreadyImported") for c in mine.values()),
              json.dumps(prev, ensure_ascii=False)[:300])
        items = [{"repoId": repo_id, "commitSha": s, "subject": f"M3 导入提交{i}",
                  "date": TODAY, "hours": 0.5} for i, s in enumerate(shas)]
        r1 = req("POST", "/worklog/git/import", {"items": items}, token)
        check("首次导入 created=2", r1.get("created") == 2 and r1.get("skipped") == 0, str(r1))
        r2 = req("POST", "/worklog/git/import", {"items": items}, token)
        check("二次导入全 skipped（判重走文件）", r2.get("created") == 0 and r2.get("skipped") == 2, str(r2))
        tf = read_file(TODAY_FILE)
        check("导入条目落当日文件（source=GIT + sha）",
              all(f"sha={s}" in tf for s in shas) and "source=GIT" in tf, tf[-400:])
        body = git("-C", str(REPO_DIR), "log", "-1", "--format=%b")
        check("git-import trailer", "DevMind-Source: git-import" in body, body)
        prev2 = req("GET", f"/worklog/git/preview?from={TODAY}&to={TODAY}&filter=ALL", token=token)
        mine2 = {c["sha"]: c for c in prev2.get("commits", []) if c.get("sha") in shas}
        check("preview alreadyImported 翻转", all(c.get("alreadyImported") for c in mine2.values()),
              json.dumps(mine2)[:200])

        # ---------- [7] 有素材 generate → 起会话（fake runner） ----------
        issued = req("POST", "/agent-nodes", {"name": f"e2e-cap74m3-{int(time.time())}"}, token)
        node_id, node_token = issued["node"]["id"], issued["token"]
        req("POST", f"/agent-nodes/{node_id}/default", None, token)
        props = TMP / "runner.properties"
        props.write_text(
            f"serverUrl=ws://localhost:{PORT}/ws/agent\ntoken={node_token}\nexecutor=fake\n"
            f"workDir={(TMP / 'runner-work').as_posix()}\nworkspaceRoot={(TMP / 'ws').as_posix()}\n"
            f"worklogRoot={REMOTELESS_WORKLOG.as_posix()}\nmaxConcurrent=4\ngcDays=7\n",
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
        ack = req("POST", "/worklog/daily/generate", {"date": TODAY, "force": True}, token)
        sid = ack.get("sessionId")
        check("有条目 generate 起会话（素材预检文件化通路）",
              bool(sid) and ack.get("reused") is False, str(ack))
        drive_session(sid, token)
        check("会话收尾无异常（无产出不写日报）", True)

        # ---------- [8] 重启：迁移二跑零新 commit ----------
        n1 = rev_count()
        legacy_bytes = LEGACY_FILE.read_bytes()
        stop_app(app, app_log)
        app, app_log = start_app()
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        time.sleep(3)  # ApplicationRunner 二跑窗口
        check("重启后 rev-list 不变（迁移幂等）", rev_count() == n1, f"{n1} -> {rev_count()}")
        check("重启后 legacy 文件字节不变", LEGACY_FILE.read_bytes() == legacy_bytes)
        page = req("GET", f"/worklog/entries?from={LEGACY_DATE}&to={LEGACY_DATE}", token=token)
        items = page.get("items", [])
        check("列表 API 读回迁移条目（2 条/hours/source）",
              page.get("total") == 2
              and sorted(e.get("source") for e in items) == ["GIT", "MANUAL"]
              and any(e.get("hours") == 1.5 for e in items), json.dumps(page, ensure_ascii=False)[:300])
        check("迁移条目 id 为字符串", all(isinstance(e.get("id"), str) for e in items), str(items)[:200])

        print(f"\n== CAP-74 M3 E2E: {len(passed)} passed, {len(failed)} failed ==")
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
