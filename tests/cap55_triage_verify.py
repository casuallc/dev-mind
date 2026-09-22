# -*- coding: utf-8 -*-
"""CAP-55 FR-04 提案分诊 E2E（laya-sidecar-mock，无需真边车/模型）。

运行前提（与 cap55_verify.py 同款）：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. app 独立实例已起（干净 H2 即可）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18095 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:./tmp/cap55-triage-e2e/devmind;AUTO_SERVER=TRUE"
3. python（tests/fixtures/laya-sidecar-mock.py 由脚本自起，端口 LAYA_MOCK_PORT 默认 18195）。

覆盖 FR-04：
  A 自动分诊——新提案入库后异步出徽标（层级/置信度/重复/质量分 + 模型与 routing.reason）；
  B 发出去的题面与 state——三题类型（choice/noul/score）、题里回引 state 键、不放"提案人自称去向"、
    超长正文按 1500 字截断、召回 query 用标题（LIKE 兜底才有命中）；
  C 重复判定两段式——无 embedding 时走关键词召回，把撞上的条目带进徽标；
  D 手动重新分诊——换 mock 答案后徽标跟着变（证明它真来自模型，不是硬编码）；
  E 降级链（FR-06）——端点删掉后：状态端点报不可用（按钮置灰）、提案照建不报错、
    分诊落 triage_degraded=true 且无徽标、知识库其余接口零影响；
  F 边界——不存在的提案分诊 404，不静默空转。
"""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18095/api")
LAYA_PORT = int(os.environ.get("LAYA_MOCK_PORT", "18195"))
TOKEN = None
passed = 0
failed = 0

# 提案标题刻意与下面的知识条目正文重叠：LIKE 兜底召回靠的就是这个子串
PROPOSAL_TITLE = "构建失败先看日志末尾"
ENTRY_CONTENT = "环境类报错九成在日志末尾 200 行，构建失败先看日志末尾再猜网络"


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


def sidecar(path, body=None):
    """假边车控制面。body=None 走 GET——无体的动作用 `{}`（否则被当 GET 打到 POST 路径回 404）"""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(f"http://127.0.0.1:{LAYA_PORT}{path}", data=data,
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


def predict_calls():
    return [r for r in sidecar("/__state")["requests"] if r["path"] == "/v1/predict"]


def proposals():
    st, ps = call("GET", "/knowledge/proposals")
    return ps if st == 200 else []


def proposal(pid):
    return next((p for p in proposals() if p["id"] == pid), None)


def create_proposal(title, content, target_scope="project", project_id=None):
    st, p = call("POST", "/knowledge/proposals",
                 {"title": title, "contentMd": content, "targetScope": target_scope,
                  "targetProjectId": project_id, "sourceSessionId": "cap55-triage-e2e"})
    assert st == 200, f"建提案失败 {st} {p}"
    return p


def wait_triage(pid, timeout=25.0):
    """等异步分诊落库（triage.at 出现）。返回最新的提案视图。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        p = proposal(pid)
        if p and (p.get("triage") or {}).get("at"):
            return p
        time.sleep(0.3)
    return proposal(pid)


def wait_retriage(pid, previous_at, timeout=25.0):
    """等"重新分诊"覆盖掉上一次（at 变了）——只等 at 非空会把旧结果当新结果"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        p = proposal(pid)
        if p and (p.get("triage") or {}).get("at") not in (None, previous_at):
            return p
        time.sleep(0.3)
    return proposal(pid)


FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "laya-sidecar-mock.py")
sidecar_proc = subprocess.Popen([sys.executable, FIXTURE, str(LAYA_PORT)],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
ENDPOINT_ID = None
KB_ID = None
ENTRY_ID = None
try:
    for _ in range(50):
        try:
            sidecar("/__state")
            break
        except Exception:
            time.sleep(0.2)
    else:
        print("laya-sidecar-mock 启动失败")
        sys.exit(1)
    SIDECAR = f"http://127.0.0.1:{LAYA_PORT}"

    # ---------- 0. 登录 + 备好端点与知识库 ----------
    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("accessToken") or (login or {}).get("token")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    print("\n[0] 前置：登记默认决策端点 + 建一个全局经验库与条目（给重复判定当比对物）")
    st, ep = call("POST", "/model-endpoints",
                  {"kind": "DECISION", "name": "CAP55-TRIAGE-E2E-决策边车", "baseUrl": SIDECAR})
    ENDPOINT_ID = (ep or {}).get("id")
    check("建 DECISION 端点", st == 200 and bool(ENDPOINT_ID), f"{st} {ep}")
    st, d = call("PUT", f"/model-endpoints/{ENDPOINT_ID}/default")
    check("设为平台默认（分诊才找得到它）", st == 200 and (d or {}).get("isDefault") is True, f"{st} {d}")

    st, kb = call("POST", "/knowledge/bases",
                  {"name": "CAP55-TRIAGE-E2E 经验库", "scope": "global", "injectMode": "FULL"})
    KB_ID = (kb or {}).get("id")
    check("建全局经验库", st == 200 and bool(KB_ID), f"{st} {kb}")
    st, entry = call("POST", "/knowledge/entries",
                     {"kbId": KB_ID, "name": "构建排错心得", "contentMd": ENTRY_CONTENT,
                      "status": "active"})
    ENTRY_ID = (entry or {}).get("id")
    check("建条目（正文含提案标题子串）", st == 200 and bool(ENTRY_ID), f"{st} {entry}")

    # ---------- A. 自动分诊出徽标 ----------
    print("\n[A] 新提案入库 → 异步自动分诊 → inbox 徽标")
    sidecar("/__reset", {})
    before = len(predict_calls())
    p1 = create_proposal(PROPOSAL_TITLE, "环境类报错九成在日志末尾 200 行，先看再猜网络。")
    check("新建提案 status=open", p1.get("status") == "open", f"{p1}")
    check("建完立刻查：还没分诊（徽标区块为空）", p1.get("triage") is None, f"{p1.get('triage')}")

    p1 = wait_triage(p1["id"])
    tri = (p1 or {}).get("triage") or {}
    check("异步分诊自动落库（无需点按钮）", bool(tri.get("at")), f"{p1}")
    check("未降级", tri.get("degraded") is False, f"{tri}")
    check("落库了边车实际选中的 checkpoint 与原因",
          tri.get("model") == "multilingual" and "non-Latin script" in (tri.get("routingReason") or ""),
          f"{tri}")
    layer = tri.get("adoptLayer") or {}
    check("层级建议 = mock 给的第一选项（global），带中文标签与置信度",
          layer.get("value") == "global" and layer.get("label") == "采纳到全局"
          and layer.get("confidence") == 0.9, f"{layer}")
    check("层级概率分布原样透出（key 是机器值）",
          set((layer.get("probabilities") or {}).keys()) == {"global", "project", "discard"}, f"{layer}")
    dup = tri.get("duplicate") or {}
    check("重复判定：noul=0.2 未过半数 → 不重复", dup.get("duplicate") is False
          and dup.get("probability") == 0.2, f"{dup}")
    quality = tri.get("quality") or {}
    check("质量分 = mock 给的最高等级（2 直接可用）",
          quality.get("level") == 2 and quality.get("label") == "直接可用"
          and quality.get("confidence") == 0.8, f"{quality}")
    check("answers 原样可查（抽屉「查看依据」用）",
          set((tri.get("answers") or {}).keys()) == {"adopt_layer", "duplicate", "quality"},
          f"{tri.get('answers')}")
    check("自动分诊确实打了一次边车 predict", len(predict_calls()) == before + 1, f"{predict_calls()}")

    # ---------- B. 发出去的题面与 state ----------
    print("\n[B] 题面与 state：三题类型 / 回引 state 键 / 不放自称去向 / 超长截断 / query 用标题")
    calls = predict_calls()
    c = calls[-1]
    qs = c["questions"]
    check("三题且类型正确：choice / noul / score",
          list(qs.keys()) == ["adopt_layer", "duplicate", "quality"]
          and [qs[k].get("type") for k in qs] == ["choice", "noul", "score"], f"{list(qs.items())}")
    check("choice 的 criteria 是 {机器值: 说明}",
          list((qs["adopt_layer"].get("criteria") or {}).keys()) == ["global", "project", "discard"],
          f"{qs['adopt_layer']}")
    check("score 的 criteria 是三级列表（下标即分值）",
          isinstance(qs["quality"].get("criteria"), list) and len(qs["quality"]["criteria"]) == 3,
          f"{qs['quality']}")
    check("题面回引 state 键名（模型知道去哪取值）",
          "`proposal_title`" in qs["adopt_layer"]["instructions"]
          and "`proposal_content`" in qs["quality"]["instructions"]
          and "`similar_entries`" in qs["duplicate"]["instructions"], f"{qs}")
    state = c["state"]
    check("state 带标题/正文/project/相似条目", state.get("proposal_title") == PROPOSAL_TITLE
          and bool(state.get("proposal_content")) and "project" in state
          and "similar_entries" in state, f"{state}")
    check("state 不放提案人自称的去向（不给模型下锚）",
          "declared_target" not in state and "targetScope" not in json.dumps(state), f"{state}")

    long_text = "中文" * 1200  # 2400 字 > 1500
    p_long = create_proposal("超长提案", long_text)
    p_long = wait_triage(p_long["id"])
    long_state = predict_calls()[-1]["state"]
    check("超长正文发给边车前按 1500 字截断且留标记",
          len(long_state["proposal_content"]) < 1600
          and "（已截断）" in long_state["proposal_content"], f"{len(long_state['proposal_content'])}")
    check("超长提案同样出徽标（截断不影响分诊）",
          ((p_long or {}).get("triage") or {}).get("degraded") is False, f"{p_long}")

    # ---------- C. 重复判定两段式（关键词召回） ----------
    print("\n[C] 重复判定两段式：先召回相似条目（无 embedding 走关键词），再交给模型判")
    similar = dup.get("similar") or []
    check("召回把撞上的条目带进徽标（LIKE 兜底命中）",
          any(s.get("entryName") == "构建排错心得" for s in similar), f"{similar}")
    check("召回依据带降级说明（没配 embedding → 结论仅供参考）",
          "关键词" in (dup.get("note") or ""), f"{dup.get('note')}")
    check("召回 query 是标题而非全文（否则 LIKE 恒零命中）",
          PROPOSAL_TITLE in (predict_calls()[0]["state"]["similar_entries"] or ""), "")

    # ---------- D. 手动重新分诊 ----------
    print("\n[D] 手动分诊（202 异步）：换 mock 答案后徽标跟着变")
    previous_at = ((p1 or {}).get("triage") or {}).get("at")
    sidecar("/__answers", {"answers": {
        "adopt_layer": {"type": "choice", "choice": "discard",
                        "probabilities": {"global": 0.1, "project": 0.2, "discard": 0.7},
                        "confidence": 0.7},
        "duplicate": {"type": "noul", "noul": 0.71, "confidence": 0.75, "probabilities": {}},
        "quality": {"type": "score", "score": 0.0,
                    "probabilities": {"0": 0.6, "1": 0.3, "2": 0.1}, "confidence": 0.6},
    }})
    st, _ = call("POST", f"/knowledge/proposals/{p1['id']}/triage")
    check("手动分诊返回 202（排队不等结果）", st == 202, f"{st}")
    p1 = wait_retriage(p1["id"], previous_at)
    tri2 = (p1 or {}).get("triage") or {}
    check("重新分诊覆盖旧结果（at 变了）", tri2.get("at") not in (None, previous_at), f"{tri2.get('at')}")
    check("徽标随模型变：discard → 建议放弃",
          (tri2.get("adoptLayer") or {}).get("value") == "discard"
          and (tri2.get("adoptLayer") or {}).get("label") == "建议放弃", f"{tri2.get('adoptLayer')}")
    check("noul=0.71 过半数 → 提示重复",
          (tri2.get("duplicate") or {}).get("duplicate") is True, f"{tri2.get('duplicate')}")
    check("质量分变 0 → 含糊不可用",
          (tri2.get("quality") or {}).get("level") == 0
          and (tri2.get("quality") or {}).get("label") == "含糊不可用", f"{tri2.get('quality')}")
    sidecar("/__answers", {"answers": None})

    # ---------- E. 降级链（FR-06） ----------
    print("\n[E] 端点删除 → 全链路降级（无徽标、按钮置灰、其余功能零影响）")
    st, s = call("GET", "/knowledge/proposals/triage-status")
    check("配好时状态端点报可用", st == 200 and (s or {}).get("available") is True, f"{st} {s}")
    st, _ = call("DELETE", f"/model-endpoints/{ENDPOINT_ID}")
    check("删掉 DECISION 端点", st == 200, f"{st}")
    st, s = call("GET", "/knowledge/proposals/triage-status")
    check("按钮置灰：available=false 且带原因（指向模型接入）",
          st == 200 and (s or {}).get("available") is False and "模型接入" in (s or {}).get("reason", ""),
          f"{st} {s}")

    p2 = create_proposal("降级期提案", "端点没了也该能正常入库")
    check("端点缺失时提案照建（不 5xx）", p2.get("id") is not None, f"{p2}")
    st, ps = call("GET", "/knowledge/proposals")
    check("提案列表照常 200（inbox 不报错）", st == 200 and len(ps) >= 3, f"{st} {len(ps) if st == 200 else ps}")
    p2 = wait_triage(p2["id"])
    tri3 = (p2 or {}).get("triage") or {}
    check("降级也落库（triage_at 有值）", bool(tri3.get("at")), f"{p2}")
    check("降级徽标：degraded=true + 原因，三块建议全空",
          tri3.get("degraded") is True and "模型接入" in (tri3.get("degradedReason") or "")
          and tri3.get("adoptLayer") is None and tri3.get("quality") is None, f"{tri3}")
    before_calls = len(predict_calls())
    st, _ = call("POST", f"/knowledge/proposals/{p2['id']}/triage")
    check("降级期手动分诊仍 202（不 500）", st == 202, f"{st}")
    time.sleep(1.5)
    check("没端点时一个 predict 都不发", len(predict_calls()) == before_calls, f"{predict_calls()}")
    st, kbv = call("GET", f"/knowledge/bases/{KB_ID}")
    check("知识库其余功能零影响（库详情照常）", st == 200 and (kbv or {}).get("id") == KB_ID, f"{st}")
    st, sr = call("POST", "/knowledge/search", {"kbIds": [KB_ID], "query": "构建", "topK": 5})
    check("检索接口零影响", st == 200, f"{st} {sr}")

    # ---------- F. 边界 ----------
    print("\n[F] 边界：不存在的提案分诊 404 而非静默空转")
    st, r = call("POST", "/knowledge/proposals/999999/triage")
    check("分诊不存在的提案 → 404", st == 404, f"{st} {r}")

    # ---------- G. 清理 ----------
    print("\n[G] 清理：建的知识条目/库可删（端点已删）")
    if ENTRY_ID:
        st, _ = call("DELETE", f"/knowledge/entries/{ENTRY_ID}")
        check("删条目", st == 200, f"{st}")
    if KB_ID:
        st, _ = call("DELETE", f"/knowledge/bases/{KB_ID}", )
        check("删知识库", st == 200, f"{st}")

    print(f"\n== CAP-55 FR-04 E2E: {passed} passed, {failed} failed ==")
    sys.exit(1 if failed else 0)
finally:
    sidecar_proc.terminate()
