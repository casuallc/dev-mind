// CAP-11 发版执行器接口封装
import { api } from '../../shared/api/client'
import type { CreateReleaseInput, ReleaseConfig, ReleaseConfigInput, ReleaseRecord } from './types'

// ---------------- 发版配置（/projects/{id}/release-config） ----------------

export function getReleaseConfig(id: string): Promise<ReleaseConfig | null> {
  return api.get<ReleaseConfig | null>(`/projects/${id}/release-config`)
}

export function saveReleaseConfig(id: string, input: ReleaseConfigInput): Promise<ReleaseConfig> {
  return api.post<ReleaseConfig>(`/projects/${id}/release-config`, input)
}

// ---------------- 发版记录 ----------------

export function createRelease(input: CreateReleaseInput): Promise<ReleaseRecord> {
  return api.post<ReleaseRecord>('/releases', input)
}

export function getRelease(id: number): Promise<ReleaseRecord> {
  return api.get<ReleaseRecord>(`/releases/${id}`)
}

export function executeRelease(id: number): Promise<ReleaseRecord> {
  return api.post<ReleaseRecord>(`/releases/${id}/execute`)
}

export function rollbackRelease(id: number): Promise<ReleaseRecord> {
  return api.post<ReleaseRecord>(`/releases/${id}/rollback`)
}

export function listReleases(projectId: string, status?: string): Promise<ReleaseRecord[]> {
  const q = status ? `?projectId=${projectId}&status=${status}` : `?projectId=${projectId}`
  return api.get<ReleaseRecord[]>(`/releases${q}`)
}

export function deleteRelease(id: number): Promise<void> {
  return api.del(`/releases/${id}`)
}

/** 全量日志为纯文本，走原生 fetch */
export async function getReleaseLogs(id: number): Promise<string> {
  const res = await fetch(`/api/releases/${id}/logs`)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.text()
}
