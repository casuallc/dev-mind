// CAP-87 更新日志：内容来自随安装包分发的 CHANGELOG.md，只读。
export type ChangelogType = 'ADDED' | 'IMPROVED' | 'FIXED' | 'BREAKING'

export interface ChangelogEntryBrief {
  version: string
  releasedAt: string | null
  types: ChangelogType[]
  refs: string[]
  summary: string
}

export interface ChangelogEntryView extends ChangelogEntryBrief {
  contentMd: string
}

export interface ChangelogPageView {
  items: ChangelogEntryBrief[]
  total: number
  page: number
  size: number
  /** 数据源文件是否可读；false 时页面展示放行指引而不是空表 */
  available: boolean
  sourcePath: string | null
  loadedAt: string | null
}

export interface ChangelogQuery {
  keyword?: string
  version?: string
  from?: string
  to?: string
  page?: number
  size?: number
}
