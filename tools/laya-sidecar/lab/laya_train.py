"""CAP-56 FR-05：在节点上做一次 RLCD 微调，权重留在节点，只回传指标与指纹。

用法（由服务端渲染的命令行调用，人也可以手工跑一次排查）：

    python laya_train.py --payload <payload.json> --base-checkpoint <基座目录> --slot typed-decisions \
        --out <产出目录> --epochs 3 --lr 1e-4 --batch 8 --seed 42 [--launcher "torchrun --nproc_per_node=2"]

**为什么权重不出节点**：几百 MB～几 GB 的权重穿一遍 exec 日志/HTTP 不现实，也没有地方落。
服务端只收回两样东西——**指标**（这次训得怎么样）与**指纹**（产出的目录级 sha256，
见 `_rl_common.fingerprint`）。产物目录留在节点上，由边车按槽位加载。

**算法（RLCD 的移植）**：官方训练脚本不在 pip 包里，只有 `laya.common` 的原语可用。
这里用 `build_sequence`（随机选项顺序做增广）+ `collate_items` + 前向出 logits + 高斯噪声探索，
奖励取**官方 `proper_reward`**（log + spherical + RPS）作用在"采样到的那个决策"上，
优势减组内均值（GRPO 式）做 REINFORCE。方向是明确的：让报出来的分布在这条 strictly proper
scoring rule 下更好——它同时管住了"猜得准"与"说得有多自信"。

**默认冻结编码器**（`--train-encoder` 才放开）：这个平台上的训练集是几十到几百条，
把 322M 的编码器一起放开训，几轮就能把它训坏，而"训坏了"在指标上只表现为"稍微差一点"，
不一定看得出来。冻结编码器只训决策头，是这份数据量下唯一稳的做法。

**收敛性只能靠真机验证**（CAP-56 风险清单第 2 条）：所以脚本在训练前后各评一遍验证集，
报告里两个指标都报，并给出与基座的逐题胜负。退化会被显式暴露，而不是静默通过。
"""
from __future__ import annotations

import argparse
import json
import math
import os
import random
import shlex
import shutil
import subprocess
import sys
import time
import zlib
from pathlib import Path
from typing import Any, Dict, List, Sequence

sys.path.insert(0, str(Path(__file__).resolve().parent))

from _rl_common import (aggregate, apply_buckets, baselines, by_case_group, compare_block, emit,
                        evaluate_items, fingerprint, fit_temperature, gold_to_distribution,
                        load_agent, load_payload, log, MARKER_FINGERPRINT, MARKER_REPORT, NOUL_KEYS,
                        seal, setup_stdout, split_for_calibration)

LAUNCHED_ENV = "DEVMIND_LAB_LAUNCHED"


def parse_args(argv: Sequence[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description="laya RLCD 微调（CAP-56 FR-05）")
    p.add_argument("--payload", help="执行包数据文件（默认取 DEVMIND_LAB_PAYLOAD）")
    p.add_argument("--base-checkpoint", required=True, help="基座 checkpoint 目录（节点上的本地路径）")
    p.add_argument("--slot", help="产出要服务的槽位名（沿用登记时的槽位，只换来源不改名字）")
    p.add_argument("--out", required=True, help="产出目录（新 checkpoint 写在这里；必须与基座不同）")
    p.add_argument("--epochs", type=int, default=3, help="训练集完整过几遍")
    p.add_argument("--lr", type=float, default=1e-4)
    p.add_argument("--batch", type=int, default=8, help="每个优化步的题数（不是采样数）")
    p.add_argument("--seed", type=int, default=42)
    p.add_argument("--group-size", type=int, default=4, help="同一批题每次采样的决策数（GRPO 的组）")
    p.add_argument("--exploration", type=float, default=0.5, help="加到 logits 上的高斯噪声标准差")
    p.add_argument("--temperature", type=float, default=1.0,
                   help="采样温度（探索用；服务温度在训练结束时按验证集重新拟合）")
    p.add_argument("--max-steps", type=int, default=0, help="限制优化步数（0 = 不限；冒烟用）")
    p.add_argument("--train-encoder", action="store_true",
                   help="连编码器一起训（默认不放开：几十条样本几轮就能把基座训坏）")
    p.add_argument("--device", help="cpu / cuda / cuda:0（默认自动探测）")
    p.add_argument("--launcher", help="多卡启动前缀，如 'torchrun --nproc_per_node=2'（由本脚本自行拉起）")
    return p.parse_args(list(argv))


def validate(args: argparse.Namespace) -> None:
    """拦下那些"能跑完、但什么都没发生"的参数组合。

    这类配置最贵：它不报错，跑完还打一份指标——而那份指标只是"没训练"的模型。
    """
    if args.group_size < 2:
        raise SystemExit("--group-size 必须 >= 2：组内均值是优势的基线，组里只有一个样本时"
                         "优势恒为 0，训练会跑完而一个参数都没动")
    if args.epochs < 1 and not args.max_steps:
        raise SystemExit("--epochs 至少 1（或给 --max-steps）：跑 0 步会「成功」产出"
                         "一份与基座完全相同的 checkpoint")
    if args.lr <= 0:
        raise SystemExit("--lr 必须为正")


def relaunch_if_needed(args: argparse.Namespace, raw_argv: Sequence[str]) -> None:
    """`--launcher` 的处理：把**自己**再拉一次，前面挂上 torchrun。

    为什么不把 launcher 直接当命令的首 token：runner 逐行取首 token 校验 `execAllowlist`，
    首 token 换成 `torchrun` 就意味着白名单要同时放行两套命令，"哪条命令能跑"从一处变成两处。
    所以服务端渲染的命令永远以 python 开头，多卡由脚本自己拉起来。
    """
    if not args.launcher:
        return
    if os.environ.get(LAUNCHED_ENV) == "1":
        return                      # 已经在 launcher 下面了，别再套一层（会无限递归）
    cmd = shlex.split(args.launcher)
    if not cmd:
        raise SystemExit("--launcher 是空的")
    if "--launcher" in raw_argv:
        at = raw_argv.index("--launcher")
        passthrough = [a for i, a in enumerate(raw_argv) if i not in (at, at + 1)]
    else:
        passthrough = [a for a in raw_argv if not a.startswith("--launcher=")]
    child = cmd + [sys.executable, str(Path(__file__).resolve())] + passthrough
    log("[多卡] 以 %s 启动：%s" % (args.launcher, " ".join(shlex.quote(c) for c in child)))
    env = dict(os.environ, **{LAUNCHED_ENV: "1"})
    # 子进程继承 stdout：marker 行由子进程打印，服务端抓的是同一条流
    raise SystemExit(subprocess.call(child, env=env))


# ---------------------------------------------------------------- 训练题


class Task:
    """一道训练题：某条样本的某个题面 + 它的 gold 分布（规范顺序）。"""

    __slots__ = ("item_id", "case_group", "qid", "qtype", "keys", "gold", "state", "internal")

    def __init__(self, item: Dict[str, Any], qid: str, qdef: Dict[str, Any], gold: Dict[str, Any],
                 internal: Dict[str, Any]):
        self.item_id = int(item["id"])
        self.case_group = item.get("caseGroup") or "(未分组)"
        self.qid = qid
        self.qtype = (qdef.get("type") or "").strip()
        self.keys = NOUL_KEYS if self.qtype == "noul" else list(gold.keys())
        self.gold = gold_to_distribution(gold, self.keys)
        self.state = item.get("state")
        self.internal = internal


def to_internal(qdef: Dict[str, Any]) -> Dict[str, Any]:
    """题面 → `build_sequence` 认的内部形态。

    这是 `laya.Agent._to_internal` 的镜像（8 行）：训练侧要自己拼序列就必须拿到内部形态，
    而那个方法是私有的。镜像的代价是"题面口径"多了一处——但它只可能以**当场报错**的形式暴露
    （marker 数与选项数不等时脚本停下来），不会静默训出另一套题面。
    """
    t = qdef.get("type")
    crit = qdef.get("criteria")
    if t == "choice" and isinstance(crit, list):
        crit = {c: None for c in crit}
    ins = qdef.get("instructions")
    if not isinstance(ins, str):
        ins = json.dumps(ins, ensure_ascii=False)
    return {"t": t, "ins": ins, "crit": crit}


def tasks_of(items: Sequence[Dict[str, Any]]) -> List[Task]:
    out: List[Task] = []
    for item in items:
        questions = item.get("questions") or {}
        golds = item.get("goldDistribution") or {}
        for qid, gold in golds.items():
            qdef = questions.get(qid)
            if not qdef:
                continue
            out.append(Task(item, qid, qdef, gold, to_internal(qdef)))
    return out


# ---------------------------------------------------------------- 训练


def train(agent, tasks: List[Task], args: argparse.Namespace, world: int, rank: int) -> Dict[str, Any]:
    """跑完整个训练；返回训练段落（进报告的 `train`）。"""
    import torch
    from laya.common import QTYPES, build_sequence, clamp_temperature, collate_items, proper_reward

    core = agent.model
    params = trainable_params(core, args.train_encoder)
    log("[微调] 可训练参数 %.2fM / 全部 %.2fM（编码器%s）"
        % (sum(p.numel() for p in params) / 1e6,
           sum(p.numel() for p in core.parameters()) / 1e6,
           "已放开" if args.train_encoder else "冻结——这份数据量下放开它是把基座训坏的最快路径"))

    model = core
    if world > 1:
        import torch.nn as nn
        model = nn.parallel.DistributedDataParallel(core)
        agent.model = model

    opt = torch.optim.AdamW(params, lr=args.lr, weight_decay=0.0)
    my_tasks = tasks[rank::world] if world > 1 else list(tasks)
    per_step = max(1, args.batch)
    steps_per_epoch = max(1, math.ceil(len(my_tasks) / per_step))
    total_steps = steps_per_epoch * max(1, args.epochs)
    if args.max_steps:
        total_steps = min(total_steps, args.max_steps)
    warmup = max(1, int(total_steps * 0.05))
    sched = torch.optim.lr_scheduler.LambdaLR(opt, lambda s: min(1.0, (s + 1) / warmup))

    cfg = agent.cfg
    h = dict(QTYPES=QTYPES, build_sequence=build_sequence, collate_items=collate_items,
             proper_reward=proper_reward, tok=agent.tok,
             temp=clamp_temperature(args.temperature),
             max_len=cfg.get("max_len", 512), head_max_len=cfg.get("head_max_len", 192),
             pad_id=agent.tok.pad_token_id)

    started = time.time()
    step = 0
    final_loss = None
    rewards: List[float] = []
    order = list(my_tasks)
    model.train()
    while step < total_steps:
        random.Random(args.seed * 7919 + step).shuffle(order)
        for i in range(0, len(order), per_step):
            if step >= total_steps:
                break
            loss, mean_reward = reinforce_step(model, order[i:i + per_step], args, h, step)
            opt.zero_grad(set_to_none=True)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(params, 1.0)
            opt.step()
            sched.step()
            step += 1
            final_loss = float(loss.item())
            rewards.append(mean_reward)
            if step == 1 or step % 5 == 0 or step == total_steps:
                log("[微调] step %d/%d loss %.4f 组内平均奖励 %.4f lr %.3g"
                    % (step, total_steps, final_loss, mean_reward, sched.get_last_lr()[0]))

    # 交还未包装的模块：验证与存盘都不该走 DDP（包装器只服务于训练时的集合通信）
    agent.model = core
    core.eval()
    log("[微调] 训练结束：%d 步 %.1fs 末步 loss %s"
        % (step, time.time() - started, "%.4f" % final_loss if final_loss is not None else "—"))
    return {
        "steps": step,
        "epochs": args.epochs,
        "questions": len(tasks),
        "items": len({t.item_id for t in tasks}),
        "batch": per_step,
        "groupSize": max(1, args.group_size),
        "exploration": args.exploration,
        "temperature": h["temp"],
        "learningRate": args.lr,
        "seed": args.seed,
        "finalLoss": final_loss,
        "meanReward": _avg(rewards),
        "lastReward": rewards[-1] if rewards else None,
        "durationSec": round(time.time() - started, 1),
        "encoderFrozen": not args.train_encoder,
        "worldSize": world,
    }


def trainable_params(core, train_encoder: bool) -> List[Any]:
    """要优化的参数。

    冻结是把 `requires_grad` 关掉，而不是在 forward 里 detach——前者连那部分激活都不留，
    后者只是不算梯度，显存照花。
    """
    if not train_encoder:
        for p in core.encoder.parameters():
            p.requires_grad = False
    return [p for p in core.parameters() if p.requires_grad]


def reinforce_step(model, batch: List[Task], args: argparse.Namespace, h: Dict[str, Any], step: int):
    """一个优化步：组内采 G 个决策，按 proper_reward 算奖励，减组均值当优势。

    **奖励为什么必须落在"采样到的那个决策"上**：把整条分布的得分当奖励，它与采样无关，
    `E[∇log π(a)·r]` 里 r 是常数、`E[∇log π(a)] = 0`——梯度恒为零，训练什么也不会发生。
    奖励取 `proper_reward(采样到的那个决策, gold)`：它衡量的是"你实际做出的这个判断有多好"。
    """
    import torch

    seqs, orders, qtypes, targets = [], [], [], []
    for t in batch:
        order = list(range(len(t.keys)))
        if t.qtype != "noul":
            # 选项顺序增广只做 choice/score：noul 的 false/true 是有方向的两端，
            # 交换它换的不是"顺序"，而是另一道题。
            # 顺序随**优化步**变（同一个优化步里的 G 个样本共用一份题面）：同一步内要比的是
            # "同一道题上不同决策谁更好"，题面变了就不是一次可比的比较
            random.Random(args.seed * 1000003 + step * 97 + t.item_id * 31
                          + zlib.crc32(t.qid.encode())).shuffle(order)
        seq, markers = h["build_sequence"](h["tok"], t.state, t.internal, h["max_len"],
                                          h["head_max_len"], option_order=order)
        if len(markers) != len(t.keys):
            raise SystemExit("样本 #%d 的题 %s 拼出的选项数 %d ≠ 题面选项数 %d"
                             "（题面与执行包不一致，先别训）"
                             % (t.item_id, t.qid, len(markers), len(t.keys)))
        seqs.append({"ids": seq, "markers": markers, "qtype": h["QTYPES"][t.qtype]})
        orders.append(order)
        qtypes.append(h["QTYPES"][t.qtype])
        targets.append(t.gold)

    b = h["collate_items"]([seqs], h["pad_id"])
    kmax = b["marker_mask"].size(1)
    mask = b["marker_mask"]
    target = torch.zeros((len(batch), kmax))
    for n, gold in enumerate(targets):
        target[n, :len(gold)] = torch.tensor(gold, dtype=torch.float32)
    qtype_t = torch.tensor(qtypes, dtype=torch.long)

    logps, probs = [], []
    for _ in range(max(1, args.group_size)):
        logits = forward(model, b)
        canon = unpermute(logits, orders, kmax)
        z = (canon + torch.randn_like(canon) * args.exploration) / h["temp"]
        z = z.masked_fill(~mask, -1e4)
        logp = torch.log_softmax(z, dim=-1)
        # 采样用的分布要 detach：奖励是环境给的，不该顺着它把梯度也带回采样本身
        p = torch.softmax(z.detach(), dim=-1)
        action = torch.multinomial(p.clamp_min(1e-12), 1).squeeze(1)
        logps.append(logp.gather(1, action[:, None]).squeeze(1))
        probs.append(p)

    q_stack = torch.stack(probs)                                          # [G, N, K]
    reward = h["proper_reward"](q_stack, target, qtype_t, mask.float())   # [G, N]
    advantage = reward - reward.mean(dim=0, keepdim=True)
    advantage = advantage / reward.std(dim=0, keepdim=True).clamp_min(1e-6)
    loss = -(advantage.detach() * torch.stack(logps)).mean()
    return loss, float(reward.mean().item())


def forward(model, batch):
    device = batch["input_ids"].device
    logits, _ = model(batch["input_ids"].to(device), batch["attention_mask"].to(device),
                      batch["marker_pos"].to(device), batch["marker_mask"].to(device),
                      batch["qtype"].to(device))
    return logits.float()


def unpermute(logits, orders: Sequence[Sequence[int]], kmax: int):
    """把"按给定选项顺序"的 logits 摆回题面顺序。

    奖励的 gold 分布是题面顺序的，位置对不上就等于拿别人的答案给自己打分——
    而它不会报错，只会让训练朝一个随机方向走。
    """
    import torch

    out = torch.full_like(logits, -1e4)
    for n, order in enumerate(orders):
        for pos, idx in enumerate(order):
            out[n, idx] = logits[n, pos]
    return out


# ---------------------------------------------------------------- 产物

# safetensors 表头里的 dtype 名 → torch 的 dtype 名（torch 没有 f16 这种别名，只能查表）
SAFETENSOR_DTYPES = {"F64": "float64", "F32": "float32", "F16": "float16", "BF16": "bfloat16",
                     "I64": "int64", "I32": "int32", "I16": "int16", "I8": "int8",
                     "U8": "uint8", "BOOL": "bool"}


def base_dtypes(path: Path) -> Dict[str, str]:
    """读基座权重文件每个张量的 dtype（**只读表头**，不加载那几百 MB）。

    为什么要读：基座是混精度的（编码器 F16、头与 buffer F32），全按 fp32 存出来
    体积翻倍（322M 参数 = 1.3G 而不是 643M），而且产物与基座不同构——加载能成功，
    但"这份产物是照着哪个基座训的"就得靠配置文件去猜。
    """
    import struct

    with path.open("rb") as f:
        size = struct.unpack("<Q", f.read(8))[0]
        header = json.loads(f.read(size).decode("utf-8"))
    return {k: v["dtype"] for k, v in header.items() if k != "__metadata__"}


def save_checkpoint(base: Path, out: Path, model, args: argparse.Namespace, buckets: Dict[str, float],
                    payload: Dict[str, Any], train_info: Dict[str, Any],
                    val_ids: Sequence[int]) -> None:
    """把训练结果写成一个能直接被 `laya.Agent` 加载的 checkpoint 目录。

    做法是"复制基座目录、覆盖权重、更新配置"：`Agent` 加载一个目录需要 `rl_agent_config.json`
    + `model.safetensors` + `tokenizer/` + `encoder/`，只写权重不写其余部件，产出的目录**加载不了**
    ——而它在文件列表里看着很正常。
    """
    import torch
    from safetensors.torch import save_file

    if out.resolve() == base.resolve():
        raise SystemExit("产出目录不能与基座相同：一次中途失败就永久毁掉基座，"
                         "而基座是后面所有对照的基准")
    out.mkdir(parents=True, exist_ok=True)
    for entry in base.iterdir():
        if entry.name == "model.safetensors":
            continue                       # 我们马上要写一份新的，别白复制几百 MB
        target = out / entry.name
        if entry.is_dir():
            shutil.copytree(entry, target, dirs_exist_ok=True)
        else:
            shutil.copy2(entry, target)

    dtypes = base_dtypes(base / "model.safetensors")
    mine = dict(model.state_dict())
    if set(mine) != set(dtypes):
        # 张量集合对不上 = 产物的结构不再是基座的结构：加载会失败或静默少一截权重，
        # 而它在文件列表里看着和正常产物一模一样
        only_base = sorted(set(dtypes) - set(mine))[:5]
        only_mine = sorted(set(mine) - set(dtypes))[:5]
        raise SystemExit("产出的张量与基座对不上（基座多 %s / 产出多 %s）："
                         "这份产物加载不了，别写出去" % (only_base, only_mine))
    state = {}
    for key, value in mine.items():
        name = SAFETENSOR_DTYPES.get(dtypes[key])
        if name is None:
            raise SystemExit("基座张量 %s 的 dtype %s 不认识" % (key, dtypes[key]))
        state[key] = value.detach().to("cpu", getattr(torch, name)).contiguous()
    save_file(state, str(out / "model.safetensors"), metadata={"format": "pt"})

    cfg_path = out / "rl_agent_config.json"
    cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    merged = dict(cfg.get("temperature_by_options") or {})
    merged.update(buckets)
    cfg["temperature_by_options"] = merged
    cfg["devmind"] = {
        "baseCheckpoint": str(base),
        "baseCheckpointId": (payload.get("baseCheckpoint") or {}).get("id"),
        "serveSlot": args.slot or payload.get("serveSlot"),
        "taskId": payload.get("taskId"),
        "dataset": payload.get("dataset"),
        "split": payload.get("split"),
        "hyper": {"epochs": args.epochs, "lr": args.lr, "batch": args.batch, "seed": args.seed,
                  "groupSize": args.group_size, "exploration": args.exploration,
                  "temperature": args.temperature, "encoderFrozen": not args.train_encoder},
        "train": train_info,
        "valItemIds": list(val_ids),
        "calibrationTemperature": buckets,
        "finishedAt": time.strftime("%Y-%m-%d %H:%M:%S"),
    }
    # ensure_ascii=True 是硬要求：laya 用 `json.load(open(path))` 读这个文件（不带 encoding），
    # Windows 上就是按 cp936 解——中文（数据集名、槽位备注）会当场 UnicodeDecodeError，
    # 表现为"产物加载不了"。转义成 \uXXXX 之后文件是纯 ASCII，任何 locale 都读得回来
    cfg_path.write_text(json.dumps(cfg, ensure_ascii=True, indent=2), encoding="utf-8")
    log("[产物] checkpoint 已写出: %s（服务温度覆盖 %d 个桶）" % (out, len(buckets)))


# ---------------------------------------------------------------- 主流程


def main() -> None:
    setup_stdout()
    raw_argv = sys.argv[1:]
    args = parse_args(raw_argv)
    validate(args)
    relaunch_if_needed(args, raw_argv)

    import torch
    import torch.distributed as dist

    world = int(os.environ.get("WORLD_SIZE", "1"))
    rank = int(os.environ.get("RANK", "0"))
    if world > 1:
        dist.init_process_group(backend="nccl" if torch.cuda.is_available() else "gloo")
        world, rank = dist.get_world_size(), dist.get_rank()
        log("[微调] 分布式启动：rank %d/%d" % (rank, world))

    payload = load_payload(args.payload)
    train_items = [it for it in (payload.get("items") or []) if it.get("split") == "TRAIN"]
    val_items = [it for it in (payload.get("items") or []) if it.get("split") == "VAL"]
    if not train_items:
        raise SystemExit("执行包里没有训练切分的样本（服务端切分失败？）")
    if not val_items:
        raise SystemExit("执行包里没有验证切分的样本：跑完只有训练损失，"
                         "「学得怎么样」无从说起——宁可不跑")

    tasks = tasks_of(train_items)
    if not tasks:
        raise SystemExit("训练样本里没有一题能和 gold 对上（goldDistribution 全空）")
    dataset = payload.get("dataset") or {}
    log("[微调] 数据集「%s v%s」%d 训练 / %d 验证 · %d 道训练题 · 基座 %s"
        % (dataset.get("name"), dataset.get("version"), len(train_items), len(val_items),
           len(tasks), args.base_checkpoint))

    agent = load_agent(args.base_checkpoint, args.device)
    agent.model.eval()
    log("[微调] 模型已加载，设备 %s" % getattr(agent, "device", "unknown"))

    # 训练前后各评一遍验证集：报告里两个都报，退化不会被静默吞掉
    base_records = evaluate_items(agent, val_items, "基座", rank=rank, world=world)
    train_info = train(agent, tasks, args, world, rank)
    tuned_records = evaluate_items(agent, val_items, "微调后", rank=rank, world=world)

    if rank != 0:
        if world > 1:
            dist.barrier()
            dist.destroy_process_group()
        return

    if not tuned_records:
        raise SystemExit("验证集里没有一题能和 gold 对上：这次训练无法给出可比指标")

    val_ids = [r.item_id for r in tuned_records]
    # 报告里的 ECE 用对半切分（一半拟合温度、一半算校准前后），与服务端评测脚本同一口径；
    # 而**写进产物**的温度用整个验证集拟合——参数估计要用全部数据，报告要的是不乐观的数字
    fit_ids, eval_ids = split_for_calibration(tuned_records)
    heldout = fit_temperature(tuned_records, fit_ids)
    before, after = apply_buckets(tuned_records, heldout, eval_ids)
    buckets = fit_temperature(tuned_records, val_ids)
    full_before, full_after = apply_buckets(tuned_records, buckets, val_ids)

    report = seal(
        kind="finetune",
        checkpoint={"name": Path(args.out).name, "slot": args.slot or payload.get("serveSlot"),
                    "path": str(Path(args.out))},
        baseCheckpoint=payload.get("baseCheckpoint"),
        dataset=dataset,
        split=payload.get("split"),
        train=train_info,
        metrics=aggregate(tuned_records),
        baselines=baselines(tuned_records),
        byCaseGroup=by_case_group(tuned_records),
        perItem=[r.row() for r in tuned_records],
        compare=dict(compare_block(tuned_records, base_records),
                     baselineCheckpoint=(payload.get("baseCheckpoint") or {}).get("name"),
                     baselineMetrics=aggregate(base_records)),
        calibration={
            # 验证切分没参与训练 = 对"这次训练"是 held-out；但温度是在同一批上拟合的，
            # 所以 before/after 取自对半切分（拟合一半、评估另一半），全套拟合的数字另列 allItems
            "mode": "heldout",
            "source": "val-split",
            "fitItems": len(fit_ids),
            "evalItems": len(eval_ids),
            "before": {"ece": round(before, 6)},
            "after": {"ece": round(after, 6)},
            "allItems": {"before": {"ece": round(full_before, 6)},
                         "after": {"ece": round(full_after, 6)}},
            "temperature": buckets,
            "note": "before/after 取自验证集对半切分；写进产物的温度用整个验证集拟合（见 allItems）",
        },
        warnings=list(payload.get("warnings") or []),
        layaVersion=_laya_version(),
    )
    emit(MARKER_REPORT, report)

    # 存盘放在报告之后：报告是"跑出来了没有"的第一信号，写盘失败会在下一行立刻炸出来
    save_checkpoint(Path(args.base_checkpoint), Path(args.out), agent.model, args, buckets, payload,
                    train_info, val_ids)
    fp = fingerprint(args.out)
    emit(MARKER_FINGERPRINT, fp)

    log("[微调] 验证集 choice 准确率 %s → %s（基座 → 微调后）· 逐题胜 %d / 负 %d / 平 %d"
        % (_fmt(aggregate(base_records).get("choice", {}).get("accuracy")),
           _fmt(report["metrics"].get("choice", {}).get("accuracy")),
           report["compare"]["win"], report["compare"]["lose"], report["compare"]["tie"]))
    log("[产物] 指纹 sha256=%s bytes=%d files=%d"
        % (fp["sha256"][:16], fp["bytes"], fp["files"]))
    if world > 1:
        dist.barrier()
        dist.destroy_process_group()


def _avg(values: Sequence[float]):
    return round(sum(values) / len(values), 6) if values else None


def _laya_version() -> str:
    try:
        import laya
        return getattr(laya, "__version__", "unknown")
    except Exception:      # noqa: BLE001 —— 版本只进报告，取不到不该让训练失败
        return "unknown"


def _fmt(value) -> str:
    return "—" if value is None else ("%.4f" % float(value))


if __name__ == "__main__":
    main()
