// CAP-80 WS 日志流验证：连接 /ws/async-tasks/{runId}。
// 用法: node cap80-ws-test.mjs <port> <runId> <live|replay>
//   live   —— 运行中的 run：须收到 snapshot/log 实时帧与 done 帧（45s 窗口）
//   replay —— 已终态的 run：服务端先推 snapshot 再立即 done（10s 窗口），有日志则快照须含 echo 行
const port = process.argv[2]
const runId = process.argv[3]
const mode = process.argv[4] || 'replay'
let gotSnapshot = false
let gotLog = false
let snapshotHasEcho = false
let status = null

const ws = new WebSocket(`ws://localhost:${port}/ws/async-tasks/${runId}`)
const timer = setTimeout(() => {
  console.error(`TIMEOUT: ${mode} 模式未在窗口内收齐帧 gotSnapshot=${gotSnapshot} gotLog=${gotLog}`)
  process.exit(2)
}, mode === 'live' ? 45000 : 10000)

ws.onopen = () => console.log('ws open')
ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  if (f.type === 'snapshot') {
    gotSnapshot = true
    snapshotHasEcho = (f.logs || '').includes('echo')
    console.log(`snapshot: ${(f.logs || '').length} chars`)
  } else if (f.type === 'log') {
    gotLog = true
    console.log(`log: ${String(f.line).slice(0, 90)}`)
  } else if (f.type === 'done') {
    status = f.status
    console.log(`done: ${f.status}`)
    clearTimeout(timer)
    // live：须见实时 log + done；replay：须见 snapshot + done（终态日志在快照里）
    const ok = mode === 'live'
      ? gotLog && !!status
      : gotSnapshot && snapshotHasEcho && !!status
    console.log(`received mode=${mode} gotSnapshot=${gotSnapshot} gotLog=${gotLog} status=${status}`)
    console.log(ok ? 'WS_OK' : 'WS_FAIL')
    ws.close()
    setTimeout(() => process.exit(ok ? 0 : 1), 200)
  }
}
ws.onerror = (e) => {
  console.error('ws error', e.message || e)
  process.exit(3)
}
