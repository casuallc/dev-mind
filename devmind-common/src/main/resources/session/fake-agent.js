// Dev-Mind 假 Agent：模拟 claude stream-json 输出，供全链路自测（无 claude 环境时）。
// 用法: node fake-agent.js <sessionId> hold [steps]
// 行为：发 init → N 轮 assistant/tool_use/tool_result → permission_request → 等待 stdin：
//   {"type":"input","text":"..."}             → 收到并回复；text 为 "__exit__" 时发 result 正常退出
//   {"type":"permission_result","permission_request_id":"...","permission":"allow"} → 继续
// CAP-50：每条 assistant 正文之前先按 --include-partial-messages 的形态吐一片 stream_event
// 增量（见 streamText），完整 assistant 照旧紧跟其后——真实 CLI 两者都发，前端增量打底、
// 全量覆盖收口。缺少这一片则该路径只有单测覆盖，链路（聚合 → 出口 → 落库）没有回归网。
'use strict';

const sessionId = process.argv[2] || 'fake';
const mode = process.argv[3] || 'hold';
const steps = parseInt(process.argv[4] || '3', 10);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const emit = obj => process.stdout.write(JSON.stringify(obj) + '\n');
const MODEL = 'fake-opus';
const readline = require('readline');
const rl = readline.createInterface({ input: process.stdin });

/** 相邻增量之间的间隔：> 服务端聚合器的 flushMs(120)，否则会被攒成一片、看不出逐字。 */
const PIECE_MS = 130;
/** 主正文切几片。片数由代码均分而非手写常量：断言「增量拼接 == 全量正文」才有意义。 */
const PIECES = 3;

/**
 * 正文流闸门：同一会话的增量必须依次吐。
 *
 * <p>真实 CLI 是单线程事件循环，一回合的增量不会与下一回合交错；而本脚本的 stdin 回调与
 * main() 是并发跑的——授权来得早时（E2E 一看到 permission_request 就授权），回复会与提问
 * 尾部的增量同时流。服务端聚合器只看相邻增量、分辨不出回合边界，交错就会把两段正文并进
 * 同一条 text_delta（E2E 的「增量拼接 == 全量正文」断言正是照此抓出来的）。</p>
 */
let streamGate = Promise.resolve();
function serial(fn) {
  const next = streamGate.then(fn, fn);
  streamGate = next.then(() => { }, () => { });
  return next;
}

/** 把一段正文均分成 n 片（空串得空数组，不产空增量）。 */
function splitPieces(text, n) {
  if (!text) return [];
  const size = Math.ceil(text.length / n);
  const out = [];
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size));
  return out;
}

/**
 * 按 claude --include-partial-messages 的形态吐一段正文的增量流：
 * message_start → content_block_start → text_delta... → content_block_stop → message_stop。
 * 中间混入真实 CLI 会发、但服务端解析层整片吞掉的帧（thinking_delta / input_json_delta /
 * ping，以及带 parent_tool_use_id 的子 agent 增量）——它们若被误降级成 log 或混进主气泡，
 * 端到端断言（相邻只有 text_delta、无空增量）就会失败。
 */
async function streamText(text) {
  const ev = e => emit({ type: 'stream_event', event: e });
  ev({ type: 'message_start', message: { role: 'assistant', model: MODEL } });
  ev({ type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } });
  ev({ type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: '（fake 思考，不应渲染）' } });
  for (const p of splitPieces(text, PIECES)) {
    await sleep(PIECE_MS);
    ev({ type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: p } });
  }
  // 子 agent（Task）的流：parent_tool_use_id 非空 → 解析层丢弃，不得混进主气泡
  emit({ type: 'stream_event', parent_tool_use_id: 'task-fake',
    event: { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: '（子 agent 正文，不应渲染）' } } });
  ev({ type: 'content_block_delta', index: 1, delta: { type: 'input_json_delta', partial_json: '{"command":"echo' } });
  ev({ type: 'content_block_stop', index: 0 });
  ev({ type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'fake-sig' } });
  ev({ type: 'message_delta', delta: { stop_reason: 'end_turn' } });
  ev({ type: 'message_stop' });
  ev({ type: 'ping' });
}

/** 正文流式吐完后再发完整 assistant（收口）；整段走闸门，不与其他回合的流交错。 */
function assistantMsg(blocks) {
  return serial(async () => {
    for (const b of blocks) {
      if (b && b.type === 'text') await streamText(b.text);
    }
    emit({ type: 'assistant', message: { role: 'assistant', model: MODEL, content: blocks } });
  });
}

// stdin EOF = 优雅结束（与真实 claude -p 一致）：发 result 后退出；
// 管道写是异步的，必须等 flush 回调再 exit，否则 Windows 下末行可能被截断
rl.on('close', () => {
  // 也走闸门：真实 CLI 是「把手头这一回合吐完再读 EOF 退出」，不会把 result 塞进正文中间
  serial(() => process.stdout.write(
    JSON.stringify({ type: 'result', subtype: 'success', is_error: false, result: '任务完成（fake，stdin EOF）。', duration_ms: 2000 }) + '\n',
    () => process.exit(0)));
});

rl.on('line', async line => {
  let msg;
  try { msg = JSON.parse(line); } catch (e) { return; }
  // 与 claude 一致的 stream-json 输入：{"type":"user","message":{...}}；兼容旧的 {"type":"input","text":...}
  let text = '';
  if (msg.type === 'user' && msg.message) {
    const c = msg.message.content;
    text = Array.isArray(c) ? c.map(b => (b && b.text) || '').join('') : (msg.message.content || '');
  } else if (msg.type === 'input') {
    text = msg.text || '';
  }
  if (msg.type === 'input' || msg.type === 'user') {
    if (text === '__exit__') {
      emit({ type: 'user', message: { role: 'user', content: '__exit__' } });
      emit({ type: 'result', subtype: 'success', is_error: false, result: '任务完成（fake）。', duration_ms: 2000 });
      process.exit(0);
    }
    emit({ type: 'user', message: { role: 'user', content: text } });
    await assistantMsg([{ type: 'text', text: '收到：' + text + '（fake 回复）\n\n- 要点一\n- 要点二\n\n```js\nconsole.log("markdown ok")\n```' }]);
  } else if (msg.type === 'permission_result') {
    emit({ type: 'permission_result', permission_request_id: msg.permission_request_id, permission: msg.permission });
    await assistantMsg([{ type: 'text', text: '权限已' + msg.permission + '，继续执行（fake）' }]);
  }
});

async function main() {
  emit({ type: 'system', subtype: 'init', session_id: sessionId, cwd: process.cwd(), model: MODEL });
  for (let i = 1; i <= steps; i++) {
    await sleep(300);
    if (i === 1) {
      // 真实 CLI 形态：tool_use 块内嵌在 assistant content 里，tool_result 以 user 消息回传
      await assistantMsg([
        { type: 'text', text: `第 ${i}/${steps} 步：正在执行任务（fake，块内嵌工具调用）` },
        { type: 'tool_use', id: 'tool-1', name: 'Bash', input: { command: 'echo "step 1"' } },
      ]);
      await sleep(300);
      emit({ type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tool-1', content: [{ type: 'text', text: 'step 1 output' }], is_error: false }] } });
      continue;
    }
    // 顶层 tool_use/tool_result 事件形态（兼容路径）
    await assistantMsg([{ type: 'text', text: `第 ${i}/${steps} 步：正在执行任务（fake）` }]);
    await sleep(300);
    emit({ type: 'tool_use', id: `tool-${i}`, name: 'Bash', tool_input: { command: `echo "step ${i}"` } });
    await sleep(300);
    emit({ type: 'tool_result', tool_use_id: `tool-${i}`, content: [{ type: 'text', text: `step ${i} output` }], is_error: false });
  }
  emit({ type: 'permission_request', request_id: 'perm-fake', action: 'ask', tool_name: 'Bash', input: 'npm install', options: [{ type: 'allowOnce', label: '允许一次' }] });
  await assistantMsg([{ type: 'text', text: '请问是否允许我执行 npm install？' }]);
  if (mode === 'run') {
    await sleep(1000);
    emit({ type: 'result', subtype: 'success', is_error: false, result: '任务完成（fake）。', duration_ms: 2000 });
    process.exit(0);
  }
}

main().catch(e => { console.error(e); process.exit(1); });
