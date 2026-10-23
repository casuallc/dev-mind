// CAP-85 FR-30 检索评测脚本：登录 → 调 ADMIN 评测接口 → 打印指标并可选落盘。
//
// 用法：
//   node tests/knowledge_eval/run-eval.mjs
//   node tests/knowledge_eval/run-eval.mjs --topK=10 --kbId=3 --out=eval-before.json
//
// 环境变量（默认值对齐本地 e2e 实例）：
//   DEVMIND_BASE=http://localhost:18090
//   DEVMIND_USER=admin  DEVMIND_PASS=admin123
//
// 改动索引/embedding/阈值前后各跑一次、保存两份 JSON，即可做指标差异对比。

const args = process.argv.slice(2)
const opt = (name, fallback) => {
  const hit = args.find((a) => a.startsWith(`--${name}=`))
  return hit ? hit.slice(name.length + 3) : fallback
}

const BASE = (process.env.DEVMIND_BASE || 'http://localhost:18090').replace(/\/+$/, '')
const USER = process.env.DEVMIND_USER || 'admin'
const PASS = process.env.DEVMIND_PASS || 'admin123'
const topK = opt('topK', process.env.DEVMIND_EVAL_TOPK || '')
const kbId = opt('kbId', process.env.DEVMIND_EVAL_KB || '')
const out = opt('out', '')

async function request(method, path, body, token) {
  const res = await fetch(`${BASE}/api${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`${method} ${path} → ${res.status} ${text.slice(0, 400)}`)
  }
  return text ? JSON.parse(text) : null
}

function percent(v) {
  return `${(Number(v) * 100).toFixed(1)}%`
}

async function main() {
  const login = await request('POST', '/auth/login', { username: USER, password: PASS })
  if (!login?.accessToken) {
    throw new Error('登录未拿到 accessToken')
  }
  const query = new URLSearchParams()
  if (topK) query.set('topK', topK)
  if (kbId) query.set('kbId', kbId)
  const path = `/knowledge/admin/eval/report${query.toString() ? `?${query}` : ''}`
  const report = await request('GET', path, undefined, login.accessToken)

  console.error(`数据集 ${report.dataset}｜用例 ${report.caseCount}（可回答 ${report.answerableCount} / 不可回答 ${report.unanswerableCount}）`)
  console.error(`Recall@${report.topK}=${percent(report.recallAtK)}  MRR=${Number(report.mrr).toFixed(3)}  `
    + `引用准确率=${percent(report.citationPrecision)}  拒答率=${percent(report.refusalRate)}  降级=${report.degradedReason}`)
  for (const c of report.cases || []) {
    const mark = c.answerable ? (c.hit ? 'OK ' : 'MISS') : (c.refused ? 'REF' : 'ANS')
    console.error(`  ${mark} ${c.id}  ${c.question}  首命中=${c.firstRelevantRank || '-'}  ${percent(c.citationPrecision)}`)
  }

  console.log(JSON.stringify(report, null, 2))
  if (out) {
    const { writeFileSync } = await import('node:fs')
    writeFileSync(out, JSON.stringify(report, null, 2), 'utf8')
    console.error(`已保存 ${out}`)
  }
}

main().catch((e) => {
  console.error(`评测失败: ${e.message}`)
  process.exit(1)
})
