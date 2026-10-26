// CAP-28 个人工作日志与工时管理 API
import { api } from '../../shared/api/client'
import type {
  CarryResult,
  DailyReport,
  EntryPage,
  EntryPayload,
  GenerateAck,
  GitPreview,
  GitPreviewFilter,
  PlanItem,
  PlanItemPayload,
  PlanListView,
  TemplateDefaults,
  WeeklyReport,
  WorklogEntry,
  WorklogRepo,
  WorklogSettings,
  WorklogSyncResult,
  WorkspaceView,
} from './types'

// ---- 全局代码仓库（CAP-29 起登记在 /admin/repos；此处仅列表 + 本人订阅勾选） ----
export const listRepos = () => api.get<WorklogRepo[]>('/worklog/repos')
/** 订阅勾选；branches 传空数组 = 恢复跟随默认分支，不传 = 不改动既有选择 */
export const setSubscription = (id: number, subscribed: boolean, branches?: string[]) =>
  api.put(`/worklog/repos/${id}/subscription`, branches === undefined ? { subscribed } : { subscribed, branches })

// ---- 工作条目 ----
export const listEntries = (from: string, to: string, page = 0, size = 20, keyword?: string) =>
  api.get<EntryPage>(
    `/worklog/entries?from=${from}&to=${to}&page=${page}&size=${size}` +
      (keyword ? `&keyword=${encodeURIComponent(keyword)}` : ''),
  )
export const createEntry = (body: EntryPayload) => api.post<WorklogEntry>('/worklog/entries', body)
/** CAP-74 M3：id 为 uuid 字符串；寻址靠请求体 workDate（改期由后端跨文件搬移） */
export const updateEntry = (id: string, body: EntryPayload) =>
  api.put<WorklogEntry>(`/worklog/entries/${id}`, body)
/** CAP-89：删除按 uuid 直删（DB 主键，不再需要日期寻址） */
export const deleteEntry = (id: string) => api.del(`/worklog/entries/${id}`)

// ---- CAP-88 计划项（日/周双层，状态机 TODO→DOING 自动、DONE/DROPPED 人工、CARRIED 滚转写入） ----
export const listPlans = (scope: 'DAY' | 'WEEK', date: string) =>
  api.get<PlanListView>(`/worklog/plans?scope=${scope}&date=${date}`)
/** 条目抽屉可关联项：当日日计划 + 当周周计划 */
export const listLinkablePlans = (date: string) =>
  api.get<PlanItem[]>(`/worklog/plans/linkable?date=${date}`)
export const createPlanItem = (body: PlanItemPayload) => api.post<PlanItem>('/worklog/plans/items', body)
export const updatePlanItem = (id: number, body: PlanItemPayload) =>
  api.put<PlanItem>(`/worklog/plans/items/${id}`, body)
export const deletePlanItem = (id: number) => api.del(`/worklog/plans/items/${id}`)
/** 一键滚转（幂等：已 CARRIED/已完结跳过）；to：DAY=目标日期，WEEK=目标周内任意日 */
export const carryPlans = (scope: 'DAY' | 'WEEK', to: string, ids: number[]) =>
  api.post<CarryResult>('/worklog/plans/carry', { scope, to, ids })

// ---- git 扫描导入 ----
/** filter：NEW（仅未导入，弹窗默认）/ IMPORTED（仅已导入）/ ALL（后端缺省值）；诊断不受 filter 影响 */
export const previewGit = (from: string, to: string, filter: GitPreviewFilter = 'NEW') =>
  api.get<GitPreview>(`/worklog/git/preview?from=${from}&to=${to}&filter=${filter}`)
export const importGit = (
  items: { repoId: number; commitSha: string; subject: string; date: string; hours?: number }[],
) => api.post<{ created: number; skipped: number }>('/worklog/git/import', { items })

// ---- 日报 / 周报 ----
export const getDaily = (date: string) => api.get<DailyReport | undefined>(`/worklog/daily?date=${date}`)
/** 某周（weekStart=周一）7 天内有报告的日报，日期升序；日报周视图的周日选择条用 */
export const listDailyWeek = (weekStart: string) =>
  api.get<DailyReport[]>(`/worklog/daily/week?weekStart=${weekStart}`)
export const createDaily = (date: string) => api.post<DailyReport>('/worklog/daily', { date })
/** hint：本人补充说明（重点/影响/数字），作为高优先级事实进入生成 prompt；空则不传 */
export const generateDaily = (date: string, force = false, hint?: string) =>
  api.post<GenerateAck>('/worklog/daily/generate', { date, force, hint })
/** CAP-89：按日期寻址保存（落库 upsert）；status 字段已废弃，传了后端 400 */
export const updateDaily = (date: string, body: { contentMd?: string }) =>
  api.put<DailyReport>(`/worklog/daily/${date}`, body)

export const getWeekly = (weekStart: string) =>
  api.get<WeeklyReport | undefined>(`/worklog/weekly?weekStart=${weekStart}`)
/** 最近 weeks 个周（含本周）有报告的周报，新周在前；周报视图的最近周选择条用 */
export const listWeeklyRecent = (weeks = 7) =>
  api.get<WeeklyReport[]>(`/worklog/weekly/recent?weeks=${weeks}`)
export const createWeekly = (weekStart: string) => api.post<WeeklyReport>('/worklog/weekly', { weekStart })
/** hint：本人补充说明（重点/影响/数字），作为高优先级事实进入生成 prompt；空则不传 */
export const generateWeekly = (weekStart: string, force = false, hint?: string) =>
  api.post<GenerateAck>('/worklog/weekly/generate', { weekStart, force, hint })
/** CAP-89：按周一寻址保存（分节字段落库两列）；status 传了 400 */
export const updateWeekly = (weekStart: string, body: { summaryMd?: string; nextPlanMd?: string }) =>
  api.put<WeeklyReport>(`/worklog/weekly/${weekStart}`, body)

// ---- CAP-41/89 工作日志空间（事实源=数据库；WORKLOG 项目仅服务 AI 生成/对话） ----
export const getWorkspace = () => api.get<WorkspaceView>('/worklog/workspace')
export const ensureWorkspace = () => api.post<WorkspaceView>('/worklog/workspace/ensure', {})
/** CAP-41/89：把空间某会话的成稿产出（daily-/weekly-）落库（幂等；已有不同内容跳过不覆盖） */
export const syncSessionReports = (sessionId: string) =>
  api.post<WorklogSyncResult>('/worklog/reports/sync', { sessionId })

// ---- 个人设置 ----
export const getSettings = () => api.get<WorklogSettings>('/worklog/settings')
export const updateSettings = (body: Partial<WorklogSettings>) =>
  api.put<WorklogSettings>('/worklog/settings', body)
/** CAP-41 FR-05：内置默认模板（编辑器「填入默认」用） */
export const getDefaultTemplates = () => api.get<TemplateDefaults>('/worklog/settings/templates/default')
