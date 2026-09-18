# -*- coding: utf-8 -*-
"""CAP-47 自建需求推送到 Jira E2E（jira-mock，不需要真实 Jira 实例）。

运行前提：
1. 依赖已构建并装进本地仓库：`mvn -q install -DskipTests`。
2. app 独立实例已起（18090 + 独立 H2，禁碰共享库）。**必须显式换掉默认的 local profile**，
   否则 application-local.yml 的 MySQL driver 会与本机共享库撞上（见 CAP-29 文档排错表）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=e2e --server.port=18090 --spring.datasource.url=jdbc:h2:file:./tmp/cap47-db/devmind"
3. 本机有 python（fixtures/jira-mock.py 由本脚本自起，端口 JIRA_MOCK_PORT，默认 18192）。

覆盖：push-targets 一次给齐（候选实例/默认实例/身份来源 BOT/未被同步覆盖）→ push-options 动态
任务类型（子任务被过滤）与优先级（缺 name 的脏条目被过滤）→ 可指派用户（GDPR 拒 query 后自动退
username 重试）→ 推送建 issue（payload 断言：项目/类型/标题/优先级/经办人/标签/截止日期 + 描述尾
回链 + 机器人 Bearer 身份 + 空参数不写进 payload）→ 需求转 JIRA 托管且**不套用托管字段**（本地
fixVersions/reporter 不被 Jira 空值清空）→ 重复推送 409 → 无机器人凭证的实例推送 400 引导绑定
→ 手动 refresh 拉回远端变更 → 无 link 需求 refresh 400 → 建同步配置跑 run：imported=0、需求总数
不变（防「推送过的 issue 被同步重复建成新需求」回归）且 syncCovered 翻真。

复用同一 H2 时实例可能残留，故候选实例相关断言用「包含 / 与 instances 首条一致」而非精确计数。
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
MARK = "e2e-cap47-%d" % int(time.time())
REPO = TMP / ("cap47-repo-" + MARK)  # 每轮独立目录：LOCAL 项目要求 path 是真 git 仓库
TOKEN = None
pid = None
ia_id = None
ib_id = None
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
    """打 jira-mock 数据面/控制面（不带平台 token）"""
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


def create_payloads():
    """mock 收到的 POST /issue 请求体（按时间顺序取 fields）"""
    st, state = mock("/__state")
    return [(r.get("body") or {}).get("fields") or {} for r in (state or {}).get("requests", [])
            if r["method"] == "POST" and r["path"] == "/rest/api/2/issue"]


def create_auths():
    st, state = mock("/__state")
    return [r["auth"] for r in (state or {}).get("requests", [])
            if r["method"] == "POST" and r["path"] == "/rest/api/2/issue"]


# ---------- 起 jira-mock ----------
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

    # ---------- 1. LOCAL 项目（真 git 仓库）+ 三个需求 ----------
    REPO.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@local",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@local")
    subprocess.run(["git", "init", "-b", "main"], cwd=REPO, check=True, capture_output=True)
    (REPO / "README.md").write_text("# cap47 e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "-A"], cwd=REPO, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=REPO, check=True,
                   capture_output=True, env=env)

    st, proj = call("POST", "/projects", {
        "name": MARK, "sourceType": "LOCAL", "path": str(REPO), "defaultBranch": "main"})
    pid = (proj or {}).get("id")
    check("创建 LOCAL 项目", st == 200 and bool(pid), "%s %s" % (st, proj))
    if not pid:
        sys.exit(1)

    st, ra = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 阈值告警", "description": "支持配置化告警阈值。",
        "type": "FEATURE", "priority": "High", "assignee": "zhangsan",
        "reporter": "李四", "labels": ["e2e", "cap47"],
        "fixVersions": ["1.0"], "dueDate": "2026-10-31"})
    rid_a, code_a = (ra or {}).get("id"), (ra or {}).get("code")
    check("创建需求 A（优先级/标签/经办人/修复版本/报告人/截止日期齐备）",
          st == 200 and bool(rid_a) and (ra or {}).get("source") == "LOCAL", "%s %s" % (st, ra))

    st, rb = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 最小参数", "description": ""})
    rid_b, code_b = (rb or {}).get("id"), (rb or {}).get("code")
    check("创建需求 B（仅标题，留作空参数用例）", st == 200 and bool(rid_b), "%s %s" % (st, rb))

    st, rc = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 不推送", "description": ""})
    rid_c = (rc or {}).get("id")
    check("创建需求 C（留作负例）", st == 200 and bool(rid_c), "%s %s" % (st, rc))
    if not (rid_a and rid_b and rid_c):
        sys.exit(1)

    # ---------- 2. JIRA 集成：A 带机器人凭证，B 无凭证 ----------
    st, ia = call("POST", "/integrations", {
        "type": "JIRA", "name": MARK + " 实例A", "baseUrl": MOCK_BASE,
        "authType": "PAT", "token": "e2e-jira-token"})
    ia_id = (ia or {}).get("id")
    check("创建 JIRA 集成 A（机器人凭证）", st == 200 and bool(ia_id), "%s %s" % (st, ia))
    st, t = call("POST", "/integrations/%s/test" % ia_id)
    check("集成 A 连接测试通过", st == 200 and (t or {}).get("ok") is True, "%s %s" % (st, t))

    st, ib = call("POST", "/integrations", {
        "type": "JIRA", "name": MARK + " 实例B（无凭证）", "baseUrl": MOCK_BASE})
    ib_id = (ib or {}).get("id")
    check("创建 JIRA 集成 B（无任何凭证）", st == 200 and bool(ib_id), "%s %s" % (st, ib))
    if not (ia_id and ib_id):
        sys.exit(1)

    # ---------- 3. push-targets：一次给齐 ----------
    st, tgt = call("GET", "/projects/%s/requirements/%s/jira/push-targets" % (pid, rid_a))
    check("push-targets 200", st == 200, "%s %s" % (st, tgt))
    tgt = tgt or {}
    inst_ids = [i.get("id") for i in (tgt.get("instances") or [])]
    check("候选实例含 A/B", ia_id in inst_ids and ib_id in inst_ids, "%s" % (inst_ids,))
    check("无同步配置时默认实例取候选首条",
          tgt.get("defaultIntegrationId") == (inst_ids[0] if inst_ids else None), "%s" % (tgt,))
    check("身份来源 BOT（实例机器人凭证）", tgt.get("identitySource") == "BOT",
          "%s" % (tgt.get("identitySource"),))
    check("尚无同步配置 → syncCovered=false", tgt.get("syncCovered") is False, "%s" % (tgt,))
    check("选项拉取无错误", tgt.get("optionsError") is None, "%s" % (tgt.get("optionsError"),))
    check("Jira 项目清单来自实例（PROJ/OPS）",
          [p.get("id") for p in (tgt.get("jiraProjects") or [])] == ["PROJ", "OPS"],
          "%s" % (tgt.get("jiraProjects"),))
    check("无默认项目 key → 不预拉任务类型且 defaultJiraProjectKey 为空",
          not tgt.get("defaultJiraProjectKey") and not (tgt.get("issueTypes") or []),
          "%s" % (tgt,))
    d = tgt.get("defaults") or {}
    check("默认值取需求当前值（标题/优先级/标签/经办人/截止日期）",
          d.get("title") == MARK + " 阈值告警" and d.get("priority") == "High"
          and d.get("labels") == ["e2e", "cap47"] and d.get("assignee") == "zhangsan"
          and d.get("dueDate") == "2026-10-31", "%s" % (d,))

    # ---------- 4. push-options：任务类型（子任务过滤）/ 优先级（脏条目过滤） ----------
    st, opt = call("GET", "/projects/%s/requirements/%s/jira/push-options"
                   "?integrationId=%s&jiraProjectKey=PROJ" % (pid, rid_a, ia_id))
    check("push-options 200", st == 200, "%s %s" % (st, opt))
    opt = opt or {}
    names = [t.get("name") for t in (opt.get("issueTypes") or [])]
    check("任务类型动态拉取且过滤子任务", names == ["任务", "缺陷"], "%s" % (names,))
    prios = [p.get("name") for p in (opt.get("priorities") or [])]
    check("优先级词表过滤缺 name 的脏条目",
          prios == ["Highest", "High", "Medium", "Low"], "%s" % (prios,))

    # ---------- 5. 可指派用户（含 GDPR 退 username 重试） ----------
    st, users = call("GET", "/projects/%s/requirements/%s/jira/assignable-users"
                     "?integrationId=%s&jiraProjectKey=PROJ&q=lisi" % (pid, rid_a, ia_id))
    check("经办人搜索命中（displayName 透出）",
          st == 200 and [u.get("displayName") for u in (users or [])] == ["李四"], "%s %s" % (st, users))
    mock("/__gdpr", {"on": True})
    st, users2 = call("GET", "/projects/%s/requirements/%s/jira/assignable-users"
                      "?integrationId=%s&jiraProjectKey=PROJ&q=wangwu" % (pid, rid_a, ia_id))
    check("GDPR 拒 query 后自动退 username 重试（displayName 退 name）",
          st == 200 and [u.get("displayName") for u in (users2 or [])] == ["wangwu"],
          "%s %s" % (st, users2))
    mock("/__gdpr", {"on": False})

    # ---------- 6. 推送 A（全参数） ----------
    backlink = "%s/projects/%s/requirements/%s" % (MOCK_BASE, pid, rid_a)  # 真实前端用 window.location.origin
    st, res = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_a), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 阈值告警（Jira）", "description": "支持配置化告警阈值。",
        "backlinkUrl": backlink, "priorityName": "High", "assigneeName": "lisi",
        "labels": ["e2e", "cap47"], "dueDate": "2026-10-31"})
    key_a = (res or {}).get("externalKey")
    check("推送成功（key 前缀 = Jira 项目 key）",
          st == 200 and (key_a or "").startswith("PROJ-"), "%s %s" % (st, res))
    check("结果带回读到的远端状态 / 任务类型",
          (res or {}).get("remoteStatus") == "To Do" and (res or {}).get("issueType") == "任务",
          "%s" % (res,))
    check("结果 syncCovered=false（尚无同步配置）", (res or {}).get("syncCovered") is False, "%s" % (res,))

    payloads = create_payloads()
    check("mock 收到 1 次创建", len(payloads) == 1, "%s" % (len(payloads),))
    f = payloads[0] if payloads else {}
    check("payload：项目 key / 任务类型 id / 标题",
          f.get("project", {}).get("key") == "PROJ"
          and f.get("issuetype", {}).get("id") == "10001"
          and f.get("summary") == MARK + " 阈值告警（Jira）", "%s" % (f,))
    check("payload：优先级 / 经办人 / 标签 / 截止日期",
          f.get("priority", {}).get("name") == "High"
          and f.get("assignee", {}).get("name") == "lisi"
          and f.get("labels") == ["e2e", "cap47"]
          and f.get("duedate") == "2026-10-31", "%s" % (f,))
    check("payload：描述尾部强制追加平台回链",
          (f.get("description") or "").endswith("\n\n%s · %s" % (code_a, backlink)),
          "%r" % (f.get("description"),))
    check("payload：用机器人凭证（Bearer）创建",
          create_auths() == ["Bearer e2e-jira-token"], "%s" % (create_auths(),))

    # 转托管：source/externalKey 就位，但托管字段未被套用（FR-04 边界）
    st, a = call("GET", "/projects/%s/requirements/%s" % (pid, rid_a))
    check("需求 A 转 JIRA 托管并落 externalKey",
          (a or {}).get("source") == "JIRA" and (a or {}).get("externalKey") == key_a, "%s" % (a,))
    check("externalUrl / remoteStatus 由链接反查补出",
          (a or {}).get("externalUrl", "").endswith("/browse/" + (key_a or ""))
          and (a or {}).get("remoteStatus") == "To Do", "%s" % (a,))
    check("推送不套用托管字段：本地修复版本/报告人未被 Jira 空值清空",
          (a or {}).get("fixVersions") == ["1.0"] and (a or {}).get("reporter") == "李四",
          "fixVersions=%s reporter=%s" % ((a or {}).get("fixVersions"), (a or {}).get("reporter")))
    check("推送不动本地字段：priority/labels/assignee/dueDate 原样",
          (a or {}).get("priority") == "High" and (a or {}).get("labels") == ["e2e", "cap47"]
          and (a or {}).get("assignee") == "zhangsan" and (a or {}).get("dueDate") == "2026-10-31",
          "%s" % (a,))

    # ---------- 7. 需求级幂等：重复推送 409 ----------
    st, dup = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_a), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": "再来一次", "backlinkUrl": backlink})
    check("重复推送 409 且回传既有 key",
          st == 409 and key_a in json.dumps(dup, ensure_ascii=False), "%s %s" % (st, dup))
    check("重复推送未再建 issue", len(create_payloads()) == 1, "%s" % (len(create_payloads()),))

    # ---------- 8. 推送 B（最小参数）：空参数不写进 payload ----------
    st, res_b = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_b), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10002",
        "summary": MARK + " 最小参数", "backlinkUrl": backlink})
    key_b = (res_b or {}).get("externalKey")
    check("推送 B 成功（无优先级/经办人/标签/截止日期）",
          st == 200 and bool(key_b), "%s %s" % (st, res_b))
    payloads = create_payloads()
    fb = payloads[1] if len(payloads) > 1 else {}
    check("空参数不写进 payload（不显式清空 Jira 侧默认值）",
          not any(k in fb for k in ("priority", "assignee", "labels", "duedate")), "%s" % (fb,))
    check("描述为空时只推回链",
          (fb.get("description") or "") == "%s · %s" % (code_b, backlink),
          "%r" % (fb.get("description"),))

    # ---------- 9. 无可用凭据的实例：400 引导绑定，且不落任何本地状态 ----------
    st, bad = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_c), {
        "integrationId": ib_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 无凭证", "backlinkUrl": backlink})
    check("无凭证实例推送 400 且引导绑定第三方账号",
          st == 400 and "第三方账号" in json.dumps(bad, ensure_ascii=False), "%s %s" % (st, bad))
    st, c_before = call("GET", "/projects/%s/requirements/%s" % (pid, rid_c))
    check("失败推送不落本地状态：需求 C 仍 LOCAL 且无 key",
          (c_before or {}).get("source") == "LOCAL" and not (c_before or {}).get("externalKey"),
          "%s" % (c_before,))
    check("失败推送未在远端建 issue", len(create_payloads()) == 2, "%s" % (len(create_payloads()),))

    # ---------- 10. 手动 refresh：拉回远端变更 ----------
    mock("/__mutate", {"key": key_a, "patch": {
        "status": "In Progress", "summary": MARK + " 阈值告警（Jira 改过）",
        "priority": "Medium", "assignee": "wangwu", "fixVersions": ["9.9"],
        "duedate": "2026-12-01"}})
    st, rf = call("POST", "/projects/%s/requirements/%s/jira/refresh" % (pid, rid_a))
    check("手动刷新 200 且回传远端状态",
          st == 200 and (rf or {}).get("remoteStatus") == "In Progress", "%s %s" % (st, rf))
    st, a2 = call("GET", "/projects/%s/requirements/%s" % (pid, rid_a))
    check("刷新拉回托管字段（标题/优先级/经办人/修复版本/截止日期）",
          (a2 or {}).get("title") == MARK + " 阈值告警（Jira 改过）"
          and (a2 or {}).get("priority") == "Medium"
          and (a2 or {}).get("assignee") == "wangwu"
          and (a2 or {}).get("fixVersions") == ["9.9"]
          and (a2 or {}).get("dueDate") == "2026-12-01", "%s" % (a2,))
    check("刷新后 remoteStatus 就位且仍为 JIRA 托管",
          (a2 or {}).get("remoteStatus") == "In Progress"
          and (a2 or {}).get("source") == "JIRA" and (a2 or {}).get("externalKey") == key_a,
          "%s" % (a2,))

    st, rf_c = call("POST", "/projects/%s/requirements/%s/jira/refresh" % (pid, rid_c))
    check("无关联 issue 的需求刷新被拒 400",
          st == 400 and "未关联" in json.dumps(rf_c, ensure_ascii=False), "%s %s" % (st, rf_c))

    # ---------- 11. 同步回归：JQL 覆盖同一 issue 时不重复建需求 ----------
    st, before_page = call("GET", "/projects/%s/requirements?size=100" % pid)
    before = (before_page or {}).get("total")
    st, cfg = call("POST", "/projects/%s/jira-sync" % pid, {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "enabled": True, "pollIntervalSec": 300})
    cfg_id = (cfg or {}).get("id")
    check("创建同步配置", st == 200 and bool(cfg_id), "%s %s" % (st, cfg))

    st, tgt2 = call("GET", "/projects/%s/requirements/%s/jira/push-targets" % (pid, rid_c))
    check("有 enabled 同步配置后 syncCovered=true（弹窗据此可提示手动刷新兜底）",
          (tgt2 or {}).get("syncCovered") is True, "%s" % (tgt2,))
    check("有默认项目 key 后预拉任务类型（子任务仍被过滤）",
          [t.get("name") for t in (tgt2 or {}).get("issueTypes") or []] == ["任务", "缺陷"],
          "%s" % ((tgt2 or {}).get("issueTypes"),))
    check("默认目标切到同步配置（实例 + 项目 key）",
          (tgt2 or {}).get("defaultIntegrationId") == ia_id
          and (tgt2 or {}).get("defaultJiraProjectKey") == "PROJ", "%s" % (tgt2,))

    st, run = call("POST", "/projects/%s/jira-sync/%s/run" % (pid, cfg_id))
    check("同步 run 无错误", st == 200 and not (run or {}).get("error"), "%s %s" % (st, run))
    check("同步 imported=0（已推送的 issue 不再重复建需求）", (run or {}).get("imported") == 0,
          "%s" % (run,))
    check("同步 updated=2（两条已推送需求随同步刷新）", (run or {}).get("updated") == 2, "%s" % (run,))
    st, after_page = call("GET", "/projects/%s/requirements?size=100" % pid)
    check("同步后需求总数不变",
          (after_page or {}).get("total") == before,
          "before=%s after=%s" % (before, (after_page or {}).get("total")))

    print("\n== CAP-47 E2E: %d passed, %d failed ==" % (passed, failed))
finally:
    kill_tree(mock_proc.pid)
    if pid:
        try:
            call("DELETE", "/projects/%s" % pid)
        except Exception:
            pass
    shutil.rmtree(REPO, ignore_errors=True)

sys.exit(1 if failed else 0)
