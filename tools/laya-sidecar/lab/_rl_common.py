"""CAP-56 评测/微调脚本的共用件：执行包解析、marker 上报、指标聚合、温度校准、产物指纹。

这个文件是**服务端与 python 之间那份契约的 python 侧实现**，键名必须与
`devmind-decision-lab` 的 `EvalReport`（报告结构）、`LabMarkers`（marker 名与编码）、
`LabPayload`（执行包数据）严格一致。三处对应关系：

| 这里 | 服务端 |
|---|---|
| `MARKER_REPORT/ITEM/FINGERPRINT` | `LabMarkers.REPORT/ITEM/FINGERPRINT` |
| `emit()` 的 `base64(gzip(utf8 json))` | `LabMarkers.decode()` |
| `load_payload()` 读到的结构 | `LabPayload` 装配的 `payload.json` |
| `aggregate()` 产出的结构 | `EvalReport.status/headline/byCaseGroup` 读的键 |

**为什么指标在 python 侧算而不是服务端重算**：口径只能有一份。`laya.common` 里的
`ece_score` / `proper_reward` 是官方实现，用它们算出来的数才叫"这个模型的 ECE"；
服务端重算一遍只会得到两份迟早不一致的口径。服务端只做两件事——检查必报项在不在、
把要显示的几个数字摘出来（`EvalReport.headline`）。

**为什么日志要 flush**：runner 是逐行读 stdout 的，缓冲住了就等于"跑起来就没动静"，
而一次微调要跑几十分钟——没有进度输出的训练，看着像卡死。
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
import os
import sys
import time
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple

# 与服务端 LabMarkers 里的常量一一对应（改一边必须改另一边）
MARKER_REPORT = "DEVMIND_REPORT"
MARKER_ITEM = "DEVMIND_ITEM"
MARKER_FINGERPRINT = "DEVMIND_FINGERPRINT"

NOUL_KEYS = ["false", "true"]

# 温度网格与边界：边界取 laya.common.TEMP_MIN/TEMP_MAX，超出这个范围的温度是在"锐化"logits
# 而不是校准（官方注释里点了名：0.1006 会把 0.24 的 top 概率宣传成 0.99）
TEMP_MIN = 0.5
TEMP_MAX = 5.0
TEMP_GRID = 46


# ---------------------------------------------------------------- 输出与 marker


def setup_stdout() -> None:
    """stdout/stderr 一律 UTF-8 + 行缓冲。

    Windows 上 python 的 stdout 默认是 cp936：中文日志会变成乱码或被 UnicodeEncodeError 打断，
    而 runner 那侧是按 UTF-8 解码的（`ExecHandler` 用 StandardCharsets.UTF_8）。日志乱码最大的
    危害不是难看——是"看着像服务端坏了"。
    """
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace", line_buffering=True)
        except Exception:
            # 老 python / 被重定向到不支持 reconfigure 的对象：不致命，继续跑
            pass


def log(msg: str) -> None:
    """人读的一行进度。marker 走 emit()，两者在服务端被分流（marker 不进人读日志）。"""
    print(msg, flush=True)


def emit(marker: str, payload: Any) -> None:
    """打一行机器可读载荷：`<MARKER> <base64(gzip(utf8 json))>`。

    单行 + base64 是硬要求：服务端逐行抓取，载荷里出现换行就会被撕成两半。
    gzip 是因为逐题明细可能上百 KB，日志帧与数据库都不该被无谓撑大。
    """
    raw = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    gz = gzip.compress(raw, 6)
    print("%s %s" % (marker, base64.b64encode(gz).decode("ascii")), flush=True)


def seal(**extra: Any) -> Dict[str, Any]:
    """报告收口：补上 schemaVersion 与生成时间。"""
    doc: Dict[str, Any] = {"schemaVersion": 1, "generatedAt": time.strftime("%Y-%m-%d %H:%M:%S")}
    doc.update(extra)
    return doc


# ---------------------------------------------------------------- 执行包


def load_payload(path: Optional[str] = None) -> Dict[str, Any]:
    """读执行包里的 payload.json（路径由 runner 经 DEVMIND_LAB_PAYLOAD 注入）。"""
    p = path or os.environ.get("DEVMIND_LAB_PAYLOAD")
    if not p:
        raise SystemExit("未指定执行包数据文件：读不到 DEVMIND_LAB_PAYLOAD 环境变量")
    data = json.loads(Path(p).read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        raise SystemExit("执行包结构版本不认识：%r（脚本与平台的版本不一致）" % data.get("schemaVersion"))
    return data


def items_of(payload: Dict[str, Any], split: Optional[str] = None) -> List[Dict[str, Any]]:
    """取样本；给了 split 就只取该切分（微调包的每条样本带 TRAIN/VAL 标记）。"""
    items = list(payload.get("items") or [])
    if split is None:
        return items
    return [it for it in items if it.get("split") == split]


def scorable_questions(item: Dict[str, Any]) -> List[str]:
    """这条样本里 gold 落得上题面的题 id（顺序即题面顺序）。

    `goldDistribution` 由服务端按题面 criteria 摊好（`GoldDistributions`），脚本直接用它打分——
    在 python 里再实现一遍换算，等于把同一口径写两处，而两处哪天差一分一毫都没人会发现。
    """
    dist = item.get("goldDistribution") or {}
    questions = item.get("questions") or {}
    return [qid for qid in questions if qid in dist]


# ---------------------------------------------------------------- 模型


def load_agent(path: str, device: Optional[str] = None):
    """加载一个 checkpoint 目录（`laya.Agent` 支持本地绝对路径）。

    路径给了却不存在的报错要指向"路径不对"，不是"模型不兼容"——laya 自己的
    FileNotFoundError 文案正好点明了这一点。
    """
    import laya

    if not path:
        raise SystemExit("未指定 checkpoint 路径")
    if not Path(path).exists():
        raise SystemExit(
            "checkpoint 路径不存在: %s\n"
            "  （产物登记里的 sourcePath 是节点上的本地路径，节点上没同步到就加载不了）" % path
        )
    return laya.Agent(path, device=device or os.environ.get("LAYA_DEVICE") or None)


# ---------------------------------------------------------------- 单题打分


class QRecord:
    """一道题的判分材料。

    `p` / `g` 都是**规范顺序**下的分布（题面 criteria 的顺序），长度 = 选项数。
    choice 的键是选项名、score 的键是等级下标字符串、noul 固定 `["false","true"]`——
    与 `GoldDistributions` 和 laya 应答里 `probabilities` 的键域完全一致，所以对齐只按位置做，
    不做按键查表（键名不一致时按键查会静默丢成 0，按位置至少会因为长度不等当场炸）。
    """

    __slots__ = ("item_id", "case_group", "question", "qtype", "keys", "p", "g",
                 "confidence", "latency_ms")

    def __init__(self, item_id, case_group, question, qtype, keys, p, g, confidence, latency_ms):
        self.item_id = item_id
        self.case_group = case_group
        self.question = question
        self.qtype = qtype
        self.keys = keys
        self.p = list(p)
        self.g = list(g)
        self.confidence = float(confidence)
        self.latency_ms = float(latency_ms)

    @property
    def correct(self) -> bool:
        return argmax(self.p) == argmax(self.g)

    @property
    def gold_label(self) -> str:
        return label_of(self.keys, argmax(self.g))

    @property
    def pred_label(self) -> str:
        return label_of(self.keys, argmax(self.p))

    def row(self) -> Dict[str, Any]:
        """逐题明细的一行（报告里的 perItem，也是页面逐题列表的一行）。"""
        return {
            "id": self.item_id,
            "caseGroup": self.case_group,
            "question": self.question,
            "qtype": self.qtype,
            "gold": self.gold_label,
            "pred": self.pred_label,
            "correct": self.correct,
            "confidence": round(self.confidence, 4),
            "latencyMs": round(self.latency_ms, 1),
        }


def argmax(values: Sequence[float]) -> int:
    best, at = float("-inf"), 0
    for i, v in enumerate(values):
        if v > best:
            best, at = float(v), i
    return at


def label_of(keys: Sequence[str], index: int) -> str:
    return keys[index] if 0 <= index < len(keys) else str(index)


def answer_to_distribution(answer: Dict[str, Any], qdef: Dict[str, Any],
                           gold_keys: Sequence[str]) -> Tuple[List[str], List[float]]:
    """把 laya 的应答对齐成规范顺序的分布。

    - choice：`probabilities` 的键就是选项名（= 题面 criteria 的键）；
      但**不按名字取值**——按 `gold_keys`（服务端摊分布时用的同一套键序）取，
      少一个键就当场炸，而不是悄悄补 0。
    - score：键是等级下标字符串。
    - noul：应答只给一个概率，摊成 `[1-p, p]`。
    """
    qtype = (qdef.get("type") or "").strip()
    if qtype == "noul":
        p_true = float(answer.get("noul", 0.0))
        return NOUL_KEYS, [1.0 - p_true, p_true]
    probs = answer.get("probabilities")
    if not isinstance(probs, dict):
        raise ValueError("应答里没有 probabilities（题面类型 %r）" % qtype)
    out = []
    for key in gold_keys:
        if key not in probs:
            raise ValueError("应答的概率分布里缺选项 %r（题面键 %r，分布键 %r）"
                             % (key, list(gold_keys), list(probs)))
        out.append(float(probs[key]))
    total = sum(out)
    if total <= 0:
        raise ValueError("应答的概率分布全为 0：%r" % probs)
    return list(gold_keys), [v / total for v in out]


def gold_to_distribution(gold: Dict[str, Any], gold_keys: Sequence[str]) -> List[float]:
    """服务端摊好的 gold 分布 → 规范顺序的向量。noul 是单值 `{"noul": 0/1}`。"""
    if set(gold.keys()) == {"noul"}:
        v = float(gold["noul"])
        return [1.0 - v, v]
    missing = [k for k in gold_keys if k not in gold]
    if missing:
        raise ValueError("gold 分布里缺选项 %r（题面键 %r）" % (missing, list(gold_keys)))
    return [float(gold[k]) for k in gold_keys]


def reward_of(p: Sequence[float], g: Sequence[float], qtype: str) -> float:
    """用官方的 `proper_reward`（log + spherical + RPS）给一个分布打分。数值越高越好。

    这是**逐题比较两个模型**（对照基线 checkpoin）与"这次判断有多好"的共同尺子：
    准确率只有一个 0/1，分不出"错得离谱"和"错在临门一脚"。
    """
    import torch
    from laya.common import QTYPES, proper_reward

    k = len(p)
    q = torch.tensor([[list(map(float, p))]], dtype=torch.float32)
    target = torch.tensor([list(map(float, g))], dtype=torch.float32)
    qtype_t = torch.tensor([QTYPES[qtype]], dtype=torch.long)
    mask = torch.ones((1, k), dtype=torch.float32)
    return float(proper_reward(q, target, qtype_t, mask)[0, 0].item())


# ---------------------------------------------------------------- 跑一批样本


def evaluate_items(agent, items: Sequence[Dict[str, Any]], tag: str,
                   rank: int = 0, world: int = 1) -> List[QRecord]:
    """逐条样本跑一次前向，返回逐题判分材料。

    一条样本的几道题**合在同一个批次里**喂给 `system_one`——laya 的设计就是"一次前向答完
    一条样本的全部题"，逐题调用会把这份优势白白丢掉。

    单条样本推理失败**整体失败**（`SystemExit`）而不是跳过：跳过换来的是"分母变小的报告"，
    它看起来只是"这次样本少一点"，却会让人拿两次不同分母的准确率互相比。
    """
    if world > 1 and rank != 0:
        # 报告只在 rank 0 出，其余 rank 直接去训练：验证走的是**未包装**的模型，
        # 没有集合通信，所以 rank 0 独自多跑一会儿不会把别的 rank 卡死
        return []

    records: List[QRecord] = []
    for index, item in enumerate(items, start=1):
        item_id = int(item["id"])
        case_group = item.get("caseGroup") or "(未分组)"
        questions = item.get("questions") or {}
        golds = item.get("goldDistribution") or {}
        qids = scorable_questions(item)
        if not qids:
            # 没有一题能对上 gold = 这条样本对指标毫无贡献。服务端打包时已经提示过（warnings），
            # 这里只记一行——因为它不影响分母（分母是"能算的题"）
            log("[%s] 样本 #%d 没有可判分的题（gold 落不上题面），跳过" % (tag, item_id))
            emit(MARKER_ITEM, {"id": item_id, "caseGroup": case_group, "questions": 0,
                               "correct": 0, "skipped": True})
            continue
        asked = {qid: questions[qid] for qid in qids}
        started = time.time()
        try:
            answer = agent.system_one(item.get("state"), asked)
        except Exception as exc:      # noqa: BLE001
            raise SystemExit("[%s] 样本 #%d 推理失败：%s: %s\n"
                             "（宁可不跑，也不要一份分母变小的报告）"
                             % (tag, item_id, type(exc).__name__, exc))
        elapsed_ms = (time.time() - started) * 1000.0 / max(1, len(qids))
        answers = answer.get("answers") or {}
        correct = 0
        for qid in qids:
            if qid not in answers:
                raise SystemExit("[%s] 样本 #%d 的题 %r 没有应答（模型跳过了它？）" % (tag, item_id, qid))
            keys, p = answer_to_distribution(answers[qid], questions[qid], list(golds[qid].keys()))
            g = gold_to_distribution(golds[qid], keys)
            rec = QRecord(item_id, case_group, qid, (questions[qid].get("type") or "").strip(),
                          keys, p, g, answers[qid].get("confidence", 0.0), elapsed_ms)
            records.append(rec)
            correct += 1 if rec.correct else 0
        emit(MARKER_ITEM, {"id": item_id, "caseGroup": case_group, "questions": len(qids),
                           "correct": correct})
        log("[%s] %d/%d 样本 #%d [%s] %d 题 对 %d" % (tag, index, len(items), item_id, case_group,
                                                      len(qids), correct))
    return records


# ---------------------------------------------------------------- 指标聚合


def aggregate(records: Sequence[QRecord]) -> Dict[str, Any]:
    """按题面类型分块聚合指标。

    **缺项不编数**：没有 score 题就不出现 `score` 块（而不是 `mae: 0.0`）——
    "0 误差"与"没测"必须是两回事。
    """
    metrics: Dict[str, Any] = {
        "items": len({r.item_id for r in records}),
        "questions": len(records),
        "latencyMs": percentile_block([r.latency_ms for r in records]),
    }
    for qtype in ("choice", "score", "noul"):
        block = [r for r in records if r.qtype == qtype]
        if not block:
            continue
        entry: Dict[str, Any] = {"questions": len(block)}
        conf = [r.confidence for r in block]
        correct = [1.0 if r.correct else 0.0 for r in block]
        entry["accuracy"] = mean(correct)
        entry["avgConfidence"] = mean(conf)
        entry["ece"] = ece(conf, correct)
        # 软准确率：预测分布与 gold 分布的内积。gold 是硬标签时它就退化成"押中的概率质量"，
        # 比准确率多出来的那部分信息正是"差一点"与"差很远"的区别
        entry["softAccuracy"] = mean([sum(a * b for a, b in zip(r.p, r.g)) for r in block])
        entry["brier"] = mean([sum((a - b) ** 2 for a, b in zip(r.p, r.g)) for r in block])
        if qtype in ("choice", "noul"):
            entry["answered"] = {r.pred_label: sum(1 for x in block if x.pred_label == r.pred_label)
                                 for r in block}
        if qtype == "score":
            entry["mae"] = mean([abs(expectation(r.p) - expectation(r.g)) for r in block])
            entry["within1Level"] = mean([
                1.0 if abs(round(expectation(r.p)) - round(expectation(r.g))) <= 1 else 0.0
                for r in block])
        if qtype == "noul":
            entry["noulRate"] = mean([r.p[1] for r in block])
        metrics[qtype] = entry
    return metrics


def baselines(records: Sequence[QRecord]) -> Dict[str, Any]:
    """FR-03 的两条基线，**必须始终报**：没有它们，"准确率 0.72" 读不出好坏。

    - `random`：每题均匀瞎猜（1/选项数）的平均值——这是"什么都不做"的下限；
    - `majority`：**按题**取该题在所有样本里最常见的 gold 标签来答。
      跨题取全局多数类是错的：不同题的标签空间不同（"重复/采纳"与"是/否"），
      把它们并成一个多数类，得到的数字与"答对这一题"无关。
    """
    if not records:
        return {"random": 0.0, "majority": 0.0}
    random_acc = mean([1.0 / max(1, len(r.keys)) for r in records])
    per_question: Dict[str, List[str]] = {}
    for r in records:
        per_question.setdefault(r.question, []).append(r.gold_label)
    hit = 0
    for r in records:
        labels = per_question[r.question]
        top = max(set(labels), key=lambda lbl: (labels.count(lbl), lbl))
        hit += 1 if r.gold_label == top else 0
    return {"random": random_acc, "majority": hit / len(records)}


def by_case_group(records: Sequence[QRecord]) -> List[Dict[str, Any]]:
    """对照组分解。对照组是这套评测集存在的理由（CAP-55 那次退化正是全判「重复」），
    所以它必须在报告里有独立的一行，而不是混进总准确率里被平均掉。"""
    groups: Dict[str, List[QRecord]] = {}
    for r in records:
        groups.setdefault(r.case_group or "(未分类)", []).append(r)
    out = []
    for name, block in groups.items():
        out.append({
            "caseGroup": name,
            "items": len({r.item_id for r in block}),
            "questions": len(block),
            "accuracy": mean([1.0 if r.correct else 0.0 for r in block]),
        })
    return sorted(out, key=lambda x: x["caseGroup"])


def percentile_block(values: Sequence[float]) -> Dict[str, float]:
    if not values:
        return {}
    ordered = sorted(values)
    return {"p50": round(pick(ordered, 0.50), 1), "p95": round(pick(ordered, 0.95), 1)}


def pick(ordered: Sequence[float], q: float) -> float:
    if not ordered:
        return 0.0
    idx = min(len(ordered) - 1, max(0, int(round(q * (len(ordered) - 1)))))
    return float(ordered[idx])


def expectation(p: Sequence[float]) -> float:
    """score 题的期望等级：Σ i·p_i（0 基）。与 laya 应答里的 `score` 是同一个取法。"""
    return sum(i * v for i, v in enumerate(p))


def mean(values: Iterable[float]) -> float:
    vals = list(values)
    return float(sum(vals) / len(vals)) if vals else 0.0


def ece(conf: Sequence[float], correct: Sequence[float]) -> float:
    """直接用官方的 `laya.common.ece_score`（15 桶）。空集返回 NaN → 落成 None。"""
    import numpy as np
    from laya.common import ece_score

    if not conf:
        return 0.0
    value = float(ece_score(np.asarray(conf, dtype=float), np.asarray(correct, dtype=float)))
    return 0.0 if value != value else value      # NaN → 0（没有样本就不该走到这里）


# ---------------------------------------------------------------- 温度校准


def fit_temperature(records: Sequence[QRecord], fit_ids: Sequence[int],
                    min_samples: int = 8) -> Dict[str, float]:
    """按 `(题面类型, 选项数桶)` 拟合温度：grid search 最小化 ECE。

    只在该桶样本数 >= `min_samples` 时才拟合——三五条样本上把 ECE 调低，
    调出来的是噪声，而它会被当成"校准过了"写进 checkpoint 的元数据里。

    温度的语义：`p ∝ p_raw^(1/t)`（等价于对 log-prob 重新缩放）。t>1 变软、t<1 变尖，
    边界由 `laya.common.TEMP_MIN/TEMP_MAX` 钉死（超出那个范围就不是校准是锐化了）。
    """
    from laya.common import QTYPES, temp_bucket

    fit_set = set(fit_ids)
    buckets: Dict[str, List[QRecord]] = {}
    for r in records:
        if r.item_id in fit_set:
            buckets.setdefault(temp_bucket(QTYPES[r.qtype], len(r.keys)), []).append(r)

    grid = [TEMP_MIN + (TEMP_MAX - TEMP_MIN) * i / (TEMP_GRID - 1) for i in range(TEMP_GRID)]
    out: Dict[str, float] = {}
    for bucket, block in buckets.items():
        if len(block) < min_samples:
            continue
        correct = [1.0 if r.correct else 0.0 for r in block]
        best_t, best_ece = 1.0, None
        for t in grid:
            e = ece([rescaled_confidence(r.p, t, r.qtype) for r in block], correct)
            if best_ece is None or e < best_ece - 1e-12:
                best_t, best_ece = t, e
        out[bucket] = round(best_t, 4)
    return out


def rescaled_confidence(p: Sequence[float], temperature: float, qtype: str = "choice") -> float:
    """在温度 t 下重新计算置信度（规则与 laya 对同一分布报出来的那把尺子一致）。

    **尺子必须按题面类型分开**：choice/score 报的是归一化熵置信度，
    而 noul 报的是 `max(p_true, 1-p_true)`（见 `Agent.system_one`）。混用这两把尺子，
    ECE 的 before/after 比的就是两个不同定义的量——数字会动，但动的原因不是校准。

    温度只影响置信度不影响 argmax（单调变换），所以逐题对错在拟合前后不变。
    """
    import math

    from laya.common import confidence_from_probs

    logs = [math.log(max(float(v), 1e-12)) / max(temperature, 1e-6) for v in p]
    top = max(logs)
    exps = [math.exp(v - top) for v in logs]
    total = sum(exps)
    q = [v / total for v in exps]
    if qtype == "noul":
        return float(max(q[-1], 1.0 - q[-1]))
    return float(confidence_from_probs(q, len(q)))


def apply_buckets(records: Sequence[QRecord], buckets: Dict[str, float],
                  eval_ids: Sequence[int]) -> Tuple[float, float]:
    """在 `eval_ids` 这些题上算校准前后 ECE（同一批样本，唯一变量是温度）。"""
    from laya.common import QTYPES, temp_bucket

    eval_set = set(eval_ids)
    block = [r for r in records if r.item_id in eval_set]
    if not block:
        return 0.0, 0.0
    correct = [1.0 if r.correct else 0.0 for r in block]
    before = ece([r.confidence for r in block], correct)
    after = ece([rescaled_confidence(r.p, buckets.get(
        temp_bucket(QTYPES[r.qtype], len(r.keys)), 1.0), r.qtype) for r in block], correct)
    return before, after


def split_for_calibration(records: Sequence[QRecord], seed: int = 20260922) -> Tuple[List[int], List[int]]:
    """把样本（**按 item 而不是按题**）对半切开：一半拟合温度、一半算校准前后的 ECE。

    按 item 切是因为同一条样本的几道题来自同一段输入，相关性很强；
    按题切会让同一段输入的两道题分别落进拟合集与评测集，held-out 就成了自欺。
    """
    import random

    ids = sorted({r.item_id for r in records})
    random.Random(seed).shuffle(ids)
    half = len(ids) // 2
    return ids[:half] or ids, ids[half:] or ids


# ---------------------------------------------------------------- 产物指纹


def fingerprint(path: str) -> Dict[str, Any]:
    """产物的指纹：**目录级**摘要（相对路径 + 每个文件内容的 sha256）+ 总字节数。

    权重是一个目录（config + tokenizer + encoder + safetensors），只对 safetensors 取哈希
    会漏掉"换了 tokenizer""改了温度校准"这类同样改变行为的改动；而只报一个文件名又不足以
    证明"这份产物就是那次训练的结果"。目录级摘要两者都说得清。
    """
    root = Path(path)
    if not root.exists():
        raise SystemExit("产出目录不存在（训练没写出东西？）: %s" % root)
    files = sorted(p for p in root.rglob("*") if p.is_file())
    if not files:
        raise SystemExit("产出目录里没有文件: %s" % root)
    h = hashlib.sha256()
    total = 0
    for p in files:
        data = p.read_bytes()
        h.update(p.relative_to(root).as_posix().encode("utf-8"))
        h.update(b"\0")
        h.update(hashlib.sha256(data).digest())
        total += len(data)
    return {"path": str(root), "bytes": total, "sha256": h.hexdigest(), "files": len(files)}


# ---------------------------------------------------------------- 报告


def compare_block(records: Sequence[QRecord], base_records: Sequence[QRecord]) -> Dict[str, Any]:
    """与对照 checkpoint 的逐题胜负：用官方 proper_reward 比"这一题谁判得更好"。

    只看准确率的话，"双方都对"与"双方都错"会混成一个数字，而这两件事的改进含义完全不同。
    """
    index = {(r.item_id, r.question): r for r in base_records}
    win = lose = tie = 0
    for r in records:
        other = index.get((r.item_id, r.question))
        if other is None:
            continue
        mine = reward_of(r.p, r.g, r.qtype)
        theirs = reward_of(other.p, other.g, other.qtype)
        if abs(mine - theirs) < 1e-6:
            tie += 1
        elif mine > theirs:
            win += 1
        else:
            lose += 1
    return {"win": win, "lose": lose, "tie": tie}
