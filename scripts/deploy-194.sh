#!/usr/bin/env bash
# 一键更新 8.135.37.194:/home/liuchangqing/dev-mind 环境（https 38880，SSH 30022）。
# 远端为 systemd 托管（unit dev-mind.service，含内存调优）：DEPLOY_SYSTEMD=1 让停/起/回滚都走 systemctl，
# 不再出现「deploy 脚本 nohup 起进程、systemd 不知道、restart 端口冲突」的手工收尾。
# 参数与公共脚本一致（--skip-frontend/--skip-build/--config/--sql/--no-restart），见 scripts/deploy-common.sh 头部。
set -euo pipefail
export DEPLOY_HOST="${DEPLOY_HOST:-root@8.135.37.194}"
export DEPLOY_DIR="${DEPLOY_DIR:-/home/liuchangqing/dev-mind}"
export DEPLOY_PORT="${DEPLOY_PORT:-30022}"
export DEPLOY_SYSTEMD="${DEPLOY_SYSTEMD:-1}"
exec "$(dirname "$0")/deploy-common.sh" "$@"
