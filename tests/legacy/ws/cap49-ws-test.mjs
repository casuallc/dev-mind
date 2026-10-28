// CAP-49 模型执行体 WS 实时流验证（/ws/chats/{id}）。
// 用法: node cap49-ws-test.mjs <chatId> <stream|interrupt|notice> <问题文本> [wsBase]
//   stream    —— 发问 ⇒ 断言多条 text_delta 先到、全量 assistant 后到、result{isError:false}、状态回 WAITING_INPUT
//   interrupt —— 收到首个 text_delta 立刻停止生成 ⇒ 断言 result.subtype=interrupted（部分正文由 Python 侧比对）
//   notice    —— 空闲时先发一次必然被拒的 interrupt ⇒ 断言只回 notice（连接不死），随后照常提问并能拿到回答
// 结果以最后一行 `WS_JSON {…}` 交给 Python 断言（断言只写一处）。
//
// 只统计"本轮"（发问之后）到达的事件：snapshot 回放里带着上一轮的 assistant/result/state，
// 混进来会让"已收敛"在提问之前就成立。
const [, , chatId, mode, question, wsBaseArg] = process.argv
const wsBase = wsBaseArg || process.env.DEVMIND_WS || 'ws://localhost:18090'
const url = `${wsBase}/ws/chats/${chatId}`

const out = {
  mode, chatId, snapshotEvents: null, asked: false, frames: 0,
  users: [], deltas: [], deltaSeqs: [], assistant: null, assistantSeq: null,
  result: null, resultSeq: null, states: [], stateSeqs: [], errors: [], notices: [],
  interruptsSent: 0, deltasAtInterrupt: null,
}

let done = false
function finish(ok, why) {
  if (done) return
  done = true
  out.ok = ok
  out.why = why || ''
  clearTimeout(timer)
  console.log(ok ? 'WS_OK' : `WS_FAIL ${why || ''}`)
  console.log('WS_JSON ' + JSON.stringify(out))
  try { ws.close() } catch { /* 已关 */ }
  setTimeout(() => process.exit(ok ? 0 : 1), 200)
}

const timer = setTimeout(() => finish(false, '超时未收敛'), 70000)
const ws = new WebSocket(url)

ws.onopen = () => console.log(`ws open ${url} mode=${mode}`)
ws.onerror = (e) => finish(false, 'ws error ' + (e.message || e))

function ask() {
  out.asked = true
  ws.send(JSON.stringify({ type: 'input', text: question }))
  console.log('→ input')
}

ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  out.frames += 1

  if (f.type === 'snapshot') {
    out.snapshotEvents = (f.events || []).length
    console.log(`← snapshot ${out.snapshotEvents} 条历史`)
    if (mode === 'notice') {
      // 空闲问答上停止生成：后端 409 → 必须回 notice 而非 error（error 会掀掉整条实时流）
      ws.send(JSON.stringify({ type: 'interrupt' }))
      console.log('→ interrupt（空闲，预期被拒）')
    } else {
      ask()
    }
    return
  }
  if (f.type === 'notice') {
    out.notices.push(f.message)
    console.log('← notice ' + f.message)
    // notice 后连接仍应在：接着把真正的问题问出去
    if (mode === 'notice' && !out.asked) ask()
    return
  }
  if (f.type === 'error') {
    // 注意：error 帧在真实前端里是致命的（断流且不重连）——notice 用例就是要钉住"动作被拒别用 error"
    out.errors.push(f.message)
    console.log('← error ' + f.message)
    return
  }
  if (f.type !== 'event') return
  const ev = f.event || {}
  const p = ev.payload || {}

  // 提问之前到达的事件（snapshot 回放之后仍可能补发）不算本轮
  const live = out.asked
  switch (ev.type) {
    case 'user':
      if (live) out.users.push(ev.content)
      break
    case 'text_delta':
      if (!live) break
      out.deltas.push(ev.content)
      out.deltaSeqs.push(ev.seq)
      console.log(`← text_delta #${out.deltas.length} +${(ev.content || '').length}字`)
      if (mode === 'interrupt' && out.interruptsSent === 0) {
        out.interruptsSent = 1
        out.deltasAtInterrupt = out.deltas.slice()
        ws.send(JSON.stringify({ type: 'interrupt' }))
        console.log('→ interrupt（首个增量到达后立刻停止生成）')
      }
      break
    case 'assistant':
      if (!live) break
      out.assistant = ev.content
      out.assistantSeq = ev.seq
      console.log(`← assistant ${(ev.content || '').length}字`)
      break
    case 'result':
      if (!live) break
      out.result = p
      out.resultSeq = ev.seq
      console.log(`← result ${JSON.stringify(p)}`)
      break
    case 'state':
      if (!live) break
      out.states.push(p.state)
      out.stateSeqs.push(ev.seq)
      console.log(`← state ${p.state}`)
      break
    default:
      break
  }

  // 收敛判据：本轮拿到 result 且状态已离开 RUNNING（内核在 result 之后转 WAITING_INPUT）
  if (live && out.resultSeq !== null && out.states.some((s) => s !== 'RUNNING')) {
    finish(true)
  }
}
