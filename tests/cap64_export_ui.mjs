// CAP-64 收藏导出 UI E2E：导出 Chrome（Netscape）书签文件，且能被自家导入解析器往返识别。
// 前置：后端 + 前端 dev 已起（BASE 页面代理到 API）。
// 断言：
//   1) 「导出」按钮触发落盘 devmind-bookmarks-*.html，头部是 NETSCAPE-Bookmark-file-1；
//   2) 分组层级（父>子）、收藏条目（HREF/标题）、特殊字符转义、TAGS 属性、<DD> 备注都在；
//   3) 往返无损：把导出文件喂回 parseBookmarkFile（vite dev 直接 import 源模块），
//      还原出的导入树与页面数据一致（分组/收藏/标签/备注）→ Chrome 认的格式自家也认。
// 用法：node tests/cap64_export_ui.mjs   （BASE/API/CHROME_PATH 可覆盖）
// 运行产物（chrome profile / 下载目录）写 tmp/，不入库。
import { spawn } from 'node:child_process'
import { existsSync, readFileSync, readdirSync, rmSync, mkdirSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:8080'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = 9467
const PROFILE = join(ROOT, 'tmp', 'cap64-export-chrome')
const DOWNLOADS = join(ROOT, 'tmp', 'cap64-export-downloads')
const TS = Date.now().toString(36)
const GP = `导出父${TS}`
const GC = `导出子${TS}`
const T1 = `导出收藏<甲>&"${TS}` // 标题带 HTML 特殊字符，验转义
const T2 = `导出默认组${TS}`
const U1 = `https://example.com/a?x=1&y=%3C2%3E`
const U2 = `https://example.com/b/${TS}`
const NOTE = `备注<敏感>&内容${TS}`
const TAG1 = `标签一${TS}`
const TAG2 = `标签二${TS}`

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

/** 最小 CDP 客户端（与 cap64_group_tree_ui.mjs 同套路） */
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

const cleanup = { groups: [], bookmarks: [], tags: [] }
let chrome
async function main() {
  // ── 0. 登录 + 备数据：父>子分组、组内收藏（标签+备注+特殊字符）、默认分组收藏 ──
  const login = await api('POST', '/auth/login', { username: USER, password: PASS })
  const { accessToken, refreshToken, user } = login
  const tag1 = await api('POST', '/bookmark-tags', { name: TAG1 }, accessToken)
  const tag2 = await api('POST', '/bookmark-tags', { name: TAG2 }, accessToken)
  cleanup.tags.push(tag1.id, tag2.id)
  const gP = await api('POST', '/bookmark-groups', { name: GP }, accessToken)
  const gC = await api('POST', '/bookmark-groups', { name: GC, parentId: gP.id }, accessToken)
  cleanup.groups.push(gC.id, gP.id) // 先子后父，删序安全
  const b1 = await api('POST', '/bookmarks', {
    title: T1, url: U1, description: NOTE, groupId: gC.id, tagIds: [tag1.id, tag2.id],
  }, accessToken)
  const b2 = await api('POST', '/bookmarks', { title: T2, url: U2 }, accessToken) // 默认分组
  cleanup.bookmarks.push(b1.id, b2.id)
  console.log(`[0] 备好数据：${GP} > ${GC} > ${T1}（标签+备注+特殊字符），默认组 ${T2}`)

  // ── 1. 起 headless Chrome，落盘目录指到 tmp/ ──
  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  rmSync(PROFILE, { recursive: true, force: true })
  rmSync(DOWNLOADS, { recursive: true, force: true })
  mkdirSync(DOWNLOADS, { recursive: true })
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
  await ws.send('Page.setDownloadBehavior', { behavior: 'allow', downloadPath: DOWNLOADS })

  try {
    await ws.send('Page.navigate', { url: `${BASE}/bookmarks` })
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      return 'ok';
    })()`)
    await ws.send('Page.navigate', { url: `${BASE}/bookmarks` })
    await waitFor(() => evaluate(ws, `!!document.querySelector('.bm-group-tree')`), '收藏页渲染')

    // ── 2. 点「导出」→ 文件落盘 ──
    await evaluate(ws, `[...document.querySelectorAll('button')].find((b) => b.textContent.replace(/\\s/g, '') === '导出')?.click()`)
    const fileName = await waitFor(async () => {
      const done = readdirSync(DOWNLOADS).filter((f) => !f.endsWith('.crdownload'))
      return done.length > 0 ? done[0] : null
    }, '导出文件落盘')
    ok(/^devmind-bookmarks-\d{8}\.html$/.test(fileName), `导出文件名 ${fileName}`)
    const html = readFileSync(join(DOWNLOADS, fileName), 'utf8')
    ok(html.startsWith('<!DOCTYPE NETSCAPE-Bookmark-file-1>'), 'Netscape 文件头（Chrome 导入认这个格式）')

    // ── 3. 结构断言：层级 / 转义 / TAGS / DD ──
    const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')
    ok(html.includes(`>${esc(GP)}</H3>`), '父分组头在')
    ok(html.includes(`>${esc(GC)}</H3>`), '子分组头在')
    ok(html.indexOf(`>${esc(GP)}</H3>`) < html.indexOf(`>${esc(GC)}</H3>`), '父分组先于子分组（层级序）')
    ok(html.includes(`HREF="${esc(U1)}"`), '收藏地址转义（& < > 进属性）')
    ok(html.includes(`>${esc(T1)}</A>`), '收藏标题转义')
    ok(html.includes(`TAGS="${esc(`${TAG1},${TAG2}`)}"`), 'TAGS 属性带两个标签（导回本平台不丢）')
    ok(html.includes(`<DD>${esc(NOTE)}`), '<DD> 备注在且转义')
    ok(html.includes(`HREF="${esc(U2)}"`), '默认分组收藏在顶层')
    ok(!html.includes('password'), '导出文件不含账号密码字段')

    // ── 4. 往返：导出文件喂回 parseBookmarkFile，树与页面数据一致 ──
    const roundTrip = await evaluate(ws, `(async () => {
      const { parseBookmarkFile, importStats } = await import('/src/features/bookmarks/utils/netscape.ts');
      const nodes = parseBookmarkFile(${JSON.stringify(html)});
      const stats = importStats(nodes);
      const findBm = (ns, url) => {
        for (const n of ns) {
          if (n.type === 'bookmark' && n.url === url) return n;
          const hit = findBm(n.children ?? [], url);
          if (hit) return hit;
        }
        return null;
      };
      const top = nodes.find((n) => n.type === 'folder' && n.name === ${JSON.stringify(GP)});
      const b1 = findBm(nodes, ${JSON.stringify(U1)});
      const b2 = findBm(nodes, ${JSON.stringify(U2)});
      return {
        stats,
        childInParent: !!top?.children?.some((n) => n.type === 'folder' && n.name === ${JSON.stringify(GC)}),
        b1: b1 ? { title: b1.title, description: b1.description ?? null, tags: b1.tags ?? [] } : null,
        b2: b2 ? { title: b2.title, topLevel: nodes.some((n) => n.type === 'bookmark' && n.url === ${JSON.stringify(U2)}) } : null,
      };
    })()`)
    ok(roundTrip.b1?.title === T1, '往返：特殊字符标题原样还原')
    ok(roundTrip.b1?.description === NOTE, '往返：备注原样还原')
    ok(roundTrip.b1?.tags?.includes(TAG1) && roundTrip.b1?.tags?.includes(TAG2), '往返：两个标签都在')
    ok(roundTrip.childInParent, '往返：子分组挂在父分组下')
    ok(roundTrip.b2?.title === T2 && roundTrip.b2?.topLevel, '往返：默认分组收藏落在顶层')
    // 体量对得上服务端全量（用户可能已有别的收藏，只校验下界：至少含本次 2 条 + 2 组）
    ok(roundTrip.stats.bookmarks >= 2 && roundTrip.stats.folders >= 2, `往返：统计体量合理（${roundTrip.stats.folders} 组 / ${roundTrip.stats.bookmarks} 条）`)

    console.log(`\n[结果] ${passed} 项断言全部通过`)
  } finally {
    ws.close()
  }
}

main().finally(async () => {
  chrome?.kill()
  try {
    const login = await api('POST', '/auth/login', { username: USER, password: PASS })
    for (const id of cleanup.bookmarks) await api('DELETE', `/bookmarks/${id}`, undefined, login.accessToken).catch(() => {})
    for (const id of cleanup.groups) await api('DELETE', `/bookmark-groups/${id}?cascade=true`, undefined, login.accessToken).catch(() => {})
    for (const id of cleanup.tags) await api('DELETE', `/bookmark-tags/${id}`, undefined, login.accessToken).catch(() => {})
  } catch { /* 实例已停 */ }
  console.log('[清理] 测试数据已删除')
}).catch((e) => {
  console.error(e.stack ?? e)
  process.exit(1)
})
