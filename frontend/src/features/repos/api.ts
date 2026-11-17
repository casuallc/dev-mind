// CAP-29 全局代码仓库登记 API（/api/repos，写操作仅 ADMIN）
import { api } from '../../shared/api/client'
import type { GitRepo, GitRepoPayload, RepoCommitView } from './types'

/** CAP-109：keyword 匹配名称/远端/本地路径（后端 DB lower like）；status 可选 ACTIVE/DISABLED */
export const listRepos = (keyword?: string, status?: string) => {
  const params = new URLSearchParams()
  if (keyword) params.set('keyword', keyword)
  if (status) params.set('status', status)
  const q = params.toString()
  return api.get<GitRepo[]>(`/repos${q ? `?${q}` : ''}`)
}
export const createRepo = (body: GitRepoPayload) => api.post<GitRepo>('/repos', body)
export const updateRepo = (id: number, body: Partial<GitRepoPayload>) =>
  api.put<GitRepo>(`/repos/${id}`, body)
export const deleteRepo = (id: number) => api.del(`/repos/${id}`)
/** 手动抓取远端最新代码与分支（CLONE 行） */
export const fetchRepo = (id: number) => api.post(`/repos/${id}/fetch`)
/** 克隆失败重试 / 强制重新克隆（CLONE 行） */
export const recloneRepo = (id: number) => api.post(`/repos/${id}/clone`)

// ---------------- CAP-109 提交历史 / 分支切换 ----------------

/** 分支提交历史：branch 缺省 = 仓库默认分支；limit 缺省 30（后端硬上限 200） */
export const listRepoCommits = (id: number, branch?: string, limit?: number) => {
  const params = new URLSearchParams()
  if (branch) params.set('branch', branch)
  if (limit) params.set('limit', String(limit))
  const q = params.toString()
  return api.get<RepoCommitView[]>(`/repos/${id}/commits${q ? `?${q}` : ''}`)
}
/** 切换服务端克隆的本地检出分支（仅 CLONE + READY） */
export const checkoutRepo = (id: number, branch: string) =>
  api.post(`/repos/${id}/checkout`, { branch })
