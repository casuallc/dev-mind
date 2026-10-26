# -*- coding: utf-8 -*-
r"""CAP-88 M1 计划闭环 E2E（自起隔离 app，纯 DB REST 链路，无需 runner/外部服务）。

运行前提：`mvn -q install -DskipTests`；JDK 21（JAVA_HOME 未设时回落本机 Adoptium 路径）。
脚本自起隔离 app（:18098，--spring.profiles.active=e2e 独立 H2，定时开关全关），跑完杀掉。

验收覆盖（CAP-88 §9 的 M1 部分）：
  [1] 日计划 CRUD：新增落库 TODO / 编辑标题 / 人工 DONE / CARRIED 不可人工设（400）
  [2] 周计划日期归一：传周三 → planDate 落周一
  [3] 条目关联联动：planId 落库、TODO 自动变 DOING、linkable 当日日计划+当周周计划、
      他人/不存在计划项 404
  [4] 删计划项：关联条目 plan_item_id 置空
  [5] 日滚转：昨日未完成提示随列表返回；一键滚转副本 TODO+carriedFromId、原项 CARRIED；
      DONE 项跳过；重复滚转幂等
  [6] 周滚转：上周 TODO → 本周一，同口径
  [7] M2 内置模板占位符：日报含 {{plans}}、周报含 {{lastWeekPlan}}/{{thisWeekPlan}}
      （prompt 组装与计划对照格式由单测覆盖；生成链路需 runner，不在本脚本范围）
"""
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

ROOT = Path(__file__).resolve().parent.parent
PORT = int(os.environ.get("E2E_PORT", "18098"))
BASE = f"http://localhost:{PORT}/api"
TMP = ROOT / "tmp" / "cap88-e2e"
APP_LOG = TMP / "app.log"

JAVA_HOME = os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"

TODAY = date.today()
TODAY_S = TODAY.isoformat()
YESTERDAY_S = (TODAY - timedelta(days=1)).isoformat()
MONDAY_S = (TODAY - timedelta(days=TODAY.weekday())).isoformat()
LAST_MONDAY_S = (TODAY - timedelta(days=TODAY.weekday() + 7)).isoformat()
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
    log = open(APP_LOG, "w", encoding="utf-8")
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    env["SPRING_DATASOURCE_URL"] = f'jdbc:h2:file:{(TMP / "db/devmind").as_posix()};AUTO_SERVER=TRUE'
    cmd = (f'cmd /c mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e '
           f'-Dspring-boot.run.arguments="{APP_ARGS}"')
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

        # ---------- [1] 日计划 CRUD + 状态机 ----------
        p1 = req("POST", "/worklog/plans/items",
                 {"scope": "DAY", "date": TODAY_S, "title": "写 CAP-88 设计", "planType": "DEV",
                  "hours": 2.0, "requirementId": "REQ-88"}, token)
        check("新建日计划落库 TODO", p1.get("status") == "TODO" and p1.get("source") == "MANUAL"
              and p1.get("estimateHours") == 2.0, json.dumps(p1, ensure_ascii=False)[:200])
        pid1 = p1["id"]
        lst = req("GET", f"/worklog/plans?scope=DAY&date={TODAY_S}", token=token)
        check("日计划列表可读", any(i["id"] == pid1 for i in lst.get("items", [])), str(lst)[:200])
        upd = req("PUT", f"/worklog/plans/items/{pid1}",
                  {"scope": "DAY", "date": TODAY_S, "title": "写 CAP-88 设计（改）", "status": "DONE"}, token)
        check("编辑+人工 DONE 生效", upd.get("title").endswith("（改）") and upd.get("status") == "DONE",
              json.dumps(upd, ensure_ascii=False)[:200])
        req("PUT", f"/worklog/plans/items/{pid1}",
            {"scope": "DAY", "date": TODAY_S, "title": "x", "status": "CARRIED"}, token, expect=400)
        check("CARRIED 不可人工设（400）", True)
        req("GET", "/worklog/plans?scope=MONTH&date=" + TODAY_S, token=token, expect=400)
        check("非法 scope 400", True)

        # ---------- [2] 周计划日期归一 ----------
        pw = req("POST", "/worklog/plans/items",
                 {"scope": "WEEK", "date": WEEK_MID_S, "title": "本周方向", "planType": "RESEARCH"}, token)
        check("周计划传周内日归一到周一", pw.get("planDate") == MONDAY_S, json.dumps(pw)[:200])

        # ---------- [3] 条目关联联动 ----------
        p2 = req("POST", "/worklog/plans/items",
                 {"scope": "DAY", "date": TODAY_S, "title": "联动的计划"}, token)
        pid2 = p2["id"]
        en = req("POST", "/worklog/entries",
                 {"workDate": TODAY_S, "title": "执行条目", "hours": 1.0, "entryType": "DEV",
                  "planId": pid2}, token)
        check("条目关联 planId 落库", en.get("planItemId") == pid2, json.dumps(en)[:200])
        after = req("GET", f"/worklog/plans?scope=DAY&date={TODAY_S}", token=token)
        linked = {i["id"]: i for i in after.get("items", [])}
        check("关联后 TODO 自动变 DOING", linked.get(pid2, {}).get("status") == "DOING",
              json.dumps(linked.get(pid2), ensure_ascii=False)[:200])
        check("DONE 项不被联动回退", linked.get(pid1, {}).get("status") == "DONE",
              json.dumps(linked.get(pid1))[:160])
        lk = req("GET", f"/worklog/plans/linkable?date={TODAY_S}", token=token)
        lk_ids = {i["id"] for i in (lk or [])}
        check("linkable 含当日日计划+当周周计划", pid2 in lk_ids and pw["id"] in lk_ids,
              json.dumps(lk, ensure_ascii=False)[:300])
        req("POST", "/worklog/entries",
            {"workDate": TODAY_S, "title": "x", "hours": 1.0, "planId": 999999}, token, expect=404)
        check("不存在计划项关联 404", True)

        # ---------- [4] 删计划项清条目引用 ----------
        req("DELETE", f"/worklog/plans/items/{pid2}", token=token)
        kw = urllib.parse.quote("执行条目")  # query 含中文必须 URL 编码
        ent = req("GET", f"/worklog/entries?from={TODAY_S}&to={TODAY_S}&keyword={kw}", token=token)
        e0 = (ent.get("items") or [{}])[0]
        check("删计划项后条目 plan_item_id 置空", e0.get("planItemId") is None, json.dumps(e0)[:200])

        # ---------- [5] 日滚转：提示 + 一键滚转 + 幂等 ----------
        y1 = req("POST", "/worklog/plans/items",
                 {"scope": "DAY", "date": YESTERDAY_S, "title": "昨天没做完"}, token)["id"]
        y2 = req("POST", "/worklog/plans/items",
                 {"scope": "DAY", "date": YESTERDAY_S, "title": "昨天已完成"}, token)["id"]
        req("PUT", f"/worklog/plans/items/{y2}",
            {"scope": "DAY", "date": YESTERDAY_S, "title": "昨天已完成", "status": "DONE"}, token)
        today_list = req("GET", f"/worklog/plans?scope=DAY&date={TODAY_S}", token=token)
        check("昨日未完成提示随列表返回", today_list.get("carryoverFrom") == YESTERDAY_S
              and any(i["id"] == y1 for i in today_list.get("carryoverItems", []))
              and all(i["id"] != y2 for i in today_list.get("carryoverItems", [])),
              json.dumps(today_list, ensure_ascii=False)[:300])
        c1 = req("POST", "/worklog/plans/carry",
                 {"scope": "DAY", "to": TODAY_S, "ids": [y1, y2]}, token)
        check("滚转：TODO 转入/DONE 跳过", c1.get("carried") == 1 and c1.get("skipped") == 1,
              json.dumps(c1, ensure_ascii=False)[:200])
        copy = c1["items"][0]
        check("副本 TODO+carriedFromId+目标日", copy.get("status") == "TODO"
              and copy.get("carriedFromId") == y1 and copy.get("planDate") == TODAY_S,
              json.dumps(copy, ensure_ascii=False)[:200])
        ylst = req("GET", f"/worklog/plans?scope=DAY&date={YESTERDAY_S}", token=token)
        orig = {i["id"]: i for i in ylst.get("items", [])}
        check("原项置 CARRIED", orig.get(y1, {}).get("status") == "CARRIED", json.dumps(orig.get(y1))[:160])
        c2 = req("POST", "/worklog/plans/carry",
                 {"scope": "DAY", "to": TODAY_S, "ids": [y1]}, token)
        check("重复滚转幂等（carried=0）", c2.get("carried") == 0 and c2.get("skipped") == 1,
              json.dumps(c2)[:160])
        # CARRIED 项不进 linkable
        lk2 = req("GET", f"/worklog/plans/linkable?date={YESTERDAY_S}", token=token)
        check("linkable 排除 CARRIED", all(i["id"] != y1 for i in (lk2 or [])), str(lk2)[:200])
        # 已滚转项只读
        req("PUT", f"/worklog/plans/items/{y1}",
            {"scope": "DAY", "date": YESTERDAY_S, "title": "改名"}, token, expect=400)
        check("已滚转项编辑 400", True)
        # scope 不匹配 400
        req("POST", "/worklog/plans/carry", {"scope": "WEEK", "to": MONDAY_S, "ids": [y2]},
            token, expect=400)
        check("滚转 scope 不匹配 400", True)

        # ---------- [6] 周滚转 ----------
        w1 = req("POST", "/worklog/plans/items",
                 {"scope": "WEEK", "date": LAST_MONDAY_S, "title": "上周没收尾"}, token)["id"]
        wc = req("POST", "/worklog/plans/carry", {"scope": "WEEK", "to": MONDAY_S, "ids": [w1]}, token)
        check("周滚转副本落本周一", wc.get("carried") == 1
              and wc["items"][0].get("planDate") == MONDAY_S
              and wc["items"][0].get("carriedFromId") == w1,
              json.dumps(wc, ensure_ascii=False)[:200])

        # ---------- [7] M2 内置模板占位符（prompt 组装细节由单测覆盖，E2E 只钉 REST 可见口径） ----------
        tpl = req("GET", "/worklog/settings/templates/default", token=token)
        check("日报内置模板含 {{plans}} 计划对照占位", "{{plans}}" in tpl.get("dailyTemplateMd", ""),
              tpl.get("dailyTemplateMd", "")[:80])
        check("周报内置模板含 {{lastWeekPlan}}/{{thisWeekPlan}}",
              "{{lastWeekPlan}}" in tpl.get("weeklyTemplateMd", "")
              and "{{thisWeekPlan}}" in tpl.get("weeklyTemplateMd", ""),
              tpl.get("weeklyTemplateMd", "")[:80])

        print(f"\n== CAP-88 M1 E2E: {len(passed)} passed, {len(failed)} failed ==")
        if failed:
            print("FAILED: " + " | ".join(failed))
    finally:
        stop_app(app, app_log)

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
