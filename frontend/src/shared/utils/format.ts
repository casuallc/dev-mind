// 共享格式化工具：test/deploy/build/server-adapter 等页面统一使用，避免各抄一份。

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

/** Record → 每行 k=v 文本（表单编辑用） */
export const paramsToText = (p: Record<string, string> | undefined): string =>
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
