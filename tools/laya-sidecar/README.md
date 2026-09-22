# laya-sidecar — 决策模型边车服务（CAP-55 FR-01）

把 [laya](https://pypi.org/project/laya/)（非自回归 System 1 决策模型，choice/score/noul 三原语）
包成常驻 HTTP 服务，供 Dev-Mind 服务端经 CAP-48 `kind=DECISION` 端点调用。

**为什么常驻**：laya 冷启动 7-10s（CPU 7.4s / T4 10.3s，官方实测），单次决策 33ms 级，
模型必须常驻内存，请求期永不加载 checkpoint（启动期 `preload` 一次付清）。

## 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/healthz` | 存活 + 已常驻 checkpoint + 设备（CAP-48 连接测试第一段） |
| POST | `/v1/predict` | `{state, questions, model?, task?, lang?}` → laya answers/routing 原文透传 |

`questions` 用 laya 原生 schema：

- choice：`{"type": "choice", "instructions": "...", "criteria": {"optA": "描述", ...}}`
- score：`{"type": "score", "instructions": "...", "criteria": ["lvl0", "lvl1", ...]}`
- noul：`{"type": "noul", "instructions": "..."}`（返回 0-1 概率）

调用方错误（未知 model 名、选项数超 `head_max_len`、schema 非法）返回 400 + detail。

## 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `LAYA_PRELOAD` | `english,multilingual,typed-decisions` | 启动期常驻的 checkpoint，逗号分隔；内存紧张可裁到 `multilingual` |
| `LAYA_DEVICE` | 自动探测 | `cpu` / `cuda` / `cuda:0` |
| `LAYA_AUTO_TASK_DETECTION` | 关 | 开后按问题 id 签名自动路由 typed-decisions |
| `HF_TOKEN` | 空 | checkpoint 为公开仓库，一般不需要 |
| `PORT` | `8377` | 监听端口（Docker CMD 使用） |

三 checkpoint 常驻内存约 3-5GB；`LAYA_PRELOAD=multilingual` 单 checkpoint（322M）约 1GB。

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
