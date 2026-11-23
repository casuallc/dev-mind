#!/usr/bin/env bash
# CAP-115 M2 终端通道 E2E：真实 runner + 真实 git 临时仓库（无 python 环境的 bash 等价验证）。
# 覆盖：open(RUNNING+重绑) / exec(git) / 危险命令 428 → confirmed 重发 → runner 硬阻断 /
#       写档白名单拦截(curl) / tee 放行 / 文件树 / 文件读取(带 sha) / 文件直写(sha 对→OK、旧 sha→409) /
#       heartbeat / history 审计链(WS_TERMINAL_PREPARE/TERMINAL_EXEC/TERMINAL_DANGER_CONFIRM/WS_QUERY/WS_FILE_WRITE) /
#       close → reopen 复用。
# 用法：先 mvn -q install -DskipTests，再 tests/e2e/cap115-terminal-channel.sh
# 产物写 tmp/cap115-e2e/<ts>/；自起 app+runner 进程退出时一律清理。
set -u

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TS="$(date +%Y%m%d-%H%M%S)"
WORK="$ROOT/tmp/cap115-e2e/$TS"
PORT="${CAP115_E2E_PORT:-18317}"
BASE="http://127.0.0.1:$PORT/api"
ADMIN_PW="e2e-cap115-$(date +%s)"
APP_PID=""
RUNNER_PID=""
FAILED=0

say() { echo "[cap115-m2] $*"; }
fail() { echo "[cap115-m2][FAIL] $*" >&2; FAILED=1; }
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
PROJ=$(req POST /projects "{\"name\":\"cap115-m2-$TS\",\"path\":\"${WORK//\\//}/git/projrepo\",\"defaultBranch\":\"master\",\"status\":\"ACTIVE\"}")
CODE=$(cat "$WORK/.code"); check "创建项目" "$CODE" "200"
PID="$(json_str "$PROJ" id)"
REPOS=$(req GET "/projects/$PID/repos")
REPO_ID="$(json_num "$REPOS" id)"
UPD=$(req PUT "/projects/$PID/repos/$REPO_ID" "{\"remoteUrl\":\"file:///${REMOTE//\\//}\"}")
CODE=$(cat "$WORK/.code"); check "仓库补登 remoteUrl" "$CODE" "200"

# ---------- 5. 节点 + runner ----------
NODE_JSON=$(req POST /agent-nodes '{"name":"cap115-m2","labels":"e2e"}')
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

# ---------- 6. 终端工作区 + open ----------
TW=$(req POST /terminal-workspaces "{\"projectId\":\"$PID\",\"agentNodeId\":\"$NODE_ID\"}")
CODE=$(cat "$WORK/.code"); check "新建终端工作区" "$CODE" "200"
TW_ID="$(json_str "$TW" id)"; WS_KEY="$(json_str "$TW" workspaceKey)"
OPEN=$(req POST "/terminal-workspaces/$TW_ID/terminal/open")
CODE=$(cat "$WORK/.code"); check "打开终端" "$CODE" "200"
check "会话 RUNNING" "$(json_str "$OPEN" state)" "RUNNING"

# ---------- 7. exec：git / 危险命令 428 → confirmed → 硬阻断 / 白名单 ----------
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"git status --short --branch","cwd":"projrepo"}')
CODE=$(cat "$WORK/.code"); check "exec git status" "$CODE" "200"
check "exec ok" "$(json_str "$R" ok)" "true"

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"rm -rf ~","cwd":"projrepo"}')
CODE=$(cat "$WORK/.code"); check "危险命令 428" "$CODE" "428"
check "NEED_CONFIRM" "$(json_str "$R" code)" "NEED_CONFIRM"

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"rm -rf ~","cwd":"projrepo","confirmed":true}')
CODE=$(cat "$WORK/.code"); check "confirmed 重发到达 runner" "$CODE" "200"
check "runner 硬阻断 rm -rf ~" "$R" "硬阻断"

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"curl http://example.com","cwd":"projrepo","confirmed":true}')
check "写档白名单拦截 curl" "$R" "不在 terminalAllowlist"

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"echo hello-terminal | tee hello.txt","cwd":"projrepo"}')
check "tee 写文件放行" "$(json_str "$R" ok)" "true"

# ---------- 8. 文件树 / 读取(sha) / 直写(乐观锁) ----------
R=$(req GET "/terminal-workspaces/$TW_ID/files/tree?path=projrepo")
check "文件树含 hello.txt" "$R" "hello.txt"

R=$(req GET "/terminal-workspaces/$TW_ID/files/file?path=projrepo/hello.txt")
CODE=$(cat "$WORK/.code"); check "文件读取" "$CODE" "200"
check "读取带内容" "$R" "hello-terminal"
SHA="$(json_str "$R" sha)"
[ -n "$SHA" ] && say "OK: 读取带 sha=$SHA" || fail "读取缺 sha"

B64_V2="$(printf 'hello-v2\n' | base64 -w0)"
R=$(req PUT "/terminal-workspaces/$TW_ID/files/file" "{\"path\":\"projrepo/hello.txt\",\"contentBase64\":\"$B64_V2\",\"expectedSha\":\"$SHA\"}")
CODE=$(cat "$WORK/.code"); check "直写(sha 匹配)" "$CODE" "200"

B64_V3="$(printf 'hello-v3\n' | base64 -w0)"
R=$(req PUT "/terminal-workspaces/$TW_ID/files/file" "{\"path\":\"projrepo/hello.txt\",\"contentBase64\":\"$B64_V3\",\"expectedSha\":\"$SHA\"}")
CODE=$(cat "$WORK/.code"); check "直写(旧 sha) 409" "$CODE" "409"

# ---------- 9. heartbeat / history / close / reopen ----------
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/heartbeat")
CODE=$(cat "$WORK/.code"); check "心跳" "$CODE" "200"

H=$(req GET "/terminal-workspaces/$TW_ID/history")
for ev in WS_TERMINAL_PREPARE TERMINAL_EXEC TERMINAL_DANGER_CONFIRM WS_QUERY WS_FILE_WRITE; do
  check "审计含 $ev" "$H" "$ev"
done

R=$(req POST "/terminal-workspaces/$TW_ID/terminal/close")
check "关闭 CLOSED" "$(json_str "$R" state)" "CLOSED"
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/open")
CODE=$(cat "$WORK/.code"); check "重开终端" "$CODE" "200"
check "重开 RUNNING" "$(json_str "$R" state)" "RUNNING"
R=$(req POST "/terminal-workspaces/$TW_ID/terminal/exec" '{"command":"cat hello.txt","cwd":"projrepo"}')
check "重开后读到 v2 内容" "$R" "hello-v2"
req POST "/terminal-workspaces/$TW_ID/terminal/close" >/dev/null

# ---------- 10. 汇总 ----------
say "产物目录: $WORK"
if [ "$FAILED" -eq 0 ]; then say "全部断言通过 ✅"; exit 0; else say "存在失败断言 ❌"; exit 1; fi
