// Skill 管理（基础模块）API
import { api } from '../../shared/api/client'
import type {
  Skill,
  SkillDetail,
  SkillFileContent,
  SkillFileInput,
  SkillFileMeta,
  SkillInput,
  SkillPackage,
  SkillPage,
  SkillStatus,
} from './types'

// ---------------- 本体 ----------------

export const listSkills = (params: {
  scope?: string
  projectId?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
} = {}) => {
  const q = new URLSearchParams()
  if (params.scope) q.set('scope', params.scope)
  if (params.projectId) q.set('projectId', params.projectId)
  if (params.status) q.set('status', params.status)
  if (params.keyword) q.set('keyword', params.keyword)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  return api.get<SkillPage>(`/skills?${q.toString()}`)
}

export const getSkill = (id: string) => api.get<SkillDetail>(`/skills/${id}`)
export const createSkill = (input: SkillInput) => api.post<Skill>('/skills', input)
export const updateSkill = (id: string, input: SkillInput) =>
  api.put<Skill>(`/skills/${id}`, input)
export const updateSkillStatus = (id: string, status: SkillStatus) =>
  api.put<Skill>(`/skills/${id}/status?status=${status}`)
export const deleteSkill = (id: string) => api.del(`/skills/${id}`)

// ---------------- 附件文件 ----------------

export const listSkillFiles = (skillId: string) =>
  api.get<SkillFileMeta[]>(`/skills/${skillId}/files`)
export const getSkillFile = (skillId: string, fileId: string) =>
  api.get<SkillFileContent>(`/skills/${skillId}/files/${fileId}`)
export const createSkillFile = (skillId: string, input: SkillFileInput) =>
  api.post<SkillFileMeta>(`/skills/${skillId}/files`, input)
export const updateSkillFile = (skillId: string, fileId: string, input: SkillFileInput) =>
  api.put<SkillFileMeta>(`/skills/${skillId}/files/${fileId}`, input)
export const deleteSkillFile = (skillId: string, fileId: string) =>
  api.del(`/skills/${skillId}/files/${fileId}`)

// ---------------- 导出（为注入预留） ----------------

export const exportSkillPackages = (ids: string[]) =>
  api.get<SkillPackage>(`/skills/export?ids=${ids.map(encodeURIComponent).join(',')}`)
