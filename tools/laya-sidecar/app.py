"""Laya 决策模型边车服务（CAP-55 FR-01 / CAP-56 FR-01）。

把 laya 的 Router 包成常驻 HTTP 进程：模型冷启动 7-10s，必须常驻内存，
Java 侧（devmind-decision）只认 HTTP 端点，不感知 Python/torch 细节。

端点：
  GET  /healthz       存活 + 已常驻 checkpoint 列表 + 设备信息 + **每个槽位的实际来源**
                      （CAP-48 连接测试第一段；CAP-56 FR-06 的 serve 自检读 sources）
  POST /v1/predict    {state, questions, model?} -> laya answers/routing 原文透传

环境变量：
  LAYA_PRELOAD               启动期常驻的 checkpoint，逗号分隔
                             （english / multilingual / typed-decisions，默认全三个）
  LAYA_DEVICE                cpu / cuda / cuda:0 ...（默认 laya 自动探测）
  LAYA_AUTO_TASK_DETECTION   true 时允许按问题 id 签名自动路由 typed-decisions（默认关）
  LAYA_SLOT_MODELS           槽位→来源覆盖（CAP-56 FR-01）。JSON 对象，或 "k=v,k=v"：
                                 {"typed-decisions": "/data/out/ft7"}
                             值可以是**本地绝对路径**（微调产物就落在节点上）或 HF 仓库名。
  LAYA_MODELS_FILE           覆盖来源文件路径（默认：与本文件同目录的 models.json；
                             默认路径不存在 = 用内置默认，**显式指定却不存在 = 报错**）
  HF_TOKEN                   HuggingFace token（checkpoint 为公开仓库，一般不需要）
  PORT                       监听端口（默认 8377，仅 Dockerfile CMD 使用）

**槽位来源覆盖只换来源，不改槽位名**：`typed-decisions` 永远叫 `typed-decisions`，
变的只是它背后加载哪个目录/仓库。这样平台侧登记的"槽位"不需要跟着改，
而"此刻这个槽位到底加载了什么"由 `/healthz` 的 `sources` 如实上报
（CAP-56 的准入闸门放行的是一份具体产物，这条上报是核对"放行的就是正在跑的"的唯一凭据）。
"""
import json
import os
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional, Tuple, Union

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel

from laya import DEFAULT_MODELS, Router
from laya import __version__ as LAYA_VERSION
from laya.router import normalise_name

DEFAULT_PRELOAD = "english,multilingual,typed-decisions"

# 槽位来源覆盖：环境变量优先于文件；文件默认与边车同目录
SLOT_SOURCES_ENV = "LAYA_SLOT_MODELS"
SLOT_SOURCES_FILE_ENV = "LAYA_MODELS_FILE"
DEFAULT_SLOT_SOURCES_FILE = "models.json"

# 本地 checkpoint 目录要能加载，这两个文件缺一不可（laya.Agent 的加载前提）
REQUIRED_CHECKPOINT_FILES = ("rl_agent_config.json", "model.safetensors")


def _env_bool(name: str, default: bool = False) -> bool:
    v = os.environ.get(name)
    return default if v is None else v.strip().lower() in ("1", "true", "yes", "on")


# --------------------------------------------------------------------- 槽位来源覆盖


def _parse_slot_sources(text: str, origin: str) -> Dict[str, str]:
    """`LAYA_SLOT_MODELS` 的两种写法：JSON 对象，或 `k=v,k=v`（docker/命令行里更好敲）。"""
    text = (text or "").strip()
    if not text:
        return {}
    if text.startswith("{"):
        try:
            doc = json.loads(text)
        except ValueError as e:
            raise ValueError("%s 不是合法 JSON：%s" % (origin, e)) from e
        if not isinstance(doc, dict):
            raise ValueError("%s 必须是「槽位 → 来源」的对象，收到 %s" % (origin, type(doc).__name__))
        return {str(k): v for k, v in doc.items()}
    out: Dict[str, str] = {}
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        key, sep, value = part.partition("=")
        if not sep or not value.strip():
            raise ValueError("%s 的 %r 不是 k=v 形式（多个用逗号分隔）" % (origin, part))
        out[key.strip()] = value.strip()
    return out


def load_slot_sources() -> Tuple[Dict[str, Any], str]:
    """解析出「槽位 → 来源」，并给出来源说明（进 /healthz，供人追"这份配置从哪来"）。

    <b>解析失败一律上抛，不做任何降级</b>：静默退回内置默认意味着节点上服务的是旧模型，
    而页面上看不出任何异常——这正是本功能要防的那件事。宁可边车起不来（现象明确、位置明确）。
    """
    raw = os.environ.get(SLOT_SOURCES_ENV)
    if raw and raw.strip():
        return _parse_slot_sources(raw, SLOT_SOURCES_ENV), SLOT_SOURCES_ENV

    explicit = os.environ.get(SLOT_SOURCES_FILE_ENV)
    path = explicit or os.path.join(os.path.dirname(os.path.abspath(__file__)), DEFAULT_SLOT_SOURCES_FILE)
    if not os.path.exists(path):
        if explicit:
            # 显式指了路径却不在 = 配置错了（多半是路径敲错）。这里同样不降级：
            # 静默用内置默认，等于节点上服务的是旧模型而页面上看不出来
            raise ValueError("%s 指向的槽位来源文件不存在：%s" % (SLOT_SOURCES_FILE_ENV, path))
        return {}, "内置默认（%s 不存在）" % DEFAULT_SLOT_SOURCES_FILE
    try:
        doc = json.loads(open(path, encoding="utf-8").read())
    except (OSError, ValueError) as e:
        raise ValueError("槽位来源文件 %s 读不了或不是合法 JSON：%s" % (path, e)) from e
    if not isinstance(doc, dict):
        raise ValueError("槽位来源文件 %s 必须是「槽位 → 来源」的对象" % path)
    # 允许 `{"slots": {...}}` 包裹，好让文件里还能放注释类字段（如 "_comment"）
    slots = doc.get("slots") if isinstance(doc.get("slots"), dict) else doc
    kept = {str(k): v for k, v in slots.items() if not str(k).startswith("_")}
    return kept, path


def _normalise_sources(sources: Dict[str, Any], origin: str) -> Dict[str, Any]:
    """槽位名归一（别名 → 规范名）+ 本地路径存在性校验。名字不认识就报错，而不是忽略。"""
    out: Dict[str, Any] = {}
    for name, spec in sources.items():
        try:
            key = normalise_name(name)
        except ValueError as e:
            raise ValueError("%s 里的槽位 %r 不认识：%s" % (origin, name, e)) from e
        path, sub = _spec_parts(spec)
        if _is_local(path) and not os.path.isdir(path):
            raise ValueError("%s 里 %s 指向的本地路径不存在：%s"
                             "（路径是节点上的绝对路径；产物被移走/删掉时这条会当场拦住）"
                             % (origin, key, path))
        out[key] = spec
    return out


def _spec_parts(spec: Any) -> Tuple[str, Optional[str]]:
    """拆分模型来源：字符串（仓库名或本地路径）或 `[仓库, 子目录]`。"""
    if isinstance(spec, (list, tuple)):
        parts = list(spec) + [None]
        return str(parts[0]), (None if parts[1] is None else str(parts[1]))
    return str(spec), None


def _is_local(spec: str) -> bool:
    """是不是本地路径（区别于 HF 仓库名 `org/name`）。"""
    return os.path.isabs(spec) or spec.startswith("~") or os.path.isdir(spec)


def _source_label(spec: Any) -> str:
    """人读的来源 id：本地路径原样，仓库+子目录拼成 `repo/subfolder`。"""
    path, sub = _spec_parts(spec)
    return "%s/%s" % (path, sub) if sub else path


def _slot_source_report(slot: str, spec: Any, loaded: List[str], devices: Dict[str, str]) -> Dict[str, Any]:
    """一个槽位的实际来源：给 serve 自检核对"登记的那份 == 正在跑的那份"。"""
    path, sub = _spec_parts(spec)
    report: Dict[str, Any] = {
        "source": _source_label(spec),
        "kind": "local" if _is_local(path) else "repo",
        "overridden": tuple(DEFAULT_MODELS.get(slot, ())) != (path, sub),
        "loaded": slot in loaded,
    }
    if report["kind"] == "local":
        report["path"] = os.path.abspath(os.path.expanduser(path))
        if sub:
            report["subfolder"] = sub
        missing = [name for name in REQUIRED_CHECKPOINT_FILES
                   if not os.path.exists(os.path.join(report["path"], sub or "", name))]
        report["exists"] = os.path.isdir(report["path"])
        report["ready"] = not missing
        report["missing"] = missing
    else:
        report["repo"] = path
        if sub:
            report["subfolder"] = sub
    if slot in devices:
        report["device"] = devices[slot]
    return report


@asynccontextmanager
async def lifespan(app: FastAPI):
    names = [n.strip() for n in os.environ.get("LAYA_PRELOAD", DEFAULT_PRELOAD).split(",") if n.strip()]
    sources, origin = load_slot_sources()
    router = Router(
        models=_normalise_sources(sources, origin) or None,
        device=os.environ.get("LAYA_DEVICE") or None,
        auto_task_detection=_env_bool("LAYA_AUTO_TASK_DETECTION"),
    )
    app.state.slot_source_origin = origin
    if sources:
        # 覆盖生效时打一行：这是"节点上到底加载了什么"的第一现场，出问题时先看它
        changed = {k: _source_label(v) for k, v in router.models.items()
                   if tuple(DEFAULT_MODELS.get(k, ())) != _spec_parts(v)}
        print("[laya-sidecar] 槽位来源覆盖（%s）: %s"
              % (origin, json.dumps(changed, ensure_ascii=False)), flush=True)
    if names:
        # 冷启动在进程启动期一次性付掉，请求期永不触发模型加载
        router.preload(names)
    app.state.router = router
    yield


app = FastAPI(title="laya-sidecar", version=LAYA_VERSION, lifespan=lifespan)


class PredictRequest(BaseModel):
    state: Union[str, Dict[str, Any], List[Any]]
    questions: Dict[str, Dict[str, Any]]
    model: Optional[str] = None   # 显式指定 checkpoint（english / multilingual / typed-decisions 及别名）
    task: Optional[str] = None    # typed_decisions 等任务名，等价于显式路由
    lang: Optional[str] = None    # 显式语言提示（zh / en ...），跳过脚本探测


@app.get("/healthz")
def healthz(request: Request) -> Dict[str, Any]:
    router: Router = request.app.state.router
    devices = {}
    for name in router.loaded:
        agent = router.load(name)  # 已常驻，load 只是查表
        devices[name] = str(getattr(agent, "device", "unknown"))
    try:
        import torch
        cuda = bool(torch.cuda.is_available())
    except Exception:
        cuda = False
    # 每个槽位的**实际来源**：CAP-56 FR-06 的 serve 自检拿它核对"登记的那份 == 正在跑的那份"。
    # 报的是 Router 此刻真正持有的 spec（不是配置文件里的原文）——两者不一致时，
    # 该看到的是"边车实际用什么"，配置的事去追配置文件
    sources = {name: _slot_source_report(name, spec, router.loaded, devices)
               for name, spec in router.models.items()}
    return {
        "status": "ok",
        "laya_version": LAYA_VERSION,
        "loaded": router.loaded,
        "devices": devices,
        "cuda_available": cuda,
        "sources": sources,
        "source_origin": getattr(request.app.state, "slot_source_origin", ""),
        "overridden_slots": sorted(n for n, s in sources.items() if s["overridden"]),
        "unready_slots": sorted(n for n, s in sources.items()
                                if s["kind"] == "local" and not s.get("ready", False)),
    }


@app.post("/v1/predict")
def predict(req: PredictRequest, request: Request) -> Dict[str, Any]:
    # 同步 def：FastAPI 放线程池执行，torch 前向不阻塞事件循环
    router: Router = request.app.state.router
    try:
        return router.predict(req.state, req.questions, model=req.model, task=req.task, lang=req.lang)
    except ValueError as e:
        # 未知 model 名、选项数超 head_max_len、question schema 非法等调用方错误
        raise HTTPException(status_code=400, detail=str(e))
