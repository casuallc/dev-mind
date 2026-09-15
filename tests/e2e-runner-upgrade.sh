#!/usr/bin/env bash
# CAP-21 FR-09 升级链路 E2E（tmp/，gitignored）：
# 建节点 → 起 runner(fake executor) → 挂会话验证 BUSY 推迟 → 杀会话 → 上传改版本 jar →
# 触发升级 → 断言 runner 自替换重启且服务端 runnerVersion 翻新。
set -uo pipefail
BASE=http://localhost:8080
E2E=tmp/runner-e2e
cd /d/apusic/dev-mind

py() { python -c "import sys,json;d=json.load(sys.stdin.buffer);print($1)"; }

echo "== 1. 登录 =="
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | py "d['accessToken']")
AUTH="Authorization: Bearer $TOKEN"
[ -n "$TOKEN" ] && echo "login ok" || { echo "login FAILED"; exit 1; }

echo "== 2. 建节点 =="
NODE_NAME="e2e-upgrade-$(date +%s)"
RESP=$(curl -s -X POST $BASE/api/agent-nodes -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"$NODE_NAME\"}")
NODE_ID=$(echo "$RESP" | py "d['node']['id']")
NODE_TOKEN=$(echo "$RESP" | py "d['token']")
echo "node id=$NODE_ID name=$NODE_NAME"

echo "== 3. 备 runner 工作目录与配置 =="
mkdir -p $E2E/work
cp -f devmind-agent-runner/target/devmind-agent-runner.jar $E2E/devmind-agent-runner.jar
cat > $E2E/agent.properties <<EOF
serverUrl=ws://localhost:8080/ws/agent
token=$NODE_TOKEN
executor=fake
workDir=D:/apusic/dev-mind/$E2E/work
maxConcurrent=2
EOF
echo "jar copied, config written"
