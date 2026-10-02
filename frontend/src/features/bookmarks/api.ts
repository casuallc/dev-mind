// CAP-64 收藏夹 API。owner 校验全在服务端，前端不做权限判断（越权拿到的是 404）。
import { api } from '../../shared/api/client'
import type {
  Bookmark,
  BookmarkFilter,
  BookmarkGroup,
  BookmarkGroupPayload,
  BookmarkPayload,
  BookmarkShare,
  BookmarkTagSummary,
  ImportNode,
  ImportResult,
  ProbeResult,
  SharedWithMe,
} from './types'

function query(filter: BookmarkFilter): string {
  const p = new URLSearchParams()
  if (filter.ungrouped) p.set('ungrouped', 'true')
  else if (filter.groupId != null) p.set('groupId', String(filter.groupId))
  for (const t of filter.tagIds ?? []) p.append('tagIds', String(t))
  if (filter.keyword?.trim()) p.set('keyword', filter.keyword.trim())
  if (filter.status && filter.status !== 'ALL') p.set('status', filter.status)
  const s = p.toString()
  return s ? `?${s}` : ''
}

// ---- FR-01 收藏条目 ----

export const listBookmarks = (filter: BookmarkFilter = {}) => api.get<Bookmark[]>(`/bookmarks${query(filter)}`)
export const createBookmark = (body: BookmarkPayload) => api.post<Bookmark>('/bookmarks', body)
export const updateBookmark = (id: string, body: BookmarkPayload) => api.put<Bookmark>(`/bookmarks/${id}`, body)
export const deleteBookmark = (id: string) => api.del<void>(`/bookmarks/${id}`)

/** FR-06 打开动作落 last_visited_at（失败静默，调用方 catch 掉） */
export const visitBookmark = (id: string) => api.post<void>(`/bookmarks/${id}/visit`)

// ---- FR-04 探测 ----

export const probeBookmark = (id: string) => api.post<ProbeResult>(`/bookmarks/${id}/probe`)
/** 批量探测异步执行，返回受理条数；进度靠轮询列表的 lastCheckedAt 体现 */
export const probeBookmarks = (ids: string[]) =>
  api.post<{ accepted: number }>('/bookmarks/probe-batch', { ids: ids.map(Number) })

// ---- FR-09 导入 ----

/** 导入浏览器书签树（Netscape HTML 已由 parseBookmarkFile 解析为结构化树） */
export const importBookmarks = (nodes: ImportNode[]) => api.post<ImportResult>('/bookmarks/import', { nodes })

// ---- FR-02 分组与转移 ----

export const moveBookmarks = (ids: string[], groupId: number | null) =>
  api.put<{ moved: number }>('/bookmarks/move', { ids: ids.map(Number), groupId })

export const listGroups = () => api.get<BookmarkGroup[]>('/bookmark-groups')
export const createGroup = (body: BookmarkGroupPayload) => api.post<BookmarkGroup>('/bookmark-groups', body)
export const updateGroup = (id: number, body: BookmarkGroupPayload) =>
  api.put<BookmarkGroup>(`/bookmark-groups/${id}`, body)
/** cascade=false（默认）收藏落未分组、子分组上提；cascade=true 整棵子树连收藏一起删 */
export const deleteGroup = (id: number, cascade = false) =>
  api.del<void>(`/bookmark-groups/${id}?cascade=${cascade}`)

// ---- FR-03 标签 ----

export const listTags = () => api.get<BookmarkTagSummary[]>('/bookmark-tags')
/** 同名幂等返回既有标签（表单里「边填边建」依赖此口径） */
export const createTag = (name: string) => api.post<BookmarkTagSummary>('/bookmark-tags', { name })
export const renameTag = (id: number, name: string) =>
  api.put<BookmarkTagSummary>(`/bookmark-tags/${id}`, { name })
export const deleteTag = (id: number) => api.del<void>(`/bookmark-tags/${id}`)

// ---- FR-05 账号明文（按次取） ----

export const getAccountSecret = (bookmarkId: string, accountId: string) =>
  api.get<{ password: string | null }>(`/bookmarks/${bookmarkId}/accounts/${accountId}/secret`)

// ---- FR-07 分享 ----

export const listShares = () => api.get<BookmarkShare[]>('/bookmark-shares')
export const createShare = (body: { bookmarkId?: number | null; groupId?: number | null; targetUser: string }) =>
  api.post<BookmarkShare>('/bookmark-shares', body)
export const deleteShare = (id: number) => api.del<void>(`/bookmark-shares/${id}`)

/** 接收方只读命名空间（密码字段被服务端剔除） */
export const sharedWithMe = () => api.get<SharedWithMe[]>('/bookmarks/shared-with-me')
export const copyShared = (bookmarkId: string, groupId: number | null) =>
  api.post<Bookmark>('/bookmarks/shared-with-me/copy', { bookmarkId: Number(bookmarkId), groupId })
