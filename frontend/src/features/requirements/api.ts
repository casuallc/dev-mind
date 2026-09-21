// CAP-13/14 研发主线接口封装：Requirement / WorkItem / Design / 需求流程（flow）
import { api } from '../../shared/api/client'
import type {
  Design,
  FlowSession,
  JiraAssignableUser,
  JiraCreateFields,
  JiraPushInput,
  JiraPushOptions,
  JiraPushResult,
  JiraPushTargets,
  JiraTransition,
  JiraTransitionResult,
  Requirement,
  RequirementInput,
  RequirementOverview,
  RequirementPage,
  RequirementStatus,
  WorkItem,
  WorkItemInput,
  WorkItemStatus,
} from './types'

/** 需求分页列表：status/type/source 过滤（空/ALL=不限；status=OPEN 伪值=未完结，排除 DONE/CANCELLED），keyword 匹配标题/Jira key，page 从 0 起 */
export function listRequirements(
  projectId: string,
  opts?: { status?: string; type?: string; source?: string; keyword?: string; page?: number; size?: number },
): Promise<RequirementPage> {
  const q = new URLSearchParams()
  if (opts?.status && opts.status !== 'ALL') q.set('status', opts.status)
  if (opts?.type && opts.type !== 'ALL') q.set('type', opts.type)
  if (opts?.source && opts.source !== 'ALL') q.set('source', opts.source)
  if (opts?.keyword) q.set('keyword', opts.keyword)
  q.set('page', String(opts?.page ?? 0))
  q.set('size', String(opts?.size ?? 20))
  return api.get<RequirementPage>(`/projects/${projectId}/requirements?${q}`)
}

export function createRequirement(projectId: string, input: RequirementInput): Promise<Requirement> {
  return api.post<Requirement>(`/projects/${projectId}/requirements`, input)
}

export function updateRequirement(
  projectId: string,
  requirementId: string,
  input: RequirementInput,
): Promise<Requirement> {
  return api.put<Requirement>(`/projects/${projectId}/requirements/${requirementId}`, input)
}

export function updateRequirementStatus(
  projectId: string,
  requirementId: string,
  status: RequirementStatus,
): Promise<Requirement> {
  return api.put<Requirement>(`/projects/${projectId}/requirements/${requirementId}/status`, { status })
}

export function deleteRequirement(projectId: string, requirementId: string): Promise<void> {
  return api.del(`/projects/${projectId}/requirements/${requirementId}`)
}

/** 需求主线聚合（CAP-13）：工作单元 + 文档/会话/构建/测试/部署/发版/产物 + 时间线 */
export function getRequirementOverview(
  projectId: string,
  requirementId: string,
): Promise<RequirementOverview> {
  return api.get<RequirementOverview>(`/projects/${projectId}/requirements/${requirementId}/overview`)
}

// ---- CAP-19 FR-08 Jira 状态回写 ----

/** 需求关联 issue 当前可用的 Jira 工作流转换（详情页「Jira 操作」下拉数据源） */
export function listJiraTransitions(projectId: string, requirementId: string): Promise<JiraTransition[]> {
  return api.get<JiraTransition[]>(`/projects/${projectId}/requirements/${requirementId}/jira/transitions`)
}

/** 执行一次 Jira 工作流转换（只回写远端，本地需求状态不动） */
export function transitionJiraIssue(
  projectId: string,
  requirementId: string,
  transitionId: string,
): Promise<JiraTransitionResult> {
  return api.post<JiraTransitionResult>(
    `/projects/${projectId}/requirements/${requirementId}/jira/transitions`,
    { transitionId },
  )
}

// ---- CAP-27 Jira 工时回写 ----

/** 登记 Jira 工时（hours 换算秒写 worklog，timeSpent 随即刷新；本地需求状态不动） */
export function logJiraWork(
  projectId: string,
  requirementId: string,
  hours: number,
  comment?: string,
): Promise<{ seconds: number; remoteStatus?: string }> {
  return api.post(`/projects/${projectId}/requirements/${requirementId}/jira/worklog`, {
    seconds: Math.round(hours * 3600),
    comment: comment || undefined,
  })
}

// ---- CAP-47 自建需求手动推送到 Jira ----

/** 推送弹窗数据源：候选实例/默认目标/任务类型/优先级/默认值/写身份来源/是否被同步覆盖。
 *  服务端不抛错（选项拉取失败降级为空表 + optionsError），弹窗一定打得开。 */
export function getJiraPushTargets(projectId: string, requirementId: string): Promise<JiraPushTargets> {
  return api.get<JiraPushTargets>(`/projects/${projectId}/requirements/${requirementId}/jira/push-targets`)
}

/** 切换实例/项目后重拉 Jira 项目 / 任务类型 / 优先级（失败即抛，错误原文透出） */
export function getJiraPushOptions(
  projectId: string,
  requirementId: string,
  integrationId: number,
  jiraProjectKey?: string,
): Promise<JiraPushOptions> {
  const q = new URLSearchParams({ integrationId: String(integrationId) })
  if (jiraProjectKey) q.set('jiraProjectKey', jiraProjectKey)
  return api.get<JiraPushOptions>(
    `/projects/${projectId}/requirements/${requirementId}/jira/push-options?${q}`,
  )
}

/** 经办人搜索（q 为用户名/显示名关键字）；失败由调用方降级为纯文本输入，不阻断提交 */
export function searchJiraAssignableUsers(
  projectId: string,
  requirementId: string,
  integrationId: number,
  jiraProjectKey: string | undefined,
  q: string,
): Promise<JiraAssignableUser[]> {
  const params = new URLSearchParams({ integrationId: String(integrationId) })
  if (jiraProjectKey) params.set('jiraProjectKey', jiraProjectKey)
  if (q) params.set('q', q)
  return api.get<JiraAssignableUser[]>(
    `/projects/${projectId}/requirements/${requirementId}/jira/assignable-users?${params}`,
  )
}

/** CAP-47 FR-08：选定实例+项目+任务类型后的必填字段清单（createmeta）——弹窗据此动态渲染输入项。
 *  不抛错：拉取失败降级为空表 + error，提交不禁用（读接口不可用不该堵死原本能推的类型）。 */
export function getJiraCreateFields(
  projectId: string,
  requirementId: string,
  integrationId: number,
  jiraProjectKey: string,
  issueTypeId: string,
): Promise<JiraCreateFields> {
  const q = new URLSearchParams({
    integrationId: String(integrationId),
    jiraProjectKey,
    issueTypeId,
  })
  return api.get<JiraCreateFields>(
    `/projects/${projectId}/requirements/${requirementId}/jira/create-fields?${q}`,
  )
}

/** 推送自建需求到 Jira：建 issue + 登记关联 + 需求转 Jira 托管（不可撤销） */
export function pushRequirementToJira(
  projectId: string,
  requirementId: string,
  input: JiraPushInput,
): Promise<JiraPushResult> {
  return api.post<JiraPushResult>(
    `/projects/${projectId}/requirements/${requirementId}/jira/push`,
    input,
  )
}

/** 按已关联 issue 手动刷新托管字段（同步配置不覆盖该 issue 时的兜底通道） */
export function refreshRequirementFromJira(
  projectId: string,
  requirementId: string,
): Promise<JiraPushResult> {
  return api.post<JiraPushResult>(
    `/projects/${projectId}/requirements/${requirementId}/jira/refresh`,
  )
}

// ---- Work Item ----

export function listWorkItems(projectId: string, requirementId: string): Promise<WorkItem[]> {
  return api.get<WorkItem[]>(`/projects/${projectId}/requirements/${requirementId}/work-items`)
}

export function createWorkItem(
  projectId: string,
  requirementId: string,
  input: WorkItemInput,
): Promise<WorkItem> {
  return api.post<WorkItem>(`/projects/${projectId}/requirements/${requirementId}/work-items`, input)
}

export function updateWorkItem(
  projectId: string,
  requirementId: string,
  workItemId: string,
  input: WorkItemInput,
): Promise<WorkItem> {
  return api.put<WorkItem>(
    `/projects/${projectId}/requirements/${requirementId}/work-items/${workItemId}`,
    input,
  )
}

export function updateWorkItemStatus(
  projectId: string,
  requirementId: string,
  workItemId: string,
  status: WorkItemStatus,
): Promise<WorkItem> {
  return api.put<WorkItem>(
    `/projects/${projectId}/requirements/${requirementId}/work-items/${workItemId}/status`,
    { status },
  )
}

export function deleteWorkItem(
  projectId: string,
  requirementId: string,
  workItemId: string,
): Promise<void> {
  return api.del(`/projects/${projectId}/requirements/${requirementId}/work-items/${workItemId}`)
}

// ---- Design ----

export function listDesigns(projectId: string, requirementId: string): Promise<Design[]> {
  return api.get<Design[]>(`/projects/${projectId}/requirements/${requirementId}/designs`)
}

export function createDesign(
  projectId: string,
  requirementId: string,
  docId?: number,
): Promise<Design> {
  return api.post<Design>(`/projects/${projectId}/requirements/${requirementId}/designs`, { docId })
}

export function updateDesignStatus(
  projectId: string,
  requirementId: string,
  designId: string,
  status: Design['status'],
): Promise<Design> {
  return api.put<Design>(
    `/projects/${projectId}/requirements/${requirementId}/designs/${designId}/status`,
    { status },
  )
}

export function deleteDesign(projectId: string, requirementId: string, designId: string): Promise<void> {
  return api.del(`/projects/${projectId}/requirements/${requirementId}/designs/${designId}`)
}

// ---- CAP-14/52 需求流程 ----

/** CAP-52 开启 AI 规划：一个会话产出 分析 + 方案 + 工作单元，三份齐备后自动起开发会话 */
export function flowPlan(projectId: string, requirementId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/requirements/${requirementId}/flow/plan`)
}

/** CAP-52 开发（重新开发）：按已固化清单起需求级开发会话，整份清单一次做完 */
export function flowDev(projectId: string, requirementId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/requirements/${requirementId}/flow/dev`)
}

/** 工作单元起会话（spec 自动带入 taskSpec） */
export function startWorkItemSession(projectId: string, workItemId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/work-items/${workItemId}/start-session`)
}
