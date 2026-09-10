# dev-mind agent runner Windows 服务控制脚本（CAP-34：会话/问答一律由 runner 执行）。
# 本文件必须保存为 UTF-8 with BOM（PowerShell 5.1 无 BOM 按 GBK 解析 → 中文乱码 + 语法错误）。
#
# 用法：
#   dev-mind-agent.ps1 install     注册 Windows 服务（需管理员；自动下载 WinSW，可用 -WinSwUrl 覆盖下载地址实现离线自带）
#   dev-mind-agent.ps1 uninstall   停止并移除服务（需管理员）
#   dev-mind-agent.ps1 start       启动服务
#   dev-mind-agent.ps1 stop        停止服务（直接终止 java；会话现场由 CAP-34 重启对账兜底）
#   dev-mind-agent.ps1 restart     stop + start
#   dev-mind-agent.ps1 status      查看服务状态
#   dev-mind-agent.ps1 run         前台运行（调试用，等价 Linux 版 run）
#
# 环境变量：
#   APUSIC_JAVA_HOME     首选 JDK 目录（必须 21+）
#   JAVA_HOME            备选 JDK 目录
#   WINSW_URL            WinSW 下载地址覆盖（同 -WinSwUrl）
#   DEVMIND_SERVICE_NAME 服务名覆盖（默认 devmind-agent）
[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidateSet('install', 'uninstall', 'start', 'stop', 'restart', 'status', 'run')]
    [string]$Command,
    [string]$WinSwUrl
)

$ErrorActionPreference = 'Stop'

# WinSW v2 系：.NET Framework 4.6.1+ 即可运行（Win10/Server2016+ 自带），无需额外运行时。
# 配置约定：exe 与同目录同名 xml 配对（devmind-agent.exe + devmind-agent.xml）。
$DefaultWinSwUrl = 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW.NET461.exe'

$ServiceName = if ($env:DEVMIND_SERVICE_NAME) { $env:DEVMIND_SERVICE_NAME } else { 'devmind-agent' }
$BinDir      = $PSScriptRoot
$AppHome     = Split-Path $BinDir -Parent
$RunnerJar   = Join-Path $AppHome 'runner\devmind-agent-runner.jar'
$Conf        = Join-Path $AppHome 'config\agent.properties'
$LogDir      = Join-Path $AppHome 'logs'
$WinSwExe    = Join-Path $BinDir "$ServiceName.exe"
$WinSwXml    = Join-Path $BinDir "$ServiceName.xml"

function Assert-Admin {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "需要管理员权限：请用「以管理员身份运行」的 PowerShell 重试"
    }
}

function Resolve-Java {
    $candidates = @()
    if ($env:APUSIC_JAVA_HOME) { $candidates += (Join-Path $env:APUSIC_JAVA_HOME 'bin\java.exe') }
    if ($env:JAVA_HOME)        { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
    $onPath = Get-Command java.exe -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }
    foreach ($java in $candidates) {
        if (-not (Test-Path $java)) { continue }
        # java -version 输出走 stderr："openjdk version \"21.0.x\"" / "java version \"21\""
        $verLine = (& $java -version 2>&1 | Select-Object -First 1) -join ' '
        if ($verLine -match 'version "(\d+)') {
            $major = [int]$Matches[1]
            if ($major -ge 21) { return (Resolve-Path $java).Path }
        }
    }
    throw "未找到 JDK 21+：请设置 APUSIC_JAVA_HOME 或 JAVA_HOME 指向 JDK 21 目录"
}

function Assert-Config {
    if (-not (Test-Path $Conf)) {
        throw "缺少 $Conf —— 参考同目录 agent.properties.example 填入 token"
    }
    $content = Get-Content $Conf -Raw -Encoding UTF8
    if ($content -match '(?m)^token=\s*$' -or $content -match '(?m)^token=dmag_待填') {
        throw "$Conf 的 token 未填写（后台 → Agent 节点 新建节点复制 token）"
    }
    if (-not (Test-Path $RunnerJar)) {
        throw "缺少 $RunnerJar —— 分发包不完整"
    }
}

function Get-WinSw {
    if (Test-Path $WinSwExe) { return }
    # 离线场景：允许手工把 WinSW.NET461.exe 放到 bin/ 并改名（见 usage 说明）
    $url = if ($WinSwUrl) { $WinSwUrl } elseif ($env:WINSW_URL) { $env:WINSW_URL } else { $DefaultWinSwUrl }
    Write-Host "[agent] 下载 WinSW: $url"
    try {
        Invoke-WebRequest -Uri $url -OutFile $WinSwExe -UseBasicParsing
    } catch {
        Remove-Item $WinSwExe -Force -ErrorAction SilentlyContinue
        throw "WinSW 下载失败：$_  —— 可离线自带：下载 WinSW.NET461.exe 放到 $BinDir 并改名 $ServiceName.exe 后重试（或用 -WinSwUrl 指定内网地址）"
    }
}

function Write-WinSwXml([string]$JavaExe) {
    $esc = { param([string]$s) [Security.SecurityElement]::Escape($s) }
    $xml = @"
<service>
  <id>$(& $esc $ServiceName)</id>
  <name>Dev-Mind Agent Runner</name>
  <description>Dev-Mind agent runner (CAP-34 executor)</description>
  <executable>$(& $esc $JavaExe)</executable>
  <arguments>-jar runner\devmind-agent-runner.jar config\agent.properties</arguments>
  <workingdirectory>$(& $esc $AppHome)</workingdirectory>
  <!-- 服务管理模式标记：runner 升级换包后以退出码 42 退出，由本服务 onfailure 拉起新 jar，
       SelfUpdater 不自行 spawn（防与服务双份起进程） -->
  <env name="DEVMIND_RUNNER_SERVICE" value="1"/>
  <logpath>$(& $esc $LogDir)</logpath>
  <logmode>roll</logmode>
  <onfailure action="restart" delay="5 sec"/>
</service>
"@
    # WinSW 按 UTF-8 读 xml，写无 BOM 即可
    [System.IO.File]::WriteAllText($WinSwXml, $xml, (New-Object System.Text.UTF8Encoding($false)))
}

function Assert-ServiceExists {
    if (-not (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
        throw "服务 $ServiceName 不存在（先执行: dev-mind-agent.ps1 install）"
    }
}

function Invoke-Install {
    Assert-Admin
    Assert-Config
    if (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue) {
        Write-Host "[agent] 服务 $ServiceName 已存在（重装请先 uninstall）"
        return
    }
    $java = Resolve-Java
    Get-WinSw
    Write-WinSwXml $java
    New-Item -ItemType Directory -Force $LogDir | Out-Null
    Write-Host "[agent] 注册服务 $ServiceName (java=$java home=$AppHome)"
    & $WinSwExe install
    if ($LASTEXITCODE -ne 0) { throw "winsw install 失败（exit $LASTEXITCODE）" }
    Write-Host "[agent] 已注册。启动：Start-Service $ServiceName（或 dev-mind-agent.ps1 start）"
}

function Invoke-Uninstall {
    Assert-Admin
    Assert-ServiceExists
    $svc = Get-Service -Name $ServiceName
    if ($svc.Status -ne 'Stopped') {
        Write-Host "[agent] 停止服务 $ServiceName ..."
        Stop-Service -Name $ServiceName -Force
    }
    & $WinSwExe uninstall
    if ($LASTEXITCODE -ne 0) { throw "winsw uninstall 失败（exit $LASTEXITCODE）" }
    Write-Host "[agent] 已移除服务 $ServiceName"
}

function Invoke-Run {
    Assert-Config
    $java = Resolve-Java
    Set-Location $AppHome
    Write-Host "[agent] home=$AppHome java=$java conf=$Conf"
    & $java -jar $RunnerJar $Conf
}

switch ($Command) {
    'install'   { Invoke-Install }
    'uninstall' { Invoke-Uninstall }
    'start'     { Assert-ServiceExists; Start-Service $ServiceName; Write-Host "[agent] $ServiceName started" }
    'stop'      { Assert-ServiceExists; Stop-Service $ServiceName -Force; Write-Host "[agent] $ServiceName stopped" }
    'restart'   { Assert-ServiceExists; Restart-Service $ServiceName -Force; Write-Host "[agent] $ServiceName restarted" }
    'status'    {
        Assert-ServiceExists
        Get-Service $ServiceName | Format-List Name, Status, StartType
    }
    'run'       { Invoke-Run }
    default     { Get-Content $PSCommandPath -TotalCount 21 | Select-Object -Skip 1 }
}
