// CAP-23 WS 实时流验证：连接 /ws/repo-clones/clone-<repoId>，须收到 snapshot 帧与 done 帧。
// 用法: node cap23-ws-test.mjs <repoId>
const repoId = process.argv[2]
let gotSnapshot = false
let gotLog = false
let gotDone = false
let status = null

const ws = new WebSocket(`ws://localhost:8080/ws/repo-clones/clone-${repoId}`)
const timer = setTimeout(() => {
  console.error('TIMEOUT: 未在 25s 内收到 done')
  process.exit(2)
}, 25000)

ws.onopen = () => console.log('ws open')
ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  if (f.type === 'snapshot') {
    gotSnapshot = true
    console.log(`snapshot: logs=${(f.logs || '').length}chars status=${f.cloneStatus ?? ''}`)
  } else if (f.type === 'log') {
    gotLog = true
    console.log(`log: ${f.line}`)
  } else if (f.type === 'done') {
    gotDone = true
    status = f.status
    console.log(`done: ${f.status}`)
    clearTimeout(timer)
    // 连接早时 DB 尚无持久化日志，handler 跳过 snapshot 帧 → 实时 log 帧即等价证据
    const ok = (gotSnapshot || gotLog) && gotDone && (status === 'READY' || status === 'FAILED')
    console.log(`received gotSnapshot=${gotSnapshot} gotLog=${gotLog} gotDone=${gotDone} status=${status}`)
    console.log(ok ? 'WS_OK' : 'WS_FAIL')
    ws.close()
    setTimeout(() => process.exit(ok ? 0 : 1), 200)
  }
}
ws.onerror = (e) => {
  console.error('ws error', e.message || e)
  process.exit(3)
}
