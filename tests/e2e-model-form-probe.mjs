// 「模型接入」页抽屉内「测试连接」端到端回归：**填进表单的凭据与超时必须真的进探针请求**。
//
// 为什么单独写这个脚本：这一跳只存在于浏览器里。cap48_verify.py 打的是服务端接口，参数由脚本显式传入；
// 页面则是「从 antd 表单取值 → 组装草稿请求」。2026-09-20 真机反馈「PowerShell 能列出模型，页面添加时
// 报 401」的根因就在这里：form.validateFields(nameList) 只回 nameList 里那几个字段
// （rc-field-form useForm.js `getFieldsValue(namePathList)`），把它的返回值直接当表单取值用，
// apiKey 与 timeoutSeconds 会被静默丢掉 → 服务端不发 Authorization → 上游 401。
//
// 前置（与 tests/e2e-layout-pages.mjs 同姿势，两个进程都要起）：
//   1) 后端：先 `mvn -q install -DskipTests`，再
//      mvn -pl devmind-app spring-boot:run -Dspring-boot.run.arguments="--server.port=18090 \
//        --spring.profiles.active=e2e \
//        --spring.datasource.url=jdbc:h2:file:./tmp/e2e-model-form/devmind;AUTO_SERVER=TRUE"
//   2) 前端：cd frontend && VITE_BACKEND_URL=http://localhost:18090 npm run dev
//      **端口被占时 Vite 会自动换到 5174，此时必须用 BASE 指过去**（脚本开头有防呆比对，指错会直接报错）
//   3) 本机 Chrome（CHROME_PATH 可覆盖）
// 用法：node tests/e2e-model-form-probe.mjs
//   BASE / API / USER / PASS / CHROME_PATH / MOCK_PORT 可覆盖。
// 只读：本脚本自起一个「强制鉴权」的假端点收探针，全程不点「保存」，不往库里写任何端点。
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { createServer } from 'node:http'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')
const BASE = process.env.BASE ?? 'http://localhost:5173'
const API = process.env.API ?? 'http://localhost:18090'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const MOCK_PORT = Number(process.env.MOCK_PORT ?? 18195)
const CDP_PORT = Number(process.env.CDP_PORT ?? 9336)
/** 假端点的期望凭据：只有它才放行，其余一律 401（与真机 vLLM 行为一致） */
const KEY = 'sk-form-probe-9f3c71'
const TMP = join(ROOT, 'tmp')
const OUT = join(TMP, 'e2e-model-form-probe')
const PROFILE = join(TMP, 'chrome-model-form-probe')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const log = (...a) => console.log(...a)

let passed = 0
let failed = 0
const failures = []
function check(name, ok, detail = '') {
  if (ok) {
    passed++
    log(`  PASS  ${name}`)
  } else {
    failed++
    failures.push(`${name}${detail ? ` —— ${detail}` : ''}`)
    log(`  FAIL  ${name}${detail ? `\n         ${detail}` : ''}`)
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

// ── 假端点：强制鉴权，收探针 ───────────────────────────────────
const mock = { requests: [], delayMs: 0 }

function startMock() {
  const srv = createServer((req, res) => {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const rec = { path: req.url, auth: req.headers.authorization, body: Buffer.concat(chunks).toString('utf8').slice(0, 300) }
      mock.requests.push(rec)
      const send = (status, payload) => {
        const buf = Buffer.from(payload, 'utf8')
        res.writeHead(status, { 'Content-Type': 'application/json', 'Content-Length': buf.length })
        res.end(buf)
      }
      const reply = () => {
        if (rec.auth !== `Bearer ${KEY}`) return send(401, '{"error":"Unauthorized"}')
        if (rec.path === '/v1/embeddings') {
          return send(200, '{"data":[{"embedding":[0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8]}]}')
        }
        if (rec.path === '/v1/chat/completions') {
          return send(200, '{"choices":[{"message":{"role":"assistant","content":"可用"}}]}')
        }
        return send(404, '{"detail":"Not Found"}')
      }
      if (mock.delayMs) setTimeout(reply, mock.delayMs)
      else reply()
    })
  })
  return new Promise((resolve) => srv.listen(MOCK_PORT, '127.0.0.1', () => resolve(srv)))
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

async function waitFor(pred, what, timeout = 20000, interval = 200) {
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

/**
 * 页面内助手。**取值必须模拟真人输入**（native setter + input 事件），
 * 否则 React 受控组件不认，表单 store 仍是空值——那样测出来的失败是测试自己的问题。
 */
const INJECT = `(() => {
  window.__t = {
    item(label) {
      return [...document.querySelectorAll('.ant-drawer .ant-form-item')]
        .find((el) => (el.querySelector('label')?.textContent ?? '').includes(label));
    },
    set(label, value) {
      const input = this.item(label)?.querySelector('input');
      if (!input) return { ok: false, why: '找不到字段「' + label + '」' };
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
      setter.call(input, value);
      for (const t of ['input', 'change']) input.dispatchEvent(new Event(t, { bubbles: true }));
      return { ok: true, echo: input.value };
    },
    openSelect(label) {
      const trigger = this.item(label)?.querySelector('.ant-select-selector');
      if (!trigger) return { ok: false, why: '找不到下拉「' + label + '」' };
      trigger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      return { ok: true };
    },
    pickOption(text) {
      const opts = [...document.querySelectorAll('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')];
      const opt = opts.find((o) => (o.textContent ?? '').includes(text));
      if (!opt) return { ok: false, why: '下拉里没有「' + text + '」', seen: opts.map((o) => o.textContent) };
      opt.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      opt.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      return { ok: true, picked: opt.textContent };
    },
    selected(label) { return (this.item(label)?.querySelector('.ant-select-selection-item')?.textContent ?? '').trim(); },
    alert() {
      const a = document.querySelector('.ant-drawer .ant-alert');
      if (!a) return null;
      const cls = a.className;
      const type = cls.includes('ant-alert-error') ? 'error'
        : cls.includes('ant-alert-success') ? 'success'
        : cls.includes('ant-alert-warning') ? 'warning' : '?';
      return { type, text: (a.querySelector('.ant-alert-message')?.textContent ?? '').trim() };
    },
    clickTest() {
      const b = [...document.querySelectorAll('.ant-drawer button')].find((x) => (x.textContent ?? '').includes('测试连接'));
      if (!b) return { ok: false, why: '找不到「测试连接」按钮' };
      b.click();
      return { ok: true };
    },
    ready() { return !!document.querySelector('.ant-drawer .ant-form-item'); },
  };
  return 'ok';
})()`

async function main() {
  mkdirSync(OUT, { recursive: true })
  rmSync(PROFILE, { recursive: true, force: true })

  const { accessToken, refreshToken, user } = await api('POST', '/auth/login', { username: USER, password: PASS })
  if (!accessToken) throw new Error('登录未拿到 accessToken')
  const projects = await api('GET', '/projects', undefined, accessToken).catch(() => null)
  const project = (projects?.items ?? projects ?? [])[0] ?? null
  log(`[1] 登录 ${USER}${project ? `，当前项目 ${project.name}` : '（库里无项目）'}`)

  // 防呆：前端必须代理到本脚本要用的那个后端。5173/8080 上常驻着另一个实例（本机 dev 环境），
  // Vite 端口被占会自动换到 5174——不核对就会「用 A 前端测 B 后端」，测出来的是一堆假失败。
  const viaFrontend = await fetch(`${BASE}/api/model-endpoints`, { headers: { Authorization: `Bearer ${accessToken}` } })
    .then((r) => (r.ok ? r.json() : `HTTP ${r.status}`)).catch((e) => `连不上 ${e.message}`)
  const direct = await api('GET', '/model-endpoints', undefined, accessToken).catch(() => null)
  const n = (v) => (Array.isArray(v) ? v.length : Array.isArray(v?.items) ? v.items.length : String(v))
  if (n(viaFrontend) !== n(direct)) {
    throw new Error(`前端 ${BASE} 代理到的不是 ${API}（前端侧端点 ${n(viaFrontend)} 条 vs 直连 ${n(direct)} 条）。`
      + `5173 多半被别的实例占着，Vite 已换端口——用 BASE=http://localhost:5174 指到本实例的前端`)
  }
  log(`[1b] 前后端对齐：${BASE} 经代理拿到 ${n(direct)} 条端点，与直连 ${API} 一致`)

  const mockSrv = await startMock()
  log(`[2] 假端点已起 http://127.0.0.1:${MOCK_PORT}/v1（强制 Bearer ${KEY.slice(0, 8)}…，不匹配一律 401）`)

  if (!existsSync(CHROME)) throw new Error(`找不到浏览器：${CHROME}（用 CHROME_PATH 指定）`)
  const chrome = spawn(CHROME, [
    '--headless=new',
    `--remote-debugging-port=${CDP_PORT}`,
    `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1600,900',
    'about:blank',
  ], { stdio: 'ignore' })

  let ws
  let shot = async () => null
  try {
    const target = await waitFor(async () => {
      const l = await (await fetch(`http://127.0.0.1:${CDP_PORT}/json/list`)).json()
      return l.find((t) => t.type === 'page')?.webSocketDebuggerUrl
    }, 'Chrome 调试端口')
    ws = cdp(target)
    await ws.ready
    await ws.send('Page.enable')
    await ws.send('Runtime.enable')
    shot = async (slug) => {
      const s = await ws.send('Page.captureScreenshot', { format: 'png' })
      const f = join(OUT, `${slug}.png`)
      writeFileSync(f, Buffer.from(s.data, 'base64'))
      return f
    }

    await ws.send('Page.navigate', { url: `${BASE}/` })
    await ws.waitEvent('Page.loadEventFired')
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      ${project ? `localStorage.setItem('devmind.currentProjectId', ${JSON.stringify(project.id)});` : ''}
      return 'ok';
    })()`)

    ws.reset()
    await ws.send('Page.navigate', { url: `${BASE}/admin/models` })
    await ws.waitEvent('Page.loadEventFired').catch(() => {})
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-layout-content')`), '内容区就绪', 10000)

    // 防呆：浏览器带 Origin 的请求必须过后端 CORS 白名单，否则写接口一律 403（提示条根本不会出现）。
    // 前端换了端口就会踩（devmind.cors.allowed-origins 默认只放 5173/8080）。
    const corsStatus = await evaluate(ws, `fetch('/api/health').then((r) => r.status).catch((e) => String(e))`)
    if (corsStatus !== 200) {
      throw new Error(`浏览器在 ${BASE} 上打 /api/health 得到 ${corsStatus}——该 origin 大概率不在后端 CORS 白名单`
        + `（devmind.cors.allowed-origins）。把前端跑回 :5173，或给 app 加 --devmind.cors.allowed-origins=…`)
    }
    await waitFor(() => evaluate(ws, `document.querySelectorAll('.ant-spin-spinning').length === 0`), '加载态结束', 10000).catch(() => {})
    const opened = await evaluate(ws, `(() => {
      const b = [...document.querySelectorAll('button')].find((x) => (x.textContent ?? '').includes('新建端点'));
      if (!b) return false;
      b.click();
      return true;
    })()`)
    if (!opened) throw new Error('模型接入页没渲染出「新建端点」按钮（路由/权限？先人工打开 ' + BASE + '/admin/models 看一眼）')
    await evaluate(ws, INJECT)
    await waitFor(() => evaluate(ws, `window.__t.ready()`), '抽屉表单就绪', 10000)

    // ── 3. 选「通用模型」（默认是 Embedding，真机反馈的场景就是对话模型）──
    let kind = 'EMBEDDING'
    const openedSel = await evaluate(ws, `window.__t.openSelect('类型')`)
    if (openedSel.ok) {
      await sleep(350)
      const picked = await evaluate(ws, `window.__t.pickOption('通用模型')`)
      await sleep(250)
      const shown = await evaluate(ws, `window.__t.selected('类型')`)
      if (picked.ok && shown.includes('通用模型')) kind = 'CHAT'
      else log(`    （类型下拉没切过去：${JSON.stringify(picked)}，按 ${kind} 分支断言）`)
    } else {
      log(`    （${openedSel.why}，按 ${kind} 分支断言）`)
    }
    const expectPath = kind === 'CHAT' ? '/v1/chat/completions' : '/v1/embeddings'
    log(`[3] 类型 = ${kind}，预期探针打到 ${expectPath}`)

    // ── 4. 填表：凭据是这次回归的主角 ───────────────────────────
    const fill = { 名称: 'E2E 表单探针', 服务地址: `http://127.0.0.1:${MOCK_PORT}/v1`, 模型名: 'e2e-probe-model', 'API Key': KEY }
    for (const [label, value] of Object.entries(fill)) {
      const r = await evaluate(ws, `window.__t.set(${JSON.stringify(label)}, ${JSON.stringify(value)})`)
      if (!r.ok) throw new Error(`填表失败：${r.why}`)
      if (r.echo !== value) throw new Error(`字段「${label}」没被表单接住（回显 ${JSON.stringify(r.echo)}）——测试自身的输入姿势有问题`)
    }
    log('[4] 表单已填（名称/服务地址/模型名/API Key）')

    // ── 5. 断言一：点「测试连接」后，服务端真的收到了 Bearer 凭据 ──
    mock.requests.length = 0
    const clicked = await evaluate(ws, `window.__t.clickTest()`)
    if (!clicked.ok) throw new Error(clicked.why)
    const alert = await waitFor(async () => {
      const a = await evaluate(ws, `window.__t.alert()`)
      return a && a.text ? a : null
    }, '测试结果提示条', 25000)
    const hit = mock.requests.find((r) => r.path === expectPath)
    if (!hit) await shot('no-request')
    check('探针打到了预期路径', !!hit, `收到 ${mock.requests.map((r) => r.path).join(', ') || '零请求'}，预期 ${expectPath}`)
    check('探针带了表单里填的凭据', hit?.auth === `Bearer ${KEY}`, `服务端收到 auth=${JSON.stringify(hit?.auth)}`)
    check('提示条显示连接成功', alert.type === 'success' && alert.text.includes('连接成功'), `提示条：${alert.type} ${alert.text}`)

    // ── 6. 断言二：同样是「表单里填了才生效」的超时，也必须带上 ──
    // 假端点改成拖 5 秒：表单填 2 秒 → 必须在 2 秒（embedding 还会重试一次）内失败；
    // 若超时被丢掉（退回默认 30 秒），5 秒的响应会成功——那就是没带上。
    mock.delayMs = 5000
    const t = await evaluate(ws, `window.__t.set('超时（秒）', '2')`)
    if (!t.ok) throw new Error(`填超时失败：${t.why}`)
    mock.requests.length = 0
    const t0 = Date.now()
    await evaluate(ws, `window.__t.clickTest()`)
    const err = await waitFor(async () => {
      const a = await evaluate(ws, `window.__t.alert()`)
      return a && a.type === 'error' ? a : null
    }, '超时失败的提示条', 20000).catch(() => null)
    const elapsed = Date.now() - t0
    const passedKey = mock.requests.some((r) => r.auth === `Bearer ${KEY}`)
    check('超时值生效（拖 5 秒的假端点按表单的 2 秒超时失败）', !!err && /timeout|timed out|超时/i.test(err.text),
      err ? `提示条：${err.text}（耗时 ${elapsed}ms）` : `20 秒内没出现失败提示条（耗时 ${elapsed}ms）——超时很可能被丢掉退回默认 30 秒`)
    check('超时重试的请求同样带了凭据', passedKey, `auth 值：${JSON.stringify(mock.requests.map((r) => r.auth))}`)
    mock.delayMs = 0

    // 收尾：关抽屉（不保存，不落库）
    await evaluate(ws, `(() => {
      const b = [...document.querySelectorAll('.ant-drawer button')].find((x) => (x.textContent ?? '').trim() === '取消');
      if (b) b.click();
      return 'ok';
    })()`)
  } catch (e) {
    failed++
    failures.push(`脚本异常：${e.message}`)
    log(`\n  FATAL ${e.message}`)
    if (ws) await shot('fatal').catch(() => {})
  } finally {
    try { ws?.close() } catch { /* ignore */ }
    chrome.kill()
    mockSrv.close()
  }

  const report = join(OUT, 'report.txt')
  writeFileSync(report, [
    `时间：${new Date().toISOString()}`,
    `类型：${'见上方日志'}`,
    `收/发探针：${JSON.stringify(mock.requests, null, 2)}`,
    '',
    ...failures.map((f) => `FAIL ${f}`),
    `passed=${passed} failed=${failed}`,
  ].join('\n'), 'utf8')
  log(`\n[5] ${passed} passed / ${failed} failed   —— 报告 ${report}`)
  process.exit(failed ? 1 : 0)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
