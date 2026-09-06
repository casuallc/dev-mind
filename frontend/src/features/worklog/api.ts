// CAP-28 个人工作日志与工时管理 API
import { api } from '../../shared/api/client'
import type {
  DailyReport,
  EntryPayload,
  GenerateAck,
  GitPreview,
  WeeklyReport,
  WorklogEntry,
  WorklogRepo,
  WorklogSettings,
} from './types'

// ---- 全局代码仓库（CAP-29 起登记在 /admin/repos；此处仅列表 + 本人订阅勾选） ----
export const listRepos = () => api.get<WorklogRepo[]>('/worklog/repos')
export const setSubscription = (id: number, subscribed: boolean) =>
  api.put(`/worklog/repos/${id}/subscription`, { subscribed })

// ---- 工作条目 ----
export const listEntries = (from: string, to: string) =>
  api.get<WorklogEntry[]>(`/worklog/entries?from=${from}&to=${to}`)
export const createEntry = (body: EntryPayload) => api.post<WorklogEntry>('/worklog/entries', body)
export const updateEntry = (id: number, body: EntryPayload) =>
  api.put<WorklogEntry>(`/worklog/entries/${id}`, body)
export const deleteEntry = (id: number) => api.del(`/worklog/entries/${id}`)

// ---- git 扫描导入 ----
export const previewGit = (date: string) => api.get<GitPreview>(`/worklog/git/preview?date=${date}`)
export const importGit = (date: string, items: { repoId: number; commitSha: string; subject: string; hours?: number }[]) =>
  api.post<{ created: number; skipped: number }>('/worklog/git/import', { date, items })

// ---- 日报 / 周报 ----
export const getDaily = (date: string) => api.get<DailyReport | undefined>(`/worklog/daily?date=${date}`)
export const generateDaily = (date: string, force = false) =>
  api.post<GenerateAck>('/worklog/daily/generate', { date, force })
export const updateDaily = (id: number, body: { contentMd?: string; status?: string }) =>
  api.put<DailyReport>(`/worklog/daily/${id}`, body)

export const getWeekly = (weekStart: string) =>
  api.get<WeeklyReport | undefined>(`/worklog/weekly?weekStart=${weekStart}`)
export const generateWeekly = (weekStart: string, force = false) =>
  api.post<GenerateAck>('/worklog/weekly/generate', { weekStart, force })
export const updateWeekly = (id: number, body: { summaryMd?: string; nextPlanMd?: string; status?: string }) =>
  api.put<WeeklyReport>(`/worklog/weekly/${id}`, body)

// ---- 个人设置 ----
export const getSettings = () => api.get<WorklogSettings>('/worklog/settings')
export const updateSettings = (body: Partial<WorklogSettings>) =>
  api.put<WorklogSettings>('/worklog/settings', body)
