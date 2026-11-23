#!/usr/bin/env bash
# CAP-115 M1 终端工作区 E2E：真实 runner + 真实 git 临时仓库（无 python 环境的 bash 等价验证）。
# 覆盖：新建(term- 键物化+落库) / 同键挂接 409 / 加锁续期 / 解锁 / 反向占用预检(会话 create 409)。
# 用法：先 mvn -q install -DskipTests，再 tests/e2e/cap115-terminal-workspace.sh
# 产物写 tmp/cap115-e2e/<ts>/；自起 app+runner 进程退出时一律清理。
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
WORK="$ROOT/tmp/cap115-e2e/$TS"
PORT="${CAP115_E2E_PORT:-18315}"
BASE="http://127.0.0.1:$PORT/api"
ADMIN_PW="e2e-cap115-$(date +%s)"
APP_PID=""
RUNNER_PID=""
FAILED=0

say() { echo "[cap115] $*"; }
fail() { echo "[cap115][FAIL] $*" >&2; FAILED=1; }
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
  && echo "cap115 seed" > README.md && git add -A \
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
json_num() { grep -o "\"$2\"[[:space:]]*:[[:space:]]*[0-9]*" <<<"$1" | head -1 | sed "s/.*\"$2\"[[:space:]]*:[[:space:]]*//"; }

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
PROJ=$(req POST /projects "{\"name\":\"cap115-e2e-$TS\",\"path\":\"${WORK//\\//}/git/projrepo\",\"defaultBranch\":\"master\",\"status\":\"ACTIVE\"}")
CODE=$(cat "$WORK/.code"); check "创建项目" "$CODE" "200"
PID="$(json_str "$PROJ" id)"
REPOS=$(req GET "/projects/$PID/repos")
REPO_ID="$(json_num "$REPOS" id)"
UPD=$(req PUT "/projects/$PID/repos/$REPO_ID" "{\"remoteUrl\":\"file:///${REMOTE//\\//}\"}")
CODE=$(cat "$WORK/.code"); check "仓库补登 remoteUrl" "$CODE" "200"
say "项目 $PID 主库 $REPO_ID remoteUrl=file://$REMOTE"

# ---------- 5. 节点 + runner ----------
NODE_JSON=$(req POST /agent-nodes '{"name":"cap115-e2e","labels":"e2e"}')
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
  LINE=$(tr ',' '\n' <<<"$NODES" | grep -A0 "\"id\":$NODE_ID" || true)
  if grep -q "\"id\":$NODE_ID" <<<"$NODES" && grep -q '"status":"ONLINE"' <<<"$NODES"; then break; fi
  if ! kill -0 "$RUNNER_PID" 2>/dev/null; then fail "runner 退出，见 $WORK/runner.log"; exit 1; fi
  [ $SECONDS -gt $DEADLINE ] && { fail "runner 未上线（90s），见 $WORK/runner.log"; exit 1; }
  sleep 2
done
PV="$(json_num "$NODES" protocolVersion)"
say "runner ONLINE，protocolVersion=$PV"
[ "$PV" -ge 26 ] 2>/dev/null || fail "runner 协议版本 $PV < 26（runner jar 未随 v26 重建？）"

# ---------- 6. 新建终端工作区 ----------
TW=$(req POST /terminal-workspaces "{\"projectId\":\"$PID\",\"agentNodeId\":\"$NODE_ID\"}")
CODE=$(cat "$WORK/.code"); check "新建终端工作区" "$CODE" "200"
TW_ID="$(json_str "$TW" id)"; WS_KEY="$(json_str "$TW" workspaceKey)"; BR="$(json_str "$TW" branch)"
STATE="$(json_str "$TW" state)"
check "状态 OPEN" "$STATE" "OPEN"
[[ "$WS_KEY" =~ ^term-[a-z0-9]{8}$ ]] && say "OK: 键 $WS_KEY" || fail "键格式错误: $WS_KEY"
check "分支 terminal/ 前缀" "$BR" "terminal/${WS_KEY#term-}"

# runner 磁盘物化断言：worktrees/<key>/ 下应有检出且分支正确
AGG="$RDIR/workspaces/worktrees/$WS_KEY"
[ -d "$AGG" ] || fail "runner 未物化工作区目录: $AGG"
FOUND_BRANCH=""
for d in "$AGG"/*/; do
  [ -e "$d/.git" ] || continue
  FOUND_BRANCH="$(git -C "$d" branch --show-current 2>/dev/null)"
  break
done
check "runner 工作树分支" "$FOUND_BRANCH" "$BR"

# 幂等重入：attach 同键应 409（同键 OPEN 冲突）
AGAIN=$(req POST /terminal-workspaces/attach "{\"workspaceKey\":\"$WS_KEY\",\"projectId\":\"$PID\"}")
CODE=$(cat "$WORK/.code"); check "同键挂接 409" "$CODE" "409"

# ---------- 7. 锁 ----------
LOCK=$(req POST "/terminal-workspaces/$TW_ID/lock")
CODE=$(cat "$WORK/.code"); check "加锁" "$CODE" "200"
check "锁归属 admin" "$(json_str "$LOCK" lockOwner)" "admin"
UNLOCK=$(req POST "/terminal-workspaces/$TW_ID/unlock")
CODE=$(cat "$WORK/.code"); check "解锁" "$CODE" "200"
[ -z "$(json_str "$UNLOCK" lockOwner)" ] && say "OK: 解锁后 lockOwner 为空" || fail "解锁未生效"

# ---------- 8. 反向占用互斥：attach req- 键 → 会话 create 409 ----------
REQV=$(req POST "/projects/$PID/requirements" '{"title":"cap115 占用互斥","type":"TASK"}')
RID="$(json_str "$REQV" id)"
ATT=$(req POST /terminal-workspaces/attach "{\"workspaceKey\":\"req-$RID\",\"agentNodeId\":\"$NODE_ID\"}")
CODE=$(cat "$WORK/.code"); check "挂接需求工作区" "$CODE" "200"
SESS=$(req POST /sessions "{\"projectId\":\"$PID\",\"requirementId\":\"$RID\",\"taskSpec\":\"e2e 占用预检\",\"agentNodeId\":\"$NODE_ID\"}")
CODE=$(cat "$WORK/.code"); check "会话 create 被终端工作区占用挡下" "$CODE" "409"
check "409 话术带占用方" "$SESS" "终端工作区"

# ---------- 9. 汇总 ----------
say "产物目录: $WORK"
if [ "$FAILED" -eq 0 ]; then say "全部断言通过 ✅"; exit 0; else say "存在失败断言 ❌"; exit 1; fi
