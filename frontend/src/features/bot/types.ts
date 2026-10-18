/** CAP-75 M3 机器人运维页视图类型（对接 /api/bot/admin/** 与 /api/admin/user-platform-accounts） */

/** 交互日志行（bot_interactions）：底座处理过的每条事件一行，含 DEDUPED/REJECTED */
export interface BotInteraction {
  id: number
  /** 平台 code（feishu/wecom/…） */
  channel: string
  externalChatId: string
  /** p2p | group */
  chatType?: string | null
  /** 发送者平台侧 id（飞书 open_id） */
  senderId?: string | null
  /** 实际执行身份（四级链命中结果） */
  actor?: string | null
  /** 指令名（help/bind/…）| QA | DEDUPED */
  command?: string | null
  inputText?: string | null
  replyText?: string | null
  status: 'OK' | 'FAILED' | 'DEDUPED' | 'REJECTED' | string
  error?: string | null
  durationMs?: number | null
  createdAt: string
}

/** 群绑定（bot_bindings + 项目名联查） */
export interface BotBinding {
  id: number
  channel: string
  externalChatId: string
  chatType?: string | null
  ownerSenderId?: string | null
  chatSessionId?: string | null
  projectId?: string | null
  projectName?: string | null
  actor?: string | null
  createdAt: string
  updatedAt: string
}

/** 用户映射管理视图（user_platform_accounts 跨用户列表；运维页只调 type=FEISHU） */
export interface AdminPlatformAccount {
  id: number
  integrationId: number
  integrationType: string
  integrationName: string
  userId: string
  username: string
  displayName?: string | null
  /** 第三方账号用户名（FEISHU = open_id 身份码） */
  externalUsername?: string | null
  authType?: string | null
  createdAt: string
  updatedAt: string
}

export interface BotInteractionPage {
  items: BotInteraction[]
  total: number
  page: number
  size: number
}

/** CAP-76 智能体运行时配置只读视图（/api/copilot/admin/config，仅 ADMIN） */
export interface CopilotConfigView {
  enabled: boolean
  endpointId?: string | null
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
  actionSweepCron?: string | null
  cleanupCron?: string | null
}

/** CAP-76 智能体健康检查（/api/copilot/admin/health） */
export interface CopilotHealthView {
  enabled: boolean
  tools: number
  policyPresent: boolean
  endpointConfigured: boolean
  endpointFailure?: string | null
  pendingActions: number
  pendingToolConfirmations: number
  executingActions: number
}
