// CAP-69 交互统一 UI 回归：三种套件类型（smoke/api/script）的新建/运行/编辑走统一交互。
// 断言：① 「新建套件」开统一抽屉（类型选择在表单内，选 script 才展开 git/命令字段）；
// ② smoke 与 script 套件行操作都是 运行/编辑/删除 三键；③ 行内「运行」弹窗按类型渲染字段
// （smoke=目标环境/baseUrl；script=env 覆盖/命令覆盖）；④ script 行「编辑」跳内层页且是脚本属性表单。
// 前置：后端 :8080 + 前端 :5173 已起（scripts/dev.sh）。
// 用法：node tests/cap69_ui_unify.mjs   （BASE/API/USER/PASS/CHROME_PATH 可覆盖）
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
const PROFILE = join(ROOT, 'tmp', 'chrome-cap69-ui-unify')
const PORT = 9341
const MARK = 'CAP69-UI-UNIFY'

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

/** 最小 CDP 客户端（与 tests/cap67_usage_tab_switch_ui.mjs 同款） */
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
  if (exceptionDetails) throw new Error(`${exceptionDetails.text ?? '页面求值异常'}: ${JSON.stringify(exceptionDetails.exception?.description ?? '').slice(0, 300)}`)
  return result.value
}

// ---------- 页面操作辅助（注入页内执行；antd 两字按钮会插空格，一律去空白比较） ----------
const HELPERS = `
  window.__t = (s) => (s ?? '').replace(/\\s+/g, '');
  window.__btn = (root, label) => [...root.querySelectorAll('button')]
    .find((b) => window.__t(b.textContent) === label);
  window.__setInput = (el, v) => {
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
  };
  window.__formItem = (root, label) => [...root.querySelectorAll('.ant-form-item')]
    .find((f) => window.__t(f.querySelector('.ant-form-item-label')?.textContent).startsWith(window.__t(label)));
  window.__drawer = () => document.querySelector('.ant-drawer-open .ant-drawer-content-wrapper') ?? document.querySelector('.ant-drawer-open');
  window.__modal = () => [...document.querySelectorAll('.ant-modal-wrap')]
    .find((m) => getComputedStyle(m).display !== 'none');
  window.__row = (name) => [...document.querySelectorAll('.ant-table tbody tr.ant-table-row')]
    .find((tr) => (tr.textContent ?? '').includes(name));
`

/** 点内容区 extra 里的按钮 */
async function clickExtraButton(ws, label) {
  const ok = await evaluate(ws, `(() => {
    const card = document.querySelector('.ant-layout-content .ant-card');
    const b = window.__btn(card.querySelector('.ant-card-extra'), ${JSON.stringify(label)});
    if (!b) return false;
    b.click();
    return true;
  })()`)
  if (!ok) throw new Error(`找不到 extra 按钮「${label}」`)
}

/** 在抽屉/弹窗表单里按 label 填 input/textarea */
async function fillField(ws, containerFn, label, value) {
  const ok = await evaluate(ws, `(() => {
    const c = ${containerFn};
    if (!c) return 'no-container';
    const f = window.__formItem(c, ${JSON.stringify(label)});
    if (!f) return 'no-field';
    const el = f.querySelector('textarea') ?? f.querySelector('input');
    if (!el) return 'no-input';
    window.__setInput(el, ${JSON.stringify(value)});
    return true;
  })()`)
  if (ok !== true) throw new Error(`填字段「${label}」失败：${ok}`)
}

/** 抽屉里切「类型」下拉 */
async function selectKind(ws, optionText) {
  await evaluate(ws, `(() => {
    const f = window.__formItem(window.__drawer(), '类型');
    f.querySelector('.ant-select-selector').dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    return true;
  })()`)
  await sleep(400)
  const ok = await evaluate(ws, `(() => {
    const opt = [...document.querySelectorAll('.ant-select-dropdown .ant-select-item-option')]
      .find((e) => window.__t(e.textContent).startsWith(${JSON.stringify(optionText)}));
    if (!opt) return false;
    opt.click();
    return true;
  })()`)
  if (!ok) throw new Error(`找不到类型选项「${optionText}」`)
  await sleep(300)
}

/** 表格行内点操作按钮 */
async function clickRowButton(ws, rowName, label) {
  const ok = await evaluate(ws, `(() => {
    const tr = window.__row(${JSON.stringify(rowName)});
    if (!tr) return 'no-row';
    const b = window.__btn(tr, ${JSON.stringify(label)});
    if (!b) return 'no-button';
    b.click();
    return true;
  })()`)
  if (ok !== true) throw new Error(`行「${rowName}」点「${label}」失败：${ok}`)
  await sleep(300)
}

/** 行内操作按钮文本集（去空白） */
async function rowActions(ws, rowName) {
  return evaluate(ws, `(() => {
    const tr = window.__row(${JSON.stringify(rowName)});
    if (!tr) return null;
    return [...tr.querySelectorAll('button')].map((b) => window.__t(b.textContent));
  })()`)
}

async function main() {
  const { accessToken, refreshToken, user } = await api('POST', '/auth/login', { username: USER, password: PASS })
  console.log('[1] 登录 ok')

  // 造数：临时 LOCAL 项目（path 用仓库自身即可，不做构建），结束删除
  const proj = await api('POST', '/projects', { name: MARK, path: ROOT.replaceAll('\\', '/') }, accessToken)
  const pid = proj.id
  console.log(`[2] 临时项目 ${pid} 已建`)
  const smokeName = `${MARK}-smoke`
  const scriptName = `${MARK}-script`

  rmSync(PROFILE, { recursive: true, force: true })
  const chrome = spawn(CHROME, [
    '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
    '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--window-size=1900,1000', 'about:blank',
  ], { stdio: 'ignore' })

  const failures = []
  let ws
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
    await evaluate(ws, HELPERS + `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      localStorage.setItem('devmind.currentProjectId', ${JSON.stringify(pid)});
      return 'ok';
    })()`)

    ws.reset()
    await ws.send('Page.navigate', { url: `${BASE}/tests` })
    await ws.waitEvent('Page.loadEventFired', 20000).catch(() => {})
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-layout-content .ant-card .ant-table')`), '套件表格渲染')
    await evaluate(ws, HELPERS) // 导航后重新注入辅助函数
    await sleep(500)
    // extra 只剩 刷新/新建套件：「从 OpenAPI 生成」已收进抽屉类型
    const hasGenBtn = await evaluate(ws, `!!window.__btn(document.querySelector('.ant-layout-content .ant-card .ant-card-extra'), '从OpenAPI生成')`)
    if (hasGenBtn) failures.push('extra 仍挂「从 OpenAPI 生成」入口（应收进新建抽屉类型）')
    console.log('[3] /tests 已加载')

    // ① 新建抽屉：类型选择在表单内；默认 smoke 无 git 字段
    await clickExtraButton(ws, '新建套件')
    await waitFor(() => evaluate(ws, `!!window.__drawer()`), '新建抽屉打开')
    let probe = await evaluate(ws, `(() => ({
      hasKind: !!window.__formItem(window.__drawer(), '类型'),
      hasRepo: !!window.__formItem(window.__drawer(), 'git 仓库地址'),
    }))()`)
    if (!probe.hasKind) failures.push('新建抽屉缺「类型」选择')
    if (probe.hasRepo) failures.push('默认 smoke 类型不应出现 git 仓库字段')

    // ①b openapi 类型：收进同一抽屉，不填名称（服务端生成），提示文案出现；不真生成（项目无 apiDocSource 会 400）
    await selectKind(ws, 'openapi')
    probe = await evaluate(ws, `(() => ({
      hasName: !!window.__formItem(window.__drawer(), '名称'),
      hint: (window.__drawer().textContent ?? '').includes('OpenAPI'),
      okText: window.__t(window.__btn(window.__drawer(), '生成')?.textContent),
    }))()`)
    if (probe.hasName) failures.push('openapi 类型不应要名称字段')
    if (!probe.hint) failures.push('openapi 类型缺生成说明文案')
    if (probe.okText !== '生成') failures.push(`openapi 类型确认键应为「生成」，实为 ${probe.okText}`)
    // 切回 smoke 再建，确认名称字段恢复
    await selectKind(ws, 'smoke')
    probe = await evaluate(ws, `(() => ({ hasName: !!window.__formItem(window.__drawer(), '名称') }))()`)
    if (!probe.hasName) failures.push('切回 smoke 后名称字段未恢复')

    // smoke：只填名称即建
    await fillField(ws, `window.__drawer()`, '名称', smokeName)
    await evaluate(ws, `window.__btn(window.__drawer(), '创建').click()`)
    await waitFor(() => evaluate(ws, `!window.__drawer()`), '抽屉关闭')
    await waitFor(() => evaluate(ws, `!!window.__row(${JSON.stringify(smokeName)})`), 'smoke 套件入行')
    console.log('[4] smoke 套件经统一抽屉创建 ok')

    // ② script：切类型展开 git 字段，填全后建
    await clickExtraButton(ws, '新建套件')
    await waitFor(() => evaluate(ws, `!!window.__drawer()`), '新建抽屉再开')
    await selectKind(ws, 'script')
    probe = await evaluate(ws, `(() => ({
      hasRepo: !!window.__formItem(window.__drawer(), 'git 仓库地址'),
      hasCmd: !!window.__formItem(window.__drawer(), '执行命令'),
    }))()`)
    if (!probe.hasRepo || !probe.hasCmd) failures.push('切 script 后 git/命令字段未展开')
    await fillField(ws, `window.__drawer()`, '名称', scriptName)
    await fillField(ws, `window.__drawer()`, 'git 仓库地址', 'http://git.local/group/repo.git')
    await fillField(ws, `window.__drawer()`, '执行命令', 'echo hi')
    await evaluate(ws, `window.__btn(window.__drawer(), '创建').click()`)
    await waitFor(() => evaluate(ws, `!window.__drawer()`), '抽屉关闭(script)')
    await waitFor(() => evaluate(ws, `!!window.__row(${JSON.stringify(scriptName)})`), 'script 套件入行')
    console.log('[5] script 套件经同一抽屉创建 ok')

    // ③ 行操作统一：运行/编辑/删除 三键
    for (const [name, tag] of [[smokeName, 'smoke'], [scriptName, 'script']]) {
      const acts = await rowActions(ws, name)
      const want = ['运行', '编辑', '删除']
      if (!acts || !want.every((w) => acts.includes(w)))
        failures.push(`${tag} 行操作不统一：${JSON.stringify(acts)}`)
    }
    console.log('[6] 行操作三键一致 ok')

    // ④ 行内运行弹窗按类型渲染字段
    await clickRowButton(ws, smokeName, '运行')
    await waitFor(() => evaluate(ws, `!!window.__modal()`), 'smoke 运行弹窗')
    probe = await evaluate(ws, `(() => ({
      env: !!window.__formItem(window.__modal(), '目标环境'),
      base: !!window.__formItem(window.__modal(), 'baseUrl'),
      cmd: !!window.__formItem(window.__modal(), '命令覆盖'),
    }))()`)
    if (!probe.env || !probe.base || probe.cmd) failures.push(`smoke 运行弹窗字段错：${JSON.stringify(probe)}`)
    await evaluate(ws, `window.__btn(window.__modal(), '取消').click()`)
    await sleep(400)

    await clickRowButton(ws, scriptName, '运行')
    await waitFor(() => evaluate(ws, `!!window.__modal()`), 'script 运行弹窗')
    probe = await evaluate(ws, `(() => ({
      env: !!window.__formItem(window.__modal(), 'env 覆盖'),
      cmd: !!window.__formItem(window.__modal(), '命令覆盖'),
      base: !!window.__formItem(window.__modal(), 'baseUrl'),
    }))()`)
    if (!probe.env || !probe.cmd || probe.base) failures.push(`script 运行弹窗字段错：${JSON.stringify(probe)}`)
    await evaluate(ws, `window.__btn(window.__modal(), '取消').click()`)
    await sleep(400)
    console.log('[7] 行内运行弹窗按类型渲染 ok')

    // ⑤ script 行「编辑」跳内层页且为脚本属性表单
    await clickRowButton(ws, scriptName, '编辑')
    await waitFor(() => evaluate(ws, `location.pathname.startsWith('/tests/suites/')`), '跳内层页')
    await waitFor(() => evaluate(ws, `!!window.__formItem(document, 'git 仓库地址')`), 'script 属性表单渲染')
    const cmdVal = await evaluate(ws, `window.__formItem(document, '执行命令').querySelector('textarea').value`)
    if (cmdVal !== 'echo hi') failures.push(`script 内层页命令回显错：${cmdVal}`)
    // ⑤a 并排字段布局回归：Space 会包一层 .ant-space-item 使子项 flex:1 失效（退回内容宽度、字段瘦长错位），
    // 必须 Flex 直包——此处断言「分支」输入框宽度接近整行字段（git 仓库地址）的一半
    const layoutProbe = await evaluate(ws, `(() => {
      const w = (label) => window.__formItem(document, label)?.querySelector('input')?.getBoundingClientRect().width ?? 0
      return { repo: w('git 仓库地址'), branch: w('分支'), subdir: w('工作子目录（可选）') }
    })()`)
    if (!(layoutProbe.repo > 0 && layoutProbe.branch > 0.35 * layoutProbe.repo && layoutProbe.branch < 0.65 * layoutProbe.repo
      && Math.abs(layoutProbe.branch - layoutProbe.subdir) < 24)) {
      failures.push(`script 内层页并排字段宽度异常：${JSON.stringify(layoutProbe)}`)
    }
    const hasCaseTable = await evaluate(ws, `!!document.querySelector('.ant-layout-content .ant-table')`)
    if (hasCaseTable) failures.push('script 内层页不应有用例表格')
    // ⑤b 内层页保存：改命令 → 保存 → API 回读确认落库
    await fillField(ws, `document`, '执行命令', 'echo hi2')
    await evaluate(ws, `window.__btn(document.querySelector('.ant-card-extra'), '保存').click()`)
    await waitFor(() => evaluate(ws, `[...document.querySelectorAll('.ant-message')].some((m) => (m.textContent ?? '').includes('已保存'))`), '保存成功提示')
    const ss = (await api('GET', `/script-suites?projectId=${pid}`, undefined, accessToken)).find((x) => x.name === scriptName)
    if (ss?.command !== 'echo hi2') failures.push(`script 内层页保存未落库：command=${ss?.command}`)
    console.log('[8] script 编辑内层页 ok（含保存落库）')

    // ⑥ 内层页「运行」按钮：extra 直发，弹窗字段与列表行内运行一致（script=env/命令覆盖）
    await evaluate(ws, `window.__btn(document.querySelector('.ant-card-extra'), '运行').click()`)
    await waitFor(() => evaluate(ws, `!!window.__modal()`), '内层页运行弹窗')
    probe = await evaluate(ws, `(() => ({
      env: !!window.__formItem(window.__modal(), 'env 覆盖'),
      cmd: !!window.__formItem(window.__modal(), '命令覆盖'),
      base: !!window.__formItem(window.__modal(), 'baseUrl'),
    }))()`)
    if (!probe.env || !probe.cmd || probe.base) failures.push(`内层页 script 运行弹窗字段错：${JSON.stringify(probe)}`)
    await evaluate(ws, `window.__btn(window.__modal(), '取消').click()`)
    await sleep(400)
    console.log('[9] 内层页「运行」按钮 ok')

    // ⑦ smoke 内层页也有「运行」（api/smoke 字段族）
    await evaluate(ws, `window.__btn(document.querySelector('.ant-card-extra'), '返回列表').click()`)
    await waitFor(() => evaluate(ws, `!!window.__row(${JSON.stringify(smokeName)})`), '回列表')
    await clickRowButton(ws, smokeName, '编辑')
    await waitFor(() => evaluate(ws, `location.pathname.startsWith('/tests/suites/') && !!document.querySelector('.ant-layout-content .ant-table')`), 'smoke 内层页用例表格')
    await evaluate(ws, `window.__btn(document.querySelector('.ant-card-extra'), '运行').click()`)
    await waitFor(() => evaluate(ws, `!!window.__modal()`), 'smoke 内层页运行弹窗')
    probe = await evaluate(ws, `(() => ({
      env: !!window.__formItem(window.__modal(), '目标环境'),
      base: !!window.__formItem(window.__modal(), 'baseUrl'),
      cmd: !!window.__formItem(window.__modal(), '命令覆盖'),
    }))()`)
    if (!probe.env || !probe.base || probe.cmd) failures.push(`内层页 smoke 运行弹窗字段错：${JSON.stringify(probe)}`)
    await evaluate(ws, `window.__btn(window.__modal(), '取消').click()`)
    console.log('[10] smoke 内层页「运行」按钮 ok')
  } finally {
    ws?.close()
    chrome.kill()
    // 清理：套件 → 项目
    try {
      const suites = await api('GET', `/projects/${pid}/test-suites`, undefined, accessToken)
      for (const s of suites) {
        await api('DELETE', s.kind === 'script' ? `/script-suites/${s.id}` : `/test-suites/${s.id}`, undefined, accessToken)
      }
      await api('DELETE', `/projects/${pid}`, undefined, accessToken)
      console.log('[11] 临时项目与套件已清理')
    } catch (e) {
      console.log(`[11] 清理失败（项目 ${pid} 需手工删）：${e.message}`)
    }
  }

  if (failures.length) {
    console.error('FAIL:')
    failures.forEach((f) => console.error('  - ' + f))
    process.exit(1)
  }
  console.log('PASS: 新建/运行/编辑交互统一全部断言通过')
}

main().catch((e) => { console.error(e); process.exit(1) })
