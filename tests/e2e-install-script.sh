#!/usr/bin/env bash
# 一键安装脚本 E2E(tmp/,gitignored):
# esbuild 打包前端生成器 → 生成真实 sh/ps1 → bash 跑生成的 sh(HOME 指向隔离目录)
# → 断言节点 ONLINE 且 runnerVersion 非空 → powershell parse 检查 ps1 → 清理。
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

echo "== 2. esbuild 打包生成器,产出真实脚本 =="
./frontend/node_modules/.bin/esbuild frontend/src/features/agent/utils/installScript.ts \
  --bundle --format=cjs --outfile=$E2E/installScript.cjs --log-level=warning || { echo "FAIL: esbuild"; exit 1; }

echo "== 3. 建节点拿 token =="
RESP=$(curl -s -X POST $BASE/api/agent-nodes -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-install-$(date +%s)\"}")
NODE_ID=$(echo "$RESP" | py "d['node']['id']")
NODE_TOKEN=$(echo "$RESP" | py "d['token']")
echo "node id=$NODE_ID"

echo "== 4. 用真实生成器产出内嵌 token 的 sh + ps1 =="
NODE_TOKEN="$NODE_TOKEN" node -e "
const g = require('./$E2E/installScript.cjs')
const fs = require('fs')
const opts = { serverUrl: 'ws://localhost:8080/ws/agent',
  downloadUrl: 'http://localhost:8080/api/agent-nodes/runner-package/download',
  token: process.env.NODE_TOKEN }
fs.writeFileSync('$E2E/install-runner.sh', g.buildLinuxInstallScript(opts))
fs.writeFileSync('$E2E/install-runner.ps1', '\uFEFF' + g.buildWindowsInstallScript(opts))
const p = g.buildLinuxInstallScript({ ...opts, token: null })
fs.writeFileSync('$E2E/install-runner-param.sh', p)
console.log('scripts generated')
" || { echo "FAIL: 生成脚本"; exit 1; }

echo "== 5. 跑生成的 sh(HOME 隔离到 $E2E/home) =="
HOME="/d/apusic/dev-mind/$E2E/home" bash $E2E/install-runner.sh || { echo "FAIL: 安装脚本执行失败"; exit 1; }
[ -f $E2E/home/devmind-runner/devmind-agent-runner.jar ] || { echo "FAIL: jar 未下载"; exit 1; }
[ -f $E2E/home/devmind-runner/agent.properties ] || { echo "FAIL: 配置未生成"; exit 1; }
[ -f $E2E/home/devmind-runner/runner.pid ] || { echo "FAIL: pid 文件缺失"; exit 1; }
echo "jar/config/pid 齐备"

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
