// CAP-55 决策记录查询与训练集导出。分页口径与全平台一致：page 从 0 起、size 上限 200。
import { api } from '../../shared/api/client'
import { getAccessToken } from '../auth/authStore'
import type { DecisionRecord, DecisionRecordDetail } from './types'

/** 服务端分页响应（PageView 的前端投影） */
export interface DecisionRecordPage {
  items: DecisionRecord[]
  total: number
  page: number
  size: number
}

export function listDecisionRecords(params: {
  capability?: string
  since?: string
  page?: number
  size?: number
} = {}): Promise<DecisionRecordPage> {
  const q = new URLSearchParams()
  if (params.capability) q.set('capability', params.capability)
  if (params.since) q.set('since', params.since)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  return api.get<DecisionRecordPage>(`/decision/records?${q.toString()}`)
}

export const getDecisionRecord = (id: number) =>
  api.get<DecisionRecordDetail>(`/decision/records/${id}`)

/**
 * 导出 laya 训练 JSONL。api client 只解 JSON，二进制走原生 fetch + blob；
 * 文件名用后端给的 Content-Disposition（带能力与时间戳，导多次不会互相覆盖），
 * 拿不到时回落到固定名。
 */
export async function exportDecisionRecords(params: { capability?: string; since?: string } = {}): Promise<string> {
  const q = new URLSearchParams()
  if (params.capability) q.set('capability', params.capability)
  if (params.since) q.set('since', params.since)
  const res = await fetch(`/api/decision/records/export?${q.toString()}`, {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  if (!res.ok) throw new Error(`导出失败: ${res.status}`)
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filenameOf(res.headers.get('Content-Disposition')) || 'decision-records.jsonl'
  a.click()
  URL.revokeObjectURL(a.href)
  return a.download
}

/** 从 Content-Disposition 取文件名：`attachment; filename="…"` / `filename*=UTF-8''…` 两种形态都认 */
function filenameOf(header: string | null): string {
  if (!header) return ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim())
    } catch {
      return star[1].trim()
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1].trim() : ''
}
