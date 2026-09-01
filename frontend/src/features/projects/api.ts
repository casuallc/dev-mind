// CAP-02 项目接口封装
import { api } from '../../shared/api/client'
import type {
  BuildStep,
  BuildStepInput,
  ContextSummary,
  Design,
  EnvironmentInput,
  FlowSession,
  Project,
  ProjectEnvironment,
  ProjectInput,
  ProjectLock,
  ProjectRepo,
  ProjectRepoInput,
  ProjectServer,
  ReleaseConfig,
  ReleaseConfigInput,
  Requirement,
  RequirementInput,
  RequirementOverview,
  RequirementPage,
  RequirementStatus,
  ServerInput,
  SplitDraft,
  SplitDraftItem,
  WorkItem,
  WorkItemInput,
  WorkItemStatus,
  WorktreeInfo,
} from './types'

export function listProjects(status?: string): Promise<Project[]> {
  const q = status && status !== 'ALL' ? `?status=${status}` : ''
  return api.get<Project[]>(`/projects${q}`)
}

export function getProject(id: string): Promise<Project> {
  return api.get<Project>(`/projects/${id}`)
}

export function createProject(input: ProjectInput): Promise<Project> {
  return api.post<Project>('/projects', input)
}

export function updateProject(id: string, input: ProjectInput): Promise<Project> {
  return api.put<Project>(`/projects/${id}`, input)
}

export function deleteProject(id: string): Promise<void> {
  return api.del(`/projects/${id}`)
}

// ---------------- 项目仓库（P0-4 多库模型） ----------------

export function listRepos(id: string): Promise<ProjectRepo[]> {
  return api.get<ProjectRepo[]>(`/projects/${id}/repos`)
}

export function addRepo(id: string, input: ProjectRepoInput): Promise<ProjectRepo> {
  return api.post<ProjectRepo>(`/projects/${id}/repos`, input)
}

export function updateRepo(id: string, repoId: number, input: ProjectRepoInput): Promise<ProjectRepo> {
  return api.put<ProjectRepo>(`/projects/${id}/repos/${repoId}`, input)
}

export function deleteRepo(id: string, repoId: number): Promise<void> {
  return api.del(`/projects/${id}/repos/${repoId}`)
}

export function setPrimaryRepo(id: string, repoId: number): Promise<ProjectRepo> {
  return api.post<ProjectRepo>(`/projects/${id}/repos/${repoId}/primary`)
}

// ---------------- 环境（P1-1） ----------------

export function listEnvironments(id: string): Promise<ProjectEnvironment[]> {
  return api.get<ProjectEnvironment[]>(`/projects/${id}/environments`)
}

export function addEnvironment(id: string, input: EnvironmentInput): Promise<ProjectEnvironment> {
  return api.post<ProjectEnvironment>(`/projects/${id}/environments`, input)
}

export function updateEnvironment(id: string, envId: number, input: EnvironmentInput): Promise<ProjectEnvironment> {
  return api.put<ProjectEnvironment>(`/projects/${id}/environments/${envId}`, input)
}

export function deleteEnvironment(id: string, envId: number): Promise<void> {
  return api.del(`/projects/${id}/environments/${envId}`)
}

// ---------------- 上下文摘要 ----------------

export function getSummary(id: string): Promise<ContextSummary> {
  return api.get<ContextSummary>(`/projects/${id}/summary`)
}

export function refreshSummary(id: string): Promise<ContextSummary> {
  return api.post<ContextSummary>(`/projects/${id}/summary/refresh`)
}

export function saveSummary(id: string, text: string): Promise<ContextSummary> {
  return api.put<ContextSummary>(`/projects/${id}/summary`, { text })
}

// ---------------- 服务器 ----------------

export function listServers(id: string): Promise<ProjectServer[]> {
  return api.get<ProjectServer[]>(`/projects/${id}/servers`)
}

export function addServer(id: string, input: ServerInput): Promise<ProjectServer> {
  return api.post<ProjectServer>(`/projects/${id}/servers`, input)
}

export function updateServer(id: string, serverId: number, input: ServerInput): Promise<ProjectServer> {
  return api.put<ProjectServer>(`/projects/${id}/servers/${serverId}`, input)
}

export function deleteServer(id: string, serverId: number): Promise<void> {
  return api.del(`/projects/${id}/servers/${serverId}`)
}

// ---------------- 构建步骤 ----------------

export function listBuildSteps(id: string): Promise<BuildStep[]> {
  return api.get<BuildStep[]>(`/projects/${id}/build-steps`)
}

export function addBuildStep(id: string, input: BuildStepInput): Promise<BuildStep> {
  return api.post<BuildStep>(`/projects/${id}/build-steps`, input)
}

export function updateBuildStep(id: string, stepId: number, input: BuildStepInput): Promise<BuildStep> {
  return api.put<BuildStep>(`/projects/${id}/build-steps/${stepId}`, input)
}

export function deleteBuildStep(id: string, stepId: number): Promise<void> {
  return api.del(`/projects/${id}/build-steps/${stepId}`)
}

export function reorderBuildSteps(id: string, steps: BuildStepInput[]): Promise<BuildStep[]> {
  return api.put<BuildStep[]>(`/projects/${id}/build-steps`, steps)
}

// ---------------- 发版配置 ----------------

export function getReleaseConfig(id: string): Promise<ReleaseConfig | null> {
  return api.get<ReleaseConfig | null>(`/projects/${id}/release-config`)
}

export function saveReleaseConfig(id: string, input: ReleaseConfigInput): Promise<ReleaseConfig> {
  return api.post<ReleaseConfig>(`/projects/${id}/release-config`, input)
}

// ---------------- 锁定 ----------------

export function getLock(id: string): Promise<ProjectLock> {
  return api.get<ProjectLock>(`/projects/${id}/lock`)
}

export function updateLock(id: string, maxConcurrent: number): Promise<ProjectLock> {
  return api.put<ProjectLock>(`/projects/${id}/lock`, { maxConcurrent })
}

export function claimWrite(id: string): Promise<ProjectLock> {
  return api.post<ProjectLock>(`/projects/${id}/lock/claim`)
}

export function releaseWrite(id: string): Promise<ProjectLock> {
  return api.post<ProjectLock>(`/projects/${id}/lock/release`)
}

// ---------------- 研发主线（CAP-13：Requirement / Design / WorkItem） ----------------

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

// ---------------- worktree ----------------

export function listWorktrees(id: string): Promise<WorktreeInfo[]> {
  return api.get<WorktreeInfo[]>(`/projects/${id}/worktrees`)
}
