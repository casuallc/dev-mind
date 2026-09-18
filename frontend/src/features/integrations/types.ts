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

/** Jira 同步配置（含运行状态）；同步只按 project + 附加 JQL 过滤，无其他条件 */
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

/** JQL 预览请求（创建/编辑同步配置时实时试算） */
export interface JiraSyncPreviewInput {
  integrationId?: number
  jiraProjectKey?: string
  jql?: string
}

/** JQL 预览结果：命中总数 + 前几条样例 */
export interface JiraSyncPreview {
  total: number
  issues: {
    key: string
    summary?: string | null
    issueType?: string | null
    status?: string | null
    created?: string | null
    updated?: string | null
  }[]
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

// ---- Jira 选项 / 创建字段元数据（CAP-47；推送弹窗与项目推送默认值配置页共用，故落在本能力内） ----

/** 通用下拉选项：id 为回传值（任务类型 id / Jira 项目 key；优先级 id 可能为空，回传用 name） */
export interface JiraOption {
  id?: string
  name: string
}

/** CAP-47 FR-02：切换实例/项目后重拉的选项 */
export interface JiraPushOptions {
  jiraProjects: JiraOption[]
  issueTypes: JiraOption[]
  priorities: JiraOption[]
}

/** CAP-47 FR-02：经办人候选（name = Jira 登录名，创建 issue 时回传） */
export interface JiraAssignableUser {
  name: string
  displayName: string
}

/** CAP-47 FR-08：动态必填字段的渲染控件（服务端按 Jira schema 判定，前端只按它 switch，
 *  不必理解 Jira 字段类型）。null 表示必填但平台渲染不了 → 列入 unsupported 并禁用提交。 */
export type JiraCreateFieldControl =
  | 'MULTI_SELECT'
  | 'SELECT'
  | 'DATE'
  | 'TEXT'
  | 'NUMBER'
  | 'TIMETRACKING'

/** CAP-47 FR-08：一个待填写的创建字段；options 为平台合法取值（枚举类必有，自由文本数组为空） */
export interface JiraCreateField {
  id: string
  name: string
  control: JiraCreateFieldControl | null
  options: JiraOption[]
}

/** CAP-47 FR-08：必填字段清单（createmeta）。requiredFixed 是固定表单已有的字段 id
 *  （duedate/priority/assignee/labels/description），前端加必填校验即可，不重复渲染。 */
export interface JiraCreateFields {
  fields: JiraCreateField[]
  requiredFixed: string[]
  /** 必填但渲染不了（用户选择器/级联选择等）：列出并禁用提交，好过提交后吃 400 */
  unsupported: JiraCreateField[]
  /** 字段 id → 预填值（只回填同域且命中实例候选值的本地值，目前只有 fixVersions） */
  prefill: Record<string, string[]>
  /** 元数据拉取失败原因；非空时三个列表皆空且**不禁用提交** */
  error?: string
}

// ---- CAP-47 FR-10 项目级 Jira 推送默认值 ----

/** 项目级推送默认值配置（一个项目一行）；未配置时接口返回空 body */
export interface JiraPushDefaults {
  id: number
  integrationId: number
  integrationName?: string | null
  jiraProjectKey?: string | null
  issueTypeId?: string | null
  priorityName?: string | null
  assigneeName?: string | null
  labels?: string[] | null
  /** 动态字段默认值；键=平台字段 id，值=**Jira API 形态**（与推送 payload 同） */
  extraFields?: Record<string, unknown> | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 保存请求：整行覆盖，没给的字段即被清空 */
export interface JiraPushDefaultsInput {
  integrationId: number
  jiraProjectKey?: string
  issueTypeId?: string
  priorityName?: string
  assigneeName?: string
  labels?: string[]
  extraFields?: Record<string, unknown>
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
  /** 仅创建 MR 响应携带（CAP-35：PERSONAL/BOT 身份来源） */
  identitySource?: string | null
}
