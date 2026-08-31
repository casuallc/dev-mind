// CAP-02 项目接口封装
import { api } from '../../shared/api/client'
import type {
  BuildStep,
  BuildStepInput,
  ContextSummary,
  Project,
  ProjectInput,
  ProjectLock,
  ProjectRepo,
  ProjectRepoInput,
  ProjectServer,
  ReleaseConfig,
  ReleaseConfigInput,
  Requirement,
  RequirementInput,
  RequirementStatus,
  ServerInput,
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

// ---------------- 需求（P0-5 项目内主线） ----------------

export function listRequirements(projectId: string, status?: string): Promise<Requirement[]> {
  const q = status && status !== 'ALL' ? `?status=${status}` : ''
  return api.get<Requirement[]>(`/projects/${projectId}/requirements${q}`)
}

export function createRequirement(projectId: string, input: RequirementInput): Promise<Requirement> {
  return api.post<Requirement>(`/projects/${projectId}/requirements`, input)
}

export function updateRequirement(projectId: string, reqId: string, input: RequirementInput): Promise<Requirement> {
  return api.put<Requirement>(`/projects/${projectId}/requirements/${reqId}`, input)
}

export function updateRequirementStatus(
  projectId: string,
  reqId: string,
  status: RequirementStatus,
): Promise<Requirement> {
  return api.put<Requirement>(`/projects/${projectId}/requirements/${reqId}/status`, { status })
}

export function deleteRequirement(projectId: string, reqId: string): Promise<void> {
  return api.del(`/projects/${projectId}/requirements/${reqId}`)
}

// ---------------- worktree ----------------

export function listWorktrees(id: string): Promise<WorktreeInfo[]> {
  return api.get<WorktreeInfo[]>(`/projects/${id}/worktrees`)
}
