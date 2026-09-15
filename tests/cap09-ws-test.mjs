// CAP-09 WS 实时流验证：连接 /ws/deployments/{id}/stream，须收到 step/log 帧与 done 帧。
// 用法: node cap09-ws-test.mjs <deploymentId> [expectedStatus]
const depId = process.argv[2]
const expectStatus = process.argv[3] || 'SUCCESS'
let gotLive = false
let gotDone = false
let status = null

const ws = new WebSocket(`ws://localhost:8080/ws/deployments/${depId}/stream`)
const timer = setTimeout(() => {
  console.error('TIMEOUT: 未在 25s 内收到 done')
  process.exit(2)
}, 25000)

ws.onopen = () => console.log('ws open')
ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  if (f.type === 'snapshot') {
    console.log(`snapshot: steps=${(f.steps || []).length} status=${f.status}`)
  } else if (f.type === 'step') {
    gotLive = true
    console.log(`step: ${f.step && f.step.seq} ${f.step && f.step.name} -> ${f.step && f.step.status}`)
  } else if (f.type === 'log') {
    gotLive = true
    console.log(`log: ${String(f.line).slice(0, 90)}`)
  } else if (f.type === 'done') {
    gotDone = true
    status = f.status
    console.log(`done: ${f.status}`)
    clearTimeout(timer)
    const ok = gotLive && gotDone && status === expectStatus
    console.log(`received gotLive=${gotLive} gotDone=${gotDone} status=${status}`)
    console.log(ok ? 'WS_OK' : 'WS_FAIL')
    ws.close()
    setTimeout(() => process.exit(ok ? 0 : 1), 200)
  }
}
ws.onerror = (e) => {
  console.error('ws error', e.message || e)
  process.exit(3)
}
