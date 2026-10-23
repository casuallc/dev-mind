// 共享 Markdown 阅读/编辑组件（shared/components/Markdown、MarkdownEditor）真浏览器验证。
// 覆盖：
//   阅读：代码块语言标签 + 高亮 token + 一键复制、表格横向滚动容器、图片点击预览、任务列表；
//   编辑：CodeMirror 源码、字符统计、Ctrl+B 加粗、Tab 缩进、编辑/分栏/预览三态、分栏预览高亮、
//         全屏与 Esc 退出（不连带关抽屉）；
//   极简模式（showToolbar=false）：工作日志日报默认预览、顶栏只有源码/分栏/预览三个图标、
//         字段标题通过 title 进入顶栏（上方无独立标签）、撑满页面底部，源码态行号/当前行/
//         Markdown 语法高亮、可编辑；分栏左源码右实时预览，输入后右侧即时更新，
//         两栏滚动条按比例双向联动；周报两字段左右等宽、等高并排。
// 前置：app 隔离实例 + 指向它的前端 dev 同时起（数据自造，落隔离 H2，无需 runner）：
//   mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
//     "-Dspring-boot.run.arguments=--server.port=18103 --spring.datasource.url=jdbc:h2:file:<tmp>/markdown-components/db/devmind;AUTO_SERVER=TRUE"
//   VITE_BACKEND_URL=http://localhost:18103 npm run dev -- --port 5199 --strictPort   # frontend/ 下
//   node tests/e2e-markdown-components.mjs [--shots]   （API/BASE/CHROME_PATH 可覆盖）
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
const PORT = 9473
const TMP = join(ROOT, 'tmp', 'markdown-components', 'e2e')
const PROFILE = join(TMP, 'chrome')
const MARK = `e2e-md-${Date.now()}`

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

async function uploadAttachment(token) {
  // 1x1 透明 PNG：只验证「点击图片开预览」链路，不求画质
  const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=', 'base64')
  const form = new FormData()
  form.append('file', new Blob([png], { type: 'image/png' }), `dot-${MARK}.png`)
  form.append('scope', 'SHARED')
  const res = await fetch(`${API}/api/attachments`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${token}` },
    body: form,
  })
  const text = await res.text()
  if (!res.ok) throw new Error(`上传附件 → ${res.status} ${text.slice(0, 300)}`)
  return JSON.parse(text)
}

/** 最小 CDP 客户端（Node 内置 WebSocket，与 e2e-injection-preview-drawer.mjs 同套路） */
function cdp(wsUrl) {
  const ws = new WebSocket(wsUrl)
  const pending = new Map()
  const logs = []
  let seq = 0
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data)
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id)
      pending.delete(msg.id)
      msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
      return
    }
    if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) {
      logs.push(`${msg.params.type}: ${msg.params.args.map((a) => a.value ?? a.description ?? '').join(' ')}`)
    }
  })
  return {
    logs,
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

const drawerOpenJs = `(() => {
  const w = document.querySelector('.ant-drawer-open');
  return !!w && getComputedStyle(w).display !== 'none';
})()`
const clickButtonJs = (text) => `(() => {
  const btn = [...document.querySelectorAll('button')]
    .find((b) => b.innerText.replace(/\\s/g, '') === ${JSON.stringify(text)});
  if (!btn) return false;
  btn.click();
  return true;
})()`
const clickSegJs = (text) => `(() => {
  const el = [...document.querySelectorAll('.ant-drawer .ant-segmented-item')]
    .find((e) => e.textContent.replace(/\\s/g, '') === ${JSON.stringify(text)});
  if (!el) return false;
  el.click();
  return true;
})()`
const clickPageSegJs = (text) => `(() => {
  const el = [...document.querySelectorAll('.ant-segmented-item')]
    .find((e) => e.textContent.replace(/\\s/g, '') === ${JSON.stringify(text)});
  if (!el) return false;
  el.click();
  return true;
})()`
const javaCodeBlockJs = `(() => {
  return [...document.querySelectorAll('.ant-drawer .md-code, .md-code')]
    .find((b) => b.querySelector('.md-code-lang')?.textContent.trim() === 'Java') ?? null;
})()`
// 预览层关闭后节点可能仍留在 DOM（rc-dialog 默认不销毁），可见性要看 root 的面积与 display
const imagePreviewOpenJs = `(() => {
  const el = document.querySelector('.ant-image-preview-root');
  if (!el) return false;
  const r = el.getBoundingClientRect();
  return getComputedStyle(el).display !== 'none' && r.width > 0 && r.height > 0;
})()`

async function pressEscape(ws) {
  for (const type of ['keyDown', 'keyUp']) {
    await ws.send('Input.dispatchKeyEvent', {
      type, key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27,
    })
  }
}

/** 真实鼠标点中 CodeMirror 某一行（按行内文本定位），让编辑器自己接管选区 */
async function clickCmLine(ws, scope, text) {
  let pos = null
  // 抽屉滑入动画期间 rect 还在视口外，等行真正落到可点击位置再点
  for (let i = 0; i < 20; i++) {
    pos = await evaluate(ws, `(() => {
      const line = [...document.querySelectorAll(${JSON.stringify(scope)})]
        .find((l) => l.textContent.includes(${JSON.stringify(text)}));
      if (!line) return null;
      const r = line.getBoundingClientRect();
      const x = r.left + 8;
      const y = r.top + r.height / 2;
      const hit = document.elementFromPoint(x, y);
      return { x, y, inside: !!hit && line.contains(hit) };
    })()`)
    if (pos?.inside) break
    await sleep(100)
  }
  if (!pos?.inside) throw new Error(`CodeMirror 行不可点击：${text}`)
  await ws.send('Input.dispatchMouseEvent', {
    type: 'mousePressed', x: pos.x, y: pos.y, button: 'left', buttons: 1, clickCount: 1,
  })
  await ws.send('Input.dispatchMouseEvent', {
    type: 'mouseReleased', x: pos.x, y: pos.y, button: 'left', buttons: 0, clickCount: 1,
  })
  await sleep(100)
}

/** 真实按键（modifiers：Alt=1 Ctrl=2 Meta=4 Shift=8） */
async function pressKey(ws, key, code, vk, modifiers = 0) {
  for (const type of ['keyDown', 'keyUp']) {
    await ws.send('Input.dispatchKeyEvent', {
      type, key, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk, modifiers,
    })
  }
  await sleep(100)
}

let chrome
async function main() {
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(TMP, { recursive: true })

  const login = await api('/auth/login', { body: { username: USER, password: PASS } })
  const { accessToken, refreshToken, user } = login
  const attachment = await uploadAttachment(accessToken)

  const title = `Markdown 组件验证-${MARK}`
  const codeMarker = `hello-${MARK}`
  const contentMd = `# ${title}

这是段落文字，用于快捷键验证。

## Java 代码块

\`\`\`java
public class Demo {
  public static void main(String[] args) {
    System.out.println("${codeMarker}");
  }
}
\`\`\`

## 无语言代码块

\`\`\`
plain text ${MARK}
\`\`\`

## 表格

| 名称 | 说明 | 值 |
| --- | --- | --- |
| alpha | 一段足够长的说明文本用来撑开表格宽度 | 1 |
| beta | 另一段说明 | 2 |

## 任务

- [x] 已完成
- [ ] 待办

![预览图](/api/attachments/${attachment.attachmentId}/raw)

尾部标记 ${MARK}
`
  const doc = await api('/documents', {
    token: accessToken,
    body: { kind: 'design', title, tags: ['e2e'], contentMd },
  })
  ok(!!doc.id && !!attachment.attachmentId, '造数完成（文档 + 图片附件）')

  // 工作日志日报：初始化日志空间 + 造当天日报，供编辑器极简模式断言
  const today = new Date().toLocaleDateString('sv-SE')
  const dailyBody = Array.from({ length: 30 }, (_, i) => `第 ${i + 1} 段：用于验证分栏滚动联动与实时预览。`).join('\n\n')
  await api('/worklog/workspace/ensure', { token: accessToken, body: {} })
  try {
    await api('/worklog/daily', { token: accessToken, body: { date: today } })
  } catch { /* 已存在 */ }
  await api(`/worklog/daily/${today}`, {
    method: 'PUT',
    token: accessToken,
    body: {
      contentMd: `# 日报 ${MARK}\n\n- 完成甲\n- 完成乙\n\n## 明细\n\n${dailyBody}\n\n\`\`\`java\nclass Daily { }\n\`\`\`\n`,
    },
  })
  ok(true, '日报造数完成')

  // 周报：本周一为 weekStart，造周总结 / 下周计划两段，供左右布局断言
  const todayDate = new Date(`${today}T00:00:00`)
  todayDate.setDate(todayDate.getDate() - (todayDate.getDay() + 6) % 7)
  const weekStart = todayDate.toLocaleDateString('sv-SE')
  try {
    await api('/worklog/weekly', { token: accessToken, body: { weekStart } })
  } catch { /* 已存在 */ }
  await api(`/worklog/weekly/${weekStart}`, {
    method: 'PUT',
    token: accessToken,
    body: {
      summaryMd: `# 周总结 ${MARK}\n\n- 完成甲\n- 完成乙\n\n${dailyBody}\n`,
      nextPlanMd: `# 下周计划 ${MARK}\n\n- 计划甲\n- 计划乙\n`,
    },
  })
  ok(true, '周报造数完成')

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
    await waitFor(() => evaluate(ws, `document.readyState === 'complete'`), '首页加载完', 30000)
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      return 'ok';
    })()`)
    await ws.send('Page.navigate', { url: `${BASE}/admin/docs/${doc.id}` })
    await waitFor(() => evaluate(ws, `!!${javaCodeBlockJs}`), '文档只读页 Java 代码块', 30000)

    // ── 1. 阅读：高亮 + 语言标签 ────────────────────────────
    const readInfo = await evaluate(ws, `(() => {
      const blocks = [...document.querySelectorAll('.md-code')];
      const javaBlock = ${javaCodeBlockJs};
      const keywords = [...(javaBlock?.querySelectorAll('.hljs-keyword') ?? [])].map((e) => e.textContent);
      return {
        blocks: blocks.length,
        labels: blocks.map((b) => b.querySelector('.md-code-lang')?.textContent.trim() ?? ''),
        keywords,
        hasString: !!javaBlock?.querySelector('.hljs-string')?.textContent.includes(${JSON.stringify(codeMarker)}),
        taskItems: document.querySelectorAll('.doc-md .task-list-item').length,
      };
    })()`)
    ok(readInfo.blocks >= 2, `代码块渲染出工具栏（${readInfo.blocks} 个）`)
    ok(readInfo.labels.includes('Java') && readInfo.labels.includes('代码'), `语言标签（${readInfo.labels.join(' / ')}）`)
    ok(readInfo.keywords.length > 0 && readInfo.hasString, `highlight.js 着色生效（keyword: ${readInfo.keywords.slice(0, 3).join(', ')}）`)
    ok(readInfo.taskItems === 2, 'GFM 任务列表渲染')
    await sleep(300)
    ok(!ws.logs.some((l) => l.includes('cannot be a descendant')), '无非法 DOM 嵌套告警（图片段落）')

    // ── 2. 复制按钮：点击后拿到纯代码文本 ────────────────────
    await evaluate(ws, `(() => {
      window.__copied = null;
      Object.defineProperty(navigator, 'clipboard', {
        configurable: true,
        value: { writeText: async (t) => { window.__copied = t; } },
      });
      return true;
    })()`)
    await evaluate(ws, `(() => { ${javaCodeBlockJs}.querySelector('.md-code-copy').click(); return true })()`)
    const copied = await waitFor(() => evaluate(ws, `window.__copied`), '复制回调')
    ok(copied.includes(codeMarker) && copied.includes('class Demo') && !copied.includes('\`\`\`'),
      '复制内容为纯代码（含正文、不含围栏）')
    await waitFor(
      () => evaluate(ws, `[...document.querySelectorAll('.ant-message-notice-content')].some((e) => e.textContent.includes('代码已复制'))`),
      '复制成功提示', 5000,
    )
    ok(true, '复制成功提示')

    // ── 3. 表格横向滚动容器 + 图片预览 ───────────────────────
    const tableInfo = await evaluate(ws, `(() => {
      const wrap = document.querySelector('.doc-md .md-table-wrap');
      return {
        hasWrap: !!wrap,
        overflowX: wrap ? getComputedStyle(wrap).overflowX : '',
        rows: wrap?.querySelectorAll('tbody tr').length ?? 0,
      };
    })()`)
    ok(tableInfo.hasWrap && tableInfo.overflowX === 'auto' && tableInfo.rows === 2,
      `表格包横向滚动容器（overflow-x=${tableInfo.overflowX}，${tableInfo.rows} 行）`)

    await evaluate(ws, `document.querySelector('.doc-md .ant-image-img').click()`)
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-image-preview-img')`), '图片预览层')
    const previewSrc = await evaluate(ws, `document.querySelector('.ant-image-preview-img')?.getAttribute('src') ?? ''`)
    ok(previewSrc.includes(attachment.attachmentId), '点图片打开 antd 预览（src 指向附件）')
    await evaluate(ws, `document.querySelector('.ant-image-preview-close')?.click()`)
    await waitFor(async () => !(await evaluate(ws, imagePreviewOpenJs)), '图片预览关闭')

    // ── 4. 打开编辑抽屉：字符数 / 快捷键 / Tab 缩进 ─────────
    ok(await evaluate(ws, clickButtonJs('编辑')), '点「编辑」打开抽屉')
    await waitFor(() => evaluate(ws, drawerOpenJs), '编辑抽屉打开')
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-drawer .md-editor .cm-content')`), '编辑器 CodeMirror')
    await waitFor(() => evaluate(ws, `(() => {
      const lines = [...document.querySelectorAll('.ant-drawer .md-editor .cm-line')];
      return lines.map((l) => l.textContent).join('').length > 0;
    })()`), '编辑器载入正文')

    const countText = await evaluate(ws, `(() => {
      const el = [...document.querySelectorAll('.ant-drawer .md-editor .ant-typography')]
        .find((e) => e.textContent.includes('字符'));
      return el?.textContent ?? '';
    })()`)
    const countDigits = countText.split('字符')[0].replace(/[^0-9]/g, '')
    ok(countDigits === String(contentMd.length), `字符统计（${countText.trim()} / 实际 ${contentMd.length}）`)

    const cmDocJs = `[...document.querySelectorAll('.ant-drawer .md-editor .cm-line')]
      .map((l) => l.textContent).join('\\n')`
    await clickCmLine(ws, '.ant-drawer .md-editor .cm-line', '这是段落')
    await pressKey(ws, 'Home', 'Home', 36)
    await pressKey(ws, 'End', 'End', 35, 8)
    await pressKey(ws, 'b', 'KeyB', 66, 2)
    await sleep(200)
    const boldValue = await evaluate(ws, cmDocJs)
    ok(boldValue.includes('**这是段落文字，用于快捷键验证。**'), 'Ctrl+B 包裹选区加粗')

    await clickCmLine(ws, '.ant-drawer .md-editor .cm-line', '## 无语言代码块')
    await pressKey(ws, 'Home', 'Home', 36)
    await pressKey(ws, 'Tab', 'Tab', 9)
    await sleep(200)
    const tabValue = await evaluate(ws, cmDocJs)
    ok(tabValue.includes('\n  ## 无语言代码块'), 'Tab 在行首缩进两空格')

    // ── 5. 分栏 / 预览 / 全屏 ───────────────────────────────
    ok(await evaluate(ws, clickSegJs('分栏')), '切到分栏模式')
    await waitFor(() => evaluate(ws, `!!document.querySelector('.ant-drawer .doc-md .md-code')`), '分栏预览代码块')
    const split = await evaluate(ws, `(() => {
      const src = document.querySelector('.ant-drawer .md-editor-source')?.getBoundingClientRect();
      const paneEl = document.querySelector('.ant-drawer .md-editor-preview');
      const pane = paneEl?.getBoundingClientRect();
      return {
        sideBySide: !!src && !!pane && pane.left >= src.right - 2,
        highlight: !!document.querySelector('.ant-drawer .doc-md .hljs-keyword'),
        srcHeight: src?.height ?? 0,
        paneHeight: pane?.height ?? 0,
        paneScroll: paneEl ? getComputedStyle(paneEl).overflowY : '',
      };
    })()`)
    ok(split.sideBySide, '分栏为左右两栏（预览在编辑区右侧）')
    ok(split.highlight, '分栏预览同样有代码高亮')
    ok(Math.abs(split.srcHeight - split.paneHeight) < 4 && split.paneScroll === 'auto',
      `分栏两栏等高、预览独立滚动（${Math.round(split.srcHeight)} / ${Math.round(split.paneHeight)}，overflow=${split.paneScroll}）`)

    await evaluate(ws, `(() => {
      const btn = document.querySelector('.ant-drawer .md-editor button[aria-label="全屏编辑"]');
      btn?.click();
      return !!btn;
    })()`)
    await waitFor(() => evaluate(ws, `!!document.querySelector('.md-editor-fullscreen')`), '全屏编辑器')
    const full = await evaluate(ws, `(() => {
      const el = document.querySelector('.md-editor-fullscreen');
      const r = el.getBoundingClientRect();
      return {
        covers: Math.abs(r.width - innerWidth) < 2 && Math.abs(r.height - innerHeight) < 2,
        portaled: !el.closest('.ant-drawer'),
        hasEditor: !!el.querySelector('.cm-editor'),
        hasPreview: !!el.querySelector('.doc-md .md-code'),
      };
    })()`)
    ok(full.covers && full.portaled, '全屏层铺满视口且脱离抽屉 DOM（portal）')
    ok(full.hasEditor && full.hasPreview, '全屏下编辑与分栏预览都在')

    if (process.argv.includes('--shots')) {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'fullscreen.png'), Buffer.from(shot.data, 'base64'))
      console.log(`  截图 → ${join(TMP, 'fullscreen.png')}`)
    }

    await pressEscape(ws)
    await waitFor(async () => !(await evaluate(ws, `!!document.querySelector('.md-editor-fullscreen')`)), 'Esc 退出全屏')
    ok(await evaluate(ws, drawerOpenJs), 'Esc 退出全屏后编辑抽屉仍在（未连带关闭）')

    ok(await evaluate(ws, clickSegJs('预览')), '切到纯预览模式')
    await waitFor(() => evaluate(ws, `!document.querySelector('.ant-drawer .md-editor .cm-editor') && !!document.querySelector('.ant-drawer .md-editor .doc-md .md-code')`), '纯预览模式（无编辑框）')
    ok(true, '纯预览模式隐藏编辑框，仅保留渲染结果')

    // ── 6. 取消关闭：改动不落库 ─────────────────────────────
    await evaluate(ws, clickButtonJs('取消'))
    await waitFor(async () => !(await evaluate(ws, drawerOpenJs)), '编辑抽屉关闭')
    const reloaded = await api(`/documents/${doc.id}`, { method: 'GET', token: accessToken })
    ok(reloaded.contentMd === contentMd, '取消编辑后正文未落库')

    // ── 7. 工作日志日报：极简模式（showToolbar=false，只有源码/分栏/预览，无格式工具栏） ──
    await ws.send('Page.navigate', { url: `${BASE}/worklog` })
    await waitFor(() => evaluate(ws, `!![...document.querySelectorAll('.ant-segmented-item')].find((e) => e.textContent.replace(/\\s/g, '') === '日报')`), '工作日志页', 30000)
    ok(await evaluate(ws, clickPageSegJs('日报')), '切到日报视图')
    await waitFor(() => evaluate(ws, `!!document.querySelector('.md-editor-minimal')`), '日报极简模式编辑器')

    const simplePreview = await evaluate(ws, `(() => {
      const el = document.querySelector('.md-editor-minimal');
      return {
        modes: [...el.querySelectorAll('.md-editor-modes button')].map((b) => b.getAttribute('aria-label')),
        hasEditor: !!el.querySelector('.cm-editor'),
        previewOn: el.querySelector('button[aria-label="预览"]')?.getAttribute('aria-pressed') === 'true',
        topTitle: el.querySelector('.md-editor-title')?.textContent ?? '',
        standaloneLabel: el.parentElement.children.length > 1
          || !!el.parentElement.querySelector(':scope > .ant-typography'),
        title: el.querySelector('.doc-md h1')?.textContent ?? '',
        items: el.querySelectorAll('.doc-md li').length,
        highlight: !!el.querySelector('.doc-md .hljs-keyword'),
      };
    })()`)
    ok(simplePreview.modes.join() === '编辑源码,分栏,预览', '日报顶栏只有源码/分栏/预览三个图标（无格式工具栏）')
    ok(!simplePreview.hasEditor && simplePreview.previewOn, '日报默认预览态（无编辑器）')
    ok(/^日报内容（\d{4}-\d{2}-\d{2}，Markdown）$/.test(simplePreview.topTitle),
      `字段标题进入编辑器顶栏（${simplePreview.topTitle}）`)
    ok(!simplePreview.standaloneLabel, '编辑器上方不再有独立字段标签')
    ok(simplePreview.title.includes(MARK) && simplePreview.items === 2 && simplePreview.highlight,
      '日报预览走共享 Markdown 渲染（标题/列表/代码高亮）')

    // 高度铺满：编辑器底部 = 卡片内容区底部（只差卡片 padding）
    const layout = await evaluate(ws, `(() => {
      const el = document.querySelector('.md-editor-minimal');
      const body = el.closest('.ant-card-body');
      const er = el.getBoundingClientRect();
      const br = body.getBoundingClientRect();
      return {
        editorHeight: er.height,
        gap: br.bottom - er.bottom,
        paddingBottom: parseFloat(getComputedStyle(body).paddingBottom),
      };
    })()`)
    ok(layout.editorHeight > 280 && Math.abs(layout.gap - layout.paddingBottom) < 2,
      `日报编辑器撑满到页面底部（高 ${Math.round(layout.editorHeight)}px，底部留白 ${Math.round(layout.gap)}px = padding ${layout.paddingBottom}px）`)

    if (process.argv.includes('--shots')) {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'daily-report.png'), Buffer.from(shot.data, 'base64'))
      console.log(`  截图 → ${join(TMP, 'daily-report.png')}`)
    }

    await evaluate(ws, `document.querySelector('.md-editor-minimal button[aria-label="编辑源码"]').click()`)
    await waitFor(() => evaluate(ws, `(() => {
      const content = document.querySelector('.md-editor-minimal .cm-content');
      return !!content && content.textContent.includes(${JSON.stringify(MARK)});
    })()`), '日报源码态')
    const simpleEdit = await evaluate(ws, `(() => {
      const el = document.querySelector('.md-editor-minimal');
      const content = el.querySelector('.cm-content');
      const heading = [...el.querySelectorAll('.cm-line')].find((l) => l.textContent.includes('# 日报'));
      const headingColors = heading
        ? [...heading.querySelectorAll('span')].map((s) => getComputedStyle(s).color)
        : [];
      return {
        modes: el.querySelectorAll('.md-editor-modes button').length,
        noToolbar: ![...el.querySelectorAll('button')].some((b) => /^H[1-3]$/.test(b.textContent)),
        noStats: !el.innerText.includes('字符'),
        editable: content?.getAttribute('contenteditable') === 'true',
        lineNumbers: el.querySelectorAll('.cm-lineNumbers .cm-gutterElement').length,
        headingColors,
        headingHighlighted: headingColors.includes('rgb(9, 88, 217)')
          || (!!heading && getComputedStyle(heading).color === 'rgb(9, 88, 217)'),
        activeLine: !!el.querySelector('.cm-activeLine'),
        editorHeight: el.querySelector('.cm-editor')?.getBoundingClientRect().height ?? 0,
      };
    })()`)
    ok(simpleEdit.modes === 3 && simpleEdit.noToolbar && simpleEdit.noStats, '源码态同样无格式工具栏/字符统计')
    ok(simpleEdit.editable && simpleEdit.lineNumbers >= 3 && simpleEdit.activeLine,
      `源码态为带行号/当前行的可编辑编辑器（${simpleEdit.lineNumbers} 行号）`)
    ok(simpleEdit.headingHighlighted, `Markdown 标题语法高亮（${simpleEdit.headingColors.join(' / ')}）`)
    ok(Math.abs(simpleEdit.editorHeight - layout.editorHeight) < 40, '源码态与预览态高度一致（都铺满）')

    if (process.argv.includes('--shots')) {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'daily-report-source.png'), Buffer.from(shot.data, 'base64'))
      console.log(`  截图 → ${join(TMP, 'daily-report-source.png')}`)
    }

    // ── 8. 分栏：左源码实时驱动右侧预览，两栏滚动条按比例双向联动 ──
    await evaluate(ws, `document.querySelector('.md-editor-minimal button[aria-label="分栏"]').click()`)
    await waitFor(() => evaluate(ws, `(() => {
      const el = document.querySelector('.md-editor-minimal');
      return !!el?.querySelector('.cm-scroller') && !!el?.querySelector('.md-editor-preview');
    })()`), '分栏两栏渲染')

    const splitLayout = await evaluate(ws, `(() => {
      const el = document.querySelector('.md-editor-minimal');
      const src = el.querySelector('.cm-scroller');
      const prev = el.querySelector('.md-editor-preview');
      const sr = src.getBoundingClientRect();
      const pr = prev.getBoundingClientRect();
      return {
        sideBySide: pr.left >= sr.right - 1 && Math.abs(sr.height - pr.height) < 4,
        srcScrollable: src.scrollHeight > src.clientHeight,
        prevScrollable: prev.scrollHeight > prev.clientHeight,
        srcOverflow: getComputedStyle(src).overflowY,
        prevOverflow: getComputedStyle(prev).overflowY,
      };
    })()`)
    ok(splitLayout.sideBySide, '分栏左右并排且两栏等高')
    ok(splitLayout.srcScrollable && splitLayout.prevScrollable
      && splitLayout.srcOverflow === 'auto' && splitLayout.prevOverflow === 'auto',
      '分栏两栏都有独立滚动条')

    // 左侧改字，右侧预览实时更新（分栏的核心目的）
    const realtimeMark = `实时预览-${MARK}`
    await evaluate(ws, `document.querySelector('.md-editor-minimal .cm-content').focus()`)
    await ws.send('Input.insertText', { text: realtimeMark })
    await waitFor(() => evaluate(ws, `document.querySelector('.md-editor-minimal .md-editor-preview')?.innerText.includes(${JSON.stringify(realtimeMark)})`), '分栏实时预览')
    ok(true, '源码输入后右侧预览实时更新')

    const syncDown = await evaluate(ws, `(async () => {
      const el = document.querySelector('.md-editor-minimal');
      const src = el.querySelector('.cm-scroller');
      const prev = el.querySelector('.md-editor-preview');
      const maxSrc = src.scrollHeight - src.clientHeight;
      const maxPrev = prev.scrollHeight - prev.clientHeight;
      src.scrollTop = maxSrc * 0.6;
      await new Promise((r) => setTimeout(r, 300));
      return { maxSrc, maxPrev, srcTop: src.scrollTop, prevTop: prev.scrollTop };
    })()`)
    ok(syncDown.maxSrc > 0 && syncDown.maxPrev > 0
      && Math.abs(syncDown.srcTop / syncDown.maxSrc - syncDown.prevTop / syncDown.maxPrev) < 0.15,
      `源码滚动带动右侧按比例联动（${Math.round(syncDown.srcTop / syncDown.maxSrc * 100)}% → ${Math.round(syncDown.prevTop / syncDown.maxPrev * 100)}%）`)

    const syncUp = await evaluate(ws, `(async () => {
      const el = document.querySelector('.md-editor-minimal');
      const src = el.querySelector('.cm-scroller');
      const prev = el.querySelector('.md-editor-preview');
      prev.scrollTop = 0;
      await new Promise((r) => setTimeout(r, 300));
      return { srcTop: src.scrollTop, prevTop: prev.scrollTop };
    })()`)
    ok(syncUp.prevTop === 0 && syncUp.srcTop < 5, '右侧滚动条回顶时左侧跟随')

    if (process.argv.includes('--shots')) {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'daily-report-split.png'), Buffer.from(shot.data, 'base64'))
      console.log(`  截图 → ${join(TMP, 'daily-report-split.png')}`)
    }

    // ── 9. 周报：周总结 / 下周计划左右等宽并排，编辑器顶栏各自带字段标题 ──
    ok(await evaluate(ws, clickPageSegJs('周报')), '切到周报视图')
    await waitFor(() => evaluate(ws, `document.querySelectorAll('.md-editor-minimal').length === 2`), '周报两个编辑器')
    const weeklyLayout = await evaluate(ws, `(() => {
      const els = [...document.querySelectorAll('.md-editor-minimal')];
      const rects = els.map((e) => e.getBoundingClientRect());
      return {
        titles: els.map((e) => e.querySelector('.md-editor-title')?.textContent ?? ''),
        sideBySide: rects.length === 2
          && Math.abs(rects[0].top - rects[1].top) < 2
          && rects[0].right <= rects[1].left + 1,
        equalSize: rects.length === 2
          && Math.abs(rects[0].width - rects[1].width) < 2
          && Math.abs(rects[0].height - rects[1].height) < 2,
        heights: rects.map((r) => Math.round(r.height)),
      };
    })()`)
    ok(weeklyLayout.titles.length === 2 && weeklyLayout.titles[0].startsWith('周总结（')
      && weeklyLayout.titles[1].startsWith('下周计划（'),
      `周报两字段标题在各自编辑器顶栏（${weeklyLayout.titles.join(' / ')}）`)
    ok(weeklyLayout.sideBySide && weeklyLayout.equalSize,
      `周总结与下周计划左右并排、等宽等高（高 ${weeklyLayout.heights.join(' / ')}px）`)

    if (process.argv.includes('--shots')) {
      const shot = await ws.send('Page.captureScreenshot', { format: 'png' })
      writeFileSync(join(TMP, 'weekly-report.png'), Buffer.from(shot.data, 'base64'))
      console.log(`  截图 → ${join(TMP, 'weekly-report.png')}`)
    }
  } catch (e) {
    try {
      const diag = await evaluate(ws, `location.href + ' | ' + document.body.innerText.slice(0, 300)`)
      console.error(`[诊断] ${diag}`)
      if (ws.logs.length) console.error(`[诊断] 控制台告警：\n${ws.logs.slice(-5).join('\n')}`)
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
