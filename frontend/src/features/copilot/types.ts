/** CAP-76 智能体网关运维页类型（对接 /api/copilot/**） */

export interface PageView<T> {
  items: T[]
  total: number
  page: number
  size: number
}

/** FR-32 健康检查：只反映装配/配置/积压，不调用模型 */
export interface CopilotHealth {
  enabled: boolean
  tools: number
  /** ToolAccessPolicy 是否接入（CAP-62 数据权限） */
  policyPresent: boolean
  endpointConfigured: boolean
  endpointFailure?: string | null
  pendingActions: number
  pendingToolConfirmations: number
  executingActions: number
}

/** 运行时配置只读视图（FR-30 /admin/config） */
export interface CopilotConfig {
  enabled: boolean
  endpointId?: string
  toolMode: string
  maxSteps: number
  maxToolCallsPerStep: number
  maxWritesPerTurn: number
  turnTimeoutSeconds: number
  modelTimeoutSeconds: number
  toolTimeoutSeconds: number
  confirmTtlMinutes: number
  confirmPreviewChunkChars: number
  confirmPreviewMaxChunks: number
  maxTurnsPerMinute: number
  maxConcurrentModel: number
  replyProgressCard: boolean
  allowDefaultActorWrites: boolean
  auditRetentionDays: number
  actionSweepCron?: string
  cleanupCron?: string
  /** FR-33~35 计划编排（M7/M8） */
  planMaxSteps?: number
  planExecutionTimeoutMinutes?: number
  planAwaitPollSeconds?: number
  planAwaitMaxTimeoutMinutes?: number
}

/** 工具目录行（/admin/tools） */
export interface CopilotTool {
  name: string
  title: string
  description: string
  risk: 'READ' | 'WRITE' | 'DANGEROUS' | string
  minRole: string
  requiresProject: boolean
  ownerModule: string
  since: string
  timeoutSeconds: number
  /** JSON 字符串（OpenAPI schema） */
  parametersSchema: string
}

/** 对话审计轮（copilot_turns） */
export interface CopilotTurn {
  id: string
  conversationId: string
  channel: string
  externalChatId?: string | null
  senderId?: string | null
  actor?: string | null
  projectId?: string | null
  userText?: string | null
  assistantText?: string | null
  status: 'OK' | 'FAILED' | 'REJECTED' | 'PENDING_CONFIRM' | string
  triggerType?: string | null
  model?: string | null
  endpointId?: string | null
  toolMode?: string | null
  steps: number
  promptTokens: number
  completionTokens: number
  durationMs: number
  error?: string | null
  createdAt: string
}

/** 工具调用审计（copilot_tool_calls） */
export interface CopilotToolCall {
  id: number
  turnId: string
  actionId?: string | null
  /** FR-36 计划步骤序号（非步骤行为 null） */
  stepSeq?: number | null
  toolName: string
  argsJson?: string | null
  risk: string
  status: string
  resultSummary?: string | null
  error?: string | null
  durationMs: number
  createdAt: string
}

/** 确认单（copilot_actions） */
export interface CopilotAction {
  id: string
  turnId: string
  channel: string
  externalChatId?: string | null
  senderId?: string | null
  actor?: string | null
  projectId?: string | null
  toolName: string
  argsJson?: string | null
  risk: string
  previewText?: string | null
  status: 'PENDING' | 'EXECUTING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED' | string
  shortCode: string
  cardMessageId?: string | null
  createdAt: string
  expiresAt?: string | null
  executedAt?: string | null
  resultJson?: string | null
  error?: string | null
}

export interface CopilotTurnDetail {
  turn: CopilotTurn | null
  toolCalls: CopilotToolCall[]
  actions: CopilotAction[]
}

/** FR-36 计划列表行（submit_plan 确认单 + 步骤进度聚合计数） */
export interface CopilotPlan {
  id: string
  shortCode: string
  goal: string
  status: 'PENDING' | 'EXECUTING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED' | string
  risk: string
  actor?: string | null
  projectId?: string | null
  totalSteps: number
  completedSteps: number
  error?: string | null
  createdAt: string
  executedAt?: string | null
}

/** FR-36 计划步骤（步骤快照 join 步骤审计行） */
export interface CopilotPlanStep {
  seq: number
  tool?: string | null
  title?: string | null
  await?: { kind?: string; idField?: string; timeoutMinutes?: number } | null
  argsJson?: string | null
  /** PENDING（未开始）/RUNNING/WAITING/OK/FAILED/REJECTED */
  status: string
  resultSummary?: string | null
  error?: string | null
  durationMs?: number
  startedAt?: string | null
}

export interface CopilotPlanDetail {
  plan: (CopilotPlan & { steps: CopilotPlanStep[] }) | null
}
