// 新问答/新会话草稿「高级选项」真实链路探针：把 NewChatDraft / NewSessionDraft 直接渲染起来
// （fetch 打桩，不动后端），用真鼠标走一遍用户姿势——开高级选项 → 选字段 → 点输入框（弹层关闭）
// → 输入 → 发送，断言 POST /api/chats、/api/sessions 的请求体里选过的选项都还在。
//
// 钉死的坑（两份草稿同源）：高级选项的 Form 住在 destroyOnHidden 的 Popover 里，弹层一关字段就
// 卸载，而「默认口径」的取值/监听只认已注册字段——
//   1) form.getFieldsValue()（无参）把执行体等选项静默丢光，表现就是「选了模型执行体，建的还是
//      runner 问答」（无值即回落后端默认）→ 取值必须 getFieldsValue(true)（取整个 store）；
//   2) Form.useWatch('requirementId', form) 把「字段卸载」读成「需求被清空」，联动 effect 跟着
//      误清刚选好的工作单元，表现就是「选了工作单元，建会话时丢了」→ 必须带 preserve:true。
// 本探针即上述两条的回归网。
//
// 前置：`cd frontend && npm install` 已装依赖（esbuild 从 frontend/node_modules 取）。
// 用法：node tests/e2e-draft-options-probe.mjs   （无需后端/runner，Chrome 起在无头模式）
// 运行产物（探针工程、bundle、chrome profile）写 tmp/draft-probe/，不入库。
// 打包时会在 tmp/node_modules 建一个指向 frontend/node_modules 的 junction（esbuild CLI 没有
// node-paths 参数，入口在 tmp 下就只能靠目录向上找依赖）；缺了会自动重建，不必手工维护。
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const FRONTEND = join(ROOT, 'frontend')
const TMP = join(ROOT, 'tmp', 'draft-probe')
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const PORT = Number(process.env.PROBE_PORT ?? 9356)
const HTTP_PORT = Number(process.env.PROBE_HTTP_PORT ?? 5199)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 起完的资源登记在此：main 抛错时也要收干净，否则 http/chrome 句柄吊着进程不退出 */
const cleanups = []
const onCleanup = (fn) => cleanups.push(fn)
const runCleanups = () => {
  for (const fn of cleanups.reverse()) {
    try {
      fn()
    } catch {
      /* 忽略 */
    }
  }
}

const EP = { id: 42, kind: 'CHAT', name: '探针对话端点', provider: 'mock', model: 'probe-chat-model' }
const KB = { id: 7, name: '探针知识库', status: 'active', injectMode: 'auto', entryCount: 3 }

const MAIN_TSX = `
import '@ant-design/v5-patch-for-react-19'
import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import NewChatDraft from '../../frontend/src/features/chat/components/NewChatDraft'
import NewSessionDraft from '../../frontend/src/features/sessions/components/NewSessionDraft'

const endpoints = [{
  id: ${EP.id}, kind: 'CHAT', name: '${EP.name}', provider: 'mock', model: '${EP.model}',
  hasApiKey: false, timeoutSeconds: 60, batchSize: 8, status: 'active', isDefault: true,
  createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00',
}]
const nodes = [{ id: 1, name: 'probe-node', status: 'ONLINE', os: 'linux', isDefault: true }]
const bases = [{
  id: ${KB.id}, name: '${KB.name}', description: '', status: 'active', injectMode: 'auto',
  entryCount: 3, createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00',
}]
const project = { id: 'p1', name: '探针项目', key: 'PROBE', kind: 'DEV', status: 'ACTIVE',
  createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00' }
const repos = [
  { id: 1, projectId: 'p1', name: 'probe-primary', url: 'git@x:probe-primary.git', primary: true },
  { id: 2, projectId: 'p1', name: 'probe-extra', url: 'git@x:probe-extra.git', primary: false },
]
const requirements = { items: [{ id: 'p1r1', code: 'REQ-1', projectId: 'p1', title: '探针需求一', status: 'DEVELOPING',
  priority: 'P1', createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00' }], total: 1 }
const workItems = [{ id: 'p1w1', code: 'WI-1', projectId: 'p1', requirementId: 'p1r1', title: '探针工作单元一',
  status: 'TODO', createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00' }]

// fetch 打桩：只放行被测路径，其它一律空数组；POST /chats 与 /sessions 记下请求体供断言
const payloads: any[] = []
;(window as any).__payloads = payloads
;(window as any).__signals = { created: null }
const fetchLog: string[] = []
;(window as any).__fetchLog = fetchLog
window.fetch = (async (input: any, init: any = {}) => {
  const url = String(input)
  const method = (init.method ?? 'GET').toUpperCase()
  fetchLog.push(method + ' ' + url)
  const json = (v: unknown, status = 200) =>
    new Response(JSON.stringify(v), { status, headers: { 'Content-Type': 'application/json' } })
  if (url.includes('/api/model-endpoints')) return json(endpoints)
  if (url.includes('/api/agent-nodes')) return json(nodes)
  if (url.includes('/api/scenarios')) return json([])
  if (url.includes('/api/knowledge/bases')) return json(bases)
  if (url.includes('/work-items')) return json(workItems)
  if (url.includes('/requirements')) return json(requirements)
  if (url.includes('/repos')) return json(repos)
  if (url.match(/\\/api\\/projects\\/[^/]+$/)) return json(project)
  if (url.includes('/api/chats') && method === 'POST') {
    payloads.push(JSON.parse(init.body))
    return json({
      id: 'probechat1', title: '探针问答', status: 'RUNNING', state: 'RUNNING',
      executor: 'MODEL', modelEndpointId: ${EP.id}, agentNodeId: null,
      createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00',
    })
  }
  if (url.includes('/api/sessions') && method === 'POST') {
    payloads.push(JSON.parse(init.body))
    return json({
      id: 'probesess1', title: '探针会话', status: 'RUNNING', state: 'RUNNING', projectId: 'p1',
      createdAt: '2026-09-21 10:00:00', updatedAt: '2026-09-21 10:00:00',
    })
  }
  return json([])
}) as any

function Probe() {
  const [done, setDone] = useState('')
  const onCreated = (id: string) => { (window as any).__signals.created = id; setDone(id) }
  const which = new URLSearchParams(location.search).get('draft')
  return (
    <div>
      {which === 'session' ? (
        <NewSessionDraft projectId="p1" onCreated={(s: any) => onCreated(s.id)} />
      ) : (
        <NewChatDraft presetKbId={${KB.id}} onCreated={(c: any) => onCreated(c.id)} />
      )}
      <pre id="probe-state">{done}</pre>
    </div>
  )
}
createRoot(document.getElementById('root')!).render(<Probe />)
`

const INDEX_HTML = `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><title>draft-probe</title></head>
<body><div id="root"></div><script src="./bundle.js"></script></body></html>`

// ---------------------------------------------------------------- CDP 客户端
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
    ready: new Promise((res, rej) => {
      ws.addEventListener('open', res)
      ws.addEventListener('error', () => rej(new Error('CDP 连接失败')))
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

async function main() {
  // 1) 探针工程 + 打包（node_modules 走 junction：探针在 tmp/ 下也能解析 react/antd）
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(TMP, { recursive: true })
  writeFileSync(join(TMP, 'main.tsx'), MAIN_TSX, 'utf8')
  writeFileSync(join(TMP, 'index.html'), INDEX_HTML, 'utf8')
  const junction = join(ROOT, 'tmp', 'node_modules')
  if (!existsSync(junction)) {
    spawnSync('cmd', ['/c', 'mklink', '/J', junction, join(FRONTEND, 'node_modules')], { stdio: 'ignore' })
  }
  const esbuild = join(FRONTEND, 'node_modules', '@esbuild', 'win32-x64', 'esbuild.exe')
  const built = spawnSync(
    esbuild,
    [join(TMP, 'main.tsx'), '--bundle', `--outfile=${join(TMP, 'bundle.js')}`, '--loader:.tsx=tsx',
      '--jsx=automatic', '--define:process.env.NODE_ENV="development"', '--log-level=warning'],
    { cwd: ROOT, encoding: 'utf8' },
  )
  if (built.status !== 0) throw new Error('esbuild 打包失败: ' + (built.stderr || built.stdout))

  // 2) 静态服务（file:// 下 portal/localStorage 姿势不真，走 http）
  const server = createServer((req, res) => {
    const path = (req.url ?? '/').split('?')[0]
    const file = join(TMP, path === '/' ? 'index.html' : path.replace(/^\/+/, ''))
    if (!file.startsWith(TMP) || !existsSync(file)) {
      res.writeHead(404).end('not found')
      return
    }
    res.writeHead(200, { 'Content-Type': file.endsWith('.js') ? 'text/javascript' : 'text/html' })
    res.end(readFileSync(file))
  })
  await new Promise((r) => server.listen(HTTP_PORT, '127.0.0.1', r))
  onCleanup(() => server.close())

  // 3) 无头 Chrome + CDP
  const profile = join(TMP, 'chrome-profile')
  const chrome = spawn(
    CHROME,
    ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
      `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, '--window-size=1400,1000', 'about:blank'],
    { stdio: 'ignore' },
  )
  onCleanup(() => chrome.kill())
  let target = null
  for (let i = 0; i < 40 && !target; i++) {
    await sleep(250)
    try {
      target = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page')
    } catch { /* 还没起来 */ }
  }
  if (!target) throw new Error('Chrome 调试端口未就绪')

  const c = cdp(target.webSocketDebuggerUrl)
  onCleanup(() => c.close())
  await c.ready
  await c.send('Page.enable')
  await c.send('Runtime.enable')
  const evalJs = async (expr) =>
    (await c.send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true })).result?.value

  /** 按表达式找到元素，取其中心真点一下（antd Select 靠 mousedown 展开） */
  const click = async (finder, what) => {
    // 先滚进视口：窗口只有 900 高，会话草稿的高级选项比问答草稿长，靠下的字段
    // （工作单元就在 y≈896）会落在可视区外——elementFromPoint 命中 null，点了等于没点
    const found = await evalJs(`(() => {
      const el = ${finder}
      if (!el) return false
      el.scrollIntoView({ block: 'center', inline: 'center' })
      return true
    })()`)
    if (!found) throw new Error(`找不到可点的元素：${what}`)
    await sleep(200) // 等滚动落定再取坐标
    const box = await evalJs(`(() => {
      const el = ${finder}
      if (!el) return null
      const r = el.getBoundingClientRect()
      return { x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height }
    })()`)
    if (!box) throw new Error(`找不到可点的元素：${what}`)
    if (box.w <= 0 || box.h <= 0) throw new Error(`元素不可见（可能弹层还没开）：${what}`)
    console.log('   · 点击', what)
    const args = { x: box.x, y: box.y, button: 'left', clickCount: 1, buttons: 1 }
    await c.send('Input.dispatchMouseEvent', { type: 'mouseMoved', ...args })
    await c.send('Input.dispatchMouseEvent', { type: 'mousePressed', ...args })
    await c.send('Input.dispatchMouseEvent', { type: 'mouseReleased', ...args })
    await sleep(350)
  }
  const byText = (sel, text) =>
    `[...document.querySelectorAll(${JSON.stringify(sel)})].find((e) => (e.textContent || '').includes(${JSON.stringify(text)}))`
  /** 弹层里某个 Form.Item（按 label 文字认）里的下拉选择器 */
  const selectOf = (label) =>
    `[...document.querySelectorAll('.ant-popover .ant-form-item')].find((f) => ` +
    `(f.querySelector('.ant-form-item-label')?.textContent || '').includes(${JSON.stringify(label)}))?.querySelector('.ant-select')`
  /** 打开某个 Form.Item 的下拉并点中指定文字的那一项 */
  const pickOption = async (label, optionText) => {
    await click(selectOf(label), `${label} 选择器`)
    const options = await evalJs(
      `JSON.stringify([...document.querySelectorAll('.ant-select-item-option-content')].map((e) => e.textContent))`,
    )
    console.log(`   · 打开 ${label} 后下拉里有：${options}（弹层数 ${await evalJs(`document.querySelectorAll('.ant-select-dropdown').length`)}）`)
    await click(byText('.ant-select-item-option-content', optionText), `${label}=${optionText} 选项`)
    await sleep(300)
    const picked = await evalJs(`${selectOf(label)}?.textContent ?? '(取不到)'`)
    console.log(`   · ${label} 现值 = ${picked}`)
  }
  /**
   * 等高级选项弹层真的消失。注意：下拉选项是 portal 到 body 的，点选项在 rc-trigger 眼里
   * 就是"点了外面"——每选一项弹层都会自己关掉（且要等退场动画走完才从 DOM 摘掉）。
   */
  const waitOptionsClosed = async () => {
    for (let i = 0; i < 24; i++) {
      if (!(await evalJs(`!!document.querySelector('.ant-popover')`))) return
      await sleep(150)
    }
  }
  /** 确保高级选项弹层是"刚点开"的状态：先关干净（点输入框 = 点外面）再点开 */
  const openOptions = async () => {
    if (await evalJs(`!!document.querySelector('.ant-popover')`)) {
      await click(`document.querySelector('textarea')`, '输入框（先关掉弹层）')
    }
    await waitOptionsClosed()
    await click(byText('button', '高级选项'), '高级选项按钮')
  }
  const reset = async (draft) => {
    await c.send('Page.navigate', { url: `http://127.0.0.1:${HTTP_PORT}/?draft=${draft}` })
    await sleep(2500)
    return evalJs('JSON.stringify(window.__payloads)') // 页面重载后清空
  }

  let failed = 0
  const check = (name, ok) => {
    console.log(`${ok ? '  ✓' : '  ✗'} ${name}`)
    if (!ok) failed++
  }

  // ---------------- 阶段 A：新问答草稿（执行体=模型） ----------------
  console.log('【新问答草稿】')
  await reset('chat')
  await click(byText('button', '高级选项'), '高级选项按钮')
  const popoverOpen = await evalJs(`!!document.querySelector('.ant-popover')`)
  await pickOption('执行体', '模型')
  await openOptions()
  const modelPicked = await evalJs(`document.querySelector('.ant-popover')?.innerText.includes('对话端点') === true`)
  const chatRetained = await evalJs(
    `JSON.stringify([...document.querySelectorAll('.ant-popover .ant-select-selection-item')].map((e) => e.textContent))`,
  )
  await click(`document.querySelector('textarea')`, '问题输入框（关闭弹层）')
  const popoverGone = await evalJs(`!document.querySelector('.ant-popover')`)
  await c.send('Input.insertText', { text: '探针提问：你好' })
  await sleep(200)
  await click(byText('button', '发送'), '发送按钮')
  await sleep(800)
  const chatList = JSON.parse((await evalJs('JSON.stringify(window.__payloads)')) ?? '[]')
  const chatBody = chatList[0] ?? {}
  const chatCreated = await evalJs('window.__signals.created')
  check('高级选项弹层已打开', popoverOpen === true)
  check(`执行体可选到「模型」（重开弹层后仍是模型：${chatRetained}）`, modelPicked === true)
  check('点输入框后弹层关闭、字段已卸载', popoverGone === true)
  check('确实发出了 POST /api/chats', chatList.length === 1)
  check('请求体带 executor=MODEL（回归点）', chatBody.executor === 'MODEL')
  check(`请求体带 modelEndpointId=${EP.id}`, chatBody.modelEndpointId === EP.id)
  check(`请求体带 knowledgeBaseId=${KB.id}（预选库没丢）`, chatBody.knowledgeBaseId === KB.id)
  check('模型执行体不带 agentNodeId', chatBody.agentNodeId === undefined)
  check('模型执行体不带 permissionMode', chatBody.permissionMode === undefined)
  check('创建回执已交给面板', chatCreated === 'probechat1')
  console.log('  请求体:', JSON.stringify(chatBody))

  // ---------------- 阶段 B：新会话草稿（需求/工作单元/仓库） ----------------
  console.log('\n【新会话草稿】')
  await reset('session')
  await click(byText('button', '高级选项'), '高级选项按钮')
  await pickOption('需求', '探针需求一')
  await sleep(600) // 需求变化后拉工作单元
  await openOptions()
  await pickOption('工作单元', '探针工作单元一')
  await openOptions()
  // 弹层重开后已选项必须还在（值活在 store 里，UI 不该"看着像没选"）
  const retained = await evalJs(
    `JSON.stringify([...document.querySelectorAll('.ant-popover .ant-select-selection-item')].map((e) => e.textContent))`,
  )
  console.log('   · 重开高级选项后已选项:', retained)
  await click(`document.querySelector('textarea')`, '任务输入框（关闭弹层）')
  await c.send('Input.insertText', { text: '探针会话任务' })
  await sleep(200)
  await click(byText('button', '发送'), '发送按钮')
  await sleep(800)
  const sessList = JSON.parse((await evalJs('JSON.stringify(window.__payloads)')) ?? '[]')
  const sessBody = sessList[0] ?? {}
  const sessCreated = await evalJs('window.__signals.created')
  check('确实发出了 POST /api/sessions', sessList.length === 1)
  check('重开弹层后需求/工作单元仍在（store 保留）', /REQ-1/.test(retained) && /WI-1/.test(retained))
  check('请求体带 requirementId=p1r1（回归点）', sessBody.requirementId === 'p1r1')
  check('请求体带 workItemId=p1w1', sessBody.workItemId === 'p1w1')
  check('请求体带 repoIds=[1]（主库默认没丢）', JSON.stringify(sessBody.repoIds) === '[1]')
  check('请求体带 projectId=p1', sessBody.projectId === 'p1')
  check('创建回执已交给面板', sessCreated === 'probesess1')
  console.log('  请求体:', JSON.stringify(sessBody))

  runCleanups()

  if (failed) {
    console.error(`\n${failed} 项未通过`)
    process.exitCode = 1
  } else {
    console.log('\n全部通过')
  }
}

main()
  .catch((e) => {
    console.error('探针失败:', e.message)
    process.exitCode = 1
  })
  .finally(runCleanups)
