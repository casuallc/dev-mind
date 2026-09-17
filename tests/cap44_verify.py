# -*- coding: utf-8 -*-
"""CAP-44 知识库容器化与向量检索 E2E（mock embedding，无需外部服务）。

运行前提：app 已起且配了 devmind.knowledge.embedding.provider=mock
（建议独立实例：--server.port=18090 --spring.datasource.url=jdbc:h2:file:<tmp路径> --devmind.knowledge.embedding.provider=mock）。

覆盖：库 CRUD/级联删除、legacy scope 建条目兜底解析经验库、条目索引状态机
（pending→ready）、向量检索命中与排序、内容更新自动重索引、手动 reindex。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18090/api")
passed = 0
failed = 0
TOKEN = None


def call(method, path, body=None):
    url = quote(BASE + path, safe=":/?&=%,.-")
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


def wait_index_ready(entry_id, timeout=60):
    deadline = time.time() + timeout
    while time.time() < deadline:
        st, e = call("GET", f"/knowledge/entries/{entry_id}")
        if st == 200 and e.get("indexStatus") == "ready":
            return e
        if st == 200 and e.get("indexStatus") == "failed":
            return e
        time.sleep(1)
    return e if st == 200 else None


# ---------- 0. 登录 ----------
st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
if not TOKEN:
    sys.exit(1)

# ---------- 1. 建 RAG 库 ----------
st, kb = call("POST", "/knowledge/bases", {
    "name": "E2E 规范库", "description": "cap44 verify", "scope": "global", "injectMode": "RAG"})
check("创建 RAG 知识库", st == 200 and kb.get("injectMode") == "RAG", f"{st} {kb}")
kb_id = kb.get("id") if kb else None

st, bases = call("GET", "/knowledge/bases")
check("库列表含新库", st == 200 and any(b.get("id") == kb_id for b in (bases or [])), f"{st}")

# ---------- 2. legacy scope 建条目 → 兜底解析/建经验库 ----------
st, legacy = call("POST", "/knowledge/entries", {
    "scope": "global", "name": "E2E-legacy 全局经验", "contentMd": "旧接口创建的条目。",
    "tags": [], "status": "active"})
check("legacy scope=global 建条目", st == 200 and legacy.get("kbId"), f"{st} {legacy}")
check("legacy 条目 scope 派生自库", (legacy or {}).get("scope") == "global", f"{legacy}")
if legacy and legacy.get("kbId"):
    st, exp_kb = call("GET", f"/knowledge/bases/{legacy['kbId']}")
    check("经验库为 FULL 注入模式", st == 200 and exp_kb.get("injectMode") == "FULL", f"{st} {exp_kb}")

# ---------- 3. 建条目 → 异步索引 ready ----------
st, e1 = call("POST", "/knowledge/entries", {
    "kbId": kb_id, "name": "前端构建规范",
    "contentMd": "# 前端构建规范\n前端构建统一使用 Vite，产物输出 dist 目录。",
    "tags": ["frontend"], "status": "active"})
check("RAG 库建条目", st == 200 and e1.get("kbId") == kb_id, f"{st} {e1}")
e1_id = e1.get("id") if e1 else None

e1_ready = wait_index_ready(e1_id)
check("条目索引状态 ready（mock embedding）",
      e1_ready is not None and e1_ready.get("indexStatus") == "ready",
      f"{e1_ready}")
st, kb_after = call("GET", f"/knowledge/bases/{kb_id}")
check("库分块数 > 0", st == 200 and kb_after.get("chunkCount", 0) > 0, f"{st} {kb_after}")

# ---------- 4. 向量检索命中与排序 ----------
st, s1 = call("POST", "/knowledge/search", {"kbIds": [kb_id], "query": "前端构建产物目录", "topK": 5})
check("检索走向量通道", st == 200 and s1.get("vector") is True, f"{st} {s1}")
hits = (s1 or {}).get("chunks") or []
check("检索命中条目", len(hits) > 0 and hits[0].get("entryName") == "前端构建规范", f"{hits[:1]}")
check("命中带相关度分", len(hits) > 0 and hits[0].get("score", 0) > 0, f"{hits[:1]}")

st, e2 = call("POST", "/knowledge/entries", {
    "kbId": kb_id, "name": "数据库备份策略", "contentMd": "数据库每日全量备份，保留 30 天。",
    "status": "active"})
e2_id = (e2 or {}).get("id")
wait_index_ready(e2_id)
st, s2 = call("POST", "/knowledge/search", {"kbIds": [kb_id], "query": "前端构建规范", "topK": 5})
hits2 = (s2 or {}).get("chunks") or []
check("多条目检索按相关度排序",
      len(hits2) > 0 and hits2[0].get("entryName") == "前端构建规范", f"{[h.get('entryName') for h in hits2]}")

# ---------- 5. 内容更新 → 自动重索引 ----------
st, e1u = call("PUT", f"/knowledge/entries/{e1_id}", {
    "name": "前端构建规范",
    "contentMd": "# 前端构建规范（修订）\n前端构建统一使用 Rsbuild，产物输出 build 目录。",
    "tags": ["frontend"], "status": "active"})
check("更新条目内容", st == 200, f"{st} {e1u}")
e1_re = wait_index_ready(e1_id)
check("更新后重索引 ready", e1_re is not None and e1_re.get("indexStatus") == "ready", f"{e1_re}")
st, s3 = call("POST", "/knowledge/search", {"kbIds": [kb_id], "query": "Rsbuild 构建产物", "topK": 5})
hits3 = (s3 or {}).get("chunks") or []
check("新内容可被检索到", len(hits) >= 0 and any("Rsbuild" in (h.get("content") or "") for h in hits3),
      f"{[h.get('content', '')[:30] for h in hits3]}")

# ---------- 6. 手动 reindex ----------
st, e1r = call("POST", f"/knowledge/entries/{e1_id}/reindex")
check("手动 reindex 受理", st == 200 and e1r.get("indexStatus") in ("pending", "ready"), f"{st} {e1r}")

# ---------- 7. 库删除保护 + 级联 ----------
st, _ = call("DELETE", f"/knowledge/bases/{kb_id}")
check("非空库无 force 删除被拒", st in (400, 409), f"{st}")
st, _ = call("DELETE", f"/knowledge/bases/{kb_id}?force=true")
check("force 级联删除", st == 200, f"{st}")
st, bases = call("GET", "/knowledge/bases")
check("删除后库列表不含该库", st == 200 and not any(b.get("id") == kb_id for b in (bases or [])), f"{st}")

# ---------- 收尾 ----------
print(f"\n== CAP-44 E2E: {passed} passed, {failed} failed ==")
sys.exit(1 if failed else 0)
