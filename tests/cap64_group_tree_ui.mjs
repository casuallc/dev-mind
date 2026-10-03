// CAP-64 分组树交互 UI E2E（M4：折叠记忆 + 拖拽改父分组）。
// 前置：后端 + 前端 dev 已起（BASE 页面代理到 API）。
// 断言：
//   1) 分组树用 antd Tree 渲染（有折叠箭头），默认全展开；
//   2) 点箭头折叠父分组 → 子分组行消失，折叠集写 localStorage；刷新页面后仍折叠（记忆生效）；
//   3) 真鼠标拖拽子分组到目标分组上 → 服务端 parentId 变更（allowDrop/落点换算生效）；
//   4) 把父分组拖进自己的子树 → allowDrop 拒绝，parentId 不变（前端防线；后端 400 由 cap64_e2e.py 覆盖）。
// 用法：node tests/cap64_group_tree_ui.mjs   （BASE/API/CHROME_PATH 可覆盖）
// 运行产物（chrome profile）写 tmp/，不入库。
import { spawn } from 'node:child_process'
import { existsSync, rmSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:8080'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9466
const PROFILE = join(ROOT, 'tmp', 'cap64-ui-chrome')
const TS = Date.now().toString(36)
const P = `ui树父${TS}`
const C = `ui树子${TS}`
const T = `ui树目标${TS}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let passed = 0
function ok(cond, what) {
  if (!cond) throw new Error(`断言失败：${what}`)
  passed++
  console.log(`  ✔ ${what}`)
}

async function api(method, path, body, token) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`)
  return text ? JSON.parse(text) : null
}

/** 最小 CDP 客户端（Node 内置 WebSocket，与 e2e-layout-pages.mjs 同套路） */
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

/** 找标题含 name 的 Tree 节点（rc-tree 平铺渲染，取 .ant-tree-title 匹配防父子串名互相误中） */
const nodeJs = (name) => `[...document.querySelectorAll('.bm-group-tree .ant-tree-treenode')]
  .find(n => n.querySelector('.ant-tree-title')?.textContent.includes(${JSON.stringify(name)}))`

async function nodeRect(ws, name) {
  return evaluate(ws, `(() => {
    const n = ${nodeJs(name)};
    if (!n) return null;
    const r = n.querySelector('.ant-tree-node-content-wrapper').getBoundingClientRect();
    return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
  })()`)
}

/** 拖拽一个分组到另一个分组中央（落进去换父）。
 * rc-tree 5.x 用 HTML5 drag 事件（dragstart/dragenter/drop），Input.dispatchMouseEvent
 * 的鼠标管线不产生 dragstart，必须合成 DragEvent；且 dragenter/drop 之间要留拍，
 * 等 rc-tree 把 dragenter 里算出的 dropPosition 经 setState 落进 state，drop 才会被受理。
 * 元素间共享 dataTransfer/节点引用，挂 window 跨 evaluate 传递。
 * 落点判定（rc-tree util calcDropPosition）：纵坐标在目标行下半格即可；「落进去」
 * （dropPosition=0）要求横移量 (起点x − 落点x − 12)/indent ≤ −1.5，即往右拖约 48px 以上
 * ——所以 dragstart 横坐标钉 0、落点取目标行右缘，保证算成 inside 而不是 gap-bottom。
 * 纵坐标必须落在目标行**下半格且留余量**（0.75 处）：calcDropPosition 以
 * `clientY < top + height/2` 判上半格，取整后的正中坐标会因果赛克亚像素落进上半格，
 * 上半格把抽象落点前移一行——恰好是拖拽源自己 → 自我落点 resetDragState，偶发不生效。 */
async function drag(ws, fromName, toName) {
  const setup = await evaluate(ws, `(() => {
    const find = (name) => [...document.querySelectorAll('.bm-group-tree .ant-tree-treenode')]
      .find(n => n.querySelector('.ant-tree-title')?.textContent.includes(name));
    window.__dndSrc = find(${JSON.stringify(fromName)});
    window.__dndTgt = find(${JSON.stringify(toName)});
    if (!window.__dndSrc || !window.__dndTgt) return false;
    const r = window.__dndTgt.getBoundingClientRect();
    window.__dndAt = { clientX: Math.round(r.right - 8), clientY: Math.round(r.y + r.height * 0.75) };
    window.__dndDt = new DataTransfer();
    window.__dndSrc.dispatchEvent(new DragEvent('dragstart', {
      bubbles: true, cancelable: true, dataTransfer: window.__dndDt,
      clientX: 0, clientY: window.__dndAt.clientY,
    }));
    return true;
  })()`)
  if (!setup) throw new Error(`拖拽起点/终点找不到：${fromName} → ${toName}`)
  await sleep(300)
  await evaluate(ws, `window.__dndTgt.dispatchEvent(new DragEvent('dragenter', {
    bubbles: true, cancelable: true, dataTransfer: window.__dndDt, ...window.__dndAt }))`)
  await sleep(300)
  await evaluate(ws, `window.__dndTgt.dispatchEvent(new DragEvent('dragover', {
    bubbles: true, cancelable: true, dataTransfer: window.__dndDt, ...window.__dndAt }))`)
  await sleep(300)
  await evaluate(ws, `window.__dndTgt.dispatchEvent(new DragEvent('drop', {
    bubbles: true, cancelable: true, dataTransfer: window.__dndDt, ...window.__dndAt }))`)
  await sleep(200)
  await evaluate(ws, `window.__dndSrc.dispatchEvent(new DragEvent('dragend', {
    bubbles: true, cancelable: true, dataTransfer: window.__dndDt, ...window.__dndAt }))`)
}

const createdGroups = []
let chrome
async function main() {
  // ── 0. 登录 + 备数据 ─────────────────────────────────────
  const login = await api('POST', '/auth/login', { username: USER, password: PASS })
  const { accessToken, refreshToken, user } = login
  const gP = await api('POST', '/bookmark-groups', { name: P }, accessToken)
  const gC = await api('POST', '/bookmark-groups', { name: C, parentId: gP.id }, accessToken)
  const gT = await api('POST', '/bookmark-groups', { name: T }, accessToken)
  createdGroups.push(gC.id, gT.id, gP.id) // 先子后父，删序安全
  console.log(`[0] 备好分组：${P} > ${C}，${T}`)

  // ── 1. 起 headless Chrome ────────────────────────────────
  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  rmSync(PROFILE, { recursive: true, force: true })
  chrome = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1366,768', 'about:blank',
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
    await ws.send('Page.navigate', { url: `${BASE}/bookmarks` })
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      return 'ok';
    })()`)
    await ws.send('Page.navigate', { url: `${BASE}/bookmarks` })
    await waitFor(() => evaluate(ws, `!!document.querySelector('.bm-group-tree')`), '分组树渲染')
    await waitFor(() => nodeRect(ws, C), '子分组行出现（默认全展开）')
    ok(true, '分组树 antd Tree 渲染，默认全展开（子分组可见）')
    ok(await evaluate(ws, `(() => {
      const n = ${nodeJs(P)};
      return !!n?.querySelector('.ant-tree-switcher:not(.ant-tree-switcher-noop)');
    })()`), '父分组带折叠箭头')

    // ── 2. 折叠 + 记忆 ────────────────────────────────────
    await evaluate(ws, `${nodeJs(P)}.querySelector('.ant-tree-switcher').click()`)
    await waitFor(async () => !(await nodeRect(ws, C)), '折叠后子分组行移除')
    ok(true, '点箭头折叠父分组，子分组行消失')
    const collapsedStored = await evaluate(ws, `localStorage.getItem('bookmark.groupTree.collapsed')`)
    ok(collapsedStored?.includes(String(gP.id)), '折叠集写 localStorage（bookmark.groupTree.collapsed）')

    await evaluate(ws, `window.__navMark = 'before-reload'`)
    await ws.send('Page.navigate', { url: `${BASE}/bookmarks` })
    // 等真正换文档：旧 window 的标记消失才算刷新落地，否则读到的是旧 DOM
    await waitFor(async () => {
      const m = await evaluate(ws, `window.__navMark ?? null`).catch(() => 'pending')
      return m !== 'before-reload' && m !== 'pending' ? true : null
    }, '页面刷新落地')
    await waitFor(() => evaluate(ws, `!!document.querySelector('.bm-group-tree')`), '刷新后分组树渲染')
    await sleep(800)
    ok(!(await nodeRect(ws, C)), '刷新后仍折叠（本地记忆生效）')
    ok(!!(await nodeRect(ws, P)), '刷新后父分组仍在')

    // 展开还原，进入拖拽用例
    await evaluate(ws, `${nodeJs(P)}.querySelector('.ant-tree-switcher').click()`)
    await waitFor(() => nodeRect(ws, C), '重新展开子分组行')
    // 展开是滑动动画：行出现≠到位，等目标行 rect 连续两拍不变（动画落定）再量坐标，
    // 否则拖拽起点量的是动画中途的位置，dragenter 时行已下移 → 纵坐标落进上半格误判
    await waitFor(async () => {
      const a = await nodeRect(ws, T)
      await sleep(200)
      const b = await nodeRect(ws, T)
      return a && b && a.y === b.y ? b : null
    }, '展开动画落定（目标行位置稳定）')

    // ── 3. 拖拽改父 ───────────────────────────────────────
    await drag(ws, C, T)
    await waitFor(async () => {
      const tree = await api('GET', '/bookmark-groups', undefined, accessToken)
      const t = tree.find((x) => x.id === gT.id)
      return t?.children?.some((x) => x.id === gC.id) ? t : null
    }, '拖拽落库（子分组挂到目标分组下）')
    ok(true, '拖拽子分组到目标分组上 → 服务端 parentId 变更')
    await waitFor(() => nodeRect(ws, C), '拖拽后子分组在新父级下可见（自动展开）')
    ok(true, '拖进收起/新父级后节点立即可见')

    // ── 4. 子树防线：把 T 拖进自己的孩子 C → 拒绝 ─────────
    await drag(ws, T, C)
    await sleep(800)
    const tree2 = await api('GET', '/bookmark-groups', undefined, accessToken)
    ok(tree2.some((x) => x.id === gT.id && (x.parentId == null)), '拖父分组进自己的子树被 allowDrop 拒绝（parentId 不变）')

    console.log(`\n[结果] ${passed} 项断言全部通过`)
  } finally {
    ws.close()
  }
}

main().finally(async () => {
  chrome?.kill()
  for (const id of createdGroups) {
    try {
      const login = await api('POST', '/auth/login', { username: USER, password: PASS })
      await api('DELETE', `/bookmark-groups/${id}?cascade=true`, undefined, login.accessToken)
    } catch { /* 已删或实例已停 */ }
  }
  console.log('[清理] 测试分组已删除')
}).catch((e) => {
  console.error(e.message ?? e)
  process.exit(1)
})
