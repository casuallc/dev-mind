# -*- coding: utf-8 -*-
"""CAP-55 FR-05 决策记录与训练集导出 E2E（laya-sidecar-mock，无需真边车/模型）。

运行前提（与 cap55_triage_verify.py 同款）：
1. 依赖已构建：`mvn -q install -DskipTests`。
2. app 独立实例已起（干净 H2 即可）：
     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18095 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:./tmp/cap55-records-e2e/devmind;AUTO_SERVER=TRUE"
   注意：spring-boot:run 的工作目录是**模块 basedir**，所以这份相对路径的 H2 实际落在
   `devmind-app/tmp/cap55-records-e2e/`——想清库重跑要删的是那个目录（删仓库根的 tmp/ 没用，
   脚本会读到上一次的残留行）。
3. python（tests/fixtures/laya-sidecar-mock.py 由脚本自起，端口 LAYA_MOCK_PORT 默认 18195）。

覆盖 FR-05 全链：
  A 分诊留痕——自动分诊后 decision_records 就有行：state/questions/model_answer/routing/延迟 逐字落库；
  B 人工裁决配对——adopt 后同一行补上 human_action/gold/decided_by/decided_at，agreement 逐题算；
  C 训练集导出——JSONL 三字段齐全、gold 是按题面 criteria 摊的分布（键序一致）、
    文件名/内容类型/附件头；reject 那行（gold 为空）不进导出集；
  D 重新分诊不抹裁决——再分诊一次，人的那次决定仍在（决定已经发生过了）；
  E 查询——capability/since 过滤、分页与 size 边界（1-200 之外 400）、详情带 state/questions、未知 id 404；
  F 降级样本也留痕——端点没了以后照样落行（degraded=true），这是可用性评估的第一手数据。
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
CAPABILITY = "kb-proposal-triage"
TOKEN = None
passed = 0
failed = 0

PROPOSAL_TITLE = "构建失败先看日志末尾"
ENTRY_CONTENT = "环境类报错九成在日志末尾 200 行，构建失败先看日志末尾再猜网络"


def call(method, path, body=None, raw=False):
    url = quote(BASE + path, safe=":/?&=%,.-")
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = r.read()
            if raw:
                return r.status, body, dict(r.headers)
            return r.status, (json.loads(body) if body else None)
    except urllib.error.HTTPError as e:
        raw_body = e.read()
        try:
            return e.code, json.loads(raw_body)
        except Exception:
            return e.code, raw_body.decode("utf-8", "replace")


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


def proposals():
    st, ps = call("GET", "/knowledge/proposals")
    return ps if st == 200 else []


def proposal(pid):
    return next((p for p in proposals() if p["id"] == pid), None)


def create_proposal(title, content, target_scope="project", project_id=None):
    st, p = call("POST", "/knowledge/proposals",
                 {"title": title, "contentMd": content, "targetScope": target_scope,
                  "targetProjectId": project_id, "sourceSessionId": "cap55-records-e2e"})
    assert st == 200, f"建提案失败 {st} {p}"
    return p


def wait_triage(pid, timeout=25.0):
    deadline = time.time() + timeout
    while time.time() < deadline:
        p = proposal(pid)
        if p and (p.get("triage") or {}).get("at"):
            return p
        time.sleep(0.3)
    return proposal(pid)


def records(**params):
    qs = "&".join(f"{k}={quote(str(v))}" for k, v in params.items())
    st, view = call("GET", f"/decision/records?{qs}" if qs else "/decision/records")
    assert st == 200, f"查记录失败 {st} {view}"
    return view


def record_of(pid, timeout=15.0):
    """按 subjectId 找回某提案那一行（导出/列表都按 id 倒序，此处直接筛）"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        items = records(size=200)["items"]
        hit = next((r for r in items if r["refId"] == str(pid)), None)
        if hit:
            return hit
        time.sleep(0.3)
    return None


def export(capability=CAPABILITY, since=None):
    path = f"/decision/records/export?capability={quote(capability)}"
    if since:
        path += f"&since={quote(since)}"
    st, body, headers = call("GET", path, raw=True)
    assert st == 200, f"导出失败 {st} {body}"
    return body.decode("utf-8"), headers


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

    # ---------- 0. 登录 + 端点 + 知识库 ----------
    st, login = call("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = (login or {}).get("accessToken") or (login or {}).get("token")
    check("登录 admin", st == 200 and bool(TOKEN), f"{st} {login}")
    if not TOKEN:
        sys.exit(1)

    print("\n[0] 前置：登记决策端点 + 建全局经验库与条目（重复判定要有比对物）")
    st, ep = call("POST", "/model-endpoints",
                  {"kind": "DECISION", "name": "CAP55-RECORDS-E2E-决策边车", "baseUrl": SIDECAR})
    ENDPOINT_ID = (ep or {}).get("id")
    check("建 DECISION 端点", st == 200 and bool(ENDPOINT_ID), f"{st} {ep}")
    st, _ = call("PUT", f"/model-endpoints/{ENDPOINT_ID}/default")
    check("设为平台默认", st == 200, f"{st}")

    st, kb = call("POST", "/knowledge/bases",
                  {"name": "CAP55-RECORDS-E2E 经验库", "scope": "global", "injectMode": "FULL"})
    KB_ID = (kb or {}).get("id")
    st, entry = call("POST", "/knowledge/entries",
                     {"kbId": KB_ID, "name": "构建排错心得", "contentMd": ENTRY_CONTENT,
                      "status": "active"})
    ENTRY_ID = (entry or {}).get("id")
    check("建库与条目", st == 200 and bool(ENTRY_ID), f"{st} {entry}")

    check("干净库：一开始没有该能力的记录",
          records(capability=CAPABILITY)["total"] == 0, f"{records(capability=CAPABILITY)['total']}")

    # ---------- A. 分诊落痕 ----------
    print("\n[A] 自动分诊 → decision_records 长出「模型建议」那半边")
    sidecar("/__reset", {})
    p1 = create_proposal(PROPOSAL_TITLE, "环境类报错九成在日志末尾 200 行，先看再猜网络。",
                         target_scope="global")
    p1 = wait_triage(p1["id"])
    check("提案已分诊（前置条件）", bool(((p1 or {}).get("triage") or {}).get("at")), f"{p1}")

    row = record_of(p1["id"])
    check("分诊后落行（无需人工动作）", row is not None, f"pid={p1['id']}")
    if not row:
        print("\n== CAP-55 FR-05 E2E 提前终止：记录没落 ==")
        sys.exit(1)
    check("行归属该能力与提案 id",
          row["capability"] == CAPABILITY and row["refId"] == str(p1["id"]), f"{row}")
    check("未降级且带模型耗时", row["degraded"] is False and row["latencyMs"] >= 0, f"{row}")
    check("落库了边车实际选中的模型与原因（routing 拆成两字段）",
          row["model"] == "multilingual" and "non-Latin script" in row["routingReason"], f"{row}")
    check("模型答案逐字留存（三题齐全）",
          set(row["answers"].keys()) == {"adopt_layer", "duplicate", "quality"}, f"{row['answers']}")
    check("此刻还没有人工裁决（gold/decidedBy 为空、不可训练）",
          row["gold"] == {} and not row["decidedBy"] and row["trainable"] is False, f"{row}")

    st, detail = call("GET", f"/decision/records/{row['id']}")
    check("详情带 state 快照（当初模型看到的输入）",
          st == 200 and detail["state"].get("proposal_title") == PROPOSAL_TITLE, f"{st} {detail}")
    check("详情带题面（含 criteria——gold 的取值域来自它）",
          st == 200 and set(detail["questions"].keys()) == {"adopt_layer", "duplicate", "quality"}
          and list(detail["questions"]["adopt_layer"]["criteria"]) == ["global", "project", "discard"],
          f"{detail.get('questions')}")

    st, r = call("GET", "/decision/records/999999")
    check("未知 id 详情 → 404", st == 404, f"{st} {r}")

    # ---------- B. 人工裁决配对 ----------
    print("\n[B] 人工 adopt → 同一行补上「人工裁决」那半边，逐题算 agreement")
    st, adopted = call("POST", f"/knowledge/proposals/{p1['id']}/adopt?target=global")
    check("采纳到全局层", st == 200 and (adopted or {}).get("status") == "adopted"
          and (adopted or {}).get("adoptedTo") == "global", f"{st} {adopted}")

    row = record_of(p1["id"])
    check("裁决落到同一行（不是新开一行）",
          row is not None and row["humanAction"] == "adopt:global", f"{row}")
    check("human_action = adopt:global（动作与 gold 分开记）",
          row["humanAction"] == "adopt:global", f"{row}")
    check("gold 与题面选项同域（adopt_layer=global）",
          row["gold"] == {"adopt_layer": "global"}, f"{row['gold']}")
    check("留了裁决人（decided_by）", row["decidedBy"] == "admin", f"{row}")
    check("带裁决时间", bool(row["decidedAt"]), f"{row}")
    check("agreement 只算两边都答的题：adopt_layer 两边都说了 global → true",
          row["agreement"] == {"adopt_layer": True}, f"{row['agreement']}")
    check("三份快照齐全 → 可训练", row["trainable"] is True, f"{row}")

    # ---------- C. 训练集导出 ----------
    print("\n[C] 导出 laya 训练 JSONL：三字段 / gold 分布 / 附件头 / 跳过不可训练的行")
    st, body, headers = call("GET", f"/decision/records/export?capability={quote(CAPABILITY)}", raw=True)
    text = body.decode("utf-8")
    check("内容类型是 JSONL 的标准名 x-ndjson",
          headers.get("Content-Type") == "application/x-ndjson", f"{headers.get('Content-Type')}")
    check("当文件下载（attachment + 带能力与时间戳的 .jsonl 名）",
          "attachment" in (headers.get("Content-Disposition") or "")
          and CAPABILITY in (headers.get("Content-Disposition") or "")
          and (headers.get("Content-Disposition") or "").rstrip('"').endswith(".jsonl"),
          f"{headers.get('Content-Disposition')}")

    lines = [json.loads(x) for x in text.splitlines() if x.strip()]
    mine = [x for x in lines if x["state"].get("proposal_title") == PROPOSAL_TITLE]
    check("导出含该提案这行", len(mine) == 1, f"{len(lines)} 行：{[x['state'] for x in lines]}")
    if mine:
        line = mine[0]
        check("行里只有 state/questions/gold 三字段（不多塞元信息）",
              list(line.keys()) == ["state", "questions", "gold"], f"{list(line.keys())}")
        check("questions 与当初发给模型的一致",
              list(line["questions"].keys()) == ["adopt_layer", "duplicate", "quality"],
              f"{list(line['questions'].keys())}")
        check("gold 是按题面 criteria 摊开的分布（键序与题面一致）",
              line["gold"] == {"adopt_layer": {"global": 1.0, "project": 0.0, "discard": 0.0}},
              f"{line['gold']}")

    # 不可训练的行不进导出集：拒绝提案（gold 为空）与只裁决没分诊的提案
    print("\n[C2] reject 那次不产 gold → 该行不进导出集（拒绝 ≠ 不值得沉淀，但也不编标签）")
    p_rej = create_proposal("待拒绝的提案", "这条会被拒掉，但不该被当成负样本喂给模型。")
    p_rej = wait_triage(p_rej["id"])
    n_before = len([x for x in export()[0].splitlines() if x.strip()])
    st, rejected = call("POST", f"/knowledge/proposals/{p_rej['id']}/reject")
    check("拒绝成功", st == 200 and (rejected or {}).get("status") == "rejected", f"{st} {rejected}")

    rej_row = record_of(p_rej["id"])
    check("拒绝也留痕（human_action=reject）", rej_row and rej_row["humanAction"] == "reject", f"{rej_row}")
    check("拒绝不编 gold（gold 为空、不可训练）",
          rej_row["gold"] == {} and rej_row["trainable"] is False, f"{rej_row}")
    check("agreement 为空（没有可比的题）", rej_row["agreement"] == {}, f"{rej_row['agreement']}")
    after_text = export()[0]
    check("导出集条数不变（这条被跳过）",
          len([x for x in after_text.splitlines() if x.strip()]) == n_before,
          f"{n_before} → {len([x for x in after_text.splitlines() if x.strip()])}")
    check("导出正文里没有那条被拒的提案",
          "待拒绝的提案" not in after_text, "跳过失败")

    # ---------- D. 重新分诊不抹裁决 ----------
    print("\n[D] 重新分诊：建议被覆盖，人工那次决定不被抹掉（决定已经发生过了）")
    sidecar("/__answers", {"answers": {
        "adopt_layer": {"type": "choice", "choice": "discard",
                        "probabilities": {"global": 0.1, "project": 0.2, "discard": 0.7},
                        "confidence": 0.7},
        "duplicate": {"type": "noul", "noul": 0.71, "confidence": 0.75, "probabilities": {}},
        "quality": {"type": "score", "score": 0.0,
                    "probabilities": {"0": 0.6, "1": 0.3, "2": 0.1}, "confidence": 0.6},
    }})
    st, _ = call("POST", f"/knowledge/proposals/{p1['id']}/triage")
    check("重新分诊 202", st == 202, f"{st}")
    time.sleep(2.0)
    row = record_of(p1["id"])
    check("模型答案已更新（discard）", (row["answers"].get("adopt_layer") or {}).get("choice") == "discard",
          f"{row['answers']}")
    check("人工裁决仍在：gold/human_action/decided_by 原样",
          row["gold"] == {"adopt_layer": "global"} and row["humanAction"] == "adopt:global"
          and row["decidedBy"] == "admin", f"{row}")
    check("agreement 跟着新建议重算（模型改口 discard ≠ 人的 global）",
          row["agreement"] == {"adopt_layer": False}, f"{row['agreement']}")
    check("仍是同一行（行数没涨：upsert 不是插新行）",
          records(capability=CAPABILITY, size=200)["total"] == 2,
          f"{records(capability=CAPABILITY, size=200)['total']}")
    check("导出仍然出这一行且 gold 没变",
          {"adopt_layer": {"global": 1.0, "project": 0.0, "discard": 0.0}} in
          [x["gold"] for x in
           (json.loads(t) for t in export()[0].splitlines() if t.strip())],
          f"{export()[0]}")
    sidecar("/__answers", {"answers": None})

    # ---------- E. 查询 ----------
    print("\n[E] 查询：capability/since 过滤、分页与 size 边界")
    check("按能力筛：命中本能力", records(capability=CAPABILITY)["total"] == 2, f"{records()['total']}")
    check("未知能力筛：空集（不是报错）",
          records(capability="不存在的能力")["total"] == 0, f"{records(capability='x')}")
    check("不筛能力：至少包含本能力这 2 行",
          records()["total"] >= 2, f"{records()['total']}")

    today = time.strftime("%Y-%m-%d")
    check("since=今天：包含刚产生的行", records(capability=CAPABILITY, since=today)["total"] == 2,
          f"{records(capability=CAPABILITY, since=today)['total']}")
    check("since=明天：未来日期筛不出东西（含当天语义下 0 行）",
          records(capability=CAPABILITY, since="2099-01-01")["total"] == 0,
          f"{records(capability=CAPABILITY, since='2099-01-01')['total']}")

    page = records(capability=CAPABILITY, page=0, size=1)
    check("分页：size=1 回 1 条但 total 仍是 2（total 不受分页影响）",
          len(page["items"]) == 1 and page["total"] == 2 and page["size"] == 1
          and page["page"] == 0, f"{page}")
    check("列表按 id 倒序（新的在前）",
          [r["id"] for r in records(capability=CAPABILITY)["items"]]
          == sorted([r["id"] for r in records(capability=CAPABILITY)["items"]], reverse=True),
          f"{[r['id'] for r in records(capability=CAPABILITY)['items']]}")
    st, r = call("GET", "/decision/records?size=500")
    check("size>200 → 400（不静默截断）", st == 400, f"{st} {r}")
    st, r = call("GET", "/decision/records?size=0")
    check("size<1 → 400", st == 400, f"{st} {r}")

    # ---------- F. 降级样本 ----------
    print("\n[F] 降级：端点没了以后照样留痕（可用性评估的第一手数据）")
    st, _ = call("DELETE", f"/model-endpoints/{ENDPOINT_ID}")
    check("删掉 DECISION 端点", st == 200, f"{st}")
    p_d = create_proposal("降级期提案", "端点没了，这条记录要能说明「模型当时给不出建议」。")
    p_d = wait_triage(p_d["id"])
    d_row = record_of(p_d["id"])
    check("降级也落行", d_row is not None, f"pid={p_d['id']}")
    check("标了降级与原因", d_row and d_row["degraded"] is True
          and "模型接入" in d_row["degradedReason"], f"{d_row}")
    check("降级行不可训练（没有模型答案可比）", d_row["trainable"] is False, f"{d_row}")
    check("降级行不进导出集", "降级期提案" not in export()[0], "被当成样本喂出去了")

    # ---------- G. 清理 ----------
    print("\n[G] 清理：非空库拒删（409），清空条目后库可删")
    if KB_ID:
        st, entries = call("GET", f"/knowledge/bases/{KB_ID}/entries")
        check("库里还有条目（对应下面的 409）", st == 200 and len(entries or []) >= 1, f"{st} {entries}")
        st, r = call("DELETE", f"/knowledge/bases/{KB_ID}")
        check("非空库拒删 409（不静默连条目一起删）", st == 409, f"{st} {r}")
        for e in (entries or []):
            call("DELETE", f"/knowledge/entries/{e['id']}")
        st, _ = call("DELETE", f"/knowledge/bases/{KB_ID}")
        check("条目清空后库可删", st == 200, f"{st}")

    print(f"\n== CAP-55 FR-05 E2E: {passed} passed, {failed} failed ==")
    sys.exit(1 if failed else 0)
finally:
    sidecar_proc.terminate()
