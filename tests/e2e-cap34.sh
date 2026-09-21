#!/usr/bin/env bash
# CAP-34 P0 E2E（tmp/，gitignored）：
# 用 dist 包起 服务端:18090 + runner（executor=fake），验证
#   1. 无节点时创建会话 → 409；传 "local" → 400
#   2. 注册节点设默认 → runner 接入 → 问答全链路（RUNNING→finish→DONE，沙箱 _chat/<sid> 建了又被收）
#   3. 带全局知识条目创建裸会话 → launch 帧 manifest → runner 拉包物化（worktree 下 CLAUDE.local.md 含注入 + settings.local.json）
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
WORK="$ROOT/tmp/e2e-cap34"
PORT=18090
BASE="http://127.0.0.1:$PORT"
DIST="$WORK/devmind-0.1.0-SNAPSHOT"

say() { echo "[e2e] $*"; }
fail() { echo "[e2e] FAIL: $*" >&2; exit 1; }

jget() { # stdin json → 字段值
  node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>{const v=JSON.parse(s);const k='$1';console.log(k.split('.').reduce((o,p)=>o?.[p],v)??'')})"
}

# Windows curl -d 中文会按 GBK 发 → 服务端 JSON 报 Invalid UTF-8；一律落 UTF-8 临时文件 --data-binary
post() { # post <url> <json-body> [method]
  local f; f=$(mktemp)
  printf '%s' "$2" > "$f"
  curl -s "$1" -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' -X "${3:-POST}" --data-binary @"$f"
  rm -f "$f"
}
post_code() { # 只取状态码
  local f; f=$(mktemp)
  printf '%s' "$2" > "$f"
  curl -s -o /dev/null -w '%{http_code}' "$1" -H "$AUTH" -H 'Content-Type: application/json; charset=UTF-8' --data-binary @"$f"
  rm -f "$f"
}

say "构建（install 全量 + dist 组装，保证 ~/.m2 与 tarball 为当前代码）"
cd "$ROOT"
mvn -q install -DskipTests || fail "mvn install 失败"
mvn -q -pl devmind-dist -Pdist package || fail "dist 组装失败"

say "解包 dist 到 $WORK"
rm -rf "$WORK"; mkdir -p "$WORK"
tar xzf "$ROOT/devmind-dist/target/devmind-0.1.0-SNAPSHOT.tar.gz" -C "$WORK"

say "起服务端 :$PORT"
cd "$DIST"
# jar 内打包了本机 application-local.yml（指向共享 MySQL）——E2E 必须用外置同名 profile 文件
# 强制 H2 文件库 + 端口，绝不碰共享库
cat > config/application-local.yml <<EOF
server:
  port: $PORT
spring:
  datasource:
    url: jdbc:h2:file:./data/devmind;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
EOF
DEVMIND_PORT=$PORT bin/dev-mind start || { tail -30 logs/console.out; fail "服务端启动失败"; }

cleanup() {
  say "收尾：停 runner 与服务端"
  cd "$DIST" 2>/dev/null && { bin/dev-mind-agent stop || true; bin/dev-mind stop || true; }
}
trap cleanup EXIT

TOKEN=$(curl -s "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | jget accessToken)
[ -n "$TOKEN" ] || fail "登录失败"
AUTH="Authorization: Bearer $TOKEN"

say "1) 无节点：创建会话应 409，local 保留值应 400"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/sessions" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"taskSpec":"e2e no-node","agentNodeId":null}')
[ "$CODE" = "409" ] || fail "无节点创建会话应 409，实际 $CODE"
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/sessions" -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"taskSpec":"e2e local","agentNodeId":"local"}')
[ "$CODE" = "400" ] || fail "local 保留值应 400，实际 $CODE"

say "2) 注册节点 + 设默认 + 起 runner（fake executor）"
NODE_JSON=$(curl -s "$BASE/api/agent-nodes" -H "$AUTH" -H 'Content-Type: application/json' -d '{"name":"e2e-node"}')
NODE_ID=$(echo "$NODE_JSON" | jget node.id)
NODE_TOKEN=$(echo "$NODE_JSON" | jget token)
[ -n "$NODE_TOKEN" ] || fail "节点创建失败: $NODE_JSON"
curl -s -o /dev/null "$BASE/api/agent-nodes/$NODE_ID/default" -X POST -H "$AUTH"

cat > config/agent.properties <<EOF
serverUrl=ws://127.0.0.1:$PORT/ws/agent
token=$NODE_TOKEN
executor=fake
workspaceRoot=./workspaces
workDir=./workspaces/_default
maxConcurrent=4
EOF
bin/dev-mind-agent start || { tail -20 logs/agent-runner.out; fail "runner 启动失败"; }
sleep 3
grep -q "hello\|registered\|连接" logs/agent-runner.out || tail -5 logs/agent-runner.out
ONLINE=$(curl -s "$BASE/api/agent-nodes" -H "$AUTH" | node -e "let s='';process.stdin.on('data',d=>s+=d).on('end',()=>console.log(JSON.parse(s)[0].status))")
[ "$ONLINE" = "ONLINE" ] || fail "节点未 ONLINE（实际 $ONLINE）"

say "3) 问答全链路（runner _chat 沙箱）"
CHAT=$(post "$BASE/api/chats" '{"message":"e2e 问答：介绍一下你自己"}')
CHAT_ID=$(echo "$CHAT" | jget id)
[ -n "$CHAT_ID" ] || fail "问答创建失败: $CHAT"
CHAT_NODE=$(echo "$CHAT" | jget agentNodeId)
[ "$CHAT_NODE" = "$NODE_ID" ] || fail "问答未路由到默认节点: $CHAT_NODE"
[ -d "workspaces/_chat/$CHAT_ID" ] || fail "runner 问答沙箱未建"
curl -s -o /dev/null "$BASE/api/chats/$CHAT_ID/finish" -X POST -H "$AUTH"
for i in $(seq 1 30); do
  ST=$(curl -s "$BASE/api/chats/$CHAT_ID" -H "$AUTH" | jget status)
  [ "$ST" = "DONE" ] && break; sleep 1
done
[ "$ST" = "DONE" ] || fail "问答未 DONE（实际 $ST）"
[ ! -d "workspaces/_chat/$CHAT_ID" ] || fail "问答结束后沙箱未清理"

say "4) 全局知识条目 → 会话 launch 带 manifest → runner 拉包物化"
# 注意：带标签的全局条目对裸会话（无项目标签可匹配）不注入（防上下文膨胀）——必须无标签
post "$BASE/api/knowledge/entries" '{"scope":"global","name":"e2e-marker","contentMd":"E2E-INJECTION-MARKER-42 永远先跑 mvn test","tags":[]}' > /dev/null
SES=$(post "$BASE/api/sessions" "{\"taskSpec\":\"e2e 会话：验证上下文物化\",\"agentNodeId\":\"$NODE_ID\"}")
SES_ID=$(echo "$SES" | jget id)
[ -n "$SES_ID" ] || fail "会话创建失败: $SES"
# 落点目录随 CAP-42 固定布局（<ws>/<proj>/<owner>/work）变化 → 用 find 定位，不硬编码层级
INJ=""
for i in $(seq 1 30); do
  INJ=$(find workspaces -path '*/_chat' -prune -o -name CLAUDE.local.md -print -quit)
  [ -n "$INJ" ] && break; sleep 1
done
[ -n "$INJ" ] || fail "runner 未物化 CLAUDE.local.md（无 manifest？）"
grep -q "E2E-INJECTION-MARKER-42" "$INJ" || { cat "$INJ"; fail "注入块缺知识内容"; }
SET="${INJ%CLAUDE.local.md}.claude/settings.local.json"
[ -f "$SET" ] || fail "缺 .claude/settings.local.json（$SET）"
grep -q "Bash(mvn" "$SET" || fail "settings.local.json 缺权限白名单"
say "物化校验通过（$INJ 注入块 + settings.local.json）"

curl -s -o /dev/null "$BASE/api/sessions/$SES_ID/kill" -X POST -H "$AUTH"
say "ALL PASS"
