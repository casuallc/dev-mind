// CAP-28 个人工作日志与工时管理 类型定义

/** 全局代码仓库（CAP-29 起经 /api/repos 登记；subscribed 为当前用户是否勾选参与扫描） */
export interface WorklogRepo {
  id: number
  name: string
  remoteUrl?: string
  defaultBranch?: string
  status: string
  cloneStatus?: string
  subscribed?: boolean
  /** 仓库远程分支列表（订阅分支选择的选项来源；LOCAL 行/未抓取过为空） */
  branches?: string[]
  /** 本人勾选扫描的分支；空 = 跟随默认分支 */
  subscribedBranches?: string[]
}

/** 工作条目（一天多条；工时按条目记，单位小时）。CAP-74 M3 起 id 为 uuid 字符串（CAP-89 起为 DB 主键） */
export interface WorklogEntry {
  id: string
  workDate: string
  title: string
  content?: string
  entryType: string
  hours: number
  source: string
  repoId?: number
  repoName?: string
  commitSha?: string
  requirementId?: string
  jiraIssueKey?: string
  /** CAP-88 FR-04：关联计划项 id（worklog_plan_items；删计划项后端置空） */
  planItemId?: number
  /** CAP-90 FR-01：DONE=事实（默认）；TODO/DOING=计划态（日计划并入条目）；CARRIED/DROPPED 终态 */
  status: string
  /** CAP-90 FR-01：计划态预估工时（小时），「完成」交互预填用 */
  estimateHours?: number
  /** CAP-90 FR-05：日粒度滚转来源条目 id */
  carriedFromId?: string
  createdAt?: string
  updatedAt?: string
}

/** 条目分页响应（totalMinutes 为范围内工时合计，非当前页） */
export interface EntryPage {
  items: WorklogEntry[]
  total: number
  totalMinutes: number
}

/** 条目新建/更新请求 */
export interface EntryPayload {
  workDate: string
  title: string
  content?: string
  entryType?: string
  hours: number
  requirementId?: string
  jiraIssueKey?: string
  /** CAP-88 FR-04：关联计划项；undefined/无字段 = 不关联（更新即解除） */
  planId?: number
  /** CAP-90 FR-02：不传 = 默认 DONE（补录事实）；TODO/DOING = 计划态；CARRIED 不可人工设 */
  status?: string
  /** CAP-90 FR-01：计划态预估工时（小时，可空） */
  estimateHours?: number
}

/** CAP-90：计划项收窄为 WEEK 唯一粒度（日计划并入条目，DAY 后端 400；planDate=周一） */
export interface PlanItem {
  id: number
  scope: 'WEEK'
  planDate: string
  title: string
  content?: string
  planType: string
  /** 预估工时（小时）；undefined=未预估 */
  estimateHours?: number
  status: string
  /** MANUAL / AI_SUGGEST（M3） */
  source: string
  requirementId?: string
  jiraIssueKey?: string
  carriedFromId?: number
  sortOrder: number
  createdAt?: string
  updatedAt?: string
}

/** 计划项列表响应：本周期计划 + 上一周期未完成提示 */
export interface PlanListView {
  items: PlanItem[]
  carryoverFrom: string
  carryoverItems: PlanItem[]
}

/** 计划项新建/更新请求（date 传周内任意日，后端归一到周一） */
export interface PlanItemPayload {
  scope: 'WEEK'
  date: string
  title: string
  content?: string
  planType?: string
  hours?: number
  requirementId?: string
  jiraIssueKey?: string
  /** 仅更新有效：TODO/DOING/DONE/DROPPED（CARRIED 由滚转写入） */
  status?: string
}

export const PLAN_STATUSES: Record<string, { label: string; color: string }> = {
  TODO: { label: '待办', color: 'default' },
  DOING: { label: '进行中', color: 'processing' },
  DONE: { label: '已完成', color: 'success' },
  DROPPED: { label: '已放弃', color: 'warning' },
  CARRIED: { label: '已滚转', color: 'purple' },
}

/** 计划项滚转结果：carried=成功滚转数；skipped=幂等跳过数；items=新建副本 */
export interface CarryResult {
  carried: number
  skipped: number
  items: PlanItem[]
}

/** CAP-90 FR-05：条目日粒度滚转结果（同 CarryResult 口径，items 为新条目副本） */
export interface EntryCarryResult {
  carried: number
  skipped: number
  items: WorklogEntry[]
}

/** CAP-90 FR-10「从计划项重建」预览：下周计划项确定性渲染的 markdown（不经 LLM） */
export interface NextPlanPreview {
  markdown: string
  itemCount: number
}

/** CAP-89：日报视图 DB 口径——无 status（状态机已删），exists 恒 true（GET 不存在返回 undefined） */
export interface DailyReport {
  exists: boolean
  workDate: string
  contentMd: string
  sessionId?: string
  createdAt?: string
  updatedAt?: string
}

/** CAP-89：周报视图 DB 口径——无 status；summary/nextPlan 分两列存储 */
export interface WeeklyReport {
  exists: boolean
  weekStart: string
  summaryMd: string
  nextPlanMd: string
  sessionId?: string
  createdAt?: string
  updatedAt?: string
}

/** git 扫描预览条目（不落库）。body/改动规模随扫描返回，供日报/周报生成做主题归并 */
export interface GitCommit {
  repoId: number
  repoName: string
  sha: string
  authorName: string
  authorEmail: string
  committedAt: string
  subject: string
  body?: string
  filesChanged?: number
  insertions?: number
  deletions?: number
  alreadyImported: boolean
}

/** 单仓库扫描诊断：outcome = SCANNED / SKIPPED / FAILED */
export interface GitScanRepoDiag {
  repoId: number
  repoName: string
  outcome: string
  authorFilter?: string
  detail?: string
  commitCount: number
}

/** git 扫描预览响应：提交列表 + 每仓库诊断 */
export interface GitPreview {
  commits: GitCommit[]
  repos: GitScanRepoDiag[]
}

/** git 预览过滤（后端过滤）：NEW=仅未导入（默认）/ IMPORTED=仅已导入 / ALL=全部 */
export type GitPreviewFilter = 'NEW' | 'IMPORTED' | 'ALL'

export interface WorklogSettings {
  autoDaily: boolean
  autoWeekly: boolean
  /** CAP-28 FR-09：每日定时从 Git 导入工作条目（严格 opt-in，默认关） */
  autoGitImport?: boolean
  dailyMinutesTarget?: number
  /** CAP-41 FR-05：日报格式模板；null/undefined = 内置默认（见 TemplateDefaults） */
  dailyTemplateMd?: string | null
  /** CAP-41 FR-05：周报格式模板；null/undefined = 内置默认 */
  weeklyTemplateMd?: string | null
  /** 个人日报执行时间 "HH:mm"；null/undefined = 跟随全局 */
  dailyTime?: string | null
  /** 个人周报执行星期（1=周一…7=周日）；null/undefined = 跟随全局；需与 weeklyTime 同时设置才覆盖 */
  weeklyDay?: number | null
  /** 个人周报执行时间 "HH:mm"；null/undefined = 跟随全局 */
  weeklyTime?: string | null
  /** 个人 Git 定时导入执行时间 "HH:mm"；null/undefined = 跟随全局 */
  gitImportTime?: string | null
  /** 全局兜底规则中文展示（如 "每天 18:30"），设置页 placeholder/extra 提示用；PUT 时忽略 */
  globalDailyLabel?: string
  globalWeeklyLabel?: string
  globalGitImportLabel?: string
}

/** 内置默认模板（GET /worklog/settings/templates/default），模板编辑器「填入默认」用 */
export interface TemplateDefaults {
  dailyTemplateMd: string
  weeklyTemplateMd: string
}

/** CAP-41 FR-03：生成受理回执——sessionId 为生成会话；reused=true 表示已有报告直接复用 */
export interface GenerateAck {
  sessionId?: string
  reused?: boolean
}

/** CAP-41 / CAP-89：会话成稿同步结果（POST /worklog/reports/sync） */
export interface WorklogSyncResult {
  /** 已落库的报告（如 "日报 2026-09-16"） */
  mirrored: string[]
  /** 库中已有不同内容被跳过的报告（「自动不覆盖」；同内容幂等静默跳过不进此列） */
  skipped: string[]
}

/** CAP-89：工作日志空间视图——事实源是数据库；项目/节点只用于 AI 生成与对话链路 */
export interface WorkspaceView {
  /** WORKLOG 项目是否已建（false = 未初始化，仅影响 AI 生成/对话，不影响记录与手动编辑） */
  exists: boolean
  /** WORKLOG 项目 id */
  projectId?: string
  /** 亲和节点 id（创建时固化） */
  agentNodeId?: string
  /** 亲和节点当前是否在线（离线仅影响 AI 生成与对话） */
  nodeOnline?: boolean
}

export const ENTRY_TYPES: Record<string, string> = {
  DEV: '开发',
  SUPPORT: '支持',
  MEETING: '会议',
  RESEARCH: '调研',
  OTHER: '其他',
}

export const ENTRY_SOURCES: Record<string, string> = {
  GIT: 'Git 导入',
  MANUAL: '手动',
  AGENT: 'Agent',
}
