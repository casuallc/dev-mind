# runner Windows 服务 E2E：install → start → status → restart → stop → uninstall
# 需要管理员运行；全程日志写同目录 service-e2e.log
$ErrorActionPreference = 'Continue'
$home_ = $args[0]
$log = Join-Path $home_ 'service-e2e.log'
"[e2e] start $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') home=$home_" | Out-File $log -Encoding utf8

function Step($name, $script) {
    "[e2e] --- $name" | Out-File $log -Append -Encoding utf8
    try {
        & $script 2>&1 | Out-File $log -Append -Encoding utf8
        "[e2e] $name OK" | Out-File $log -Append -Encoding utf8
    } catch {
        "[e2e] $name FAIL: $_" | Out-File $log -Append -Encoding utf8
    }
}

Push-Location $home_
Step 'install'   { & .\bin\dev-mind-agent.ps1 install }
Step 'get-svc'   { Get-Service devmind-agent | Format-List Name,Status,StartType }
Step 'start'     { & .\bin\dev-mind-agent.ps1 start; Start-Sleep 3; Get-Service devmind-agent }
Step 'restart'   { & .\bin\dev-mind-agent.ps1 restart; Start-Sleep 3; Get-Service devmind-agent }
Step 'status'    { & .\bin\dev-mind-agent.ps1 status }
Step 'java-proc' { Get-CimInstance Win32_Process -Filter "Name='java.exe'" | ? { $_.CommandLine -match 'devmind-agent-runner' } | Select-Object ProcessId, @{n='Cmd';e={$_.CommandLine.Substring(0,[Math]::Min(120,$_.CommandLine.Length))}} }
Step 'stop'      { & .\bin\dev-mind-agent.ps1 stop; Get-Service devmind-agent }
Step 'uninstall' { & .\bin\dev-mind-agent.ps1 uninstall }
Step 'svc-gone'  { if (Get-Service devmind-agent -ErrorAction SilentlyContinue) { 'STILL EXISTS' } else { 'service removed' } }
Pop-Location
"[e2e] done $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" | Out-File $log -Append -Encoding utf8
