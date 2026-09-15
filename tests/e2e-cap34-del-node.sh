#!/usr/bin/env bash
# 删除共享 MySQL 中 E2E 误建节点 e2e-node（id=1，用户已确认）：先 unset-default 再删。
set -euo pipefail
cd "$(dirname "$0")/.."
DIST="$PWD/tmp/e2e-cap34/devmind-0.1.0-SNAPSHOT"
PORT=18091
BASE="http://127.0.0.1:$PORT"
cd "$DIST"
DEVMIND_PORT=$PORT EXTRA_OPTS="--server.port=$PORT" bin/dev-mind start
trap "cd '$DIST' && bin/dev-mind stop" EXIT
TOKEN=$(curl -s "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s).accessToken??''))")
AUTH="Authorization: Bearer $TOKEN"
curl -s -o /dev/null -w 'unset-default: %{http_code}\n' "$BASE/api/agent-nodes/1/unset-default" -X POST -H "$AUTH"
curl -s -o /dev/null -w 'delete: %{http_code}\n' "$BASE/api/agent-nodes/1" -X DELETE -H "$AUTH"
curl -s "$BASE/api/agent-nodes" -H "$AUTH"
