// 全站布局回归：逐页（含页内 Segmented 各视图）巡检「内容区高度撑满、滚动只发生在页面内部」。
// 覆盖后端托管的前端全部列表/管理页 + 个人页；每个路由断言：
//   1) .ant-layout-content 不溢出（整页无纵向滚动条）
//   2) document 不溢出
//   3) 无「溢出内容区底部且祖先都不滚动」的元素（漏了滚动容器 → 内容被裁/顶破卡片）
// 前置：后端 :8080 + 前端 :5173 已起。
// 用法：node tests/e2e-layout-pages.mjs [--only knowledge,worklog] [--shots] [--keep] [--settle 800]
//   BASE=http://localhost:5173 API=http://localhost:8080 USER=admin PASS=admin123 CHROME_PATH=… 可覆盖。
// 运行产物（截图、报告、chrome profile）写 tmp/，不入库。
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:8080'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const argOf = (name, dflt) => {
  const i = process.argv.findIndex((a) => a === name)
  return i >= 0 ? process.argv[i + 1] : dflt
}
const SETTLE = Number(argOf('--settle', 800))
const WINDOW = argOf('--window', '1600,900')   // 视口越小越容易暴露魔法数残留，可再跑一轮 1366,768
const [WIN_W, WIN_H] = WINDOW.split(',').map((s) => Number(s.trim()))
const SHOTS_ALL = process.argv.includes('--shots')
const DUMP = process.argv.includes('--dump')
const KEEP = process.argv.includes('--keep')
// 注意别写前导斜杠：Git Bash 会把 `/a,/b` 当路径转成 `C:/Program Files/Git/a,/b`。
// 这里做兜底还原（去 Git 安装前缀 + 去前导斜杠），`--only requirements,admin/knowledge` 与 `--only /requirements` 都能用。
const ONLY = (argOf('--only', '') ?? '')
  .split(',')
  .map((s) => s.trim().replace(/^.*\/Git\//, '').replace(/^\/+/, '').replace(/\/+$/, ''))
  .filter(Boolean)

const TMP = join(ROOT, 'tmp')
const OUT = join(TMP, 'layout-sweep')
const PROFILE = join(TMP, 'chrome-layout-sweep')
const PORT = 9334

// ctx: 需要当前项目上下文（门控路由）；warn: 失败只提示不判负（非本次改动范围，仅登记现状）
const ROUTES = [
  { path: '/overview', name: '项目概览', ctx: true },
  { path: '/sessions', name: '项目会话', ctx: true },
  { path: '/requirements', name: '需求列表', ctx: true },
  { path: '/context', name: '项目上下文', ctx: true },
  { path: '/builds', name: '构建', ctx: true },
  { path: '/deployments', name: '部署', ctx: true },
  { path: '/releases', name: '发版', ctx: true },
  { path: '/tests', name: '测试', ctx: true },
  { path: '/home', name: '工作台首页' },
  { path: '/chats', name: 'AI 问答' },
  { path: '/worklog', name: '工作日志' },
  { path: '/settings/profile', name: '个人设置' },
  { path: '/notifications', name: '通知中心' },
  { path: '/admin/dashboard', name: '后台-仪表盘' },
  { path: '/admin/projects', name: '后台-项目' },
  { path: '/admin/repos', name: '后台-仓库' },
  { path: '/admin/users', name: '后台-用户' },
  { path: '/admin/execution', name: '后台-执行' },
  { path: '/admin/integrations', name: '后台-集成' },
  { path: '/admin/keys', name: '后台-开放 API' },
  { path: '/admin/agent-nodes', name: '后台-执行节点' },
  { path: '/admin/scenarios', name: '后台-场景' },
  { path: '/admin/knowledge', name: '后台-知识库' },
  { path: '/admin/skills', name: '后台-Skill' },
  { path: '/admin/docs', name: '后台-文档库' },
  { path: '/admin/attachments', name: '后台-附件' },
  { path: '/admin/projects/{pid}/repos', name: '项目设置-仓库', ctx: true },
  { path: '/admin/projects/{pid}/summary', name: '项目设置-概览', ctx: true },
  { path: '/admin/projects/{pid}/environments', name: '项目设置-环境', ctx: true },
  { path: '/admin/projects/{pid}/build', name: '项目设置-构建', ctx: true },
  { path: '/admin/projects/{pid}/release', name: '项目设置-发版', ctx: true },
  { path: '/admin/projects/{pid}/jira', name: '项目设置-Jira', ctx: true },
  { path: '/admin/projects/{pid}/lock', name: '项目设置-锁', ctx: true },
]

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)

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
    /** 导航前清掉已捕获的事件，避免 waitEvent 命中上一轮的旧事件（多路由循环必踩） */
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

/** 在页面里量内容区溢出 / 找出「越界且祖先不滚动」的元素 */
const PROBE = `(() => {
  const content = document.querySelector('.ant-layout-content');
  if (!content) return { noContent: true };
  const cRect = content.getBoundingClientRect();
  const scrollable = (el) => {
    for (let n = el.parentElement; n && n !== content; n = n.parentElement) {
      const ov = getComputedStyle(n).overflowY;
      if (ov === 'auto' || ov === 'scroll') return true;
    }
    return false;
  };
  const offenders = [];
  for (const el of content.querySelectorAll('*')) {
    const cs = getComputedStyle(el);
    if (cs.visibility === 'hidden' || cs.display === 'none') continue;
    const r = el.getBoundingClientRect();
    if (r.height < 16 || r.width < 60) continue;
    if (r.bottom <= cRect.bottom + 2) continue;
    if (scrollable(el)) continue;
    // 只报「最外层」越界节点，子节点随父节点一起越界时不重复报
    if (el.parentElement && el.parentElement.closest('.ant-layout-content') &&
        el.parentElement.getBoundingClientRect().bottom > cRect.bottom + 2) continue;
    offenders.push({
      tag: el.tagName.toLowerCase(),
      cls: (typeof el.className === 'string' ? el.className : '').split(/\\s+/).filter(Boolean).slice(0, 3).join('.'),
      bottom: Math.round(r.bottom),
    });
    if (offenders.length >= 5) break;
  }
  return {
    contentOverflow: content.scrollHeight - content.clientHeight,
    contentBottom: Math.round(cRect.bottom),
    docOverflow: document.documentElement.scrollHeight - document.documentElement.clientHeight,
    viewportH: window.innerHeight,
    rows: document.querySelectorAll('.ant-table-tbody tr.ant-table-row').length,
    fitTables: [...document.querySelectorAll('.ant-table-body')]
      .filter((b) => parseFloat(getComputedStyle(b).maxHeight) > 40).length,
    offenders,
  };
})()`

/** --dump：失败时打印内容区组件树（含高度与滚动量），定位是谁把内容区顶破的 */
const TREE = `(() => {
  const c = document.querySelector('.ant-layout-content');
  const out = [];
  const walk = (el, d) => {
    for (const ch of el.children) {
      const r = ch.getBoundingClientRect();
      const cs = getComputedStyle(ch);
      const cls = (typeof ch.className === 'string' ? ch.className : '').split(/\\s+/).filter(Boolean).slice(0, 2).join('.');
      out.push('  '.repeat(d) + ch.tagName.toLowerCase() + (cls ? '.' + cls : '')
        + ' h=' + Math.round(r.height) + ' top=' + Math.round(r.top) + ' bottom=' + Math.round(r.bottom)
        + ' ovY=' + cs.overflowY + ' flexGrow=' + cs.flexGrow + ' minH=' + cs.minHeight
        + ' clientH=' + ch.clientHeight + ' scrollH=' + ch.scrollHeight);
      if (d < 4 && ch.children.length) walk(ch, d + 1);
    }
  };
  walk(c, 0);
  return out.join('\\n');
})()`

const checks = []
function record(route, view, m, warn) {
  const problems = []
  if (m.noContent) problems.push('内容区缺失')
  else {
    if (m.contentOverflow > 1) problems.push(`内容区溢出 ${m.contentOverflow}px`)
    if (m.docOverflow > 1) problems.push(`文档溢出 ${m.docOverflow}px`)
    if (m.offenders.length) {
      problems.push(`越界元素 ${m.offenders.map((o) => `${o.tag}.${o.cls}(bottom=${o.bottom})`).join(' ')}`)
    }
  }
  const label = `${route.path}${view ? ` ▸ ${view}` : ''}`
  checks.push({ label, route: route.path, view, warn: !!warn, ok: !problems.length, detail: problems.join('；'), metrics: m })
  const mark = !problems.length ? '  PASS' : warn ? '  WARN' : '  FAIL'
  log(`${mark}  ${label}${route.name && !view ? `（${route.name}）` : ''}` +
    (m.noContent ? '' : `  [行 ${m.rows} 固定表高 ${m.fitTables}]`) +
    (problems.length ? `\n         ${problems.join('；')}` : ''))
  return !problems.length
}

async function evaluate(ws, expression) {
  const { result, exceptionDetails } = await ws.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  if (exceptionDetails) throw new Error(exceptionDetails.text ?? '页面求值异常')
  return result.value
}

async function main() {
  mkdirSync(OUT, { recursive: true })
  rmSync(PROFILE, { recursive: true, force: true })

  // ── 1. 登录 + 选定项目 ────────────────────────────────────
  const { accessToken, refreshToken, user } = await api('POST', '/auth/login', { username: USER, password: PASS })
  const projects = await api('GET', '/projects', undefined, accessToken)
  const list = projects.items ?? projects
  const project = list[0]
  if (!project) throw new Error('库里没有项目，先建一个再跑巡检（项目上下文页面无处可去）')
  log(`[1] 登录 ${USER}，巡检项目取 ${project.name}（${project.id}）`)

  // ── 2. 起 headless Chrome ─────────────────────────────────
  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    `--window-size=${WIN_W},${WIN_H}`,
    'about:blank',
  ], { stdio: 'ignore' })

  let ws
  const shots = []
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
      localStorage.setItem('devmind.currentProjectId', ${JSON.stringify(project.id)});
      return 'ok';
    })()`)

    // ── 3. 逐路由巡检 ──────────────────────────────────────
    const routes = ROUTES.filter((r) => !ONLY.length || ONLY.some((o) => r.path.includes(o)))
    log(`[2] 巡检 ${routes.length} 个路由（窗口 ${WIN_W}x${WIN_H}，settle ${SETTLE}ms）`)

    for (const route of routes) {
      const url = BASE + route.path.replace('{pid}', project.id)
      ws.reset()
      await ws.send('Page.navigate', { url })
      try {
        await ws.waitEvent('Page.loadEventFired', 20000)
      } catch { /* SPA 内部跳转可能不触发，继续等 DOM */ }
      // 等页面骨架就绪：内容区出现 + 转圈停下（超时也继续，按现状判）
      await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-layout-content')`), '内容区就绪', 8000).catch(() => {})
      await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-spin-spinning').length === 0`), '加载态结束', 8000).catch(() => {})
      await sleep(SETTLE)

      const shot = async (slug) => {
        const s = await ws.send('Page.captureScreenshot', { format: 'png' })
        const f = join(OUT, `${slug}.png`)
        writeFileSync(f, Buffer.from(s.data, 'base64'))
        shots.push(f)
        return f
      }

      let m = await evaluate(ws, PROBE)
      const slug = (p) => p.replace(/^\//, '').replace(/[^\w-]+/g, '_') || 'root'
      const ok = record(route, '', m, route.warn)
      if (!ok || SHOTS_ALL) await shot(slug(route.path))
      if (!ok && DUMP) log(await evaluate(ws, TREE))

      // 页内视图切换（只取内容区第一组 Segmented = 页头视图切换器）
      const views = await evaluate(ws, `(() => {
        const g = document.querySelector('.ant-layout-content .ant-segmented');
        if (!g) return [];
        return [...g.querySelectorAll('.ant-segmented-item')]
          .filter((e) => !e.classList.contains('ant-segmented-item-disabled'))
          .map((e) => (e.querySelector('.ant-segmented-item-label')?.textContent ?? '').trim())
          .filter(Boolean);
      })()`) ?? []

      for (let i = 1; i < views.length; i++) {
        const clicked = await evaluate(ws, `(() => {
          const g = document.querySelector('.ant-layout-content .ant-segmented');
          if (!g) return null;
          const items = [...g.querySelectorAll('.ant-segmented-item')]
            .filter((e) => !e.classList.contains('ant-segmented-item-disabled'));
          const it = items[${i}];
          if (!it) return null;
          it.querySelector('input')?.click();
          return (it.querySelector('.ant-segmented-item-label')?.textContent ?? '').trim();
        })()`)
        if (!clicked) break
        await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-spin-spinning').length === 0`), `「${clicked}」加载结束`, 8000).catch(() => {})
        await sleep(SETTLE)
        m = await evaluate(ws, PROBE)
        const okView = record(route, clicked, m, route.warn)
        if (!okView || SHOTS_ALL) await shot(`${slug(route.path)}__${slug(clicked)}`)
        if (!okView && DUMP) log(await evaluate(ws, TREE))
      }
    }
  } finally {
    ws?.close()
    chrome.kill()
    await sleep(600)   // 等 Chrome 放掉 profile 目录句柄，否则 Windows 上 rm 报 EPERM
  }

  writeFileSync(join(OUT, 'report.json'), JSON.stringify({ at: new Date().toISOString(), checks }, null, 2))
  const failed = checks.filter((c) => !c.ok && !c.warn)
  const warned = checks.filter((c) => !c.ok && c.warn)
  log(`\n[3] 截图/报告 → ${OUT}`)
  if (warned.length) log(`    另有 ${warned.length} 处现状告警（非本次范围）：${warned.map((c) => c.label).join('、')}`)
  log(failed.length
    ? `结果：${checks.length - failed.length - warned.length}/${checks.length - warned.length} 通过，${failed.length} 处失败`
    : `结果：全部 ${checks.length - warned.length} 项通过`)
  if (!KEEP) {
    try {
      rmSync(PROFILE, { recursive: true, force: true })
    } catch {
      log(`    （chrome profile 暂被占用，留待下次运行前清理：${PROFILE}）`)
    }
  }
  process.exit(failed.length ? 1 : 0)
}

main().catch((e) => {
  console.error(`\n脚本失败：${e.message}`)
  process.exit(1)
})
