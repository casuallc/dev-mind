// CAP-04 知识库类型定义，与后端 devmind-knowledge 模块对齐

export type EntryScope = 'global' | 'project'

export interface KnowledgeEntry {
  id: number
  scope: EntryScope
  projectId: string | null
  name: string
  path: string
  contentMd: string
  tags: string[]
  sourceProject: string | null
  hitCount: number
  status: 'active' | 'deprecated'
  createdAt: string
  updatedAt: string
}

export interface KnowledgeEntryInput {
  scope: EntryScope
  projectId?: string
  name: string
  contentMd: string
  tags?: string[]
  sourceProject?: string
  status?: 'active' | 'deprecated'
}

export type ProposalStatus = 'open' | 'adopted' | 'rejected'

export interface KnowledgeProposal {
  id: number
  title: string
  contentMd: string
  targetScope: EntryScope
  targetProjectId: string | null
  sourceSessionId: string | null
  status: ProposalStatus
  adoptedTo: string | null
  adoptedProjectId: string | null
  createdAt: string
  adoptedAt: string | null
}

export interface KnowledgeProposalInput {
  title: string
  contentMd: string
  targetScope: EntryScope
  targetProjectId?: string
  sourceSessionId?: string
}

export interface PreviewResult {
  content: string
  entriesUsed: KnowledgeEntry[]
}
