#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-38 流程简化 E2E（前置：后端 :8080 已用新代码启动，runner jar 已重建）。

（历史脚本：CAP-52 起流程入口收敛为「开启 AI 规划」/「重新开发」，flow/analyze|design|split|skip 已删除、
阶段跳过不复存在；同链路验证见 tests/cap52_e2e.py，本脚本保留作历史足迹。）
链路 A（自动化主线）：flowAnalyze → 预写 analysis.md → /finish → 分析文档；
flowDesign → 预写 design.md → /finish → Design(DRAFT) 登记后【自动起拆分会话】
（无需 CONFIRMED、无需手动 split）→ 预写 wi-plan.json → /finish →【自动固化】2 个 WI
+ depends_on 边 + flow.split.done 通知（全程无 confirmSplit）。
链路 B（跳过）：新需求 skip analysis（DRAFT→ANALYZING、幂等）→ skip design；
已完成阶段跳过 409。
链路 C（会话关联需求自动建 WI）：POST /sessions 挂需求 → 返回 workItemId 非空、
需求下新增 DEVELOPMENT WI（title=taskSpec 首行）；终态需求 409；[flow:] 会话豁免。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap38-ws"
ORIGIN = TMP / "cap38-origin"
PROPS = TMP / "cap38-runner.properties"
MARK = f"e2e-cap38-{int(time.time())}"


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


def write_output(sid, name, content):
    """模拟 agent 写产出：定位 runner 会话工作区并预写 .devmind/output/<name>。"""
    t0 = time.time()
    target = None
    while time.time() - t0 < 60:
        for cand in WS.glob(f"*/sessions/{sid}"):
            target = cand
            break
        if target is None and (WS / "_chat" / sid).exists():
            target = WS / "_chat" / sid
        if target:
            break
        time.sleep(1)
    assert target, f"未找到会话工作区: {sid}"
    out = target / ".devmind" / "output"
    out.mkdir(parents=True, exist_ok=True)
    (out / name).write_text(content, encoding="utf-8")
    return target


def overview(tok, pid, rid):
    return req("GET", f"/projects/{pid}/requirements/{rid}/overview", token=tok)


def finish_session(tok, sid, what):
    req("POST", f"/sessions/{sid}/finish", token=tok)
    wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") == "DONE" or None,
         f"{what} DONE", 60)


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    # 源仓库：本地 git 仓库作 file:// 远端（匿名通道，无需凭据）
    # Windows 上 .git 只读对象文件会让 rmtree 失败，用 onexc 强制清
    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)
    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap38 e2e\n", encoding="utf-8")
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
        "title": f"{MARK} 需求A", "description": "支持配置化告警阈值，含前端表单与后端校验",
    }, tok)
    rid = reqm["id"]
    print(f"[2] 需求A {reqm['code']}")

    # 节点 + runner（fake executor）
    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap38-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=3\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap38-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[3] runner 上线")

        # ---- 链路 A：分析 → 方案 → 自动拆分固化 ----
        s1 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/analyze", None, tok)
        # [flow:] 流程会话豁免自动建 WI（直挂需求，无 workItemId）
        assert not s1.get("workItemId"), f"分析会话不应挂工作单元: {s1}"
        write_output(s1["id"], "analysis.md",
                     "# 需求分析\n\n## 影响面\n涉及 alert_rule 表与阈值配置接口（CAP38-E2E-MARK）\n")
        finish_session(tok, s1["id"], "分析会话")
        doc = wait(lambda: next((d for d in overview(tok, pid, rid)["docs"]
                                 if d["kind"] == "analysis"), None),
                   "分析文档登记", 30)
        print(f"[4] 分析产出 → analysis 文档 #{doc['id']}（流程会话豁免自动建 WI OK）")

        s2 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/design", None, tok)
        spec2 = wait(lambda: req("GET", f"/sessions/{s2['id']}", token=tok).get("taskSpec"),
                     "方案会话可读", 20)
        assert "## 需求分析结论" in spec2 and "CAP38-E2E-MARK" in spec2, \
            f"方案 spec 未注入分析结论:\n{spec2[:400]}"
        write_output(s2["id"], "design.md",
                     "# 方案\n\n## 总体思路\n阈值表 + 校验注解（CAP38-DESIGN-MARK）\n")
        finish_session(tok, s2["id"], "方案会话")
        design = wait(lambda: next((d for d in req(
            "GET", f"/projects/{pid}/requirements/{rid}/designs", token=tok)
            if d["status"] == "DRAFT" and d.get("docId")), None), "Design(DRAFT) 登记", 30)
        print(f"[5] 方案产出 → Design v{design['version']} DRAFT（spec 注入分析结论 OK）")

        # CAP-38 FR-03：方案登记后【自动】起拆分会话（不确认、不手动 split）
        split_sess = wait(lambda: next((s for s in overview(tok, pid, rid)["sessions"]
                                        if (s.get("taskSpec") or "").startswith("[flow:split]")), None),
                          "自动拆分会话出现", 60)
        spec3 = req("GET", f"/sessions/{split_sess['id']}", token=tok).get("taskSpec")
        assert "## 已确认方案" in spec3 and "CAP38-DESIGN-MARK" in spec3, "拆分 spec 未注入方案"
        assert "需求分析结论" in spec3, "拆分 spec 未注入分析背景"
        print(f"[6] 方案登记后自动起拆分会话 {split_sess['id']}（spec 双注入 OK）")

        plan = json.dumps([
            {"type": "DEVELOPMENT", "title": "后端阈值校验", "spec": "改 alert_rule 表与接口", "dependsOn": []},
            {"type": "DEVELOPMENT", "title": "前端阈值表单", "spec": "配置页表单", "dependsOn": [0]},
        ], ensure_ascii=False)
        write_output(split_sess["id"], "wi-plan.json", plan)
        finish_session(tok, split_sess["id"], "拆分会话")

        # CAP-38 FR-03：wi-plan.json【自动固化】为正式 WI（无 confirmSplit 环节）
        def _exec_wis():
            return [w for w in overview(tok, pid, rid)["workItems"] if w["type"] != "DESIGN"]
        wait(lambda: _exec_wis() if len(_exec_wis()) == 2 else None, "自动固化 2 个执行 WI", 30)
        exec_wis = _exec_wis()
        assert exec_wis[0]["title"] == "后端阈值校验" and exec_wis[1]["title"] == "前端阈值表单", \
            f"固化 WI 不符: {exec_wis}"
        notes = req("GET", "/notifications", token=tok)
        assert any(n["eventType"] == "flow.split.done" for n in notes), "缺 flow.split.done 通知"
        # DESIGN WI 已被 launchSplit 置 DONE
        design_wi = next(w for w in overview(tok, pid, rid)["workItems"] if w["type"] == "DESIGN")
        assert design_wi["status"] == "DONE", f"DESIGN WI 应已置 DONE: {design_wi}"
        print("[7] 拆分产出自动固化 2 个 WI + flow.split.done 通知 + DESIGN WI 置 DONE OK")

        # ---- 链路 B：阶段跳过 ----
        req_b = req("POST", f"/projects/{pid}/requirements", {
            "title": f"{MARK} 需求B", "description": "简单文案修改",
        }, tok)
        rid_b = req_b["id"]
        # 已完成阶段不可跳过：需求A 已有分析文档 → 409
        r409 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/skip",
                   {"stage": "analysis"}, tok, expect=409)
        assert r409, "跳过已完成阶段应 409"
        # 跳过分析：置标记 + DRAFT→ANALYZING
        req("POST", f"/projects/{pid}/requirements/{rid_b}/flow/skip", {"stage": "analysis"}, tok)
        cur = req("GET", f"/projects/{pid}/requirements/{rid_b}", token=tok)
        assert cur.get("analysisSkipped") is True and cur["status"] == "ANALYZING", \
            f"跳过分析后状态不符: {cur}"
        # 幂等：重复跳过不报错
        req("POST", f"/projects/{pid}/requirements/{rid_b}/flow/skip", {"stage": "analysis"}, tok)
        # 跳过方案：置标记
        req("POST", f"/projects/{pid}/requirements/{rid_b}/flow/skip", {"stage": "design"}, tok)
        cur = req("GET", f"/projects/{pid}/requirements/{rid_b}", token=tok)
        assert cur.get("designSkipped") is True, f"跳过方案后标记不符: {cur}"
        print("[8] 阶段跳过（置标记/DRAFT→ANALYZING/幂等/已完成 409）OK")

        # ---- 链路 C：会话关联需求自动建 WI ----
        s_auto = req("POST", "/sessions", {
            "projectId": pid, "requirementId": rid_b,
            "taskSpec": "修改告警文案为更友好表述\n补充埋点",
        }, tok)
        assert s_auto.get("workItemId"), f"关联需求的会话应自动建 WI 并挂载: {s_auto}"
        wis_b = [w for w in overview(tok, pid, rid_b)["workItems"] if w["type"] == "DEVELOPMENT"]
        assert len(wis_b) == 1 and wis_b[0]["title"] == "修改告警文案为更友好表述", \
            f"自动建 WI 不符: {wis_b}"
        req("POST", f"/sessions/{s_auto['id']}/finish", token=tok)  # 收尾，不占 runner 并发
        print(f"[9] 会话关联需求自动建 WI（{wis_b[0]['code']}，title=taskSpec 首行）OK")

        # 终态需求 409：验收态需求不能关联新会话
        req("PUT", f"/projects/{pid}/requirements/{rid_b}/status", {"status": "ACCEPTANCE"}, tok)
        e409 = req("POST", "/sessions", {
            "projectId": pid, "requirementId": rid_b, "taskSpec": "再改点什么",
        }, tok, expect=409)
        assert "ACCEPTANCE" in json.dumps(e409, ensure_ascii=False), f"409 消息应含状态: {e409}"
        print("[10] 终态需求关联会话 409 OK")
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
