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
}

export interface GenerateAck {
  accepted: boolean
  running: boolean
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
