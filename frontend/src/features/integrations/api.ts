// CAP-18/19 平台集成接口封装
import { api } from '../../shared/api/client'
import type {
  ExternalLink,
  ExternalProject,
  Integration,
  IntegrationInput,
  IntegrationTestResult,
  JiraAssignableUser,
  JiraCreateFields,
  JiraPushOptions,
  JiraPushTemplate,
  JiraPushTemplateInput,
  JiraSyncConfig,
  JiraSyncConfigInput,
  JiraSyncPreview,
  JiraSyncPreviewInput,
  JiraSyncRun,
} from './types'

// ---------------- 集成实例管理（/api/integrations，写操作仅 ADMIN） ----------------

export function listIntegrations(): Promise<Integration[]> {
  return api.get<Integration[]>('/integrations')
}

export function createIntegration(input: IntegrationInput): Promise<Integration> {
  return api.post<Integration>('/integrations', input)
}

export function updateIntegration(id: number, input: Partial<IntegrationInput>): Promise<Integration> {
  return api.put<Integration>(`/integrations/${id}`, input)
}

export function changeIntegrationStatus(id: number, status: 'ENABLED' | 'DISABLED'): Promise<Integration> {
  return api.put<Integration>(`/integrations/${id}/status`, { status })
}

export function testIntegration(id: number): Promise<IntegrationTestResult> {
  return api.post<IntegrationTestResult>(`/integrations/${id}/test`)
}

/** 未保存配置的连接测试（新建/编辑表单内预检，凭据不落库） */
export function testIntegrationDraft(input: IntegrationInput): Promise<IntegrationTestResult> {
  return api.post<IntegrationTestResult>('/integrations/test', input)
}

export function listExternalProjects(id: number): Promise<ExternalProject[]> {
  return api.get<ExternalProject[]>(`/integrations/${id}/projects`)
}

// ---------------- Jira 同步配置（项目作用域） ----------------

export function listJiraSyncConfigs(projectId: string): Promise<JiraSyncConfig[]> {
  return api.get<JiraSyncConfig[]>(`/projects/${projectId}/jira-sync`)
}

export function createJiraSyncConfig(projectId: string, input: JiraSyncConfigInput): Promise<JiraSyncConfig> {
  return api.post<JiraSyncConfig>(`/projects/${projectId}/jira-sync`, input)
}

export function updateJiraSyncConfig(
  projectId: string,
  configId: number,
  input: JiraSyncConfigInput,
): Promise<JiraSyncConfig> {
  return api.put<JiraSyncConfig>(`/projects/${projectId}/jira-sync/${configId}`, input)
}

export function deleteJiraSyncConfig(projectId: string, configId: number): Promise<void> {
  return api.del(`/projects/${projectId}/jira-sync/${configId}`)
}

/** 手动触发一次同步 */
export function runJiraSync(projectId: string, configId: number): Promise<JiraSyncRun> {
  return api.post<JiraSyncRun>(`/projects/${projectId}/jira-sync/${configId}/run`)
}

/** JQL 预览：按当前 project key + 附加 JQL 实时试算命中数与样例 issue */
export function previewJiraSyncFilter(
  projectId: string,
  input: JiraSyncPreviewInput,
): Promise<JiraSyncPreview> {
  return api.post<JiraSyncPreview>(`/projects/${projectId}/jira-sync/preview`, input)
}

/** 项目内某类内部实体的全部外部链接（需求列表 Jira 徽标批量反查） */
export function listExternalLinksByType(projectId: string, internalType: string): Promise<ExternalLink[]> {
  return api.get<ExternalLink[]>(
    `/projects/${projectId}/external-links?internalType=${encodeURIComponent(internalType)}`,
  )
}

// ---------------- CAP-47 FR-10 个人 Jira 推送模板 ----------------

/** 我的推送模板列表（按更新时间倒序） */
export function listJiraPushTemplates(): Promise<JiraPushTemplate[]> {
  return api.get<JiraPushTemplate[]>('/me/jira-push-templates')
}

/** 保存我的推送模板（upsert：同 实例+项目+类型 整行覆盖，没给的字段即被清空） */
export function saveJiraPushTemplate(input: JiraPushTemplateInput): Promise<JiraPushTemplate> {
  return api.put<JiraPushTemplate>('/me/jira-push-templates', input)
}

export function deleteJiraPushTemplate(id: number): Promise<void> {
  return api.del<void>(`/me/jira-push-templates/${id}`)
}

/** 模板配置表单用的个人作用域选项端点（需求侧的同类端点在路径里带 pid/rid，配置页不挂在需求上） */
export function getJiraPushTemplateOptions(
  integrationId: number,
  jiraProjectKey?: string,
): Promise<JiraPushOptions> {
  const q = new URLSearchParams({ integrationId: String(integrationId) })
  if (jiraProjectKey) q.set('jiraProjectKey', jiraProjectKey)
  return api.get<JiraPushOptions>(`/me/jira-push-templates/options?${q}`)
}

export function searchJiraTemplateAssignableUsers(
  integrationId: number,
  jiraProjectKey: string | undefined,
  q: string,
): Promise<JiraAssignableUser[]> {
  const params = new URLSearchParams({ integrationId: String(integrationId) })
  if (jiraProjectKey) params.set('jiraProjectKey', jiraProjectKey)
  if (q) params.set('q', q)
  return api.get<JiraAssignableUser[]>(`/me/jira-push-templates/assignable-users?${params}`)
}

export function getJiraTemplateCreateFields(
  integrationId: number,
  jiraProjectKey: string,
  issueTypeId: string,
): Promise<JiraCreateFields> {
  const q = new URLSearchParams({
    integrationId: String(integrationId),
    jiraProjectKey,
    issueTypeId,
  })
  return api.get<JiraCreateFields>(`/me/jira-push-templates/create-fields?${q}`)
}
