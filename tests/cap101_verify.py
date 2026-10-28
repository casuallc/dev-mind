# -*- coding: utf-8 -*-
"""CAP-101 验收通过联动 Jira 解决与工时登记 E2E（jira-mock，不需要真实 Jira 实例）。

运行前提：
1. 依赖已构建并装进本地仓库：`mvn -q install -DskipTests`。
2. app 独立实例已起（18090 + 独立 H2，**必须显式换掉默认的 local profile**，姿势同 cap47）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=e2e --server.port=18090 --spring.datasource.url=jdbc:h2:file:./tmp/cap101-db/devmind"
3. fixtures/jira-mock.py 由本脚本自起（端口 JIRA_MOCK_PORT，默认 18192）。

链路：LOCAL 需求 → CAP-47 推送转 JIRA 托管（拿到真实 externalKey/link）→ PUT status=DONE
（验收通过主路径）→ POST /jira/resolve。
覆盖：
- 主路径：词表命中 toStatus（完成→Done）执行转换 + 登记工时（comment 中文透传），
  结果带回 transitionName/remoteStatus/loggedSeconds，mock 侧 issue 翻 Done 且 timespent 累加，
  需求 remoteStatus 单条刷新为 Done；
- 已是终态：issue 在 Done 时可用转换只剩「重新打开」，词表无命中 → transitioned=false 不报错，
  seconds 为空时纯空转（mock 无 worklog）；
- 部分失败：转换成功 + worklog 403 注入 → HTTP 200 + transitioned=true + worklogError 透出，
  转换不回滚（mock 侧状态已 Done）、工时未登记；
- 转换失败：transition 400 注入 → 接口抛错（4xx）、不发 worklog、远端状态不变；
- 兜底匹配：toStatus 不含解决词、name 含 "Resolve" 时按 name 命中；
- 非法入参：seconds<=0 / 超上限 → 400；非 Jira 来源需求 → 400。
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

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18090/api")
MOCK_PORT = int(os.environ.get("JIRA_MOCK_PORT", "18192"))
MOCK_BASE = "http://127.0.0.1:%d" % MOCK_PORT
ROOT = Path(__file__).resolve().parent.parent
TMP = ROOT / "tmp"
FIXTURE = ROOT / "tests" / "fixtures" / "jira-mock.py"
MARK = "e2e-cap101-%d" % int(time.time())
REPO = TMP / ("cap101-repo-" + MARK)
TOKEN = None
pid = None
passed = 0
failed = 0


def call(method, path, body=None, base=None):
    url = (base or BASE) + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if TOKEN and base is None:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def mock(path, body=None):
    return call("POST" if body is not None else "GET", path, body, base=MOCK_BASE)


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print("  PASS  %s" % name)
    else:
        failed += 1
        print("  FAIL  %s  %s" % (name, detail))


def kill_tree(proc_id):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc_id)], capture_output=True)


def mock_issue(key):
    st, state = mock("/__state")
    for issue in (state or {}).get("issues", []):
        if issue.get("key") == key:
            return issue
    return None


def mock_worklogs():
    st, state = mock("/__state")
    return (state or {}).get("worklogs", [])


mock_proc = subprocess.Popen([sys.executable, str(FIXTURE), str(MOCK_PORT)],
                             stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
try:
    ready = False
    for _ in range(60):
        try:
            if mock("/rest/api/2/myself")[0] == 200:
                ready = True
                break
        except Exception:
            pass
        time.sleep(0.2)
    if not ready:
        print("jira-mock 启动失败（端口 %d 被占？）" % MOCK_PORT)
        sys.exit(1)

    # ---------- 0. 登录 ----------
    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
    check("登录 admin", st == 200 and bool(TOKEN), "%s %s" % (st, login))
    if not TOKEN:
        sys.exit(1)

    # ---------- 1. LOCAL 项目 + 集成 + 推送需求转 JIRA 托管 ----------
    REPO.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@local",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@local")
    subprocess.run(["git", "init", "-b", "main"], cwd=REPO, check=True, capture_output=True)
    (REPO / "README.md").write_text("# cap101 e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "-A"], cwd=REPO, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=REPO, check=True,
                   capture_output=True, env=env)

    st, proj = call("POST", "/projects", {
        "name": MARK, "sourceType": "LOCAL", "path": str(REPO), "defaultBranch": "main"})
    pid = (proj or {}).get("id")
    check("创建 LOCAL 项目", st == 200 and bool(pid), "%s %s" % (st, proj))
    if not pid:
        sys.exit(1)

    st, ia = call("POST", "/integrations", {
        "type": "JIRA", "name": MARK + " 实例", "baseUrl": MOCK_BASE,
        "authType": "PAT", "token": "e2e-jira-token"})
    ia_id = (ia or {}).get("id")
    check("创建 JIRA 集成", st == 200 and bool(ia_id), "%s %s" % (st, ia))

    st, r1 = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 验收联动", "description": "验收后自动解决 Jira 并登记工时。"})
    rid = (r1 or {}).get("id")
    check("创建 LOCAL 需求", st == 200 and bool(rid), "%s %s" % (st, r1))

    st, res = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 验收联动", "backlinkUrl": "%s/browse" % MOCK_BASE})
    key = (res or {}).get("externalKey")
    check("推送 Jira 转托管", st == 200 and (key or "").startswith("PROJ-"), "%s %s" % (st, res))
    if not key:
        sys.exit(1)

    st, r_local = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 纯本地负例", "description": ""})
    rid_local = (r_local or {}).get("id")
    check("创建 LOCAL 负例需求", st == 200 and bool(rid_local), "%s %s" % (st, r_local))

    # ---------- 2. 验收通过（主流程先 DONE） ----------
    st, done = call("PUT", "/projects/%s/requirements/%s/status" % (pid, rid),
                    {"status": "DONE"})
    check("需求翻 DONE（验收通过）", st == 200 and (done or {}).get("status") == "DONE",
          "%s %s" % (st, done))

    # ---------- 3. 主路径：解决 + 登记工时 ----------
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid),
                   {"seconds": 3600, "comment": "验收通过"})
    check("resolve 200", st == 200, "%s %s" % (st, out))
    check("词表命中 toStatus 执行解决转换",
          (out or {}).get("transitioned") is True and (out or {}).get("transitionName") == "完成",
          "%s" % (out,))
    check("工时登记 3600s",
          (out or {}).get("loggedSeconds") == 3600 and not (out or {}).get("worklogError"),
          "%s" % (out,))
    check("结果带回刷新后远端状态 Done", (out or {}).get("remoteStatus") == "Done", "%s" % (out,))
    issue = mock_issue(key) or {}
    check("mock 侧 issue 翻 Done 且 timespent=3600",
          (issue.get("fields") or {}).get("status", {}).get("name") == "Done"
          and (issue.get("fields") or {}).get("timespent") == 3600, "%s" % (issue,))
    wl = mock_worklogs()
    check("mock 收到 worklog（中文 comment 透传）",
          len(wl) == 1 and wl[0].get("key") == key
          and wl[0].get("seconds") == 3600 and wl[0].get("comment") == "验收通过", "%s" % (wl,))
    st, req_view = call("GET", "/projects/%s/requirements/%s" % (pid, rid))
    check("需求 remoteStatus 单条刷新为 Done（本地 status 仍 DONE）",
          (req_view or {}).get("remoteStatus") == "Done"
          and (req_view or {}).get("status") == "DONE", "%s" % (req_view,))

    # ---------- 4. 已是终态：无解决转换跳过，空转不报错 ----------
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid), {})
    check("终态重入 200 且 transitioned=false（词表无命中跳过）",
          st == 200 and (out or {}).get("transitioned") is False, "%s %s" % (st, out))
    check("空转不登记工时", len(mock_worklogs()) == 1, "%s" % (mock_worklogs(),))
    check("空转后 issue 仍 Done",
          (mock_issue(key).get("fields") or {}).get("status", {}).get("name") == "Done", "")

    # ---------- 5. 部分失败：转换成功 + 工时失败 → 200 + worklogError ----------
    mock("/__mutate", {"key": key, "patch": {"status": "In Progress"}})
    mock("/__fail-worklog", {"on": True})
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid),
                   {"seconds": 1800})
    check("部分成功 200 + transitioned=true + worklogError 透出",
          st == 200 and (out or {}).get("transitioned") is True
          and bool((out or {}).get("worklogError")), "%s %s" % (st, out))
    check("转换不回滚（mock 侧已 Done）",
          (mock_issue(key).get("fields") or {}).get("status", {}).get("name") == "Done", "")
    check("工时未登记（timespent 不增）",
          len(mock_worklogs()) == 1
          and (mock_issue(key).get("fields") or {}).get("timespent") == 3600, "")
    mock("/__fail-worklog", {"on": False})

    # ---------- 6. 转换失败：抛错、不发工时、远端不变 ----------
    mock("/__mutate", {"key": key, "patch": {"status": "In Progress"}})
    mock("/__fail-transition", {"on": True})
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid),
                   {"seconds": 1800})
    check("转换失败接口报错（4xx/5xx）", st >= 400, "%s %s" % (st, out))
    check("转换失败不登记工时", len(mock_worklogs()) == 1, "%s" % (mock_worklogs(),))
    check("远端状态未变（仍 In Progress）",
          (mock_issue(key).get("fields") or {}).get("status", {}).get("name") == "In Progress", "")
    mock("/__fail-transition", {"on": False})

    # ---------- 7. name 兜底匹配（toStatus 不含解决词） ----------
    mock("/__transitions", {"transitions": [
        {"id": "11", "name": "开始处理", "toStatus": "处理中"},
        {"id": "41", "name": "Resolve issue", "toStatus": "待关闭"}]})
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid), {})
    check("toStatus 无命中时按 name 兜底（Resolve issue）",
          st == 200 and (out or {}).get("transitioned") is True
          and (out or {}).get("transitionName") == "Resolve issue", "%s %s" % (st, out))
    check("兜底转换只解决不登记（seconds 空）", len(mock_worklogs()) == 1, "")
    mock("/__transitions", {"transitions": None})

    # ---------- 8. 非法入参与来源边界 ----------
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid),
                   {"seconds": 0})
    check("seconds=0 → 400", st == 400, "%s %s" % (st, out))
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid),
                   {"seconds": 400000})
    check("seconds 超上限 → 400", st == 400, "%s %s" % (st, out))
    st, out = call("POST", "/projects/%s/requirements/%s/jira/resolve" % (pid, rid_local), {})
    check("非 Jira 来源需求 → 400", st == 400, "%s %s" % (st, out))

    print("\n== CAP-101 E2E: %d passed, %d failed ==" % (passed, failed))
finally:
    kill_tree(mock_proc.pid)
    if pid:
        try:
            call("DELETE", "/projects/%s" % pid)
        except Exception:
            pass
    shutil.rmtree(REPO, ignore_errors=True)

sys.exit(1 if failed else 0)
