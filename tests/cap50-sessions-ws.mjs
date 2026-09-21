// CAP-50 CLI 会话流式输出 WS 验证（/ws/sessions/{id}）。
// 用法: node cap50-sessions-ws.mjs <sessionId> <问题文本> [wsBase]
// 握手收 snapshot → 发问 → 断言多条 text_delta 先到、全量 assistant 后到且拼接等于正文；
// 全量 assistant 到达后再开一条探测连接，断言其 snapshot 里没有 text_delta——
// 会话进行中刷新页面时 snapshot（环形缓冲）是唯一历史来源，增量挤进去会把历史整片冲掉。
// 收敛判据是「全量 assistant 到达」而非 result：CLI 会话的假执行体（hold 模式）只在
// __exit__ 时才发 result，一回合结束没有 result 帧（真实 claude 有，但这里不该依赖它）。
//
// 退出码 0 = 全部断言通过；失败打印 WS_FAIL 原因。WS_JSON 一行交给 Python 侧做补充断言。
const [, , sessionId, question, wsBaseArg] = process.argv
if (!sessionId || !question) {
  console.error('用法: node cap50-sessions-ws.mjs <sessionId> <问题文本> [wsBase]')
  process.exit(2)
}
const wsBase = wsBaseArg || process.env.DEVMIND_WS || 'ws://localhost:8080'
const url = `${wsBase}/ws/sessions/${sessionId}`

const out = {
  sessionId, snapshotTypes: null, probeSnapshotTypes: null, asked: false,
  deltas: [], deltaSeqs: [], emptyDeltas: 0, assistant: null, assistantSeq: null,
  result: null, resultSeq: null, states: [], stateSeqs: [], errors: [], frames: 0,
}

let done = false
function finish(ok, why) {
  if (done) return
  done = true
  const fails = [...check(ok, why)]
  console.log(fails.length ? `WS_FAIL ${fails.join(' | ')}` : 'WS_OK')
  console.log('WS_JSON ' + JSON.stringify(out))
  try { ws.close() } catch { /* 已关 */ }
  try { probe && probe.close() } catch { /* 已关 */ }
  setTimeout(() => process.exit(fails.length ? 1 : 0), 200)
}

/** 断言集中在此，失败原因随 WS_FAIL 一起打出。 */
function* check(ok, why) {
  if (!ok) yield why || '未收敛'
  if (!out.asked) yield '没发出问题'
  if (out.deltas.length < 2) yield `增量条数 ${out.deltas.length} < 2（没流式）`
  if (out.emptyDeltas) yield `有 ${out.emptyDeltas} 条空增量`
  if (out.assistant === null) yield '没收到全量 assistant'
  else if (out.deltas.join('') !== out.assistant) {
    yield `增量拼接与全量正文不一致: 增量 ${out.deltas.join('').length} 字 / 全量 ${out.assistant.length} 字`
  }
  if (out.assistantSeq !== null && out.deltaSeqs.length && out.assistantSeq < Math.max(...out.deltaSeqs)) {
    yield '全量 assistant 的 seq 早于增量（顺序反了）'
  }
  if (!out.probeSnapshotTypes) yield '没探到 snapshot'
  else if (out.probeSnapshotTypes.includes('text_delta')) yield 'snapshot 里混进了 text_delta（环形缓冲没排除增量）'
  else if (!out.probeSnapshotTypes.length) yield '探测连接的 snapshot 为空，未覆盖到排除逻辑'
  if (out.result && out.result.isError) yield `result 异常: ${JSON.stringify(out.result)}`
}

const timer = setTimeout(() => finish(false, '超时未收敛'), 70000)
const ws = new WebSocket(url)
let probe = null

/** 本轮增量都已发布后再探——「环形缓冲排除增量」若被改坏，此时 snapshot 里必然能看见它们。 */
function openProbe() {
  probe = new WebSocket(url)
  const dead = setTimeout(() => finish(false, '探测连接的 snapshot 未回到'), 10000)
  probe.onmessage = (pe) => {
    let pf
    try { pf = JSON.parse(pe.data) } catch { return }
    if (pf.type !== 'snapshot') return
    clearTimeout(dead)
    out.probeSnapshotTypes = (pf.events || []).map((x) => x.type)
    probe.close()
    if (out.assistant !== null) finish(true)
  }
  probe.onerror = () => finish(false, '探测连接失败')
}

ws.onerror = (e) => finish(false, 'ws error ' + (e.message || e))

ws.onmessage = (e) => {
  let f
  try { f = JSON.parse(e.data) } catch { return }
  out.frames += 1

  if (f.type === 'error') {
    out.errors.push(f.message)
    return
  }
  if (f.type === 'snapshot') {
    out.snapshotTypes = (f.events || []).map((x) => x.type)
    out.asked = true
    ws.send(JSON.stringify({ type: 'input', text: question }))
    console.log(`← snapshot ${out.snapshotTypes.length} 条 → input`)
    return
  }
  if (f.type !== 'event') return
  const ev = f.event || {}
  const p = ev.payload || {}
  switch (ev.type) {
    case 'text_delta':
      out.deltas.push(ev.content)
      out.deltaSeqs.push(ev.seq)
      if (!ev.content) out.emptyDeltas += 1
      console.log(`← text_delta #${out.deltas.length} +${(ev.content || '').length}字`)
      break
    case 'assistant':
      out.assistant = ev.content
      out.assistantSeq = ev.seq
      console.log(`← assistant ${(ev.content || '').length}字`)
      if (!probe) openProbe()
      break
    case 'result':
      out.result = p
      out.resultSeq = ev.seq
      console.log(`← result ${JSON.stringify(p)}`)
      break
    case 'state':
      out.states.push(p.state)
      out.stateSeqs.push(ev.seq)
      break
    default:
      break
  }
}
