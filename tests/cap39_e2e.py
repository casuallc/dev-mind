#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-39 产出按需回传 + 手动推送为需求文档 E2E
（前置：后端 :8080 已用新代码启动，runner jar 已重建为协议 v4）。

链路：建会话（挂 projectId+requirementId）→ 预写 .devmind/output/analysis.md、design.md
→ 会话 RUNNING 中即调 POST /sessions/{id}/outputs/collect → 断言即刻回传（不等退出）
→ publish：create analysis → 断言 kind=analysis 文档挂对需求
→ 改文件内容再 collect → publish update → 断言版本 v2
→ publish create design → 断言 Design(DRAFT) 记录生成
→ 负例：产出缺失 404 / 非法类型 400 / 更新跨需求文档 400。
"""
import json, os, shutil, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap39-ws"
ORIGIN = TMP / "cap39-origin"
PROPS = TMP / "cap39-runner.properties"
MARK = f"e2e-cap39-{int(time.time())}"


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


def session_dir(sid):
    """定位 runner 会话工作区：托管布局 <pid>/sessions/<sid>。"""
    t0 = time.time()
    while time.time() - t0 < 60:
        for cand in WS.glob(f"*/sessions/{sid}"):
            return cand
        time.sleep(1)
    raise AssertionError(f"未找到会话工作区: {sid}")


def write_output(sdir, name, content):
    out = sdir / ".devmind" / "output"
    out.mkdir(parents=True, exist_ok=True)
    (out / name).write_text(content, encoding="utf-8")


def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    # 源仓库：本地 git 仓库作 file:// 远端
    def _force_remove(func, path, _exc):
        os.chmod(path, 0o666)
        func(path)
    if ORIGIN.exists():
        shutil.rmtree(ORIGIN, onexc=_force_remove)
    ORIGIN.mkdir(parents=True)
    env = dict(os.environ, GIT_AUTHOR_NAME="e2e", GIT_AUTHOR_EMAIL="e2e@t",
               GIT_COMMITTER_NAME="e2e", GIT_COMMITTER_EMAIL="e2e@t")
    subprocess.run(["git", "init", "-b", "main"], cwd=ORIGIN, check=True, capture_output=True)
    (ORIGIN / "README.md").write_text("# cap39 e2e\n", encoding="utf-8")
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
        "title": f"{MARK} 需求", "description": "产出推送 E2E",
    }, tok)
    rid = reqm["id"]
    print(f"[2] 需求 {reqm['code']}")

    # 节点 + runner（fake executor，协议 v4）
    issued = req("POST", "/agent-nodes", {"name": MARK}, tok)
    node, node_token = issued["node"], issued["token"]
    req("POST", f"/agent-nodes/{node['id']}/default", token=tok)
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap39-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=2\n",
        encoding="utf-8")
    runner = subprocess.Popen(
        ["java", "-jar", str(RUNNER_JAR), str(PROPS)],
        stdout=open(TMP / "cap39-runner.log", "w", encoding="utf-8"),
        stderr=subprocess.STDOUT)
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node["id"] and n["status"] == "ONLINE"), None),
             "节点上线", 40)
        print("[3] runner 上线")

        # ---- 建会话（RUNNING 中测即时回传）----
        s = req("POST", "/sessions", {
            "projectId": pid, "requirementId": rid,
            "taskSpec": "产出推送 E2E 会话",
        }, tok)
        sid = s["id"]
        sdir = session_dir(sid)
        write_output(sdir, "analysis.md", "# 需求分析\n\n影响面 v1（CAP39-ANALYSIS-V1）\n")
        write_output(sdir, "design.md", "# 方案\n\n总体思路（CAP39-DESIGN-MARK）\n")
        print(f"[4] 会话 {sid} + 预写产出 OK")

        # 进行中即 collect → 断言即刻回传
        col = req("POST", f"/sessions/{sid}/outputs/collect", None, tok)
        names = {f["fileName"] for f in col["files"]}
        assert col["collected"] is True, f"collect 应成功: {col}"
        assert {"analysis.md", "design.md"} <= names, f"回传文件不全: {col}"
        print(f"[5] 进行中 collect 即时回传 {len(col['files'])} 个文件 OK")

        # 列表 + 内容端点
        listed = req("GET", f"/sessions/{sid}/outputs", token=tok)
        assert {f["fileName"] for f in listed} >= {"analysis.md", "design.md"}, f"列表不符: {listed}"
        content = req("GET", f"/sessions/{sid}/outputs/analysis.md", token=tok)
        assert "CAP39-ANALYSIS-V1" in content["content"], f"内容不符: {content}"
        print("[6] outputs 列表/内容端点 OK")

        # ---- publish create analysis ----
        pub = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "analysis.md", "kind": "analysis", "requirementId": rid,
            "mode": "create", "title": "手动推送分析",
        }, tok)
        assert pub["docId"] and pub["versionNo"] == 1, f"create 结果不符: {pub}"
        docs = req("GET", f"/documents?kind=analysis&projectId={pid}", token=tok)
        doc = next((d for d in docs if d["id"] == pub["docId"]), None)
        assert doc and doc["requirementId"] == rid, f"分析文档未挂对需求: {docs}"
        detail = req("GET", f"/documents/{pub['docId']}", token=tok)
        assert "CAP39-ANALYSIS-V1" in detail["contentMd"], "文档内容应为产出内容"
        print(f"[7] publish create analysis → 文档 #{pub['docId']} v1 OK")

        # ---- 改内容再 collect + update → v2 ----
        write_output(sdir, "analysis.md", "# 需求分析\n\n影响面 v2（CAP39-ANALYSIS-V2）\n")
        col2 = req("POST", f"/sessions/{sid}/outputs/collect", None, tok)
        assert col2["collected"] is True
        pub2 = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "analysis.md", "kind": "analysis", "requirementId": rid,
            "mode": "update", "docId": pub["docId"],
        }, tok)
        assert pub2["versionNo"] == 2, f"update 应为 v2: {pub2}"
        detail2 = req("GET", f"/documents/{pub['docId']}", token=tok)
        assert "CAP39-ANALYSIS-V2" in detail2["contentMd"] and detail2["versionNo"] == 2, \
            f"v2 内容不符: {detail2.get('versionNo')}"
        print(f"[8] publish update → 文档 #{pub['docId']} v2（内容为回传新版）OK")

        # ---- publish create design → Design(DRAFT) ----
        pub3 = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "design.md", "kind": "design", "requirementId": rid,
            "mode": "create", "title": "手动推送方案",
        }, tok)
        assert pub3.get("designId"), f"design 推送应同步落 Design 记录: {pub3}"
        designs = req("GET", f"/projects/{pid}/requirements/{rid}/designs", token=tok)
        d = next((x for x in designs if x.get("docId") == pub3["docId"]), None)
        assert d and d["status"] == "DRAFT", f"Design(DRAFT) 未生成: {designs}"
        print(f"[9] publish create design → 文档 #{pub3['docId']} + Design {pub3['designId']} DRAFT OK")

        # ---- 负例 ----
        e404 = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "not-exist.md", "kind": "analysis", "requirementId": rid,
            "mode": "create", "title": "x",
        }, tok, expect=404)
        assert e404, "缺失产出应 404"
        e400 = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "analysis.md", "kind": "bogus", "requirementId": rid,
            "mode": "create", "title": "x",
        }, tok, expect=400)
        assert e400, "非法类型应 400"
        # 跨类型更新：design 文档用 kind=analysis 更新 → 400
        e400b = req("POST", f"/sessions/{sid}/outputs/publish", {
            "fileName": "analysis.md", "kind": "analysis", "requirementId": rid,
            "mode": "update", "docId": pub3["docId"],
        }, tok, expect=400)
        assert e400b, "目标文档类型不匹配应 400"
        print("[10] 负例（产出缺失 404 / 非法类型 400 / 类型不匹配 400）OK")

        req("POST", f"/sessions/{sid}/finish", token=tok)
        wait(lambda: req("GET", f"/sessions/{sid}", token=tok).get("status") in
             ("DONE", "FAILED", "TERMINATED") or None, "会话收尾", 60)
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
