#!/usr/bin/env bash
# 清理 CAP-34 E2E 误写共享 MySQL 的测试数据（e2e-node 节点 / e2e 会话与问答）。
# 起 dist 实例（jar 内 application-local.yml 指向共享库）→ API 删除 → 停。
set -euo pipefail
cd "$(dirname "$0")/.."
DIST="$PWD/tmp/e2e-cap34/devmind-0.1.0-SNAPSHOT"
PORT=18091
BASE="http://127.0.0.1:$PORT"

jget() {
  node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const v=JSON.parse(s);const k='$1';console.log(k.split('.').reduce((o,p)=>o?.[p],v)??'')})"
}

cd "$DIST"
DEVMIND_PORT=$PORT EXTRA_OPTS="--server.port=$PORT" bin/dev-mind start
trap "cd '$DIST' && bin/dev-mind stop" EXIT

TOKEN=$(curl -s "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jget accessToken)
[ -n "$TOKEN" ] || { echo "登录失败（共享库 admin 密码可能已改）"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

for n in $(curl -s "$BASE/api/agent-nodes" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const n of JSON.parse(s)) if(n.name==='e2e-node') console.log(n.id)})"); do
  curl -s -o /dev/null "$BASE/api/agent-nodes/$n/unset-default" -X POST -H "$AUTH"
  curl -s -o /dev/null "$BASE/api/agent-nodes/$n" -X DELETE -H "$AUTH"
  echo "删除节点 $n"
done

curl -s "$BASE/api/sessions" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const x of JSON.parse(s)) if((x.taskSpec||'').startsWith('e2e')) console.log(x.id)})" | while read -r id; do
  curl -s -o /dev/null "$BASE/api/sessions/$id/kill" -X POST -H "$AUTH" || true
  curl -s -o /dev/null "$BASE/api/sessions/$id" -X DELETE -H "$AUTH"
  echo "删除会话 $id"
done

curl -s "$BASE/api/chats" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{for(const x of JSON.parse(s)) if((x.title||'').startsWith('e2e')) console.log(x.id)})" | while read -r id; do
  curl -s -o /dev/null "$BASE/api/chats/$id" -X DELETE -H "$AUTH"
  echo "删除问答 $id"
done
echo "清理完成"
