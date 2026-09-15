#!/usr/bin/env bash
# 只读：列出共享 MySQL 中 e2e 遗留（节点/会话/问答），不删。
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
echo "== 节点 e2e-node =="
curl -s "$BASE/api/agent-nodes" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const n of JSON.parse(s)) if(n.name==='e2e-node') console.log(JSON.stringify({id:n.id,isDefault:n.isDefault,status:n.status}))})"
echo "== 会话 taskSpec 以 e2e 开头 =="
curl -s "$BASE/api/sessions" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const x of JSON.parse(s)) if((x.taskSpec||'').startsWith('e2e')) console.log(JSON.stringify({id:x.id,taskSpec:x.taskSpec,status:x.status,agentNodeId:x.agentNodeId,createdBy:x.createdBy}))})"
echo "== 问答标题以 e2e 开头 =="
curl -s "$BASE/api/chats" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const x of JSON.parse(s)) if((x.title||'').startsWith('e2e')) console.log(JSON.stringify({id:x.id,title:x.title,status:x.status}))})"
