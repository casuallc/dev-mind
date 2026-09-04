// Dev-Mind 假 Agent：模拟 claude stream-json 输出，供全链路自测（无 claude 环境时）。
// 用法: node fake-agent.js <sessionId> hold [steps]
// 行为：发 init → N 轮 assistant/tool_use/tool_result → permission_request → 等待 stdin：
//   {"type":"input","text":"..."}             → 收到并回复；text 为 "__exit__" 时发 result 正常退出
//   {"type":"permission_result","permission_request_id":"...","permission":"allow"} → 继续
'use strict';

const sessionId = process.argv[2] || 'fake';
const mode = process.argv[3] || 'hold';
const steps = parseInt(process.argv[4] || '3', 10);

const sleep = ms => new Promise(r => setTimeout(r, ms));
const emit = obj => process.stdout.write(JSON.stringify(obj) + '\n');
const MODEL = 'fake-opus';
const assistantMsg = blocks => emit({ type: 'assistant', message: { role: 'assistant', model: MODEL, content: blocks } });
const readline = require('readline');
const rl = readline.createInterface({ input: process.stdin });

rl.on('line', line => {
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
    assistantMsg([{ type: 'text', text: '收到：' + text + '（fake 回复）\n\n- 要点一\n- 要点二\n\n```js\nconsole.log("markdown ok")\n```' }]);
  } else if (msg.type === 'permission_result') {
    emit({ type: 'permission_result', permission_request_id: msg.permission_request_id, permission: msg.permission });
    assistantMsg([{ type: 'text', text: '权限已' + msg.permission + '，继续执行（fake）' }]);
  }
});

async function main() {
  emit({ type: 'system', subtype: 'init', session_id: sessionId, cwd: process.cwd(), model: MODEL });
  for (let i = 1; i <= steps; i++) {
    await sleep(300);
    if (i === 1) {
      // 真实 CLI 形态：tool_use 块内嵌在 assistant content 里，tool_result 以 user 消息回传
      assistantMsg([
        { type: 'text', text: `第 ${i}/${steps} 步：正在执行任务（fake，块内嵌工具调用）` },
        { type: 'tool_use', id: 'tool-1', name: 'Bash', input: { command: 'echo "step 1"' } },
      ]);
      await sleep(300);
      emit({ type: 'user', message: { role: 'user', content: [{ type: 'tool_result', tool_use_id: 'tool-1', content: [{ type: 'text', text: 'step 1 output' }], is_error: false }] } });
      continue;
    }
    // 顶层 tool_use/tool_result 事件形态（兼容路径）
    assistantMsg([{ type: 'text', text: `第 ${i}/${steps} 步：正在执行任务（fake）` }]);
    await sleep(300);
    emit({ type: 'tool_use', id: `tool-${i}`, name: 'Bash', tool_input: { command: `echo "step ${i}"` } });
    await sleep(300);
    emit({ type: 'tool_result', tool_use_id: `tool-${i}`, content: [{ type: 'text', text: `step ${i} output` }], is_error: false });
  }
  emit({ type: 'permission_request', request_id: 'perm-fake', action: 'ask', tool_name: 'Bash', input: 'npm install', options: [{ type: 'allowOnce', label: '允许一次' }] });
  assistantMsg([{ type: 'text', text: '请问是否允许我执行 npm install？' }]);
  if (mode === 'run') {
    await sleep(1000);
    emit({ type: 'result', subtype: 'success', is_error: false, result: '任务完成（fake）。', duration_ms: 2000 });
    process.exit(0);
  }
}

main().catch(e => { console.error(e); process.exit(1); });
