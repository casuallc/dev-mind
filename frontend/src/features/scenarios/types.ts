// 场景（CAP-33）的类型定义，与后端 devmind-session 模块对齐。
// 场景 = 命名模板 + 预装配上下文包（skills/docs/知识 tags/场景背景），会话与问答可挂。

export interface Scenario {
  id: number
  code: string
  name: string
  description?: string
  /** prompt 骨架（占位符 {{task}}/{{project}}/{{branch}}/{{requirement}}） */
  promptSkeleton?: string
  /** ①层绑定 skill ids */
  skillIds: string[]
  /** ①层绑定 doc ids */
  docIds: number[]
  /** ①层绑定知识 tags */
  knowledgeTags: string[]
  /** 场景背景（→ CLAUDE.md「场景背景」节） */
  extraContextMd?: string
  model?: string
  permissionMode?: string
  agentNodeId?: string
  scope: 'GLOBAL' | 'PROJECT'
  projectId?: string
  enabled: boolean
  sortOrder: number
  createdAt: string
  updatedAt: string
}

/** 创建/更新入参（id/时间戳由后端管） */
export type ScenarioInput = Partial<Omit<Scenario, 'id' | 'createdAt' | 'updatedAt'>>

/** 装配清单项（FR-07：来源标注 scenario | project-auto | request） */
export interface ManifestItem {
  kind: 'knowledge' | 'skill' | 'doc'
  ref: string
  name: string
  scope?: string
  source: 'scenario' | 'project-auto' | 'request'
  extra?: Record<string, unknown>
}

/** GET /api/scenarios/{code}/preview（dryRun 装配结果） */
export interface ScenarioPreview {
  renderedTaskSpec: string
  /** false = 装配为空，真实创建按无上下文启动 */
  hasContext: boolean
  claudeMd?: string
  items: ManifestItem[]
  skills: string[]
  docs: { docId: string; title: string }[]
  entries: number
  totalBytes: number
  sha256?: string
}

/** GET /api/sessions/{id}/context 与 /api/chats/{id}/context（FR-07 快照） */
export interface ContextSnapshot {
  schemaVersion: number
  scenarioCode?: string
  scenarioName?: string
  assembledAt?: string
  renderedTaskSpecPreview?: string
  hasExtraContext: boolean
  items: ManifestItem[]
  package: { entries: number; totalBytes: number; sha256: string }
}

/** 项目「上下文」页资产项（GET /api/projects/{id}/context-assets） */
export interface ProjectAssetItem {
  ref: string
  name: string
  summary?: string
  extra?: Record<string, unknown>
}

export interface AssetGroup {
  kind: 'knowledge' | 'skill' | 'doc' | string
  items: ProjectAssetItem[]
}
