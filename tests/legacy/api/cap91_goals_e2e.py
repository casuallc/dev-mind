# -*- coding: utf-8 -*-
r"""CAP-91 工作目标与 OKR 对齐 E2E（自起隔离 app，纯 DB REST 链路，无需 runner/外部服务）。

运行前提：`mvn -q install -DskipTests`；JDK 21（JAVA_HOME 未设时回落本机 Adoptium 路径）。
脚本自起隔离 app（:18106，--spring.profiles.active=e2e 独立 H2，定时开关全关），跑完杀掉。
（知识注入检索能力的同名脚本为 cap92_e2e.py，本脚本用 goals 后缀区分。）

验收覆盖（CAP-91 §9）：
  [1] 目标 CRUD 与层级校验：年 O/季 O/KR 新增落库；KR 无父 400、父跨周期 400、YEAR O 带父 400、
      period 格式非法 400、progress 越界 400；编辑/改进度生效；他人目标 404（改/删）、
      他人 O 作父 400；删有子项的 O 400；删 KR 后关联计划项 kr_id 置空；
      framework（M4）：缺省回传 OKR、非 OKR 400、更新改 framework 400
  [2] 滚转：KR 单独滚转无 krParentMap 400；O+KR 同批滚转副本挂副本、原项 CARRIED、
      副本 ACTIVE+carriedFromId；重复滚转幂等（skipped）
  [3] 计划对齐：计划项挂 krId 落库；krId 指 O 400；他人 KR 400；计划列表附 krTitles；
      linkable-krs 只回该日所在季 ACTIVE KR；周滚转复制 kr_id
  [4] 派生进度（FR-07）：KR 关联 2 计划项 1 DONE → suggestedProgress=50、linkedHours 只计
      DONE 条目工时；无关联项 suggestedProgress 为 null；人工 progress 不被派生值覆盖
  [5] 季度复盘（M2）：手动空白稿幂等、编辑保存、查询；review-preview 确定性渲染
      （OKR 对照含 KR 行 + 计划外聚合含未对齐 DONE 条目）；非 force 已有行 reused；
      无素材季度 generate 400
  M3（suggest KR 标注）的 prompt/解析/落库校验由单测覆盖（ReportPromptsTest/
  PlanSuggestParserTest/WorklogPlanServiceTest），E2E 不起 runner 不盖生成链路。
"""
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import json
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PORT = int(os.environ.get("E2E_PORT", "18106"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap91-goals-e2e"
APP_LOG = TMP / "app.log"
DB_URL = f'jdbc:h2:file:{(TMP / "db/devmind").as_posix()};AUTO_SERVER=TRUE'

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

TODAY = date.today()
MONDAY = TODAY - timedelta(days=TODAY.weekday())
MONDAY_S = MONDAY.isoformat()
NEXT_MONDAY_S = (MONDAY + timedelta(days=7)).isoformat()
# 目标/计划锚定「本周周一」所在年季（周一可能与今天跨季，统一以周一为准）
Y = MONDAY.year
Q = (MONDAY.month - 1) // 3 + 1
QP = f"{Y}-Q{Q}"
NQ = f"{Y}-Q{Q + 1}" if Q < 4 else f"{Y + 1}-Q1"
NQ_YEAR = Y if Q < 4 else Y + 1
# 年内另一个无数据季度（复盘 precheck 400 用；Q==4 时全用过了，取明年 Q1）
EMPTY_Q = next((f"{Y}-Q{i}" for i in range(1, 5) if f"{Y}-Q{i}" not in (QP, NQ)), f"{Y + 1}-Q1")

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
    log = open(APP_LOG, "a", encoding="utf-8")
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["SPRING_DATASOURCE_URL"] = DB_URL
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


def _force_rmtree(path):
    def _onerror(func, p, _exc):
        os.chmod(p, 0o666)
        func(p)
    if os.path.exists(path):
        shutil.rmtree(path, onerror=_onerror)


def quarter_section(tree, period):
    return next((q for q in tree["quarters"] if q["period"] == period), None)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    _force_rmtree(TMP)
    TMP.mkdir(parents=True, exist_ok=True)
    kill_port_strays()

    app, app_log = start_app()
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        bob_name = f"cap91bob{int(time.time()) % 100000}"
        req("POST", "/auth/users", {"username": bob_name, "displayName": "b",
                                    "password": "pass12345", "role": "DEVELOPER"}, token)
        bob = req("POST", "/auth/login", {"username": bob_name, "password": "pass12345"})["accessToken"]
        print(f"[0] app 已起（:{PORT}），admin + 第二用户登录成功（锚定 {Y} 年 {QP}）")

        # ---------- [1] 目标 CRUD 与层级校验 ----------
        y_o = req("POST", "/worklog/goals",
                  {"scope": "YEAR", "period": str(Y), "kind": "OBJECTIVE", "title": "年度目标：平台跃迁"}, token)
        check("年度 O 落库（ACTIVE/0）", y_o.get("status") == "ACTIVE" and y_o.get("progress") == 0,
              json.dumps(y_o, ensure_ascii=False)[:200])
        # framework（CAP-91 M4）：缺省回传 OKR；非 OKR 400；更新改 framework 400（身份属性）
        check("framework 缺省回传 OKR", y_o.get("framework") == "OKR", json.dumps(y_o)[:200])
        req("POST", "/worklog/goals",
            {"scope": "YEAR", "period": str(Y), "kind": "OBJECTIVE", "title": "x", "framework": "KPI"},
            token, expect=400)
        check("非 OKR framework 400", True)
        req("PUT", f"/worklog/goals/{y_o['id']}",
            {"scope": "YEAR", "period": str(Y), "kind": "OBJECTIVE", "title": "年度目标：平台跃迁",
             "framework": "KPI"}, token, expect=400)
        check("更新改 framework 400", True)
        req("POST", "/worklog/goals",
            {"scope": "YEAR", "period": str(Y), "kind": "OBJECTIVE", "title": "x", "parentId": y_o["id"]},
            token, expect=400)
        check("YEAR O 带父 400", True)
        q_o = req("POST", "/worklog/goals",
                  {"scope": "QUARTER", "period": QP, "kind": "OBJECTIVE", "title": "季度O：能力跃迁",
                   "parentId": y_o["id"], "weight": 60}, token)
        check("季度 O 挂年度 O 落库", q_o.get("parentId") == y_o["id"], json.dumps(q_o)[:200])
        req("POST", "/worklog/goals",
            {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "无父KR"}, token, expect=400)
        check("KR 无父 400", True)
        req("POST", "/worklog/goals",
            {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "跨周期父", "parentId": y_o["id"]},
            token, expect=400)
        check("KR 父跨周期（挂年 O）400", True)
        req("POST", "/worklog/goals",
            {"scope": "QUARTER", "period": f"{Y}-Q9", "kind": "OBJECTIVE", "title": "x"}, token, expect=400)
        check("period 格式非法 400", True)
        req("POST", "/worklog/goals",
            {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "x",
             "parentId": q_o["id"], "progress": 150}, token, expect=400)
        check("progress 越界 400", True)
        kr1 = req("POST", "/worklog/goals",
                  {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "KR1：上线目标视图",
                   "parentId": q_o["id"]}, token)
        kr2 = req("POST", "/worklog/goals",
                  {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "KR2：复盘投影落地",
                   "parentId": q_o["id"]}, token)
        # 派生进度用 KR + 删 KR 清引用用 KR
        kr_del = req("POST", "/worklog/goals",
                     {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "KR-待删",
                      "parentId": q_o["id"]}, token)
        kr2 = req("PUT", f"/worklog/goals/{kr2['id']}",
                  {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "KR2：复盘投影落地",
                   "parentId": q_o["id"], "progress": 33, "status": "ACTIVE"}, token)
        check("编辑改进度生效", kr2.get("progress") == 33, json.dumps(kr2)[:200])

        tree = req("GET", f"/worklog/goals/tree?year={Y}", token=token)
        check("树含年度 O", any(n["goal"]["id"] == y_o["id"] for n in tree["yearObjectives"]))
        qs = quarter_section(tree, QP)
        qo_node = next((n for n in (qs["objectives"] if qs else []) if n["goal"]["id"] == q_o["id"]), None)
        check("树季度区 O 嵌套 KR", qo_node is not None and len(qo_node["krs"]) == 3,
              json.dumps(qs, ensure_ascii=False)[:300])

        # 他人口径：改/删 404；拿他人 O 作父 400（父目标不存在）
        req("PUT", f"/worklog/goals/{kr1['id']}",
            {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "越权", "parentId": q_o["id"]},
            bob, expect=404)
        check("他人改 KR 404", True)
        req("DELETE", f"/worklog/goals/{kr1['id']}", token=bob, expect=404)
        check("他人删 KR 404", True)
        req("POST", "/worklog/goals",
            {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "越权KR", "parentId": q_o["id"]},
            bob, expect=400)
        check("他人 O 作父 400", True)

        req("DELETE", f"/worklog/goals/{q_o['id']}", token=token, expect=400)
        check("删有子项的 O 400", True)

        # 删 KR → 关联计划项 kr_id 置空
        p_del = req("POST", "/worklog/plans/items",
                    {"scope": "WEEK", "date": MONDAY_S, "title": "挂待删KR的计划", "krId": kr_del["id"]}, token)
        check("计划项挂 krId 落库", p_del.get("krId") == kr_del["id"], json.dumps(p_del)[:200])
        req("DELETE", f"/worklog/goals/{kr_del['id']}", token=token)
        plans_after = req("GET", f"/worklog/plans?scope=WEEK&date={MONDAY_S}", token=token)
        p_del_after = next((i for i in plans_after["items"] if i["id"] == p_del["id"]), None)
        check("删 KR 后计划项 kr_id 置空", p_del_after is not None and p_del_after.get("krId") is None,
              json.dumps(p_del_after)[:200])

        # ---------- [2] 滚转 ----------
        req("POST", "/worklog/goals/carry", {"toPeriod": NQ, "ids": [kr1["id"]]}, token, expect=400)
        check("KR 单独滚转无 krParentMap 400", True)
        r = req("POST", "/worklog/goals/carry", {"toPeriod": NQ, "ids": [q_o["id"], kr1["id"]]}, token)
        check("同批滚转 carried=2", r.get("carried") == 2, json.dumps(r, ensure_ascii=False)[:300])
        copies = {c["carriedFromId"]: c for c in r["items"]}
        o_copy, kr_copy = copies.get(q_o["id"]), copies.get(kr1["id"])
        check("副本 ACTIVE + carriedFromId",
              o_copy and kr_copy and o_copy["status"] == "ACTIVE" and kr_copy["status"] == "ACTIVE")
        check("KR 副本挂 O 副本", kr_copy and kr_copy.get("parentId") == o_copy["id"],
              json.dumps(kr_copy)[:200])
        check("滚转复制 framework", kr_copy and kr_copy.get("framework") == "OKR")
        src_o = req("GET", f"/worklog/goals/tree?year={Y}", token=token)
        src_qs = quarter_section(src_o, QP)
        src_o_node = next((n for n in src_qs["objectives"] if n["goal"]["id"] == q_o["id"]), None)
        check("原 O 置 CARRIED", src_o_node and src_o_node["goal"]["status"] == "CARRIED")
        check("原 KR 置 CARRIED",
              src_o_node and all(k["status"] == "CARRIED" for k in src_o_node["krs"] if k["id"] == kr1["id"]))
        r2 = req("POST", "/worklog/goals/carry", {"toPeriod": NQ, "ids": [q_o["id"], kr1["id"]]}, token)
        check("重复滚转幂等（skipped=2, carried=0）",
              r2.get("carried") == 0 and r2.get("skipped") == 2, json.dumps(r2)[:200])
        # 跨年滚转时副本在明年树里
        nq_tree = req("GET", f"/worklog/goals/tree?year={NQ_YEAR}", token=token)
        nq_qs = quarter_section(nq_tree, NQ)
        check("副本落在目标季度区",
              nq_qs is not None and any(n["goal"]["id"] == o_copy["id"] for n in nq_qs["objectives"]))

        # ---------- [3] 计划对齐 ----------
        req("POST", "/worklog/plans/items",
            {"scope": "WEEK", "date": MONDAY_S, "title": "挂O当KR", "krId": kr2["id"]}, token)
        req("POST", "/worklog/plans/items",
            {"scope": "WEEK", "date": MONDAY_S, "title": "krId 指 O", "krId": q_o["id"]}, token, expect=400)
        check("krId 指向 O 400", True)
        req("POST", "/worklog/plans/items",
            {"scope": "WEEK", "date": MONDAY_S, "title": "他人KR", "krId": kr2["id"]}, bob, expect=400)
        check("他人 KR 400", True)

        plans = req("GET", f"/worklog/plans?scope=WEEK&date={MONDAY_S}", token=token)
        check("计划列表附 krTitles", (plans.get("krTitles") or {}).get(str(kr2["id"])) == "KR2：复盘投影落地",
              json.dumps(plans.get("krTitles"), ensure_ascii=False)[:200])

        linkable = req("GET", f"/worklog/goals/linkable-krs?date={MONDAY_S}", token=token)
        lids = [k["id"] for k in linkable]
        check("linkable-krs 只回当季 ACTIVE KR",
              kr2["id"] in lids and kr1["id"] not in lids and kr_copy["id"] not in lids,
              json.dumps(linkable, ensure_ascii=False)[:300])
        kr2_link = next((k for k in linkable if k["id"] == kr2["id"]), None)
        check("linkable 带所属 O 标题", kr2_link and kr2_link.get("objectiveTitle") == "季度O：能力跃迁",
              json.dumps(kr2_link, ensure_ascii=False)[:200])

        # 周滚转复制 kr_id
        p_carry = req("POST", "/worklog/plans/items",
                      {"scope": "WEEK", "date": MONDAY_S, "title": "滚转带KR", "krId": kr2["id"]}, token)
        cr = req("POST", "/worklog/plans/carry", {"scope": "WEEK", "to": NEXT_MONDAY_S, "ids": [p_carry["id"]]}, token)
        carried_copy = (cr.get("items") or [{}])[0]
        check("周滚转复制 kr_id", carried_copy.get("krId") == kr2["id"], json.dumps(carried_copy)[:200])

        # ---------- [4] 派生进度（只展示不回写） ----------
        # 专用 KR：kr2 已被 [3] 的挂接/滚转项污染（派生口径含全部非 DROPPED/CARRIED 关联项）
        kr3 = req("POST", "/worklog/goals",
                  {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "KR3：派生验证",
                   "parentId": q_o["id"]}, token)
        # 人工进度 33：验证派生 suggestedProgress 不回写人工 progress
        req("PUT", f"/worklog/goals/{kr3['id']}",
            {"scope": "QUARTER", "period": QP, "kind": "KEY_RESULT", "title": "KR3：派生验证",
             "parentId": q_o["id"], "progress": 33, "status": "ACTIVE"}, token)
        p1 = req("POST", "/worklog/plans/items",
                 {"scope": "WEEK", "date": MONDAY_S, "title": "KR3计划-完成", "krId": kr3["id"]}, token)
        p2 = req("POST", "/worklog/plans/items",
                 {"scope": "WEEK", "date": MONDAY_S, "title": "KR3计划-未完", "krId": kr3["id"]}, token)
        req("POST", "/worklog/entries",
            {"workDate": MONDAY_S, "title": "KR3 事实工时", "hours": 2.0, "entryType": "DEV",
             "planId": p1["id"]}, token)
        req("POST", "/worklog/entries",
            {"workDate": MONDAY_S, "title": "KR3 计划态条目", "hours": 0.5, "entryType": "DEV",
             "status": "TODO", "planId": p2["id"]}, token)
        req("PUT", f"/worklog/plans/items/{p1['id']}",
            {"scope": "WEEK", "date": MONDAY_S, "title": "KR3计划-完成", "krId": kr3["id"], "status": "DONE"}, token)

        tree2 = req("GET", f"/worklog/goals/tree?year={Y}", token=token)
        qs2 = quarter_section(tree2, QP)
        kr2_view = next(k for n in qs2["objectives"] if n["goal"]["id"] == q_o["id"]
                        for k in n["krs"] if k["id"] == kr3["id"])
        check("suggestedProgress=50（2 项 1 完成）", kr2_view.get("suggestedProgress") == 50,
              json.dumps(kr2_view, ensure_ascii=False)[:300])
        check("linkedPlanTotal/Done=2/1",
              kr2_view.get("linkedPlanTotal") == 2 and kr2_view.get("linkedPlanDone") == 1)
        check("linkedHours 只计 DONE 条目（=2.0）", kr2_view.get("linkedHours") == 2.0,
              json.dumps(kr2_view)[:300])
        check("人工 progress 不被派生覆盖（=33）", kr2_view.get("progress") == 33)
        # 无关联项 KR：NQ 的 kr_copy 无计划项 → suggestedProgress null
        nq_tree2 = req("GET", f"/worklog/goals/tree?year={NQ_YEAR}", token=token)
        kr_copy_view = next(k for n in quarter_section(nq_tree2, NQ)["objectives"]
                            for k in n["krs"] if k["id"] == kr_copy["id"])
        check("无关联项 suggestedProgress 为 null", kr_copy_view.get("suggestedProgress") is None)

        # ---------- [5] 季度复盘（M2） ----------
        none_q = req("GET", f"/worklog/quarterly?period={QP}", token=token)
        check("复盘不存在返回 null", none_q is None, str(none_q)[:100])
        c1 = req("POST", "/worklog/quarterly", {"period": QP}, token)
        c2 = req("POST", "/worklog/quarterly", {"period": QP}, token)
        check("手动空白稿幂等", c1.get("exists") and c2.get("exists")
              and c1.get("createdAt") == c2.get("createdAt"), json.dumps(c2)[:200])
        req("PUT", f"/worklog/quarterly/{QP}", {"contentMd": "# 复盘手改"}, token)
        got = req("GET", f"/worklog/quarterly?period={QP}", token=token)
        check("编辑保存即生效", got and got.get("contentMd") == "# 复盘手改", json.dumps(got)[:200])

        # 计划外素材：本周一条不挂 KR 的 DONE 条目
        req("POST", "/worklog/entries",
            {"workDate": MONDAY_S, "title": "计划外救火", "hours": 1.0, "entryType": "SUPPORT"}, token)
        pv = req("GET", f"/worklog/quarterly/review-preview?period={QP}", token=token)
        md = pv.get("markdown") or ""
        check("预览含 OKR 对照块", "## OKR 对照" in md and "KR2：复盘投影落地" in md, md[:300])
        check("预览含计划外聚合（救火条目）", "## 计划外工作" in md and "计划外救火" in md, md[:300])
        check("对齐条目不计算划外", "KR3 事实工时" not in (md.split("## 计划外工作")[-1]), md[:400])

        gen = req("POST", "/worklog/quarterly/generate", {"period": QP}, token)
        check("非 force 已有行 reused 跳过（不起会话）", gen.get("reused") is True, json.dumps(gen)[:200])
        req("POST", "/worklog/quarterly/generate", {"period": EMPTY_Q}, token, expect=400)
        check("无素材季度 generate 400", True)
        req("POST", "/worklog/quarterly", {"period": f"{Y}-Q9"}, token, expect=400)
        check("复盘 period 非法 400", True)

        print(f"\n===== 结果：{len(passed)} PASS / {len(failed)} FAIL =====")
        if failed:
            print("FAILED: " + " | ".join(failed))
            return 1
        return 0
    finally:
        stop_app(app, app_log)


if __name__ == "__main__":
    sys.exit(main())
