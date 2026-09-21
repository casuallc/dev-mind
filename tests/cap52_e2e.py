#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-52 需求流程精简 E2E（前置：后端 :8080 已用新代码启动，runner jar 已重建）。

链路 A（三合一规划 → 自动开发会话）：POST /flow/plan 起**一个**规划会话（taskSpec 首行 [flow:plan]，
正文要求三份产出 + 粒度硬约束）→ 预写 analysis.md/design.md/wi-plan.json → /finish
→ 三份产出各自登记（分析文档 + Design(DRAFT) + 2 个 WI，WI 直接 IN_PROGRESS）
+ flow.plan.done / flow.dispatched 通知 + **自动起 [flow:dev] 开发会话**（清单整份带入，
依赖渲染成「先完成 #n」）。
链路 B（开发会话收尾）：预写 dev-summary.md → /finish → 需求 ACCEPTANCE + flow.dev.done；
**WI 不自动置 DONE**（人验收，避免 N 条一起 DONE 掀起 N 次构建）。
链路 C（粒度护栏）：清单 6 条（> MAX_ITEMS=5）→ flow.plan.partial、不建 WI、不起开发会话。
链路 D（入口门禁）：规划会话进行中再起规划 409；无清单起开发 409；终态需求起规划 409。
链路 E（人工再跑一轮）：清单已就绪时 POST /flow/dev 再起一个开发会话。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap52-ws"
ORIGIN = TMP / "cap52-origin"
PROPS = TMP / "cap52-runner.properties"
MARK = f"e2e-cap52-{int(time.time())}"


def req(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token:
        r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            return json.loads(resp.read().decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:400]}")


def wait(cond, what, timeout=60):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)


def session_dir(sid, rid, timeout=60):
    """定位会话工作区：CAP-51 需求工作树（worktrees/req-<rid>）优先，兼容旧布局。"""
    t0 = time.time()
    while time.time() - t0 < timeout:
        for pattern in (f"*/worktrees/req-{rid}", f"*/worktrees/sid-{sid}", f"*/sessions/{sid}"):
            hit = next(iter(WS.glob(pattern)), None)
            if hit:
                return hit
        if (WS / "_chat" / sid).exists():
            return WS / "_chat" / sid
        time.sleep(1)
    raise AssertionError(f"未找到会话工作区: session={sid} req={rid}")


def write_output(sid, rid, name, content):
    """模拟 agent 写产出：把 .devmind/output/<name> 预写进会话工作区。"""
    out = session_dir(sid, rid) / ".devmind" / "output"
    out.mkdir(parents=True, exist_ok=True)
    (out / name).write_text(content, encoding="utf-8")


def overview(tok, pid, rid):
    return req("GET", f"/projects/{pid}/requirements/{rid}/overview", token=tok)


def sessions_of(task_spec_marker, tok, pid, rid):
    return [s for s in overview(tok, pid, rid)["sessions"]
            if (s.get("taskSpec") or "").startswith(task_spec_marker)]


def finish_session(tok, sid, what):
    req("POST", f"/sessions/{sid}/finish", token=tok)
    wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
         f"{what} DONE", 90)


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)

    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap52 e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)

    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    print(f"[1] 项目就绪 {pid}")

    reqm = req("POST", f"/projects/{pid}/requirements", {
        "title": f"{MARK} 需求A", "description": "告警阈值可配置：后端校验 + 前端表单",
    }, tok)
    rid = reqm["id"]
    print(f"[2] 需求A {reqm['code']}")

    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap52-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap52-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[3] runner 上线")

        # ---- 链路 A：一个会话产出三份 + 自动接开发会话 ----
        plan_sess = req("POST", f"/projects/{pid}/requirements/{rid}/flow/plan", None, tok)
        assert not plan_sess.get("workItemId"), f"规划会话应直挂需求: {plan_sess}"
        spec = wait(lambda: req("GET", f"/sessions/{plan_sess['id']}", token=tok).get("taskSpec"),
                    "规划会话可读", 20)
        assert spec.startswith("[flow:plan]"), f"规划标记不符:\n{spec[:120]}"
        for needle in ("analysis.md", "design.md", "wi-plan.json", "1~3 个", "spec 必须自包含"):
            assert needle in spec, f"规划 spec 缺要求「{needle}」"
        cur = req("GET", f"/projects/{pid}/requirements/{rid}", token=tok)
        assert cur["status"] == "ANALYZING", f"DRAFT 应推进 ANALYZING: {cur['status']}"
        print(f"[4] 规划会话 {plan_sess['id']}（三份产出要求 + 粒度约束 OK，需求 → ANALYZING）")

        # 规划会话进行中：同需求再起规划/开发都应 409（服务端互斥预检）
        e = req("POST", f"/projects/{pid}/requirements/{rid}/flow/plan", None, tok, expect=409)
        assert "进行中的会话" in json.dumps(e, ensure_ascii=False), f"409 文案不符: {e}"
        print("[5] 规划中重复起会话 409 OK")

        write_output(plan_sess["id"], rid, "analysis.md",
                     "# 需求分析\n\n## 影响面\nalert_rule 表与阈值校验接口（CAP52-E2E-MARK）\n")
        write_output(plan_sess["id"], rid, "design.md",
                     "# 方案\n\n## 总体思路\n阈值表 + 校验注解（CAP52-DESIGN-MARK）\n")
        write_output(plan_sess["id"], rid, "wi-plan.json", json.dumps([
            {"type": "DEVELOPMENT", "title": "后端阈值校验", "spec": "改 alert_rule 表与接口",
             "dependsOn": []},
            {"type": "DEVELOPMENT", "title": "前端阈值表单", "spec": "配置页表单", "dependsOn": [0]},
        ], ensure_ascii=False))
        finish_session(tok, plan_sess["id"], "规划会话")

        wait(lambda: next((d for d in overview(tok, pid, rid)["docs"]
                           if d["kind"] == "analysis"), None), "分析文档登记", 30)
        design = wait(lambda: next((d for d in req(
            "GET", f"/projects/{pid}/requirements/{rid}/designs", token=tok)
            if d.get("docId")), None), "方案登记", 30)
        assert "CAP52-DESIGN-MARK" in req(
            "GET", f"/documents/{design['docId']}", token=tok)["contentMd"]
        wis = wait(lambda: overview(tok, pid, rid)["workItems"] or None, "固化工作单元", 30)
        assert [w["title"] for w in wis] == ["后端阈值校验", "前端阈值表单"], f"清单不符: {wis}"
        assert all(w["status"] == "IN_PROGRESS" for w in wis), f"WI 应随开发会话置 IN_PROGRESS: {wis}"
        notes = req("GET", "/notifications", token=tok)
        for et in ("flow.plan.done", "flow.dispatched"):
            assert any(n["eventType"] == et for n in notes), f"缺 {et} 通知"
        print(f"[6] 三份产出登记（分析文档 + 方案 v{design['version']} + {len(wis)} 个 WI 进行中）OK")

        # 自动起的开发会话：清单整份带入 + 依赖渲染成人话
        dev_sess = wait(lambda: sessions_of("[flow:dev]", tok, pid, rid) or None, "自动开发会话", 60)
        dev_spec = req("GET", f"/sessions/{dev_sess[0]['id']}", token=tok).get("taskSpec")
        assert "工作单元清单（共 2 条" in dev_spec, f"开发 spec 缺清单:\n{dev_spec[:400]}"
        assert "后端阈值校验" in dev_spec and "前端阈值表单" in dev_spec, "开发 spec 未带清单条目"
        assert "依赖：先完成 #1" in dev_spec, f"依赖未渲染成先完成 #1:\n{dev_spec[:400]}"
        assert "dev-summary.md" in dev_spec, "开发 spec 缺收尾产出要求"
        print(f"[7] 自动开发会话 {dev_sess[0]['id']}（整份清单 + 依赖 #1 渲染 OK）")

        # ---- 链路 B：开发会话收尾 → 待验收，WI 不自动 DONE ----
        write_output(dev_sess[0]["id"], rid, "dev-summary.md",
                     "## 完成情况\n- 后端阈值校验：已改 alert_rule\n- 前端表单：已加校验\n")
        finish_session(tok, dev_sess[0]["id"], "开发会话")
        wait(lambda: req("GET", f"/projects/{pid}/requirements/{rid}", token=tok)["status"]
             == "ACCEPTANCE" or None, "需求待验收", 30)
        wis = overview(tok, pid, rid)["workItems"]
        assert all(w["status"] == "IN_PROGRESS" for w in wis), f"WI 不该自动 DONE（人验收）: {wis}"
        notes = req("GET", "/notifications", token=tok)
        assert any(n["eventType"] == "flow.dev.done" for n in notes), "缺 flow.dev.done 通知"
        print("[8] 开发收尾 → 需求 ACCEPTANCE + WI 保持进行中（不自动 DONE）OK")

        # ---- 链路 E：人工再跑一轮开发 ----
        dev2 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/dev", None, tok)
        assert dev2["taskSpec"].startswith("[flow:dev]"), f"开发标记不符: {dev2['taskSpec'][:80]}"
        finish_session(tok, dev2["id"], "第二轮开发会话")
        print(f"[9] 人工重新开发 → {dev2['id']} OK")

        # ---- 链路 C：粒度护栏（6 条 > 上限 5）----
        req_b = req("POST", f"/projects/{pid}/requirements", {
            "title": f"{MARK} 需求B", "description": "拆得过细的清单应被拒绝固化",
        }, tok)
        rid_b = req_b["id"]
        # 无清单起开发 → 409
        e = req("POST", f"/projects/{pid}/requirements/{rid_b}/flow/dev", None, tok, expect=409)
        assert "开启 AI 规划" in json.dumps(e, ensure_ascii=False), f"409 文案不符: {e}"
        plan_b = req("POST", f"/projects/{pid}/requirements/{rid_b}/flow/plan", None, tok)
        write_output(plan_b["id"], rid_b, "analysis.md", "# 分析\n")
        write_output(plan_b["id"], rid_b, "design.md", "# 方案\n")
        write_output(plan_b["id"], rid_b, "wi-plan.json", json.dumps(
            [{"type": "DEVELOPMENT", "title": f"T{i}", "spec": "s", "dependsOn": []}
             for i in range(6)], ensure_ascii=False))
        finish_session(tok, plan_b["id"], "超限规划会话")
        assert not overview(tok, pid, rid_b)["workItems"], "超限清单不该固化出 WI"
        assert not sessions_of("[flow:dev]", tok, pid, rid_b), "超限清单不该起开发会话"
        notes = req("GET", "/notifications", token=tok)
        assert any(n["eventType"] == "flow.plan.partial" for n in notes), "缺 flow.plan.partial 通知"
        print("[10] 6 条清单被拒（不建 WI、不起开发会话、发部分产出通知）OK")

        # ---- 链路 D：终态需求 409 ----
        req("PUT", f"/projects/{pid}/requirements/{rid}/status", {"status": "DONE"}, tok)
        e = req("POST", f"/projects/{pid}/requirements/{rid}/flow/plan", None, tok, expect=409)
        assert "DONE" in json.dumps(e, ensure_ascii=False), f"409 文案不符: {e}"
        print("[11] 终态需求起规划 409 OK")
    finally:
        kill_tree(runner.pid)
        try:
            req("POST", f"/agent-nodes/{node['id']}/unset-default", token=tok)
            req("DELETE", f"/agent-nodes/{node['id']}", token=tok)
            req("DELETE", f"/projects/{pid}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
    print("全部通过")


if __name__ == "__main__":
    main()
