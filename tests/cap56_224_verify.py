#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-56 决策实验室在 **172.20.140.224 生产环境** 的实跑验证（不是隔离实例、不是 stub）。

与另两个脚本的分工：
  - `tests/cap56_e2e.py`      —— 平台链（执行包/白名单/marker/闸门/报告口径），节点上是 stub 假模型；
  - `tests/cap56_gpu_e2e.py`  —— 真模型的**全链**（含 RLCD 微调），跑在**本机隔离实例**（:18097）；
  - 本脚本                     —— 真模型 + **224 生产环境**，只走「最小可用 + 一次真评测」这一段：
      登记基座 → serve 自检 → 放行（闸门开、分诊恢复）→ 基准集冻结 → 真评测 + 报告口径核对。
    刻意不跑微调：那一段开销大、且会就地覆盖节点上的训练产物目录，需要时另开一轮。

## 前置（脚本只做检查，不代劳；缺哪条它会指名报出来）

1. 224 已部署含 CAP-56 的包，且 `APP_HOME/lab` 有评测/微调脚本（`deploy-224.sh` 现在会铺这个目录）；
2. 224 的 `application-local.yml` 里 `devmind.decision-lab.python-path` 指向**节点上的 venv 全路径**
   （单 token 无空白；脚本默认不传 pythonPath，走配置值——这样验的就是服务端配置本身）；
3. 节点在线且能跑 lab：`agent.properties` 里有 `execAllowlist=<venv python>`（空 = 拒一切 exec）
   与 `labels`（两者都要**重启 runner** 才生效，改完不重启是白改）；
4. DECISION 端点已设为**平台默认**（serve 自检只认默认端点）并指向边车根地址，`model` 钉住槽位
   （留空 = 边车按语言路由，会撞 laya 内置 english 槽位那个坑）；
5. 边车在跑，且**该槽位此刻加载的就是基座目录**（脚本会核对；不符直接失败，不静默继续——
   否则「放行的」与「在跑的」当场就分家了，而这正是 FR-06 要拦的事）。

## 跑法

    python tests/cap56_224_verify.py                 # 全跑（可重复跑：已完成的步骤读 state 跳过）
    python tests/cap56_224_verify.py --from 4        # 从第 4 步接着跑（报告那步断了重跑最省事）
    DEVMIND_BASE=http://172.20.140.224:8088/api python tests/cap56_224_verify.py

状态与产物写 `tmp/cap56-224/`（已 gitignore）。**本脚本不做收尾清理**：放行的那份产物要留着
（224 的知识库分诊就靠它开门），评测集/报告是要看的证据。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))
import cap56_gpu_e2e as gpu  # noqa: E402  只借它的 BENCH（37 条含三类对照组的真机基准题面）
import cap56_e2e as base  # noqa: E402    只借它纯函数式的 gold 口径（单一来源）

BASE = os.environ.get("DEVMIND_BASE", "http://172.20.140.224:8088/api")
USER = os.environ.get("DEVMIND_USER", "admin")
PASSWORD = os.environ.get("DEVMIND_PASSWORD", "admin123")
TMP = ROOT / "tmp" / "cap56-224"
STATE_FILE = TMP / "state.json"

NODE_ID = int(os.environ.get("DEVMIND_LAB_NODE_ID", "3"))
LABELS = os.environ.get("DEVMIND_LAB_LABELS", "gpu,A6000")
SIDECAR = os.environ.get("DEVMIND_LAB_SIDECAR", "http://172.20.140.88:8377")
SLOT = os.environ.get("DEVMIND_LAB_SLOT", "multilingual")
NODE_HOME = os.environ.get("DEVMIND_LAB_NODE_HOME", "/home/liuchangqing")
BASE_CKPT = os.environ.get("DEVMIND_LAB_BASE_CKPT", f"{NODE_HOME}/laya/multilingual-base")
EVAL_OUT = os.environ.get("DEVMIND_LAB_EVAL_OUT", f"{NODE_HOME}/laya/eval-224")
PROJECT_LABEL = os.environ.get("DEVMIND_LAB_PROJECT", "dev-mind")
MARK = f"cap56224-{time.strftime('%m%d-%H%M')}"

TOKEN = None
ST = {}
PASSED = 0
FAILED = 0
WARNED = 0

# Windows 控制台是 GBK：编不出的符号宁可打成 "?" 也不要抛 UnicodeEncodeError（一次异常会把整轮
# 验成"没跑完"，与链路本身无关）；管道里一律 UTF-8，否则终端里中文全成乱码。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace") if not _stream.isatty() \
            else _stream.reconfigure(errors="replace")
    except Exception:
        pass


# ---------------------------------------------------------------- 断言与 HTTP

def check(name, ok, detail=""):
    global PASSED, FAILED
    if ok:
        PASSED += 1
        print(f"  [OK]   {name}" + (f" — {detail}" if detail else ""))
    else:
        FAILED += 1
        print(f"  [FAIL] {name}" + (f" — {detail}" if detail else ""))


def warn(name, ok, detail=""):
    global WARNED
    if not ok:
        WARNED += 1
    print(f"  [{'OK' if ok else 'WARN'}] {name}" + (f" — {detail}" if detail else ""))


def step(title):
    print(f"\n== {title}")


def _decode(raw):
    if not raw:
        return {}
    try:
        return json.loads(raw.decode("utf-8"))
    except Exception:
        return {"_raw": raw.decode("utf-8", "replace")}


def call(method, url, body=None, timeout=180):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", f"Bearer {TOKEN}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, _decode(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, _decode(e.read())
    except Exception as e:                      # 连不上/超时：当成"这一次没成"，不炸整轮
        return 0, {"_error": f"{type(e).__name__}: {e}"}


def api(method, path, body=None, expect=200, timeout=180):
    code, payload = call(method, f"{BASE}{path}", body, timeout)
    assert code == expect, f"{method} {path} → {code}（期望 {expect}）: {json.dumps(payload, ensure_ascii=False)[:400]}"
    return payload


def wait(probe, what, timeout=300, every=4):
    """轮询到 probe 返回真值；超时返回最后一次的值（调用方自己断言）"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = probe()
        if last:
            return last
        time.sleep(every)
    return last


def load_state():
    TMP.mkdir(parents=True, exist_ok=True)
    if STATE_FILE.exists():
        return json.loads(STATE_FILE.read_text(encoding="utf-8"))
    return {}


def save_state():
    TMP.mkdir(parents=True, exist_ok=True)
    STATE_FILE.write_text(json.dumps(ST, ensure_ascii=False, indent=1), encoding="utf-8")


def login():
    global TOKEN
    code, payload = call("POST", f"{BASE}/auth/login", {"username": USER, "password": PASSWORD})
    assert code == 200 and payload.get("accessToken"), f"登录失败: {code} {str(payload)[:200]}"
    TOKEN = payload["accessToken"]
    who = payload.get("user") or {}
    print(f"      已登录 {BASE}（{who.get('username')} / {who.get('role')}）")


# ---------------------------------------------------------------- 1. 前置

def stage1_preflight():
    step("1. 前置：闸门现状 / 节点可跑 lab / 端点与边车来源")
    gate = api("GET", "/decision/checkpoints/gate")
    ST["gate_before"] = gate
    print(f"      闸门: open={gate['open']} reason={gate.get('reason') or '（无）'}")

    code, tri = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    ST["triage_before"] = {"http": code, "body": tri}
    check("分诊可用性拿得到（闸门两侧行为的起点）", code == 200, str(tri)[:160])
    print(f"      分诊此刻: available={tri.get('available')}"
          + (f"（{tri.get('reason')}）" if tri.get("reason") else ""))

    nodes = api("GET", "/agent-nodes")
    node = next((n for n in nodes if n.get("id") == NODE_ID), None)
    check(f"节点 #{NODE_ID} 在线", bool(node) and node.get("status") == "ONLINE",
          f"status={node.get('status') if node else '节点不存在'}")
    labels = (node or {}).get("labels") or ""
    check(f"节点 #{NODE_ID} 标签覆盖本轮要求（{LABELS}）",
          all(tag.strip() in [x.strip() for x in labels.split(",")] for tag in LABELS.split(",")),
          f"节点上报 labels={labels!r}（runner 上报值会覆盖服务端编辑值）")
    check(f"节点 #{NODE_ID} 协议 v14（exec 帧带 bundle，lab 拉包靠它）",
          (node or {}).get("protocolVersion") == 14, f"protocolVersion={(node or {}).get('protocolVersion')}")

    eps = api("GET", "/model-endpoints")
    dec = next((e for e in eps if e.get("kind") == "DECISION" and e.get("isDefault")), None)
    check("存在默认决策端点（serve 自检与分诊都只认它）", bool(dec),
          f"决策端点: {[(e['id'], e.get('name'), e.get('isDefault')) for e in eps if e.get('kind') == 'DECISION']}")
    if dec:
        check(f"默认决策端点指向边车 {SIDECAR}", (dec.get("baseUrl") or "").rstrip("/") == SIDECAR.rstrip("/"),
              f"baseUrl={dec.get('baseUrl')}")
        check(f"端点 model 钉住槽位（{SLOT}）", dec.get("model") == SLOT,
              f"model={dec.get('model')!r}（留空 = 边车按语言路由，会撞内置 english 槽位）")

    code, health = call("GET", f"{SIDECAR}/healthz", timeout=15)
    check("边车 /healthz 可达", code == 200, str(health)[:200])
    if code == 200:
        loaded = health.get("loaded") or []
        src = ((health.get("sources") or {}).get(SLOT)) or {}
        check(f"边车常驻槽位 {SLOT}", SLOT in loaded, f"loaded={loaded}")
        check("边车设备是 cpu（与 vLLM 共卡时 cuda 会随机 500/超时）",
              (health.get("devices") or {}).get(SLOT) == "cpu",
              f"devices={health.get('devices')}")
        check(f"该槽位此刻加载的就是基座目录（{BASE_CKPT}）",
              (src.get("path") or src.get("source")) == BASE_CKPT and src.get("ready") is True,
              f"sources[{SLOT}]={src}")
    save_state()


# ---------------------------------------------------------------- 2. 登记 + 自检 + 放行

def stage2_register_verify():
    step("2. 登记基座 → serve 自检 → 放行（闸门开、分诊恢复）")
    if ST.get("ckpt_id"):
        print(f"      已登记过产物 #{ST['ckpt_id']}，跳过登记")
    else:
        ckpt = api("POST", "/decision/checkpoints", {
            "name": f"{MARK}-base-{SLOT}", "serveSlot": SLOT, "kind": "BASE",
            "sourcePath": BASE_CKPT, "nodeId": NODE_ID,
            "note": f"节点 {NODE_ID} 上的官方基座（{BASE_CKPT}），本轮 224 验证的放行对象"})
        ST["ckpt_id"] = ckpt["id"]
        save_state()
        print(f"      产物 #{ckpt['id']} 已登记（槽位 {SLOT} / {BASE_CKPT}）")

    result = api("POST", f"/decision/checkpoints/{ST['ckpt_id']}/serve-check")
    for c in result.get("checks") or []:
        print(f"      · [{c['status']}] {c['item']}: {c['detail']}")
    worst = "OK"
    for c in result.get("checks") or []:
        if c["status"] == "FAIL":
            worst = "FAIL"
        elif c["status"] == "WARN" and worst == "OK":
            worst = "WARN"
    check("serve 自检无 FAIL（端点/可达/槽位常驻/来源一致/设备）", worst != "FAIL", f"总体={worst}")
    warn("serve 自检全 OK（WARN 也要看：缺的检查不许显示成验过了）", worst == "OK", f"总体={worst}")

    ckpt = api("POST", f"/decision/checkpoints/{ST['ckpt_id']}/verify", {
        "note": f"CAP-56 224 验证：基座自检通过（槽位 {SLOT} 常驻、来源与登记一致），"
                f"随后以 {MARK} 基准集真机评测为准"})
    check("放行成功（verified=true）", ckpt.get("verified") is True, f"verified={ckpt.get('verified')}")

    gate = api("GET", "/decision/checkpoints/gate")
    check("闸门已开（open=true，reason 为空）", gate["open"] is True and not gate.get("reason"),
          f"{json.dumps(gate, ensure_ascii=False)[:200]}")
    code, tri = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("知识库分诊恢复可用（224 上那道「没有已验证产物 → 整体不可用」的闸门解开了）",
          code == 200 and tri.get("available") is True, f"{code} {str(tri)[:200]}")
    ST["gate_after"] = gate
    save_state()


# ---------------------------------------------------------------- 3. 基准集

def stage3_dataset():
    step(f"3. 人工基准集（{len(gpu.BENCH)} 条，含三类对照组）→ 冻结")
    if ST.get("bench_id"):
        print(f"      已建过基准集 #{ST['bench_id']}，跳过")
        return
    tpls = {t["caseGroup"]: t for t in api("GET", "/decision/datasets/templates")}
    check("三类对照组模板齐全", set(gpu.CONTROL) <= set(tpls), f"拿到 {list(tpls)}")
    questions = tpls["VERBATIM_DUP"]["questions"]      # 三份模板共用同一份标准题面
    ds = api("POST", "/decision/datasets", {
        "name": f"{MARK}-224基准集", "kind": "BENCHMARK",
        "note": "224 生产验证：三类对照组 + 普通样本，gold 逐条人工标注"})
    ds_id = ds["dataset"]["id"]
    ST["bench_id"] = ds_id
    save_state()
    for content, similar, layer, quality, dup, group in gpu.BENCH:
        gold = dict(base.gold_for(tpls[group if group != "NORMAL" else "VERBATIM_DUP"], layer, quality))
        for qid, q in questions.items():               # noul 题按人工判断给 gold（对照组覆盖构造值）
            if q.get("type") == "noul":
                gold[qid] = dup
        code, payload = call("POST", f"{BASE}/decision/datasets/{ds_id}/items", {
            "state": {"proposal_title": content[:20], "proposal_content": content,
                      "project": PROJECT_LABEL, "similar_entries": similar},
            "questions": questions, "gold": gold, "caseGroup": group, "note": "224 生产验证基准集"})
        assert code == 200, f"造样本失败（{group}: {content[:16]}）: {code} {str(payload)[:300]}"
    frozen = api("POST", f"/decision/datasets/{ds_id}/freeze")
    counts = {g: n for g, n in (frozen.get("caseGroupCounts") or {}).items() if n}
    check(f"基准集冻结通过（{len(gpu.BENCH)} 条，对照组齐且名副其实——冻结校验自己会拦）",
          frozen["dataset"]["frozen"] is True and set(gpu.CONTROL) <= set(counts),
          json.dumps(counts, ensure_ascii=False))
    print(f"      基准集 #{ds_id}：{json.dumps(counts, ensure_ascii=False)}")
    save_state()


# ---------------------------------------------------------------- 4. 真评测

def eval_detail(eid):
    return api("GET", f"/decision/evaluations/{eid}")


def stage4_eval():
    step("4. 真评测（节点上真 laya 批量推理）")
    if ST.get("eval_id"):
        done = eval_detail(ST["eval_id"])
        if done["view"]["status"] in ("SUCCESS", "FAILED"):
            report_summary(done)
            return
    ev = api("POST", "/decision/evaluations", {
        "checkpointId": ST["ckpt_id"], "datasetId": ST["bench_id"],
        "nodeId": NODE_ID, "requiredLabels": LABELS,
        "fitTemperature": True, "outputPath": EVAL_OUT, "timeoutSec": 3600})
    ST["eval_id"] = ev["id"]
    save_state()
    print(f"      评测 #{ev['id']} 已下发（pythonPath 走服务端配置；command={ev.get('commandText') or '（页面可见）'}）")

    def settled():
        d = eval_detail(ST["eval_id"])
        return d if d["view"]["status"] in ("SUCCESS", "FAILED") else None

    done = wait(settled, f"评测 #{ST['eval_id']}", timeout=3600, every=5)
    assert done, f"评测 #{ST['eval_id']} 超时未结束（状态 {eval_detail(ST['eval_id'])['view']['status']}）"
    report_summary(done)


def report_summary(done):
    v = done["view"]
    report = done.get("report") or {}
    check("评测跑通（SUCCESS）", v["status"] == "SUCCESS", f"status={v['status']} label={v.get('reportLabel')}")
    check("报告口径齐备（REPORT_OK）", v.get("reportStatus") == "OK", f"reportStatus={v.get('reportStatus')}")
    if v["status"] != "SUCCESS":
        logs = call("GET", f"{BASE}/decision/evaluations/{ST['eval_id']}/logs")[1]
        print(f"      失败，日志尾部：{json.dumps(logs, ensure_ascii=False)[-1500:]}")
        return

    head = v.get("headline") or {}
    print(f"      头条：accuracy={head.get('accuracy')} random={head.get('random')} majority={head.get('majority')} "
          f"win/lose/tie={head.get('win')}/{head.get('lose')}/{head.get('tie')} "
          f"ece {head.get('eceBefore')}→{head.get('eceAfter')}")
    check("报告带两条基线（没有基线，「准确率 0.72」读不出好坏）",
          head.get("random") is not None and head.get("majority") is not None, str(head))
    check("准确率有值（不是「—」）", head.get("accuracy") is not None, str(head))

    metrics = report.get("metrics") or {}
    for name in ("choice", "score", "noul"):
        blk = metrics.get(name)
        if blk:
            print(f"      {name}: " + json.dumps(blk, ensure_ascii=False)[:220])
    lat = metrics.get("latencyMs") or {}
    print(f"      延迟：p50={lat.get('p50')}ms p95={lat.get('p95')}ms，题目 {metrics.get('questions')} 道")

    by = {g["caseGroup"]: g for g in (done.get("byCaseGroup") or [])}
    print("      分组：")
    for group, m in by.items():
        print(f"        {group:14s} items={m.get('items')} questions={m.get('questions')} accuracy={m.get('accuracy')}")
    check("对照组三组都在报告里（退化要靠对照组现形）", set(gpu.CONTROL) <= set(by), f"拿到 {list(by)}")

    per_item = done.get("perItem") or []
    for group in gpu.CONTROL:
        rows = [r for r in per_item if r.get("caseGroup") == group]
        if not rows:
            continue
        preds = {json.dumps(r.get("pred"), ensure_ascii=False, sort_keys=True) for r in rows}
        noul = sorted({json.dumps(r.get("pred"), ensure_ascii=False) for r in rows if r.get("qtype") == "noul"})
        acc = [r for r in rows if r.get("correct") is True]
        print(f"      {group}: {len(acc)}/{len(rows)} 判对；不同答案 {len(preds)} 种")
        if len(preds) == 1 and len(rows) >= 4:
            print(f"        ↑ 该组恒答同一答案：{'（退化形态，正是 CAP-56 的立身教训）' if group != 'VERBATIM_DUP' else '（该组本就该恒答，正常）'}")

    if done.get("compare"):
        print(f"      与基线逐题：{json.dumps(done['compare'], ensure_ascii=False)[:200]}")
    cal = done.get("calibration")
    if cal:
        print(f"      温度校准：{json.dumps(cal, ensure_ascii=False)[:220]}")
    (TMP / f"report-{ST['eval_id']}.json").write_text(
        json.dumps(done, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"      报告原文：{TMP / f'report-{ST["eval_id"]}.json'}")
    save_state()


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="start", type=int, default=1, help="从第几步开始（1..4）")
    args = ap.parse_args()

    global ST
    ST = load_state()
    login()
    print(f"      node=#{NODE_ID} labels={LABELS} slot={SLOT} sidecar={SIDECAR}")

    # 失败即停：前置没满足还往下走，只会拿一串级联失败盖住真正的那条
    for n, fn in ((1, stage1_preflight), (2, stage2_register_verify),
                  (3, stage3_dataset), (4, stage4_eval)):
        if n < args.start:
            continue
        before = FAILED
        fn()
        if FAILED > before:
            print(f"\n!! 第 {n} 步有 {FAILED - before} 条失败，后续步骤不再继续（先照上面修前置）")
            break

    print(f"\n==== 结论：{PASSED} 通过 / {FAILED} 失败 / {WARNED} 警告 ====")
    print(f"     闸门：{ST.get('gate_before', {}).get('open')} → {ST.get('gate_after', {}).get('open')}"
          f"　分诊：{(ST.get('triage_before', {}).get('body') or {}).get('available')}"
          f" → 已恢复" if ST.get("gate_after", {}).get("open") else "     闸门未开")
    sys.exit(1 if FAILED else 0)


if __name__ == "__main__":
    main()
