# laya-sidecar — 决策模型边车服务（CAP-55 FR-01 / CAP-56 FR-01）

把 [laya](https://pypi.org/project/laya/)（非自回归 System 1 决策模型，choice/score/noul 三原语）
包成常驻 HTTP 服务，供 Dev-Mind 服务端经 CAP-48 `kind=DECISION` 端点调用。

**为什么常驻**：laya 冷启动 7-10s（CPU 7.4s / T4 10.3s，官方实测），单次决策 33ms 级，
模型必须常驻内存，请求期永不加载 checkpoint（启动期 `preload` 一次付清）。

## 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/healthz` | 存活 + 已常驻 checkpoint + 设备 + **每个槽位的实际来源**（CAP-48 连接测试第一段；CAP-56 FR-06 的 serve 自检读 `sources`） |
| POST | `/v1/predict` | `{state, questions, model?, task?, lang?}` → laya answers/routing 原文透传 |

`/healthz` 的 `sources` 回答的是「此刻这个槽位到底加载了哪个目录/仓库」——这是核对
「准入闸门放行的那份产物 == 正在跑的那份」的唯一凭据（CAP-56）：

```jsonc
{
  "status": "ok", "loaded": ["typed-decisions"], "devices": {"typed-decisions": "cpu"},
  "source_origin": "/etc/laya/models.json",     // 这份配置从哪来（环境变量名 / 文件路径 / 内置默认）
  "overridden_slots": ["typed-decisions"],      // 与内置来源不同的槽位
  "unready_slots": [],                          // 本地目录缺 rl_agent_config.json / model.safetensors
  "sources": {
    "typed-decisions": {"source": "/data/laya/ft7", "kind": "local", "overridden": true,
                        "loaded": true, "path": "/data/laya/ft7", "exists": true,
                        "ready": true, "missing": [], "device": "cpu"},
    "multilingual":    {"source": "convaiinnovations/laya/multilingual", "kind": "repo",
                        "overridden": false, "loaded": false, "repo": "convaiinnovations/laya",
                        "subfolder": "multilingual"}
  }
}
```

`questions` 用 laya 原生 schema：

- choice：`{"type": "choice", "instructions": "...", "criteria": {"optA": "描述", ...}}`
- score：`{"type": "score", "instructions": "...", "criteria": ["lvl0", "lvl1", ...]}`
- noul：`{"type": "noul", "instructions": "..."}`（返回 0-1 概率）

调用方错误（未知 model 名、选项数超 `head_max_len`、schema 非法）返回 400 + detail。

## 槽位来源覆盖（CAP-56 FR-01）

**只换来源，不改槽位名**：`typed-decisions` 永远叫 `typed-decisions`，变的只是它背后加载哪个
目录/仓库。这样平台侧登记的「槽位」不用跟着改，微调产物（RLCD 训练输出目录）可以就地顶上。

```bash
# 环境变量（两种写法都行）
LAYA_SLOT_MODELS='{"typed-decisions": "/data/laya/ft7"}' uvicorn app:app --port 8377
LAYA_SLOT_MODELS='typed-decisions=/data/laya/ft7,multilingual=laya-multilingual' uvicorn app:app --port 8377
# 或放文件：与本文件同目录的 models.json（节点专属，不进版本库；模板见 models.json.example）
```

值可以是**本地绝对路径**（微调产物就落在节点上，laya 原生支持直接吃目录）或 HF 仓库名
（`"repo"` 或 `["repo", "subfolder"]`）；只写要改的槽位，其余保持内置默认。

**坏配置一律当场炸**（进程起不来，而不是静默退回内置模型——那意味着页面上服务的是旧模型却看不出来）：

| 情形 | 行为 |
|------|------|
| 槽位名不认识（如 `typed_decisions2`） | 启动失败，列出合法槽位与别名 |
| 本地路径不存在 / 目录里缺 `rl_agent_config.json`、`model.safetensors` | 启动失败（缺文件时 `/healthz` 另报 `ready:false` + `missing`） |
| `LAYA_SLOT_MODELS` 不是合法 JSON / 不是 `k=v` 形式 | 启动失败 |
| 来源文件存在但不是合法 JSON | 启动失败（报文件路径） |

来源优先级：`LAYA_SLOT_MODELS` > `LAYA_MODELS_FILE` 指定的文件 > 同目录 `models.json` > 内置默认。
生效时启动日志会打一行覆盖清单（`[laya-sidecar] 槽位来源覆盖（…）: {...}`）。

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `LAYA_PRELOAD` | `english,multilingual,typed-decisions` | 启动期常驻的 checkpoint，逗号分隔；内存紧张可裁到 `multilingual` |
| `LAYA_DEVICE` | 自动探测 | `cpu` / `cuda` / `cuda:0` |
| `LAYA_AUTO_TASK_DETECTION` | 关 | 开后按问题 id 签名自动路由 typed-decisions |
| `LAYA_SLOT_MODELS` | 空 | 槽位→来源覆盖（CAP-56 FR-01），JSON 对象或 `k=v,k=v` |
| `LAYA_MODELS_FILE` | 同目录 `models.json` | 覆盖来源文件路径；默认路径不存在 = 用内置默认，**显式指定却不存在 = 报错**（防路径敲错后静默服务旧模型） |
| `HF_TOKEN` | 空 | checkpoint 为公开仓库，一般不需要 |
| `PORT` | `8377` | 监听端口（Docker CMD 使用） |

三 checkpoint 常驻内存约 3-5GB；`LAYA_PRELOAD=multilingual` 单 checkpoint（322M）约 1GB。

> 注意 `LAYA_PRELOAD` 里写的是**槽位名**，不是路径：覆盖来源时照旧写 `typed-decisions`。

## 本地开发

```bash
cd tools/laya-sidecar
python -m venv .venv && source .venv/Scripts/activate   # Windows Git Bash
# 无 GPU 先装 CPU 版 torch，否则 PyPI 默认轮子拖 CUDA 依赖（多 5GB+）
pip install torch --index-url https://download.pytorch.org/whl/cpu
pip install -r requirements.txt
# 网络不稳时换镜像：两条的 pip 都加 -i https://pypi.tuna.tsinghua.edu.cn/simple
# （清华镜像的 torch 在 Windows 上本身就是 +cpu 构建）
uvicorn app:app --port 8377
```

首启会从 HuggingFace 下载 checkpoint（~2.5GB，落 `~/.cache/huggingface`），之后走本地缓存。

## Docker

```bash
docker build -t laya-sidecar .
# 烘 checkpoint 进镜像（启动零下载、断外网可起，镜像 +~2.5GB）：
docker build --build-arg BAKE_MODELS=english,multilingual,typed-decisions -t laya-sidecar:baked .
docker run -d -p 8377:8377 --name laya laya-sidecar
```

## 冒烟验证

```bash
curl localhost:8377/healthz

# 用仓库内的中文样例（三原语各一题：choice 采纳层级 / noul 重复判定 / score 质量分）
curl -X POST localhost:8377/v1/predict -H 'Content-Type: application/json' -d @smoke-predict.json
```

返回含 `answers`（每题答案 + 概率分布 + 置信度）与 `routing`（选中 checkpoint 及原因）。
`routing.repo` 就是这次预测**实际**用的来源——覆盖来源后它应当是被覆盖的那个路径/仓库名。

覆盖来源的冒烟（本地绝对路径要写成 `D:/…` 正斜杠形式，避免 Git Bash 的路径转换）：

```bash
LAYA_SLOT_MODELS='{"typed-decisions": "D:/apusic/dev-mind/tmp/cap56/out-ft1"}' \
LAYA_PRELOAD=typed-decisions LAYA_DEVICE=cpu uvicorn app:app --port 8399
curl -s localhost:8399/healthz -o hz.json   # sources.typed-decisions.kind=local / ready / overridden
```

**Windows 两个坑（已踩过）**：

- 请求体含中文必须 `-d @文件`：curl 命令行内联中文经 Windows 控制台编码会坏，
  FastAPI 报 `There was an error parsing the body`。
- 响应管道给 `python -m json.tool` 会把 UTF-8 按 GBK 解码显示成乱码假象
  （服务端数据没问题）——要么 `-o resp.json` 落盘读，要么 `PYTHONIOENCODING=utf-8`。

## 国内网络：checkpoint 下载走镜像

直连 huggingface.co 会被重置。设 `HF_ENDPOINT=https://hf-mirror.com` 再启动；
大文件（safetensors 600-850MB/个）若中途断流，可先手工 curl 续传落进 HF 缓存再启动
（缓存目录 `~/.cache/huggingface/hub/models--convaiinnovations--laya/snapshots/<sha>/`，
文件清单见 `https://hf-mirror.com/api/models/convaiinnovations/laya/tree/main?recursive=true`）。

## 部署位置

与 Dev-Mind 服务端解耦：可跑在任何 HTTP 可达机器（含 runner 节点机），
服务端只认 CAP-48 端点登记的 baseUrl。GPU 部署换 CUDA 基础镜像、去掉 Dockerfile 里
CPU torch 那行、`LAYA_DEVICE=cuda`。
