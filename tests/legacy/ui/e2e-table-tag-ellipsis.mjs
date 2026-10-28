// 表格内 Tag 溢出（执行节点列）CDP 真浏览器验证：
//   1) 备数据：超长名节点（真 runner executor=fake 上线）+ LOCAL 项目默认节点指向它；
//   2) 无头 Chrome 打开 /admin/projects，断言执行节点列 Tag 被省略号截断：
//      computed text-overflow=ellipsis、Tag 内容不再溢出单元格（tag.right ≤ td.right）、
//      td 自身 scrollWidth 不超宽（td 默认 overflow:visible，修复前长 Tag 会越界压右列）；
//   3) 悬停 Tag → Tooltip 展示完整节点名。
// 前置：app 隔离实例 + 前端 dev 同时起（参考下面用法），runner jar 已构建（mvn -q install -DskipTests）。
// 用法：
//   mvn -pl devmind-app spring-boot:run -Dspring-boot.run.profiles=e2e \
//     "-Dspring-boot.run.arguments=--server.port=18102 --spring.datasource.url=jdbc:h2:file:<tmp>/db/devmind;AUTO_SERVER=TRUE \
//      --devmind.cors.allowed-origins=http://localhost:5199 --devmind.project.workspace-root=<tmp>/repositories"
//   VITE_BACKEND_URL=http://localhost:18102 npm run dev -- --port 5199 --strictPort   # frontend/ 下
//   node tests/legacy/ui/e2e-table-tag-ellipsis.mjs   （API/BASE/CHROME_PATH 可覆盖）
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '../../..')
const BASE = process.env.BASE ?? 'http://localhost:5199'
const API = process.env.API ?? 'http://localhost:18102'
const USER = process.env.USER ?? 'admin'
const PASS = process.env.PASS ?? 'admin123'
const CHROME = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const JAVA_HOME = process.env.JAVA_HOME ?? 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot'
const JAVA = join(JAVA_HOME, 'bin', 'java.exe')
const RUNNER_JAR = join(ROOT, 'devmind-agent-runner', 'target', 'devmind-agent-runner.jar')
const PORT = 9468
const TMP = join(ROOT, 'tmp', 'tag-ellipsis-ui')
const PROFILE = join(TMP, 'chrome')
const TS = Date.now().toString(36)
const NODE_NAME = `kingdee-win-194-超长节点名称溢出校验-${TS}`
const PROJ_NAME = `tag溢出校验-${TS}`

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
let passed = 0
function ok(cond, what) {
  if (!cond) throw new Error(`断言失败：${what}`)
  passed++
  console.log(`  ✔ ${what}`)
}

async function api(method, path, body, token, expect = 200) {
  const res = await fetch(`${API}/api${path}`, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (res.status !== expect) throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 300)}`)
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

let chrome, runner, nodeId, projectId
async function main() {
  if (!existsSync(RUNNER_JAR)) throw new Error('runner jar 缺失，先 mvn -q install -DskipTests')
  rmSync(TMP, { recursive: true, force: true })
  mkdirSync(TMP, { recursive: true })

  // ── 0. 登录 + 备数据（超长名节点 + LOCAL 项目）──────────────
  const login = await api('POST', '/auth/login', { username: USER, password: PASS })
  const { accessToken, refreshToken, user } = login

  const repoDir = join(TMP, 'repo')
  mkdirSync(repoDir, { recursive: true })
  spawnSync('git', ['init', '-b', 'main'], { cwd: repoDir })
  writeFileSync(join(repoDir, 'README.md'), '# tag ellipsis e2e\n')
  spawnSync('git', ['add', '.'], { cwd: repoDir })
  spawnSync('git', ['-c', 'user.name=e2e', '-c', 'user.email=e2e@t', 'commit', '-m', 'init'], { cwd: repoDir })

  const issued = await api('POST', '/agent-nodes', { name: NODE_NAME }, accessToken)
  const node = issued.node
  nodeId = node.id
  const props = join(TMP, 'agent.properties')
  writeFileSync(props, [
    `serverUrl=ws://localhost:${new URL(API).port}/ws/agent`,
    `token=${issued.token}`,
    'executor=fake',
    // 本机常驻 runner 服务已占默认 gitProxyPort（18377），本用例不需要 git 代理
    'gitProxyEnabled=false',
    `workDir=${join(TMP, 'runner-work').replaceAll('\\', '/')}`,
    `workspaceRoot=${join(TMP, 'ws').replaceAll('\\', '/')}`,
    'maxConcurrent=1',
    '',
  ].join('\n'))
  runner = spawn(JAVA, ['-jar', RUNNER_JAR, props], { stdio: 'ignore' })
  await waitFor(async () => {
    const nodes = await api('GET', '/agent-nodes', undefined, accessToken)
    return nodes.find((n) => n.id === nodeId && n.status === 'ONLINE')
  }, '节点上线', 40000)
  console.log(`[0] 节点上线：${NODE_NAME}（id=${nodeId}）`)

  const proj = await api('POST', '/projects', {
    name: PROJ_NAME, path: repoDir, sourceType: 'LOCAL', defaultBranch: 'main',
    agentNodeId: String(nodeId),
  }, accessToken)
  projectId = proj.id
  console.log(`[0] 项目就绪：${PROJ_NAME}（id=${projectId}，默认节点=${nodeId}）`)

  // ── 1. 起 headless Chrome ────────────────────────────────
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
    await evaluate(ws, `(() => {
      localStorage.setItem('devmind.accessToken', ${JSON.stringify(accessToken)});
      localStorage.setItem('devmind.refreshToken', ${JSON.stringify(refreshToken)});
      localStorage.setItem('devmind.user', ${JSON.stringify(JSON.stringify(user))});
      return 'ok';
    })()`)
    await ws.send('Page.navigate', { url: `${BASE}/admin/projects` })

    // 找到「执行节点」列里渲染我们超长节点名的那个 Tag
    const tagJs = `(() => {
      const tags = [...document.querySelectorAll('.ant-table td .ant-tag')];
      return tags.find((t) => t.textContent.includes(${JSON.stringify(NODE_NAME.slice(0, 20))})) ?? null;
    })()`
    await waitFor(() => evaluate(ws, `!!${tagJs}`), '项目表执行节点列 Tag 渲染', 30000)

    // ── 2. 截断断言 ─────────────────────────────────────────
    ok(await evaluate(ws, `(() => {
      const t = ${tagJs};
      const cs = getComputedStyle(t);
      return cs.textOverflow === 'ellipsis' && cs.overflow === 'hidden' && cs.whiteSpace === 'nowrap';
    })()`), '表格内 Tag 计算样式：ellipsis + hidden + nowrap（index.css 全局兜底生效）')

    const rect = await evaluate(ws, `(() => {
      const t = ${tagJs};
      const td = t.closest('td');
      const tr = t.getBoundingClientRect(), dr = td.getBoundingClientRect();
      return { tagRight: tr.right, tdRight: dr.right, tdOverflow: td.scrollWidth - td.clientWidth,
               tagClient: t.clientWidth, tagScroll: t.scrollWidth };
    })()`)
    ok(rect.tagRight <= rect.tdRight + 1, `Tag 右缘不越出单元格（tag.right=${rect.tagRight} ≤ td.right=${rect.tdRight}）`)
    ok(rect.tdOverflow <= 1, `单元格自身无横向溢出（td.scrollWidth-clientWidth=${rect.tdOverflow}）`)
    ok(rect.tagScroll > rect.tagClient, `节点名确实被截断（scrollWidth=${rect.tagScroll} > clientWidth=${rect.tagClient}，即修复前必溢出）`)

    // ── 3. Tooltip 展示完整名 ───────────────────────────────
    const center = await evaluate(ws, `(() => {
      const r = ${tagJs}.getBoundingClientRect();
      return { x: Math.round(r.x + r.width / 2), y: Math.round(r.y + r.height / 2) };
    })()`)
    await ws.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: center.x - 60, y: center.y - 60 })
    await sleep(150)
    await ws.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: center.x, y: center.y })
    const tip = await waitFor(() => evaluate(ws, `(() => {
      const el = [...document.querySelectorAll('.ant-tooltip:not(.ant-tooltip-hidden)')]
        .find((x) => x.textContent.includes(${JSON.stringify(NODE_NAME)}));
      return el ? el.textContent.trim() : null;
    })()`), '悬停出现完整节点名 Tooltip', 8000)
    ok(tip === NODE_NAME, `Tooltip 展示完整节点名「${NODE_NAME}」`)

    console.log(`\n[结果] ${passed} 项断言全部通过`)
  } finally {
    ws.close()
  }
}

main().finally(async () => {
  chrome?.kill()
  runner?.kill()
  try {
    const login = await api('POST', '/auth/login', { username: USER, password: PASS })
    if (projectId) await api('DELETE', `/projects/${projectId}`, undefined, login.accessToken)
    if (nodeId) await api('DELETE', `/agent-nodes/${nodeId}`, undefined, login.accessToken)
    console.log('[清理] 项目与节点已删除')
  } catch { /* 已删或实例已停 */ }
}).catch((e) => {
  console.error(e.message ?? e)
  process.exit(1)
})
