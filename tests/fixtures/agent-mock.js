// CAP-07 验证用 mock Server Agent（Node 内置模块，无依赖）。
// 协议：GET /api/agent/ping | POST /api/agent/exec | /api/agent/health | /api/agent/upload | GET /api/agent/download
// 鉴权：Bearer tok123（验错返回 401）
const http = require('http')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { execFile } = require('child_process')

const PORT = Number(process.argv[2] || 9100)
const TOKEN = process.argv[3] || 'tok123'

function auth(req, res) {
  const h = req.headers['authorization'] || ''
  if (h !== `Bearer ${TOKEN}`) {
    res.writeHead(401, { 'Content-Type': 'application/json' })
    res.end(JSON.stringify({ error: 'unauthorized' }))
    return false
  }
  return true
}

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

// 写临时 .sh 并用 bash 执行，回传 exitCode/stdout/stderr
function runScript(script, cb) {
  const tmp = path.join(os.tmpdir(), `devmind-agent-${Date.now()}-${Math.random().toString(16).slice(2)}.sh`)
  fs.writeFileSync(tmp, script)
  execFile('bash', [tmp], { timeout: 30000, maxBuffer: 10 * 1024 * 1024 }, (err, stdout, stderr) => {
    fs.unlink(tmp, () => {})
    const exitCode = err ? (typeof err.code === 'number' ? err.code : 1) : 0
    cb({ exitCode, stdout: stdout || '', stderr: stderr || '' })
  })
}

// 极简 multipart 解析（仅 file 与 remotePath 两个 part）
function parseMultipart(raw, boundary) {
  const result = { file: null, remotePath: null }
  const parts = raw.toString('binary').split(`--${boundary}`)
  for (const part of parts) {
    const headerEnd = part.indexOf('\r\n\r\n')
    if (headerEnd < 0) continue
    const header = part.slice(0, headerEnd)
    const body = part.slice(headerEnd + 4)
    if (header.includes('name="file"')) {
      const contentStart = body.indexOf('\r\n\r\n')
      const content = contentStart >= 0 ? body.slice(contentStart + 4) : body
      // 去掉尾部 \r\n--boundary
      result.file = Buffer.from(content.replace(/\r\n$/, ''), 'binary')
    } else if (header.includes('name="remotePath"')) {
      result.remotePath = body.replace(/\r\n/g, '').replace(/--$/, '').trim()
    }
  }
  return result
}

const server = http.createServer((req, res) => {
  const url = req.url || ''
  // CORS for dev frontend
  res.setHeader('Access-Control-Allow-Origin', '*')

  if (req.method === 'GET' && url === '/api/agent/ping') {
    if (!auth(req, res)) return
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('pong')
    return
  }

  if (req.method === 'GET' && url === '/api/agent/healthz') {
    // 免鉴权健康探针（供 url 模式健康检查探测）
    res.writeHead(200, { 'Content-Type': 'text/plain' })
    res.end('ok')
    return
  }

  if (req.method === 'GET' && url.startsWith('/api/agent/download')) {
    if (!auth(req, res)) return
    const p = decodeURIComponent(new URL(url, 'http://x').searchParams.get('path') || '')
    try {
      const text = fs.readFileSync(p, 'utf8')
      res.writeHead(200, { 'Content-Type': 'text/plain' })
      res.end(text)
    } catch (e) {
      res.writeHead(404, { 'Content-Type': 'text/plain' })
      res.end('not found: ' + e.message)
    }
    return
  }

  if (req.method === 'POST') {
    const chunks = []
    req.on('data', (c) => chunks.push(c))
    req.on('end', () => {
      const raw = Buffer.concat(chunks)

      if (url === '/api/agent/exec') {
        if (!auth(req, res)) return
        let body = {}
        try { body = JSON.parse(raw.toString('utf8')) } catch { }
        runScript(body.command || '', (r) => json(res, 200, r))
        return
      }

      if (url === '/api/agent/health') {
        if (!auth(req, res)) return
        let body = {}
        try { body = JSON.parse(raw.toString('utf8')) } catch { }
        if (body.command) {
          runScript(body.command, (r) => json(res, 200, { ok: r.exitCode === 0, message: r.exitCode === 0 ? '健康检查通过' : '健康检查失败: ' + (r.stderr || r.stdout).trim().slice(0, 200) }))
        } else if (body.url) {
          const expected = body.expectedStatus || 200
          http.get(body.url, (r2) => {
            r2.resume()
            r2.on('end', () => json(res, 200, { ok: r2.statusCode === expected, message: `HTTP ${r2.statusCode}` }))
          }).on('error', (e) => json(res, 200, { ok: false, message: '连接失败: ' + e.message }))
        } else {
          json(res, 200, { ok: false, message: '缺 url 或 command' })
        }
        return
      }

      if (url === '/api/agent/upload') {
        if (!auth(req, res)) return
        const ct = req.headers['content-type'] || ''
        const m = ct.match(/boundary=([^;]+)/)
        if (!m) { json(res, 400, { error: 'no boundary' }); return }
        const { file, remotePath } = parseMultipart(raw, m[1])
        if (!file || !remotePath) { json(res, 400, { error: '缺 file 或 remotePath' }); return }
        fs.mkdirSync(path.dirname(remotePath), { recursive: true })
        fs.writeFileSync(remotePath, file)
        json(res, 200, { ok: true, message: 'uploaded to ' + remotePath })
        return
      }

      json(res, 404, { error: 'not found: ' + url })
    })
    return
  }

  res.writeHead(405)
  res.end('method not allowed')
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`AGENT_READY port=${PORT} token=${TOKEN}`)
})
