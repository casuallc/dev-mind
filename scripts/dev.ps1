# Dev-Mind 本地一键启动：后端 :8080 + 前端 Vite :5173（代理 /api、/ws）+ agent runner
# CAP-34 起服务端零执行——会话/问答一律由 runner 节点执行，本地开发靠本机 runner 进程兜底。
# 用法：
#   scripts\dev.ps1              # 构建（跳测试）后起三进程
#   scripts\dev.ps1 -SkipBuild   # 跳过 mvn install 直接起（只改了前端时快）
# runner 配置在 tmp/runner/agent.properties：首次运行自动生成模板，填 token（后台 → Agent 节点
# 新建节点，建议设平台默认）后重跑才会启动 runner；未配置/未填 token 则跳过（此时创建会话会 409）。
# Ctrl+C 一起停掉三进程（含 spring-boot:run fork 出的 JVM）。
param(
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

if (-not $SkipBuild) {
    Write-Host "[dev] 构建后端与 runner（跳过测试）..."
    mvn -q install -DskipTests
}

if (-not (Test-Path "frontend/node_modules")) {
    Write-Host "[dev] 首次运行，安装前端依赖..."
    Push-Location frontend; npm install; Pop-Location
}

# ---------- runner（CAP-34：无 runner 则会话/问答创建 409） ----------
$runnerDir = "tmp/runner"
$runnerConf = "$runnerDir/agent.properties"
$runnerJar = "devmind-agent-runner/target/devmind-agent-runner.jar"
$runnerProc = $null
if (-not (Test-Path $runnerConf)) {
    New-Item -ItemType Directory -Force $runnerDir | Out-Null
    # 模板必须 UTF-8 无 BOM（runner 按 UTF-8 读 properties，BOM 会污染首行 key）
    $tpl = @"
# agent runner 本地开发配置（CAP-34：服务端零执行，会话/问答必须由 runner 执行）
serverUrl=ws://localhost:8080/ws/agent
# 节点 token：先起后端，到 后台 -> Agent 节点 新建节点（如 local-dev，建议设平台默认）复制 token 填入
token=dmag_待填
executor=claude          # claude=真实 CLI / fake=内置假进程（自测/E2E）
claudePath=              # 空 = where claude 自动探测
workspaceRoot=./workspaces
maxConcurrent=4
"@
    [System.IO.File]::WriteAllText("$PWD/$runnerConf", $tpl, (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "[dev] 已生成 runner 配置模板 $runnerConf ——填入 token 后重跑本脚本才会启动 runner"
} elseif ((Get-Content $runnerConf -Raw) -match "(?m)^token=dmag_待填") {
    Write-Host "[dev] runner 配置 token 未填（$runnerConf），跳过 runner（创建会话将 409）"
} elseif (-not (Test-Path $runnerJar)) {
    Write-Host "[dev] runner jar 不存在（$runnerJar），跳过 runner（去掉 -SkipBuild 先构建）"
} else {
    Write-Host "[dev] 启动 agent runner（$runnerConf）..."
    $runnerProc = Start-Process -PassThru -NoNewWindow -WorkingDirectory $runnerDir `
        -FilePath "java" -ArgumentList "-jar `"$PWD/$runnerJar`" agent.properties"
}

Write-Host "[dev] 启动后端 :8080 ..."
$backend = Start-Process -PassThru -NoNewWindow -FilePath "cmd.exe" `
    -ArgumentList "/c mvn -pl devmind-app spring-boot:run"

Write-Host "[dev] 启动前端 :5173 ..."
$frontend = Start-Process -PassThru -NoNewWindow -WorkingDirectory "frontend" -FilePath "cmd.exe" `
    -ArgumentList "/c npm run dev"

try {
    Write-Host -NoNewline "[dev] 等待后端就绪"
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/api/health" -TimeoutSec 2
            if ($r.StatusCode -eq 200) { break }
        } catch { }
        Write-Host -NoNewline "."
        Start-Sleep -Seconds 2
    }
    Write-Host " OK"
    Write-Host "[dev] 前端  → http://localhost:5173 （开发入口，热更新）"
    Write-Host "[dev] 后端  → http://localhost:8080 （API/健康检查 /api/health）"
    if ($runnerProc) { Write-Host "[dev] runner → 已启动（PID $($runnerProc.Id)，日志见上方输出）" }
    Write-Host "[dev] 首次登录：admin / admin123（devmind.auth.admin-password 可改）"
    Write-Host "[dev] 按 Ctrl+C 停止全部进程"
    if ($runnerProc) { Wait-Process -Id $backend.Id, $frontend.Id, $runnerProc.Id }
    else { Wait-Process -Id $backend.Id, $frontend.Id }
} finally {
    # Stop-Process 只到直接子进程；npm/mvn 会再 fork（node、spring-boot JVM），按进程树+端口双保险
    taskkill /PID $backend.Id /T /F 2>$null | Out-Null
    taskkill /PID $frontend.Id /T /F 2>$null | Out-Null
    if ($runnerProc) { taskkill /PID $runnerProc.Id /T /F 2>$null | Out-Null }
    Start-Sleep -Seconds 1
    foreach ($port in 8080, 5173) {
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
    }
    # runner 残留兜底：前次异常退出留下的 java 进程按命令行特征杀
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -match 'devmind-agent-runner' } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
