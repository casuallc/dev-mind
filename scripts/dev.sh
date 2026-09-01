#!/usr/bin/env bash
# Dev-Mind 本地一键启动：后端 :8080 + 前端 Vite :5173（代理 /api、/ws）
# 用法：
#   scripts/dev.sh                 # 构建（跳测试）后起前后端，真实 claude 执行器
#   scripts/dev.sh fake            # 用内置假进程执行器（自测/E2E）
#   scripts/dev.sh claude --skip-build   # 跳过 mvn install 直接起（只改了前端时快）
# Ctrl+C 一起停掉前后端（含 spring-boot:run fork 出的 JVM）。
set -euo pipefail
cd "$(dirname "$0")/.."

EXECUTOR="claude"
SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    fake|claude) EXECUTOR="$arg" ;;
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "未知参数: $arg（可用: fake|claude|--skip-build）"; exit 1 ;;
  esac
done

if [ "$SKIP_BUILD" = "0" ]; then
  echo "[dev] 构建后端（跳过测试）..."
  mvn -q install -DskipTests
fi

if [ ! -d frontend/node_modules ]; then
  echo "[dev] 首次运行，安装前端依赖..."
  (cd frontend && npm install)
fi

BACK_PID=""
FRONT_PID=""
cleanup() {
  # MSYS 的 $! 不是 Windows PID，taskkill 用不上；mvn/npm 都会再 fork（spring-boot JVM、node），
  # 干脆按端口杀属主进程——对本机 dev 脚本最稳。
  sleep 1
  for port in 8080 5173; do
    PID=$(netstat -ano | grep ":$port" | grep LISTENING | awk '{print $NF}' | head -1)
    [ -n "$PID" ] && taskkill //PID "$PID" //T //F >/dev/null 2>&1
  done
}
trap cleanup EXIT INT TERM

echo "[dev] 启动后端 :8080（executor=$EXECUTOR，日志尾随中带 [dev] 前缀的为本脚本输出）..."
mvn -pl devmind-app spring-boot:run "-Dspring-boot.run.arguments=--devmind.session.executor=$EXECUTOR" &
BACK_PID=$!

echo "[dev] 启动前端 :5173 ..."
(cd frontend && npm run dev) &
FRONT_PID=$!

echo -n "[dev] 等待后端就绪"
for _ in $(seq 1 60); do
  if curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/health 2>/dev/null | grep -q 200; then
    echo " OK"
    echo "[dev] 前端  → http://localhost:5173 （开发入口，热更新）"
    echo "[dev] 后端  → http://localhost:8080 （API/健康检查 /health）"
    echo "[dev] 首次登录：admin / admin123（devmind.auth.admin-password 可改）"
    break
  fi
  echo -n "."
  sleep 2
done

wait
