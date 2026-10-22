// 注入预览抽屉 CDP 真浏览器验证：
//   1) /admin/projects?tab=proposals 的 extra 有「注入预览」按钮（不再是 Segmented 独立 tab）；
//   2) 点击后打开大抽屉（70% 宽）：内含项目选择与「预览注入内容」按钮；
//   3) 不选项目直接预览 → 结果区出现「命中条目」（接口允许空 projectId=只看全局）；
//   4) 关闭抽屉后提案列表仍在（抽屉不顶替页面内容）。
// 前置：app 隔离实例 + 前端 dev 同时起：
//   mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
//     "-Dspring-boot.run.arguments=--server.port=18103 --spring.datasource.url=jdbc:h2:file:<tmp>/db/devmind;AUTO_SERVER=TRUE"
//   VITE_BACKEND_URL=http://localhost:18103 npm run dev -- --port 5199 --strictPort   # frontend/ 下
//   node tests/e2e-injection-preview-drawer.mjs   （API/BASE/CHROME_PATH 可覆盖）
// 只读验证：预览是 GET，不落数据。
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5199'
const API = process.env.API ?? 'http://localhost:18103'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9471
const TMP = join(ROOT, 'tmp', 'injection-preview-drawer')
const PROFILE = join(TMP, 'chrome')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let passed = 0
function ok(cond, what) {
  if (!cond) throw new Error(`断言失败：${what}`)
  passed++
  console.log(`  ✔ ${what}`)
}

async function api(path, body) {
  const res = await fetch(`${API}/api${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`POST ${path} → ${res.status} ${text.slice(0, 300)}`)
  return text ? JSON.parse(text) : null
}

/** 最小 CDP 客户端（Node 内置 WebSocket，与 e2e-table-tag-ellipsis.mjs 同套路） */
function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  const pending = new Map()
  let seq = 0
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error)) ) : resolve(msg.result)
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

// antd 两字按钮会被自动插空格（「注入预览」是四字不受影响，「取消」这类二字要 textContent 去空格比）
const btnJs = (scopeSelector, text) => `(() => {
  const btns = [...document.querySelectorAll(${JSON.stringify(scopeSelector + ' button')})];
  return btns.find((b) => b.textContent.replace(/\\s/g, '') === ${JSON.stringify(text)}) ?? null;
})()`
// Drawer 是 fixed 定位，offsetParent 恒 null；可见性用 getComputedStyle（见开发注意事项/CDP 坑）
const drawerVisibleJs = `(() => {
  const d = document.querySelector('.ant-drawer-content');
  if (!d) return false;
  const wrap = d.closest('.ant-drawer');
  return !!wrap && getComputedStyle(wrap).display !== 'none' && wrap.classList.contains('ant-drawer-open');
})()`

let chrome
async function main() {
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(TMP, { recursive: true })

  const login = await api('/auth/login', { username: USER, password: PASS })
  const { accessToken, refreshToken, user } = login

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
      return 'ok';
    })()`)
    await ws.send('Page.navigate', { url: `${BASE}/admin/projects?tab=proposals` })
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-card')`), '聚合页 Card 渲染', 30000)

    // ── 1. extra 有「注入预览」按钮，Segmented 里不再有该 tab ──
    await waitFor(() => evaluate(ws, `!!${btnJs('.ant-card-head .ant-card-extra', '注入预览')}`),
      '经验提案 extra「注入预览」按钮渲染', 30000)
    ok(await evaluate(ws, `(() => {
      const items = [...document.querySelectorAll('.ant-segmented-item')].map((i) => i.textContent);
      return !items.includes('注入预览') && items.includes('经验提案');
    })()`), 'Segmented 视图清单无「注入预览」tab（已并入经验提案）')

    // ── 2. 点击开大抽屉 ─────────────────────────────────────
    await evaluate(ws, `${btnJs('.ant-card-head .ant-card-extra', '注入预览')}.click()`)
    await waitFor(() => evaluate(ws, drawerVisibleJs), '注入预览抽屉打开')
    const layout = await evaluate(ws, `(() => {
      const d = document.querySelector('.ant-drawer-content');
      const r = d.getBoundingClientRect();
      return { width: r.width, winW: window.innerWidth,
               hasProjectSelect: !!${btnJs('.ant-drawer-body', '预览注入内容')},
               hasForm: !!d.querySelector('.ant-form') };
    })()`)
    ok(layout.hasForm && layout.hasProjectSelect, '抽屉内含项目/任务说明表单与「预览注入内容」按钮')
    ok(layout.width / layout.winW > 0.6, `大抽屉宽度（${layout.width}/${layout.winW} ≈ 70%）`)

    // ── 3. 点预览出结果（不选项目=只看全局注入）──────────────
    await evaluate(ws, `${btnJs('.ant-drawer-body', '预览注入内容')}.click()`)
    await waitFor(() => evaluate(ws, `(() => {
      const d = document.querySelector('.ant-drawer-body');
      return !!d && d.textContent.includes('命中条目') && !!d.querySelector('pre');
    })()`), '预览结果渲染（命中条目 + 内容 pre）')
    ok(true, '点击「预览注入内容」后结果区出现（命中条目 + pre 内容块）')

    // ── 4. 关闭抽屉，提案列表仍在 ────────────────────────────
    await evaluate(ws, `document.querySelector('.ant-drawer-close').click()`)
    await waitFor(async () => !(await evaluate(ws, drawerVisibleJs)), '抽屉关闭')
    ok(await evaluate(ws, `!!document.querySelector('.ant-table')`), '抽屉关闭后提案表格仍在')
  } catch (e) {
    // 失败诊断：落截图 + 打印当前地址与正文片段（多数超时是「没登进去/页面没渲染」）
    try {
      const diag = await evaluate(ws, `location.href + ' | ' + document.body.innerText.slice(0, 200)`)
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
      await sleep(1200) // 等进程死透再删 profile，否则 EPERM
    }
    try {
      rmSync(PROFILE, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
    } catch { /* profile 留在 tmp/ 无碍，下次跑开头还会清 */ }
  })
