# -*- coding: utf-8 -*-
"""CAP-55 FR-02 决策端点 E2E（laya-sidecar-mock，无需真边车/模型）。

运行前提：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. app 独立实例已起（干净 H2 即可）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18095 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:./tmp/cap55-e2e/devmind;AUTO_SERVER=TRUE"
3. 本机有 python（tests/fixtures/laya-sidecar-mock.py 由脚本自起，端口 LAYA_MOCK_PORT，默认 18195）。

覆盖 FR-02：DECISION 端点登记（provider 默认 laya、model = checkpoint 别名可空、不吃向量语义）、
连接测试两段实调（/healthz → 固定样例 /v1/predict，message 带常驻清单 + 样例答案 + routing.reason）、
边车未就绪（status≠ok）不白跑样例题、样例失败透出边车 detail、mock provider 自报不走网络、
kind↔provider 配对校验 400、baseUrl 必填而 model 可空、同类型唯一的平台默认、无引用可删。

注意：本脚本假设实例里没有别的 DECISION 端点（会断言"设为默认后 DECISION 默认唯一"），
跑完自己建的端点全删。真边车（tools/laya-sidecar）连通性由 FR-01 的 smoke 覆盖，这里只钉 Java 侧协议。
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
    """假边车控制面（改答案 / 换 checkpoint / 注入故障 / 回看请求）。

    body=None 走 GET——**无体的动作用 `{}`**：写成 sidecar("/__reset") 会被发成 GET 打到
    /__reset，被边车当未知路径回 404（本脚本第一次跑就栽在这）。"""
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


def endpoints():
    st, eps = call("GET", "/model-endpoints")
    return eps if st == 200 else []


def ep(ep_id):
    return next((e for e in endpoints() if e["id"] == ep_id), None)


def predict_calls():
    """假边车收到的 /v1/predict 请求（healthz 不计入）"""
    return [r for r in sidecar("/__state")["requests"] if r["path"] == "/v1/predict"]


FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "laya-sidecar-mock.py")
sidecar_proc = subprocess.Popen([sys.executable, FIXTURE, str(LAYA_PORT)],
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
CREATED = []
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

    # ---------- 0. 登录 ----------
    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("accessToken") or (login or {}).get("token")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    # ---------- A. 登记 DECISION 端点 ----------
    print("\n[A] 登记决策端点：provider 默认 laya、model 可空、不吃向量语义")
    sidecar("/__reset", {})
    st, e1 = call("POST", "/model-endpoints",
                  {"kind": "DECISION", "name": "CAP55-E2E-决策边车", "baseUrl": SIDECAR,
                   "apiKey": "sk-laya-e2e-7788"})
    CREATED.append((e1 or {}).get("id"))
    check("建 DECISION 端点（不传 provider）", st == 200 and (e1 or {}).get("kind") == "DECISION",
          f"{st} {e1}")
    check("provider 省略时落 laya", (e1 or {}).get("provider") == "laya", f"{e1}")
    check("model（checkpoint 别名）可空且如实报空", (e1 or {}).get("model") in (None, ""), f"{e1}")
    check("不吃向量语义：dimensions/topK/threshold 全空",
          (e1 or {}).get("dimensions") is None and (e1 or {}).get("topK") is None
          and (e1 or {}).get("threshold") is None, f"{e1}")
    check("凭据入库但不回显（仅 hasApiKey）", (e1 or {}).get("hasApiKey") is True
          and "sk-laya-e2e-7788" not in json.dumps(e1), f"{e1}")
    E1 = (e1 or {}).get("id")
    check("从未测试时 lastTestOk 为空", (e1 or {}).get("lastTestOk") is None, f"{e1}")

    # ---------- B. 连接测试：两段实调 ----------
    print("\n[B] 连接测试 = /healthz + 固定样例 /v1/predict")
    st, t1 = call("POST", f"/model-endpoints/{E1}/test")
    msg = (t1 or {}).get("message") or ""
    check("连接测试成功", st == 200 and (t1 or {}).get("ok") is True, f"{st} {t1}")
    check("消息报常驻 checkpoint 与版本", "laya 0.3.5-mock" in msg and "常驻 multilingual" in msg, msg)
    check("消息报样例答案与概率", "sample_choice=keep（90%）" in msg, msg)
    check("消息透出 routing.reason（为什么用这个 checkpoint）", "routing：" in msg
          and "non-Latin script" in msg, msg)
    check("决策端点测试结果不写维度", (t1 or {}).get("dimensions") is None
          and ep(E1).get("dimensions") is None, f"{t1} {ep(E1)}")
    check("测试结果回写 last_test_*（列表可见）",
          ep(E1).get("lastTestOk") is True and bool(ep(E1).get("lastTestAt"))
          and "sample_choice" in (ep(E1).get("lastTestMessage") or ""), f"{ep(E1)}")

    calls = predict_calls()
    check("边车确实收到一次样例 predict", len(calls) == 1, f"{calls}")
    if calls:
        c = calls[0]
        check("样例 state 是中文提案（走中文脚本路由）",
              any("StepRunner" in json.dumps(v, ensure_ascii=False) for v in (c["state"] or {}).values())
              or any("中文" in json.dumps(v, ensure_ascii=False) for v in (c["state"] or {}).values()),
              f"{c['state']}")
        check("样例 questions 只有 sample_choice 且 type=choice",
              list(c["questions"].keys()) == ["sample_choice"]
              and c["questions"]["sample_choice"].get("type") == "choice", f"{c['questions']}")
        check("model 未配时请求体不带 model 键（由边车自己路由）",
              c["hasModelKey"] is False and c["model"] is None, f"{c}")
        check("密钥进了 Authorization 头", c["auth"] == "Bearer sk-laya-e2e-7788", f"{c['auth']}")

    # ---------- C. 边车未就绪：不白跑样例题 ----------
    print("\n[C] 边车未就绪（status≠ok）：报可读原因且不再发样例题")
    sidecar("/__health", {"status": "loading"})
    st, t2 = call("POST", f"/model-endpoints/{E1}/test")
    msg2 = (t2 or {}).get("message") or ""
    check("未就绪时 ok=false 并报 status", (t2 or {}).get("ok") is False
          and "loading" in msg2 and "未就绪" in msg2, f"{t2}")
    check("未就绪时不发样例题（省一次前向）", len(predict_calls()) == 1, f"{predict_calls()}")
    check("未就绪也回写 last_test_ok=false", ep(E1).get("lastTestOk") is False, f"{ep(E1)}")
    sidecar("/__health", {"status": "ok"})

    # ---------- D. 样例失败：透出边车 detail ----------
    print("\n[D] 样例题失败：把边车给的 detail 原样透出来")
    sidecar("/__status", {"code": 400,
                          "detail": "Unknown model 'multi-lingual'. Available: english, multilingual"})
    st, e2 = call("POST", "/model-endpoints",
                  {"kind": "DECISION", "name": "CAP55-E2E-错别名", "provider": "laya",
                   "baseUrl": SIDECAR, "model": "multi-lingual"})
    CREATED.append((e2 or {}).get("id"))
    E2 = (e2 or {}).get("id")
    check("checkpoint 别名随端点入库", (e2 or {}).get("model") == "multi-lingual", f"{e2}")
    st, t3 = call("POST", f"/model-endpoints/{E2}/test")
    msg3 = (t3 or {}).get("message") or ""
    check("错误别名 → ok=false 且报 400", (t3 or {}).get("ok") is False and "400" in msg3, f"{t3}")
    check("失败消息带边车可用别名列表（用户不用猜）",
          "Available: english, multilingual" in msg3, msg3)
    sidecar("/__status", {"code": 200})
    st, t4 = call("POST", f"/model-endpoints/{E2}/test")
    check("恢复后同一端点重测成功，且显式 checkpoint 进了请求体",
          (t4 or {}).get("ok") is True and (t4 or {}).get("model") == "multi-lingual"
          and predict_calls()[-1]["model"] == "multi-lingual", f"{t4} {predict_calls()[-1:]}")

    # ---------- E. mock provider：自报不走网络 ----------
    print("\n[E] provider=mock：假决策自报，不打网络")
    before = len(sidecar("/__state")["requests"])
    st, e3 = call("POST", "/model-endpoints",
                  {"kind": "DECISION", "name": "CAP55-E2E-假决策", "provider": "mock",
                   "model": "multilingual"})
    CREATED.append((e3 or {}).get("id"))
    E3 = (e3 or {}).get("id")
    check("mock 决策端点无需 baseUrl 即可建", st == 200 and (e3 or {}).get("baseUrl") in (None, ""),
          f"{st} {e3}")
    st, t5 = call("POST", f"/model-endpoints/{E3}/test")
    check("mock 决策端点测试成功且自报", (t5 or {}).get("ok") is True
          and "假决策" in ((t5 or {}).get("message") or ""), f"{t5}")
    check("mock 分支零网络请求", len(sidecar("/__state")["requests"]) == before, f"{st}")
    check("mock 沿用 checkpoint 别名而非 mock-embedding",
          (t5 or {}).get("model") == "multilingual", f"{t5}")

    # ---------- F. kind-provider 配对校验 ----------
    print("\n[F] kind-provider 配对校验（在建端点/预检时就拦住，不留到测试时）")
    st, r = call("POST", "/model-endpoints",
                 {"kind": "DECISION", "name": "CAP55-E2E-错配1", "provider": "openai-compatible",
                  "baseUrl": "https://api.example.com/v1", "model": "gpt-4o-mini"})
    check("DECISION + openai-compatible → 400", st == 400, f"{st} {r}")
    st, r = call("POST", "/model-endpoints",
                 {"kind": "EMBEDDING", "name": "CAP55-E2E-错配2", "provider": "laya",
                  "baseUrl": SIDECAR, "model": "bge-m3"})
    check("EMBEDDING + laya → 400", st == 400, f"{st} {r}")
    st, r = call("POST", "/model-endpoints",
                 {"kind": "DECISION", "name": "CAP55-E2E-无地址", "provider": "laya"})
    check("DECISION 缺 baseUrl → 400 且提示只填边车根地址",
          st == 400 and "baseUrl" in json.dumps(r, ensure_ascii=False), f"{st} {r}")
    st, r = call("POST", "/model-endpoints/test",
                 {"kind": "DECISION", "name": "草稿", "provider": "laya", "baseUrl": SIDECAR})
    check("草稿预检：model 可空的 DECISION 直接测通", st == 200 and (r or {}).get("ok") is True,
          f"{st} {r}")

    # ---------- G. 平台默认（同类型唯一） ----------
    print("\n[G] 平台默认决策端点：同类型唯一，不劫持向量/对话默认")
    st, d1 = call("PUT", f"/model-endpoints/{E1}/default")
    check("设为平台默认决策端点", st == 200 and (d1 or {}).get("isDefault") is True, f"{st} {d1}")
    st, d2 = call("PUT", f"/model-endpoints/{E3}/default")
    check("换一个设为默认", st == 200 and (d2 or {}).get("isDefault") is True, f"{st} {d2}")
    check("DECISION 默认唯一（前一个自动取消）", ep(E1).get("isDefault") is False, f"{ep(E1)}")

    # ---------- H. 清理 ----------
    print("\n[H] 清理：无引用端点可删")
    for ep_id in [i for i in CREATED if i]:
        st, _ = call("DELETE", f"/model-endpoints/{ep_id}")
        check(f"删除端点 {ep_id}", st == 200, f"{st}")
    check("删除后清单不含本次端点",
          all(e["id"] not in [i for i in CREATED if i] for e in endpoints()), f"{endpoints()}")

    print(f"\n== CAP-55 E2E: {passed} passed, {failed} failed ==")
    sys.exit(1 if failed else 0)
finally:
    sidecar_proc.terminate()
