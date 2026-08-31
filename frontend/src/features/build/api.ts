// CAP-08 构建执行器接口封装
import { api } from '../../shared/api/client'
import type { BuildConfig, BuildRecord, BuildStatus, TriggerInput } from './types'

export function getBuildConfig(id: string): Promise<BuildConfig> {
  return api.get<BuildConfig>(`/projects/${id}/build-config`)
}

export function saveBuildConfig(id: string, input: Partial<BuildConfig>): Promise<BuildConfig> {
  return api.put<BuildConfig>(`/projects/${id}/build-config`, input)
}

export function triggerBuild(id: string, input: TriggerInput): Promise<BuildRecord> {
  return api.post<BuildRecord>(`/projects/${id}/builds`, input)
}

export function getBuild(buildId: number): Promise<BuildRecord> {
  return api.get<BuildRecord>(`/builds/${buildId}`)
}

export function listBuilds(projectId: string, status?: BuildStatus): Promise<BuildRecord[]> {
  const q = status ? `?projectId=${projectId}&status=${status}` : `?projectId=${projectId}`
  return api.get<BuildRecord[]>(`/builds${q}`)
}

/** 日志为纯文本，走原生 fetch（api 客户端按 JSON 解析） */
export async function getBuildLogs(buildId: number): Promise<string> {
  const res = await fetch(`/api/builds/${buildId}/logs`)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.text()
}
