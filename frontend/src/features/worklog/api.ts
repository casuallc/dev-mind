// CAP-28 个人工作日志与工时管理 API
import { api } from '../../shared/api/client'
import type {
  DailyReport,
  EntryPage,
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
export const listEntries = (from: string, to: string, page = 0, size = 20, keyword?: string) =>
  api.get<EntryPage>(
    `/worklog/entries?from=${from}&to=${to}&page=${page}&size=${size}` +
      (keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''),
  )
export const createEntry = (body: EntryPayload) => api.post<WorklogEntry>('/worklog/entries', body)
export const updateEntry = (id: number, body: EntryPayload) =>
  api.put<WorklogEntry>(`/worklog/entries/${id}`, body)
export const deleteEntry = (id: number) => api.del(`/worklog/entries/${id}`)

// ---- git 扫描导入 ----
export const previewGit = (from: string, to: string) =>
  api.get<GitPreview>(`/worklog/git/preview?from=${from}&to=${to}`)
export const importGit = (
  items: { repoId: number; commitSha: string; subject: string; date: string; hours?: number }[],
) => api.post<{ created: number; skipped: number }>('/worklog/git/import', { items })

// ---- 日报 / 周报 ----
export const getDaily = (date: string) => api.get<DailyReport | undefined>(`/worklog/daily?date=${date}`)
/** 某周（weekStart=周一）7 天内有报告的日报，日期升序；日报周视图的周日选择条用 */
export const listDailyWeek = (weekStart: string) =>
  api.get<DailyReport[]>(`/worklog/daily/week?weekStart=${weekStart}`)
export const createDaily = (date: string) => api.post<DailyReport>('/worklog/daily', { date })
export const generateDaily = (date: string, force = false) =>
  api.post<GenerateAck>('/worklog/daily/generate', { date, force })
export const updateDaily = (id: number, body: { contentMd?: string; status?: string }) =>
  api.put<DailyReport>(`/worklog/daily/${id}`, body)

export const getWeekly = (weekStart: string) =>
  api.get<WeeklyReport | undefined>(`/worklog/weekly?weekStart=${weekStart}`)
/** 最近 weeks 个周（含本周）有报告的周报，新周在前；周报视图的最近周选择条用 */
export const listWeeklyRecent = (weeks = 7) =>
  api.get<WeeklyReport[]>(`/worklog/weekly/recent?weeks=${weeks}`)
export const createWeekly = (weekStart: string) => api.post<WeeklyReport>('/worklog/weekly', { weekStart })
export const generateWeekly = (weekStart: string, force = false) =>
  api.post<GenerateAck>('/worklog/weekly/generate', { weekStart, force })
export const updateWeekly = (id: number, body: { summaryMd?: string; nextPlanMd?: string; status?: string }) =>
  api.put<WeeklyReport>(`/worklog/weekly/${id}`, body)

// ---- 个人设置 ----
export const getSettings = () => api.get<WorklogSettings>('/worklog/settings')
export const updateSettings = (body: Partial<WorklogSettings>) =>
  api.put<WorklogSettings>('/worklog/settings', body)
