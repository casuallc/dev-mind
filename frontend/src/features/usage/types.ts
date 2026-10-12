// CAP-67 用量统计类型：与 devmind-usage 的 DTO 一一对应。

/** 总体汇总（会话 + 问答两源合并） */
export interface UsageSummary {
  costUsd: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
  turnCount: number
  sessionCount: number
  chatCount: number
}

/** 分组维度行：key 为分组原值（未归属桶为 null），projectId 仅需求行带出（跳需求详情用） */
export interface UsageBreakdownRow {
  key: string | null
  label: string
  projectId: string | null
  sessionCount: number
  chatCount: number
  turnCount: number
  costUsd: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
}

/** 每日趋势点（date=yyyy-MM-dd，零用量日也返回） */
export interface UsageDailyPoint {
  date: string
  costUsd: number
  tokens: number
  turns: number
}

/** 用量 Top 明细行（会话/问答混合，成本降序） */
export interface UsageTopRow {
  source: 'SESSION' | 'CHAT'
  id: string
  title: string | null
  requirementId: string | null
  requirementTitle: string | null
  projectId: string | null
  model: string | null
  createdBy: string | null
  createdAt: string | null
  turnCount: number
  costUsd: number
  inputTokens: number
  outputTokens: number
  cacheReadTokens: number
  cacheCreationTokens: number
}

export type UsageDim = 'requirement' | 'project' | 'model' | 'user'

/** 通用分页视图（与后端 common.dto.PageView 对应） */
export interface PageView<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** 请求日志行（逐回合账本视图）。costUsd 照常住账随 DTO 返回，但前端不展示（成本失真）。
 * token/耗时字段可空：执行体未上报时为 null，渲染一律兜底 '-'。 */
export interface UsageRecordRow {
  id: number
  source: 'SESSION' | 'CHAT'
  sessionId: string
  title: string | null
  requirementId: string | null
  projectId: string | null
  model: string | null
  owner: string | null
  createdAt: string | null
  durationMs: number | null
  costUsd: number | null
  inputTokens: number | null
  outputTokens: number | null
  cacheReadTokens: number | null
  cacheCreationTokens: number | null
  tokensPerSec: number | null
}

/** 按小时趋势点（hour="00".."23" 服务器时区，24 桶含零桶） */
export interface UsageHourlyPoint {
  hour: string
  tokens: number
  turns: number
}
