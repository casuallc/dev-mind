// CAP-03 文档管理类型，与后端 devmind-docs 对齐

export type DocKind = 'requirement' | 'design' | 'api-suite' | 'report'
export type DocStatus = 'draft' | 'pending_confirm' | 'frozen'

export interface DocMeta {
  id: number
  kind: DocKind
  requirementId: string | null
  workItemId: string | null
  projectId: string | null
  title: string
  currentVersion: number
  status: DocStatus
  tags: string[]
  filePath: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface DocDetail extends DocMeta {
  versionNo: number
  contentMd: string
  changeNote: string | null
  commitSha: string | null
}

export interface DocVersion {
  documentId: number
  versionNo: number
  changeNote: string | null
  commitSha: string | null
  createdBy: string
  createdAt: string
}

export interface DocInput {
  kind: DocKind
  requirementId?: string
  workItemId?: string
  projectId?: string
  title: string
  tags?: string[]
  template?: string
  contentMd?: string
}

export interface SaveVersionInput {
  contentMd: string
  changeNote?: string
}

export interface DiffResult {
  hasChanges: boolean
  lines: string[]
  additions: number
  deletions: number
}

export interface DocTemplate {
  kind: DocKind
  name: string
  content: string
}

export const KIND_LABEL: Record<DocKind, string> = {
  requirement: '需求文档',
  design: '技术方案',
  'api-suite': 'API 套件',
  report: '报告',
}

export const STATUS_LABEL: Record<DocStatus, string> = {
  draft: '草稿',
  pending_confirm: '待确认',
  frozen: '已冻结',
}
