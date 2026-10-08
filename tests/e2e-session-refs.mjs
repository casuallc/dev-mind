// 会话关联需求展示与跳转 E2E：SessionSummary 只有 requirementId/workItemId，前端映射出
// 「需求链接 + 工作单元标签」（useSessionRefs + SessionRefsTags）。
// 断言：① 对话视图左栏会话项出现需求链接；② 点链接跳到需求详情页；③ 对话头部操作条出现同款链接；
//      ④ 列表视图「关联」列有链接。（fake runner 起会话，关联需求自动建工作单元 → 两个标签都覆盖）
// 前置：后端 :8080 + 前端 :5173 已起（scripts/dev.sh），runner 在线（executor=fake 即可）。
// 用法：node tests/e2e-session-refs.mjs   （BASE/API/USER/PASS/PROJECT_ID/CHROME_PATH 可覆盖）
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
const PROFILE = join(ROOT, 'tmp', 'chrome-session-refs')
const PORT = 9343

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

async function main() {
  const { accessToken, refreshToken, user } = await api('POST', '/auth/login', { username: USER, password: PASS })
  console.log('[1] 登录 ok')

  // 数据准备：选一个非 WORKLOG 项目，建需求 + 带关联需求的会话（fake runner）
  const projects = await api('GET', '/projects', undefined, accessToken)
  const project = process.env.PROJECT_ID
    ? projects.find((p) => p.id === process.env.PROJECT_ID)
    : projects.find((p) => p.kind !== 'WORKLOG')
  if (!project) throw new Error('无可用项目')
  const nodes = await api('GET', '/agent-nodes', undefined, accessToken)
  const node = nodes.find((n) => n.status === 'ONLINE')
  if (!node) throw new Error('无在线 runner 节点（先起 runner）')
  const req = await api('POST', `/projects/${project.id}/requirements`, { title: 'E2E 会话关联展示验证' }, accessToken)
  const session = await api('POST', '/sessions', {
    taskSpec: 'e2e：验证会话关联需求展示（fake 执行体，可删）',
    projectId: project.id,
    requirementId: req.id,
    agentNodeId: String(node.id),
  }, accessToken)
  const reqUrl = `/projects/${project.id}/requirements/${req.id}`
  console.log(`[2] 数据就绪：项目=${project.name ?? project.id} 需求=${req.code ?? req.id} 会话=${session.id}（workItemId=${session.workItemId ?? '无'}）`)

  const failures = []
  let chrome
  let ws
  try {
    rmSync(PROFILE, { recursive: true, force: true })
    chrome = spawn(CHROME, [
      '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
      '--no-first-run', '--no-default-browser-check', '--disable-extensions',
      '--window-size=1900,1000', 'about:blank',
    ], { stdio: 'ignore' })

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
      localStorage.setItem('devmind.currentProjectId', ${JSON.stringify(project.id)});
      return 'ok';
    })()`)

    // ① 对话视图左栏：会话项内出现指向需求详情页的链接
    ws.reset()
    await ws.send('Page.navigate', { url: `${BASE}/sessions?sid=${session.id}` })
    await ws.waitEvent('Page.loadEventFired', 20000).catch(() => {})
    const paneHit = await waitFor(
      () => evaluate(ws, `(() => {
        const a = [...document.querySelectorAll('.ant-list a')]
          .find((e) => e.getAttribute('href') === ${JSON.stringify(reqUrl)});
        return a ? a.textContent.trim() : null;
      })()`),
      '左栏需求链接渲染', 20000,
    )
    console.log(`[3] 左栏需求链接：「${paneHit}」`)
    if (!paneHit.includes(req.code ?? '')) failures.push(`左栏链接文本未含需求编码 ${req.code}：${paneHit}`)

    // ③ 对话头部操作条：同款链接（选中会话后渲染在 id/状态旁）
    const headHit = await waitFor(
      () => evaluate(ws, `(() => {
        const a = [...document.querySelectorAll('.ant-badge a, .ant-space a')]
          .find((e) => e.getAttribute('href') === ${JSON.stringify(reqUrl)});
        return a ? a.textContent.trim() : null;
      })()`),
      '头部需求链接渲染', 10000,
    ).catch(() => null)
    console.log(`[4] 头部需求链接：「${headHit}」`)
    if (!headHit) failures.push('对话头部操作条未出现需求链接')

    // ② 点击左栏链接 → 跳到需求详情页
    await evaluate(ws, `(() => {
      const a = [...document.querySelectorAll('.ant-list a')]
        .find((e) => e.getAttribute('href') === ${JSON.stringify(reqUrl)});
      a.click();
      return true;
    })()`)
    await waitFor(
      () => evaluate(ws, `location.pathname === ${JSON.stringify(reqUrl)} ? location.pathname : null`),
      '跳转需求详情页', 10000,
    )
    console.log(`[5] 点击跳转 → ${reqUrl} ok`)

    // ④ 列表视图「关联」列
    ws.reset()
    await ws.send('Page.navigate', { url: `${BASE}/sessions` })
    await ws.waitEvent('Page.loadEventFired', 20000).catch(() => {})
    await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-segmented-item').length > 0`), '视图切换器', 10000)
    await evaluate(ws, `(() => {
      const it = [...document.querySelectorAll('.ant-segmented-item')]
        .find((e) => (e.textContent ?? '').trim() === '列表');
      it?.querySelector('input')?.click();
      return true;
    })()`)
    const colHit = await waitFor(
      () => evaluate(ws, `(() => {
        const a = [...document.querySelectorAll('.ant-table a')]
          .find((e) => e.getAttribute('href') === ${JSON.stringify(reqUrl)});
        return a ? a.textContent.trim() : null;
      })()`),
      '列表视图关联列链接', 15000,
    )
    console.log(`[6] 列表「关联」列链接：「${colHit}」`)
  } finally {
    ws?.close()
    chrome?.kill()
    // 清理：杀会话 → 删会话 → 删需求（自动建的工作单元可能挡住删需求，兜底提示）
    await api('POST', `/sessions/${session.id}/kill`, undefined, accessToken).catch(() => {})
    await api('DELETE', `/sessions/${session.id}`, undefined, accessToken).catch((e) => console.warn(`清理会话失败：${e.message}`))
    await api('DELETE', `/projects/${project.id}/requirements/${req.id}`, undefined, accessToken)
      .catch((e) => console.warn(`清理需求失败（可手工删 ${req.code ?? req.id}）：${e.message}`))
  }

  if (failures.length) {
    console.error(`\nFAIL ${failures.length} 项：`)
    failures.forEach((f) => console.error(`  - ${f}`))
    process.exit(1)
  }
  console.log('\nPASS：左栏/头部/列表三处均渲染需求链接，点击可跳需求详情页')
}

main().catch((e) => { console.error(e); process.exit(1) })
