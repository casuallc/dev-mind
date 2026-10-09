// CAP-34 会话 WS snapshot 探针（/ws/sessions/{id}）：握手收 snapshot 帧，
// 打印一行 SNAP_JSON（事件数/最大 seq/各事件 seq+type+内容摘要）后退出。
// 供 cap34_reattach_verify.py 断言「服务端重启 reattach 后 snapshot 仍含重启前历史」。
//
// 用法: node cap34-snapshot-probe.mjs <sessionId> [wsBase]
// 退出码 0 = 收到 snapshot；2 = 参数错误；1 = 超时/连接失败（打印 SNAP_FAIL）。
const [, , sessionId, wsBaseArg] = process.argv
if (!sessionId) {
  console.error('用法: node cap34-snapshot-probe.mjs <sessionId> [wsBase]')
  process.exit(2)
}
const wsBase = wsBaseArg || process.env.DEVMIND_WS || 'ws://localhost:8080'
const url = `${wsBase}/ws/sessions/${sessionId}`

let done = false
function finish(code, line) {
  if (done) return
  done = true
  console.log(line)
  try { ws.close() } catch { /* 已关 */ }
  setTimeout(() => process.exit(code), 200)
}

const timer = setTimeout(() => finish(1, 'SNAP_FAIL 超时未收到 snapshot'), 10000)
const ws = new WebSocket(url)
ws.onerror = (e) => finish(1, 'SNAP_FAIL ws error ' + (e.message || e))
ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  if (f.type === 'error') return finish(1, 'SNAP_FAIL ' + f.message)
  if (f.type !== 'snapshot') return
  clearTimeout(timer)
  const events = (f.events || []).map((x) => ({
    seq: x.seq, type: x.type, content: (x.content || '').slice(0, 120),
  }))
  finish(0, 'SNAP_JSON ' + JSON.stringify({
    sessionId,
    count: events.length,
    maxSeq: events.reduce((m, x) => Math.max(m, x.seq || 0), 0),
    events,
  }))
}
