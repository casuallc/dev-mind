# -*- coding: utf-8 -*-
"""CAP-74 工作日志事实源服务端化 E2E（自起隔离 app + fake runner，全程无需外部服务）。

运行前提：
  1. `mvn -q install -DskipTests`（需要 devmind-app 可 spring-boot:run + devmind-agent-runner.jar，
     且 runner jar 必须是 C8 去 git 化后的新构建）；
  2. 本机有 git 与 node（fake 执行器内置 fake-agent.js）；
  3. JDK 21：JAVA_HOME 未设时回落 Eclipse Adoptium jdk-21.0.12.101 本机路径。

脚本自起：隔离 app（:18097，--spring.profiles.active=e2e 独立 H2，仓根/工作区全落
tmp/cap74-e2e/，三个定时生成开关关掉防干扰）+ fake runner（executor=fake，worklogRoot
落 tmp/cap74-e2e/worklog）。跑完两者都杀掉，不留残留。

验收覆盖（CAP-74 §验收）：
  [1] 懒建仓 + 骨架 commit（init trailer）；fake runner 会话直写 .devmind/output/daily-*.md
      → 服务端仓文件 + commit trailer（DevMind-Source: ai / DevMind-Session）
  [2] PUT 手改 → manual commit；绑 file:// bare 远端 → 服务端 push → bare 仓内容一致
  [3] 非 force 已存在跳过（commit 数不变）+ force 覆盖后旧稿可 git log 找回
  [4] kill runner 后：读/编辑/服务端 push 全正常，generate 409（节点离线不静默起失败进程）
  [5] 平台外 clone 改文件 push 回 → TTL(60s) 后平台读到新内容；双侧改同一文件 → push 409
      → resolve ours/theirs 成功，仓不留 MERGING 状态
  [6] 重启 app 后 git rev-list --count 不变（启动迁移幂等；带行迁移幂等由
      WorklogReportMigrationTest 覆盖——E2E 独立 H2 无存量表，此处钉「重启零新 commit」）
  [8] PUT 带 status 字段 → 400（旧前端防误确认）
  [9] 写后自动推送（2026-10-19 修订）：force 覆盖写仓 / 离线编辑保存后，远端无需
      手动 push 即见新内容
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
PORT = int(os.environ.get("E2E_PORT", "18097"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap74-e2e"
REPOS = TMP / "repos"                       # 服务端仓根（--devmind.worklog.repo-root）
WORKLOG_ROOT = TMP / "worklog"              # runner 草稿区根
REMOTE = TMP / "remote.git"                 # file:// bare 远端
EXT = TMP / "extclone"                      # 平台外克隆
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
APP_LOG = TMP / "app.log"
RUNNER_LOG = TMP / "runner.log"

# JDK 21（mvn 与 runner 都要）
JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
JAVA = str(Path(JAVA_HOME) / "bin" / "java.exe") if (Path(JAVA_HOME) / "bin/java.exe").is_file() else "java"

TODAY = __import__("datetime").date.today().isoformat()
REPO_DIR = REPOS / "personal-admin"         # D1: personal-<username>
DAILY_REL = f"admin/daily/{TODAY}.md"

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


def git(*args, cwd=None, check_ok=True):
    p = subprocess.run(["git", *args], cwd=cwd, capture_output=True)
    out = p.stdout.decode("utf-8", "replace")
    if check_ok and p.returncode != 0:
        raise AssertionError(f"git {' '.join(str(a) for a in args)} 失败: {p.stderr.decode('utf-8', 'replace')[:300]}")
    return out


def bare_show(path_in_repo):
    return norm(git("--git-dir", str(REMOTE), "show", f"main:{path_in_repo}"))


def wait_bare(path_in_repo, expect, timeout=30):
    """轮询 bare 远端直到文件内容==expect（自动推送是后台异步，给 30s 窗口）。"""
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            last = bare_show(path_in_repo)
        except AssertionError:
            last = ""
        if last.strip() == expect.strip():
            return True
        time.sleep(1)
    return False


def rev_count():
    return int(git("-C", str(REPO_DIR), "rev-list", "--count", "HEAD").strip())


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
    f'--spring.datasource.url=jdbc:h2:file:{(TMP / "db/devmind").as_posix()};AUTO_SERVER=TRUE '
    f'--devmind.project.workspace-root={(TMP / "repositories").as_posix()} '
    f'--devmind.worktree.root={(TMP / "worktrees").as_posix()} '
    f'--devmind.attachment.root-dir={(TMP / "attachments").as_posix()} '
    f'--devmind.worklog.repo-root={REPOS.as_posix()} '
    f'--devmind.worklog.daily-enabled=false '
    f'--devmind.worklog.weekly-enabled=false '
    f'--devmind.worklog.git-import-enabled=false'
)


def start_app():
    log = open(APP_LOG, "w", encoding="utf-8")
    # mvn 必须 cmd /c 起（Windows 上 mvn 是 cmd 脚本）；arguments 值均无空格（cron 坑不适用）
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{APP_ARGS}"')
    proc = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
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


def drive_session(sid, token, timeout=90):
    """fake-agent hold 模式：喂一条输入；bypass 场景每回合出 result → WAITING_INPUT 即回合完成
    可 finish（worklog 场景带 60s 自动结束，不能无限轮询否则 finish 撞 409）。"""
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


def wait_daily(token, sid, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        d = req("GET", f"/worklog/daily?date={TODAY}", token=token)
        if d and d.get("sessionId") == sid:
            return d
        time.sleep(1)
    raise AssertionError(f"日报镜像（仓写）未落到会话 {sid} 的产出")


def write_output(name, content):
    out = WORKLOG_ROOT / "admin" / ".devmind" / "output"
    for _ in range(30):
        if (WORKLOG_ROOT / "admin").is_dir():
            break
        time.sleep(1)
    out.mkdir(parents=True, exist_ok=True)
    (out / name).write_bytes(content.encode("utf-8"))  # 字节写，防 Windows 文本模式 \n→\r\n


def _force_rmtree(path):
    """Windows 下 git 对象文件只读，裸 rmtree 静默残留（ignore_errors 会把半截 .git 留下，
    下轮 initRepo 见 .git 跳过 init → git 发现上层外层仓报 'tmp ignored'）——只读位必须先去掉。"""
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

        # ---------- [1a] 懒建仓 + 骨架 commit ----------
        ws = req("POST", "/worklog/workspace/ensure", {}, token)
        check("ensure 返回个人仓 personal-admin", ws.get("repoKey") == "personal-admin", str(ws))
        check("仓目录已建", (REPO_DIR / ".git").is_dir())
        subj = git("-C", str(REPO_DIR), "log", "-1", "--format=%s").strip()
        body = git("-C", str(REPO_DIR), "log", "-1", "--format=%b")
        check("骨架 commit 主题/ trailer", subj == "worklog: init admin" and "DevMind-Source: init" in body,
              f"{subj!r} {body!r}")
        check("骨架文件（README + daily/weekly .gitkeep）",
              (REPO_DIR / "README.md").is_file()
              and (REPO_DIR / DAILY_REL).parent.joinpath(".gitkeep").is_file()
              and (REPO_DIR / "admin/weekly/.gitkeep").is_file())
        check("[6a] 首启迁移零新 commit（rev-list==1）", rev_count() == 1, str(rev_count()))

        # ---------- runner 节点 ----------
        issued = req("POST", "/agent-nodes", {"name": f"e2e-cap74-{int(time.time())}"}, token)
        node_id, node_token = issued["node"]["id"], issued["token"]
        req("POST", f"/agent-nodes/{node_id}/default", None, token)
        props = TMP / "runner.properties"
        props.write_text(
            f"serverUrl=ws://localhost:{PORT}/ws/agent\ntoken={node_token}\nexecutor=fake\n"
            f"workDir={(TMP / 'runner-work').as_posix()}\nworkspaceRoot={(TMP / 'ws').as_posix()}\n"
            f"worklogRoot={WORKLOG_ROOT.as_posix()}\nmaxConcurrent=4\ngcDays=7\n"
            # 本机常驻 runner（WinSW devmind-agent）占着 18377，fake 执行器不需要 git 代理
            f"gitProxyEnabled=false\n", encoding="utf-8")
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
            raise AssertionError("runner 未上线，后续会话链路无法验证")

        # 有默认节点后重新 ensure → WORKLOG 项目建出并绑节点
        ws = req("POST", "/worklog/workspace/ensure", {}, token)
        check("WORKLOG 项目已建（projectId 非空）", bool(ws.get("projectId")), str(ws))
        check("workspace nodeOnline=true", ws.get("nodeOnline") is True, str(ws))

        # ---------- [1b] fake 会话产出直写 → 仓文件 + trailer ----------
        # 生成前置校验要求当日有素材（条目或 git 提交）：先补一条工作条目
        req("POST", "/worklog/entries",
            {"workDate": TODAY, "title": "CAP-74 E2E 素材条目", "hours": 1.0}, token)
        ack = req("POST", "/worklog/daily/generate", {"date": TODAY, "force": True}, token)
        sid = ack.get("sessionId")
        check("日报生成受理（起会话）", bool(sid) and ack.get("reused") is False, str(ack))
        ai_v1 = f"# {TODAY} E2E 日报\n\n- 完成 CAP-74 生成链路验证\n\n工时合计：1h\n"
        write_output(f"daily-{TODAY}.md", ai_v1)
        drive_session(sid, token)
        d = wait_daily(token, sid)
        check("日报读回==会话产出", norm(d["contentMd"]) == ai_v1, repr(d["contentMd"][:80]))
        check("视图无 id/status 字段", "id" not in d and "status" not in d, str(sorted(d.keys())))
        check("仓文件==产出（字节级）",
              norm((REPO_DIR / DAILY_REL).read_text(encoding="utf-8")) == ai_v1)
        lg = git("-C", str(REPO_DIR), "log", "-1", "--format=%s%n%b", "--", DAILY_REL)
        check("commit 主题+ai/session trailer",
              f"worklog: daily {TODAY}" in lg and "DevMind-Source: ai" in lg
              and f"DevMind-Session: {sid}" in lg, lg)

        # ---------- [3a] 非 force 已存在跳过 ----------
        n0 = rev_count()
        ack2 = req("POST", "/worklog/daily/generate", {"date": TODAY}, token)
        check("非 force 再生成 → reused", ack2.get("reused") is True and not ack2.get("sessionId"), str(ack2))
        check("跳过零新 commit", rev_count() == n0)

        # ---------- [2] PUT 手改 + 绑 file:// 远端 + 服务端 push ----------
        manual_v1 = ai_v1 + "\n手工补充：PUT 直写服务端仓\n"
        d2 = req("PUT", f"/worklog/daily/{TODAY}", {"contentMd": manual_v1}, token)
        check("PUT 按日期寻址保存", norm(d2["contentMd"]) == manual_v1, str(d2)[:200])
        lg = git("-C", str(REPO_DIR), "log", "-1", "--format=%b", "--", DAILY_REL)
        check("PUT 落 manual commit", "DevMind-Source: manual" in lg, lg)

        git("init", "--bare", "-b", "main", str(REMOTE))
        req("PUT", "/worklog/settings",
            {"remoteUrl": REMOTE.as_uri(), "remoteBranch": "main"}, token)
        push = req("POST", "/worklog/workspace/push", {}, token)
        check("服务端 push 成功", push.get("ok") is True, str(push))
        check("bare 仓日报内容一致", bare_show(DAILY_REL).strip() == manual_v1.strip(),
              bare_show(DAILY_REL)[:120])
        check("bare 仓含骨架（README）", "工作日志" in bare_show("README.md"))

        # ---------- [3b] force 覆盖 + 旧稿可找回 ----------
        ack3 = req("POST", "/worklog/daily/generate", {"date": TODAY, "force": True}, token)
        sid2 = ack3.get("sessionId")
        check("force 再生成起新会话", bool(sid2), str(ack3))
        ai_v2 = ai_v1 + "\nforce 覆盖版 v2\n"
        write_output(f"daily-{TODAY}.md", ai_v2)
        drive_session(sid2, token)
        wait_daily(token, sid2)
        check("force 覆盖后仓文件==v2",
              norm((REPO_DIR / DAILY_REL).read_text(encoding="utf-8")) == ai_v2)
        check("[9a] AI 成稿写仓后自动推送远端（无需手动 push）",
              wait_bare(DAILY_REL, ai_v2), bare_show(DAILY_REL)[-80:])
        revs = git("-C", str(REPO_DIR), "log", "--format=%H", "--", DAILY_REL).split()
        old = norm(git("-C", str(REPO_DIR), "show", f"{revs[1]}:{DAILY_REL}")) if len(revs) > 1 else ""
        check("旧稿（manual 版）git log 可找回", old.strip() == manual_v1.strip(), old[:120])
        push = req("POST", "/worklog/workspace/push", {}, token)  # 同步远端，进下一阶段
        check("覆盖后 push 同步远端", push.get("ok") is True, str(push))

        # ---------- [4] kill runner：读/编辑/push 正常，generate 409 ----------
        kill_tree(runner.pid)
        runner_log.close()
        runner = None
        offline = False
        for _ in range(90):
            nodes = req("GET", "/agent-nodes", token=token)
            if any(n.get("id") == node_id and n.get("status") == "OFFLINE" for n in (nodes or [])):
                offline = True
                break
            time.sleep(1)
        check("节点判 OFFLINE", offline)
        d3 = req("GET", f"/worklog/daily?date={TODAY}", token=token)
        check("节点离线读正常", norm(d3["contentMd"]) == ai_v2)
        offline_v1 = ai_v2 + "\n节点离线期间的手工编辑\n"
        req("PUT", f"/worklog/daily/{TODAY}", {"contentMd": offline_v1}, token)
        check("节点离线编辑正常", norm(req("GET", f"/worklog/daily?date={TODAY}", token=token)["contentMd"]) == offline_v1)
        check("[9b] 编辑保存后自动推送远端（无需手动 push）",
              wait_bare(DAILY_REL, offline_v1), bare_show(DAILY_REL)[-80:])
        push = req("POST", "/worklog/workspace/push", {}, token)
        check("节点离线服务端 push 正常", push.get("ok") is True, str(push))
        check("bare 仓==离线编辑版", bare_show(DAILY_REL).strip() == offline_v1.strip())
        st = req("POST", "/worklog/daily/generate", {"date": TODAY, "force": True}, token, expect=409)
        check("节点离线 generate 409", isinstance(st, (dict, str)))

        # ---------- [5a] 平台外改动 → TTL 读路径合并 ----------
        git("clone", REMOTE.as_uri(), str(EXT))
        ext_file = EXT / DAILY_REL
        ext_v1 = offline_v1 + "\n平台外编辑 v1（外部 clone push）\n"
        ext_file.write_bytes(ext_v1.encode())
        git("-C", str(EXT), "add", "-A")
        git("-C", str(EXT), "-c", "user.email=e@e", "-c", "user.name=e", "commit", "-m", "ext v1")
        git("-C", str(EXT), "push", "origin", "main")
        print("  ... 等 61s 过读路径 fetch TTL")
        time.sleep(61)  # FETCH_TTL_MS=60s，上次读触发的 fetch 之后重新计时
        req("GET", f"/worklog/daily?date={TODAY}", token=token)  # 触发后台 fetch+merge
        merged = None
        for _ in range(30):
            c = norm(req("GET", f"/worklog/daily?date={TODAY}", token=token)["contentMd"])
            if c == ext_v1:
                merged = c
                break
            time.sleep(1)
        check("TTL 后平台读到平台外改动", merged == ext_v1,
              repr((merged or "")[-80:]))

        # ---------- [5b] 双侧同改 → push 409 → resolve ours ----------
        ext_v2 = ext_v1.replace("平台外编辑 v1", "平台外编辑 v2")
        ext_file.write_bytes(ext_v2.encode())
        git("-C", str(EXT), "add", "-A")
        git("-C", str(EXT), "-c", "user.email=e@e", "-c", "user.name=e", "commit", "-m", "ext v2")
        git("-C", str(EXT), "push", "origin", "main")
        local_v2 = ext_v1.replace("平台外编辑 v1", "平台本地编辑 v2")
        req("PUT", f"/worklog/daily/{TODAY}", {"contentMd": local_v2}, token)
        r409 = req("POST", "/worklog/workspace/push", {}, token, expect=409)
        check("分叉冲突 push 409", "冲突" in json.dumps(r409, ensure_ascii=False), str(r409)[:200])
        res = req("POST", "/worklog/workspace/resolve?strategy=ours", None, token)
        check("resolve ours 成功", res.get("ok") is True, str(res))
        check("ours 后 bare==平台版", bare_show(DAILY_REL).strip() == local_v2.strip(),
              bare_show(DAILY_REL)[-120:])
        check("仓不留 MERGING 状态", not (REPO_DIR / ".git" / "MERGING_HEAD").exists())

        # ---------- [5c] 再分叉 → resolve theirs ----------
        git("-C", str(EXT), "pull", "--no-edit", "origin", "main")
        ext_v3 = local_v2.replace("平台本地编辑 v2", "平台外编辑 v3")
        (EXT / DAILY_REL).write_bytes(ext_v3.encode())
        git("-C", str(EXT), "add", "-A")
        git("-C", str(EXT), "-c", "user.email=e@e", "-c", "user.name=e", "commit", "-m", "ext v3")
        git("-C", str(EXT), "push", "origin", "main")
        local_v3 = local_v2.replace("平台本地编辑 v2", "平台本地编辑 v3")
        req("PUT", f"/worklog/daily/{TODAY}", {"contentMd": local_v3}, token)
        req("POST", "/worklog/workspace/push", {}, token, expect=409)
        res = req("POST", "/worklog/workspace/resolve?strategy=theirs", None, token)
        check("resolve theirs 成功", res.get("ok") is True, str(res))
        check("theirs 后平台读到远端版",
              norm(req("GET", f"/worklog/daily?date={TODAY}", token=token)["contentMd"]).strip() == ext_v3.strip())
        check("theirs 后 bare==远端版", bare_show(DAILY_REL).strip() == ext_v3.strip())
        check("仓不留 MERGING 状态（theirs）", not (REPO_DIR / ".git" / "MERGING_HEAD").exists())

        # ---------- [8] PUT 带 status → 400 ----------
        r400 = req("PUT", f"/worklog/daily/{TODAY}",
                   {"contentMd": "x", "status": "CONFIRMED"}, token, expect=400)
        check("PUT 带 status 字段 400", isinstance(r400, (dict, str)), str(r400)[:160])

        # ---------- [6] 重启 app 迁移幂等 ----------
        n1 = rev_count()
        stop_app(app, app_log)
        app, app_log = start_app()
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        check("重启后 rev-list 不变（迁移幂等）", rev_count() == n1, f"{n1} -> {rev_count()}")
        check("重启后日报仍可读",
              norm(req("GET", f"/worklog/daily?date={TODAY}", token=token)["contentMd"]).strip() == ext_v3.strip())

        print(f"\n== CAP-74 E2E: {len(passed)} passed, {len(failed)} failed ==")
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
