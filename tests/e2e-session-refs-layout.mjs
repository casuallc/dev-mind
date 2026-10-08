// 会话列表「关联」列溢出回归：长需求标题必须在列内省略截断，不得视觉上压到「摘要」列。
// 造数：超长标题需求 + fake runner 会话 → 列表视图量取链接/标签矩形，断言不越出关联单元格内容盒。
// 前置：后端 :8080 + 前端 :5173 已起（scripts/dev.sh），runner 在线（executor=fake 即可）。
// 用法：node tests/e2e-session-refs-layout.mjs   （BASE/API/USER/PASS/PROJECT_ID/CHROME_PATH 可覆盖）
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
const PROFILE = join(ROOT, 'tmp', 'chrome-session-refs-layout')
const PORT = 9344

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

  const projects = await api('GET', '/projects', undefined, accessToken)
  const project = process.env.PROJECT_ID
    ? projects.find((p) => p.id === process.env.PROJECT_ID)
    : projects.find((p) => p.kind !== 'WORKLOG')
  if (!project) throw new Error('无可用项目')
  const nodes = await api('GET', '/agent-nodes', undefined, accessToken)
  const node = nodes.find((n) => n.status === 'ONLINE')
  if (!node) throw new Error('无在线 runner 节点（先起 runner）')
  // 超长标题：旧实现 240px 链接溢出 220 列宽压到摘要列的复现场景
  const longTitle = 'E2E 关联列溢出回归——这是一段足够长的需求标题用来验证列表关联列省略截断不再压到摘要列的展示问题'
  const req = await api('POST', `/projects/${project.id}/requirements`, { title: longTitle }, accessToken)
  const session = await api('POST', '/sessions', {
    taskSpec: 'e2e：关联列布局回归（fake 执行体，可删）',
    projectId: project.id,
    requirementId: req.id,
    agentNodeId: String(node.id),
  }, accessToken)
  const reqUrl = `/projects/${project.id}/requirements/${req.id}`
  console.log(`[2] 数据就绪：需求=${req.code ?? req.id}（${longTitle.length} 字）会话=${session.id}`)

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

    // 列表视图
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

    // 等关联单元格渲染出链接后量矩形。注意：inline <a> 被 overflow:hidden 祖先裁掉时
    // getBoundingClientRect 仍报未裁剪的布局盒，所以改用 elementFromPoint 探「视觉归属」：
    // ① 摘要单元格内取点，命中的元素必须属于摘要 td（关联链接压过来时会命中链接）
    // ② 链接的裁剪祖先（Typography.Text / 根 span）右缘 ≤ 关联 td 内容盒右缘
    // ③ 链接确实被省略截断（内容宽 > 可视宽）
    const measure = await waitFor(
      () => evaluate(ws, `(() => {
        const a = [...document.querySelectorAll('.ant-table a')]
          .find((e) => e.getAttribute('href') === ${JSON.stringify(reqUrl)});
        if (!a) return null;
        const td = a.closest('td');
        const nextTd = td.nextElementSibling;
        const cs = getComputedStyle(td);
        const tdBox = td.getBoundingClientRect();
        const contentRight = tdBox.right - parseFloat(cs.paddingRight);
        const midY = tdBox.top + tdBox.height / 2;
        // 裁剪祖先：overflow:hidden 的最近父元素
        let clip = a.parentElement;
        while (clip && clip !== td && getComputedStyle(clip).overflow === 'visible') clip = clip.parentElement;
        const clipBox = clip && clip !== td ? clip.getBoundingClientRect() : null;
        let hitOwner = null;
        if (nextTd) {
          const nb = nextTd.getBoundingClientRect();
          const hit = document.elementFromPoint(nb.left + 10, midY);
          hitOwner = hit ? (nextTd.contains(hit) ? 'summary' : (td.contains(hit) ? 'refs' : 'other:' + hit.tagName + '.' + hit.className)) : 'none';
        }
        return {
          clipRight: clipBox ? clipBox.right : null,
          contentRight,
          hitOwner,
          clipped: a.scrollWidth > a.clientWidth + 1 || (a.parentElement && a.parentElement.scrollWidth > a.parentElement.clientWidth + 1),
          text: a.textContent.trim(),
        };
      })()`),
      '关联列链接渲染', 20000,
    )
    console.log(`[3] 量取：clipRight=${measure.clipRight?.toFixed(1)} contentRight=${measure.contentRight.toFixed(1)} 摘要列命中=${measure.hitOwner} clipped=${measure.clipped}`)
    if (measure.clipRight != null && measure.clipRight > measure.contentRight + 1)
      failures.push(`链接裁剪容器越出关联单元格内容盒：${measure.clipRight.toFixed(1)} > ${measure.contentRight.toFixed(1)}`)
    if (measure.hitOwner !== 'summary' && measure.hitOwner != null)
      failures.push(`摘要单元格内取点命中了「${measure.hitOwner}」——关联列内容视觉上压到摘要列`)
    if (!measure.clipped) failures.push(`长标题未被省略截断（scrollWidth 未超可视宽）：「${measure.text}」`)
  } finally {
    ws?.close()
    chrome?.kill()
    // Chrome 退出有延迟，立刻 rm 会 EPERM——重试几次
    for (let i = 0; i < 10; i++) {
      try {
        rmSync(PROFILE, { recursive: true, force: true })
        break
      } catch {
        await sleep(500)
      }
    }
  }

  if (failures.length) {
    console.error('FAIL:\n- ' + failures.join('\n- '))
    process.exit(1)
  }
  console.log('PASS：长需求标题在「关联」列内省略截断，不压「摘要」列')
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
