/**
 * CAP-21 一键安装脚本生成器:在浏览器端拼装 runner 安装脚本并触发下载。
 * 后端零依赖——包下载端点本身支持节点 token 认证(?token=),
 * 因此脚本可内嵌 token 实现「拷到目标机跑一条命令即上线」。
 */

export interface InstallScriptOpts {
  /** WS 接入地址,如 ws://172.20.140.224:8080/ws/agent */
  serverUrl: string
  /** runner 包下载地址(不含 query),如 http://172.20.140.224:8080/api/agent-nodes/runner-package/download */
  downloadUrl: string
  /** 内嵌节点 token;null = 参数化版,运行时再传 */
  token: string | null
}

/** Windows PowerShell 一键安装:检查 java → 下载 jar → 写 agent.properties → 隐藏窗口后台启动。 */
export function buildWindowsInstallScript({ serverUrl, downloadUrl, token }: InstallScriptOpts): string {
  const embedded = token !== null
  const tokenDefault = embedded ? token : ''
  return `# devmind agent-runner 一键安装(由平台生成${embedded ? ',已内嵌节点 token' : ',token 需运行时传入'})
# 用法: powershell -ExecutionPolicy Bypass -File install-runner.ps1${embedded ? '' : ' -Token dmag_xxx'}
param(
  [string]$Token = '${tokenDefault}',
  [string]$InstallDir = "$env:USERPROFILE\\devmind-runner"
)
$ErrorActionPreference = 'Stop'

if (-not $Token) { throw '缺少节点 token,请传入 -Token <dmag_xxx>' }

# 1. 检查 Java(需 JRE/JDK 21+)
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
  throw '未找到 java 命令,请先安装 JRE/JDK 21 并加入 PATH'
}

# 2. 下载 runner 包
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$jar = Join-Path $InstallDir 'devmind-agent-runner.jar'
Write-Host '[install] 下载 runner 包...'
try {
  Invoke-WebRequest -Uri "${downloadUrl}?token=$Token" -OutFile $jar
} catch {
  throw "下载失败(服务端是否已在「Runner 包」页签上传包?): $($_.Exception.Message)"
}

# 3. 写配置(UTF-8 无 BOM;workDir 归一为正斜杠)
$workDir = (Join-Path $InstallDir 'work') -replace '\\\\', '/'
$conf = Join-Path $InstallDir 'agent.properties'
$lines = @(
  'serverUrl=${serverUrl}',
  "token=$Token",
  "workDir=$workDir",
  'maxConcurrent=4',
  'executor=claude'
)
[IO.File]::WriteAllLines($conf, $lines, (New-Object Text.UTF8Encoding($false)))

# 4. 隐藏窗口后台启动(日志: runner.log / runner.err.log;PS 5.1 下重定向勿配 -NoNewWindow)
Start-Process -FilePath 'java' \`
  -ArgumentList '-jar', ('"' + $jar + '"'), ('"' + $conf + '"') \`
  -WorkingDirectory $InstallDir -WindowStyle Hidden \`
  -RedirectStandardOutput (Join-Path $InstallDir 'runner.log') \`
  -RedirectStandardError (Join-Path $InstallDir 'runner.err.log')
Write-Host "[install] 完成:runner 已后台启动,目录 $InstallDir"
Write-Host '[install] 到平台「Agent 节点」页确认节点已 ONLINE'
`
}

/** Linux/bash 一键安装:同上,后台用 nohup,pid 落 runner.pid。 */
export function buildLinuxInstallScript({ serverUrl, downloadUrl, token }: InstallScriptOpts): string {
  const embedded = token !== null
  return `#!/usr/bin/env bash
# devmind agent-runner 一键安装(由平台生成${embedded ? ',已内嵌节点 token' : ''})
# 用法: bash install-runner.sh${embedded ? '' : ' dmag_xxx'}
set -euo pipefail

TOKEN="\${1:-${embedded ? token : ''}}"
if [ -z "$TOKEN" ]; then
  echo '[install] 缺少节点 token: bash install-runner.sh <dmag_xxx>' >&2
  exit 1
fi
if ! command -v java >/dev/null 2>&1; then
  echo '[install] 未找到 java,请先安装 JRE/JDK 21 并加入 PATH' >&2
  exit 1
fi

INSTALL_DIR="$HOME/devmind-runner"
mkdir -p "$INSTALL_DIR/work"
cd "$INSTALL_DIR"

echo '[install] 下载 runner 包...'
if ! curl -fSL -o devmind-agent-runner.jar "${downloadUrl}?token=$TOKEN"; then
  echo '[install] 下载失败:服务端是否已在「Runner 包」页签上传 runner 包?' >&2
  exit 1
fi

cat > agent.properties <<EOF
serverUrl=${serverUrl}
token=$TOKEN
workDir=$INSTALL_DIR/work
maxConcurrent=4
executor=claude
EOF

echo '[install] 后台启动 runner(日志 runner.log)...'
nohup java -jar devmind-agent-runner.jar agent.properties >> runner.log 2>&1 &
echo $! > runner.pid
echo "[install] 完成:pid=$(cat runner.pid),目录 $INSTALL_DIR"
echo '[install] 到平台「Agent 节点」页确认节点已 ONLINE'
`
}

/** 触发浏览器下载文本文件。ps1 含中文必须带 BOM(bom=true),否则 PS 5.1 按 GBK 解析乱码报错。 */
export function downloadTextFile(filename: string, content: string, bom = false): void {
  const blob = new Blob([bom ? '\uFEFF' + content : content], { type: 'text/plain;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filename
  a.click()
  URL.revokeObjectURL(a.href)
}
