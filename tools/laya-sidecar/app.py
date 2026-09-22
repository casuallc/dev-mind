"""Laya 决策模型边车服务（CAP-55 FR-01）。

把 laya 的 Router 包成常驻 HTTP 进程：模型冷启动 7-10s，必须常驻内存，
Java 侧（devmind-decision）只认 HTTP 端点，不感知 Python/torch 细节。

端点：
  GET  /healthz       存活 + 已常驻 checkpoint 列表 + 设备信息（CAP-48 连接测试第一段）
  POST /v1/predict    {state, questions, model?} -> laya answers/routing 原文透传

环境变量：
  LAYA_PRELOAD               启动期常驻的 checkpoint，逗号分隔
                             （english / multilingual / typed-decisions，默认全三个）
  LAYA_DEVICE                cpu / cuda / cuda:0 ...（默认 laya 自动探测）
  LAYA_AUTO_TASK_DETECTION   true 时允许按问题 id 签名自动路由 typed-decisions（默认关）
  HF_TOKEN                   HuggingFace token（checkpoint 为公开仓库，一般不需要）
  PORT                       监听端口（默认 8377，仅 Dockerfile CMD 使用）
"""
import os
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional, Union

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel

from laya import Router
from laya import __version__ as LAYA_VERSION

DEFAULT_PRELOAD = "english,multilingual,typed-decisions"


def _env_bool(name: str, default: bool = False) -> bool:
    v = os.environ.get(name)
    return default if v is None else v.strip().lower() in ("1", "true", "yes", "on")


@asynccontextmanager
async def lifespan(app: FastAPI):
    names = [n.strip() for n in os.environ.get("LAYA_PRELOAD", DEFAULT_PRELOAD).split(",") if n.strip()]
    router = Router(
        device=os.environ.get("LAYA_DEVICE") or None,
        auto_task_detection=_env_bool("LAYA_AUTO_TASK_DETECTION"),
    )
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
    return {
        "status": "ok",
        "laya_version": LAYA_VERSION,
        "loaded": router.loaded,
        "devices": devices,
        "cuda_available": cuda,
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
