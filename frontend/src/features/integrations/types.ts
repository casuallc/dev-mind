// CAP-18/19 平台集成类型

/** 集成平台实例（凭据不明文回显，仅 hasToken） */
export interface Integration {
  id: number
  type: 'GITLAB' | 'GITHUB' | 'JIRA'
  name: string
  baseUrl: string
  authType: string
  hasToken: boolean
  status: 'ENABLED' | 'DISABLED'
  configJson?: string | null
  createdBy?: string | null
  createdAt: string
  updatedAt: string
}

/** 创建/更新请求；更新时 token 留空 = 保持不变。authType：PAT（默认）/ BASIC（Jira 8.13-，token=密码） */
export interface IntegrationInput {
  type: string
  name: string
  baseUrl: string
  authType?: string
  username?: string
  token?: string
  configJson?: string
}

export interface IntegrationTestResult {
  ok: boolean
  message: string
  detail?: string | null
}

/** 平台侧项目（GitLab project / Jira project） */
export interface ExternalProject {
  key: string
  name?: string | null
  url?: string | null
  defaultBranch?: string | null
}

/** Jira 同步配置（含运行状态） */
export interface JiraSyncConfig {
  id: number
  integrationId: number
  integrationName?: string | null
  projectId: string
  jiraProjectKey: string
  jql?: string | null
  enabled: boolean
  pollIntervalSec: number
  lastSyncAt?: string | null
  lastWatermark?: string | null
  lastImported?: number | null
  lastUpdatedCount?: number | null
  lastError?: string | null
  createdAt: string
  updatedAt: string
}

export interface JiraSyncConfigInput {
  integrationId?: number
  jiraProjectKey?: string
  jql?: string
  enabled?: boolean
  pollIntervalSec?: number
}

/** 一次同步运行结果 */
export interface JiraSyncRun {
  configId: number
  imported: number
  updated: number
  skipped: number
  pages: number
  error?: string | null
}

/** 内部实体 ↔ 外部对象链接 */
export interface ExternalLink {
  id: number
  integrationId: number
  internalType: string
  internalId: string
  externalType: string
  externalKey: string
  externalUrl?: string | null
  status?: string | null
  createdAt: string
}
