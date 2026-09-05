// CAP-13 研发主线的类型定义：Requirement（业务目标）/ Design（解决方案）/ WorkItem（工作单元），与后端 devmind-project 模块对齐

export type RequirementStatus =
  | 'DRAFT' | 'ANALYZING' | 'DESIGNING' | 'IN_PROGRESS' | 'ACCEPTANCE' | 'DONE' | 'CANCELLED'

/** 需求类型（对齐 Jira issue type，同步直接映射） */
export type RequirementType = 'FEATURE' | 'BUG' | 'IMPROVEMENT' | 'TASK'

/** 需求来源：JIRA 同步 / LOCAL 自建 */
export type RequirementSource = 'JIRA' | 'LOCAL'

export interface Requirement {
  id: string
  projectId: string
  seq: number
  code: string // REQ-<seq>
  title: string
  description?: string
  status: RequirementStatus
  type?: RequirementType
  ownerId?: string
  docId?: number
  source: RequirementSource
  priority?: string
  assignee?: string
  reporter?: string
  labels?: string[]
  fixVersions?: string[]
  dueDate?: string // yyyy-MM-dd
  /** Jira issue key（如 PROJ-123），仅 JIRA 来源 */
  externalKey?: string
  /** Jira issue 链接，仅 JIRA 来源 */
  externalUrl?: string
  /** Jira 远端状态（随同步刷新），仅 JIRA 来源 */
  remoteStatus?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
}

/** 需求分页响应（对应后端 PageView） */
export interface RequirementPage {
  items: Requirement[]
  total: number
  page: number
  size: number
}

/** CAP-19 FR-08：Jira 工作流转换（id 执行时回传，toStatus 为目标状态名） */
export interface JiraTransition {
  id: string
  name: string
  toStatus?: string
}

/** CAP-19 FR-08：转换执行结果（已执行的转换 + 刷新后的远端状态） */
export interface JiraTransitionResult {
  transition: JiraTransition
  remoteStatus?: string
}

export interface RequirementInput {
  title: string
  description?: string
  ownerId?: string
  docId?: number
  type?: RequirementType
  priority?: string
  assignee?: string
  reporter?: string
  labels?: string[]
  fixVersions?: string[]
  dueDate?: string // yyyy-MM-dd
}

export type WorkItemType = 'DESIGN' | 'DEVELOPMENT' | 'TEST' | 'DOCUMENT' | 'REVIEW'
export type WorkItemStatus = 'TODO' | 'IN_PROGRESS' | 'BLOCKED' | 'DONE' | 'CANCELLED'

export interface WorkItem {
  id: string
  projectId: string
  requirementId: string
  designId?: string
  seq: number
  code: string // WI-<seq>
  type: WorkItemType
  title: string
  spec?: string
  status: WorkItemStatus
  ownerId?: string
  branchSlug?: string
  createdBy?: string
  createdAt: string
  updatedAt: string
}

export interface WorkItemInput {
  type?: WorkItemType
  title: string
  spec?: string
  designId?: string
  ownerId?: string
  branchSlug?: string
}

export type DesignStatus = 'DRAFT' | 'CONFIRMED' | 'DISCARDED'

export interface Design {
  id: string
  projectId: string
  requirementId: string
  docId?: number
  version: number
  status: DesignStatus
  createdBy?: string
  createdAt: string
  updatedAt: string
}

// ---- CAP-14 需求流程 ----

/** 拆分草稿项：AI 生成、人编辑后随 confirmSplit 提交固化 */
export interface SplitDraftItem {
  type: WorkItemType
  title: string
  spec: string
  dependsOn: number[]
}

export interface SplitDraft {
  sessionId?: string
  items: SplitDraftItem[]
}

/** 流程阶段动作返回的会话（只关心 id/status，用于提示与跳转） */
export interface FlowSession {
  id: string
  status: string
}

// 需求主线聚合视图（app 组装层 /requirements/{requirementId}/overview）
export interface RequirementOverview {
  requirement: Requirement
  workItems: WorkItem[]
  docs: { id: number; kind: string; title: string; status: string; currentVersion: number; updatedAt: string }[]
  sessions: { id: string; status: string; taskSpec: string; model?: string; workItemId?: string; createdAt: string; finishedAt?: string }[]
  builds: { id: number; status: string; branch?: string; commit?: string; artifactRef?: string; workItemId?: string; createdAt: string; finishedAt?: string }[]
  testRuns: { id: number; status: string; summaryJson?: string; reportDocId?: number; triggeredBy?: string; workItemId?: string; createdAt: string; finishedAt?: string }[]
  deployments: { id: number; status: string; env?: string; serverId?: number; buildId?: number; workItemId?: string; createdBy?: string; createdAt: string; finishedAt?: string }[]
  releases: { id: number; version?: string; status: string; executor?: string; rollbackOf?: number; createdAt: string; finishedAt: string }[]
  artifacts: { id: number; type: string; name?: string; path?: string; producerType?: string; createdAt: string }[]
  timeline: { time: string; type: string; label: string; refId: string }[]
}
