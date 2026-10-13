#!/usr/bin/env bash
# 一键更新 172.20.140.224:/apusic/dev-mind 环境（http 8080，bin/dev-mind nohup 托管）。
# 参数与公共脚本一致（--skip-frontend/--skip-build/--config/--sql/--no-restart），见 scripts/deploy-common.sh 头部。
set -euo pipefail
export DEPLOY_HOST="${DEPLOY_HOST:-root@172.20.140.224}"
export DEPLOY_DIR="${DEPLOY_DIR:-/apusic/dev-mind}"
export DEPLOY_PORT="${DEPLOY_PORT:-22}"
exec "$(dirname "$0")/deploy-common.sh" "$@"
