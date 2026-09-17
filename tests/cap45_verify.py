# -*- coding: utf-8 -*-
"""CAP-45 飞书文档对接 E2E（feishu-mock + mock embedding，无需外部服务）。

运行前提：
1. app 已起且配了 devmind.knowledge.embedding.provider=mock
   （建议独立实例：--server.port=18090 --spring.datasource.url=jdbc:h2:file:<tmp路径> --devmind.knowledge.embedding.provider=mock）。
2. 本机有 node（tests/fixtures/feishu-mock.js 由脚本自起，端口 FEISHU_MOCK_PORT，默认 18191）。

覆盖：FEISHU 集成创建+连接测试、知识集成清单、docx/wiki/doc 三形态导入（created）、
重复导入 unchanged、失败 URL 隔离、条目 source/externalId/path 落库、索引 ready + 向量检索命中、
内容变更重同步 updated、同内容重同步 unchanged、飞书侧失败重同步 failed 保留旧内容。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18090/api")
MOCK_PORT = int(os.environ.get("FEISHU_MOCK_PORT", "18191"))
MOCK_BASE = f"http://127.0.0.1:{MOCK_PORT}"
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "feishu-mock.js")
passed = 0
failed = 0
TOKEN = None


def call(method, path, body=None, base=None):
    url = quote((base or BASE) + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if TOKEN and base is None:
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
    e = None
    while time.time() < deadline:
        st, e = call("GET", f"/knowledge/entries/{entry_id}")
        if st == 200 and e.get("indexStatus") in ("ready", "failed"):
            return e
        time.sleep(1)
    return e


# ---------- 起 feishu-mock ----------
mock_proc = subprocess.Popen(["node", FIXTURE, str(MOCK_PORT)],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
try:
    for _ in range(50):
        try:
            st, _ = call("POST", "/open-apis/auth/v3/tenant_access_token/internal",
                         {"app_id": "cli_e2e", "app_secret": "secret_e2e"}, base=MOCK_BASE)
            if st == 200:
                break
        except Exception:
            time.sleep(0.2)
    else:
        print("feishu-mock 启动失败")
        sys.exit(1)

    # ---------- 0. 登录 ----------
    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("token") or (login or {}).get("accessToken")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    # ---------- 1. 建 FEISHU 集成 + 连接测试 ----------
    st, integ = call("POST", "/integrations", {
        "type": "FEISHU", "name": "E2E 飞书", "baseUrl": MOCK_BASE,
        "authType": "BASIC", "username": "cli_e2e", "token": "secret_e2e"})
    check("创建 FEISHU 集成", st == 200 and (integ or {}).get("type") == "FEISHU", f"{st} {integ}")
    integ_id = (integ or {}).get("id")

    st, t = call("POST", f"/integrations/{integ_id}/test")
    check("连接测试（token 获取）", st == 200 and (t or {}).get("ok") is True, f"{st} {t}")

    st, feishu_list = call("GET", "/knowledge/feishu/integrations")
    check("知识侧飞书集成清单", st == 200 and any(i.get("id") == integ_id for i in (feishu_list or [])),
          f"{st} {feishu_list}")

    # ---------- 2. 建 RAG 库 ----------
    st, kb = call("POST", "/knowledge/bases", {
        "name": "E2E 飞书库", "scope": "global", "injectMode": "RAG"})
    check("创建 RAG 知识库", st == 200, f"{st} {kb}")
    kb_id = (kb or {}).get("id")

    # ---------- 3. 导入：docx + wiki + 旧版 doc + 失败 URL ----------
    url_docx = f"{MOCK_BASE}/docx/doc-token-1?from=copylink"  # 带 query 验证归一化
    url_wiki = f"{MOCK_BASE}/wiki/wiki-node-1"
    url_doc = f"{MOCK_BASE}/docs/old-doc-1"
    url_bad = f"{MOCK_BASE}/docx/doc-missing"
    st, results = call("POST", f"/knowledge/bases/{kb_id}/import/feishu",
                       {"integrationId": integ_id, "urls": [url_docx, url_wiki, url_doc, url_bad]})
    check("导入受理", st == 200 and isinstance(results, list) and len(results) == 4, f"{st} {results}")
    by_status = {}
    by_url = {}
    for r in (results or []):
        by_status[r["status"]] = by_status.get(r["status"], 0) + 1
        by_url[r["url"]] = r
    check("docx/wiki/doc 三形态 created、bad failed",
          by_status.get("created") == 3 and by_status.get("failed") == 1, f"{by_status}")

    # 归一化：带 query 的 URL 以去 query 形态返回
    r_docx = by_url.get(f"{MOCK_BASE}/docx/doc-token-1")
    check("来源 URL 归一化（去 query）", r_docx is not None, f"{list(by_url)}")

    # ---------- 4. 条目落库形态 + 索引 + 检索 ----------
    st, entries = call("GET", f"/knowledge/bases/{kb_id}/entries")
    feishu_entries = [e for e in (entries or []) if e.get("source") == "feishu"]
    check("库内 3 条飞书条目", len(feishu_entries) == 3, f"{len(feishu_entries)}")
    e_docx = next((e for e in feishu_entries if e.get("externalId") == f"{integ_id}:doc-token-1"), None)
    e_wiki = next((e for e in feishu_entries if e.get("externalId") == f"{integ_id}:doc-token-wiki"), None)
    check("externalId={integrationId}:{docToken} 判重键", e_docx is not None and e_wiki is not None,
          f"{[e.get('externalId') for e in feishu_entries]}")
    check("wiki 条目取节点 objToken+标题",
          e_wiki is not None and e_wiki.get("name") == "E2E Wiki 发布流程", f"{e_wiki}")
    check("path 存来源 URL", e_docx is not None and e_docx.get("path") == f"{MOCK_BASE}/docx/doc-token-1",
          f"{e_docx}")
    check("docx 内容已转 markdown（heading+bullet）",
          e_docx is not None and "# E2E 飞书前端规范" in (e_docx.get("contentMd") or "")
          and "- 列表项" in (e_docx.get("contentMd") or ""),
          f"{(e_docx or {}).get('contentMd', '')[:120]}")

    e1_ready = wait_index_ready(e_docx["id"]) if e_docx else None
    check("导入条目索引 ready", e1_ready is not None and e1_ready.get("indexStatus") == "ready",
          f"{e1_ready}")
    st, s = call("POST", "/knowledge/search", {"kbIds": [kb_id], "query": "前端构建产物目录", "topK": 5})
    hits = (s or {}).get("chunks") or []
    check("飞书条目可被向量检索命中",
          st == 200 and any(h.get("entryName") == "E2E 飞书前端规范" for h in hits),
          f"{[h.get('entryName') for h in hits]}")

    # ---------- 5. 重复导入 → unchanged ----------
    st, results2 = call("POST", f"/knowledge/bases/{kb_id}/import/feishu",
                        {"integrationId": integ_id, "urls": [url_docx, url_wiki]})
    statuses2 = [r["status"] for r in (results2 or [])]
    check("同内容重复导入 unchanged", st == 200 and statuses2 == ["unchanged", "unchanged"],
          f"{st} {results2}")

    # ---------- 6. 内容变更 → 重同步 updated → 再重同步 unchanged ----------
    call("POST", "/__set_docx", {"token": "doc-token-1",
                                 "paragraphs": ["前端构建统一改用 Rsbuild，产物输出 build 目录。"]},
         base=MOCK_BASE)
    st, rs1 = call("POST", f"/knowledge/entries/{e_docx['id']}/resync")
    check("变更后重同步 updated", st == 200 and (rs1 or {}).get("status") == "updated", f"{st} {rs1}")
    st, e_after = call("GET", f"/knowledge/entries/{e_docx['id']}")
    check("重同步后内容为新版", "Rsbuild" in ((e_after or {}).get("contentMd") or ""), f"{e_after}")

    st, rs2 = call("POST", f"/knowledge/entries/{e_docx['id']}/resync")
    check("同内容重同步 unchanged", st == 200 and (rs2 or {}).get("status") == "unchanged", f"{st} {rs2}")

    # ---------- 7. 飞书侧失败 → failed 保留旧内容 ----------
    call("POST", "/__fail", {"token": "doc-token-1"}, base=MOCK_BASE)
    st, rs3 = call("POST", f"/knowledge/entries/{e_docx['id']}/resync")
    check("飞书侧失败重同步 failed", st == 200 and (rs3 or {}).get("status") == "failed", f"{st} {rs3}")
    st, e_kept = call("GET", f"/knowledge/entries/{e_docx['id']}")
    check("失败后旧内容保留", "Rsbuild" in ((e_kept or {}).get("contentMd") or ""), f"{e_kept}")

    # ---------- 8. 手动条目不可重同步 ----------
    st, manual = call("POST", "/knowledge/entries", {
        "kbId": kb_id, "name": "E2E 手动条目", "contentMd": "手动内容。", "status": "active"})
    st, rs4 = call("POST", f"/knowledge/entries/{(manual or {}).get('id')}/resync")
    check("手动条目重同步被拒 400", st == 400, f"{st} {rs4}")

    # ---------- 收尾 ----------
    print(f"\n== CAP-45 E2E: {passed} passed, {failed} failed ==")
    sys.exit(1 if failed else 0)
finally:
    mock_proc.terminate()
