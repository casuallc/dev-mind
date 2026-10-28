// CAP-08 WS 实时流验证：连接 /ws/builds/{id}/logs，须收到实时 log 帧与 done 帧。
// 用法: node cap08-ws-test.mjs <buildId> [expectedStatus]
const buildId = process.argv[2]
const expectStatus = process.argv[3] || 'SUCCESS'
let gotLog = false
let gotDone = false
let status = null

const ws = new WebSocket(`ws://localhost:8080/ws/builds/${buildId}/logs`)
const timer = setTimeout(() => {
  console.error('TIMEOUT: 未在 20s 内收到 done')
  process.exit(2)
}, 20000)

ws.onopen = () => console.log('ws open')
ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  if (f.type === 'snapshot') {
    console.log(`snapshot: ${(f.logs || '').length} chars`)
  } else if (f.type === 'log') {
    gotLog = true
    console.log(`log: ${String(f.line).slice(0, 90)}`)
  } else if (f.type === 'done') {
    gotDone = true
    status = f.status
    console.log(`done: ${f.status}`)
    clearTimeout(timer)
    const ok = gotLog && gotDone && status === expectStatus
    console.log(`received gotLog=${gotLog} gotDone=${gotDone} status=${status}`)
    console.log(ok ? 'WS_OK' : 'WS_FAIL')
    ws.close()
    setTimeout(() => process.exit(ok ? 0 : 1), 200)
  }
}
ws.onerror = (e) => {
  console.error('ws error', e.message || e)
  process.exit(3)
}
