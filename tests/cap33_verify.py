#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-33 场景化会话与上下文装配 E2E 验证。

前置：后端 :8080 已用新代码启动（mvn -pl devmind-app spring-boot:run），runner jar 已构建。
脚本自建 runner 节点（executor=fake）。

覆盖：
 1. 造资产：知识条目 A(global 无标签)/B(tag=e2e33)/C(tag=e2e33x)、GLOBAL skill、项目文档
 2. 场景 CRUD + dryRun 预览（渲染骨架、三层 items、不 bumpHits）
 3. POST /sessions + scenarioCode + extraKnowledgeTags → /sessions/{id}/context 三层来源
    （scenario / project-auto / request）+ scenarioCode 落库
 4. 节点 token 拉 /agent/context/{sid}：SKILL.md base64、文档全文、claudeMd 场景背景与索引
 5. POST /chats + scenarioCode → /chats/{id}/context + runner 沙箱实际物化
    （_chat/<cid>/CLAUDE.md、.claude/skills/<name>/SKILL.md、.devmind/docs/<id>.md）
 6. 旧 templateCode 兼容（= scenarioCode 解析）
 7. 后端重启后 find 重建：session 走 sessions 表、chat 走 ChatContextLookup，均可再拉包
"""
import json, shutil, subprocess, sys, time, urllib.request, urllib.error, urllib.parse
from pathlib import Path

BASE = "http://localhost:8080/api"
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp"
WS = TMP / "cap33-ws"
PROPS = TMP / "cap33-runner.properties"
MARK = f"e2e33-{int(time.time())}"
SC = f"e2e33-{int(time.time()) % 100000}"

def req(method, path, body=None, token=None, expect=200, raw=False):
    data = json.dumps(body).encode() if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, method=method)
    r.add_header("Content-Type", "application/json")
    if token: r.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(r) as resp:
            assert resp.status == expect, f"{method} {path} -> {resp.status}, expect {expect}"
            payload = resp.read()
            return payload if raw else json.loads(payload.decode() or "null")
    except urllib.error.HTTPError as e:
        if e.code == expect:
            return e.read() if raw else json.loads(e.read().decode() or "null")
        raise AssertionError(f"{method} {path} -> {e.code}: {e.read().decode()[:300]}")

def wait(cond, what, timeout=40):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v: return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")

def kill_tree(pid):
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(pid)], capture_output=True)

def backend_pid():
    out = subprocess.run(["jps", "-l"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if "DevMindApplication" in line:
            return int(line.split()[0])
    return None

def _health():
    try:
        with urllib.request.urlopen("http://localhost:8080/api/health", timeout=2) as r:
            return r.status == 200
    except Exception:
        return False

def main():
    login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    tok = login.get("token") or login.get("accessToken")
    print("[0] 登录 OK")

    projects = req("GET", "/projects", token=tok)
    assert projects, "需要至少一个项目"
    PID = projects[0]["id"]
    print(f"[0] 使用项目 {PID}")

    # runner 节点（executor=fake）
    issued = req("POST", "/agent-nodes", {"name": f"e2e-cap33-{MARK}"}, tok)
    node_id, node_token = issued["node"]["id"], issued["token"]
    shutil.rmtree(WS, ignore_errors=True)
    PROPS.write_text(
        f"serverUrl=ws://localhost:8080/ws/agent\ntoken={node_token}\nexecutor=fake\n"
        f"workDir={(TMP / 'cap33-runner-work').as_posix()}\nworkspaceRoot={WS.as_posix()}\n"
        f"maxConcurrent=4\ngcDays=7\n", encoding="utf-8")
    runner = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(PROPS)],
                              stdout=open(TMP / "cap33-runner.log", "w", encoding="utf-8"),
                              stderr=subprocess.STDOUT)

    created = {"entries": [], "skills": [], "docs": [], "scenarios": [], "sessions": [], "chats": []}
    backend_restarted = False
    try:
        wait(lambda: next((n for n in req("GET", "/agent-nodes", token=tok)
                           if n["id"] == node_id and n["status"] == "ONLINE"), None), "节点上线")
        print(f"[0] runner 节点上线 id={node_id}")

        # ---------- [1] 造资产 ----------
        ea = req("POST", "/knowledge/entries",
                 {"scope": "global", "name": f"{MARK}-A", "contentMd": "全局通用经验正文A"}, tok)
        eb = req("POST", "/knowledge/entries",
                 {"scope": "global", "name": f"{MARK}-B", "contentMd": "场景绑定经验正文B", "tags": ["e2e33"]}, tok)
        ec = req("POST", "/knowledge/entries",
                 {"scope": "global", "name": f"{MARK}-C", "contentMd": "请求追加经验正文C", "tags": ["e2e33x"]}, tok)
        created["entries"] = [ea["id"], eb["id"], ec["id"]]
        skill_md = f"# {MARK} 技能\n\n步骤一：先看清上下文。\n"
        sk = req("POST", "/skills",
                 {"scope": "GLOBAL", "name": f"{MARK}-skill", "description": "e2e",
                  "contentMd": skill_md, "status": "ACTIVE"}, tok)
        created["skills"] = [sk["id"]]
        doc_body = "设计全文：" + "上下文装配细节。" * 30
        doc = req("POST", "/documents",
                  {"kind": "design", "projectId": PID, "title": f"{MARK} 设计稿", "contentMd": doc_body}, tok)
        created["docs"] = [doc["id"]]
        print(f"[1] 资产就绪 entry={created['entries']} skill={sk['id']} doc={doc['id']}")

        # ---------- [2] 场景 CRUD + dryRun 预览（不 bumpHits） ----------
        hits_before = {i: req("GET", f"/knowledge/entries/{i}", token=tok)["hitCount"]
                       for i in created["entries"]}
        sc = req("POST", "/scenarios", {
            "code": SC, "name": f"{MARK} 场景", "scope": "GLOBAL",
            "promptSkeleton": "任务：{{task}}\n项目：{{project}}\n分支：{{branch}}",
            "extraContextMd": "口径：一律中文回答。",
            "skillIds": [sk["id"]], "docIds": [doc["id"]], "knowledgeTags": ["e2e33"],
        }, tok)
        created["scenarios"] = [sc["id"]]
        assert sc["skillIds"] == [sk["id"]] and sc["docIds"] == [doc["id"]]
        pv = req("GET", f"/scenarios/{SC}/preview?projectId={PID}&taskSpec={urllib.parse.quote('修登录页')}", token=tok)
        assert "修登录页" in pv["renderedTaskSpec"], pv["renderedTaskSpec"]
        assert pv["hasContext"], "预览应装配出上下文"
        sources = {(i["kind"], i["ref"]): i["source"] for i in pv["items"]}
        assert sources.get(("knowledge", str(ea["id"]))) == "project-auto", sources
        assert sources.get(("knowledge", str(eb["id"]))) == "scenario", sources
        assert sources.get(("skill", sk["id"])) == "scenario", sources
        assert sources.get(("doc", str(doc["id"]))) == "scenario", sources
        assert "场景背景" in pv["claudeMd"] and "口径：一律中文回答。" in pv["claudeMd"]
        assert ".devmind/docs/" in pv["claudeMd"], "文档索引路径应进 CLAUDE.md"
        hits_after = {i: req("GET", f"/knowledge/entries/{i}", token=tok)["hitCount"]
                      for i in created["entries"]}
        assert hits_before == hits_after, f"dryRun 预览不得 bumpHits: {hits_before} -> {hits_after}"
        print(f"[2] 场景预览 OK：items={len(pv['items'])} 三层来源正确，hitCount 未涨")

        # ---------- [3] 会话挂场景 + 请求级追加 ----------
        s = req("POST", "/sessions", {
            "projectId": PID, "taskSpec": f"{MARK} 会话任务", "scenarioCode": SC,
            "agentNodeId": str(node_id), "extraKnowledgeTags": ["e2e33x"],
        }, tok)
        sid = s["id"]
        created["sessions"] = [sid]
        snap = req("GET", f"/sessions/{sid}/context", token=tok)
        assert snap["scenarioCode"] == SC, snap
        src = {(i["kind"], i["ref"]): i["source"] for i in snap["items"]}
        assert src.get(("knowledge", str(ea["id"]))) == "project-auto", src
        assert src.get(("knowledge", str(eb["id"]))) == "scenario", src
        assert src.get(("knowledge", str(ec["id"]))) == "request", src
        assert src.get(("skill", sk["id"])) == "scenario", src
        assert src.get(("doc", str(doc["id"]))) == "scenario", src
        print(f"[3] 会话快照 OK：{len(snap['items'])} 条目三层来源齐（scenario/project-auto/request）")

        # ---------- [4] 节点 token 拉包：SKILL.md base64 / 文档全文 / claudeMd ----------
        pkg = json.loads(req("GET", f"/agent/context/{sid}?token={node_token}", raw=True).decode())
        assert "场景背景" in pkg["claudeMd"] and "修登录页" not in pkg["claudeMd"]
        assert f"{MARK} 会话任务" in pkg["claudeMd"], "渲染后任务应进 当前任务 节"
        import base64
        # exportPackages 的 SKILL.md = frontmatter + 正文，断言正文被完整携带
        sk_files = pkg["skills"][0]["files"]
        decoded_sk = base64.b64decode(sk_files["SKILL.md"]).decode()
        assert skill_md.strip() in decoded_sk, decoded_sk[:200]
        assert pkg["docs"][0]["contentMd"] == doc_body
        assert pkg["docs"][0]["docId"] == str(doc["id"])
        print(f"[4] 拉包内容 OK：SKILL.md base64 一致、文档全文一致、claudeMd 含场景背景")

        # ---------- [5] 问答挂场景 + runner 沙箱物化 ----------
        c = req("POST", "/chats", {
            "message": "这个项目的发布流程怎么走？", "scenarioCode": SC,
            "agentNodeId": str(node_id),
        }, tok)
        cid = c["id"]
        created["chats"] = [cid]
        csnap = req("GET", f"/chats/{cid}/context", token=tok)
        assert csnap["scenarioCode"] == SC
        sandbox = WS / "_chat" / cid
        claude_md = wait(lambda: (sandbox / "CLAUDE.md").read_text(encoding="utf-8")
                         if (sandbox / "CLAUDE.md").exists() else None, "chat 沙箱 CLAUDE.md 物化", 30)
        assert "场景背景" in claude_md and "这个项目的发布流程怎么走？" in claude_md
        sk_file = sandbox / ".claude" / "skills" / f"{MARK}-skill" / "SKILL.md"
        assert sk_file.is_file() and skill_md.strip() in sk_file.read_text(encoding="utf-8")
        doc_file = sandbox / ".devmind" / "docs" / f"{doc['id']}.md"
        # 物化文件带 `# 标题` 头，断言正文完整在内
        assert doc_file.is_file() and doc_body in doc_file.read_text(encoding="utf-8")
        print(f"[5] 问答沙箱物化 OK：CLAUDE.md + .claude/skills/SKILL.md + .devmind/docs/{doc['id']}.md")

        # ---------- [6] 旧 templateCode 兼容 ----------
        s2 = req("POST", "/sessions", {
            "projectId": PID, "taskSpec": f"{MARK} 兼容会话", "templateCode": SC,
            "agentNodeId": str(node_id),
        }, tok)
        created["sessions"].append(s2["id"])
        snap2 = req("GET", f"/sessions/{s2['id']}/context", token=tok)
        assert snap2["scenarioCode"] == SC, "templateCode 应按 scenarioCode 解析"
        print(f"[6] templateCode 兼容 OK：{s2['id']} 快照 scenarioCode={SC}")

        # ---------- [7] 后端重启 → find 重建（session 表 / ChatContextLookup 两条路径） ----------
        pid = backend_pid()
        assert pid, "未找到后端进程"
        kill_tree(pid)
        time.sleep(3)
        subprocess.Popen(["cmd", "/c", "mvn", "-q", "-pl", "devmind-app", "spring-boot:run"], cwd=ROOT,
                         stdout=open(TMP / "cap33-app-restart.log", "w", encoding="utf-8"),
                         stderr=subprocess.STDOUT,
                         creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
        backend_restarted = True
        wait(_health, "后端重启恢复", 180)
        login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
        tok = login.get("token") or login.get("accessToken")
        pkg2 = json.loads(req("GET", f"/agent/context/{sid}?token={node_token}", raw=True).decode())
        assert "场景背景" in pkg2["claudeMd"], "重启后 session 包应可重建"
        pkg3 = json.loads(req("GET", f"/agent/context/{cid}?token={node_token}", raw=True).decode())
        assert "场景背景" in pkg3["claudeMd"], "重启后 chat 包应可重建（ChatContextLookup）"
        print("[7] 后端重启后 find 重建 OK：session/chat 两条路径均可再拉包")
    finally:
        kill_tree(runner.pid)
        # 清理（后端可能刚重启，等它起来再清）
        try:
            if backend_restarted or not _health():
                wait(_health, "清理前后端可用", 180)
                login = req("POST", "/auth/login", {"username": "admin", "password": "admin123"})
                tok = login.get("token") or login.get("accessToken")
            for i in created["sessions"]:
                try: req("DELETE", f"/sessions/{i}", token=tok)
                except Exception: pass
            for i in created["chats"]:
                try: req("DELETE", f"/chats/{i}", token=tok)
                except Exception: pass
            for i in created["scenarios"]:
                try: req("DELETE", f"/scenarios/{i}", token=tok)
                except Exception: pass
            for i in created["entries"]:
                try: req("DELETE", f"/knowledge/entries/{i}", token=tok)
                except Exception: pass
            for i in created["skills"]:
                try: req("DELETE", f"/skills/{i}", token=tok)
                except Exception: pass
            for i in created["docs"]:
                try: req("DELETE", f"/documents/{i}", token=tok)
                except Exception: pass
            req("DELETE", f"/agent-nodes/{node_id}", token=tok)
        except Exception as ex:
            print(f"[cleanup] {ex}")
    print("全部通过")

if __name__ == "__main__":
    sys.exit(main())
