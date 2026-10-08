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
