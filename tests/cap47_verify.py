# -*- coding: utf-8 -*-
"""CAP-47 自建需求推送到 Jira E2E（jira-mock，不需要真实 Jira 实例）。

运行前提：
1. 依赖已构建并装进本地仓库：`mvn -q install -DskipTests`。
2. app 独立实例已起（18090 + 独立 H2，禁碰共享库）。**必须显式换掉默认的 local profile**，
   否则 application-local.yml 的 MySQL driver 会与本机共享库撞上（见 CAP-29 文档排错表）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--spring.profiles.active=e2e --server.port=18090 --spring.datasource.url=jdbc:h2:file:./tmp/cap47-db/devmind"
3. 本机有 python（fixtures/jira-mock.py 由本脚本自起，端口 JIRA_MOCK_PORT，默认 18192）。

覆盖：push-targets 一次给齐（候选实例/默认实例/身份来源 BOT/未被同步覆盖/默认值只取同域字段：
不回填经办人、优先级不在实例词表内时不回填）→ push-options 动态
任务类型（子任务被过滤）与优先级（缺 name 的脏条目被过滤）→ 可指派用户（GDPR 拒 query 后自动退
username 重试）→ 推送建 issue（payload 断言：项目/类型/标题/优先级/经办人/标签/截止日期 + 描述尾
回链 + 机器人 Bearer 身份 + 空参数不写进 payload）→ 需求转 JIRA 托管且**不套用托管字段**（本地
fixVersions/reporter 不被 Jira 空值清空）→ 重复推送 409 → 无机器人凭证的实例推送 400 引导绑定
→ 创建被 Jira 拒（__create-error 注入 fields 级错误）时 errorMessages 与 errors **都要**透出、
不再 dump 原始 JSON → 手动 refresh 拉回远端变更 → 无 link 需求 refresh 400 → 建同步配置跑 run：
imported=0、需求总数不变（防「推送过的 issue 被同步重复建成新需求」回归）且 syncCovered 翻真 →
**FR-08 动态必填字段**：create-fields 按可渲染性分区（固定字段只补 duedate、模块/影响版本/修复版本→
多选且候选值来自实例、时间跟踪→专用控件、必填但渲染不了的进 unsupported 且不给假控件、有默认值/
非必填的不进清单）→ 不填动态字段被 mock 逐字段拒 400（与真实 Jira 同形，需求仍 LOCAL）→ 带动态字段
推送成功且 payload 是 Jira 取值形态（[{id}] / {originalEstimate,remainingEstimate}，空值不写）→
护栏：覆盖 description 类字段/取值超两层被 400 拦在本地 → 新端点 404 时退旧端点（清单等价，候选值
走 name 回退）→ 两端点皆 500 时降级为空表 + error（不禁用提交）。

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


def payload_by_summary(summary):
    """按标题取那次创建请求的 fields（比按下标可靠：被拒的请求也会被 mock 记录）"""
    for f in create_payloads():
        if f.get("summary") == summary:
            return f
    return {}


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

    # 平台优先级是固定英文枚举（Highest…Lowest），mock 的实例词表只有 Highest..Low：
    # 用 Lowest 这条「平台有、实例没有」的值验证不回填
    st, rd = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 优先级跨域", "description": "", "priority": "Lowest"})
    rid_d = (rd or {}).get("id")
    check("创建需求 D（优先级 Lowest，不在实例词表内）",
          st == 200 and (rd or {}).get("priority") == "Lowest", "%s %s" % (st, rd))
    if not (rid_a and rid_b and rid_c and rid_d):
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
    check("默认值取需求当前值（标题/优先级命中词表/标签/截止日期）",
          d.get("title") == MARK + " 阈值告警" and d.get("priority") == "High"
          and d.get("labels") == ["e2e", "cap47"] and d.get("dueDate") == "2026-10-31", "%s" % (d,))
    check("不回填经办人（平台 assignee 是人名，Jira 要登录名）", "assignee" not in d, "%s" % (d,))
    st, tgt_d = call("GET", "/projects/%s/requirements/%s/jira/push-targets" % (pid, rid_d))
    check("平台优先级不在实例词表内时不回填（否则推送必被词表校验拒）",
          (tgt_d or {}).get("defaults", {}).get("priority") is None
          and [p.get("name") for p in (tgt_d or {}).get("priorities") or []] == ["Highest", "High", "Medium", "Low"],
          "%s" % ((tgt_d or {}).get("defaults"),))

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

    # ---------- 9b. 创建被 Jira 拒：字段级错误明细逐条透出 ----------
    # 真实场景：项目给该任务类型配了必填自定义字段，Jira 一次回 8~9 条 errors；
    # 原实现把 errors 直接 dump 成 JSON 一行，用户读不出哪些字段必填、也看不到取值错
    mock("/__create-error", {"error": {
        "errorMessages": ["工作流校验失败"],
        "errors": {"components": "模块是必需的。", "customfield_10207": "缺陷类型是必需的。",
                   "timetracking_originalestimate": "初始预估是必需的。",
                   "assignee": "用户 '刘长青' 不存在。"}}})
    st, rejected = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_c), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10002",
        "summary": MARK + " 被拒", "backlinkUrl": backlink, "assigneeName": "刘长青"})
    msg = json.dumps(rejected, ensure_ascii=False)
    check("Jira 拒绝创建 → 400 原样透出", st == 400, "%s %s" % (st, rejected))
    check("errorMessages 与 errors 都在（不因前者存在而丢掉字段明细）",
          "工作流校验失败" in msg and "模块是必需的。" in msg and "缺陷类型是必需的。" in msg
          and "初始预估是必需的。" in msg, "%s" % (msg,))
    check("取值错（用户不存在）也透出，且带字段名", "assignee: 用户 '刘长青' 不存在。" in msg, "%s" % (msg,))
    check("不再 dump 原始 JSON（响应里若还带转义引号 = 原文 JSON 未展开）",
          "\\\"" not in msg, "%s" % (msg,))
    st, c_still = call("GET", "/projects/%s/requirements/%s" % (pid, rid_c))
    check("被拒后需求 C 仍 LOCAL 且无 key",
          (c_still or {}).get("source") == "LOCAL" and not (c_still or {}).get("externalKey"),
          "%s" % (c_still,))
    mock("/__create-error", {"error": None})

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

    # ---------- 12. FR-08 动态必填字段（createmeta 驱动） ----------
    # 用户真实撞到的那组：项目给该任务类型配了 模块/影响版本/修复版本/到期日/时间跟踪 + 一个下拉
    # 自定义字段，全为必填（原先只能在 Jira 侧看到 400 才知道要配什么）。
    renderable = [
        {"fieldId": "summary", "name": "摘要", "required": True, "hasDefaultValue": False,
         "schema": {"type": "string", "system": "summary"}},
        # 非必填但**在创建界面上**：平台只在 createmeta 里有它时才写 description（否则 Jira 回
        # 「Field 'description' cannot be set」）。缺了这条，服务端的回链会被正确的 writable 闸门丢掉，
        # 12c 的「回链仍由服务端追加」就测不到东西了。
        {"fieldId": "description", "name": "描述", "required": False, "hasDefaultValue": False,
         "schema": {"type": "string", "system": "description"}},
        {"fieldId": "components", "name": "模块", "required": True, "hasDefaultValue": False,
         "schema": {"type": "array", "items": "component", "system": "components"},
         "allowedValues": [{"id": "10000", "name": "后端"}, {"id": "10001", "name": "前端"}]},
        {"fieldId": "versions", "name": "影响版本", "required": True, "hasDefaultValue": False,
         "schema": {"type": "array", "items": "version", "system": "versions"},
         "allowedValues": [{"id": "10100", "name": "1.0"}, {"id": "10101", "name": "2.0"}]},
        {"fieldId": "fixVersions", "name": "修复的版本", "required": True, "hasDefaultValue": False,
         "schema": {"type": "array", "items": "version", "system": "fixVersions"},
         "allowedValues": [{"id": "10100", "name": "1.0"}, {"id": "10101", "name": "2.0"}]},
        {"fieldId": "duedate", "name": "到期日", "required": True, "hasDefaultValue": False,
         "schema": {"type": "date", "system": "duedate"}},
        {"fieldId": "timetracking", "name": "时间跟踪", "required": True, "hasDefaultValue": False,
         "schema": {"type": "timetracking", "system": "timetracking"}},
        {"fieldId": "customfield_10207", "name": "缺陷类型", "required": True, "hasDefaultValue": False,
         "schema": {"type": "option", "customId": 10207},
         "allowedValues": [{"id": "10201", "value": "功能缺陷"}, {"id": "10202", "value": "性能缺陷"}]},
        {"fieldId": "customfield_10606", "name": "缺陷引入的活动", "required": True, "hasDefaultValue": True,
         "schema": {"type": "option", "customId": 10606},
         "allowedValues": [{"id": "10601", "value": "需求分析"}]},  # 有默认值：Jira 自填，不该让用户填
        {"fieldId": "priority", "name": "优先级", "required": False, "hasDefaultValue": True,
         "schema": {"type": "option", "system": "priority"},
         "allowedValues": [{"id": "3", "name": "中"}]},  # 非必填：不进清单
    ]
    # 必填但平台渲染不了的：用户选择器、级联选择（option 类型却无候选值）
    unrenderable = [
        {"fieldId": "customfield_10700", "name": "归属组织", "required": True, "hasDefaultValue": False,
         "schema": {"type": "option", "customId": 10700}},
        {"fieldId": "reporter", "name": "报告人", "required": True, "hasDefaultValue": False,
         "schema": {"type": "user", "system": "reporter"}},
    ]
    mock("/__createmeta", {"fields": renderable + unrenderable, "enforce": True})

    st, re = call("POST", "/projects/%s/requirements" % pid, {
        "title": MARK + " 动态字段", "description": "推送前先问 Jira 要填什么。",
        "fixVersions": ["2.0", "不存在的版本"]})   # 只有 2.0 命中实例候选值
    rid_e, code_e = (re or {}).get("id"), (re or {}).get("code")
    check("创建需求 E（修复版本含一个不在实例候选值内的本地值）",
          st == 200 and bool(rid_e) and (re or {}).get("source") == "LOCAL", "%s %s" % (st, re))
    if not rid_e:
        sys.exit(1)

    cf_url = ("/projects/%s/requirements/%s/jira/create-fields"
              "?integrationId=%s&jiraProjectKey=PROJ&issueTypeId=10001") % (pid, rid_e, ia_id)
    st, cf = call("GET", cf_url)
    check("create-fields 200", st == 200, "%s %s" % (st, cf))
    cf = cf or {}
    check("必填但已有输入项的固定字段只下发 duedate（标题本就必须，不重复渲染）",
          cf.get("requiredFixed") == ["duedate"], "%s" % (cf.get("requiredFixed"),))
    check("新渲染字段与控件映射（模块/影响版本/修复版本→多选，时间跟踪→专用控件，选项→下拉）",
          [(f.get("id"), f.get("control")) for f in cf.get("fields") or []]
          == [("components", "MULTI_SELECT"), ("versions", "MULTI_SELECT"),
              ("fixVersions", "MULTI_SELECT"), ("timetracking", "TIMETRACKING"),
              ("customfield_10207", "SELECT")],
          "%s" % ([(f.get("id"), f.get("control")) for f in cf.get("fields") or []],))
    fields_cf = cf.get("fields") or [{}]
    check("候选值来自实例（模块的组件名 + 选项自定义字段取 value 作展示名）",
          [o.get("name") for o in fields_cf[0].get("options") or []] == ["后端", "前端"]
          and [o.get("name") for o in fields_cf[4].get("options") or []] == ["功能缺陷", "性能缺陷"],
          "%s" % (fields_cf,))
    check("渲染不了的必填字段进 unsupported 且 control 为空（不给一个填什么都必被拒的假控件）",
          [(f.get("id"), f.get("control")) for f in cf.get("unsupported") or []]
          == [("customfield_10700", None)], "%s" % (cf.get("unsupported"),))
    # reporter 走另一条路：平台不推它（Jira 按写身份自动回填），**静默跳过**而不是进 unsupported——
    # 早先进 unsupported 会把弹窗提交整个禁掉（见 511b99e），故这里钉住「不在禁提交清单里、但确实在创建界面上」
    check("必填的 reporter 静默跳过（平台不推、Jira 自填），不进 unsupported 也不进待填字段",
          "reporter" in (cf.get("availableFields") or [])
          and not any(f.get("id") == "reporter"
                      for f in (cf.get("fields") or []) + (cf.get("unsupported") or [])),
          "%s" % (cf,))
    check("有默认值的必填字段与非必填字段都不进任何清单（Jira 自填，不打扰用户）",
          not any(f.get("id") in ("customfield_10606", "priority")
                  for f in (cf.get("fields") or []) + (cf.get("unsupported") or [])), "%s" % (cf,))
    check("修复版本按同域命中项预填（2.0→10101，不猜不存在的版本）",
          cf.get("prefill") == {"fixVersions": ["10101"]}, "%s" % (cf.get("prefill"),))
    check("元数据拉取无错误", cf.get("error") is None, "%s" % (cf.get("error"),))

    # 12b. 不填动态字段必被拒（mock 按目录逐字段校验必填，与真实 Jira 同形）
    st, miss = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_e), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 缺动态字段", "backlinkUrl": backlink})
    msg_miss = json.dumps(miss, ensure_ascii=False)
    check("缺动态必填字段被 Jira 拒 400，且逐字段透出（模块/影响版本/修复版本/时间跟踪）",
          st == 400 and "模块是必需的。" in msg_miss and "影响版本是必需的。" in msg_miss
          and "修复的版本是必需的。" in msg_miss and "时间跟踪是必需的。" in msg_miss,
          "%s %s" % (st, miss))
    fm = payload_by_summary(MARK + " 缺动态字段")
    check("那次 payload 里确实没有动态字段（证明拒绝来自 Jira 的必填校验）",
          bool(fm) and not any(k in fm for k in ("components", "versions", "fixVersions",
                                                "timetracking", "customfield_10207")), "%s" % (fm,))
    st, e_local = call("GET", "/projects/%s/requirements/%s" % (pid, rid_e))
    check("被拒后需求 E 仍 LOCAL 且无 key",
          (e_local or {}).get("source") == "LOCAL" and not (e_local or {}).get("externalKey"),
          "%s" % (e_local,))

    # 12c. 带动态字段推送：payload 断言 Jira 取值形态（[{id}] / {originalEstimate}）。
    # 目录换成「全部可渲染」的那批——渲染不了的字段正是弹窗禁用提交的原因，这里要看成功路径。
    mock("/__createmeta", {"fields": renderable})
    st, cf_ok = call("GET", cf_url)
    check("目录去掉渲染不了的字段后 unsupported 为空（弹窗恢复可提交）",
          (cf_ok or {}).get("unsupported") == [] and len((cf_ok or {}).get("fields") or []) == 5,
          "%s" % (cf_ok,))
    st, res_e = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_e), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 动态字段（Jira）", "backlinkUrl": backlink, "dueDate": "2026-11-30",
        "extraFields": {
            "components": [{"id": "10000"}], "versions": [{"id": "10100"}],
            "fixVersions": [{"id": "10101"}], "customfield_10207": {"id": "10201"},
            "timetracking": {"originalEstimate": "2h", "remainingEstimate": "1h"},
            "customfield_10700": None}})
    key_e = (res_e or {}).get("externalKey")
    check("带动态字段推送成功（mock 的逐字段必填校验全通过）",
          st == 200 and (key_e or "").startswith("PROJ-"), "%s %s" % (st, res_e))
    fe = payload_by_summary(MARK + " 动态字段（Jira）")
    check("payload：动态字段按 Jira 取值形态原样写入（组件/影响版本/修复版本是 [{id}]）",
          fe.get("components") == [{"id": "10000"}] and fe.get("versions") == [{"id": "10100"}]
          and fe.get("fixVersions") == [{"id": "10101"}], "%s" % (fe,))
    check("payload：时间跟踪是 {originalEstimate, remainingEstimate}，下拉选项是 {id}",
          fe.get("timetracking") == {"originalEstimate": "2h", "remainingEstimate": "1h"}
          and fe.get("customfield_10207") == {"id": "10201"}, "%s" % (fe,))
    check("payload：到期日是固定表单的值（必填校验加在固定控件上，不来自动态字段）",
          fe.get("duedate") == "2026-11-30", "%s" % (fe,))
    check("payload：空值动态字段不写进 payload", "customfield_10700" not in fe, "%s" % (fe,))
    check("payload：回链仍由服务端追加，动态字段不能顺带改标题/项目",
          fe.get("summary") == MARK + " 动态字段（Jira）"
          and (fe.get("description") or "").endswith("%s · %s" % (code_e, backlink))
          and fe.get("project", {}).get("key") == "PROJ", "%s" % (fe,))
    st, e_done = call("GET", "/projects/%s/requirements/%s" % (pid, rid_e))
    check("推送成功后需求 E 转 JIRA 托管",
          (e_done or {}).get("source") == "JIRA" and (e_done or {}).get("externalKey") == key_e,
          "%s" % (e_done,))

    # 12d. 服务端护栏：不许覆盖平台管理的字段 / 取值形态超两层
    st, g1 = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_c), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 越权动态字段", "backlinkUrl": backlink,
        "extraFields": {"description": "被覆盖的回链"}})
    check("动态字段覆盖 description/summary/project 类字段被拒 400",
          st == 400 and "平台管理" in json.dumps(g1, ensure_ascii=False), "%s %s" % (st, g1))
    st, g2 = call("POST", "/projects/%s/requirements/%s/jira/push" % (pid, rid_c), {
        "integrationId": ia_id, "jiraProjectKey": "PROJ", "issueTypeId": "10001",
        "summary": MARK + " 越深取值", "backlinkUrl": backlink,
        "extraFields": {"components": [{"id": {"nested": "x"}}]}})
    check("动态字段取值形态超两层被拒 400（不把任意 JSON 转手发给 Jira）",
          st == 400 and "取值非法" in json.dumps(g2, ensure_ascii=False), "%s %s" % (st, g2))
    check("护栏拒掉的推送未到 Jira（远端没有这两条）",
          not payload_by_summary(MARK + " 越权动态字段") and not payload_by_summary(MARK + " 越深取值"),
          "%s" % ([f.get("summary") for f in create_payloads()],))

    # 12e. 旧端点兜底：新端点 404（Jira <8.4）时退 createmeta?projectKeys=
    mock("/__createmeta", {"fields": renderable + unrenderable, "notFound": True})
    st, cf_legacy = call("GET", cf_url)
    check("新端点 404 时退旧端点，字段清单等价（候选值走 name 回退）",
          st == 200
          and [(f.get("id"), f.get("control")) for f in (cf_legacy or {}).get("fields") or []]
          == [(f.get("id"), f.get("control")) for f in fields_cf]
          and (cf_legacy or {}).get("requiredFixed") == ["duedate"]
          and [o.get("name") for o in ((cf_legacy or {}).get("fields") or [{}])[0].get("options") or []]
          == ["后端", "前端"]
          and [(f.get("id"), f.get("control")) for f in (cf_legacy or {}).get("unsupported") or []]
          == [("customfield_10700", None)], "%s" % (cf_legacy,))
    st, state_legacy = mock("/__state")
    legacy_hits = [r["path"] for r in (state_legacy or {}).get("requests", [])
                   if r["path"] == "/rest/api/2/issue/createmeta"]
    check("确实退到了旧端点（不是静默返回空清单）", bool(legacy_hits), "%s" % (legacy_hits,))

    # 12f. 两个端点都不可用：降级为空表 + error，但**不禁用提交**（读接口抖动不该堵死能推的类型）
    mock("/__createmeta", {"fail": True})
    st, cf_broken = call("GET", cf_url)
    check("元数据拉不到 → 200 + error + 空清单（降级而非报错）",
          st == 200 and (cf_broken or {}).get("error") and not (cf_broken or {}).get("fields")
          and not (cf_broken or {}).get("unsupported"), "%s %s" % (st, cf_broken))
    mock("/__createmeta", {"fields": [], "enforce": False, "notFound": False, "fail": False})
    st, cf_empty = call("GET", cf_url)
    check("目录为空 → 清单全空且无错误（提交不禁用）",
          st == 200 and not (cf_empty or {}).get("fields") and not (cf_empty or {}).get("unsupported")
          and (cf_empty or {}).get("error") is None, "%s %s" % (st, cf_empty))

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
