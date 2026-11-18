// CAP-29 全局代码仓库登记 API（/api/repos，写操作仅 ADMIN）
import { api } from '../../shared/api/client'
import type {
  GitRepo,
  GitRepoPayload,
  PageViewResult,
  RepoCommitDetail,
  RepoCommitView,
} from './types'

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

// ---------------- CAP-111 提交日志（搜索 / 详情 / 服务端分页） ----------------

/**
 * 分支提交日志：branch 缺省 = 仓库默认分支；keyword 匹配提交信息（包含）或提交哈希（前缀）；
 * page 从 0 起，size 默认 20（后端硬上限 100）。
 */
export const listRepoCommits = (
  id: number,
  opts: { branch?: string; keyword?: string; page?: number; size?: number } = {},
) => {
  const params = new URLSearchParams()
  if (opts.branch) params.set('branch', opts.branch)
  if (opts.keyword) params.set('keyword', opts.keyword)
  if (opts.page != null) params.set('page', String(opts.page))
  if (opts.size != null) params.set('size', String(opts.size))
  const q = params.toString()
  return api.get<PageViewResult<RepoCommitView>>(`/repos/${id}/commits${q ? `?${q}` : ''}`)
}
/** 提交详情：完整提交信息 + 变更文件清单 */
export const getRepoCommit = (id: number, sha: string) =>
  api.get<RepoCommitDetail>(`/repos/${id}/commits/${sha}`)
