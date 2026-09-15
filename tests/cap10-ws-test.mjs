// CAP-10 WS 实时流验证：连接 /ws/test-runs/{id}/stream，须收到 result 帧与 done 帧。
// 用法: node cap10-ws-test.mjs <runId> [expectedStatus]
const runId = process.argv[2]
const expectStatus = process.argv[3] || 'SUCCESS'
let gotResult = false
let gotDone = false
let status = null

const ws = new WebSocket(`ws://localhost:8080/ws/test-runs/${runId}/stream`)
const timer = setTimeout(() => {
  console.error('TIMEOUT: 未在 25s 内收到 done')
  process.exit(2)
}, 25000)

ws.onopen = () => console.log('ws open')
ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  if (f.type === 'snapshot') {
    console.log(`snapshot: results=${(f.results || []).length} status=${f.status}`)
  } else if (f.type === 'result') {
    gotResult = true
    console.log(`result: ${f.result && f.result.name} -> ${f.result && f.result.status}`)
  } else if (f.type === 'done') {
    gotDone = true
    status = f.status
    console.log(`done: ${f.status}`)
    clearTimeout(timer)
    const ok = gotResult && gotDone && status === expectStatus
    console.log(`received gotResult=${gotResult} gotDone=${gotDone} status=${status}`)
    console.log(ok ? 'WS_OK' : 'WS_FAIL')
    ws.close()
    setTimeout(() => process.exit(ok ? 0 : 1), 200)
  }
}
ws.onerror = (e) => {
  console.error('ws error', e.message || e)
  process.exit(3)
}
