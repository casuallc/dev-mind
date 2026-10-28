# -*- coding: utf-8 -*-
"""CAP-92 场景绑定知识库 + 装配时检索 E2E（mock embedding，无需外部服务）。

运行前提：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. app 独立实例已起（18090 + 独立 H2 + mock embedding），例如：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18090 --spring.datasource.url=jdbc:h2:file:./tmp/cap92-db/devmind --devmind.knowledge.embedding.provider=mock"

覆盖：
 1. 知识库 API 不再暴露注入方式（injectMode 字段下线）
 2. 场景绑定知识库 → dryRun 预览按任务检索命中（清单 source=scenario、CLAUDE.local.md
    含「相关知识（检索命中）」与来源路径），且不 bumpHits
 3. 场景解绑知识库 → 预览不再有知识条目、不再渲染检索节
 4. 绑定已归档库 → 归档库退出检索，预览无知识条目
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://localhost:18090/api"
MARK = f"cap92-{int(time.time())}"
SC = f"e2e92-{int(time.time()) % 100000}"
TOKEN = None
passed = 0
failed = 0


def call(method, path, body=None):
    url = BASE + path
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def wait(cond, what, timeout=40):
    deadline = time.time() + timeout
    while time.time() < deadline:
        v = cond()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}")


def preview(task_spec):
    return call("GET", f"/scenarios/{SC}/preview?taskSpec={urllib.parse.quote(task_spec)}")


def knowledge_items(pv):
    return [i for i in (pv or {}).get("items", []) if i.get("kind") == "knowledge"]


st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
if not TOKEN:
    sys.exit(1)

kb_id = entry_id = scenario_id = None
try:
    # ---------- 1. 建库与条目，等索引 ready ----------
    st, kb = call("POST", "/knowledge/bases", {
        "name": f"{MARK}-运维库", "description": "cap92 e2e", "scope": "global"})
    kb_id = (kb or {}).get("id")
    check("创建知识库", st == 200 and bool(kb_id), f"{st} {kb}")
    check("知识库视图不含注入方式字段", "injectMode" not in (kb or {}), str(kb))

    st, entry = call("POST", "/knowledge/entries", {
        "kbId": kb_id, "name": f"{MARK}-部署回滚手册",
        "contentMd": "# 部署回滚\n\n部署失败时先在预发验证回滚脚本，再回到上一稳定版本。"})
    entry_id = (entry or {}).get("id")
    check("创建知识条目", st == 200 and bool(entry_id), f"{st} {entry}")
    wait(lambda: (call("GET", f"/knowledge/entries/{entry_id}")[1] or {}).get("indexStatus") == "ready",
         "条目索引 ready")

    # ---------- 2. 场景绑定库 → 预览检索命中，dryRun 不计数 ----------
    st, sc = call("POST", "/scenarios", {
        "code": SC, "name": f"{MARK} 场景", "scope": "GLOBAL",
        "promptSkeleton": "任务：{{task}}", "knowledgeBaseIds": [kb_id]})
    scenario_id = (sc or {}).get("id")
    check("场景绑定知识库", st == 200 and (sc or {}).get("knowledgeBaseIds") == [kb_id], f"{st} {sc}")

    hits_before = (call("GET", f"/knowledge/entries/{entry_id}")[1] or {}).get("hitCount")
    st, pv = preview("部署失败怎么回滚？")
    items = knowledge_items(pv)
    check("预览清单含检索命中的知识条目",
          st == 200 and any(i.get("ref") == str(entry_id) and i.get("source") == "scenario" for i in items),
          f"{st} {items}")
    claude = (pv or {}).get("claudeMd") or ""
    check("CLAUDE.local.md 含检索节", "相关知识（检索命中）" in claude, claude[:400])
    check("检索节标注来源与库名",
          f"{MARK}-部署回滚手册" in claude and f"库：{MARK}-运维库" in claude, claude[:400])
    hits_after = (call("GET", f"/knowledge/entries/{entry_id}")[1] or {}).get("hitCount")
    check("dryRun 预览不 bumpHits", hits_before == hits_after, f"{hits_before} -> {hits_after}")

    # ---------- 3. 解绑 → 不再检索 ----------
    st, _ = call("PUT", f"/scenarios/{scenario_id}", {
        "code": SC, "name": f"{MARK} 场景", "scope": "GLOBAL",
        "promptSkeleton": "任务：{{task}}", "knowledgeBaseIds": []})
    check("场景解绑知识库", st == 200, f"{st}")
    st, pv2 = preview("部署失败怎么回滚？")
    check("解绑后预览无知识条目", st == 200 and not knowledge_items(pv2), f"{st} {knowledge_items(pv2)}")
    check("解绑后不渲染检索节", "相关知识（检索命中）" not in ((pv2 or {}).get("claudeMd") or ""))

    # ---------- 4. 绑定归档库 → 归档退出检索 ----------
    st, _ = call("PUT", f"/knowledge/bases/{kb_id}", {"status": "archived"})
    check("归档知识库", st == 200, f"{st}")
    st, _ = call("PUT", f"/scenarios/{scenario_id}", {
        "code": SC, "name": f"{MARK} 场景", "scope": "GLOBAL",
        "promptSkeleton": "任务：{{task}}", "knowledgeBaseIds": [kb_id]})
    st, pv3 = preview("部署失败怎么回滚？")
    check("归档库绑定后预览无知识条目", st == 200 and not knowledge_items(pv3),
          f"{st} {knowledge_items(pv3)}")
finally:
    if scenario_id:
        try:
            call("DELETE", f"/scenarios/{scenario_id}")
        except Exception:
            pass
    if kb_id:
        try:
            call("DELETE", f"/knowledge/bases/{kb_id}?force=true")
        except Exception:
            pass

print(f"\n== CAP-92 验证结果: {passed} passed, {failed} failed ==")
sys.exit(1 if failed else 0)
