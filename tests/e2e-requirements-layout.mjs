// 需求列表页布局回归：整页不出纵向滚动条，滚动只发生在表体（表头吸顶、分页条常驻）。
// 自带数据：在临时项目（默认「布局校验-临时」）里造 N 条需求驱动表格溢出，跑完删除。
// 前置：后端 :8080 + 前端 :5173 已起（后端跑本机 local profile）。
// 用法：node tests/e2e-requirements-layout.mjs [--keep] [--rows 30]
//   BASE=http://localhost:5173 API=http://localhost:8080 USER=admin PASS=admin123 可覆盖。
// 运行产物（截图、chrome profile、日志）写 tmp/，不入库。
import { spawn, execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:8080'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH
  ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const MARK = '布局校验-临时'
const rowsArg = process.argv.findIndex((a) => a === '--rows')
const ROWS = Number(rowsArg >= 0 ? process.argv[rowsArg + 1] : (process.env.ROWS ?? 30))
const KEEP = process.argv.includes('--keep')

const TMP = join(ROOT, 'tmp')
const SHOT = join(TMP, 'layout-check-requirements.png')
const PROFILE = join(TMP, 'chrome-layout-check')
const PORT = 9333

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)

async function api(method, path, body, token) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`)
  return text ? JSON.parse(text) : null
}

/** 最小 CDP 客户端（Node 内置 WebSocket，无第三方依赖） */
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
    waitEvent(method, timeout = 15000) {
      const hit = events.find((e) => e.method === method)
      if (hit) return Promise.resolve(hit.params)
      return new Promise((resolve, reject) => {
        const w = { method, resolve }
        waiters.push(w)
        setTimeout(() => reject(new Error(`等 ${method} 超时`)), timeout)
      })
    },
    close: () => ws.close(),
  }
}

async function waitFor(pred, what, timeout = 20000, interval = 300) {
  const deadline = Date.now() + timeout
  for (;;) {
    const v = await pred().catch(() => null)
    if (v) return v
    if (Date.now() > deadline) throw new Error(`等待「${what}」超时`)
    await sleep(interval)
  }
}

const checks = []
const check = (name, ok, detail) => {
  checks.push({ name, ok, detail })
  log(`${ok ? '  PASS' : '  FAIL'}  ${name}${detail ? ` — ${detail}` : ''}`)
}

async function main() {
  mkdirSync(TMP, { recursive: true })

  // ── 1. 造数据 ─────────────────────────────────────────────
  const { accessToken, refreshToken, user } = await api('POST', '/auth/login', { username: USER, password: PASS })
  const projects = await api('GET', '/projects', undefined, accessToken)
  let project = (projects.items ?? projects).find((p) => p.name === MARK)
  if (!project) {
    const repo = join(TMP, 'layout-check-repo')
    if (!existsSync(join(repo, '.git'))) {
      mkdirSync(repo, { recursive: true })
      execFileSync('git', ['init', '-q', '-b', 'main', repo])
    }
    project = await api('POST', '/projects', { name: MARK, sourceType: 'LOCAL', path: repo }, accessToken)
  }
  const existing = await api('GET', `/projects/${project.id}/requirements?size=200&status=ALL&source=ALL`, undefined, accessToken)
  const rows = existing.items?.length ?? 0
  for (let i = rows; i < ROWS; i++) {
    await api('POST', `/projects/${project.id}/requirements`, {
      title: `布局校验需求 ${String(i + 1).padStart(2, '0')}：表格内滚动与表头吸顶验证`,
      description: '布局回归脚本自动创建的临时数据，脚本结束即删除。',
    }, accessToken)
  }
  log(`[1] 项目 ${project.id}（${MARK}）需求 ${Math.max(rows, ROWS)} 条`)

  // ── 2. 起 headless Chrome ─────────────────────────────────
  rmSync(PROFILE, { recursive: true, force: true })
  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1600,900',
    'about:blank',
  ], { stdio: 'ignore' })

  let ws
  try {
    const target = await waitFor(async () => {
      const list = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
      return list.find((t) => t.type === 'page')?.webSocketDebuggerUrl
    }, 'Chrome 调试端口')
    ws = cdp(target)
    await ws.ready
    await ws.send('Page.enable')
    await ws.send('Runtime.enable')

    // 先落到同源页面，再写 localStorage（令牌 + 当前项目），避免被路由守卫踢回登录页
    await ws.send('Page.navigate', { url: `${BASE}/` })
    await ws.waitEvent('Page.loadEventFired')
    await ws.send('Runtime.evaluate', {
      expression: `(() => {
        localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
        localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
        localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
        localStorage.setItem('devmind.currentProjectId', ${JSON.stringify(project.id)});
        return 'ok';
      })()`,
    })

    // ── 3. 打开需求列表，等表格出数据 ───────────────────────
    await ws.send('Page.navigate', { url: `${BASE}/requirements` })
    await ws.waitEvent('Page.loadEventFired')
    const rowsInDom = await waitFor(async () => {
      const { result } = await ws.send('Runtime.evaluate', {
        expression: 'document.querySelectorAll(".ant-table-tbody tr.ant-table-row").length',
        returnByValue: true,
      })
      return result.value > 0 ? result.value : null
    }, '需求表格渲染出数据')
    log(`[2] 页面渲染完成，表格行 ${rowsInDom} 行`)

    // ── 4. 断言 ────────────────────────────────────────────
    const probe = `(() => {
      const content = document.querySelector('.ant-layout-content');
      const body = document.querySelector('.ant-table-body');
      const header = document.querySelector('.ant-table-header');
      const pager = document.querySelector('.ant-table-pagination');
      const card = document.querySelector('.ant-card-body');
      const headRect = header ? header.getBoundingClientRect().top : null;
      const firstRow = document.querySelector('.ant-table-tbody tr.ant-table-row');
      const rowTop = firstRow ? firstRow.getBoundingClientRect().top : null;
      if (body) body.scrollTop = 160;   // 表体内部滚动一段，验证表头/分页不动
      const scrolled = body ? body.scrollTop : 0;
      return {
        docScroll: document.documentElement.scrollHeight - document.documentElement.clientHeight,
        contentScroll: content ? content.scrollHeight - content.clientHeight : null,
        bodyOverflow: content ? getComputedStyle(content).overflowY : null,
        bodyCount: document.querySelectorAll('.ant-table-body').length,
        bodyClient: body ? body.clientHeight : null,
        bodyScroll: body ? body.scrollHeight : null,
        bodyMaxHeight: body ? parseFloat(getComputedStyle(body).maxHeight) : null,
        headerSplit: !!header,
        headTop: headRect, rowTop, scrolled,
        pagerBottom: pager ? pager.getBoundingClientRect().bottom : null,
        pagerTop: pager ? pager.getBoundingClientRect().top : null,
        viewportH: window.innerHeight,
        cardClient: card ? card.clientHeight : null,
        cardScroll: card ? card.scrollHeight : null,
      };
    })()`
    const { result } = await ws.send('Runtime.evaluate', { expression: probe, returnByValue: true })
    const m = result.value

    check('整页无纵向滚动条（Content 不溢出）', m.contentScroll <= 1, `Content 溢出 ${m.contentScroll}px`)
    check('文档本身不滚动', m.docScroll <= 1, `document 溢出 ${m.docScroll}px`)
    check('表体内部滚动生效', m.bodyScroll - m.bodyClient > 100, `表体 ${m.bodyClient}px 可视 / ${m.bodyScroll}px 内容`)
    check('表体高度是实测值而非魔法数', m.bodyMaxHeight > 100, `max-height=${m.bodyMaxHeight}px`)
    check('表体滚动后表头仍吸顶', m.headerSplit && m.headTop < m.cardClient, `表头 top=${m.headTop}，已滚 ${m.scrolled}px`)
    check('分页条常驻首屏', m.pagerBottom <= m.viewportH, `分页条 bottom=${Math.round(m.pagerBottom)} / 视口 ${m.viewportH}`)
    check('Card body 自身不滚动（滚动在表内）', m.cardScroll - m.cardClient <= 1, `Card body 溢出 ${m.cardScroll - m.cardClient}px`)

    const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
    writeFileSync(SHOT, Buffer.from(shot.data, 'base64'))
    log(`[3] 截图 ${SHOT}`)
  } finally {
    ws?.close()
    chrome.kill()
  }

  // ── 5. 清理：临时项目由本脚本独占，整包删掉，不留残渣 ───────
  if (KEEP) {
    log('[4] --keep：保留临时数据')
  } else {
    const rest = await api('GET', `/projects/${project.id}/requirements?size=200&status=ALL&source=ALL`, undefined, accessToken).catch(() => null)
    const ids = rest?.items?.map((r) => r.id) ?? []
    for (const id of ids) {
      await api('DELETE', `/projects/${project.id}/requirements/${id}`, undefined, accessToken).catch(() => {})
    }
    await api('DELETE', `/projects/${project.id}`, undefined, accessToken).catch(() => {})
    log(`[4] 已清理临时项目 ${project.id} 及其 ${ids.length} 条需求`)
  }

  const failed = checks.filter((c) => !c.ok)
  log(failed.length ? `\n结果：${checks.length - failed.length}/${checks.length} 通过` : `\n结果：全部 ${checks.length} 项通过`)
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => {
  console.error(`\n脚本失败：${e.message}`)
  process.exit(1)
})
