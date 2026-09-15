// CAP-10 验证用业务 API mock（被测服务）：提供健康检查、CRUD、query 参数、鉴权端点。
// Node 内置模块，无依赖。用法: node api-mock.js <port>
const http = require('http')
const PORT = Number(process.argv[2] || 9300)
const TOKEN = 'tok-secret'

const users = [
  { id: 1, name: 'alice' },
  { id: 2, name: 'bob' },
]

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json' })
  res.end(JSON.stringify(body))
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const path = url.pathname
  const method = req.method

  // 鉴权端点：无 token → 401
  if (path === '/api/private') {
    if (req.headers['authorization'] !== `Bearer ${TOKEN}`) {
      return json(res, 401, { error: 'unauthorized' })
    }
    return json(res, 200, { secret: 'top-secret' })
  }

  if (method === 'GET' && path === '/api/health') {
    return json(res, 200, { status: 'ok' })
  }
  if (method === 'GET' && path === '/api/users') {
    return json(res, 200, users)
  }
  if (method === 'GET' && path.startsWith('/api/users/')) {
    const id = Number(path.slice('/api/users/'.length))
    const u = users.find(x => x.id === id)
    if (!u) return json(res, 404, { error: 'not found' })
    return json(res, 200, u)
  }
  if (method === 'POST' && path === '/api/users') {
    let raw = ''
    req.on('data', c => { raw += c })
    req.on('end', () => {
      try {
        const body = JSON.parse(raw || '{}')
        const u = { id: users.length + 1, name: body.name || 'unknown' }
        users.push(u)
        json(res, 201, u)
      } catch (e) {
        json(res, 400, { error: 'bad json' })
      }
    })
    return
  }
  if (method === 'GET' && path === '/api/pets') {
    const name = url.searchParams.get('name') || ''
    return json(res, 200, { pets: name ? [name] : [] })
  }
  if (method === 'GET' && path === '/api/echo') {
    return json(res, 200, { echo: url.searchParams.get('msg') || '' })
  }
  if (method === 'GET' && path === '/api/slow') {
    setTimeout(() => json(res, 200, { slow: true }), 800)
    return
  }
  json(res, 404, { error: `no route ${method} ${path}` })
})

server.listen(PORT, '127.0.0.1', () => {
  console.log(`api-mock ready on ${PORT}`)
})
