// CAP-28 个人工作日志与工时管理 API
import { api } from '../../shared/api/client'
import type {
  DailyReport,
  EntryPage,
  EntryPayload,
  GenerateAck,
  GitPreview,
  GitPreviewFilter,
  PushAck,
  TemplateDefaults,
  WeeklyReport,
  WorklogEntry,
  WorklogRepo,
  WorklogRepoInfo,
  WorklogRepoMember,
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
export const updateEntry = (id: number, body: EntryPayload) =>
  api.put<WorklogEntry>(`/worklog/entries/${id}`, body)
export const deleteEntry = (id: number) => api.del(`/worklog/entries/${id}`)

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
export const generateDaily = (date: string, force = false) =>
  api.post<GenerateAck>('/worklog/daily/generate', { date, force })
/** CAP-74：按日期寻址保存（保存即写仓 commit）；status 字段已废弃，传了后端 400 */
export const updateDaily = (date: string, body: { contentMd?: string }) =>
  api.put<DailyReport>(`/worklog/daily/${date}`, body)

export const getWeekly = (weekStart: string) =>
  api.get<WeeklyReport | undefined>(`/worklog/weekly?weekStart=${weekStart}`)
/** 最近 weeks 个周（含本周）有报告的周报，新周在前；周报视图的最近周选择条用 */
export const listWeeklyRecent = (weeks = 7) =>
  api.get<WeeklyReport[]>(`/worklog/weekly/recent?weeks=${weeks}`)
export const createWeekly = (weekStart: string) => api.post<WeeklyReport>('/worklog/weekly', { weekStart })
export const generateWeekly = (weekStart: string, force = false) =>
  api.post<GenerateAck>('/worklog/weekly/generate', { weekStart, force })
/** CAP-74：按周一寻址保存（分节字段重组正文写仓）；status 传了 400 */
export const updateWeekly = (weekStart: string, body: { summaryMd?: string; nextPlanMd?: string }) =>
  api.put<WeeklyReport>(`/worklog/weekly/${weekStart}`, body)

// ---- CAP-41/74 工作日志空间（事实源=服务端仓；WORKLOG 项目仅服务 AI 生成/对话） ----
export const getWorkspace = () => api.get<WorkspaceView>('/worklog/workspace')
export const ensureWorkspace = () => api.post<WorkspaceView>('/worklog/workspace/ensure', {})
/** CAP-74：服务端直推个人仓到已绑定远端（fetch+merge+push；分叉冲突 409 → resolvePushConflict 显式解决） */
export const pushWorkspace = () => api.post<PushAck>('/worklog/workspace/push', {})
/** push 409 冲突后的显式解决（个人仓快捷入口）：ours=以平台仓为准 / theirs=以远端为准 */
export const resolvePushConflict = (strategy: 'ours' | 'theirs') =>
  api.post<PushAck>(`/worklog/workspace/resolve?strategy=${strategy}`, {})
/** CAP-41/74：把空间某会话的成稿产出（daily-/weekly-）写进服务端仓（幂等，同内容零 commit） */
export const syncSessionReports = (sessionId: string) =>
  api.post<WorklogSyncResult>('/worklog/reports/sync', { sessionId })

// ---- CAP-74：服务端仓（团队仓成员管理 + 冲突解决 + 活动仓切换数据源） ----
/** push 409 冲突后的显式解决：ours=以平台仓为准 / theirs=以远端为准 */
export const resolveRepoConflict = (repoKey: string, strategy: 'ours' | 'theirs') =>
  api.post<PushAck>(`/worklog/repos/${repoKey}/resolve?strategy=${strategy}`, {})
/** 我所属的仓列表（个人仓 + 团队仓） */
export const listMyRepos = () => api.get<WorklogRepoInfo[]>('/worklog/repos/mine')
export const listRepoMembers = (repoKey: string) =>
  api.get<WorklogRepoMember[]>(`/worklog/repos/${repoKey}/members`)
/** 仓主加成员（按 username；返回更新后的成员列表） */
export const addRepoMember = (repoKey: string, username: string) =>
  api.post<WorklogRepoMember[]>(`/worklog/repos/${repoKey}/members?username=${encodeURIComponent(username)}`, {})
/** 仓主移除成员（仅删成员关系，仓内历史文件保留） */
export const removeRepoMember = (repoKey: string, username: string) =>
  api.del<WorklogRepoMember[]>(`/worklog/repos/${repoKey}/members/${encodeURIComponent(username)}`)

// ---- 个人设置 ----
export const getSettings = () => api.get<WorklogSettings>('/worklog/settings')
export const updateSettings = (body: Partial<WorklogSettings>) =>
  api.put<WorklogSettings>('/worklog/settings', body)
/** CAP-41 FR-05：内置默认模板（编辑器「填入默认」用） */
export const getDefaultTemplates = () => api.get<TemplateDefaults>('/worklog/settings/templates/default')
