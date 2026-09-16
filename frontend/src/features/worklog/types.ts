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

/** 工作条目（一天多条；工时按条目记，单位小时） */
export interface WorklogEntry {
  id: number
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
}

export interface DailyReport {
  id: number
  workDate: string
  contentMd: string
  status: string
  sessionId?: string
  createdAt?: string
  updatedAt?: string
}

export interface WeeklyReport {
  id: number
  weekStart: string
  summaryMd: string
  nextPlanMd: string
  status: string
  sessionId?: string
  createdAt?: string
  updatedAt?: string
}

/** git 扫描预览条目（不落库） */
export interface GitCommit {
  repoId: number
  repoName: string
  sha: string
  authorName: string
  authorEmail: string
  committedAt: string
  subject: string
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

export interface WorklogSettings {
  autoDaily: boolean
  autoWeekly: boolean
  dailyMinutesTarget?: number
  /** CAP-41 FR-05：日报格式模板；null/undefined = 内置默认（见 TemplateDefaults） */
  dailyTemplateMd?: string | null
  /** CAP-41 FR-05：周报格式模板；null/undefined = 内置默认 */
  weeklyTemplateMd?: string | null
  /** CAP-41 M3：远端备份仓库 URL（http/https/file）；null/undefined = 未绑定 */
  remoteUrl?: string | null
  /** CAP-41 M3：远端备份目标分支；null/空白 = main */
  remoteBranch?: string | null
}

/** CAP-41 M3：远端备份推送回执（POST /worklog/workspace/push） */
export interface PushAck {
  ok: boolean
  detail?: string
  error?: string
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

/** CAP-41 工作日志空间：WORKLOG 项目 + runner 持久工作区状态 */
export interface WorkspaceView {
  exists: boolean
  projectId?: string
  projectName?: string
  path?: string
  agentNodeId?: string
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
