// CAP-67 用量统计 API：四端点全只读。时段用 ISO 时间串（from 含/to 不含）；
// 非 admin 服务端强制本人，userId 仅 admin 传入生效。
import { api } from '../../shared/api/client'
import type { UsageBreakdownRow, UsageDailyPoint, UsageDim, UsageSummary, UsageTopRow } from './types'

export interface UsageFilter {
  from?: string
  to?: string
  userId?: string
}

function query(p: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(p)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  const s = q.toString()
  return s ? `?${s}` : ''
}

export const getUsageSummary = (f: UsageFilter = {}) =>
  api.get<UsageSummary>(`/usage/summary${query({ from: f.from, to: f.to, userId: f.userId })}`)

export const getUsageBreakdown = (dim: UsageDim, f: UsageFilter = {}) =>
  api.get<UsageBreakdownRow[]>(`/usage/breakdown${query({ dim, from: f.from, to: f.to, userId: f.userId })}`)

export const getUsageDaily = (days: number, f: UsageFilter = {}) =>
  api.get<UsageDailyPoint[]>(`/usage/daily${query({ days, userId: f.userId })}`)

export const getUsageTop = (limit: number, f: UsageFilter = {}) =>
  api.get<UsageTopRow[]>(`/usage/top${query({ limit, from: f.from, to: f.to, userId: f.userId })}`)
