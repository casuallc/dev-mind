#!/usr/bin/env bash
# CAP-115 M3 终端工作区收口/释放治理 E2E：真实 runner + 真实 git 临时仓库（bash 等价验证）。
# 覆盖：confirmCode 不符 400 / 脏工作区收口 409 列文件 / discardChanges 收口成功
#       （远端基线含会话提交、状态 FINALIZED、finalize-detail 有明细、审计含 WS_FINALIZE）/
#       释放脏拒 409 → discardChanges 强制释放（runner 侧 worktree 目录已删、审计含 WS_RELEASE）/
#       重复释放 409。
# 用法：先 mvn -q install -DskipTests，再 tests/e2e/cap115-terminal-finalize.sh
# 产物写 tmp/cap115-m3-e2e/<ts>/；自起 app+runner 进程退出时一律清理。
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
WORK="$ROOT/tmp/cap115-m3-e2e/$TS"
PORT="${CAP115_M3_E2E_PORT:-18321}"
BASE="http://127.0.0.1:$PORT/api"
ADMIN_PW="e2e-cap115m3-$(date +%s)"
APP_PID=""
RUNNER_PID=""
FAILED=0

say() { echo "[cap115-m3] $*"; }
fail() { echo "[cap115-m3][FAIL] $*" >&2; FAILED=1; }
check() { # check <描述> <实际> <期望包含子串>
  if [[ "$2" == *"$3"* ]]; then say "OK: $1"; else fail "$1: 实际=$2 期望包含=$3"; fi
}

cleanup() {
  say "清理自起进程..."
  [ -n "$RUNNER_PID" ] && taskkill //PID "$RUNNER_PID" //T //F >/dev/null 2>&1
  [ -n "$APP_PID" ] && taskkill //PID "$APP_PID" //T //F >/dev/null 2>&1
  wait 2>/dev/null
}
trap cleanup EXIT

mkdir -p "$WORK/data" "$WORK/git" || exit 1

# ---------- 1. 真实 git 远端 + 种子提交 ----------
REMOTE="$WORK/git/remote.git"
git init --bare "$REMOTE" >/dev/null || exit 1
git clone "$REMOTE" "$WORK/git/seed" >/dev/null 2>&1 || exit 1
( cd "$WORK/git/seed" \
  && git config user.email e2e@cap115.local && git config user.name cap115 \
  && git checkout -b master >/dev/null 2>&1 \
  && echo "cap115 m3 seed" > README.md && git add -A \
  && git commit -m "seed" >/dev/null && git push origin master >/dev/null 2>&1 ) || exit 1
git clone "$REMOTE" "$WORK/git/projrepo" >/dev/null 2>&1 || exit 1
say "git 远端就绪: $REMOTE (master)"

# ---------- 2. 隔离 app ----------
APP_JSON=$(cat <<EOF
{"server.address":"127.0.0.1","server.port":$PORT,
 "spring.profiles.active":"e2e","spring.datasource.driver-class-name":"org.h2.Driver",
 "spring.datasource.url":"jdbc:h2:file:${WORK//\\//}/data/db/devmind",
 "spring.datasource.username":"sa","spring.datasource.password":"",
 "devmind.auth.jwt-secret":"$ADMIN_PW$ADMIN_PW",
 "devmind.auth.admin-password":"$ADMIN_PW",
 "devmind.project.workspace-root":"$WORK/data/repositories",
 "devmind.worktree.root":"$WORK/data/worktrees",
 "devmind.attachment.root-dir":"$WORK/data/attachments",
 "devmind.worklog.repo-root":"$WORK/data/repos",
 "devmind.test.evidence.root-dir":"$WORK/data/test-evidence",
 "devmind.test.assets.root-dir":"$WORK/data/test-assets",
 "devmind.worklog.daily-enabled":false,"devmind.worklog.weekly-enabled":false,
 "devmind.worklog.git-import-enabled":false,
 "devmind.security.server-url":"http://127.0.0.1:$PORT",
 "devmind.security.intel-enabled":false}
EOF
)
( cd "$ROOT" && SPRING_APPLICATION_JSON="$APP_JSON" SPRING_CONFIG_LOCATION="classpath:/application.yml" \
    mvn -q -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e >"$WORK/app.log" 2>&1 ) &
APP_PID=$!
say "app 启动中 (pid=$APP_PID, port=$PORT)..."
DEADLINE=$((SECONDS + 240))
until curl -sf "$BASE/health" >/dev/null 2>&1; do
  if ! kill -0 "$APP_PID" 2>/dev/null; then fail "app 进程退出，见 $WORK/app.log"; exit 1; fi
  [ $SECONDS -gt $DEADLINE ] && { fail "app 启动超时，见 $WORK/app.log"; exit 1; }
  sleep 2
done
say "app 就绪"

# ---------- 3. curl 辅助 ----------
json_str() { grep -o "\"$2\"[[:space:]]*:[[:space:]]*\"[^\"]*\"" <<<"$1" | head -1 | sed "s/.*\"$2\"[[:space:]]*:[[:space:]]*\"//;s/\"$//"; }
json_num() { grep -o "\"$2\"[[:space:]]*:[[:space:]]*[0-9]*" <<<"$1" | head -1 | sed "s/.*\"$2\"[[:space:]]*:[[:space:]]*//;s/\"$//"; }

req() { # req <method> <path> [json-body] —— 打印响应体；HTTP 码写 $WORK/.code
  local method="$1" path="$2" body="${3:-}"
  if [ -n "$body" ]; then
    curl -s -o "$WORK/.resp" -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json; charset=UTF-8" \
      --data-raw "$body" > "$WORK/.code"
  else
    curl -s -o "$WORK/.resp" -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN" > "$WORK/.code"
  fi
  cat "$WORK/.resp"
}

LOGIN=$(curl -s -X POST "$BASE/auth/login" -H "Content-Type: application/json" \
  --data-raw "{\"username\":\"admin\",\"password\":\"$ADMIN_PW\"}")
TOKEN="$(json_str "$LOGIN" accessToken)"
[ -n "$TOKEN" ] || { fail "登录失败"; exit 1; }
say "登录 OK"

# ---------- 4. 项目 + 仓库 remoteUrl ----------
PROJ=$(req POST /projects "{\"name\":\"cap115-m3-$TS\",\"path\":\"${WORK//\\//}/git/projrepo\",\"defaultBranch\":\"master\",\"status\":\"ACTIVE\"}")
CODE=$(cat "$WORK/.code"); check "创建项目" "$CODE" "200"
PID="$(json_str "$PROJ" id)"
REPOS=$(req GET "/projects/$PID/repos")
REPO_ID="$(json_num "$REPOS" id)"
UPD=$(req PUT "/projects/$PID/repos/$REPO_ID" "{\"remoteUrl\":\"file:///${REMOTE//\\//}\"}")
CODE=$(cat "$WORK/.code"); check "仓库补登 remoteUrl" "$CODE" "200"

# ---------- 5. 节点 + runner ----------
NODE_JSON=$(req POST /agent-nodes '{"name":"cap115-m3","labels":"e2e"}')
NODE_ID="$(json_num "$NODE_JSON" id)"
NODE_TOKEN="$(json_str "$NODE_JSON" token)"
[ -n "$NODE_ID" ] && [ -n "$NODE_TOKEN" ] || { fail "节点签发失败"; exit 1; }
RDIR="$WORK/runner"; mkdir -p "$RDIR"
cp "$ROOT/devmind-agent-runner/target/devmind-agent-runner.jar" "$RDIR/" || { fail "runner jar 缺失，先 mvn -q install -DskipTests"; exit 1; }
cat > "$RDIR/agent.properties" <<EOF
serverUrl=ws://127.0.0.1:$PORT/ws/agent
token=$NODE_TOKEN
executor=fake
execAllowlist=bash
execShell=$(which bash | sed 's/\\/\//g')
workDir=${WORK//\\//}/runner/work
workspaceRoot=${WORK//\\//}/runner/workspaces
worklogRoot=${WORK//\\//}/runner/worklog
gitProxyEnabled=false
maxConcurrent=4
gcInitialDelayMinutes=600
gcIntervalMinutes=600
partialMessages=false
EOF
( cd "$RDIR" && java -jar devmind-agent-runner.jar agent.properties >"$WORK/runner.log" 2>&1 ) &
RUNNER_PID=$!
say "runner 启动中 (pid=$RUNNER_PID)..."
DEADLINE=$((SECONDS + 90))
while :; do
  NODES=$(req GET /agent-nodes)
  if grep -q "\"id\":$NODE_ID" <<<"$NODES" && grep -q '"status":"ONLINE"' <<<"$NODES"; then break; fi
  if ! kill -0 "$RUNNER_PID" 2>/dev/null; then fail "runner 退出，见 $WORK/runner.log"; exit 1; fi
  [ $SECONDS -gt $DEADLINE ] && { fail "runner 未上线（90s），见 $WORK/runner.log"; exit 1; }
  sleep 2
done
PV="$(json_num "$NODES" protocolVersion)"
say "runner ONLINE，protocolVersion=$PV"
[ "$PV" -ge 26 ] 2>/dev/null || fail "runner 协议版本 $PV < 26（runner jar 未随 v26 重建？）"

# ---------- 6. 工作区 A：终端里提交一笔改动 ----------
TW=$(req POST /terminal-workspaces "{\"projectId\":\"$PID\",\"agentNodeId\":\"$NODE_ID\"}")
CODE=$(cat "$WORK/.code"); check "新建终端工作区 A" "$CODE" "200"
TW_ID="$(json_str "$TW" id)"; WS_KEY="$(json_str "$TW" workspaceKey)"
OPEN=$(req POST "/terminal-workspaces/$TW_ID/terminal/open")
CODE=$(cat "$WORK/.code"); check "打开终端 A" "$CODE" "200"
WS_PATH="$(json_str "$OPEN" path)"
say "工作区 A: key=$WS_KEY path=$WS_PATH"

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"echo cap115-m3-change | tee m3.txt","cwd":"projrepo"}')
check "写 m3.txt" "$(json_str "$R" ok)" "true"
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"git add -A","cwd":"projrepo"}')
check "git add" "$(json_str "$R" ok)" "true"
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"git -c user.email=e2e@cap115.local -c user.name=cap115 commit -m cap115-m3-change","cwd":"projrepo"}')
check "git commit" "$(json_str "$R" ok)" "true"

# ---------- 7. 收口门禁：confirmCode / 脏工作区 ----------
R=$(req POST "/terminal-workspaces/$TW_ID/finalize" '{"confirmCode":"wrong-code"}')
CODE=$(cat "$WORK/.code"); check "confirmCode 不符 400" "$CODE" "400"

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"echo dirty-uncommitted | tee dirty.txt","cwd":"projrepo"}')
check "造脏文件" "$(json_str "$R" ok)" "true"
# 收口要求终端已关闭（运行中会话先 409）
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/close")
CODE=$(cat "$WORK/.code"); check "关闭终端 A" "$CODE" "200"
R=$(req POST "/terminal-workspaces/$TW_ID/finalize" "{\"confirmCode\":\"$WS_KEY\"}")
CODE=$(cat "$WORK/.code"); check "脏工作区收口 409" "$CODE" "409"
check "409 列脏文件" "$R" "dirty.txt"

# ---------- 8. discardChanges 收口成功：远端基线含提交 + 状态/明细/审计 ----------
R=$(req POST "/terminal-workspaces/$TW_ID/finalize" "{\"confirmCode\":\"$WS_KEY\",\"discardChanges\":true}")
CODE=$(cat "$WORK/.code"); check "discardChanges 收口 200" "$CODE" "200"
check "状态 FINALIZED" "$(json_str "$R" workspaceState)" "FINALIZED"
check "收口明细非空" "$R" "projrepo"

REMOTE_LOG=$(git -C "$REMOTE" log master --oneline 2>&1)
check "远端基线含会话提交" "$REMOTE_LOG" "cap115-m3-change"

D=$(req GET "/terminal-workspaces/$TW_ID/finalize-detail")
CODE=$(cat "$WORK/.code"); check "finalize-detail 200" "$CODE" "200"
check "finalize-detail 有明细" "$D" "projrepo"

req POST "/terminal-workspaces/$TW_ID/terminal/close" >/dev/null 2>&1
H=$(req GET "/terminal-workspaces/$TW_ID/history")
check "审计含 WS_FINALIZE" "$H" "WS_FINALIZE"

# 收口后不可再操作
R=$(req POST "/terminal-workspaces/$TW_ID/finalize" "{\"confirmCode\":\"$WS_KEY\"}")
CODE=$(cat "$WORK/.code"); check "重复收口 409" "$CODE" "409"

# ---------- 9. 工作区 B：释放链路（脏拒 → 强制释放 → 目录删除 → 审计） ----------
TW2=$(req POST /terminal-workspaces "{\"projectId\":\"$PID\",\"agentNodeId\":\"$NODE_ID\"}")
CODE=$(cat "$WORK/.code"); check "新建终端工作区 B" "$CODE" "200"
TW2_ID="$(json_str "$TW2" id)"; WS2_KEY="$(json_str "$TW2" workspaceKey)"
OPEN2=$(req POST "/terminal-workspaces/$TW2_ID/terminal/open")
CODE=$(cat "$WORK/.code"); check "打开终端 B" "$CODE" "200"
WS2_PATH="$(json_str "$OPEN2" path)"
req POST "/terminal-workspaces/$TW2_ID/terminal/close" >/dev/null 2>&1
[ -d "$WS2_PATH" ] && say "OK: 工作区 B 目录已物化 $WS2_PATH" || fail "工作区 B 目录不存在: $WS2_PATH"

# 直接落盘造脏（不经终端通道，验证服务端 workspace_query 预检）
echo "orphan-dirty" > "$WS2_PATH/projrepo/dirty-b.txt" || { fail "写脏文件失败"; exit 1; }

R=$(req DELETE "/terminal-workspaces/$TW2_ID")
CODE=$(cat "$WORK/.code"); check "脏工作区释放 409" "$CODE" "409"
check "释放 409 列脏文件" "$R" "dirty-b.txt"

R=$(req DELETE "/terminal-workspaces/$TW2_ID?discardChanges=true")
CODE=$(cat "$WORK/.code"); check "discardChanges 释放 200" "$CODE" "200"
check "状态 RELEASED" "$(json_str "$R" state)" "RELEASED"
sleep 1
[ -d "$WS2_PATH" ] && fail "释放后 worktree 目录仍在: $WS2_PATH" || say "OK: 释放后 worktree 目录已删除"

H2=$(req GET "/terminal-workspaces/$TW2_ID/history")
check "审计含 WS_RELEASE" "$H2" "WS_RELEASE"

R=$(req DELETE "/terminal-workspaces/$TW2_ID?discardChanges=true")
CODE=$(cat "$WORK/.code"); check "重复释放 409" "$CODE" "409"

# ---------- 10. 汇总 ----------
say "产物目录: $WORK"
if [ "$FAILED" -eq 0 ]; then say "全部断言通过 ✅"; exit 0; else say "存在失败断言 ❌"; exit 1; fi
