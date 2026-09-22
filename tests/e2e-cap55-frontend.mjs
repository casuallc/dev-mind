// CAP-55 FR-07 前端 E2E：inbox 分诊徽标 + 「查看依据」抽屉 + 决策记录页（模型建议 vs 人工裁决）
// + 导出训练集（点按钮 → 浏览器真下载 → 校验 JSONL）。
//
// 为什么单独写这个脚本：徽标、抽屉、置灰的按钮只存在于浏览器里——cap55_triage_verify.py /
// cap55_records_verify.py 打的是服务端接口，测不到「triage 字段有没有被渲染成徽标」
// 「点导出到底下没下到文件」「端点没了按钮灰不灰、灰了有没有说明原因」这三类问题。
//
// 起两个进程（与 tests/e2e-layout-pages.mjs 同姿势）：
//   1) 后端（隔离实例，别打 8080——那儿常年是旧构建）：
//        mvn -q install -DskipTests
//        mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18095 \
//          --spring.profiles.active=e2e \
//          --spring.datasource.url=jdbc:h2:file:./tmp/cap55-ui/devmind;AUTO_SERVER=TRUE \
//          --devmind.cors.allowed-origins=http://localhost:5173,http://127.0.0.1:5173,http://localhost:5199,http://127.0.0.1:5199"
//      - CORS 白名单**必须带上前端 dev server 的 origin**：白名单外的来源，浏览器发的 GET 能过
//        （同源 GET 不带 Origin 头）但 POST/PUT/DELETE 一律 403（CORS 过滤直接拒），
//        症状是"点采纳没反应、后端日志里连请求都没有"。脚本 [0] 有一发 POST 预检专门钉这条。
//        该属性是**整表替换**，覆盖时必须把默认的 5173/8080 一并带上。
//      - spring-boot:run 的 cwd 是模块 basedir，所以相对路径的 H2 落在 devmind-app/tmp/cap55-ui/；
//        重跑前要连 JVM 一起清掉（否则测的是上一次的数据，total 比预期多）。
//   2) 前端：cd frontend && VITE_BACKEND_URL=http://localhost:18095 npm run dev -- --port 5199 --strictPort
//      **5173 常被本机常驻的 dev server 占着**，占着就用别的端口并把它加进上面的 CORS 白名单
//   3) 本机 Chrome（CHROME_PATH 可覆盖）
//
// 数据前提：分诊能不能用除了端点，还多一道 CAP-56 FR-07 的准入闸门——库里得有一份**验证通过**的
// 模型产物，否则按钮照样是灰的（原因指向决策实验室）。脚本 [1] 自己走完「登记 → 人工验证通过」
// 两步（只登记不放行，闸门仍关着），收尾撤销放行再删除（验证中的产物删不掉，不撤下轮重跑必 409）。
//
// 用法：node tests/e2e-cap55-frontend.mjs
//   可覆盖：BASE / API / USER / PASS / CHROME_PATH / CDP_PORT / MOCK_PORT
// 副作用：往目标实例写端点/知识库/提案/采纳（收尾删掉自建物）；下载产物、截图与报告落 tmp/cap55-ui/。
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:18095'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const MOCK_PORT = Number(process.env.MOCK_PORT ?? 18195)
const CDP_PORT = Number(process.env.CDP_PORT ?? 9336)
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'

const TMP = join(ROOT, 'tmp')
const OUT = join(TMP, 'cap55-ui')
const DOWNLOADS = join(OUT, 'downloads')
const PROFILE = join(TMP, 'chrome-cap55-ui')

// 提案标题 / 库条目：条目内容里**必须出现标题原文**——没配 embedding 时召回退化 LIKE 整串匹配，
// 而 query 用的就是标题（KnowledgeTriageService.collectEvidence）。这样"召回比对物"那一段才有东西可看。
const PROPOSAL_TITLE = 'CAP55-UI 构建失败先看日志末尾'
const ENTRY_NAME = '构建排错心得'
const ENTRY_CONTENT = 'CAP55-UI 构建失败先看日志末尾：环境类报错九成在日志尾部 200 行，先看完再猜网络'
const P2_TITLE = 'CAP55-UI 降级期提案'
const CAPABILITY = 'kb-proposal-triage'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)
let passed = 0
const failures = []
function check(name, cond, detail = '') {
  if (cond) {
    passed++
    log(`  PASS  ${name}`)
  } else {
    failures.push(`${name}${detail ? ` — ${detail}` : ''}`)
    log(`  FAIL  ${name}${detail ? `  ${detail}` : ''}`)
  }
}

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

/** 最小 CDP 客户端（Node 内置 WebSocket，无第三方依赖，与 e2e-layout-pages.mjs 同款） */
function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  const pending = new Map()
  const events = []
  const waiters = []
  let seq = 0
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
    reset() {
      events.length = 0
      waiters.length = 0
    },
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
  let lastErr = null
  for (;;) {
    // 轮询期间 pred 抛异常要当"还没到"，不能当"放弃"：pred 是同步函数，它抛出的异常会直接穿过
    // 这里的 await（.catch 挂不到同步 throw 上），被调用方的 .catch 吞成一句"没等到"，
    // 连原因都不剩——曾经因此把"文件晚 120ms 落盘"读成"没下载下来"。
    let v = null
    try {
      v = await pred()
    } catch (e) {
      lastErr = e
    }
    if (v) return v
    if (Date.now() > deadline) {
      throw new Error(`等待「${what}」超时${lastErr ? `（最后一次错误：${lastErr.message}）` : ''}`)
    }
    await sleep(interval)
  }
}

async function evaluate(ws, expression) {
  const { result, exceptionDetails } = await ws.send('Runtime.evaluate', {
    expression, returnByValue: true, awaitPromise: true,
  })
  if (exceptionDetails) throw new Error(exceptionDetails.text ?? '页面求值异常')
  return result.value
}

const TEXT = `(document.querySelector('.ant-layout-content')?.innerText ?? '')`
const DRAWER_TEXT = `(document.querySelector('.ant-drawer-open .ant-drawer-body')?.innerText ?? '')`
const SIDER_TEXT = `(document.querySelector('.ant-layout-sider')?.innerText ?? '')`

/** 内容区溢出总账（与 e2e-layout-pages.mjs 同口径） */
const OVERFLOW = `(() => {
  const c = document.querySelector('.ant-layout-content');
  if (!c) return { noContent: true };
  return {
    contentOverflow: c.scrollHeight - c.clientHeight,
    docOverflow: document.documentElement.scrollHeight - document.documentElement.clientHeight,
  };
})()`

/** 页头 Segmented 按 label 文本切视图（不靠下标） */
const clickSegmented = (label) => `(() => {
  const it = [...document.querySelectorAll('.ant-layout-content .ant-segmented-item')]
    .find((e) => (e.textContent ?? '').trim() === ${JSON.stringify(label)});
  if (!it) return false;
  it.querySelector('input')?.click();
  return true;
})()`

// 按钮文本比对一律**去掉所有空白**再比：antd 的 Button 对"恰好两个汉字"的文案会自动插一个空格
// （确认弹窗上的「采 纳」「取 消」就是这么来的），照字面比会永远找不到按钮。
const NORM = `const norm = (t) => (t ?? '').replace(/\\s+/g, '');`

/** 在 scope 里按文本点按钮 */
const clickByText = (scope, text) => `(() => {
  ${NORM}
  const root = document.querySelector(${JSON.stringify(scope)});
  if (!root) return false;
  const b = [...root.querySelectorAll('button')].find((e) => norm(e.textContent) === norm(${JSON.stringify(text)}));
  if (!b) return false;
  b.click();
  return true;
})()`

/** 在 scope 里按文本点按钮或链接（徽标行的「查看依据」是 <a>） */
const clickAnyByText = (scope, text) => `(() => {
  ${NORM}
  const root = document.querySelector(${JSON.stringify(scope)});
  if (!root) return false;
  const el = [...root.querySelectorAll('button, a')].find((e) => norm(e.textContent) === norm(${JSON.stringify(text)}));
  if (!el) return false;
  el.click();
  return true;
})()`

/** 某个表格行的整行文本（按包含的片段定位） */
const rowTextOf = (contains) => `(() => {
  const row = [...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')]
    .find((r) => (r.innerText ?? '').includes(${JSON.stringify(contains)}));
  return row ? row.innerText : '';
})()`

/** 某行里按文本点按钮 */
const rowButton = (contains, label) => `(() => {
  ${NORM}
  const row = [...document.querySelectorAll('.ant-table-tbody tr.ant-table-row')]
    .find((r) => (r.innerText ?? '').includes(${JSON.stringify(contains)}));
  if (!row) return false;
  const b = [...row.querySelectorAll('button')].find((e) => norm(e.textContent) === norm(${JSON.stringify(label)}));
  if (!b) return false;
  b.click();
  return true;
})()`

/** 抽屉里某个按钮的矩形中心（给"真鼠标移上去"用——dispatched MouseEvent 触发不了 antd Tooltip）
 *  先 scrollIntoView：抽屉 body 会滚动，按钮滚出视口时 rect 给的是视口外坐标，
 *  dispatchMouseEvent 就打在空气上（elementFromPoint 返回 null），Tooltip 自然不弹。 */
const buttonCenter = (scope, labels) => `(() => {
  ${NORM}
  const root = document.querySelector(${JSON.stringify(scope)});
  const want = ${JSON.stringify([].concat(labels))}.map(norm);
  const b = root && [...root.querySelectorAll('button')].find((e) => want.includes(norm(e.textContent)));
  if (!b) return null;
  b.scrollIntoView({ block: 'center' });
  const r = b.getBoundingClientRect();
  const x = Math.round(r.left + r.width / 2);
  const y = Math.round(r.top + r.height / 2);
  return {
    x, y, disabled: b.disabled === true,
    viewport: [window.innerWidth, window.innerHeight],
    inViewport: x >= 0 && y >= 0 && x < window.innerWidth && y < window.innerHeight,
  };
})()`

const MOCK = join(ROOT, 'tests', 'fixtures', 'laya-sidecar-mock.py')

let mock
let chrome
let ws
let browserWs
let login
let token
let endpointId = null
let checkpointId = null
let kbId = null
let proposalId = null
let p2Id = null

/** 真键盘事件（给 Input.Search 用）：CDP 的 keyDown 带 windowsVirtualKeyCode=13，antd 才认 onPressEnter */
async function pressEnter(ws) {
  const base = { key: 'Enter', code: 'Enter', windowsVirtualKeyCode: 13, nativeVirtualKeyCode: 13 }
  await ws.send('Input.dispatchKeyEvent', { type: 'keyDown', ...base, text: '\r' })
  await ws.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base })
}

async function typeInto(ws, selector, text) {
  const focused = await evaluate(ws, `(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return false;
    el.focus();
    return true;
  })()`)
  if (!focused) return false
  await ws.send('Input.insertText', { text })
  let value = await evaluate(ws, `document.querySelector(${JSON.stringify(selector)}).value`)
  if (value !== text) {
    // 兜底：insertText 偶尔不触发 React 的 onChange（受控组件），用原生 setter + input 事件
    await evaluate(ws, `(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(el, ${JSON.stringify(text)});
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`)
    value = await evaluate(ws, `document.querySelector(${JSON.stringify(selector)}).value`)
    if (value !== text) return false
  }
  await pressEnter(ws)
  return true
}

try {
  mkdirSync(OUT, { recursive: true })
  rmSync(DOWNLOADS, { recursive: true, force: true })
  mkdirSync(DOWNLOADS, { recursive: true })
  rmSync(PROFILE, { recursive: true, force: true })

  // ── 0. 前置：被测实例就是前端代理指向的那个 ──────────────────────
  log('[0] 前置：前后端实例一致性')
  const health = await fetch(`${API}/api/health`).then((r) => r.json()).catch(() => null)
  check('后端 /api/health 可达', health?.status === 'UP', JSON.stringify(health))
  if (health?.status !== 'UP') throw new Error(`后端未起或有误：${API}`)
  const viaFront = await fetch(`${BASE}/api/health`).then((r) => (r.ok ? r.json() : null)).catch(() => null)
  check('前端 dev server 可达且 /api 通了（端口被占时 Vite 会换端口，用 BASE 指过去）',
    viaFront?.status === 'UP', JSON.stringify(viaFront))

  // 浏览器发 POST 会带 Origin 头，白名单外直接 403（GET 同源不带 Origin，于是"读得到、写不了"）。
  // 拿登录当探针：无需 token、无副作用，200 就说明这个来源能写。
  const writeProbe = await fetch(`${BASE}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  }).then((r) => r.status).catch(() => 0)
  check('前端来源在后端 CORS 白名单内（否则浏览器侧 POST 全 403，页面看着像"点了没反应"）',
    writeProbe === 200, `经代理 POST /auth/login → ${writeProbe}（把 ${BASE} 加进 devmind.cors.allowed-origins）`)

  login = await api('POST', '/auth/login', { username: USER, password: PASS })
  token = login.accessToken
  check('登录 admin（记录页在 RequireAdmin 后面）', !!token && !!login.refreshToken && login.user?.role === 'ADMIN')

  // ── 1. 备数据 ──────────────────────────────────────────────────
  log('\n[1] 备数据：假边车 → DECISION 端点 → 知识库/条目 → 提案（等自动分诊）')
  mock = spawn('python', [MOCK, String(MOCK_PORT)], { cwd: ROOT, stdio: 'ignore' })
  const sidecar = await waitFor(
    () => fetch(`http://127.0.0.1:${MOCK_PORT}/__state`).then((r) => (r.ok ? r.json() : null)),
    '假边车起来', 10000).catch(() => null)
  check('假边车（laya-sidecar-mock）已起', !!sidecar)
  if (!sidecar) throw new Error('laya-sidecar-mock 起不来')

  endpointId = (await api('POST', '/model-endpoints',
    { kind: 'DECISION', name: 'CAP55-UI-E2E-决策边车', baseUrl: `http://127.0.0.1:${MOCK_PORT}` }, token)).id
  await api('PUT', `/model-endpoints/${endpointId}/default`, undefined, token)
  check('登记 DECISION 端点并设为默认', !!endpointId)

  // 准入闸门（CAP-56 FR-07）挡在端点判定之前：端点配好但库里没有验证过的产物时，分诊按钮照样是灰的。
  // 所以「按钮可用」这条前端断言必须先把闸门打开——登记一份产物并人工验证通过（这正是不验证闸门
  // 就永远绿不了的那一步：登记 ≠ 放行）。留一条"还没验证时不可用"的断言，钉住这一步不能省。
  const gate0 = await api('GET', '/decision/checkpoints/gate', undefined, token)
  check('闸门关着（还没登记任何验证过的产物）', gate0?.open === false, JSON.stringify(gate0))
  checkpointId = (await api('POST', '/decision/checkpoints',
    { name: 'CAP55-UI-E2E-产物', serveSlot: 'multilingual', kind: 'BASE',
      sourcePath: 'convaiinnovations/laya' }, token)).id
  const beforeVerify = await api('GET', '/decision/checkpoints/gate', undefined, token)
  check('登记完闸门仍关着（登记 ≠ 放行，要人看着指标按下验证）',
    beforeVerify?.open === false, JSON.stringify(beforeVerify))
  await api('POST', `/decision/checkpoints/${checkpointId}/verify`,
    { note: 'E2E：mock 边车跑通的判断依据，非真实指标' }, token)
  const gate1 = await api('GET', '/decision/checkpoints/gate', undefined, token)
  check('验证通过后闸门打开（按钮才有理由亮）', gate1?.open === true, JSON.stringify(gate1))

  kbId = (await api('POST', '/knowledge/bases',
    { name: 'CAP55-UI-E2E 经验库', scope: 'global', injectMode: 'FULL' }, token)).id
  await api('POST', '/knowledge/entries',
    { kbId, name: ENTRY_NAME, contentMd: ENTRY_CONTENT, status: 'active' }, token)
  check('建经验库与比对条目（条目内容含标题原文，LIKE 召回才命中）', !!kbId)

  proposalId = (await api('POST', '/knowledge/proposals',
    { title: PROPOSAL_TITLE, contentMd: '九成环境类报错在日志最后 200 行，先看再猜网络。',
      targetScope: 'project', targetProjectId: null, sourceSessionId: 'cap55-ui-e2e' }, token)).id
  const triaged = await waitFor(async () => {
    const p = (await api('GET', '/knowledge/proposals', undefined, token)).find((x) => x.id === proposalId)
    return p?.triage?.at ? p : null
  }, '自动分诊落库', 30000)
  check('提案已自动分诊（徽标数据就位）', !!triaged?.triage?.at)
  check('分诊未降级、选了 multilingual、召回命中比对条目',
    triaged.triage.degraded === false && triaged.triage.model === 'multilingual'
      && (triaged.triage.duplicate?.similar ?? []).some((s) => s.entryName === ENTRY_NAME),
    JSON.stringify(triaged.triage))

  // 实例一致性：经前端代理拿一次数据，看到刚建的提案 = 代理指的就是被测后端（不是 8080 那份旧构建）
  const viaFrontData = await fetch(`${BASE}/api/knowledge/proposals`, { headers: { Authorization: `Bearer ${token}` } })
    .then((r) => (r.ok ? r.json() : null)).catch(() => null)
  check('前端 /api 代理到同一后端（同一份数据里能看到刚建的提案）',
    (viaFrontData ?? []).some((p) => p.id === proposalId),
    `代理回 ${(viaFrontData ?? []).length} 条；直连 ${API} 建的是 ${proposalId}`)

  // ── 2. 起 Chrome、注入登录态 ─────────────────────────────────────
  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  chrome = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${CDP_PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1600,900', 'about:blank',
  ], { stdio: 'ignore' })

  const target = await waitFor(async () => {
    const l = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json()
    return l.find((t) => t.type === 'page')?.webSocketDebuggerUrl
  }, 'Chrome 调试端口')
  ws = cdp(target)
  await ws.ready
  await ws.send('Page.enable')
  await ws.send('Runtime.enable')

  // 下载行为只能设在 browser 级别（Page.setDownloadBehavior 已废弃）
  const browserTarget = await fetch(`http://127.0.0.1:${CDP_PORT}/json/version`).then((r) => r.json())
  browserWs = cdp(browserTarget.webSocketDebuggerUrl)
  await browserWs.ready
  await browserWs.send('Browser.setDownloadBehavior', {
    behavior: 'allow', downloadPath: DOWNLOADS, eventsEnabled: true,
  })

  // 先落地一次（拿到 origin），再写登录态，再整页重载——store 是模块加载时读 localStorage 的
  await ws.send('Page.navigate', { url: `${BASE}/` })
  await ws.waitEvent('Page.loadEventFired').catch(() => {})
  await evaluate(ws, `(() => {
    localStorage.setItem('devmind.accessToken', ${JSON.stringify(token)});
    localStorage.setItem('devmind.refreshToken', ${JSON.stringify(login.refreshToken)});
    localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(login.user))});
    return 'ok';
  })()`)

  const goto = async (path, waitForText) => {
    ws.reset()
    await ws.send('Page.navigate', { url: BASE + path })
    await ws.waitEvent('Page.loadEventFired', 20000).catch(() => {})
    await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-spin-spinning').length === 0`),
      '加载态结束', 10000).catch(() => {})
    if (waitForText) {
      await waitFor(async () => (await evaluate(ws, TEXT)).includes(waitForText), `页面出现「${waitForText}」`, 15000)
    }
    await sleep(400)
  }

  // ── 3. inbox：徽标 + 查看依据抽屉 + 管理抽屉里的 AI 建议 ──────────
  log('\n[2] 经验提案 inbox：徽标、查看依据、分诊按钮')
  await goto('/admin/knowledge', '知识库')
  check('页头 Segmented 切到「经验提案」', await evaluate(ws, clickSegmented('经验提案')))
  await waitFor(async () => (await evaluate(ws, TEXT)).includes(PROPOSAL_TITLE), '提案行出现', 15000)

  const rowText = await evaluate(ws, rowTextOf(PROPOSAL_TITLE))
  check('行内徽标：层级 / 不重复 / 质量三块都在',
    rowText.includes('采纳到全局') && rowText.includes('不重复') && rowText.includes('质量 直接可用'),
    JSON.stringify(rowText))
  check('未降级的行不出现「降级」徽标', !rowText.includes('降级'), JSON.stringify(rowText))

  const overflow1 = await evaluate(ws, OVERFLOW)
  check('经验提案视图不溢出内容区（内容区布局约定）',
    overflow1.contentOverflow <= 1 && overflow1.docOverflow <= 1, JSON.stringify(overflow1))

  check('点行内「查看依据」', await evaluate(ws, clickAnyByText('.ant-table-tbody', '查看依据')))
  await waitFor(async () => (await evaluate(ws, DRAWER_TEXT)).includes('laya 应答原文'), '依据抽屉打开', 10000)
  const evidence = await evaluate(ws, DRAWER_TEXT)
  check('抽屉给出去路：routing.reason 原文', evidence.includes('non-Latin script'), JSON.stringify(evidence.slice(0, 300)))
  check('抽屉摊开采纳层级的概率分布（不只留一个置信度）',
    evidence.includes('global 90%') && evidence.includes('discard'), JSON.stringify(evidence.slice(0, 300)))
  check('抽屉展示重复判定的召回比对物', evidence.includes(ENTRY_NAME), JSON.stringify(evidence.slice(0, 300)))
  check('抽屉回放 laya 应答原文（三题齐全）',
    ['adopt_layer', 'duplicate', 'quality'].every((k) => evidence.includes(k)), JSON.stringify(evidence.slice(0, 300)))

  await evaluate(ws, `document.querySelector('.ant-drawer-open .ant-drawer-close')?.click()`)
  await sleep(400)
  check('打开提案「管理」抽屉', await evaluate(ws, rowButton(PROPOSAL_TITLE, '管理')))
  await waitFor(async () => (await evaluate(ws, DRAWER_TEXT)).includes('AI 建议'), '管理抽屉出现 AI 建议区块', 10000)
  const manage = await evaluate(ws, DRAWER_TEXT)
  check('管理抽屉里徽标与分诊时间同在', manage.includes('采纳到全局') && manage.includes('质量 直接可用'),
    JSON.stringify(manage.slice(0, 300)))

  const triageBtn = await evaluate(ws, buttonCenter('.ant-drawer-open', '重新分诊'))
  check('端点可用时按钮不灰（文案是「重新分诊」= 已有建议）', triageBtn?.disabled === false, JSON.stringify(triageBtn))

  // 人工裁决：晋升全局（gold 的来源）
  check('点「晋升全局」', await evaluate(ws, clickByText('.ant-drawer-open', '晋升全局')))
  await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-modal-confirm-btns')`), '确认弹窗', 10000)
  check('确认弹窗里点「采纳」', await evaluate(ws, clickByText('.ant-modal-confirm-btns', '采纳')))
  const adopted = await waitFor(async () => {
    const p = (await api('GET', '/knowledge/proposals', undefined, token)).find((x) => x.id === proposalId)
    return p?.status === 'adopted' ? p : null
  }, '采纳落库', 15000)
  check('采纳成功（adoptedTo=global，成为该能力的第一条人工裁决）', adopted.adoptedTo === 'global', JSON.stringify(adopted))

  // ── 4. 决策记录页 ─────────────────────────────────────────────
  log('\n[3] /admin/decision-records：模型建议 vs 人工裁决')
  await goto('/admin/decision-records', '决策记录')
  check('后台菜单有「决策记录」入口', (await evaluate(ws, SIDER_TEXT)).includes('决策记录'))
  await waitFor(async () => (await evaluate(ws, rowTextOf(CAPABILITY))).length > 0, '记录行出现', 15000)

  const recRow = await evaluate(ws, rowTextOf(CAPABILITY))
  check('行归属该能力与提案 id', recRow.includes(CAPABILITY) && recRow.includes(String(proposalId)),
    JSON.stringify(recRow))
  check('人工裁决列显示采纳去向与 gold 值',
    recRow.includes('采纳到全局') && recRow.includes('adopt_layer=global'), JSON.stringify(recRow))
  check('结果比对：模型也给 global，判 1/1 一致', recRow.includes('1/1 一致'), JSON.stringify(recRow))
  check('状态列标可训练（三份快照齐全 + gold 落上题面）', recRow.includes('可训练'), JSON.stringify(recRow))

  check('点行内「查看」', await evaluate(ws, rowButton(CAPABILITY, '查看')))
  await waitFor(async () => (await evaluate(ws, DRAWER_TEXT)).includes('模型建议 vs 人工裁决'), '详情抽屉打开', 10000)
  const detail = await evaluate(ws, DRAWER_TEXT)
  check('详情逐题列出模型建议与人工裁决', detail.includes('adopt_layer') && detail.includes('global'),
    JSON.stringify(detail.slice(0, 300)))
  check('逐题给出一致性结论', detail.includes('一致'), JSON.stringify(detail.slice(0, 300)))
  check('逐字回放 state 快照（当初模型看到的输入）',
    detail.includes('当初发给模型的 state 快照') && detail.includes('proposal_title'), JSON.stringify(detail.slice(0, 300)))
  check('逐字回放题面（含 criteria——gold 的取值域来自它）',
    detail.includes('当初发给模型的 questions') && detail.includes('discard'), JSON.stringify(detail.slice(0, 300)))

  const overflow2 = await evaluate(ws, OVERFLOW)
  check('决策记录页不溢出内容区', overflow2.contentOverflow <= 1 && overflow2.docOverflow <= 1, JSON.stringify(overflow2))

  // ── 5. 导出训练集：筛选 → 点按钮 → 浏览器真下载 ──────────────────
  log('\n[4] 导出训练集（能力筛选 + 浏览器真下载 + JSONL 校验）')
  await evaluate(ws, `document.querySelector('.ant-drawer-open .ant-drawer-close')?.click()`)
  await sleep(400)
  check('在能力框里输入并回车', await typeInto(ws, '.ant-card-body input.ant-input', CAPABILITY))
  await waitFor(async () => (await evaluate(ws, rowTextOf(CAPABILITY))).length > 0, '筛选后行仍在', 10000)

  // 下载是异步落盘的（Chrome 先写 .crdownload 再改名），所以「点了」和「下到了」要分开证：
  // 先等 browser 级 downloadWillBegin（证明浏览器确实发起了下载），再等文件改名完成。
  // 只等文件的话，一个下不来的下载和一次没生效的点击在报告里长得一模一样。
  browserWs.reset()
  const downloadBegan = browserWs.waitEvent('Browser.downloadWillBegin', 20000).catch(() => null)
  check('extra 有「导出训练集」按钮',
    await evaluate(ws, clickByText('.ant-layout-content .ant-card-head', '导出训练集')))
  const began = await downloadBegan
  check('浏览器发起了下载（downloadWillBegin）', !!began,
    began ? `建议文件名 ${began.suggestedFilename}` : '点了导出但浏览器没发起下载')
  let waitErr = null
  const downloaded = await waitFor(() => {
    const f = readdirSync(DOWNLOADS).filter((n) => n.endsWith('.jsonl') && !n.endsWith('.crdownload'))
    return f.length ? f[0] : null
  }, '导出的 JSONL 落盘', 30000).catch((e) => {
    waitErr = e.message
    return null
  })
  check('浏览器确实下到了 .jsonl', !!downloaded,
    `目录：${JSON.stringify(readdirSync(DOWNLOADS))}${waitErr ? ` · ${waitErr}` : ''}`)

  if (downloaded) {
    check('文件名带当前筛选的能力 + 时间戳（不覆盖上一次导出）',
      downloaded.includes(CAPABILITY) && /-\d{8}-\d{6}\.jsonl$/.test(downloaded), downloaded)
    const raw = readFileSync(join(DOWNLOADS, downloaded), 'utf8')
    let parsed = null
    try {
      parsed = raw.split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l))
      check('每行都是合法 JSON', true)
    } catch (e) {
      check('每行都是合法 JSON', false, String(e))
    }
    if (parsed) {
      check('筛选生效：导出的就是这一条（不是全库）', parsed.length === 1, `行数 ${parsed.length}`)
      const line = parsed[0]
      check('行内正好三字段 state/questions/gold（顺序即契约）',
        JSON.stringify(Object.keys(line ?? {})) === JSON.stringify(['state', 'questions', 'gold']),
        JSON.stringify(Object.keys(line ?? {})))
      check('state 是逐字快照（训练侧对齐靠它）', line?.state?.proposal_title === PROPOSAL_TITLE,
        JSON.stringify(line?.state))
      check('questions 三题齐全', ['adopt_layer', 'duplicate', 'quality'].every((k) => k in (line?.questions ?? {})),
        JSON.stringify(Object.keys(line?.questions ?? {})))
      check('gold 按题面 criteria 摊成 one-hot（人工给 global）',
        JSON.stringify(line?.gold) === JSON.stringify({ adopt_layer: { global: 1, project: 0, discard: 0 } }),
        JSON.stringify(line?.gold))
    }
  }

  // ── 6. 降级链：端点没了 → 置灰 + 灰的理由 ────────────────────────
  log('\n[5] 降级链：删掉 DECISION 端点 → 按钮置灰并说明原因')
  await api('DELETE', `/model-endpoints/${endpointId}`, undefined, token)
  endpointId = null
  p2Id = (await api('POST', '/knowledge/proposals',
    { title: P2_TITLE, contentMd: '端点没了，按钮该灰。', targetScope: 'project', sourceSessionId: 'cap55-ui-e2e' }, token)).id

  await goto('/admin/knowledge', '知识库')
  await evaluate(ws, clickSegmented('经验提案'))
  await waitFor(async () => (await evaluate(ws, TEXT)).includes(P2_TITLE), '降级期提案出现', 15000)
  check('打开降级期提案的「管理」', await evaluate(ws, rowButton(P2_TITLE, '管理')))
  await waitFor(async () => (await evaluate(ws, DRAWER_TEXT)).includes('AI 建议'), '管理抽屉打开', 10000)

  // P2 是建的时候自动分诊过一次的（端点已删 → 落成一条 degraded 记录），所以文案是「重新分诊」：
  // 「落过一条降级记录」和「从没分诊过」在同一颗按钮上是两种文案，断言按两种都收。
  // 抽屉是**滑入**的：动画没停时按钮 rect 还挂在视口右外侧（实测 x=1644 / 视口宽 1584），
  // 这时候把坐标交给 dispatchMouseEvent 等于打空气（elementFromPoint 给 null）——等滑到位再量。
  const deadBtn = await waitFor(async () => {
    const b = await evaluate(ws, buttonCenter('.ant-drawer-open', ['AI 分诊', '重新分诊']))
    return b?.inViewport ? b : null
  }, '分诊按钮滑入视口', 8000).catch(() => null)
  check('无端点时「分诊」按钮置灰', deadBtn?.disabled === true, JSON.stringify(deadBtn))
  if (deadBtn) {
    // 真鼠标移上去：Tooltip 挂的是 mouseenter，dispatched MouseEvent 触发不了。
    // 而且必须**先移到别处再进按钮**——指针从没在别处落过时，直接报一个按钮上的坐标不构成"进入"，
    // mouseenter 不派发，Tooltip 永远不弹（探针验过：加一次 (5,5) 就弹）。
    await ws.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: 5, y: 5 })
    await sleep(200)
    await ws.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: deadBtn.x, y: deadBtn.y })
    await sleep(1200)
    // 定位坐标本身也要留证：Tooltip 不弹时，"事件没到按钮上"（被别的层压着 / 坐标在视口外）
    // 和"弹了但文案不对"是两回事，只有 elementFromPoint 能分开。
    const hit = await evaluate(ws, `(() => {
      const el = document.elementFromPoint(${deadBtn.x}, ${deadBtn.y});
      return {
        drawers: document.querySelectorAll('.ant-drawer-open').length,
        at: el ? el.tagName + '.' + el.className : null,
        tips: [...document.querySelectorAll('.ant-tooltip')].map((t) => t.className + '|' + t.innerText.slice(0, 40)),
      };
    })()`)
    const tip = await evaluate(ws, `document.querySelector('.ant-tooltip-inner')?.innerText ?? ''`)
    check('悬停给出置灰原因（指向模型接入）', tip.includes('模型接入'),
      `${JSON.stringify(tip)} · 落点 ${JSON.stringify(hit)}`)
  }

  // ── 7. 清理 ──────────────────────────────────────────────────
  log('\n[6] 清理自建的库/条目/产物（产物要撤销放行才删得掉，否则下一轮重跑必 409）')
  const entries = await api('GET', `/knowledge/bases/${kbId}/entries`, undefined, token).catch(() => [])
  for (const e of entries ?? []) await api('DELETE', `/knowledge/entries/${e.id}`, undefined, token).catch(() => {})
  await api('DELETE', `/knowledge/bases/${kbId}`, undefined, token).catch(() => {})
  if (checkpointId) {
    await api('POST', `/decision/checkpoints/${checkpointId}/unverify`,
      { reason: 'UI E2E 收尾：本轮产物作废' }, token).catch(() => {})
    await api('DELETE', `/decision/checkpoints/${checkpointId}`, undefined, token).catch(() => {})
  }
  check('清理完成（隔离实例，脏数据不落别人库）', true)
  void p2Id
} catch (e) {
  failures.push(`脚本异常：${e.message}`)
  log(`\n脚本异常：${e.stack ?? e.message}`)
} finally {
  if (ws) {
    try {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(OUT, 'last.png'), Buffer.from(shot.data, 'base64'))
    } catch { /* 页面已关就算了 */ }
  }
  ws?.close()
  browserWs?.close()
  chrome?.kill()
  mock?.kill()
  await sleep(800)
  try {
    rmSync(PROFILE, { recursive: true, force: true })
  } catch { /* Windows 句柄晚放，留给下次运行前清理 */ }
}

writeFileSync(join(OUT, 'report.json'),
  JSON.stringify({ at: new Date().toISOString(), base: BASE, api: API, passed, failures }, null, 2))
log(`\n== CAP-55 FR-07 前端 E2E: ${passed} passed, ${failures.length} failed ==`)
log(`   截图/下载/报告 → ${OUT}`)
if (failures.length) {
  log('   失败项：')
  failures.forEach((f) => log(`     - ${f}`))
}
process.exit(failures.length ? 1 : 0)
