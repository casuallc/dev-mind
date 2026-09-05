// CAP-29 全局代码仓库登记 API（/api/repos，写操作仅 ADMIN）
import { api } from '../../shared/api/client'
import type { GitRepo, GitRepoPayload } from './types'

export const listRepos = () => api.get<GitRepo[]>('/repos')
export const createRepo = (body: GitRepoPayload) => api.post<GitRepo>('/repos', body)
export const updateRepo = (id: number, body: Partial<GitRepoPayload>) =>
  api.put<GitRepo>(`/repos/${id}`, body)
export const deleteRepo = (id: number) => api.del(`/repos/${id}`)
/** 手动抓取远端最新代码与分支（CLONE 行） */
export const fetchRepo = (id: number) => api.post(`/repos/${id}/fetch`)
/** 克隆失败重试 / 强制重新克隆（CLONE 行） */
export const recloneRepo = (id: number) => api.post(`/repos/${id}/clone`)
