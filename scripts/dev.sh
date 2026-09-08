#!/usr/bin/env bash
# Dev-Mind 本地一键启动：后端 :8080 + 前端 Vite :5173（代理 /api、/ws）+ agent runner
# CAP-34 起服务端零执行——会话/问答一律由 runner 节点执行，本地开发靠本机 runner 进程兜底。
# 用法：
#   scripts/dev.sh                 # 构建（跳测试）后起三进程
#   scripts/dev.sh --skip-build    # 跳过 mvn install 直接起（只改了前端时快）
# runner 配置在 tmp/runner/agent.properties：首次运行自动生成模板，填 token（后台 → Agent 节点
# 新建节点，建议设平台默认）后重跑才会启动 runner；未配置/未填 token 则跳过（此时创建会话会 409）。
# Ctrl+C 一起停掉三进程（含 spring-boot:run fork 出的 JVM）。
set -euo pipefail
cd "$(dirname "$0")/.."

SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    *) echo "未知参数: $arg（可用: --skip-build）"; exit 1 ;;
  esac
done

if [ "$SKIP_BUILD" = "0" ]; then
  echo "[dev] 构建后端与 runner（跳过测试）..."
  mvn -q install -DskipTests
fi

if [ ! -d frontend/node_modules ]; then
  echo "[dev] 首次运行，安装前端依赖..."
  (cd frontend && npm install)
fi

# ---------- runner（CAP-34：无 runner 则会话/问答创建 409） ----------
RUNNER_DIR="tmp/runner"
RUNNER_CONF="$RUNNER_DIR/agent.properties"
RUNNER_JAR="devmind-agent-runner/target/devmind-agent-runner.jar"
RUNNER_STARTED=0
if [ ! -f "$RUNNER_CONF" ]; then
  mkdir -p "$RUNNER_DIR"
  cat > "$RUNNER_CONF" <<'EOF'
# agent runner 本地开发配置（CAP-34：服务端零执行，会话/问答必须由 runner 执行）
serverUrl=ws://localhost:8080/ws/agent
# 节点 token：先起后端，到 后台 -> Agent 节点 新建节点（如 local-dev，建议设平台默认）复制 token 填入
token=dmag_待填
executor=claude          # claude=真实 CLI / fake=内置假进程（自测/E2E）
claudePath=              # 空 = where claude 自动探测
workspaceRoot=./workspaces
maxConcurrent=4
EOF
  echo "[dev] 已生成 runner 配置模板 $RUNNER_CONF ——填入 token 后重跑本脚本才会启动 runner"
elif grep -q '^token=dmag_待填' "$RUNNER_CONF"; then
  echo "[dev] runner 配置 token 未填（$RUNNER_CONF），跳过 runner（创建会话将 409）"
elif [ ! -f "$RUNNER_JAR" ]; then
  echo "[dev] runner jar 不存在（$RUNNER_JAR），跳过 runner（去掉 --skip-build 先构建）"
else
  echo "[dev] 启动 agent runner（$RUNNER_CONF）..."
  (cd "$RUNNER_DIR" && java -jar "../../$RUNNER_JAR" agent.properties) &
  RUNNER_STARTED=1
fi

cleanup() {
  # MSYS 的 $! 不是 Windows PID，taskkill 用不上；mvn/npm 都会再 fork（spring-boot JVM、node），
  # 干脆按端口杀属主进程——对本机 dev 脚本最稳。
  sleep 1
  for port in 8080 5173; do
    PID=$(netstat -ano | grep ":$port" | grep LISTENING | awk '{print $NF}' | head -1)
    [ -n "$PID" ] && taskkill //PID "$PID" //T //F >/dev/null 2>&1
  done
  # runner 无监听端口：java 进程按命令行特征杀（借 PowerShell 拿 Windows 进程清单）
  powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { \$_.CommandLine -match 'devmind-agent-runner' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force }" >/dev/null 2>&1 || true
}
trap cleanup EXIT INT TERM

echo "[dev] 启动后端 :8080（日志尾随中带 [dev] 前缀的为本脚本输出）..."
mvn -pl devmind-app spring-boot:run &

echo "[dev] 启动前端 :5173 ..."
(cd frontend && npm run dev) &

echo -n "[dev] 等待后端就绪"
for _ in $(seq 1 60); do
  if curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/api/health 2>/dev/null | grep -q 200; then
    echo " OK"
    echo "[dev] 前端  → http://localhost:5173 （开发入口，热更新）"
    echo "[dev] 后端  → http://localhost:8080 （API/健康检查 /api/health）"
    [ "$RUNNER_STARTED" = "1" ] && echo "[dev] runner → 已启动（日志见上方输出）"
    echo "[dev] 首次登录：admin / admin123（devmind.auth.admin-password 可改）"
    break
  fi
  echo -n "."
  sleep 2
done

wait
