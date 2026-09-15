#!/usr/bin/env bash
# CAP-25 E2E：服务端(18091) + runner(fake executor) 全链路——
# CLONE 项目(file:// 匿名) → 远程会话 → runner 托管工作区(clone/fetch/worktree) → 结束 push 分支回远端。
# 前置：app 与 runner 已启动（见注释命令），tmp/e2e/origin.git 已 seed main 分支。
set -euo pipefail
BASE=http://127.0.0.1:18091
DIR=D:/apusic/dev-mind/tmp/e2e
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL: $1"; }
json() { node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const o=JSON.parse(d);console.log(eval('o.'+process.argv[1]))})" "$1"; }

# 1. 登录
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | json accessToken)
[ -n "$TOKEN" ] && ok "登录" || { bad "登录"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

# 2. 创建 CLONE 项目（file:// 匿名通道）
PROJ=$(curl -s -X POST $BASE/api/projects -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-cap25\",\"sourceType\":\"CLONE\",\"remoteUrl\":\"file:///$(cygpath -m $DIR/origin.git | sed 's|^/||')\"}" | json id)
echo "projectId=$PROJ"
# 等克隆 READY
for i in $(seq 1 30); do
  ST=$(curl -s $BASE/api/projects/$PROJ -H "$AUTH" | json cloneStatus)
  [ "$ST" = "READY" ] && break; sleep 2
done
[ "$ST" = "READY" ] && ok "项目克隆 READY" || { bad "项目克隆状态=$ST"; exit 1; }

# 3. 节点在线（runner 已跑）
NODE_ID=""
for i in $(seq 1 30); do
  NODE_ID=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>{const o=JSON.parse(d);const n=o.find(x=>x.status==='ONLINE');console.log(n?n.id:'')})")
  [ -n "$NODE_ID" ] && break; sleep 2
done
[ -n "$NODE_ID" ] && ok "节点 ONLINE id=$NODE_ID" || { bad "节点未上线"; exit 1; }

# 4. 创建远程会话（fake executor，runner 托管工作区）
SID=$(curl -s -X POST $BASE/api/sessions -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"projectId\":\"$PROJ\",\"taskSpec\":\"e2e cap25\",\"agentNodeId\":\"$NODE_ID\"}" | json id)
echo "sessionId=$SID"
sleep 3
STATUS=$(curl -s $BASE/api/sessions/$SID -H "$AUTH" | json status)
[ "$STATUS" = "RUNNING" ] && ok "远程会话 RUNNING（runner 托管工作区拉起成功）" || { bad "会话状态=$STATUS"; exit 1; }

# 5. runner 侧工作区断言：克隆缓存 + 会话 worktree + feature 分支
WS=$DIR/runner/workspaces/$PROJ
[ -d "$WS/main/.git" ] && ok "节点克隆缓存存在" || bad "克隆缓存缺失: $WS/main"
[ -d "$WS/sessions/$SID" ] && ok "会话 worktree 存在" || bad "会话 worktree 缺失"
BR=$(git -C "$WS/sessions/$SID" branch --show-current)
[ "$BR" = "feature/$SID" ] && ok "会话分支 feature/$SID" || bad "会话分支=$BR"
ORIGIN_URL=$(git -C "$WS/main" remote get-url origin)
case "$ORIGIN_URL" in *@*) bad "origin URL 含凭据残留: $ORIGIN_URL";; *) ok "origin URL 无凭据残留";; esac

# 6. 会话内提交一笔（模拟 agent 产出）
git -C "$WS/sessions/$SID" -c user.email=e2e@t -c user.name=e2e commit -q --allow-empty -m "e2e work"

# 7. 结束会话 → runner 收口 push
curl -s -X POST $BASE/api/sessions/$SID/finish -H "$AUTH" > /dev/null
for i in $(seq 1 20); do
  STATUS=$(curl -s $BASE/api/sessions/$SID -H "$AUTH" | json status)
  [ "$STATUS" = "COMPLETED" ] && break; sleep 2
done
[ "$STATUS" = "COMPLETED" ] && ok "会话 COMPLETED" || bad "会话结局=$STATUS"

# 8. 远端收到推送分支，且内容含 e2e 提交
if git -C $DIR/origin.git rev-parse --verify -q "refs/heads/feature/$SID" > /dev/null; then
  ok "远端存在 feature/$SID 分支"
  MSG=$(git -C $DIR/origin.git log -1 --format=%s "feature/$SID")
  [ "$MSG" = "e2e work" ] && ok "远端分支含会话提交" || bad "远端分支最新提交=$MSG"
else
  bad "远端无 feature/$SID 分支"
fi

# 9. 事件流含工作区 system 事件（push 结果上报）
EV=$(curl -s "$BASE/api/sessions/$SID/events" -H "$AUTH")
echo "$EV" | grep -q "已推送分支" && ok "事件流含 push 上报" || bad "事件流无 push 上报"

# 10. 会话 worktree 已清理、分支保留在缓存
[ ! -d "$WS/sessions/$SID" ] && ok "会话 worktree 已清理" || bad "会话 worktree 残留"
git -C "$WS/main" rev-parse --verify -q "refs/heads/feature/$SID" > /dev/null \
  && ok "分支保留在节点克隆缓存" || bad "缓存分支缺失"

echo "==== PASS=$PASS FAIL=$FAIL ===="
[ $FAIL -eq 0 ]
