// 共享格式化工具：test/deploy/build 等页面统一使用，避免各抄一份。

/** ISO 时间 → 'YYYY-MM-DD HH:mm:ss'，空值显示 '-' */
export function fmtTime(s: string | null | undefined): string {
  if (!s) return '-'
  const d = new Date(s)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 两个 ISO 时间差 → '123ms' / '42s' / '3m 5s' */
export function durationMs(a: string | null | undefined, b: string | null | undefined): string {
  if (!a || !b) return '-'
  const ms = new Date(b).getTime() - new Date(a).getTime()
  if (ms < 1000) return `${ms}ms`
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s}s`
  return `${Math.floor(s / 60)}m ${s % 60}s`
}

/** 秒数 → '45m' / '2h' / '2h30m'，空值显示 '-'（CAP-27 工时展示） */
export function fmtDuration(sec: number | null | undefined): string {
  if (sec == null || sec <= 0) return '-'
  const h = Math.floor(sec / 3600)
  const m = Math.round((sec % 3600) / 60)
  if (h === 0) return `${m}m`
  if (m === 0 || m === 60) return `${m === 60 ? h + 1 : h}h`
  return `${h}h${m}m`
}

/** 字节数 → '512B' / '3.2MB' / '1.5GB'，空值显示 '-'（CAP-34 FR-05 节点工作区占用） */
export function fmtBytes(n: number | null | undefined): string {
  if (n == null || n < 0) return '-'
  if (n < 1024) return `${n}B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let v = n
  let u = -1
  do {
    v /= 1024
    u++
  } while (v >= 1024 && u < units.length - 1)
  return `${v >= 100 ? Math.round(v) : v.toFixed(1)}${units[u]}`
}

/** token 数 → '980' / '1.2k' / '3.4M'（用量账本） */
export function fmtTokens(n: number | null | undefined): string {
  if (n == null || n <= 0) return '0'
  if (n < 1000) return `${n}`
  if (n < 1_000_000) return `${(n / 1000).toFixed(1).replace(/\.0$/, '')}k`
  return `${(n / 1_000_000).toFixed(1).replace(/\.0$/, '')}M`
}

/** 美元成本 → '$0.0123' / '$1.24'（用量账本；小额保 4 位，避免一片 $0.00） */
export function fmtCost(n: number | null | undefined): string {
  if (n == null) return '-'
  if (n <= 0) return '$0'
  if (n < 0.01) return `$${n.toFixed(4)}`
  return `$${n.toFixed(2)}`
}

/** 会话/问答用量一句话 → '3 回合 · $0.04 · 1.5k tok'；无任何记录返回 ''（历史行不显示） */
export function usageText(u: {
  turnCount?: number | null
  costUsd?: number | null
  inputTokens?: number | null
  outputTokens?: number | null
}): string {
  const turns = u.turnCount ?? 0
  const tokens = (u.inputTokens ?? 0) + (u.outputTokens ?? 0)
  if (turns <= 0 && (u.costUsd == null || u.costUsd <= 0) && tokens <= 0) return ''
  const parts: string[] = []
  if (turns > 0) parts.push(`${turns} 回合`)
  if (u.costUsd != null && u.costUsd > 0) parts.push(fmtCost(u.costUsd))
  if (tokens > 0) parts.push(`${fmtTokens(tokens)} tok`)
  return parts.join(' · ')
}

/** Record → 每行 k=v 文本（表单编辑用） */export const paramsToText = (p: Record<string, string> | undefined): string =>
  Object.entries(p ?? {}).map(([k, v]) => `${k}=${v}`).join('\n')

/** 每行 k=v 文本 → Record */
export const textToParams = (t: string): Record<string, string> => {
  const out: Record<string, string> = {}
  t.split('\n').map((l) => l.trim()).filter(Boolean).forEach((l) => {
    const i = l.indexOf('=')
    if (i > 0) out[l.slice(0, i).trim()] = l.slice(i + 1).trim()
  })
  return out
}
