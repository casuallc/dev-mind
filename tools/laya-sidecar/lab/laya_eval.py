"""CAP-56 FR-03：在节点上跑一次评测，把官方口径的指标 + 两条基线 + 逐题明细报回服务端。

用法（由服务端渲染的命令行调用，人也可以手工跑一次排查）：

    python laya_eval.py --payload <payload.json> --checkpoint <权重目录> --slot typed-decisions \
        [--out <产出目录>] [--baseline-checkpoint <对照权重目录>] [--fit-temperature]

**这次运行要回答的问题不是"准确率多少"**，而是"这个判断有多可信、比瞎猜好在哪"。所以：
- 两条基线（随机 / 多数类）**永远报**——没有它们，"0.72" 读不出好坏；
- 逐题明细与对照组分解**永远报**——2026-09-22 那次退化（三组对照全判「重复」）在总准确率上
  看着只是"偏低"，只有按对照组摊开才看得见"它对空召回也答重复"；
- 证据（每题答了什么、gold 是什么、置信度多少）逐题落进报告，服务端把它存下来。

`--fit-temperature` 用**按样本对半切开**的 held-out 方式拟合温度：一半样本拟合、另一半算校准
前后的 ECE。在同一批样本上既拟合又评估，ECE 一定变好看，而那个改进是假的。
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any, Dict, List

from _rl_common import (QRecord, aggregate, apply_buckets, baselines, by_case_group, compare_block,
                        emit, evaluate_items, fit_temperature, load_agent, load_payload, log,
                        MARKER_REPORT, seal, setup_stdout, split_for_calibration)


def parse_args() -> argparse.Namespace:
    p = argparse.ArgumentParser(description="laya 决策模型评测（CAP-56 FR-03）")
    p.add_argument("--payload", help="执行包数据文件（默认取 DEVMIND_LAB_PAYLOAD）")
    p.add_argument("--checkpoint", required=True, help="被评测的 checkpoint 目录（节点上的本地路径）")
    p.add_argument("--slot", help="该 checkpoint 服务的槽位名（只进报告，用于回溯）")
    p.add_argument("--out", help="产出目录（可选：写下 calibration.json 供人工核对）")
    p.add_argument("--baseline-checkpoint", help="对照 checkpoint 目录（给了就出逐题胜负）")
    p.add_argument("--baseline-slot", help="对照 checkpoint 的槽位名")
    p.add_argument("--device", help="cpu / cuda / cuda:0（默认按环境自动探测）")
    p.add_argument("--fit-temperature", action="store_true",
                   help="按 (题面类型,选项数桶) 拟合温度并报校准前后 ECE（held-out）")
    return p.parse_args()


def main() -> None:
    setup_stdout()
    args = parse_args()
    payload = load_payload(args.payload)
    items = payload.get("items") or []
    if not items:
        raise SystemExit("执行包里没有样本（数据集是空的？）")

    checkpoint = payload.get("checkpoint") or {}
    dataset = payload.get("dataset") or {}
    log("[评测] 数据集「%s v%s」%d 条样本 · checkpoint「%s」槽位 %s"
        % (dataset.get("name"), dataset.get("version"), len(items),
           checkpoint.get("name") or args.checkpoint, args.slot or checkpoint.get("serveSlot")))

    agent = load_agent(args.checkpoint, args.device)
    log("[评测] 模型已加载，设备 %s" % getattr(agent, "device", "unknown"))
    records = evaluate_items(agent, items, "评测")

    if not records:
        raise SystemExit("没有任何一题能和 gold 对上（没有可判分的题）：这份评测集没法给出指标")

    subject = aggregate(records)
    result = {
        "schemaVersion": 1,
        "kind": "evaluation",
        "checkpoint": {"id": checkpoint.get("id"), "name": checkpoint.get("name"),
                       "slot": args.slot or checkpoint.get("serveSlot"),
                       "path": args.checkpoint},
        "dataset": dataset,
        "metrics": subject,
        "baselines": baselines(records),
        "byCaseGroup": by_case_group(records),
        "perItem": [r.row() for r in records],
        "layaVersion": _laya_version(),
    }

    if args.baseline_checkpoint:
        log("[对照] 加载基线 checkpoint: %s" % args.baseline_checkpoint)
        base_agent = load_agent(args.baseline_checkpoint, args.device)
        base_records = evaluate_items(base_agent, items, "对照")
        result["compare"] = dict(compare_block(records, base_records),
                                 baselineCheckpoint=(payload.get("baseCheckpoint") or {}).get("name")
                                 or args.baseline_checkpoint,
                                 baselineSlot=args.baseline_slot
                                 or (payload.get("baseCheckpoint") or {}).get("serveSlot"),
                                 baselineMetrics=aggregate(base_records))

    if args.fit_temperature:
        result["calibration"] = calibrate(records, args.out)

    result["warnings"] = list(payload.get("warnings") or [])
    emit(MARKER_REPORT, seal(**result))

    log("[评测] 结果：准确率 %s · 随机基线 %s · 多数类基线 %s"
        % (_fmt(subject.get("choice", {}).get("accuracy", subject.get("noul", {}).get("accuracy"))),
           _fmt(result["baselines"].get("random")), _fmt(result["baselines"].get("majority"))))
    for row in result["byCaseGroup"]:
        log("[评测] 对照组 %-14s %d 条 %d 题 准确率 %s"
            % (row["caseGroup"], row["items"], row["questions"], _fmt(row["accuracy"])))
    if "compare" in result:
        log("[对照] 胜 %d / 负 %d / 平 %d" % (result["compare"]["win"], result["compare"]["lose"],
                                             result["compare"]["tie"]))
    log("[评测] 完成")


def calibrate(records: List[QRecord], out_dir) -> Dict[str, Any]:
    """温度校准：按样本对半切开，一半拟合、一半评估（held-out）。"""
    fit_ids, eval_ids = split_for_calibration(records)
    buckets = fit_temperature(records, fit_ids)
    before, after = apply_buckets(records, buckets, eval_ids)
    full_before, full_after = apply_buckets(records, buckets, [r.item_id for r in records])
    doc: Dict[str, Any] = {
        "mode": "heldout",
        "fitItems": len(fit_ids),
        "evalItems": len(eval_ids),
        "before": {"ece": round(before, 6)},
        "after": {"ece": round(after, 6)},
        "allItems": {"before": {"ece": round(full_before, 6)},
                     "after": {"ece": round(full_after, 6)}},
        "temperature": buckets,
    }
    if not buckets:
        doc["note"] = ("没有任何 (题面类型,选项数桶) 达到拟合门槛（每桶至少 8 题）："
                       "样本太少时调低 ECE 调的是噪声，而它会被当成『校准过了』")
    log("[校准] held-out %d/%d 样本 · ECE %s → %s · 温度 %s"
        % (len(fit_ids), len(eval_ids), _fmt(before), _fmt(after), buckets or "（未拟合）"))
    if out_dir:
        target = Path(out_dir)
        target.mkdir(parents=True, exist_ok=True)
        (target / "calibration.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2),
                                                encoding="utf-8")
        log("[校准] 已写入 %s" % (target / "calibration.json"))
    return doc


def _laya_version() -> str:
    try:
        import laya
        return getattr(laya, "__version__", "unknown")
    except Exception:      # noqa: BLE001 —— 版本只进报告，取不到不该让评测失败
        return "unknown"


def _fmt(value) -> str:
    return "—" if value is None else ("%.4f" % float(value))


if __name__ == "__main__":
    main()
