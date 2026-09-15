#!/usr/bin/env bash
# FR-09 E2E 主流程 v2：先传包 → BUSY 验证 → 杀会话 → 升级 → 断言换包重启
set -uo pipefail
BASE=http://localhost:8080
E2E=tmp/runner-e2e
cd /d/apusic/dev-mind

py() { python -c "import sys,json;d=json.load(sys.stdin.buffer);print($1)"; }

TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | py "d['accessToken']")
AUTH="Authorization: Bearer $TOKEN"
NODE_ID=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | py "[n for n in d if n['name'].startswith('e2e-upgrade-')][0]['id']")
echo "node id=$NODE_ID"

echo "== 1. 上传包（版本 0.9.9-e2e）=="
python - <<'EOF'
import zipfile
src = 'devmind-agent-runner/target/devmind-agent-runner.jar'
dst = 'tmp/runner-e2e/upload-runner.jar'
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'runner-version.txt':
            data = b'0.9.9-e2e'
        zout.writestr(item, data)
zin.close()
print('upload jar ready')
EOF
PKG=$(curl -s -X POST $BASE/api/agent-nodes/runner-package -H "$AUTH" -F "file=@$E2E/upload-runner.jar")
echo "pkg: $PKG"
echo "$PKG" | grep -q '"version":"0.9.9-e2e"' || { echo "FAIL: 上传版本不符"; exit 1; }

echo "== 2. BUSY 场景：挂 fake 会话再升级 =="
SID=$(curl -s -X POST $BASE/api/sessions -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"taskSpec\":\"e2e busy hold\",\"agentNodeId\":\"$NODE_ID\"}" | py "d['id']")
echo "session=$SID"
sleep 3
UP=$(curl -s -X POST $BASE/api/agent-nodes/$NODE_ID/upgrade -H "$AUTH")
echo "upgrade resp: $UP"
echo "$UP" | grep -q '"status":"BUSY"' || { echo "FAIL: 预期 BUSY"; exit 1; }
echo "BUSY ok（会话未被杀）"

echo "== 3. 杀会话，触发升级 =="
curl -s -X POST $BASE/api/sessions/$SID/kill -H "$AUTH" >/dev/null
sleep 3
OLD_VER=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | py "[n for n in d if n['id']==$NODE_ID][0]['runnerVersion']")
UP=$(curl -s -X POST $BASE/api/agent-nodes/$NODE_ID/upgrade -H "$AUTH")
echo "upgrade resp: $UP"
echo "$UP" | grep -q '"status":"ACCEPTED"' || { echo "FAIL: 预期 ACCEPTED"; exit 1; }

echo "== 4. 等 runner 换包重启、版本翻新 =="
NEW_VER=""
for i in $(seq 1 60); do
  NEW_VER=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | py "[n for n in d if n['id']==$NODE_ID][0]['runnerVersion']" 2>/dev/null || echo "")
  [ "$NEW_VER" = "0.9.9-e2e" ] && break
  sleep 2
done
echo "runnerVersion: $OLD_VER -> $NEW_VER"
[ "$NEW_VER" = "0.9.9-e2e" ] || { echo "FAIL: 版本未翻新"; exit 1; }

echo "== 5. 断言 jar 已替换 + 节点 ONLINE + 二次升级 ALREADY_LATEST =="
unzip -p $E2E/devmind-agent-runner.jar runner-version.txt
echo ""
STATUS=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | py "[n for n in d if n['id']==$NODE_ID][0]['status']")
echo "status=$STATUS"
[ "$STATUS" = "ONLINE" ] || { echo "FAIL: 节点未恢复在线"; exit 1; }
UP2=$(curl -s -X POST $BASE/api/agent-nodes/$NODE_ID/upgrade -H "$AUTH")
echo "again: $UP2"
echo "$UP2" | grep -q '"status":"ALREADY_LATEST"' || { echo "FAIL: 预期 ALREADY_LATEST"; exit 1; }
echo "== E2E PASS =="
