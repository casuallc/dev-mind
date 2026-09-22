#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""CAP-56 决策实验室全链 E2E：建集 → 冻结 → 评测 → 微调 → 回评 → 验放 → serve 自检 → 分诊闸门。

## 前置（本脚本自己起 mock 边车与真 runner，但**不起 app**）

1. 依赖已构建（含 runner jar）：`mvn -q install -DskipTests`
2. app 隔离实例已起，**库全新**、**脚本目录指向本脚本自写的 stub 目录**：

     mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18096 \
       --spring.profiles.active=e2e \
       --spring.datasource.url=jdbc:h2:file:D:/apusic/dev-mind/tmp/cap56-e2e/data/devmind;AUTO_SERVER=TRUE \
       --devmind.decision-lab.scripts-dir=D:/apusic/dev-mind/tmp/cap56-e2e/scripts"

   - `--spring.profiles.active=e2e` 必需：默认 local profile 会去抢 MySQL 驱动（见「开发注意事项」）；
   - **库必须全新**：脚本启动时自查，非空直接拒跑。重跑前停 app、`rm -rf tmp/cap56-e2e/data`、按上面重启；
   - `scripts-dir` 必须指向 stub 目录（脚本启动时写进去）：不指就会拿 `tools/laya-sidecar/lab`
     的真脚本去节点上跑（需要 torch/laya），失败信息里会带 `No module named 'torch'`。
     用绝对路径是因为 `spring-boot:run` 的工作目录不一定是仓库根（平台默认值是相对路径）。
3. 从 **Git Bash** 跑（runner 的 execShell 默认 bash，命令是以 bash 执行临时 .sh）：`python tests/cap56_e2e.py`
   缺省连 `http://localhost:18096/api`，可用 `DEVMIND_BASE` 覆盖；`LAYA_MOCK_PORT` 默认 18196。

## 这一遍验的是什么（以及**不**验什么）

验**平台这条链**：冻结红线、执行包下发/物化/白名单/env 注入、marker 抓取与日志剔除、
基线/对照组/校准在报告里的显式暴露、微调收尾的指纹登记与自动回评、闸门两侧行为、
serve 自检的四种来源形态。

**不验模型质量**：节点上跑的是本脚本写进 `tmp/cap56-e2e/scripts/` 的 stub 脚本（只用标准库），
它复刻 2026-09-22 真机实测到的退化形态——基座「恒答 project / 全档 2 / 判重复」，微调后按 gold 答。
所以「回评优于基座」在这里证的是**平台把两份数字算清并摆到了一起**，不是「RLCD 收敛了」；
后者只能在 172.20.140.88 真机验（stub 的每个数字怎么来的，见文件末尾的 stub 注释）。

**不验 WS 实时帧**（逐题 frame 不进库，需要 WS 客户端；由后端单测与前端 E2E 覆盖）。
"""
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = os.environ.get("DEVMIND_BASE", "http://localhost:18096/api")
ROOT = Path(__file__).resolve().parent.parent
RUNNER_JAR = ROOT / "devmind-agent-runner/target/devmind-agent-runner.jar"
TMP = ROOT / "tmp" / "cap56-e2e"
SCRIPTS = TMP / "scripts"
WS_DIR = TMP / "ws"
LAYA_PORT = int(os.environ.get("LAYA_MOCK_PORT", "18196"))
SIDECAR = f"http://127.0.0.1:{LAYA_PORT}"
MARK = f"cap56e2e-{int(time.time()) % 1000000}"
SLOT = "multilingual"        # mock 边车 /healthz 的常驻槽位；「槽位常驻」检查靠它才过得去
OTHER_SLOT = "english"       # 未常驻的槽位：用来验「槽位常驻」FAIL 那一支
BASE_CKPT = TMP / "base-ckpt"   # 基座权重目录（节点本机上的那份）
FT_OUT = TMP / "ft-out"         # 微调产出目录（stub 把权重写进去，服务端只收回指纹）

TOKEN = None
CTX = {}          # node_id / python / base_accuracy（main 里填，各段只读）
PASSED = 0
FAILED = 0

# Windows 控制台是 GBK：遇到编不出的符号（⇔、✓ 之类）宁可打成 "?" 也不要抛 UnicodeEncodeError
# ——一次编码异常会把整轮 E2E 变成"没跑完"，而这与链路本身毫无关系。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(errors="replace")
    except Exception:
        pass


# ---------------------------------------------------------------- 断言与 HTTP

def check(name, ok, detail=""):
    global PASSED, FAILED
    if ok:
        PASSED += 1
        print(f"  PASS  {name}")
    else:
        FAILED += 1
        print(f"  FAIL  {name}" + (f"  → {detail}" if detail else ""))


def step(title):
    print(f"\n{title}")


def _decode(raw):
    text = raw.decode("utf-8", "replace") if raw else ""
    if not text:
        return None
    try:
        return json.loads(text)
    except Exception:
        return text


def call(method, url, body=None, timeout=180):
    """返回 (状态码, body)；HTTP 错误也返回状态码而不抛——期望失败的分支靠它判。"""
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if TOKEN:
        req.add_header("Authorization", "Bearer " + TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, _decode(r.read())
    except urllib.error.HTTPError as e:
        return e.code, _decode(e.read())


def api(method, path, body=None, expect=200):
    """断言状态码的 call（用于本应成功的路径）"""
    st, payload = call(method, BASE + path, body)
    assert st == expect, f"{method} {path} → {st}（期望 {expect}）: {str(payload)[:400]}"
    return payload


def page(path, **params):
    q = "&".join(f"{k}={v}" for k, v in params.items())
    return api("GET", path + ("?" + q if q else ""))


def mock(path, body=None):
    """打 mock 边车的控制面（/__sources、/__reset…）"""
    st, payload = call("POST", SIDECAR + path, body)
    assert st == 200, f"mock 边车 {path} → {st} {payload}"
    return payload


def sources_for(slot, path, ready=True):
    """照真边车 /healthz.sources 的形状造一条槽位来源（字段见 LayaDecisionClient.SlotSource）"""
    return {"sources": {slot: {"source": path, "kind": "local", "path": path, "local": True,
                               "ready": ready, "overridden": True, "loaded": True,
                               "device": "cpu", "repo": None, "subfolder": None}}}


def norm(path):
    """路径归一：serve 自检比对时用的口径（斜杠方向、大小写、尾斜杠都不该影响判断）"""
    return (path or "").replace("\\", "/").strip().rstrip("/").lower()


def wait(probe, what, timeout=300):
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = probe()
        if v:
            return v
        time.sleep(1)
    raise AssertionError(f"超时等待: {what}（{timeout}s）")


def settled(path, what):
    """等一条评测/微调离开 QUEUED/RUNNING"""
    done = wait(lambda: (lambda d: d if d["view"]["status"] in ("SUCCESS", "FAILED") else None)(
        api("GET", path)), what)
    if done["view"]["status"] == "FAILED":
        print(f"      {what} 失败: {done['view'].get('errorSummary')}")
    return done


# ---------------------------------------------------------------- 起 mock 边车与 runner

def write_stubs():
    """把 stub 评测/微调脚本写进脚本目录（app 的 scripts-dir 必须指向这里）。

    「模型行为」由 checkpoint 路径决定：含 ft-out 的（微调产物）按 gold 答题，其余（基座）
    恒答同一个答案——真机上那个退化形态。这样 stub 一句话就能描述，而链路上的一切都是真的。
    """
    SCRIPTS.mkdir(parents=True, exist_ok=True)
    for name, text in (("_stub_common.py", STUB_COMMON), ("laya_eval.py", STUB_EVAL),
                       ("laya_train.py", STUB_TRAIN)):
        (SCRIPTS / name).write_text(text, encoding="utf-8")
    print(f"[0] stub 脚本已写入 {SCRIPTS}")


def start_sidecar():
    fixture = ROOT / "tests" / "fixtures" / "laya-sidecar-mock.py"
    proc = subprocess.Popen([sys.executable, str(fixture), str(LAYA_PORT)],
                            stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    for _ in range(60):
        if call("GET", SIDECAR + "/__state")[0] == 200:
            print(f"[0] mock 边车已起 {SIDECAR}")
            return proc
        time.sleep(0.2)
    kill(proc)
    raise AssertionError(f"mock 边车起不来（端口 {LAYA_PORT} 被占？）")


def start_runner(node_token):
    """真 runner（本仓 jar）：exec 帧 + 白名单 + 拉包物化 + 日志/标记回流走的是真实路径。"""
    assert RUNNER_JAR.exists(), f"缺 {RUNNER_JAR}：先 mvn -q install -DskipTests"
    python = CTX["python"]
    WS_DIR.mkdir(parents=True, exist_ok=True)
    ws_url = BASE[:-4] if BASE.endswith("/api") else BASE
    ws_url = ws_url.replace("http://", "ws://").replace("https://", "wss://") + "/ws/agent"
    props = TMP / "runner.properties"
    props.write_text(
        f"serverUrl={ws_url}\n"
        f"token={node_token}\n"
        "executor=fake\n"          # 不起 claude：本脚本只用 exec 帧
        f"workDir={WS_DIR.as_posix()}\n"
        f"execAllowlist={python}\n"
        "labels=gpu,T4\n"          # requiredLabels=gpu,T4 靠它选到这个节点
        "execShell=bash\n",
        encoding="utf-8")
    log = open(TMP / "runner.log", "w", encoding="utf-8")     # noqa: SIM115（进程存活期一直写）
    proc = subprocess.Popen(["java", "-jar", str(RUNNER_JAR), str(props)],
                            cwd=str(TMP), stdout=log, stderr=subprocess.STDOUT)
    print(f"[0] runner 已起（{RUNNER_JAR.name}，execAllowlist={python}）")
    return proc


def kill(proc):
    if proc is None:
        return
    subprocess.run(["taskkill", "/F", "/T", "/PID", str(proc.pid)], capture_output=True)


# ---------------------------------------------------------------- 造数据

def item_state(tpl, content, similar):
    """按对照组模板造 state：带【】的占位说明必须被真实内容替换（否则冻结会被拒）"""
    state = dict(tpl["state"])
    state["proposal_title"] = f"{MARK} {content[:12]}"
    state["proposal_content"] = content
    state["project"] = "dev-mind"
    state["similar_entries"] = similar
    return state


def gold_for(tpl, layer, quality):
    """从**返回的题面**派生 gold：choice 按选项 key、score 按下标、noul 按构造。

    刻意不硬编码题 id 与选项名：题面改了（题 id 改叫法、选项 key 改名、等级数变）这份 gold
    跟着改，不会退化成「因为选项名变了而永远 400」的死脚本。构造决定的那几题（模板预填的）优先。
    """
    gold = {}
    for qid, q in (tpl["questions"] or {}).items():
        typ = q.get("type")
        if typ == "choice" and isinstance(q.get("criteria"), dict) and layer in q["criteria"]:
            gold[qid] = layer
        elif typ == "score" and isinstance(q.get("criteria"), list):
            gold[qid] = quality
    gold.update(tpl.get("gold") or {})      # duplicate 由构造决定（模板已预填）
    return gold


def add_item(ds_id, tpl, content, similar, layer, quality, case_group=None):
    return call("POST", f"{BASE}/decision/datasets/{ds_id}/items", {
        "state": item_state(tpl, content, similar),
        "questions": tpl["questions"],
        "gold": gold_for(tpl, layer, quality),
        "caseGroup": case_group or tpl["caseGroup"],
        "note": tpl["label"],
    })


def build_dataset():
    """基准集：三类对照组 + 一条普通样本，gold 设计成「恒答 project」必错。

    对照组与 gold 的搭配是本集的立身之处（照 CAP-56 的教训）：
      - 空召回：库里什么都没有 → 不该判重复，层级该收进全局；
      - 逐字重复：正文与召回条目逐字相同 → 该判重复，层级该丢；
      - 不相关：同领域不相干 → 不该判重复；
      - 普通：与已有条目不同 → 层级落项目。
    于是基座（恒答 project/noul/全档 2）只在逐字重复组判对，空召回与不相关组全错——
    「看起来在工作」的假象靠对照组现形。
    """
    tpls = {t["caseGroup"]: t for t in api("GET", "/decision/datasets/templates")}
    assert set(tpls) == {"EMPTY_RECALL", "VERBATIM_DUP", "IRRELEVANT"}, f"对照组模板不全: {list(tpls)}"
    ds_id = api("POST", "/decision/datasets",
                {"name": f"{MARK}-基准集", "kind": "BENCHMARK", "note": "CAP-56 E2E"})["dataset"]["id"]
    dup_body = "构建失败先看日志末尾两百行再猜网络"
    items = [
        ("EMPTY_RECALL", add_item(ds_id, tpls["EMPTY_RECALL"], "新人上手时把发布窗口写进周报模板",
                                  "（未召回到相似条目）", "global", 1)),
        ("VERBATIM_DUP", add_item(ds_id, tpls["VERBATIM_DUP"], dup_body,
                                  "1. 《已有经验》\n" + dup_body, "discard", 2)),
        ("IRRELEVANT", add_item(ds_id, tpls["IRRELEVANT"], "内网镜像仓库的证书每年换一次",
                                "1. 《别的部署坑》\n容器网络与宿主防火墙的排查顺序完全不同", "global", 2)),
        ("NORMAL", add_item(ds_id, tpls["VERBATIM_DUP"], "需求变更要同步更新验收清单",
                            "1. 《别的条目》\n与这段话不同的一条内容", "project", 1,
                            case_group="NORMAL")),
    ]
    for group, (st, payload) in items:
        assert st == 200, f"造样本失败（{group}）: {st} {str(payload)[:300]}"
    landed = [p["item"]["id"] for _, (_, p) in items if not (p.get("goldNotLanded") or [])]
    check("4 条样本的 gold 全部落得上题面（一题都没被静默丢掉）", len(landed) == 4,
          f"落上的只有 {len(landed)} 条")
    print(f"[1] 基准集 #{ds_id} 已建：三类对照组 + 普通，共 4 条")
    return ds_id, tpls


# ---------------------------------------------------------------- A. 建集红线

def section_a(ds_id, tpls):
    step("[A] 建集红线：对照组要名副其实、gold 要落得上题面、题面不许私有化")
    irr = tpls["IRRELEVANT"]

    st, bad = add_item(ds_id, irr, "一条与召回条目逐字相同的正文",
                       "1. 《X》\n一条与召回条目逐字相同的正文", "global", 1)
    check("打着不相关组的标却塞了逐字重复的内容 → 409（不等冻结才说）",
          st == 409 and "不相关" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/datasets/{ds_id}/items",
                   {"state": item_state(irr, "x" * 20, "1. 《Y》\n与上面完全不同的内容"),
                    "questions": irr["questions"], "gold": {"adopt_layer": "不存在的选项"},
                    "caseGroup": "IRRELEVANT"})
    check("gold 值落不上题面 → 400（选项外的值不算答案）",
          st == 400 and "落不上" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/datasets/{ds_id}/items",
                   {"state": item_state(irr, "a" * 20, "1. 《Z》\n另一条不相关内容"),
                    "questions": {"adopt_layer": {"type": "choice", "criteria": {"x": "y"}}},
                    "gold": {"adopt_layer": "x"}, "caseGroup": "NORMAL"})
    check("题面与标准题面不一致 → 400（一期只评一套题面）",
          st == 400 and "题面" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/datasets/{ds_id}/items",
                   {"state": item_state(irr, "b" * 20, "1. 《W》\n又一条不相关内容"),
                    "questions": irr["questions"], "gold": {"adopt_layer": "project"},
                    "caseGroup": "EMPTY_RECAL"})
    check("认不出的 caseGroup → 400（拼错一个字母不会被吞成普通样本）",
          st == 400 and "caseGroup" in str(bad), f"{st} {str(bad)[:200]}")


# ---------------------------------------------------------------- B. 冻结

def section_b(ds_id, tpls):
    step("[B] 冻结：对照组齐不齐、名副其实不；冻结后只读，要改走修订")
    thin_id = api("POST", "/decision/datasets",
                  {"name": f"{MARK}-缺对照集", "kind": "BENCHMARK"})["dataset"]["id"]
    st, _ = add_item(thin_id, tpls["VERBATIM_DUP"], "缺对照组的集里只放一条普通样本",
                     "1. 《别的条目》\n与这段不同", "project", 1, case_group="NORMAL")
    assert st == 200, f"造缺对照样本失败: {st}"
    st, bad = call("POST", f"{BASE}/decision/datasets/{thin_id}/freeze")
    check("缺对照组 → 冻结被拒，且逐个点名缺哪一组",
          st == 409 and all(x in str(bad) for x in ("空召回", "逐字重复", "不相关")),
          f"{st} {str(bad)[:300]}")

    detail = api("POST", f"/decision/datasets/{ds_id}/freeze")
    v1 = detail["dataset"]
    check("对照组齐备 → 冻结成功，版本 v1", v1["frozen"] is True and v1["version"] == 1, f"{v1}")
    check("冻结时记下题面版本（否则以后改题面会让旧报告被无声对齐）",
          bool(v1["questionSetVersion"]), f"{v1}")
    check("按组给出条数（四组都在，缺的记 0——界面才看得出缺哪个）",
          detail["caseGroupCounts"] == {"NORMAL": 1, "EMPTY_RECALL": 1, "VERBATIM_DUP": 1,
                                        "IRRELEVANT": 1}, f"{detail['caseGroupCounts']}")

    st, bad = add_item(ds_id, tpls["IRRELEVANT"], "冻结之后再加的样本",
                       "1. 《V》\n与上面不同的一条内容", "global", 1)
    check("冻结后加条目 → 拒（指标的分母不许动）", st >= 400, f"{st} {str(bad)[:200]}")

    revised = api("POST", f"/decision/datasets/{ds_id}/revise")["dataset"]
    check("修订派生新版本 v2（旧版本与它名下的历史指标都留着）",
          revised["id"] != ds_id and revised["version"] == 2 and revised["frozen"] is False,
          f"{revised}")
    print(f"[2] 冻结 v1 #{ds_id}，修订出 v2 #{revised['id']}")
    return revised["id"]


# ---------------------------------------------------------------- C. 登记与闸门

def section_c(node_id):
    step("[C] 产物登记与准入闸门（没验证过的产物 → 分诊整体不可用）")
    BASE_CKPT.mkdir(parents=True, exist_ok=True)
    (BASE_CKPT / "rl_agent_config.json").write_text('{"slot": "%s"}' % SLOT, encoding="utf-8")
    (BASE_CKPT / "model.safetensors").write_bytes(b"cap56-e2e-base-weights")

    gate = api("GET", "/decision/checkpoints/gate")
    check("一份产物都没登记 → 闸门关闭，原因指向「登记」",
          gate["open"] is False and "登记" in gate["reason"], f"{gate}")

    st, tri = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("闸门关闭 = 分诊不可用（按钮置灰与运行时降级同一上游），原因指向决策实验室",
          st == 200 and tri["available"] is False and "决策实验室" in tri["reason"], f"{st} {tri}")

    st, bad = call("POST", f"{BASE}/decision/checkpoints",
                   {"name": f"{MARK}-无来源基座", "serveSlot": SLOT, "kind": "BASE"})
    check("登记时不写来源路径 → 400（总得说得出权重从哪来）",
          st == 400 and "来源路径" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/checkpoints",
                   {"name": f"{MARK}-无指纹微调产物", "serveSlot": SLOT, "kind": "FINETUNED",
                    "sourcePath": BASE_CKPT.as_posix()})
    check("登记 FINETUNED 却不给 sha256 → 400（路径会被下一轮训练覆盖，不算凭据）",
          st == 400 and "sha256" in str(bad), f"{st} {str(bad)[:200]}")

    base = api("POST", "/decision/checkpoints",
               {"name": f"{MARK}-base", "serveSlot": SLOT, "kind": "BASE",
                "sourcePath": BASE_CKPT.as_posix(), "nodeId": node_id, "note": "官方基座（本机模拟）"})
    check("登记基座：kind=BASE 且默认未验证（登记 ≠ 放行）",
          base["kind"] == "BASE" and base["verified"] is False, f"{base}")

    st, bad = call("POST", f"{BASE}/decision/checkpoints/{base['id']}/verify", {"note": "  "})
    check("放行必须写判断依据 → 空 note 400（不留「某天有人点了一下」）",
          st == 400 and "依据" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/checkpoints/{base['id']}/unverify", {"reason": ""})
    check("没在放行也撤销不了 → 409，且撤销要写原因",
          st == 409 and "并未在放行" in str(bad), f"{st} {str(bad)[:200]}")

    other = api("POST", "/decision/checkpoints",
                {"name": f"{MARK}-english", "serveSlot": OTHER_SLOT, "kind": "BASE",
                 "sourcePath": (TMP / "english-ckpt").as_posix(), "note": "另一槽位的基座"})
    print(f"[3] 已登记 #{base['id']}（{SLOT}）与 #{other['id']}（{OTHER_SLOT}），均未放行")
    return base, other


# ---------------------------------------------------------------- D. 评测

def section_d(ds_id, unfrozen_id, base):
    step("[D] 评测：未冻结集拒发、对照基线不许是自己、指标 + 两条基线 + 逐题 + 对照组 + 校准")
    st, bad = call("POST", f"{BASE}/decision/evaluations",
                   {"checkpointId": base["id"], "datasetId": unfrozen_id})
    check("未冻结的集 → 拒发（指标只有在输入固定时才可比）",
          st >= 400 and "冻结" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/evaluations",
                   {"checkpointId": base["id"], "datasetId": ds_id, "baseCheckpointId": base["id"]})
    check("对照基线选成被测自己 → 400（自己跟自己比没有信息量）",
          st == 400 and "同一个" in str(bad), f"{st} {str(bad)[:200]}")

    mock("/__reset", {})
    ev = api("POST", "/decision/evaluations", {
        "checkpointId": base["id"], "datasetId": ds_id, "nodeId": CTX["node_id"],
        "requiredLabels": "gpu,T4", "pythonPath": CTX["python"], "fitTemperature": True})
    check("发起评测 → 202 立刻返回 QUEUED 行（不等结果）",
          ev["status"] == "QUEUED" and ev["reportStatus"] is None, f"{ev}")
    print(f"      评测 #{ev['id']} 已下发，等它跑完…")

    done = settled(f"/decision/evaluations/{ev['id']}", f"评测 #{ev['id']}")
    v, rep = done["view"], done["report"]
    hint = ""
    if v["status"] == "FAILED" and "torch" in str(v.get("errorSummary")):
        hint = "（报 torch = app 没按前置要求把 scripts-dir 指向 tmp/cap56-e2e/scripts）"
    check("评测 SUCCESS（stub 退出码 0）", v["status"] == "SUCCESS",
          f"{v.get('errorSummary')}{hint}")
    check("报告齐备 = REPORT_OK（metrics 与 baselines 都在）",
          v["reportStatus"] == "OK", f"{v['reportStatus']} / {v.get('reportLabel')}")

    hp = v["headline"]
    check("头条同时给出随机与多数类两条基线（没有基线读不出好坏）",
          isinstance(hp.get("random"), (int, float)) and isinstance(hp.get("majority"), (int, float)),
          f"{hp}")
    check("头条条数 = 冻结集的条数", hp.get("items") == 4, f"{hp}")
    check("恒答退化的基座准确率**低于**多数类基线（退化被显式照出来，不靠人偶然发现）",
          isinstance(hp.get("accuracy"), (int, float)) and hp["accuracy"] < hp["majority"], f"{hp}")
    check("校准前后 ECE 都进头条（FR-04：让徽标上的百分比别骗人）",
          isinstance(hp.get("eceBefore"), (int, float)) and isinstance(hp.get("eceAfter"), (int, float)),
          f"{hp}")
    check("没选对照基线 → win/lose/tie 是「没测」而不是 0（缺项不编数）",
          hp.get("win") is None and hp.get("lose") is None and hp.get("tie") is None, f"{hp}")

    metrics = rep["metrics"]
    check("三种题型各有指标块 + 延迟分位（choice/score/noul/latencyMs）",
          all(k in metrics for k in ("choice", "score", "noul", "latencyMs"))
          and isinstance(metrics["latencyMs"].get("p50"), (int, float)), f"{list(metrics)}")
    check("baselines 是报告里显式的一段（不并进 metrics 里被忽略）",
          isinstance(rep["baselines"], dict) and {"random", "majority"} <= set(rep["baselines"]),
          f"{rep['baselines']}")
    groups = rep["byCaseGroup"]
    check("对照组分解按组给准确率（四组都在）",
          {g["caseGroup"] for g in groups} == {"NORMAL", "EMPTY_RECALL", "VERBATIM_DUP", "IRRELEVANT"},
          f"{groups}")
    by_group = {g["caseGroup"]: g["accuracy"] for g in groups}
    check("配对对照组的结论：逐字重复组明显高于空召回组（恒答重复在空召回组必错）",
          by_group["VERBATIM_DUP"] > by_group["EMPTY_RECALL"], f"{by_group}")
    check("空召回组准确率为 0（恒答重复在这里全错——这正是加对照组的原因）",
          by_group["EMPTY_RECALL"] == 0, f"{by_group}")

    per_item = rep["perItem"]
    check("逐题明细 = 4 条 × 3 题，gold/pred/对错/置信度/延迟齐全",
          len(per_item) == 12 and all(
              {"id", "caseGroup", "question", "gold", "pred", "correct", "confidence",
               "latencyMs"} <= set(r) for r in per_item), f"{len(per_item)} 行")
    check("逐题明细覆盖四组（对照组样本的预测逐条记下来，不是只留总分）",
          {r["caseGroup"] for r in per_item} == {"NORMAL", "EMPTY_RECALL", "VERBATIM_DUP",
                                                 "IRRELEVANT"}, f"{per_item[:2]}")

    cal = rep["calibration"]
    check("温度校准：held-out 拟合 + 前后 ECE 对比 + 每个（题型,选项数）桶一个温度",
          cal.get("mode") == "heldout" and isinstance(cal.get("temperature"), dict)
          and bool(cal["temperature"]) and cal["before"]["ece"] is not None
          and cal["after"]["ece"] is not None, f"{cal}")
    check("校准是真往好里去的（after < before），头条与报告同源",
          cal["after"]["ece"] < cal["before"]["ece"]
          and hp["eceBefore"] == cal["before"]["ece"] and hp["eceAfter"] == cal["after"]["ece"],
          f"{cal} vs {hp}")

    logs = api("GET", f"/decision/evaluations/{ev['id']}/logs")
    check("人读日志里没有 marker 行（base64 大块不该混进日志）",
          "DEVMIND_REPORT" not in logs and "DEVMIND_ITEM" not in logs, str(logs)[:200])
    check("执行包真的下发并物化了（runner 拉了包、解到临时目录）",
          "执行包已物化" in logs, str(logs)[:300])
    check("日志里有实际跑的那条命令，且它就是库里记的那条",
          "$DEVMIND_LAB_SCRIPT" in logs and done["commandText"] in logs, str(logs)[:300])
    check("stub 的逐题进度行照常进日志（人读的部分不被 marker 剔除殃及）",
          "[stub-eval]" in logs, str(logs)[:300])

    ckpt = api("GET", f"/decision/checkpoints/{base['id']}")["checkpoint"]
    check("评测结论回写了产物行（放行前要看的就这一处，不用去翻最近一次评测）",
          ckpt["hasMetrics"] is True and ckpt["hasCalibration"] is True, f"{ckpt}")
    CTX["base_accuracy"] = hp["accuracy"]
    return ev, done


# ---------------------------------------------------------------- E. 微调 + 自动回评

def section_e(ds_id, eval_ds_id, base):
    step("[E] 微调：切分 → 训练 → 指纹登记 → 自动回评（回评优于基座才算数）")
    st, bad = call("POST", f"{BASE}/decision/finetunes",
                   {"datasetId": ds_id, "evalDatasetId": ds_id, "baseCheckpointId": base["id"],
                    "outputPath": FT_OUT.as_posix()})
    check("训练集与回评集同一份 → 400（拿练习题当考卷）",
          st == 400 and "回评集不能与训练集相同" in str(bad), f"{st} {str(bad)[:200]}")

    st, bad = call("POST", f"{BASE}/decision/finetunes",
                   {"datasetId": ds_id, "evalDatasetId": eval_ds_id, "baseCheckpointId": base["id"],
                    "outputPath": "   "})
    check("产出目录为空 → 400（权重留节点，总得说清留在哪）",
          st == 400 and "产出目录" in str(bad), f"{st} {str(bad)[:200]}")

    shutil.rmtree(FT_OUT, ignore_errors=True)
    ft = api("POST", "/decision/finetunes", {
        "datasetId": ds_id, "evalDatasetId": eval_ds_id, "baseCheckpointId": base["id"],
        "outputPath": FT_OUT.as_posix(), "nodeId": CTX["node_id"], "requiredLabels": "gpu,T4",
        "pythonPath": CTX["python"], "epochs": 2, "batchSize": 2, "learningRate": 1e-4,
        "trainSeed": 7, "splitSeed": 7, "trainRatio": 0.75})
    check("发起微调 → 202，切分判据同时入库（3 训练 / 1 验证，种子记下）",
          ft["status"] == "QUEUED" and ft["trainCount"] == 3 and ft["valCount"] == 1
          and ft["splitSeed"] == 7 and ft["trainRatio"] == 0.75, f"{ft}")
    print(f"      微调 #{ft['id']} 已下发（{ft['trainCount']} 训练 / {ft['valCount']} 验证），等它跑完…")

    done = settled(f"/decision/finetunes/{ft['id']}", f"微调 #{ft['id']}")
    fv = done["view"]
    check("微调 SUCCESS", fv["status"] == "SUCCESS", f"{fv.get('errorSummary')}")
    check("报告带训练段（步数/loss/奖励都出来了）",
          isinstance(done["train"], dict) and done["train"].get("steps"), f"{done['train']}")
    check("valItemIds 指认得回具体样本，且条数与切分一致",
          isinstance(done["valItemIds"], list) and len(done["valItemIds"]) == fv["valCount"],
          f"{done['valItemIds']} vs {fv['valCount']}")
    check("收尾结论：产物已登记 + 自动回评已触发（且没报错）",
          "已登记" in (fv.get("postLabel") or "") and "回评" in (fv.get("postLabel") or "")
          and fv.get("postError") is None, f"{fv.get('postLabel')} / {fv.get('postError')}")

    ft_ckpt = api("GET", f"/decision/checkpoints/{fv['checkpointId']}")["checkpoint"]
    check("自动登记的产物是 FINETUNED + sha256 + 槽位跟着基座走（未验证）",
          ft_ckpt["kind"] == "FINETUNED" and len(ft_ckpt["fingerprintSha256"] or "") == 64
          and ft_ckpt["serveSlot"] == SLOT and ft_ckpt["verified"] is False, f"{ft_ckpt}")
    check("来源路径取脚本实报的那一份（输出目录会被下一轮覆盖，不认人填的那个）",
          norm(ft_ckpt["sourcePath"]) == norm(ft_ckpt["fingerprintPath"]) == norm(FT_OUT.as_posix()),
          f"{ft_ckpt['sourcePath']} / {ft_ckpt['fingerprintPath']}")
    check("指标与校准随产物一起写回（放行前唯一要看的东西）",
          ft_ckpt["hasMetrics"] is True and ft_ckpt["hasCalibration"] is True, f"{ft_ckpt}")
    check("产物名能认回是哪次实验（含微调 id 与训练集名）",
          f"ft{ft['id']}" in ft_ckpt["name"] and MARK in ft_ckpt["name"], f"{ft_ckpt['name']}")

    post = settled(f"/decision/evaluations/{fv['evalId']}", f"回评 #{fv['evalId']}")
    pv, prep = post["view"], post["report"]
    check("回评 SUCCESS 且报告齐备", pv["status"] == "SUCCESS" and pv["reportStatus"] == "OK",
          f"{pv['status']} / {pv['reportStatus']}")
    check("回评指标优于基座（同一份基准集上，两份数字摆在一起）",
          isinstance(pv["headline"].get("accuracy"), (int, float))
          and pv["headline"]["accuracy"] > CTX["base_accuracy"],
          f"基座 {CTX['base_accuracy']} → 回评 {pv['headline'].get('accuracy')}")
    cmp_ = prep["compare"]
    check("回评指认得回对照的是哪一份基座",
          bool(cmp_.get("baselineCheckpoint")), f"{cmp_}")
    check("逐题胜负是数出来的（win/lose/tie 都有数，不是「缺项」）",
          all(isinstance(cmp_.get(k), (int, float)) and cmp_[k] >= 0 for k in ("win", "lose", "tie"))
          and cmp_["win"] > 0, f"{cmp_}")
    check("同一口径可比：回评报告的对照组分解照旧四组",
          {g["caseGroup"] for g in prep["byCaseGroup"]}
          == {"NORMAL", "EMPTY_RECALL", "VERBATIM_DUP", "IRRELEVANT"}, f"{prep['byCaseGroup']}")
    check("回评报告与基座报告用同一套题面版本（否则两份数字不可比）",
          pv["questionSetVersion"]
          == api("GET", f"/decision/evaluations/{CTX['eval_id']}")["view"]["questionSetVersion"],
          f"{pv['questionSetVersion']}")
    return ft, ft_ckpt, pv


# ---------------------------------------------------------------- F. serve 自检

def section_f(base, other):
    step("[F] serve 自检：登记的这份 == 边车此刻正在服务的那份？")
    mock("/__sources", sources_for(SLOT, BASE_CKPT.as_posix()))
    sc = api("POST", f"/decision/checkpoints/{base['id']}/serve-check")
    by_item = {c["item"]: c for c in sc["checks"]}
    check("来源一致 → OK（边车实报路径与登记的归一后逐字对上）",
          sc["status"] == "OK" and by_item["来源一致"]["status"] == "OK",
          f"{sc['status']} / {sc['summary']}")
    check("逐项都有结论（边车可达 / 边车状态 / 槽位常驻 / 来源一致 / 设备）",
          {"边车可达", "边车状态", "槽位常驻", "来源一致", "设备"} <= set(by_item), f"{list(by_item)}")

    mock("/__sources", sources_for(SLOT, "/somewhere/else/model"))
    sc2 = api("POST", f"/decision/checkpoints/{base['id']}/serve-check")
    by2 = {c["item"]: c for c in sc2["checks"]}
    check("来源不一致 → FAIL，并说清「放行的不是正在服务的那份」",
          sc2["status"] == "FAIL" and "放行的不是正在服务的那份" in by2["来源一致"]["detail"],
          f"{sc2['summary']}")
    check("FAIL 项被点名（结论取最差项，不淹没在通过项里）",
          "来源一致" in sc2["summary"], f"{sc2['summary']}")

    mock("/__sources", sources_for(SLOT, BASE_CKPT.as_posix(), ready=False))
    sc3 = api("POST", f"/decision/checkpoints/{base['id']}/serve-check")
    by3 = {c["item"]: c for c in sc3["checks"]}
    check("边车说本地目录不完整 → FAIL（这份权重压根加载不出来，服务的是别的）",
          by3["来源一致"]["status"] == "FAIL" and "不完整" in by3["来源一致"]["detail"],
          f"{by3['来源一致']}")

    mock("/__sources", {"sources": None})
    sc4 = api("POST", f"/decision/checkpoints/{base['id']}/serve-check")
    by4 = {c["item"]: c for c in sc4["checks"]}
    check("老边车报不出 sources → WARN 而不是 OK（没验过 ≠ 验过了）",
          sc4["status"] == "WARN" and by4["来源一致"]["status"] == "WARN"
          and "老版本边车" in by4["来源一致"]["detail"], f"{sc4['summary']}")

    sc5 = api("POST", f"/decision/checkpoints/{other['id']}/serve-check")
    by5 = {c["item"]: c for c in sc5["checks"]}
    check("边车没常驻那个槽位 → FAIL「此刻它服务的不是这份产物」",
          by5["槽位常驻"]["status"] == "FAIL" and OTHER_SLOT in by5["槽位常驻"]["detail"],
          f"{by5['槽位常驻']}")
    check("自检结论落库（列表页免解析就能看到上次结果）",
          api("GET", f"/decision/checkpoints/{base['id']}")["checkpoint"]["serveCheckStatus"]
          == sc4["status"], "上次自检状态没落库")


# ---------------------------------------------------------------- G. 放行与同槽位互斥

def section_g(base, ft_ckpt, other):
    step("[G] 放行与同槽位互斥（一个槽位只允许一份在放行）")
    mock("/__sources", sources_for(SLOT, BASE_CKPT.as_posix()))
    verified = api("POST", f"/decision/checkpoints/{base['id']}/verify",
                   {"note": "E2E：指标已看过，基座放行"})
    check("人工放行：记下依据与放行人（谁在什么时候凭什么验的）",
          verified["verified"] is True and bool(verified["verifiedBy"])
          and "指标已看过" in (verified["verifiedNote"] or ""), f"{verified}")

    gate = api("GET", "/decision/checkpoints/gate")
    check("放行后闸门打开，并列出正在服务的那份",
          gate["open"] is True and [s["id"] for s in gate["serving"]] == [base["id"]], f"{gate}")
    st, tri = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("放行后分诊可用（与置灰/降级同一上游，两侧不可能不一致）",
          st == 200 and tri["available"] is True and tri["reason"] == "", f"{st} {tri}")

    ft_final = api("POST", f"/decision/checkpoints/{ft_ckpt['id']}/verify",
                   {"note": "E2E：回评优于基座，放行微调产物"})
    check("放行同槽位的第二份 → 顶上第一份", ft_final["verified"] is True, f"{ft_final}")
    superseded = api("GET", f"/decision/checkpoints/{base['id']}")["checkpoint"]
    check("被顶掉的那份保留放行史（谁验过它不因失宠而作废），只是开关关掉",
          superseded["verified"] is False and "指标已看过" in (superseded["verifiedNote"] or ""),
          f"{superseded}")
    gate2 = api("GET", "/decision/checkpoints/gate")
    check("闸门跟着换人：serving 变成微调产物，且只有一份",
          gate2["open"] is True and [s["id"] for s in gate2["serving"]] == [ft_ckpt["id"]], f"{gate2}")

    st, bad = call("POST", f"{BASE}/decision/checkpoints/{ft_ckpt['id']}/unverify", {"reason": " "})
    check("撤销放行必须写原因 → 400", st == 400 and "原因" in str(bad), f"{st} {str(bad)[:200]}")

    api("POST", f"/decision/checkpoints/{ft_ckpt['id']}/unverify", {"reason": "E2E 收尾：撤销"})
    gate3 = api("GET", "/decision/checkpoints/gate")
    st, tri3 = call("GET", f"{BASE}/knowledge/proposals/triage-status")
    check("撤销后闸门立即关闭、分诊立即不可用（不缓存、不等重启）",
          gate3["open"] is False and tri3["available"] is False, f"{gate3} {tri3}")
    note = api("GET", f"/decision/checkpoints/{ft_ckpt['id']}")["checkpoint"]["verifiedNote"]
    check("撤销原因追加进放行史而不是覆盖原依据（两句话都在）",
          "撤销" in (note or "") and "回评优于基座" in (note or ""), f"{note}")


# ---------------------------------------------------------------- main

def main():
    global TOKEN
    login = api("POST", "/auth/login", {"username": "admin", "password": "admin123"})
    TOKEN = login["accessToken"]
    CTX["python"] = sys.executable.replace("\\", "/")
    assert " " not in CTX["python"] and "'" not in CTX["python"] and '"' not in CTX["python"], (
        f"当前解释器路径含空白/引号，不能作为命令行首 token（runner 逐行取首 token 校验白名单，"
        f"服务端也拒）：{CTX['python']}\n请换不含空格的 python（如 venv 的 Scripts/python.exe）再跑。")
    print(f"[0] 登录 admin OK（{BASE}）；节点解释器 {CTX['python']}")

    for path, what in (("/decision/datasets", "评测集"), ("/decision/evaluations", "评测运行"),
                       ("/decision/finetunes", "微调任务"), ("/decision/checkpoints", "产物")):
        total = page(path, size=1)["total"]
        assert total == 0, (f"{what}里已有 {total} 条历史数据 —— 本脚本要求全新库：\n"
                            f"停掉 app、rm -rf {TMP / 'data'}、按文件头注释重启后再跑。")

    write_stubs()
    sidecar, runner = None, None
    try:
        sidecar = start_sidecar()
        st, nodes = call("GET", f"{BASE}/agent-nodes")
        assert st == 200 and nodes == [], f"节点表非空（库不是新的？）: {st} {nodes}"
        issued = api("POST", "/agent-nodes", {"name": f"{MARK}-node", "labels": "gpu,T4"})
        CTX["node_id"] = str(issued["node"]["id"])
        runner = start_runner(issued["token"])

        node = wait(lambda: next((n for n in api("GET", "/agent-nodes")
                                  if n["id"] == issued["node"]["id"] and n["status"] == "ONLINE"), None),
                    f"节点 {CTX['node_id']} 上线", timeout=90)
        check("runner 接上了，且协议够新（v14 才有带执行包的 exec 帧）",
              (node["protocolVersion"] or 0) >= 14, f"protocolVersion={node['protocolVersion']}")
        check("节点标签按 runner 配置上报（服务端编辑会被它覆盖）",
              "gpu" in (node["labels"] or ""), f"{node['labels']}")

        ep = api("POST", "/model-endpoints", {"kind": "DECISION", "name": f"{MARK}-决策边车",
                                              "provider": "laya", "baseUrl": SIDECAR})
        api("PUT", f"/model-endpoints/{ep['id']}/default")
        print(f"[0] DECISION 端点 #{ep['id']} 已设为平台默认 → {SIDECAR}")

        ds_id, tpls = build_dataset()
        section_a(ds_id, tpls)
        eval_ds_id = section_b(ds_id, tpls)              # v2：留着当回评集（此时还没冻结）
        base, other = section_c(CTX["node_id"])
        ev, _ = section_d(ds_id, eval_ds_id, base)       # 用未冻结的 v2 验「拒发」
        CTX["eval_id"] = ev["id"]
        api("POST", f"/decision/datasets/{eval_ds_id}/freeze")
        ft, ft_ckpt, _ = section_e(ds_id, eval_ds_id, base)
        section_f(base, other)
        section_g(base, ft_ckpt, other)
        print(f"\n[9] 留痕：基座产物 #{base['id']}、微调产物 #{ft_ckpt['id']}、"
              f"基座评测 #{ev['id']}、回评 #{ft['evalId']}、微调 #{ft['id']}")
    finally:
        kill(runner)
        kill(sidecar)


# ---------------------------------------------------------------- stub 脚本（写进 scripts-dir）

STUB_COMMON = '''\
# -*- coding: utf-8 -*-
"""CAP-56 E2E 的 stub 公共件（由 tests/cap56_e2e.py 生成，只依赖标准库）。

**它不是模型，是一面镜子**：把 2026-09-22 真机实测到的退化形态写成策略——
「基座恒答 project / 判重复 / 全档 2」，微调后按 gold 答。于是链路上的一切（执行包、白名单、
日志回流、marker 抓取、指标与基线的显式暴露）都是真的，只有模型质量是假的。
真模型与真指标在 tools/laya-sidecar/lab/，只能在 GPU 节点上验。

每个数字怎么来的（不含任何硬编码的分数）：
  metrics      逐题判对错后现算；latencyMs 是本题真实的计时（含下面那次 sleep）
  baselines    随机 = 1/选项数；多数类 = choice 题里最高频 gold 的占比（**由数据算**，
               所以「退化低于多数类」这句是被数据证明的，不是被断言写死的）
  brier        choice 题按 (pred, conf) 还原一份分布，与 gold one-hot 比平方误差
  ece          单桶估计 |平均置信度 − 准确率|（真脚本按桶拟合，见 lab/laya_eval.py）
  calibration  温度按 (题型,选项数) 桶取「置信度/准确率」，ece after = before/平均 T
               —— 形状对、口径粗糙，只为让平台侧的前后对比走通
"""
import base64, gzip, json, os, sys, time

for _s in (sys.stdout, sys.stderr):      # 节点上也是 GBK 控制台：编不出的符号打成 "?" 就好
    try:
        _s.reconfigure(errors="replace")
    except Exception:
        pass

FT_OUT_MARK = "ft-out"      # 产出目录名：出现它 = 微调产物（详见 cap56_e2e.py 的 FT_OUT）
LATENCY_S = 0.02            # 每题一次 sleep，让延迟分位有真东西可测


def marker(name, payload):
    """单行 marker：<MARKER> <base64(gzip(json))>（服务端 LabMarkers.decode 的口径）"""
    raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    sys.stdout.write(name + " " + base64.b64encode(gzip.compress(raw)).decode("ascii") + "\\n")
    sys.stdout.flush()


def load_payload(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def is_finetuned(checkpoint):
    return FT_OUT_MARK in (checkpoint or "").replace("\\\\", "/")


def _yes(value):
    if isinstance(value, bool):
        return value
    if isinstance(value, (int, float)):
        return value >= 0.5
    return str(value).strip().lower() in ("true", "1", "yes", "是")


def gold_answers(node):
    """标准答案（每题一个可比较的值）。优先用服务端摊好的 goldDistribution——

    换算口径在服务端 GoldDistributions 里只做一次，脚本直接用，就不会出现「训练用的分布
    与评测用的分布哪天差了一点点而没人发现」。noul 的分布是**单值** {"noul":0/1}，
    取它的值而不是键（键恒为 "noul"，取键会把"不重复"也读成"重复"）。
    """
    dist = node.get("goldDistribution") or {}
    raw = node.get("gold") or {}
    out = {}
    for qid, q in (node.get("questions") or {}).items():
        d = dist.get(qid)
        v = raw.get(qid)
        typ = q.get("type")
        if typ == "noul":
            val = d.get("noul") if isinstance(d, dict) else None
            out[qid] = "noul" if _yes(v if v is not None else val) else "not-noul"
        elif isinstance(d, dict) and d:
            out[qid] = max(d, key=d.get)
        elif v is not None:
            out[qid] = str(v)
    return out


def base_policy(node, qid):
    """基座的退化策略（恒答同一个答案——真机实测的形态）"""
    q = (node.get("questions") or {}).get(qid) or {}
    typ = q.get("type")
    if typ == "choice":
        return "project"          # 三组全判 project（真机实测）
    if typ == "noul":
        return "noul"             # 含空召回组也判重复（真机实测 0.988）
    if typ == "score":
        return "2"                # 全档 2（真机实测）
    return None


def percentile(values, q):
    if not values:
        return None
    s = sorted(values)
    idx = min(len(s) - 1, max(0, int(round(q * (len(s) - 1)))))
    return round(s[idx], 2)


def run(items, checkpoint, quiet=False):
    """按 checkpoint 路径决定策略跑一遍，返回 {rows, per_item, metrics, baselines, groups}。

    rows 逐题留痕，_brier/_ece/校准都从它派生——三处数字同源，不会互相打架。
    """
    finetuned = is_finetuned(checkpoint)
    rows, per_item, lats = [], [], []
    conf = 0.86 if finetuned else 0.99      # 退化的模型照样"自信"，这正是 ece 要照出来的
    for node in items:
        gold = gold_answers(node)
        group = node.get("caseGroup") or "NORMAL"
        for qid in gold:
            q = (node.get("questions") or {}).get(qid) or {}
            if q.get("type") not in ("choice", "score", "noul"):
                continue
            pred = gold[qid] if finetuned else base_policy(node, qid)
            t0 = time.perf_counter()
            time.sleep(LATENCY_S)
            lat = (time.perf_counter() - t0) * 1000
            lats.append(lat)
            rows.append({"id": node.get("id"), "caseGroup": group, "question": qid,
                         "type": q.get("type"), "keys": list((q.get("criteria") or {}).keys())
                         if q.get("type") == "choice" else None,
                         "gold": gold[qid], "pred": pred, "correct": pred == gold[qid],
                         "confidence": conf, "latencyMs": round(lat, 2)})
            per_item.append(dict(rows[-1]))
            if not quiet:
                print("[stub] 题 #%s [%s] %s → %s（gold %s）%s" % (
                    node.get("id"), group, qid, pred, gold[qid],
                    "对" if pred == gold[qid] else "错"), flush=True)

    by_type = {}
    for r in rows:
        by_type.setdefault(r["type"], []).append(r)
    a = lambda rs: round(sum(1 for r in rs if r["correct"]) / max(len(rs), 1), 4)
    choice = by_type.get("choice", [])
    noul = by_type.get("noul", [])
    score = by_type.get("score", [])
    mae = round(sum(abs(float(r["pred"]) - float(r["gold"])) for r in score) / max(len(score), 1), 4)

    # 两条基线由数据算：随机 = 1/选项数，多数类 = 最高频 gold 的占比
    sizes = [len(r["keys"] or []) for r in choice if r["keys"]]
    counts = {}
    for r in choice:
        counts[r["gold"]] = counts.get(r["gold"], 0) + 1
    baselines = {
        "random": round(1.0 / max(sum(sizes) / max(len(sizes), 1), 1), 4),
        "majority": round(max(counts.values()) / max(len(choice), 1), 4) if counts else None,
    }
    metrics = {
        "items": len(items),
        "questions": len(rows),
        "choice": {"questions": len(choice), "accuracy": a(choice), "softAccuracy": a(choice),
                   "brier": _brier(choice), "ece": _ece(choice), "avgConfidence": conf},
        "score": {"questions": len(score), "mae": mae, "within1Level": a(score)},
        "noul": {"questions": len(noul), "noulRate": a(noul), "answered": len(noul)},
        "latencyMs": {"p50": percentile(lats, 0.5), "p95": percentile(lats, 0.95)},
    }
    groups = []
    for g in sorted({r["caseGroup"] for r in rows}):
        rs = [r for r in rows if r["caseGroup"] == g]
        groups.append({"caseGroup": g, "items": len({r["id"] for r in rs}),
                       "questions": len(rs), "accuracy": a(rs)})
    return {"rows": rows, "per_item": per_item, "metrics": metrics, "baselines": baselines,
            "groups": groups}


def _brier(rows):
    total = 0.0
    for r in rows:
        keys = r["keys"] or []
        m = max(len(keys), 1)
        rest = (1.0 - r["confidence"]) / max(m - 1, 1)
        total += sum(((r["confidence"] if k == r["pred"] else rest)
                      - (1.0 if k == r["gold"] else 0.0)) ** 2 for k in keys)
    return round(total / max(len(rows), 1), 4)


def _ece(rows):
    """单桶 ECE：|平均置信度 − 准确率|（真脚本按 (题型,选项数) 分桶）"""
    if not rows:
        return None
    return round(abs(sum(r["confidence"] for r in rows) / len(rows)
                     - sum(1 for r in rows if r["correct"]) / len(rows)), 4)


def compare_block(items, test_ckpt, base_ckpt):
    """逐题胜负：被测模型答对而基线答错 = win（评测侧的 --baseline-checkpoint 就指这个）"""
    test = {(r["id"], r["question"]): r for r in run(items, test_ckpt, quiet=True)["rows"]}
    base = {(r["id"], r["question"]): r for r in run(items, base_ckpt, quiet=True)["rows"]}
    win = lose = tie = 0
    for key, t in test.items():
        b = base.get(key)
        if b is None:
            continue
        if t["correct"] and not b["correct"]:
            win += 1
        elif b["correct"] and not t["correct"]:
            lose += 1
        else:
            tie += 1
    return {"baselineCheckpoint": base_ckpt, "win": win, "lose": lose, "tie": tie}


def calibration_block(rows):
    """按 (题型,选项数) 各估一个温度：置信度/该桶准确率（真脚本按 held-out 拟合）"""
    buckets, temps = {}, {}
    for r in rows:
        key = "%s:%d" % (r["type"], len(r["keys"] or []) or 1)
        buckets.setdefault(key, []).append(r)
    for key, rs in buckets.items():
        acc = max(sum(1 for r in rs if r["correct"]) / len(rs), 1e-3)
        temps[key] = round(min(max(rs[0]["confidence"] / acc, 0.5), 3.0), 3)
    all_rows = rows
    before = _ece(all_rows)
    avg_t = sum(temps.values()) / max(len(temps), 1)
    return {"mode": "heldout", "source": "payload.items（stub：形状对、口径粗）",
            "temperature": temps, "fitItems": len(all_rows), "evalItems": len(all_rows),
            "before": {"ece": before}, "after": {"ece": round(before / max(avg_t, 1e-3), 4)}}
'''

STUB_EVAL = '''\
# -*- coding: utf-8 -*-
"""CAP-56 评测 stub（tests/cap56_e2e.py 生成；真实现 tools/laya-sidecar/lab/laya_eval.py）。

命令行与真脚本逐字一致（由服务端 EvalScript 渲染）：
  <python> $DEVMIND_LAB_SCRIPT --payload $DEVMIND_LAB_PAYLOAD --checkpoint '…' --slot …
           [--out '…'] [--baseline-checkpoint '…' --baseline-slot …] [--fit-temperature]
"""
import argparse, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _stub_common import load_payload, marker, run, compare_block, calibration_block, is_finetuned


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload")
    ap.add_argument("--checkpoint", required=True)
    ap.add_argument("--slot")
    ap.add_argument("--out")
    ap.add_argument("--baseline-checkpoint")
    ap.add_argument("--baseline-slot")
    ap.add_argument("--device")
    ap.add_argument("--fit-temperature", action="store_true")
    a = ap.parse_args()
    payload = a.payload or os.environ.get("DEVMIND_LAB_PAYLOAD")

    doc = load_payload(payload)
    items = doc.get("items") or []
    print("[stub-eval] schemaVersion=%s kind=%s 条数=%d slot=%s" % (
        doc.get("schemaVersion"), doc.get("kind"), len(items), a.slot), flush=True)
    print("[stub-eval] checkpoint=%s 对照=%s 校准=%s" % (
        a.checkpoint, a.baseline_checkpoint, a.fit_temperature), flush=True)

    r = run(items, a.checkpoint)
    report = {
        "schemaVersion": 1,
        "checkpoint": {"name": os.path.basename(a.checkpoint), "slot": a.slot, "path": a.checkpoint},
        "dataset": doc.get("dataset"),
        "metrics": r["metrics"],
        "baselines": r["baselines"],
        "byCaseGroup": r["groups"],
        "perItem": r["per_item"],
        "warnings": doc.get("warnings") or [],
        "stub": True,
    }
    if a.baseline_checkpoint:
        report["compare"] = compare_block(items, a.checkpoint, a.baseline_checkpoint)
        report["compare"]["baselineSlot"] = a.baseline_slot
    if a.fit_temperature:
        report["calibration"] = calibration_block(r["rows"])
    print("[stub-eval] 准确率=%s（随机 %s / 多数类 %s）" % (
        r["metrics"]["choice"]["accuracy"], r["baselines"]["random"],
        r["baselines"]["majority"]), flush=True)
    marker("DEVMIND_REPORT", report)


if __name__ == "__main__":
    main()
'''

STUB_TRAIN = '''\
# -*- coding: utf-8 -*-
"""CAP-56 微调 stub（tests/cap56_e2e.py 生成；真实现 tools/laya-sidecar/lab/laya_train.py）。

命令行与真脚本逐字一致（由服务端 TrainScript 渲染）：
  <python> $DEVMIND_LAB_SCRIPT --payload $DEVMIND_LAB_PAYLOAD --base-checkpoint '…' --slot …
           --out '…' --epochs N --lr F --batch N --seed N [--launcher '…']

产出：把权重写进 --out（rl_agent_config.json + model.safetensors 是边车加载它的前提），
再打印 DEVMIND_FINGERPRINT——服务端凭它自动登记产物（路径会被下一轮训练覆盖，sha256 才是凭据）。
"""
import argparse, hashlib, json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from _stub_common import load_payload, marker, run, compare_block, calibration_block


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--payload")
    ap.add_argument("--base-checkpoint", required=True)
    ap.add_argument("--slot")
    ap.add_argument("--out", required=True)
    ap.add_argument("--epochs", type=int, default=3)
    ap.add_argument("--lr", type=float, default=1e-4)
    ap.add_argument("--batch", type=int, default=8)
    ap.add_argument("--seed", type=int, default=42)
    ap.add_argument("--launcher")
    a = ap.parse_args()
    payload = a.payload or os.environ.get("DEVMIND_LAB_PAYLOAD")

    doc = load_payload(payload)
    items = doc.get("items") or []
    train = [n for n in items if n.get("split") != "VAL"]
    val = [n for n in items if n.get("split") == "VAL"]
    print("[stub-train] 基座=%s 训练 %d / 验证 %d epochs=%d lr=%s batch=%d seed=%d" % (
        a.base_checkpoint, len(train), len(val), a.epochs, a.lr, a.batch, a.seed), flush=True)
    if a.launcher:
        print("[stub-train] launcher=%s（真实现按 shlex 起子进程，不额外占白名单首 token）"
              % a.launcher, flush=True)

    loss, stepno = 1.7, 0
    for _ in range(a.epochs):
        for _node in train:
            stepno += 1
            loss *= 0.82
            print("[stub-train] step %d loss=%.4f reward=%.4f" % (
                stepno, loss, 1.0 - loss / 2.0), flush=True)
            time.sleep(0.01)

    out = a.out
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "rl_agent_config.json"), "w", encoding="utf-8") as f:
        json.dump({"slot": a.slot, "base": a.base_checkpoint, "epochs": a.epochs}, f,
                  ensure_ascii=False)
    weights = os.path.join(out, "model.safetensors")
    with open(weights, "wb") as f:
        f.write(b"cap56-e2e-ft-weights-" + str(a.seed).encode())

    # 训练结束在**验证切分**上出指标（真脚本同口径：不拿训练集自评）
    r = run(val or items, out)
    report = {
        "schemaVersion": 1,
        "checkpoint": {"name": os.path.basename(out), "slot": a.slot, "path": out},
        "dataset": doc.get("dataset"),
        "metrics": r["metrics"],
        "baselines": r["baselines"],
        "byCaseGroup": r["groups"],
        "perItem": r["per_item"],
        "split": doc.get("split") or {},
        "train": {"steps": stepno, "epochs": a.epochs, "items": len(items), "questions": None,
                  "batch": a.batch, "groupSize": 4, "learningRate": a.lr, "seed": a.seed,
                  "finalLoss": round(loss, 4), "meanReward": round(1.0 - loss / 2.0, 4),
                  "lastReward": round(1.0 - loss / 2.0, 4), "durationSec": round(stepno * 0.01, 2),
                  "encoderFrozen": True, "worldSize": 1, "stub": True},
        "compare": compare_block(val or items, out, a.base_checkpoint),
        "calibration": calibration_block(r["rows"]),
        "warnings": doc.get("warnings") or [],
        "stub": True,
    }
    marker("DEVMIND_REPORT", report)
    print("[stub-train] 验证集准确率=%s" % r["metrics"]["choice"]["accuracy"], flush=True)
    marker("DEVMIND_FINGERPRINT", {"path": out, "bytes": os.path.getsize(weights),
                                   "sha256": digest(weights)})


if __name__ == "__main__":
    main()
'''

if __name__ == "__main__":
    TMP.mkdir(parents=True, exist_ok=True)
    try:
        main()
    except AssertionError as e:
        FAILED += 1
        print(f"\nERROR  {e}")
    except Exception:                                    # noqa: BLE001（诊断用：把栈打全）
        FAILED += 1
        import traceback
        traceback.print_exc()
    print(f"\n==== CAP-56 E2E: {PASSED} passed, {FAILED} failed ====")
    if FAILED:
        print(f"排错材料：{TMP}（runner.log / scripts / data）")
    sys.exit(1 if FAILED else 0)
