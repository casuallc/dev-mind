// CAP-02 项目接口封装
import { api } from '../../shared/api/client'
import type {
  BuildStep,
  BuildStepInput,
  ContextSummary,
  EnvironmentInput,
  Project,
  ProjectEnvironment,
  ProjectInput,
  ProjectLock,
  ProjectRepo,
  ProjectRepoInput,
  ProjectServer,
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

// ---------------- CAP-23 仓库克隆 ----------------

/** 触发/重试单库克隆 */
export function cloneRepo(id: string, repoId: number): Promise<ProjectRepo> {
  return api.post<ProjectRepo>(`/projects/${id}/repos/${repoId}/clone`)
}

/** 重试项目内全部 FAILED 库 */
export function retryProjectClone(id: string): Promise<ProjectRepo[]> {
  return api.post<ProjectRepo[]>(`/projects/${id}/clone/retry`)
}

/** 克隆日志回放（非实时；实时走 WS /ws/repo-clones/clone-<repoId>） */
export function getCloneLogs(id: string, repoId: number): Promise<{ logs: string }> {
  return api.get<{ logs: string }>(`/projects/${id}/repos/${repoId}/clone/logs`)
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

// ---------------- worktree ----------------

export function listWorktrees(id: string): Promise<WorktreeInfo[]> {
  return api.get<WorktreeInfo[]>(`/projects/${id}/worktrees`)
}
