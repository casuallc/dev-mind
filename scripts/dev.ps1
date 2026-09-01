# Dev-Mind 本地一键启动：后端 :8080 + 前端 Vite :5173（代理 /api、/ws）
# 用法：
#   scripts\dev.ps1                  # 构建（跳测试）后起前后端，真实 claude 执行器
#   scripts\dev.ps1 -Executor fake   # 用内置假进程执行器（自测/E2E）
#   scripts\dev.ps1 -SkipBuild       # 跳过 mvn install 直接起（只改了前端时快）
# Ctrl+C 一起停掉前后端（含 spring-boot:run fork 出的 JVM）。
param(
    [ValidateSet("claude", "fake")][string]$Executor = "claude",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)

if (-not $SkipBuild) {
    Write-Host "[dev] 构建后端（跳过测试）..."
    mvn -q install -DskipTests
}

if (-not (Test-Path "frontend/node_modules")) {
    Write-Host "[dev] 首次运行，安装前端依赖..."
    Push-Location frontend; npm install; Pop-Location
}

Write-Host "[dev] 启动后端 :8080（executor=$Executor）..."
$backend = Start-Process -PassThru -NoNewWindow -FilePath "cmd.exe" `
    -ArgumentList "/c mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments=--devmind.session.executor=$Executor"

Write-Host "[dev] 启动前端 :5173 ..."
$frontend = Start-Process -PassThru -NoNewWindow -WorkingDirectory "frontend" -FilePath "cmd.exe" `
    -ArgumentList "/c npm run dev"

try {
    Write-Host -NoNewline "[dev] 等待后端就绪"
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri "http://localhost:8080/health" -TimeoutSec 2
            if ($r.StatusCode -eq 200) { break }
        } catch { }
        Write-Host -NoNewline "."
        Start-Sleep -Seconds 2
    }
    Write-Host " OK"
    Write-Host "[dev] 前端  → http://localhost:5173 （开发入口，热更新）"
    Write-Host "[dev] 后端  → http://localhost:8080 （API/健康检查 /health）"
    Write-Host "[dev] 首次登录：admin / admin123（devmind.auth.admin-password 可改）"
    Write-Host "[dev] 按 Ctrl+C 停止前后端"
    Wait-Process -Id $backend.Id, $frontend.Id
} finally {
    # Stop-Process 只到直接子进程；npm/mvn 会再 fork（node、spring-boot JVM），按进程树+端口双保险
    taskkill /PID $backend.Id /T /F 2>$null | Out-Null
    taskkill /PID $frontend.Id /T /F 2>$null | Out-Null
    Start-Sleep -Seconds 1
    foreach ($port in 8080, 5173) {
        $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($conn) { Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue }
    }
}
