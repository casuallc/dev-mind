// CAP-67 用量页维度 tab 切换回归：未归属桶撞 rowKey 导致 React 重复 key 协调漏删行纤维，
// 每切一次 tab 表格多一行「未归属（会话）」残留（224 实测累积；本地需「全部」时段+页大小 100
// 让两个未归属桶同页渲染才可复现，分页会把两个同 key 桶拆到不同页从而屏蔽）。
// 断言：① 每个维度首访/回访行数一致；② 所有数据行单元格数 == 表头列数（残留行带旧列数）。
// 前置：后端 :8080 + 前端 :5173 已起（scripts/dev.sh）。
// 用法：node tests/cap67_usage_tab_switch_ui.mjs   （BASE/API/USER/PASS/CHROME_PATH 可覆盖）
import { spawn } from 'node:child_process'
import { rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:8080'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PROFILE = join(ROOT, 'tmp', 'chrome-cap67-tab-switch')
const PORT = 9339

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function api(method, path, body, token) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`)
  return text ? JSON.parse(text) : null
}

/** 最小 CDP 客户端（与 tests/e2e-layout-pages.mjs 同款） */
function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  const pending = new Map()
  let seq = 0
  const events = []
  const waiters = []
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
    } else if (msg.method) {
      events.push(msg)
      waiters.filter((w) => w.method === msg.method).forEach((w) => w.resolve(msg.params))
    }
  })
  return {
    ready: new Promise((resolve, reject) => {
      ws.addEventListener('open', resolve)
      ws.addEventListener('error', () => reject(new Error(`CDP 连接失败: ${wsUrl}`)))
    }),
    send(method, params = {}) {
      const id = ++seq
      return new Promise((resolve, reject) => {
        pending.set(id, { resolve, reject })
        ws.send(JSON.stringify({ id, method, params }))
      })
    },
    reset() { events.length = 0; waiters.length = 0 },
    waitEvent(method, timeout = 20000) {
      const hit = events.find((e) => e.method === method)
      if (hit) return Promise.resolve(hit.params)
      return new Promise((resolve, reject) => {
        waiters.push({ method, resolve })
        setTimeout(() => reject(new Error(`等 ${method} 超时`)), timeout)
      })
    },
    close: () => ws.close(),
  }
}

async function waitFor(pred, what, timeout = 20000, interval = 250) {
  const deadline = Date.now() + timeout
  for (;;) {
    const v = await pred().catch(() => null)
    if (v) return v
    if (Date.now() > deadline) throw new Error(`等待「${what}」超时`)
    await sleep(interval)
  }
}

async function evaluate(ws, expression) {
  const { result, exceptionDetails } = await ws.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (exceptionDetails) throw new Error(exceptionDetails.text ?? '页面求值异常')
  return result.value
}

/** 维度卡（内容区最后一个 Card）里的表格：列头 + 行数 + 单元格数异常行数 */
const DUMP = `(() => {
  const cards = [...document.querySelectorAll('.ant-layout-content .ant-card')];
  const card = cards[cards.length - 1];
  if (!card) return null;
  const t = card.querySelector('.ant-table');
  if (!t) return null;
  const cols = t.querySelectorAll('thead th').length;
  const rows = [...t.querySelectorAll('tbody tr.ant-table-row')];
  return {
    active: card.querySelector('.ant-segmented-item-selected .ant-segmented-item-label')?.textContent?.trim(),
    cols,
    rowCount: rows.length,
    badCells: rows.filter((tr) => tr.querySelectorAll('td').length !== cols).length,
  };
})()`

async function clickDim(ws, label) {
  return evaluate(ws, `(() => {
    const cards = [...document.querySelectorAll('.ant-layout-content .ant-card')];
    const card = cards[cards.length - 1];
    const it = [...card.querySelectorAll('.ant-segmented-item')]
      .find((e) => (e.querySelector('.ant-segmented-item-label')?.textContent ?? '').trim() === ${JSON.stringify(label)});
    if (!it) return null;
    it.querySelector('input')?.click();
    return true;
  })()`)
}

/** 维度表页大小调 100（撞键两桶须同页渲染才触发泄漏；key 修复后每次切维度表格重挂载，页大小需逐轮重设） */
async function pageSize100(ws) {
  await evaluate(ws, `(() => {
    const cards = [...document.querySelectorAll('.ant-layout-content .ant-card')];
    const card = cards[cards.length - 1];
    const sel = card.querySelector('.ant-pagination-options .ant-select-selector');
    if (!sel) return false;
    sel.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    return true;
  })()`)
  await sleep(400)
  await evaluate(ws, `(() => {
    const opt = [...document.querySelectorAll('.ant-select-dropdown .ant-select-item-option')]
      .find((e) => (e.textContent ?? '').startsWith('100'));
    opt?.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    opt?.click?.();
    return true;
  })()`)
  await sleep(500)
}

async function switchDim(ws, label) {
  const ok = await clickDim(ws, label)
  if (!ok) throw new Error(`找不到维度项「${label}」`)
  await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-spin-spinning').length === 0`), `「${label}」加载结束`, 8000).catch(() => {})
  await sleep(600)
  await pageSize100(ws)
  const d = await evaluate(ws, DUMP)
  if (!d) throw new Error(`「${label}」维度表未渲染`)
  return d
}

async function main() {
  const { accessToken, refreshToken, user } = await api('POST', '/auth/login', { username: USER, password: PASS })
  console.log(`[1] 登录 ${USER} ok`)

  rmSync(PROFILE, { recursive: true, force: true })
  const chrome = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1900,1000', 'about:blank',
  ], { stdio: 'ignore' })

  let ws
  const failures = []
  try {
    const target = await waitFor(async () => {
      const l = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
      return l.find((t) => t.type === 'page')?.webSocketDebuggerUrl
    }, 'Chrome 调试端口')
    ws = cdp(target)
    await ws.ready
    await ws.send('Page.enable')
    await ws.send('Runtime.enable')

    await ws.send('Page.navigate', { url: `${BASE}/` })
    await ws.waitEvent('Page.loadEventFired')
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      return 'ok';
    })()`)

    ws.reset()
    await ws.send('Page.navigate', { url: `${BASE}/home?view=usage` })
    await ws.waitEvent('Page.loadEventFired', 20000).catch(() => {})
    await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-table tbody tr.ant-table-row').length > 0`), '首屏表格数据', 15000)

    // 切「全部」时段（内容区第一组 Segmented）：确保时间覆盖到最老数据，未归属两桶都在
    await evaluate(ws, `(() => {
      const g = document.querySelector('.ant-layout-content .ant-segmented');
      const it = [...g.querySelectorAll('.ant-segmented-item')].find((e) => (e.textContent ?? '').trim() === '全部');
      it?.querySelector('input')?.click();
      return true;
    })()`)
    await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-spin-spinning').length === 0`), '全部时段加载', 8000).catch(() => {})
    await sleep(800)
    console.log('[2] 已切「全部」时段')

    // 两轮巡检：同一维度首访/回访行数必须一致，且任何行单元格数不得偏离列数
    const firstPass = {}
    for (const pass of [1, 2]) {
      for (const label of ['按需求', '按项目', '按模型', 'Top']) {
        const d = await switchDim(ws, label)
        console.log(`    [第${pass}轮] ${label}: 行数=${d.rowCount} 列数=${d.cols} 异常行=${d.badCells}`)
        if (d.active !== label) failures.push(`${label} 切换未生效（active=${d.active}）`)
        if (d.badCells > 0) failures.push(`${label} 第${pass}轮有 ${d.badCells} 行单元格数≠列数（残留泄漏行）`)
        if (pass === 1) firstPass[label] = d.rowCount
        else if (firstPass[label] !== d.rowCount)
          failures.push(`${label} 行数漂移：首轮 ${firstPass[label]} → 次轮 ${d.rowCount}（泄漏累积）`)
      }
    }
  } finally {
    ws?.close()
    chrome.kill()
  }

  if (failures.length) {
    console.error(`\nFAIL ${failures.length} 项：`)
    failures.forEach((f) => console.error(`  - ${f}`))
    process.exit(1)
  }
  console.log('\nPASS：维度 tab 反复切换无残留行、行数恒定')
}

main().catch((e) => { console.error(e); process.exit(1) })
