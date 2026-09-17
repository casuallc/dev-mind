// CAP-45 验证用飞书开放平台 mock：tenant_access_token / wiki get_node / docx 文档+blocks / 旧版 doc raw_content。
// Node 内置模块，无依赖。用法: node feishu-mock.js <port>
// 控制面（非 /open-apis 路径）：
//   POST /__set_docx  {token, title?, paragraphs[]}  —— 重建 docx 文档（blocks 由段落生成）
//   POST /__set_doc   {token, content}               —— 设置旧版 doc 纯文本
//   POST /__fail      {token}                        —— 之后该 token 的拉取返回业务错误（模拟无权限/已删）
const http = require('http')
const PORT = Number(process.argv[2] || 18191)

const APP_ID = 'cli_e2e'
const APP_SECRET = 'secret_e2e'

// docx 文档仓：token → {title, paragraphs[]}
const docxDocs = {
  'doc-token-1': {
    title: 'E2E 飞书前端规范',
    paragraphs: ['前端构建统一使用 Vite，产物输出 dist 目录。', '组件样式使用 CSS Modules。'],
  },
  'doc-token-wiki': {
    title: 'E2E Wiki 发布流程',
    paragraphs: ['发版前必须跑全量回归测试。'],
  },
}
// wiki 节点仓：nodeToken → {objType, objToken, title}
const wikiNodes = {
  'wiki-node-1': { objType: 'docx', objToken: 'doc-token-wiki', title: 'E2E Wiki 发布流程' },
}
// 旧版 doc 仓：token → 纯文本
const oldDocs = {
  'old-doc-1': '旧版文档纯文本：数据库每日全量备份，保留 30 天。',
}
const failing = new Set()

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

function readBody(req) {
  return new Promise(resolve => {
    let buf = ''
    req.on('data', c => (buf += c))
    req.on('end', () => {
      try { resolve(JSON.parse(buf || '{}')) } catch { resolve({}) }
    })
  })
}

/** 段落数组 → docx blocks（page + heading1 + 每段一个 text 块，含一个 bullet 覆盖列表渲染） */
function docxBlocks(title, paragraphs) {
  const blocks = [
    { block_id: 'page', block_type: 1, page: { elements: [{ text_run: { content: title, text_element_style: {} } }] } },
    { block_id: 'h1', block_type: 3, heading1: { elements: [{ text_run: { content: title, text_element_style: {} } }] } },
  ]
  paragraphs.forEach((p, i) => {
    blocks.push({
      block_id: 'p' + i,
      block_type: 2,
      text: { elements: [{ text_run: { content: p, text_element_style: {} } }] },
    })
  })
  blocks.push({
    block_id: 'b0',
    block_type: 12,
    bullet: { elements: [{ text_run: { content: '列表项：遵循规范', text_element_style: {} } }] },
  })
  return blocks
}

function bizErr(res, msg) {
  // 飞书业务错误：HTTP 200 + code != 0
  json(res, 200, { code: 99991663, msg })
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname
  const method = req.method

  // ---------- 控制面 ----------
  if (method === 'POST' && path === '/__set_docx') {
    const b = await readBody(req)
    docxDocs[b.token] = { title: b.title ?? (docxDocs[b.token] || {}).title ?? b.token, paragraphs: b.paragraphs ?? [] }
    return json(res, 200, { ok: true })
  }
  if (method === 'POST' && path === '/__set_doc') {
    const b = await readBody(req)
    oldDocs[b.token] = b.content ?? ''
    return json(res, 200, { ok: true })
  }
  if (method === 'POST' && path === '/__fail') {
    const b = await readBody(req)
    failing.add(b.token)
    return json(res, 200, { ok: true })
  }

  // ---------- 飞书开放接口 ----------
  if (method === 'POST' && path === '/open-apis/auth/v3/tenant_access_token/internal') {
    const b = await readBody(req)
    if (b.app_id !== APP_ID || b.app_secret !== APP_SECRET) {
      return json(res, 200, { code: 10003, msg: 'app_id or app_secret invalid' })
    }
    return json(res, 200, { code: 0, msg: 'ok', tenant_access_token: 'mock-tenant-token', expire: 7200 })
  }

  // 以下接口要求 Bearer（mock 只校验存在性）
  if (!req.headers['authorization']) {
    return json(res, 401, { code: 99991661, msg: 'missing access token' })
  }

  if (method === 'GET' && path === '/open-apis/wiki/v2/spaces/get_node') {
    const token = url.searchParams.get('token')
    if (failing.has(token)) return bizErr(res, 'no permission')
    const node = wikiNodes[token]
    if (!node) return bizErr(res, 'node not found')
    return json(res, 200, { code: 0, msg: 'ok', data: { node: { obj_type: node.objType, obj_token: node.objToken, title: node.title } } })
  }

  let m = path.match(/^\/open-apis\/docx\/v1\/documents\/([^/]+)$/)
  if (method === 'GET' && m) {
    const token = m[1]
    if (failing.has(token)) return bizErr(res, 'no permission')
    const doc = docxDocs[token]
    if (!doc) return bizErr(res, 'document not found')
    return json(res, 200, { code: 0, msg: 'ok', data: { document: { title: doc.title } } })
  }

  m = path.match(/^\/open-apis\/docx\/v1\/documents\/([^/]+)\/blocks$/)
  if (method === 'GET' && m) {
    const token = m[1]
    if (failing.has(token)) return bizErr(res, 'no permission')
    const doc = docxDocs[token]
    if (!doc) return bizErr(res, 'document not found')
    return json(res, 200, {
      code: 0, msg: 'ok',
      data: { items: docxBlocks(doc.title, doc.paragraphs), has_more: false },
    })
  }

  m = path.match(/^\/open-apis\/doc\/v2\/([^/]+)\/raw_content$/)
  if (method === 'GET' && m) {
    const token = m[1]
    if (failing.has(token)) return bizErr(res, 'no permission')
    if (!(token in oldDocs)) return bizErr(res, 'doc not found')
    return json(res, 200, { code: 0, msg: 'ok', data: { content: oldDocs[token] } })
  }

  json(res, 404, { code: 404, msg: 'unknown mock path: ' + path })
})

server.listen(PORT, '127.0.0.1', () => console.log(`feishu-mock listening on ${PORT}`))
