#!/usr/bin/env bash
# CAP-21 FR-09 强制升级 E2E（tmp/，gitignored）：
# 前提：后端 :8080 已起 + e2e runner（e2e-upgrade- 前缀节点，fake executor）在线
# （准备见 e2e-runner-upgrade.sh；runner 用新构建的 jar，认识 force 字段）。
# 流程：上传新版本包 → 挂会话+问答 → GET active-sessions 断言清单 → 非 force 升级 BUSY →
# force 升级 ACCEPTED → 会话/问答离开活动态（kill→exit 帧正常收口）→ runner 换包重启版本翻新。
set -uo pipefail
BASE=http://localhost:8080
E2E=tmp/runner-e2e
cd /d/apusic/dev-mind
VER="0.9.8-e2e-force"

py() { python -c "import sys,json;d=json.load(sys.stdin.buffer);print($1)"; }

TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | py "d['accessToken']")
AUTH="Authorization: Bearer $TOKEN"
NODE_ID=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | py "[n for n in d if n['name'].startswith('e2e-upgrade-')][0]['id']")
echo "node id=$NODE_ID"

echo "== 1. 上传包（版本 $VER）=="
python - <<EOF
import zipfile
src = 'devmind-agent-runner/target/devmind-agent-runner.jar'
dst = 'tmp/runner-e2e/upload-runner-force.jar'
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        if item.filename == 'runner-version.txt':
            data = b'$VER'
        zout.writestr(item, data)
zin.close()
print('upload jar ready')
EOF
PKG=$(curl -s -X POST $BASE/api/agent-nodes/runner-package -H "$AUTH" -F "file=@$E2E/upload-runner-force.jar")
echo "$PKG" | grep -q "\"version\":\"$VER\"" || { echo "FAIL: 上传版本不符: $PKG"; exit 1; }

echo "== 2. 挂会话 + 问答 =="
SID=$(curl -s -X POST $BASE/api/sessions -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"taskSpec\":\"e2e force hold\",\"agentNodeId\":\"$NODE_ID\"}" | py "d['id']")
CID=$(curl -s -X POST $BASE/api/chats -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"message\":\"e2e force chat\",\"agentNodeId\":\"$NODE_ID\"}" | py "d['id']")
echo "session=$SID chat=$CID"
sleep 3

echo "== 3. active-sessions 清单 =="
AS=$(curl -s $BASE/api/agent-nodes/$NODE_ID/active-sessions -H "$AUTH")
echo "$AS"
echo "$AS" | grep -q "$SID" || { echo "FAIL: 清单缺会话 $SID"; exit 1; }
echo "$AS" | grep -q "$CID" || { echo "FAIL: 清单缺问答 $CID"; exit 1; }
echo "$AS" | grep -q '"kind":"SESSION"' || { echo "FAIL: 缺 SESSION kind"; exit 1; }
echo "$AS" | grep -q '"kind":"CHAT"' || { echo "FAIL: 缺 CHAT kind"; exit 1; }

echo "== 4. 非 force 升级 → BUSY =="
UP=$(curl -s -X POST $BASE/api/agent-nodes/$NODE_ID/upgrade -H "$AUTH")
echo "$UP"
echo "$UP" | grep -q '"status":"BUSY"' || { echo "FAIL: 预期 BUSY"; exit 1; }

echo "== 5. force 升级 → ACCEPTED =="
UP=$(curl -s -X POST "$BASE/api/agent-nodes/$NODE_ID/upgrade?force=true" -H "$AUTH")
echo "$UP"
echo "$UP" | grep -q '"status":"ACCEPTED"' || { echo "FAIL: 预期 ACCEPTED"; exit 1; }

echo "== 6. 会话/问答离开活动态（exit 帧正常收口）=="
SSTAT=""; CSTAT=""
for i in $(seq 1 30); do
  SSTAT=$(curl -s $BASE/api/sessions/$SID -H "$AUTH" | py "d['status']" 2>/dev/null || echo "")
  CSTAT=$(curl -s $BASE/api/chats/$CID -H "$AUTH" | py "d['status']" 2>/dev/null || echo "")
  case "$SSTAT" in RUNNING|WAITING_INPUT|WAITING_AUTH|"") ;; *) break ;; esac
  sleep 2
done
echo "session=$SSTAT chat=$CSTAT"
case "$SSTAT" in RUNNING|WAITING_INPUT|WAITING_AUTH|"") echo "FAIL: 会话残留活动态 $SSTAT"; exit 1 ;; esac

echo "== 7. runner 换包重启、版本翻新 =="
NEW_VER=""
for i in $(seq 1 60); do
  NEW_VER=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | py "[n for n in d if n['id']==$NODE_ID][0]['runnerVersion']" 2>/dev/null || echo "")
  [ "$NEW_VER" = "$VER" ] && break
  sleep 2
done
echo "runnerVersion -> $NEW_VER"
[ "$NEW_VER" = "$VER" ] || { echo "FAIL: 版本未翻新"; exit 1; }
echo "== E2E FORCE PASS =="
