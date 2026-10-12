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

/** CAP-74：日报视图改服务端仓口径——无 DB id/status（状态机已删），exists 恒 true（GET 不存在返回 undefined） */
export interface DailyReport {
  exists: boolean
  workDate: string
  contentMd: string
  sessionId?: string
  createdAt?: string
  updatedAt?: string
}

/** CAP-74：周报视图改服务端仓口径——无 DB id/status；summary/nextPlan 由仓文件分节拆分 */
export interface WeeklyReport {
  exists: boolean
  weekStart: string
  summaryMd: string
  nextPlanMd: string
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
  /** CAP-41 M3 / CAP-74：远端备份仓库 URL（http/https/file），写路由到个人仓行；null/undefined = 未绑定 */
  remoteUrl?: string | null
  /** CAP-41 M3：远端备份目标分支；null/空白 = main */
  remoteBranch?: string | null
  /** CAP-74 M2：活动仓（写入路由）；null/undefined = 个人仓；PUT 传空白串 = 回个人仓 */
  activeRepoKey?: string | null
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

/** CAP-41 / CAP-74：会话成稿同步结果（POST /worklog/reports/sync） */
export interface WorklogSyncResult {
  /** 已写入服务端仓的报告（如 "日报 2026-09-16"） */
  mirrored: string[]
  /** CAP-74 起恒空（状态机已删，同内容幂等跳过不进此列）；字段保留兼容 */
  skipped: string[]
}

/** CAP-74：工作日志空间视图改服务端仓口径——仓是事实源；项目/节点只用于 AI 生成与对话链路 */
export interface WorkspaceView {
  exists: boolean
  /** 活动仓键（写入路由后的仓；个人仓 personal-<username>） */
  repoKey?: string
  /** 个人仓绑定的远端备份 URL（null = 未绑定） */
  remoteUrl?: string
  remoteBranch?: string
  /** 活动仓 HEAD 最近提交时间 */
  lastCommitAt?: string
  /** 读路径后台 fetch 失败/冲突降级（fail-visible 提示；undefined/false = 正常或未绑远端） */
  fetchStale?: boolean
  /** WORKLOG 项目 id（null = 未建——无默认节点时仓先行、项目降级缺位） */
  projectId?: string
  /** 亲和节点 id（创建时固化） */
  agentNodeId?: string
  /** 亲和节点当前是否在线（离线仅影响 AI 生成与对话，不影响读/编辑/推送） */
  nodeOnline?: boolean
}

/** CAP-74 M2：我所属的工作日志仓（GET /worklog/repos/mine；设置页「活动仓」切换数据源） */
export interface WorklogRepoInfo {
  repoKey: string
  createdBy: string
  /** true = 我的个人仓 */
  personal: boolean
  remoteUrl?: string
  remoteBranch?: string
  lastCommitAt?: string
}

/** CAP-74 M2：仓成员行（owner 标记供前端控制删除按钮显隐） */
export interface WorklogRepoMember {
  username: string
  owner: boolean
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
