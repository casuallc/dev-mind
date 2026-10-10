#!/usr/bin/env bash
# 一键安装脚本 E2E(tmp/,gitignored):
# 服务端按节点 token 下发脚本(GET /api/agent-nodes/{id}/install-script) → sh --no-service
# 实跑(HOME 指向隔离目录) → 断言节点 ONLINE 且 runnerVersion 非空 → 静态断言服务路径
# (systemd/WinSW + DEVMIND_RUNNER_SERVICE) → powershell parse 检查 ps1 → 负例(坏 token/匿名 401) → 清理。
set -uo pipefail
BASE=http://localhost:8080
E2E=tmp/install-e2e
cd /d/apusic/dev-mind
rm -rf $E2E && mkdir -p $E2E/home

py() { python -c "import sys,json;d=json.load(sys.stdin.buffer);print($1)"; }

echo "== 1. 登录 + 确认 runner 包已上传 =="
TOKEN=$(curl -s -X POST $BASE/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | py "d['accessToken']")
AUTH="Authorization: Bearer $TOKEN"
PKG=$(curl -s $BASE/api/agent-nodes/runner-package -H "$AUTH")
if echo "$PKG" | grep -q '"version"'; then
  echo "pkg: $(echo "$PKG" | py "d['version']")"
else
  echo "未上传,上传当前构建 jar..."
  PKG=$(curl -s -X POST $BASE/api/agent-nodes/runner-package -H "$AUTH" \
    -F "file=@devmind-agent-runner/target/devmind-agent-runner.jar")
  echo "pkg: $PKG"
  echo "$PKG" | grep -q '"version"' || { echo "FAIL: 上传失败"; exit 1; }
fi

echo "== 2. 建节点拿 token =="
RESP=$(curl -s -X POST $BASE/api/agent-nodes -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-install-$(date +%s)\"}")
NODE_ID=$(echo "$RESP" | py "d['node']['id']")
NODE_TOKEN=$(echo "$RESP" | py "d['token']")
echo "node id=$NODE_ID"

echo "== 3. 端点下发脚本(token 内嵌 sh/ps1 + 登录态参数化 sh) =="
curl -fsSL "$BASE/api/agent-nodes/$NODE_ID/install-script?os=linux&token=$NODE_TOKEN" \
  -o $E2E/install-runner.sh || { echo "FAIL: sh 下发失败"; exit 1; }
curl -fsSL "$BASE/api/agent-nodes/$NODE_ID/install-script?os=windows&token=$NODE_TOKEN" \
  -o $E2E/install-runner.ps1 || { echo "FAIL: ps1 下发失败"; exit 1; }
curl -fsSL "$BASE/api/agent-nodes/$NODE_ID/install-script?os=linux" -H "$AUTH" \
  -o $E2E/install-runner-param.sh || { echo "FAIL: 参数化 sh 下发失败"; exit 1; }
grep -q "TOKEN=\"$NODE_TOKEN\"" $E2E/install-runner.sh || { echo "FAIL: sh 未内嵌 token"; exit 1; }
grep -q 'TOKEN=""' $E2E/install-runner-param.sh || { echo "FAIL: 参数化 sh 不应内嵌 token"; exit 1; }
echo "scripts downloaded"

echo "== 3b. 负例:坏 token / 匿名 / 坏 os =="
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/agent-nodes/$NODE_ID/install-script?os=linux&token=dmag_bad")
[ "$CODE" = "401" ] || { echo "FAIL: 坏 token 应 401,实得 $CODE"; exit 1; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/agent-nodes/$NODE_ID/install-script?os=linux")
[ "$CODE" = "401" ] || { echo "FAIL: 匿名无 token 应 401,实得 $CODE"; exit 1; }
CODE=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/agent-nodes/$NODE_ID/install-script?os=macos&token=$NODE_TOKEN")
[ "$CODE" = "400" ] || { echo "FAIL: 坏 os 应 400,实得 $CODE"; exit 1; }
echo "负例 ok"

echo "== 4. 跑下发的 sh(HOME 隔离到 $E2E/home;--no-service 走后台路径,本机无 systemd) =="
HOME="/d/apusic/dev-mind/$E2E/home" bash $E2E/install-runner.sh --no-service || { echo "FAIL: 安装脚本执行失败"; exit 1; }
[ -f $E2E/home/devmind-runner/devmind-agent-runner.jar ] || { echo "FAIL: jar 未下载"; exit 1; }
[ -f $E2E/home/devmind-runner/agent.properties ] || { echo "FAIL: 配置未生成"; exit 1; }
[ -f $E2E/home/devmind-runner/runner.pid ] || { echo "FAIL: pid 文件缺失"; exit 1; }
echo "jar/config/pid 齐备"

echo "== 5. 静态断言:默认服务路径已内嵌(systemd/WinSW + DEVMIND_RUNNER_SERVICE) =="
grep -q 'DEVMIND_RUNNER_SERVICE=1' $E2E/install-runner.sh && grep -q 'systemctl' $E2E/install-runner.sh \
  || { echo "FAIL: sh 缺 systemd 服务路径"; exit 1; }
grep -q 'WinSW' $E2E/install-runner.ps1 && grep -q 'DEVMIND_RUNNER_SERVICE' $E2E/install-runner.ps1 \
  || { echo "FAIL: ps1 缺 WinSW 服务路径"; exit 1; }
grep -q -- '--no-service' $E2E/install-runner-param.sh || { echo "FAIL: 参数化 sh 缺 --no-service"; exit 1; }
echo "服务路径断言 ok"

echo "== 6. 断言节点 ONLINE 且 runnerVersion 非空 =="
VER=""; ST=""
for i in $(seq 1 30); do
  ROW=$(curl -s $BASE/api/agent-nodes -H "$AUTH" | python -c "
import sys,json
n=[x for x in json.load(sys.stdin.buffer) if x['id']==$NODE_ID]
print((n[0]['status']+' '+(n[0].get('runnerVersion') or '')) if n else 'MISSING')")
  ST=${ROW%% *}; VER=${ROW#* }
  [ "$ST" = "ONLINE" ] && [ -n "$VER" ] && break
  sleep 2
done
echo "status=$ST version=$VER"
[ "$ST" = "ONLINE" ] && [ -n "$VER" ] || { echo "FAIL: 节点未上线"; exit 1; }

echo "== 7. 参数化 sh 缺 token 应报错 =="
HOME="/d/apusic/dev-mind/$E2E/home" bash $E2E/install-runner-param.sh 2>$E2E/param.err
[ $? -ne 0 ] && grep -q '缺少节点 token' $E2E/param.err && echo "参数化校验 ok" || { echo "FAIL: 参数化版未拦 token"; exit 1; }

echo "== 8. ps1 解析级语法检查(含 BOM) =="
powershell -NoProfile -Command "\$errs=\$null; [void][System.Management.Automation.PSParser]::Tokenize([IO.File]::ReadAllText('D:/apusic/dev-mind/$E2E/install-runner.ps1'), [ref]\$errs); if (\$errs.Count) { \$errs | ForEach-Object { Write-Host \$_.Message }; exit 1 } else { Write-Host 'ps1 parse ok' }" || { echo "FAIL: ps1 语法错误"; exit 1; }

echo "== 9. 清理 =="
kill $(cat $E2E/home/devmind-runner/runner.pid) 2>/dev/null
sleep 2
curl -s -X DELETE $BASE/api/agent-nodes/$NODE_ID -H "$AUTH" >/dev/null
echo "== E2E PASS =="
