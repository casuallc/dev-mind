# Laya 决策模型边车（CAP-55 FR-01）——Python 独立部署，不进 Maven/dist
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8377

WORKDIR /app

# CPU 版 torch 先行：laya 依赖 torch>=2.0，PyPI 默认轮子会拖整套 CUDA 依赖
# GPU 部署：换 pytorch/pytorch CUDA 基础镜像并跳过此行，LAYA_DEVICE=cuda
RUN pip install torch --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY app.py .

# 可选：构建期把 checkpoint 烘进镜像（+~2.5GB），启动零下载、断外网可起。
# docker build --build-arg BAKE_MODELS=english,multilingual,typed-decisions .
ARG BAKE_MODELS=""
RUN if [ -n "$BAKE_MODELS" ]; then \
      python -c "import os; from laya import Router; Router(preload=False).preload([n for n in os.environ['BAKE_MODELS'].split(',') if n])"; \
    fi

EXPOSE 8377

# workers=1：模型常驻单进程，多 worker 会各自加载一份 checkpoint（内存 ×N）
CMD ["sh", "-c", "uvicorn app:app --host 0.0.0.0 --port ${PORT} --workers 1"]
