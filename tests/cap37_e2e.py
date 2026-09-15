#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-37 产出回传 + 流程串联 E2E（前置：后端 :8080 已用新代码启动，runner jar 已重建）。

链路：flowAnalyze 起会话 → 脚本预写 .devmind/output/analysis.md 到 runner 会话工作区
→ /finish → fake 进程退出 → runner OutputUploader 回传 → session_outputs 落库
→ flow 监听器建 kind=analysis 文档 + 通知。
同法验证 design.md → Design(DRAFT) + 方案文档；wi-plan.json → 拆分草稿。
并断言下游 spec 注入：design 会话 taskSpec 含「需求分析结论」；split 会话含「已确认方案」。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap37-ws"
ORIGIN = TMP / "cap37-origin"
PROPS = TMP / "cap37-runner.properties"
MARK = f"e2e-cap37-{int(time.time())}"


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
    """模拟 agent 写产出：定位 runner 会话工作区并预写 .devmind/output/<name>。
    布局：托管工作区 <pid>/sessions/<sid>；chat 沙箱 _chat/<sid>。"""
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
    (ORIGIN / "README.md").write_text("# cap37 e2e\n", encoding="utf-8")
    subprocess.run(["git", "add", "."], cwd=ORIGIN, check=True, capture_output=True)
    subprocess.run(["git", "commit", "-m", "init"], cwd=ORIGIN, check=True, capture_output=True, env=env)

    # 项目（CLONE：主库带 remoteUrl，launch 帧才带 repo 块 → runner 托管工作区 → 产出可回传）
    proj = req("POST", "/projects", {
        "name": MARK, "sourceType": "CLONE",
        "remoteUrl": ORIGIN.as_uri(), "defaultBranch": "main", "tags": [],
    }, tok)
    pid = proj["id"]
    wait(lambda: req("GET", f"/projects/{pid}", token=tok).get("cloneStatus") == "READY" or None,
         "项目克隆 READY", 90)
    print(f"[1] 项目就绪 {pid}（CLONE 自主仓库）")

    reqm = req("POST", f"/projects/{pid}/requirements", {
        "title": f"{MARK} 需求", "description": "支持配置化告警阈值，含前端表单与后端校验",
    }, tok)
    rid = reqm["id"]
    print(f"[2] 需求 {reqm['code']}")

    # 节点 + runner（fake executor）
    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap37-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=2\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap37-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[3] runner 上线")

        # ---- 阶段 1：需求分析 ----
        s1 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/analyze", None, tok)
        write_output(s1["id"], "analysis.md",
                     "# 需求分析\n\n## 影响面\n涉及 alert_rule 表与阈值配置接口（CAP37-E2E-MARK）\n")
        req("POST", f"/sessions/{s1['id']}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{s1['id']}", token=tok).get("status") == "DONE" or None,
             "分析会话 DONE", 60)
        doc = wait(lambda: next((d for d in overview(tok, pid, rid)["docs"]
                                 if d["kind"] == "analysis"), None),
                   "分析文档登记", 30)
        print(f"[4] 分析产出回传 → analysis 文档 #{doc['id']} v{doc['currentVersion']}")
        # 通知
        notes = req("GET", "/notifications", token=tok)
        assert any(n["eventType"] == "flow.analysis.ready" for n in notes), "缺 flow.analysis.ready 通知"
        # 产物 ref = docId
        art = next((a for a in overview(tok, pid, rid)["artifacts"] if a["type"] == "ANALYSIS"), None)
        assert art and art.get("path") == str(doc["id"]), f"ANALYSIS 产物 ref 应为 docId: {art}"
        print("[5] flow.analysis.ready 通知 + ANALYSIS 产物(ref=docId) OK")

        # ---- 阶段 2：方案设计（spec 应注入分析结论）----
        s2 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/design", None, tok)
        # overview 的 taskSpec 是 preview 截断版，断言全文走会话详情
        spec2 = wait(lambda: req("GET", f"/sessions/{s2['id']}", token=tok).get("taskSpec"),
                     "方案会话可读", 20)
        assert "## 需求分析结论" in spec2 and "CAP37-E2E-MARK" in spec2, \
            f"方案 spec 未注入分析结论:\n{spec2[:400]}"
        print("[6] 方案 spec 注入分析结论 OK")
        write_output(s2["id"], "design.md",
                     "# 方案\n\n## 总体思路\n阈值表 + 校验注解（CAP37-DESIGN-MARK）\n")
        req("POST", f"/sessions/{s2['id']}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{s2['id']}", token=tok).get("status") == "DONE" or None,
             "方案会话 DONE", 60)
        design = wait(lambda: next((d for d in req(
            "GET", f"/projects/{pid}/requirements/{rid}/designs", token=tok)
            if d["status"] == "DRAFT" and d.get("docId")), None), "Design(DRAFT) 登记", 30)
        print(f"[7] 方案产出回传 → Design v{design['version']} DRAFT docId={design['docId']}")

        # ---- 阶段 3：确认方案 → AI 拆分（spec 双注入）----
        req("PUT", f"/projects/{pid}/requirements/{rid}/designs/{design['id']}/status",
            {"status": "CONFIRMED"}, tok)
        s3 = req("POST", f"/projects/{pid}/requirements/{rid}/flow/split", None, tok)
        spec3 = wait(lambda: req("GET", f"/sessions/{s3['id']}", token=tok).get("taskSpec"),
                     "拆分会话可读", 20)
        assert "## 已确认方案" in spec3 and "CAP37-DESIGN-MARK" in spec3, "拆分 spec 未注入方案"
        assert "需求分析结论" in spec3, "拆分 spec 未注入分析背景"
        print("[8] 拆分 spec 双注入（方案+分析背景）OK")
        plan = json.dumps([
            {"type": "DEVELOPMENT", "title": "后端阈值校验", "spec": "改 alert_rule 表与接口", "dependsOn": []},
            {"type": "DEVELOPMENT", "title": "前端阈值表单", "spec": "配置页表单", "dependsOn": [0]},
        ], ensure_ascii=False)
        write_output(s3["id"], "wi-plan.json", plan)
        req("POST", f"/sessions/{s3['id']}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{s3['id']}", token=tok).get("status") == "DONE" or None,
             "拆分会话 DONE", 60)
        draft = wait(lambda: (lambda d: d if d["items"] else None)(
            req("GET", f"/projects/{pid}/requirements/{rid}/flow/split-draft", token=tok)),
            "拆分草稿就绪", 30)
        assert len(draft["items"]) == 2 and draft["items"][1]["dependsOn"] == [0], \
            f"拆分草稿解析不符: {draft}"
        print(f"[9] 拆分产出回传 → 草稿 {len(draft['items'])} 项（依赖边保留）OK")

        # 固化 → 工作单元（confirmSplit 返回全量 WI 列表，含 DESIGN 型）
        res = req("POST", f"/projects/{pid}/requirements/{rid}/flow/confirm-split",
                  {"items": draft["items"]}, tok)
        exec_wis = [w for w in res if w["type"] != "DESIGN"]
        assert len(exec_wis) == 2, f"固化应建 2 个执行 WI: {res}"
        print("[10] 草稿固化 → 2 个工作单元 OK")
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
