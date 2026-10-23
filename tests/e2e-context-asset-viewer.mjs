// 项目「知识」页资产查看：知识条目/文档列表点名称开只读抽屉看全文（此前只有列表）。
// 覆盖：
//   1) /context 默认知识条目视图，条目名称是可点链接；
//   2) 点开抽屉 → 标题=条目名、正文含知识内容、宽度 70%；
//   3) 切到「文档」视图 → 文档名称可点，抽屉正文含文档内容与类型标签；
//   4) 关闭抽屉后列表仍在。
// 前置：app 隔离实例 + 指向它的前端 dev 同时起（数据自造，落隔离 H2，无需 runner）：
//   mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
//     "-Dspring-boot.run.arguments=--server.port=18103 --spring.datasource.url=jdbc:h2:file:<tmp>/db/devmind;AUTO_SERVER=TRUE"
//   VITE_BACKEND_URL=http://localhost:18103 npm run dev -- --port 5199 --strictPort   # frontend/ 下
//   node tests/e2e-context-asset-viewer.mjs [--shots]   （API/BASE/CHROME_PATH 可覆盖）
import { execFileSync, spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5199'
const API = process.env.API ?? 'http://localhost:18103'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9472
const TMP = join(ROOT, 'tmp', 'context-asset-viewer')
const PROFILE = join(TMP, 'chrome')
const MARK = `e2e-view-${Date.now()}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let passed = 0
function ok(cond, what) {
  if (!cond) throw new Error(`断言失败：${what}`)
  passed++
  console.log(`  ✔ ${what}`)
}

async function api(path, { method = 'POST', body, token } = {}) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`)
  return text ? JSON.parse(text) : null
}

/** 最小 CDP 客户端（Node 内置 WebSocket，与 e2e-injection-preview-drawer.mjs 同套路） */
function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  const pending = new Map()
  let seq = 0
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
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
    close: () => ws.close(),
  }
}

async function waitFor(pred, what, timeout = 15000, interval = 250) {
  const deadline = Date.now() + timeout
  for (;;) {
    const v = await pred().catch(() => null)
    if (v) return v
    if (Date.now() > deadline) throw new Error(`等待「${what}」超时`)
    await sleep(interval)
  }
}

async function evaluate(ws, expression) {
  const { result, exceptionDetails } = await ws.send('Runtime.evaluate', {
    expression, returnByValue: true, awaitPromise: true,
  })
  if (exceptionDetails) throw new Error(`页面脚本异常：${JSON.stringify(exceptionDetails).slice(0, 300)}`)
  return result.value
}

// Drawer 是 fixed 定位，offsetParent 恒 null；可见性用 class + computedStyle（见 CDP 坑注释）
const drawerVisibleJs = `(() => {
  const d = document.querySelector('.ant-drawer-content');
  if (!d) return false;
  const wrap = d.closest('.ant-drawer');
  return !!wrap && getComputedStyle(wrap).display !== 'none' && wrap.classList.contains('ant-drawer-open');
})()`
// 按文字找表格里的名称链接
const nameLinkJs = (text) => `(() => {
  return [...document.querySelectorAll('.ant-table-tbody a')]
    .find((a) => a.textContent.trim() === ${JSON.stringify(text)}) ?? null;
})()`
// 按文字找 Segmented 选项卡（antd 会给两字标签插空格）
const segJs = (text) => `(() => {
  return [...document.querySelectorAll('.ant-segmented-item')]
    .find((el) => el.textContent.replace(/\\s/g, '') === ${JSON.stringify(text)}) ?? null;
})()`
const drawerBodyTextJs = `(() => document.querySelector('.ant-drawer-body')?.innerText ?? '')()`

let chrome
async function main() {
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(TMP, { recursive: true })
  // 每轮新路径 + 真 git 仓库：项目 path 全局唯一，同库重跑不会撞 409
  const repoPath = join(TMP, `repo-${MARK}`)
  mkdirSync(repoPath, { recursive: true })
  execFileSync('git', ['init', '-q'], { cwd: repoPath })

  const login = await api('/auth/login', { body: { username: USER, password: PASS } })
  const { accessToken, refreshToken, user } = login

  // ── 造数：项目 + 文档 + 全局知识条目 ──────────────────────
  const project = await api('/projects', {
    token: accessToken,
    body: {
      name: `查看验证-${MARK}`,
      path: repoPath,
      defaultBranch: 'master',
      tags: [],
      status: 'ACTIVE',
    },
  })
  const docTitle = `查看验证文档-${MARK}`
  const docMarker = `文档正文-${MARK}`
  const doc = await api('/documents', {
    token: accessToken,
    body: {
      kind: 'design',
      projectId: project.id,
      title: docTitle,
      tags: ['e2e'],
      contentMd: `# ${docTitle}\n\n这是文档正文，marker：${docMarker}\n\n- 要点一\n- 要点二`,
    },
  })
  const entryName = `查看验证知识-${MARK}`
  const entryMarker = `知识正文-${MARK}`
  const entry = await api('/knowledge/entries', {
    token: accessToken,
    body: {
      scope: 'global',
      name: entryName,
      contentMd: `## ${entryName}\n\n这是知识条目正文，marker：${entryMarker}`,
      tags: [],
      status: 'active',
    },
  })
  ok(!!project.id && !!doc.id && !!entry.id, '造数完成（项目/文档/知识条目）')

  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  chrome = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1600,900', 'about:blank',
  ], { stdio: 'ignore' })
  const ws = cdp(await waitFor(async () => {
    const l = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
    return l.find((t) => t.type === 'page')?.webSocketDebuggerUrl
  }, 'Chrome 调试端口'))
  await ws.ready
  await ws.send('Page.enable')
  await ws.send('Runtime.enable')

  try {
    await ws.send('Page.navigate', { url: `${BASE}/` })
    // Page.navigate 只等 commit 不等 load：先在旧文档上写 localStorage 会写错 origin
    await waitFor(() => evaluate(ws, `document.readyState === 'complete'`), '首页加载完', 30000)
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      localStorage.setItem('devmind.currentProjectId', ${JSON.stringify(project.id)});
      return 'ok';
    })()`)
    await ws.send('Page.navigate', { url: `${BASE}/context` })
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-table')`), '项目知识页表格渲染', 30000)

    // ── 1. 知识条目：名称是链接 ─────────────────────────────
    await waitFor(() => evaluate(ws, `!!${nameLinkJs(entryName)}`), '知识条目名称链接', 30000)
    ok(true, '知识条目视图的名称是可点链接（列表不再只读）')

    // ── 2. 点开抽屉，正文可见 ───────────────────────────────
    await evaluate(ws, `${nameLinkJs(entryName)}.click()`)
    await waitFor(() => evaluate(ws, drawerVisibleJs), '知识条目查看抽屉打开')
    await waitFor(() => evaluate(ws, `${drawerBodyTextJs}.includes(${JSON.stringify(entryMarker)})`), '知识正文渲染')
    const entryDrawer = await evaluate(ws, `(() => {
      const d = document.querySelector('.ant-drawer-content');
      const r = d.getBoundingClientRect();
      return {
        title: document.querySelector('.ant-drawer-title')?.textContent ?? '',
        winW: window.innerWidth,
        width: r.width,
      };
    })()`)
    ok(entryDrawer.title === entryName, `抽屉标题=条目名（${entryDrawer.title}）`)
    ok(entryDrawer.width / entryDrawer.winW > 0.6, `查看抽屉宽度（${entryDrawer.width}/${entryDrawer.winW} ≈ 70%）`)

    // ── 3. 切到文档视图，点文档名看全文 ─────────────────────
    await evaluate(ws, `document.querySelector('.ant-drawer-close').click()`)
    await waitFor(async () => !(await evaluate(ws, drawerVisibleJs)), '知识条目抽屉关闭')
    await evaluate(ws, `${segJs('文档')}.click()`)
    await waitFor(() => evaluate(ws, `!!${nameLinkJs(docTitle)}`), '文档视图名称链接', 30000)
    await evaluate(ws, `${nameLinkJs(docTitle)}.click()`)
    await waitFor(() => evaluate(ws, drawerVisibleJs), '文档查看抽屉打开')
    await waitFor(() => evaluate(ws, `${drawerBodyTextJs}.includes(${JSON.stringify(docMarker)})`), '文档正文渲染')
    const docDrawer = await evaluate(ws, `(() => ({
      title: document.querySelector('.ant-drawer-title')?.textContent ?? '',
      body: document.querySelector('.ant-drawer-body')?.innerText ?? '',
    }))()`)
    ok(docDrawer.title === docTitle, `抽屉标题=文档名（${docDrawer.title}）`)
    ok(docDrawer.body.includes('技术方案'), '抽屉内展示文档类型标签（技术方案）')

    // ── 4. 关抽屉后文档表格仍在 ─────────────────────────────
    await evaluate(ws, `document.querySelector('.ant-drawer-close').click()`)
    await waitFor(async () => !(await evaluate(ws, drawerVisibleJs)), '文档抽屉关闭')
    ok(await evaluate(ws, `!!document.querySelector('.ant-table')`), '关抽屉后文档表格仍在')

    // 列宽：名称是主列（自适应），必须比固定宽的摘要更宽
    const colWidths = await evaluate(ws, `(() => {
      const w = {};
      for (const th of document.querySelectorAll('.ant-table-thead th')) {
        w[th.textContent.trim()] = th.getBoundingClientRect().width;
      }
      return w;
    })()`)
    ok(colWidths['名称'] >= 320 && colWidths['名称'] > colWidths['摘要'],
      `列宽合理：名称 ${Math.round(colWidths['名称'])}px > 摘要 ${Math.round(colWidths['摘要'])}px`)

    // --shots：留一张文档列表终态图，便于人工核对列宽（产物落 tmp/，不入库）
    if (process.argv.includes('--shots')) {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'final.png'), Buffer.from(shot.data, 'base64'))
      console.log(`  截图 → ${join(TMP, 'final.png')}`)
    }
  } catch (e) {
    try {
      const diag = await evaluate(ws, `location.href + ' | ' + document.body.innerText.slice(0, 300)`)
      console.error(`[诊断] ${diag}`)
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'failure.png'), Buffer.from(shot.data, 'base64'))
      console.error(`[诊断] 截图 → ${join(TMP, 'failure.png')}`)
    } catch { /* 诊断本身失败就算了 */ }
    throw e
  } finally {
    ws.close()
  }

  console.log(`\n全部 ${passed} 项断言通过`)
}

main().catch((e) => { console.error(`\n✖ ${e.message}`); process.exitCode = 1 })
  .finally(async () => {
    // 只杀自己 spawn 的那棵 Chrome 进程树（按 PID，别按窗口标题——会误杀用户真实浏览器）
    if (chrome?.pid) {
      spawn('cmd', ['/c', 'taskkill', '/F', '/T', '/PID', String(chrome.pid)], { stdio: 'ignore' })
      await sleep(1200)
    }
    try {
      rmSync(PROFILE, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
    } catch { /* profile 留在 tmp/ 无碍，下次跑开头还会清 */ }
  })
