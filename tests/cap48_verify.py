# -*- coding: utf-8 -*-
"""CAP-48 模型接入管理 E2E（embedding-mock + 迁移种子 mock 端点，无需外部服务）。

运行前提：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. app 独立实例已起（**必须是迁移前只配了 devmind.knowledge.embedding.* 的库**，才能验迁移种子）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18090 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:./tmp/cap48-e2e/devmind;AUTO_SERVER=TRUE \
       --devmind.knowledge.embedding.provider=mock --devmind.knowledge.embedding.dimensions=64 \
       --devmind.knowledge.embedding.chunkSize=400 --devmind.knowledge.embedding.chunkOverlap=80"
3. 本机有 python（tests/fixtures/embedding-mock.py 由脚本自起，端口 EMB_MOCK_PORT，默认 18193）。

覆盖：FR-07 迁移（provider=mock → 平台默认 mock 端点）、FR-01 端点 CRUD/校验、
FR-03 连接测试（实测维度回写、维度变化告警、失败诊断且不回显凭据）、
FR-04 解析链（库级覆盖 → 平台默认 → 无，停用/删除后回落且不回写脏数据）、
FR-06 索引血缘与维度失配诊断（DIMENSION_MISMATCH / NO_EMBEDDING，不静默空结果）、
FR-08 重建索引（全库 / 只重建失配）、删除端点引用保护。

注意：本脚本会删除迁移生成的种子端点（迁移幂等判断是"表里已有 EMBEDDING 端点就跳过"，
删完不重启就在也不会补），所以**请在 cap44/45/46 之后运行**，或在干净实例上单独跑。
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
EMB_PORT = int(os.environ.get("EMB_MOCK_PORT", "18193"))
EMB_BASE = f"http://127.0.0.1:{EMB_PORT}/v1"
FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "embedding-mock.py")
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
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw.decode("utf-8", "replace")


def _mock_call(path, body=None):
    """控制面调用（改维度 / 注入故障 / 清状态；body 为 None 走 GET）"""
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{EMB_PORT}{path}", data=data,
                                 method="POST" if body is not None else "GET",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read())


def check(name, cond, detail=""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  PASS  {name}")
    else:
        failed += 1
        print(f"  FAIL  {name}  {detail}")


def endpoints():
    st, eps = call("GET", "/model-endpoints")
    return eps if st == 200 else []


def ep(ep_id):
    return next((e for e in endpoints() if e["id"] == ep_id), None)


def get_kb(kb_id):
    st, kb = call("GET", f"/knowledge/bases/{kb_id}")
    return kb if st == 200 else {}


def wait_kb(kb_id, pred, timeout=90):
    """轮询知识库视图直到断言成立（异步索引由服务端 knowledge-index 单线程执行）"""
    kb = {}
    deadline = time.time() + timeout
    while time.time() < deadline:
        kb = get_kb(kb_id)
        if pred(kb):
            return kb
        time.sleep(1)
    return kb


def wait_entry_ready(entry_id, timeout=90):
    deadline = time.time() + timeout
    e = {}
    while time.time() < deadline:
        st, e = call("GET", f"/knowledge/entries/{entry_id}")
        if st == 200 and e.get("indexStatus") in ("ready", "failed"):
            return e
        time.sleep(1)
    return e


def search(kb_ids, query, top_k=None):
    st, r = call("POST", "/knowledge/search", {"kbIds": kb_ids, "query": query, "topK": top_k})
    return r if st == 200 else {"chunks": [], "vector": False, "degradedReason": "HTTP" + str(st)}


# ---------- 起 embedding-mock ----------
mock_proc = subprocess.Popen([sys.executable, FIXTURE, str(EMB_PORT)],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
try:
    for _ in range(50):
        try:
            _mock_call("/__state")
            break
        except Exception:
            time.sleep(0.2)
    else:
        print("embedding-mock 启动失败")
        sys.exit(1)

    # ---------- 0. 登录 ----------
    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("accessToken") or (login or {}).get("token")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    # ---------- A. FR-07 迁移锚点：provider=mock → 平台默认 mock 端点 ----------
    print("\n[A] 迁移种子 + 无覆盖库走平台默认端点")
    eps = endpoints()
    seeded = next((e for e in eps if e["provider"] == "mock" and e["isDefault"]), None)
    check("迁移生成平台默认 mock 端点", seeded is not None, f"{eps}")
    check("迁移端点维度取配置（64）", (seeded or {}).get("dimensions") == 64, f"{seeded}")
    check("迁移端点无凭据且名非空", not (seeded or {}).get("hasApiKey")
          and bool((seeded or {}).get("name")), f"{seeded}")
    E1 = (seeded or {}).get("id")
    E1_NAME = (seeded or {}).get("name")

    st, kb_a = call("POST", "/knowledge/bases",
                    {"name": "CAP48-E2E-平台默认", "scope": "global", "injectMode": "RAG"})
    check("建库 A（不传覆盖）", st == 200, f"{st} {kb_a}")
    A = (kb_a or {}).get("id")
    check("库 A 无库级覆盖且生效端点=平台默认",
          (kb_a or {}).get("modelEndpointId") is None
          and (kb_a or {}).get("modelEndpointName") == E1_NAME, f"{kb_a}")

    A_TEXT = "CAP48 端点解析 alpha 重建索引验证"
    st, entry_a = call("POST", "/knowledge/entries",
                       {"kbId": A, "name": "CAP48-E2E-条目A", "contentMd": A_TEXT})
    check("建条目 A", st == 200, f"{st} {entry_a}")
    EA = wait_entry_ready((entry_a or {}).get("id"))
    check("条目 A 索引 ready（迁移端点 64 维）", EA.get("indexStatus") == "ready", f"{EA}")

    kb_a = get_kb(A)
    check("库 A 健康度 ready=1 且无失配",
          kb_a.get("indexStats", {}).get("ready") == 1
          and kb_a.get("indexStats", {}).get("mismatched") == 0, f"{kb_a.get('indexStats')}")

    s = search([A], "端点解析")
    check("迁移端点下走向量通道并命中",
          s.get("vector") is True and s.get("degradedReason") == "NONE"
          and len(s.get("chunks") or []) >= 1 and s["chunks"][0]["score"] > 0, f"{s}")

    # ---------- B. FR-01/03 建端点 + 连接测试 ----------
    print("\n[B] 端点创建 + 连接测试（实测维度回写）")
    st, e2 = call("POST", "/model-endpoints",
                  {"kind": "EMBEDDING", "name": "CAP48-E2E-远端8维", "provider": "openai-compatible",
                   "baseUrl": EMB_BASE, "apiKey": "sk-e2e-remote-123456", "model": "fake-embed"})
    check("建 openai-compatible 端点", st == 200 and (e2 or {}).get("provider") == "openai-compatible",
          f"{st} {e2}")
    E2 = (e2 or {}).get("id")
    check("HTTP 视图不回显凭据字段", "apiKey" not in (e2 or {}) and (e2 or {}).get("hasApiKey") is True,
          f"{e2}")
    check("新建端点未探测过维度", (e2 or {}).get("dimensions") is None, f"{e2}")

    st, t2 = call("POST", f"/model-endpoints/{E2}/test")
    check("连接测试 ok 且实测维度 8", st == 200 and (t2 or {}).get("ok") is True
          and (t2 or {}).get("dimensions") == 8, f"{st} {t2}")
    e2 = ep(E2)
    check("实测维度回写端点 + 最近测试结果落库",
          e2.get("dimensions") == 8 and e2.get("lastTestOk") is True
          and bool(e2.get("lastTestAt")), f"{e2}")

    st, t_draft = call("POST", "/model-endpoints/test",
                       {"provider": "openai-compatible", "baseUrl": EMB_BASE, "model": "fake-embed"})
    check("草稿预检（凭据不落库）同样返回实测维度",
          st == 200 and (t_draft or {}).get("ok") is True
          and (t_draft or {}).get("dimensions") == 8, f"{st} {t_draft}")

    # ---------- C. FR-04 平台默认切换：失配当天可见 ----------
    print("\n[C] 换平台默认端点 → 库立刻报失配 → 定向重建")
    st, e2_def = call("PUT", f"/model-endpoints/{E2}/default")
    check("设为平台默认", st == 200 and (e2_def or {}).get("isDefault") is True, f"{st} {e2_def}")
    check("同类型默认唯一（旧默认被摘）", ep(E1).get("isDefault") is False, f"{ep(E1)}")

    kb_a = get_kb(A)
    check("库 A 生效端点跟随平台默认",
          kb_a.get("modelEndpointId") is None and kb_a.get("modelEndpointName") == "CAP48-E2E-远端8维",
          f"{kb_a}")
    check("换端点后库 A 立刻报 1 条失配（不静默劣化）",
          kb_a.get("indexStats", {}).get("mismatched") == 1, f"{kb_a.get('indexStats')}")

    s = search([A], "端点解析")
    check("失配时检索显式报 DIMENSION_MISMATCH 且不复用旧向量",
          s.get("vector") is True and s.get("degradedReason") == "DIMENSION_MISMATCH"
          and (s.get("chunks") or []) == [], f"{s}")

    st, rr = call("POST", f"/knowledge/bases/{A}/reindex?onlyMismatched=true")
    check("只重建失配条目：入队 1 条", st == 200 and (rr or {}).get("queued") == 1, f"{st} {rr}")

    kb_a = wait_kb(A, lambda k: k.get("indexStats", {}).get("mismatched") == 0
                   and k.get("indexStats", {}).get("ready") == 1)
    check("重建后失配清零、ready=1", kb_a.get("indexStats", {}).get("mismatched") == 0
          and kb_a.get("indexStats", {}).get("ready") == 1, f"{kb_a.get('indexStats')}")
    s = search([A], "端点解析")
    check("重建后走新端点向量命中（score>0）",
          s.get("vector") is True and s.get("degradedReason") == "NONE"
          and (s.get("chunks") or []) and s["chunks"][0]["score"] > 0, f"{s}")

    # ---------- D. FR-04 库级覆盖优先于平台默认 ----------
    print("\n[D] 库级覆盖优先 + 跨端点库合并检索")
    st, kb_b = call("POST", "/knowledge/bases",
                    {"name": "CAP48-E2E-库级覆盖", "scope": "global", "injectMode": "RAG",
                     "modelEndpointId": E1})
    check("建库 B 并指定库级覆盖端点", st == 200 and (kb_b or {}).get("modelEndpointId") == E1,
          f"{st} {kb_b}")
    check("库 B 生效端点为覆盖端点（非平台默认）",
          (kb_b or {}).get("modelEndpointName") == E1_NAME, f"{kb_b}")
    B = (kb_b or {}).get("id")

    B_TEXT = "CAP48 库级覆盖 beta 独立端点验证"
    st, entry_b = call("POST", "/knowledge/entries",
                       {"kbId": B, "name": "CAP48-E2E-条目B", "contentMd": B_TEXT})
    EB = wait_entry_ready((entry_b or {}).get("id"))
    check("条目 B 索引 ready（覆盖端点 64 维）", EB.get("indexStatus") == "ready", f"{EB}")
    check("库 B 健康度 ready=1 无失配",
          get_kb(B).get("indexStats", {}).get("ready") == 1
          and get_kb(B).get("indexStats", {}).get("mismatched") == 0, f"{get_kb(B)}")

    s = search([A, B], "端点")
    kbs_hit = {c["kbId"] for c in (s.get("chunks") or [])}
    check("跨库检索按端点分组分别算分并合并命中",
          s.get("degradedReason") == "NONE" and A in kbs_hit and B in kbs_hit, f"{s}")

    # ---------- E. FR-04 覆盖端点停用/删除 → 回落平台默认且不回写 ----------
    print("\n[E] 覆盖端点停用 → 回落平台默认（不写脏数据）+ 引用保护")
    call("PUT", f"/model-endpoints/{E1}/status", {"status": "disabled"})
    kb_b = get_kb(B)
    check("覆盖端点停用后回落平台默认（覆盖值仍保留）",
          kb_b.get("modelEndpointId") == E1
          and kb_b.get("modelEndpointName") == "CAP48-E2E-远端8维", f"{kb_b}")
    check("回落后库 B 报失配（64 维索引 vs 8 维端点）",
          kb_b.get("indexStats", {}).get("mismatched") == 1, f"{kb_b.get('indexStats')}")

    st, err = call("DELETE", f"/model-endpoints/{E1}")
    check("被知识库引用的端点删除被拒 409",
          st == 409 and "CAP48-E2E-库级覆盖" in json.dumps(err, ensure_ascii=False), f"{st} {err}")

    call("PUT", f"/model-endpoints/{E1}/status", {"status": "active"})
    check("端点重新启用后库 B 回到覆盖端点（无残留降级）",
          get_kb(B).get("modelEndpointName") == E1_NAME, f"{get_kb(B)}")

    st, kb_b = call("PUT", f"/knowledge/bases/{B}", {"modelEndpointId": 0})
    check("传 0 清除库级覆盖并回落平台默认",
          st == 200 and (kb_b or {}).get("modelEndpointId") is None
          and (kb_b or {}).get("modelEndpointName") == "CAP48-E2E-远端8维", f"{st} {kb_b}")

    st, rr = call("POST", f"/knowledge/bases/{B}/reindex?onlyMismatched=false")
    check("全库重建入队全部条目", st == 200 and (rr or {}).get("queued") == 1, f"{st} {rr}")
    kb_b = wait_kb(B, lambda k: k.get("indexStats", {}).get("mismatched") == 0
                   and k.get("indexStats", {}).get("ready") == 1)
    check("库 B 重建后无失配", kb_b.get("indexStats", {}).get("mismatched") == 0,
          f"{kb_b.get('indexStats')}")

    # ---------- F. FR-03 维度变化告警 + FR-06 失配再诊断 ----------
    print("\n[F] 端点重测维度变化 → 告警 + 库失配可见 → 重建")
    _mock_call("/__dims", {"dims": 12})
    st, t2b = call("POST", f"/model-endpoints/{E2}/test")
    check("重测得到新维度 12 且带 dimensionChanged(8→12)",
          st == 200 and (t2b or {}).get("dimensions") == 12
          and (t2b or {}).get("dimensionChanged") == {"from": 8, "to": 12}, f"{st} {t2b}")
    check("端点表维度已更新为 12", ep(E2).get("dimensions") == 12, f"{ep(E2)}")

    kb_a = get_kb(A)
    check("维度变化后库 A 立刻报失配（8 维索引 vs 12 维端点）",
          kb_a.get("indexStats", {}).get("mismatched") == 1, f"{kb_a.get('indexStats')}")
    st, rr = call("POST", f"/knowledge/bases/{A}/reindex?onlyMismatched=true")
    check("定向重建受理", st == 200 and (rr or {}).get("queued") == 1, f"{st} {rr}")
    kb_a = wait_kb(A, lambda k: k.get("indexStats", {}).get("mismatched") == 0)
    check("重建后库 A 失配清零", kb_a.get("indexStats", {}).get("mismatched") == 0,
          f"{kb_a.get('indexStats')}")
    s = search([A], "端点解析")
    check("12 维端点下检索命中", s.get("degradedReason") == "NONE"
          and (s.get("chunks") or []) and s["chunks"][0]["score"] > 0, f"{s}")

    # ---------- G. FR-04/06 无可用端点 → LIKE 降级不静默 ----------
    print("\n[G] 无可用端点 → LIKE 降级（vector=false，score=0，仍有命中）")
    call("PUT", f"/model-endpoints/{E2}/status", {"status": "disabled"})
    check("停用平台默认端点时默认标记被摘除",
          ep(E2).get("isDefault") is False and ep(E2).get("status") == "disabled", f"{ep(E2)}")

    kb_a = get_kb(A)
    check("库 A 无可用端点：生效端点为空、失配不可判定为 0",
          kb_a.get("modelEndpointName") is None
          and kb_a.get("indexStats", {}).get("mismatched") == 0, f"{kb_a}")
    s = search([A], "alpha")
    check("无端点时显式 NO_EMBEDDING 降级",
          s.get("vector") is False and s.get("degradedReason") == "NO_EMBEDDING", f"{s}")
    check("降级仍按关键词命中（score=0，不静默空结果）",
          bool(s.get("chunks")) and s["chunks"][0]["score"] == 0, f"{s}")

    # ---------- H. FR-01 校验与删除 ----------
    print("\n[H] 参数校验 + 无引用端点可删")
    st, _ = call("POST", "/model-endpoints", {"kind": "CHAT", "name": "非目标类型", "provider": "mock"})
    check("kind=CHAT 本期拒绝 400", st == 400, f"{st}")
    st, _ = call("POST", "/model-endpoints",
                 {"name": "非法 provider", "provider": "anthropic"})
    check("非法 provider 拒绝 400", st == 400, f"{st}")
    st, _ = call("POST", "/model-endpoints",
                 {"name": "非法 baseUrl", "provider": "openai-compatible",
                  "baseUrl": "ftp://x", "model": "m"})
    check("baseUrl 非 http/https 拒绝 400", st == 400, f"{st}")

    st, _ = call("DELETE", f"/model-endpoints/{E1}")
    check("无引用端点删除成功", st == 200, f"{st}")
    check("删除后端点清单不含该端点", all(e["id"] != E1 for e in endpoints()), f"{endpoints()}")

    # ---------- I. FR-02/03 失败诊断且不回显凭据 ----------
    print("\n[I] 连接测试失败：诊断可读且凭据脱敏")
    _mock_call("/__status", {"code": 401})
    st, e3 = call("POST", "/model-endpoints",
                  {"name": "CAP48-E2E-故障端点", "provider": "openai-compatible",
                   "baseUrl": EMB_BASE, "apiKey": "sk-e2e-secret-987654", "model": "fake-embed"})
    check("建故障端点（带凭据）", st == 200 and (e3 or {}).get("hasApiKey") is True, f"{st} {e3}")
    E3 = (e3 or {}).get("id")
    st, t3 = call("POST", f"/model-endpoints/{E3}/test")
    msg = (t3 or {}).get("message") or ""
    check("连接测试失败 ok=false 且报 401", st == 200 and (t3 or {}).get("ok") is False
          and "401" in msg, f"{st} {t3}")
    check("失败消息不含明文密钥（已抹成 ***）",
          "sk-e2e-secret-987654" not in msg and "***" in msg, f"{msg}")
    check("失败不写维度（保持未探测）", ep(E3).get("dimensions") is None, f"{ep(E3)}")

    _mock_call("/__status", {"code": 200})
    st, t3b = call("POST", f"/model-endpoints/{E3}/test")
    check("恢复后端点上重测成功并写入维度",
          st == 200 and (t3b or {}).get("ok") is True and ep(E3).get("dimensions") == 12,
          f"{st} {t3b} {ep(E3)}")

    print(f"\n== CAP-48 E2E: {passed} passed, {failed} failed ==")
    sys.exit(1 if failed else 0)
finally:
    mock_proc.terminate()
