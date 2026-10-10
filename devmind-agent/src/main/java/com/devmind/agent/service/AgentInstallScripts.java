package com.devmind.agent.service;

/**
 * CAP-21 一键安装脚本模板（服务端单一来源；前端下载/一条命令均走
 * {@code GET /api/agent-nodes/{id}/install-script}，不再浏览器端拼装）。
 *
 * <p>默认注册系统服务（Windows 服务 via WinSW / Linux systemd，均注入
 * {@code DEVMIND_RUNNER_SERVICE=1} 承接自升级退出码 42）；无管理员/root 权限自动降级后台启动，
 * {@code --no-service} / {@code -NoService} 强制后台。</p>
 *
 * <p>token 为 null 时生成参数化版（运行时传入），否则内嵌 token。</p>
 */
public final class AgentInstallScripts {

    private AgentInstallScripts() {
    }

    /** Windows PowerShell 版。含中文，下发/落地必须带 UTF-8 BOM（PS 5.1 无 BOM 按 GBK 解析）。 */
    public static String windows(String serverUrl, String downloadUrl, String token) {
        boolean embedded = token != null;
        String usage = embedded
                ? "powershell -ExecutionPolicy Bypass -File install-runner.ps1 [-NoService] [-WinSwUrl <url>]"
                : "powershell -ExecutionPolicy Bypass -File install-runner.ps1 -Token dmag_xxx [-NoService] [-WinSwUrl <url>]";
        return Template.WINDOWS
                .replace("%TOKEN%", embedded ? token : "")
                .replace("%USAGE%", usage)
                .replace("%EMBEDDED_NOTE%", embedded ? ",已内嵌节点 token" : ",token 需运行时传入")
                .replace("%SERVER_URL%", serverUrl)
                .replace("%DOWNLOAD_URL%", downloadUrl);
    }

    /** Linux/bash 版（LF 行尾，无 BOM）。 */
    public static String linux(String serverUrl, String downloadUrl, String token) {
        boolean embedded = token != null;
        String usage = embedded
                ? "sudo bash install-runner.sh [--no-service]"
                : "sudo bash install-runner.sh dmag_xxx [--no-service]";
        return Template.LINUX
                .replace("%TOKEN%", embedded ? token : "")
                .replace("%USAGE%", usage)
                .replace("%EMBEDDED_NOTE%", embedded ? ",已内嵌节点 token" : "")
                .replace("%SERVER_URL%", serverUrl)
                .replace("%DOWNLOAD_URL%", downloadUrl);
    }

    /** 模板。占位符 %SERVER_URL%/%DOWNLOAD_URL%/%TOKEN%/%USAGE%/%EMBEDDED_NOTE%；文本块闭合符顶格，输出零缩进基线。 */
    private static final class Template {

        // ps1 注意：@"..."@ here-string 的终止符 "@ 必须顶格；EAP=Stop 下原生命令 stderr 走 Invoke-Native 防误杀
        static final String WINDOWS = """
# devmind agent-runner 一键安装(由平台生成%EMBEDDED_NOTE%)
# 用法(建议管理员 PowerShell,注册系统服务): %USAGE%
param(
  [string]$Token = '%TOKEN%',
  [string]$InstallDir = "$env:USERPROFILE\\devmind-runner",
  [string]$WinSwUrl,
  [switch]$NoService
)
$ErrorActionPreference = 'Stop'

# PS 5.1 坑:脚本级 EAP=Stop 时原生命令的 stderr 输出(java -version 版本行、winsw 诊断)
# 经 2>&1 合并后会变成 ErrorRecord 直接终止脚本——原生命令统一走本函数。
function Invoke-Native([scriptblock]$Cmd, [switch]$Join) {
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    $out = & $Cmd
    if ($Join) { return ($out | Out-String).Trim() }
    return $out
  } finally {
    $ErrorActionPreference = $prev
  }
}

# Java 解析顺序:APUSIC_JAVA_HOME > JAVA_HOME > PATH,必须 21+(服务以绝对路径写入配置)
function Resolve-Java {
  $candidates = @()
  if ($env:APUSIC_JAVA_HOME) { $candidates += (Join-Path $env:APUSIC_JAVA_HOME 'bin\\java.exe') }
  if ($env:JAVA_HOME)        { $candidates += (Join-Path $env:JAVA_HOME 'bin\\java.exe') }
  $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
  if ($onPath) { $candidates += $onPath.Source }
  foreach ($java in $candidates) {
    if (-not (Test-Path $java)) { continue }
    $verLine = Invoke-Native { & $java -version 2>&1 | Select-Object -First 1 } -Join
    if ($verLine -match 'version "(\\d+)') {
      if ([int]$Matches[1] -ge 21) { return (Resolve-Path $java).Path }
    }
  }
  throw '未找到 JDK 21+:请安装 JDK 21 并加入 PATH,或设 APUSIC_JAVA_HOME/JAVA_HOME 指向 JDK 21 目录'
}

if (-not $Token) { throw '缺少节点 token,请传入 -Token <dmag_xxx>' }
$java = Resolve-Java

# 1. 下载 runner 包
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
$jar = Join-Path $InstallDir 'devmind-agent-runner.jar'
Write-Host '[install] 下载 runner 包...'
try {
  Invoke-WebRequest -Uri "%DOWNLOAD_URL%?token=$Token" -OutFile $jar
} catch {
  throw "下载失败(服务端是否已在「Runner 包」页签上传包?): $($_.Exception.Message)"
}

# 2. 写配置(UTF-8 无 BOM;workDir 归一为正斜杠)
$workDir = (Join-Path $InstallDir 'work') -replace '\\\\', '/'
$conf = Join-Path $InstallDir 'agent.properties'
$lines = @(
  'serverUrl=%SERVER_URL%',
  "token=$Token",
  "workDir=$workDir",
  'maxConcurrent=4',
  'executor=claude',
  '#gcDays=14  # 会话工作区超龄回收天数(CAP-34 FR-05,默认 14)',
  '#labels=    # 节点标签 CSV(CAP-34 FR-07,如 windows,office;非空时 hello 覆盖服务端编辑值)',
  '#claudeConfigDir=  # 服务化部署必配:指向已登录 claude 的 .claude 目录(如 C:/Users/<用户名>/.claude),服务账户读不到你本机登录态'
)
[IO.File]::WriteAllLines($conf, $lines, (New-Object Text.UTF8Encoding($false)))

# 3. 隐藏窗口后台启动(降级路径;日志: runner.log / runner.err.log)
function Start-Background {
  Start-Process -FilePath $java `
    -ArgumentList '-jar', ('"' + $jar + '"'), ('"' + $conf + '"') `
    -WorkingDirectory $InstallDir -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $InstallDir 'runner.log') `
    -RedirectStandardError (Join-Path $InstallDir 'runner.err.log')
  Write-Host "[install] 完成(后台模式):runner 已后台启动,目录 $InstallDir"
}

# 4. 默认注册 Windows 服务(WinSW);非管理员自动降级后台启动
$ServiceName = if ($env:DEVMIND_SERVICE_NAME) { $env:DEVMIND_SERVICE_NAME } else { 'devmind-agent' }
$isAdmin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

if ($NoService) {
  Start-Background
} elseif (-not $isAdmin) {
  Write-Host '[install] 当前非管理员,无法注册 Windows 服务,降级为后台启动(以管理员身份重跑本脚本即注册服务)'
  Start-Background
} else {
  # WinSW v2 系:.NET Framework 4.6.1+ 即可运行(Win10/Server2016+ 自带);exe 与同名 xml 配对
  $winswExe = Join-Path $InstallDir "$ServiceName.exe"
  $winswXml = Join-Path $InstallDir "$ServiceName.xml"
  if (-not (Test-Path $winswExe)) {
    $url = if ($WinSwUrl) { $WinSwUrl } elseif ($env:WINSW_URL) { $env:WINSW_URL } else { 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe' }
    Write-Host "[install] 下载 WinSW: $url"
    try {
      Invoke-WebRequest -Uri $url -OutFile $winswExe -UseBasicParsing
    } catch {
      Remove-Item $winswExe -Force -ErrorAction SilentlyContinue
      throw "WinSW 下载失败:$($_.Exception.Message) —— 可离线自带:下载 WinSW.NET461.exe 放到 $InstallDir 并改名 $ServiceName.exe 后重试(或用 -WinSwUrl 指定内网地址)"
    }
  }
  $logDir = Join-Path $InstallDir 'logs'
  New-Item -ItemType Directory -Force $logDir | Out-Null
  $esc = { param([string]$s) [Security.SecurityElement]::Escape($s) }
  $xml = @"
<service>
  <id>$(& $esc $ServiceName)</id>
  <name>Dev-Mind Agent Runner</name>
  <description>Dev-Mind agent runner (CAP-34 executor)</description>
  <executable>$(& $esc $java)</executable>
  <arguments>-jar devmind-agent-runner.jar agent.properties</arguments>
  <workingdirectory>$(& $esc $InstallDir)</workingdirectory>
  <!-- 服务管理模式标记:runner 升级换包后以退出码 42 退出,由本服务 onfailure 拉起新 jar -->
  <env name="DEVMIND_RUNNER_SERVICE" value="1"/>
  <logpath>$(& $esc $logDir)</logpath>
  <logmode>roll</logmode>
  <onfailure action="restart" delay="5 sec"/>
</service>
"@
  [IO.File]::WriteAllText($winswXml, $xml, (New-Object Text.UTF8Encoding($false)))
  if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
    Write-Host "[install] 服务 $ServiceName 已存在,重启加载新包"
    Restart-Service -Name $ServiceName -Force
  } else {
    Write-Host "[install] 注册服务 $ServiceName (java=$java home=$InstallDir)"
    Invoke-Native { & $winswExe install }
    if ($LASTEXITCODE -ne 0) { throw "winsw install 失败(exit $LASTEXITCODE)" }
    Start-Service -Name $ServiceName
  }
  Write-Host "[install] 完成(服务模式):服务 $ServiceName 已启动,目录 $InstallDir"
}
Write-Host '[install] 到平台「Agent 节点」页确认节点已 ONLINE'
""";

        // bash 注意:set -euo pipefail;unit heredoc 用非引号 EOF 让 $INSTALL_DIR/$JAVA_BIN 运行期展开
        static final String LINUX = """
#!/usr/bin/env bash
# devmind agent-runner 一键安装(由平台生成%EMBEDDED_NOTE%)
# 用法(注册系统服务需 root,建议 sudo): %USAGE%
set -euo pipefail

NO_SERVICE=0
TOKEN="%TOKEN%"
for a in "$@"; do
  case "$a" in
    --no-service) NO_SERVICE=1 ;;
    *) TOKEN="$a" ;;
  esac
done
if [ -z "$TOKEN" ]; then
  echo '[install] 缺少节点 token: bash install-runner.sh <dmag_xxx>' >&2
  exit 1
fi

# Java 解析顺序:APUSIC_JAVA_HOME > JAVA_HOME > PATH,必须 21+(服务以解析结果绝对路径写入 unit)
resolve_java() {
  local j ver
  for j in "${APUSIC_JAVA_HOME:+$APUSIC_JAVA_HOME/bin/java}" "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java || true)"; do
    [ -n "$j" ] && [ -x "$j" ] || continue
    ver="$("$j" -version 2>&1 | sed -n 's/.*version "\\([0-9][0-9]*\\).*/\\1/p' | head -1)"
    if [ -n "$ver" ] && [ "$ver" -ge 21 ]; then echo "$j"; return 0; fi
  done
  return 1
}
if ! JAVA_BIN="$(resolve_java)"; then
  echo '[install] 未找到 JDK 21+:请安装 JDK 21 并加入 PATH,或设 APUSIC_JAVA_HOME/JAVA_HOME' >&2
  exit 1
fi

INSTALL_DIR="$HOME/devmind-runner"
mkdir -p "$INSTALL_DIR/work"
cd "$INSTALL_DIR"

echo '[install] 下载 runner 包...'
if ! curl -fSL -o devmind-agent-runner.jar "%DOWNLOAD_URL%?token=$TOKEN"; then
  echo '[install] 下载失败:服务端是否已在「Runner 包」页签上传 runner 包?' >&2
  exit 1
fi

cat > agent.properties <<EOF
serverUrl=%SERVER_URL%
token=$TOKEN
workDir=$INSTALL_DIR/work
maxConcurrent=4
executor=claude
#gcDays=14  # 会话工作区超龄回收天数(CAP-34 FR-05,默认 14)
#labels=    # 节点标签 CSV(CAP-34 FR-07,如 windows,office;非空时 hello 覆盖服务端编辑值)
#claudeConfigDir=  # 服务化部署必配:指向已登录 claude 的 .claude 目录(如 /home/<用户名>/.claude),服务账户读不到你本机登录态
EOF

start_background() {
  echo '[install] 后台启动 runner(日志 runner.log)...'
  nohup "$JAVA_BIN" -jar devmind-agent-runner.jar agent.properties >> runner.log 2>&1 &
  echo $! > runner.pid
  echo "[install] 完成(后台模式):pid=$(cat runner.pid),目录 $INSTALL_DIR"
}

install_service() {
  local sudo=""
  if [ "$(id -u)" != 0 ]; then
    if command -v sudo >/dev/null 2>&1; then sudo="sudo"; else return 1; fi
  fi
  echo '[install] 注册 systemd 服务 devmind-agent...'
  $sudo tee /etc/systemd/system/devmind-agent.service >/dev/null <<EOF
[Unit]
Description=Dev-Mind agent runner (CAP-34 executor)
After=network.target

[Service]
Type=simple
WorkingDirectory=$INSTALL_DIR
# 服务管理模式标记:runner 升级换包后以退出码 42 退出,由本 unit 的 on-failure 拉起新 jar,
# SelfUpdater 不自行 spawn(防与 systemd 双份起进程)
Environment=DEVMIND_RUNNER_SERVICE=1
ExecStart=$JAVA_BIN -jar $INSTALL_DIR/devmind-agent-runner.jar $INSTALL_DIR/agent.properties
Restart=on-failure
RestartSec=5
SuccessExitStatus=143

[Install]
WantedBy=multi-user.target
EOF
  $sudo systemctl daemon-reload
  $sudo systemctl enable devmind-agent
  $sudo systemctl restart devmind-agent
  echo "[install] 完成(服务模式):systemctl status devmind-agent 查看,目录 $INSTALL_DIR"
}

if [ "$NO_SERVICE" = 0 ] && command -v systemctl >/dev/null 2>&1; then
  if ! install_service; then
    echo '[install] 警告:无 root/sudo 权限,无法注册系统服务,降级为后台启动(用 sudo 重跑本脚本即注册服务)' >&2
    start_background
  fi
else
  if [ "$NO_SERVICE" = 0 ]; then
    echo '[install] 未检测到 systemd,采用后台启动'
  fi
  start_background
fi
echo '[install] 到平台「Agent 节点」页确认节点已 ONLINE'
""";
    }
}
