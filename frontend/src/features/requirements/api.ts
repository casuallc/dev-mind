// CAP-13/14 研发主线接口封装：Requirement / WorkItem / Design / 需求流程（flow）
import { api } from '../../shared/api/client'
import type {
  Design,
  FlowSession,
  Requirement,
  RequirementInput,
  RequirementOverview,
  RequirementPage,
  RequirementStatus,
  SplitDraft,
  SplitDraftItem,
  WorkItem,
  WorkItemInput,
  WorkItemStatus,
} from './types'

/** 需求分页列表：status/type 过滤（空=不限），page 从 0 起 */
export function listRequirements(
  projectId: string,
  opts?: { status?: string; type?: string; page?: number; size?: number },
): Promise<RequirementPage> {
  const q = new URLSearchParams()
  if (opts?.status && opts.status !== 'ALL') q.set('status', opts.status)
  if (opts?.type && opts.type !== 'ALL') q.set('type', opts.type)
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

// ---- CAP-14 需求流程 ----

/** 开始/重新分析（起分析型会话） */
export function flowAnalyze(projectId: string, requirementId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/requirements/${requirementId}/flow/analyze`)
}

/** 生成方案（创建 DESIGN 型工作单元并起会话） */
export function flowDesign(projectId: string, requirementId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/requirements/${requirementId}/flow/design`)
}

/** AI 拆分（起拆分会话，产出 wi-plan.json） */
export function flowSplit(projectId: string, requirementId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/requirements/${requirementId}/flow/split`)
}

/** 拆分草稿（解析最近拆分会话输出，不落库） */
export function getSplitDraft(projectId: string, requirementId: string): Promise<SplitDraft> {
  return api.get<SplitDraft>(`/projects/${projectId}/requirements/${requirementId}/flow/split-draft`)
}

/** 确认固化（批量建工作单元 + depends_on 边） */
export function confirmSplit(
  projectId: string,
  requirementId: string,
  items: SplitDraftItem[],
): Promise<WorkItem[]> {
  return api.post<WorkItem[]>(`/projects/${projectId}/requirements/${requirementId}/flow/confirm-split`, { items })
}

/** 工作单元起会话（spec 自动带入 taskSpec） */
export function startWorkItemSession(projectId: string, workItemId: string): Promise<FlowSession> {
  return api.post<FlowSession>(`/projects/${projectId}/work-items/${workItemId}/start-session`)
}
