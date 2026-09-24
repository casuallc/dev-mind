// CAP-64 收藏夹类型（对齐 devmind-bookmark 的出参）。

/** FR-04 探测状态：灰（未探测）/ 绿（OK）/ 红（FAIL） */
export type BookmarkStatus = 'UNKNOWN' | 'OK' | 'FAIL'

/** FR-05 账号出参：passwordMasked 只在「我的收藏」视图出现，分享视图里该字段被服务端整个剔除 */
export interface BookmarkAccount {
  id: string
  label: string
  username?: string
  passwordMasked?: string
  hasPassword: boolean
  note?: string
  sortOrder: number
}

export interface BookmarkTag {
  id: number
  name: string
}

/** 标签清单项（带被引用条数） */
export interface BookmarkTagSummary extends BookmarkTag {
  bookmarkCount: number
}

export interface Bookmark {
  id: string
  groupId: number | null
  title: string
  url: string
  description?: string
  faviconUrl?: string
  sortOrder: number
  lastStatus: BookmarkStatus
  lastStatusCode?: string
  lastLatencyMs?: number
  lastCheckedAt?: string
  lastVisitedAt?: string
  createdAt?: string
  updatedAt?: string
  tags: BookmarkTag[]
  accounts: BookmarkAccount[]
}

export interface BookmarkGroup {
  id: number
  parentId: number | null
  name: string
  sortOrder: number
  bookmarkCount: number
  children: BookmarkGroup[]
}

/** FR-05 账号整组提交：带 id = 更新，无 id = 新建，未出现 = 删除；password 留空 = 不修改 */
export interface BookmarkAccountPayload {
  id?: string
  label: string
  username?: string
  password?: string
  /** 显式清除已存密码（编辑态「清空密码」勾选） */
  clearPassword?: boolean
  note?: string
  sortOrder?: number
}

export interface BookmarkPayload {
  title: string
  url: string
  description?: string
  groupId?: number | null
  tagIds?: number[]
  accounts?: BookmarkAccountPayload[]
  faviconUrl?: string
  sortOrder?: number
}

export interface BookmarkGroupPayload {
  name: string
  parentId?: number | null
  sortOrder?: number
}

export interface ProbeResult {
  id: string
  status: BookmarkStatus
  statusCode?: string
  latencyMs?: number
  checkedAt: string
}

/** FR-07 我发出的分享 */
export interface BookmarkShare {
  id: number
  bookmarkId: number | null
  bookmarkTitle?: string
  groupId: number | null
  groupName?: string
  targetUser: string
  createdAt: string
}

/** FR-07 我收到的分享（按分享者分组） */
export interface SharedWithMe {
  owner: string
  groups: BookmarkGroup[]
  bookmarks: Bookmark[]
}

export interface BookmarkFilter {
  groupId?: number | null
  ungrouped?: boolean
  tagIds?: number[]
  keyword?: string
  status?: BookmarkStatus | 'ALL'
}

/** FR-06 打开动作的落点：window.open 只接受 http/https（与后端校验同一口径） */
export function isOpenable(url: string): boolean {
  return /^https?:\/\//i.test(url.trim())
}
