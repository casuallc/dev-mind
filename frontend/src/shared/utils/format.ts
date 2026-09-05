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

/** 秒数 → '45m' / '2h' / '2h30m'，空值显示 '-'（CAP-27 工时展示） */
export function fmtDuration(sec: number | null | undefined): string {
  if (sec == null || sec <= 0) return '-'
  const h = Math.floor(sec / 3600)
  const m = Math.round((sec % 3600) / 60)
  if (h === 0) return `${m}m`
  if (m === 0 || m === 60) return `${m === 60 ? h + 1 : h}h`
  return `${h}h${m}m`
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
