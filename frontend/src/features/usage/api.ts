// CAP-67 用量统计 API：四端点全只读。时段用 ISO 时间串（from 含/to 不含）；
// 非 admin 服务端强制本人，userId 仅 admin 传入生效。
import { api } from '../../shared/api/client'
import type {
  PageView,
  UsageBreakdownRow,
  UsageDailyPoint,
  UsageDim,
  UsageHourlyPoint,
  UsageRecordRow,
  UsageSummary,
  UsageTopRow,
} from './types'

export interface UsageFilter {
  from?: string
  to?: string
  userId?: string
  model?: string
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
  api.get<UsageSummary>(`/usage/summary${query({ from: f.from, to: f.to, userId: f.userId, model: f.model })}`)

export const getUsageBreakdown = (dim: UsageDim, f: UsageFilter = {}) =>
  api.get<UsageBreakdownRow[]>(
    `/usage/breakdown${query({ dim, from: f.from, to: f.to, userId: f.userId, model: f.model })}`,
  )

export const getUsageDaily = (days: number, f: UsageFilter = {}) =>
  api.get<UsageDailyPoint[]>(`/usage/daily${query({ days, userId: f.userId, model: f.model })}`)

export const getUsageTop = (limit: number, f: UsageFilter = {}) =>
  api.get<UsageTopRow[]>(
    `/usage/top${query({ limit, from: f.from, to: f.to, userId: f.userId, model: f.model })}`,
  )

export interface UsageRecordsQuery extends UsageFilter {
  page?: number
  size?: number
  source?: 'SESSION' | 'CHAT'
}

/** 请求日志：逐回合账本服务端分页（账本只含上线后的新数据） */
export const getUsageRecords = (q: UsageRecordsQuery = {}) =>
  api.get<PageView<UsageRecordRow>>(
    `/usage/records${query({
      page: q.page ?? 0,
      size: q.size ?? 20,
      source: q.source,
      model: q.model,
      from: q.from,
      to: q.to,
      userId: q.userId,
    })}`,
  )

/** 今日按小时趋势（服务器时区 24 桶含零桶） */
export const getUsageHourly = (f: UsageFilter = {}) =>
  api.get<UsageHourlyPoint[]>(`/usage/hourly${query({ userId: f.userId, model: f.model })}`)
