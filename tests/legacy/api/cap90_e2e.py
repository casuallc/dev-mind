# -*- coding: utf-8 -*-
r"""CAP-90 E2E（自起隔离 app，纯 DB REST 链路，无需 runner/外部服务）。

运行前提：`mvn -q install -DskipTests`；JDK 21（JAVA_HOME 未设时回落本机 Adoptium 路径）；
H2 jar 在本地 maven 仓（迁移播种用 org.h2.tools.Shell）。
脚本自起隔离 app（:18105，--spring.profiles.active=e2e 独立 H2，定时开关全关），跑完杀掉。

验收覆盖（CAP-90 §11）：
  [1] 条目状态机：新建默认 DONE（事实）/ 显式 TODO 落计划态（预估工时入库）/
      人工 CARRIED 与非法值 400 / GIT 来源恒 DONE（转 TODO 400）
  [2] 完成交互：TODO → DONE + 实际工时确认（预估保留）
  [3] 日滚转：昨日未完成提示（carryover 只含 TODO/DOING，排除 DONE/DROPPED）；
      一键滚转副本 TODO+carriedFromId+预估沿用、原行置 CARRIED；DONE 跳过；
      重复滚转幂等；>200 ids 400
  [4] DAY scope 退役：plans items/carry/list/update 传 DAY 一律 400
  [5] WEEK 计划保留：日期归一、条目关联 TODO→DOING 联动、linkable 仅当周周计划、
      删计划项清条目引用、周滚转
  [6] 周报「下周计划」单向投影：next-plan-preview 确定性渲染（序号/类型/标题/预估/关联），
      下周期空 → itemCount=0 空 markdown
  [7] 内置模板：周报含 {{nextWeekPlan}}（日报 {{plans}} 回归钉）
  [8] FR-06 DAY 迁移：停库播种存量 DAY 行（H2 Shell）→ 重启迁移 → 条目按状态映射、
      滚转链接 uuid、挂 DAY 项的条目 plan_item_id 置空、WEEK 行不动、DAY 行删除
"""
import glob
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PORT = int(os.environ.get("E2E_PORT", "18105"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap90-e2e"
APP_LOG = TMP / "app.log"
DB_URL = f'jdbc:h2:file:{(TMP / "db/devmind").as_posix()};AUTO_SERVER=TRUE'

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

TODAY = date.today()
TODAY_S = TODAY.isoformat()
YESTERDAY_S = (TODAY - timedelta(days=1)).isoformat()
TOMORROW_S = (TODAY + timedelta(days=1)).isoformat()
MONDAY_S = (TODAY - timedelta(days=TODAY.weekday())).isoformat()
LAST_MONDAY_S = (TODAY - timedelta(days=TODAY.weekday() + 7)).isoformat()
NEXT_MONDAY_S = (TODAY - timedelta(days=TODAY.weekday()) + timedelta(days=7)).isoformat()
# 周内非周一的一天（验证 WEEK 归一；今天就周一则取明天）
WEEK_MID_S = (TODAY if TODAY.weekday() != 0 else TODAY + timedelta(days=1)).isoformat()

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


def h2_shell(sql_file):
    """停库窗口内用 H2 Shell 执行 SQL 文件（UTF-8），播种存量 DAY 行。"""
    jars = sorted(glob.glob(os.path.expanduser(r"~/.m2/repository/com/h2database/h2/*/h2-*.jar")))
    assert jars, "本地 maven 仓找不到 h2 jar"
    cmd = [str(Path(JAVA_HOME) / "bin/java"), "-cp", jars[-1], "org.h2.tools.Shell",
           "-url", DB_URL, "-user", "sa", "-password", "",
           "-sql", f"RUNSCRIPT FROM '{sql_file.as_posix()}' CHARSET 'UTF-8'"]
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    assert r.returncode == 0, f"H2 Shell 失败: {r.stdout[-500:]} {r.stderr[-500:]}"


def _force_rmtree(path):
    def _onerror(func, p, _exc):
        os.chmod(p, 0o666)
        func(p)
    if os.path.exists(path):
        shutil.rmtree(path, onerror=_onerror)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    _force_rmtree(TMP)
    TMP.mkdir(parents=True, exist_ok=True)
    kill_port_strays()

    app, app_log = start_app()
    try:
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        print(f"[0] app 已起（:{PORT}），admin 登录成功")

        # ---------- [1] 条目状态机 ----------
        e_done = req("POST", "/worklog/entries",
                     {"workDate": TODAY_S, "title": "默认事实条目", "hours": 1.0, "entryType": "DEV"}, token)
        check("新建条目默认 DONE", e_done.get("status") == "DONE", json.dumps(e_done)[:200])
        e_todo = req("POST", "/worklog/entries",
                     {"workDate": TODAY_S, "title": "今天计划做的事", "hours": 0, "entryType": "DEV",
                      "status": "TODO", "estimateHours": 2.0}, token)
        check("显式 TODO 落计划态+预估入库",
              e_todo.get("status") == "TODO" and e_todo.get("estimateHours") == 2.0,
              json.dumps(e_todo, ensure_ascii=False)[:200])
        req("PUT", f"/worklog/entries/{e_todo['id']}",
            {"workDate": TODAY_S, "title": "x", "hours": 0, "status": "CARRIED"}, token, expect=400)
        check("CARRIED 不可人工设（400）", True)
        req("PUT", f"/worklog/entries/{e_todo['id']}",
            {"workDate": TODAY_S, "title": "x", "hours": 0, "status": "FOO"}, token, expect=400)
        check("非法状态值 400", True)
        # GIT 来源恒 DONE：走 git import 造一条 GIT 条目
        req("POST", "/worklog/git/import",
            {"items": [{"repoId": 1, "commitSha": "cap90deadbeef", "subject": "git 事实提交",
                        "date": TODAY_S, "hours": 0.5}]}, token)
        kw = urllib.parse.quote("git 事实提交")
        gits = req("GET", f"/worklog/entries?from={TODAY_S}&to={TODAY_S}&keyword={kw}", token=token)
        git_entry = (gits.get("items") or [{}])[0]
        check("GIT 条目导入落 DONE", git_entry.get("source") == "GIT" and git_entry.get("status") == "DONE",
              json.dumps(git_entry, ensure_ascii=False)[:200])
        req("PUT", f"/worklog/entries/{git_entry['id']}",
            {"workDate": TODAY_S, "title": "git 事实提交", "hours": 0.5, "status": "TODO"},
            token, expect=400)
        check("GIT 条目转非 DONE 400", True)

        # ---------- [2] 完成交互（TODO → DONE + 实际工时，预估保留） ----------
        done = req("PUT", f"/worklog/entries/{e_todo['id']}",
                   {"workDate": TODAY_S, "title": "今天计划做的事", "hours": 1.5,
                    "status": "DONE", "estimateHours": 2.0}, token)
        check("完成：DONE+实际工时+预估保留",
              done.get("status") == "DONE" and done.get("hours") == 1.5
              and done.get("estimateHours") == 2.0, json.dumps(done)[:200])

        # ---------- [3] 日滚转 ----------
        y1 = req("POST", "/worklog/entries",
                 {"workDate": YESTERDAY_S, "title": "昨天没做完", "hours": 0,
                  "status": "DOING", "estimateHours": 1.0}, token)["id"]
        y2 = req("POST", "/worklog/entries",
                 {"workDate": YESTERDAY_S, "title": "昨天已完成", "hours": 2.0}, token)["id"]
        y3 = req("POST", "/worklog/entries",
                 {"workDate": YESTERDAY_S, "title": "昨天放弃", "hours": 0, "status": "DROPPED"}, token)["id"]
        co = req("GET", f"/worklog/entries/carryover?date={TODAY_S}", token=token)
        co_ids = {i["id"] for i in (co or [])}
        check("昨日未完成提示只含 TODO/DOING", y1 in co_ids and y2 not in co_ids and y3 not in co_ids,
              json.dumps(co, ensure_ascii=False)[:300])
        c1 = req("POST", "/worklog/entries/carry", {"to": TODAY_S, "ids": [y1, y2, y3]}, token)
        check("滚转：DOING 转入/DONE·DROPPED 跳过",
              c1.get("carried") == 1 and c1.get("skipped") == 2,
              json.dumps(c1, ensure_ascii=False)[:200])
        copy = c1["items"][0]
        check("副本 TODO+carriedFromId+目标日+预估沿用",
              copy.get("status") == "TODO" and copy.get("carriedFromId") == y1
              and copy.get("workDate") == TODAY_S and copy.get("estimateHours") == 1.0,
              json.dumps(copy, ensure_ascii=False)[:200])
        kw2 = urllib.parse.quote("昨天没做完")
        orig = (req("GET", f"/worklog/entries?from={YESTERDAY_S}&to={YESTERDAY_S}&keyword={kw2}",
                    token=token).get("items") or [{}])[0]
        check("原行置 CARRIED", orig.get("status") == "CARRIED", json.dumps(orig)[:160])
        c2 = req("POST", "/worklog/entries/carry", {"to": TODAY_S, "ids": [y1]}, token)
        check("重复滚转幂等（carried=0）", c2.get("carried") == 0 and c2.get("skipped") == 1,
              json.dumps(c2)[:160])
        req("POST", "/worklog/entries/carry", {"to": TODAY_S, "ids": ["x"] * 201}, token, expect=400)
        check("单次滚转 >200 ids 400", True)

        # ---------- [4] DAY scope 退役（计划项端点一律 400） ----------
        req("POST", "/worklog/plans/items",
            {"scope": "DAY", "date": TODAY_S, "title": "日计划已退役"}, token, expect=400)
        check("新建 DAY 计划项 400", True)
        req("GET", f"/worklog/plans?scope=DAY&date={TODAY_S}", token=token, expect=400)
        check("DAY 列表 400", True)
        req("POST", "/worklog/plans/carry", {"scope": "DAY", "to": TODAY_S, "ids": [1]},
            token, expect=400)
        check("DAY 滚转 400", True)
        req("GET", "/worklog/plans?scope=MONTH&date=" + TODAY_S, token=token, expect=400)
        check("非法 scope 400", True)

        # ---------- [5] WEEK 计划保留 ----------
        pw = req("POST", "/worklog/plans/items",
                 {"scope": "WEEK", "date": WEEK_MID_S, "title": "本周方向", "planType": "RESEARCH"}, token)
        check("周计划传周内日归一到周一", pw.get("planDate") == MONDAY_S, json.dumps(pw)[:200])
        en = req("POST", "/worklog/entries",
                 {"workDate": TODAY_S, "title": "挂周计划的条目", "hours": 1.0, "entryType": "DEV",
                  "planId": pw["id"]}, token)
        check("条目关联周计划 planId 落库", en.get("planItemId") == pw["id"], json.dumps(en)[:200])
        wlst = req("GET", f"/worklog/plans?scope=WEEK&date={MONDAY_S}", token=token)
        wmap = {i["id"]: i for i in wlst.get("items", [])}
        check("关联后周计划 TODO→DOING", wmap.get(pw["id"], {}).get("status") == "DOING",
              json.dumps(wmap.get(pw["id"]), ensure_ascii=False)[:200])
        lk = req("GET", f"/worklog/plans/linkable?date={TODAY_S}", token=token)
        check("linkable 只列当周周计划",
              all(i.get("scope") == "WEEK" for i in (lk or []))
              and any(i["id"] == pw["id"] for i in (lk or [])),
              json.dumps(lk, ensure_ascii=False)[:300])
        req("DELETE", f"/worklog/plans/items/{pw['id']}", token=token)
        kw3 = urllib.parse.quote("挂周计划的条目")
        ent = req("GET", f"/worklog/entries?from={TODAY_S}&to={TODAY_S}&keyword={kw3}", token=token)
        check("删计划项后条目 plan_item_id 置空",
              (ent.get("items") or [{}])[0].get("planItemId") is None, str(ent)[:200])
        w1 = req("POST", "/worklog/plans/items",
                 {"scope": "WEEK", "date": LAST_MONDAY_S, "title": "上周没收尾"}, token)["id"]
        wc = req("POST", "/worklog/plans/carry", {"scope": "WEEK", "to": MONDAY_S, "ids": [w1]}, token)
        check("周滚转副本落本周一", wc.get("carried") == 1
              and wc["items"][0].get("planDate") == MONDAY_S
              and wc["items"][0].get("carriedFromId") == w1,
              json.dumps(wc, ensure_ascii=False)[:200])

        # ---------- [6] 周报「下周计划」单向投影 ----------
        req("POST", "/worklog/plans/items",
            {"scope": "WEEK", "date": NEXT_MONDAY_S, "title": "下周计划A", "planType": "DEV",
             "hours": 2.0, "requirementId": "REQ-9"}, token)
        req("POST", "/worklog/plans/items",
            {"scope": "WEEK", "date": NEXT_MONDAY_S, "title": "下周计划B", "planType": "MEETING",
             "jiraIssueKey": "ADM-12"}, token)
        pv = req("GET", f"/worklog/weekly/next-plan-preview?weekStart={MONDAY_S}", token=token)
        md = pv.get("markdown", "")
        check("投影：itemCount=2 且确定性渲染（序号+类型+标题）",
              pv.get("itemCount") == 2 and "1. [DEV] 下周计划A" in md and "2. [MEETING] 下周计划B" in md,
              md[:300])
        check("投影：预估与关联入渲染", "预估 2h" in md and "需求=REQ-9" in md and "Jira=ADM-12" in md,
              md[:300])
        pv0 = req("GET", f"/worklog/weekly/next-plan-preview?weekStart={NEXT_MONDAY_S}", token=token)
        check("下周期空 → itemCount=0 空 markdown",
              pv0.get("itemCount") == 0 and pv0.get("markdown") == "", json.dumps(pv0)[:160])

        # ---------- [7] 内置模板占位符 ----------
        tpl = req("GET", "/worklog/settings/templates/default", token=token)
        check("周报内置模板含 {{nextWeekPlan}}", "{{nextWeekPlan}}" in tpl.get("weeklyTemplateMd", ""),
              tpl.get("weeklyTemplateMd", "")[:80])
        check("日报内置模板含 {{plans}}（回归钉）", "{{plans}}" in tpl.get("dailyTemplateMd", ""),
              tpl.get("dailyTemplateMd", "")[:80])
        check("周报内置模板含 {{lastWeekPlan}}/{{thisWeekPlan}}（回归钉）",
              "{{lastWeekPlan}}" in tpl.get("weeklyTemplateMd", "")
              and "{{thisWeekPlan}}" in tpl.get("weeklyTemplateMd", ""),
              tpl.get("weeklyTemplateMd", "")[:80])

        # ---------- [8] FR-06 DAY 迁移：停库播种 → 重启迁移 ----------
        stop_app(app, app_log)
        seed = TMP / "seed.sql"
        seed.write_text(f"""
INSERT INTO worklog_plan_items
  (id, user_id, scope, plan_date, title, content, plan_type, estimate_minutes, status, source,
   requirement_id, jira_issue_key, carried_from_id, sort_order, created_at, updated_at) VALUES
  (90001, 'admin', 'DAY', DATE '{YESTERDAY_S}', '迁移-昨天待办', NULL, 'DEV', 120, 'TODO', 'MANUAL',
   'REQ-1', NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (90002, 'admin', 'DAY', DATE '{TODAY_S}', '迁移-今天进行中', '详情文本', 'SUPPORT', 60, 'DOING', 'MANUAL',
   NULL, 'ADM-7', 90001, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (90003, 'admin', 'DAY', DATE '{TODAY_S}', '迁移-已完成', NULL, 'MEETING', 90, 'DONE', 'MANUAL',
   NULL, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (90004, 'admin', 'DAY', DATE '{TODAY_S}', '迁移-已放弃', NULL, 'OTHER', NULL, 'DROPPED', 'MANUAL',
   NULL, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  (90010, 'admin', 'WEEK', DATE '{MONDAY_S}', '迁移-周计划不动', NULL, 'DEV', NULL, 'TODO', 'MANUAL',
   NULL, NULL, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
INSERT INTO worklog_entries
  (id, user_id, work_date, title, content, entry_type, minutes, status, estimate_minutes,
   carried_from_id, source, repo_id, commit_sha, requirement_id, jira_issue_key, plan_item_id,
   created_at, updated_at) VALUES
  ('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'admin', DATE '{TODAY_S}', '迁移-挂DAY项的条目', NULL, 'DEV', 60,
   'DONE', NULL, NULL, 'MANUAL', NULL, NULL, NULL, NULL, 90001, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
""", encoding="utf-8")
        h2_shell(seed)
        app, app_log = start_app()
        token = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})["accessToken"]
        print("[8] 已播种 4 条 DAY + 1 条 WEEK + 1 条挂 DAY 的条目，重启完成")

        mg = req("GET", f"/worklog/entries?from={YESTERDAY_S}&to={TODAY_S}&size=200", token=token)
        by_title = {i["title"]: i for i in mg.get("items", [])}
        m1 = by_title.get("迁移-昨天待办", {})
        check("迁移 TODO→TODO 条目（工时归零、预估沿用、关联保留）",
              m1.get("status") == "TODO" and m1.get("hours") == 0
              and m1.get("estimateHours") == 2.0 and m1.get("requirementId") == "REQ-1"
              and m1.get("source") == "MANUAL", json.dumps(m1, ensure_ascii=False)[:200])
        m2 = by_title.get("迁移-今天进行中", {})
        check("迁移 DOING→DOING 条目（详情/Jira 保留）",
              m2.get("status") == "DOING" and m2.get("content") == "详情文本"
              and m2.get("jiraIssueKey") == "ADM-7", json.dumps(m2, ensure_ascii=False)[:200])
        check("迁移滚转链接 uuid（90002←90001）",
              m2.get("carriedFromId") is not None and m2.get("carriedFromId") == m1.get("id"),
              json.dumps(m2)[:200])
        m3 = by_title.get("迁移-已完成", {})
        check("迁移 DONE→DONE 条目（工时取预估兜底）",
              m3.get("status") == "DONE" and m3.get("hours") == 1.5, json.dumps(m3)[:200])
        m4 = by_title.get("迁移-已放弃", {})
        check("迁移 DROPPED→DROPPED 条目（工时归零）",
              m4.get("status") == "DROPPED" and m4.get("hours") == 0, json.dumps(m4)[:200])
        linked = by_title.get("迁移-挂DAY项的条目", {})
        check("挂 DAY 项的条目 plan_item_id 置空", linked.get("planItemId") is None,
              json.dumps(linked)[:160])
        wafter = req("GET", f"/worklog/plans?scope=WEEK&date={MONDAY_S}", token=token)
        check("WEEK 计划行不受迁移影响",
              any(i.get("title") == "迁移-周计划不动" for i in wafter.get("items", [])),
              json.dumps(wafter, ensure_ascii=False)[:300])
        co2 = req("GET", f"/worklog/entries/carryover?date={TOMORROW_S}", token=token)
        check("迁移产物进日滚转提示（今天 DOING → 明天提示）",
              any(i.get("title") == "迁移-今天进行中" for i in (co2 or [])),
              json.dumps(co2, ensure_ascii=False)[:300])

        print(f"\n== CAP-90 E2E: {len(passed)} passed, {len(failed)} failed ==")
        if failed:
            print("FAILED: " + " | ".join(failed))
    finally:
        stop_app(app, app_log)

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
