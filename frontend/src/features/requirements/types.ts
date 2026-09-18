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
  /** CAP-27：AI 实际耗时（秒，需求下所有会话时长汇总，活跃会话算到当前） */
  agentSeconds?: number
  /** CAP-27：Jira 预估工时（秒，托管字段随同步刷新），仅 JIRA 来源 */
  estimatedSeconds?: number
  /** CAP-27：Jira 已用工时（秒，托管字段随同步/回写刷新），仅 JIRA 来源 */
  spentSeconds?: number
  /** CAP-38 FR-01：需求分析阶段已跳过（流程不可逆引导标记） */
  analysisSkipped?: boolean
  /** CAP-38 FR-01：方案设计阶段已跳过 */
  designSkipped?: boolean
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

// ---- CAP-47 自建需求推送到 Jira ----

// Jira 选项 / 创建字段元数据这几组类型同时被「个人 Jira 推送模板」面板（integrations 能力）使用，
// 定义已上移到 features/integrations/types：既 import 供本文件内引用，又原样转出，推送侧调用点无需改动。
import type {
  JiraOption,
  JiraPushOptions,
  JiraAssignableUser,
  JiraCreateFieldControl,
  JiraCreateField,
  JiraCreateFields,
} from '../integrations/types'

export type {
  JiraOption,
  JiraPushOptions,
  JiraAssignableUser,
  JiraCreateFieldControl,
  JiraCreateField,
  JiraCreateFields,
}

/** CAP-47 FR-02：候选 Jira 实例（TYPE_JIRA + ENABLED） */
export interface JiraPushInstance {
  id: number
  name: string
  baseUrl: string
}

/** CAP-47 FR-02：推送弹窗一次性数据源（打开弹窗只发这一个请求） */
export interface JiraPushTargets {
  instances: JiraPushInstance[]
  defaultIntegrationId?: number
  defaultJiraProjectKey?: string
  jiraProjects: JiraOption[]
  issueTypes: JiraOption[]
  priorities: JiraOption[]
  /** 各字段默认值（只取需求当前值，弹窗内可改）；只含与 Jira 同域的字段——
   *  平台 assignee 是人名、Jira 要登录名，故不从需求回填经办人，priority 也仅在命中实例词表时才有值。
   *  任务类型/经办人/动态字段的默认值不在此处：它们来自 templates（个人推送模板），
   *  由弹窗在「实例+项目+类型」组合选定时带入。 */
  defaults: {
    title?: string
    description?: string
    priority?: string
    labels?: string[]
    dueDate?: string // yyyy-MM-dd
  }
  /** CAP-47 FR-10：当前用户的全部个人推送模板（随本响应一次给齐，弹窗按组合本地匹配）。
   *  extraFields 的值是 **Jira API 形态**（{id} / [{id}]），前端须反向转换成表单形态再回填。 */
  templates: JiraPushTemplateRef[]
  /** 写身份来源：PERSONAL 个人账号 / BOT 实例机器人 / NONE 都没有（须先绑定，提交禁用） */
  identitySource: 'PERSONAL' | 'BOT' | 'NONE'
  /** 该项目在此实例上被 enabled 的同步配置覆盖 → 托管字段会自动刷新 */
  syncCovered: boolean
  /** 选项拉取失败的原因；空表可能是「确实没有可创建的类型」，也可能是这里失败（须区分提示） */
  optionsError?: string
}

/** CAP-47 FR-10：个人推送模板引用（匹配键 = integrationId + jiraProjectKey + issueTypeId） */
export interface JiraPushTemplateRef {
  integrationId: number
  jiraProjectKey: string
  issueTypeId: string
  priorityName?: string
  assigneeName?: string
  labels?: string[]
  extraFields?: Record<string, unknown>
}

/** CAP-47 FR-03：推送入参（backlinkUrl 由前端按 window.location.origin 拼，服务端拼回链文案） */
export interface JiraPushInput {
  integrationId: number
  jiraProjectKey: string
  issueTypeId: string
  summary: string
  description?: string
  backlinkUrl: string
  priorityName?: string
  assigneeName?: string
  labels?: string[]
  dueDate?: string // yyyy-MM-dd
  /** CAP-47 FR-08：动态字段（键=平台字段 id，值=按 control 组装好的平台取值，见 modal 的 toJiraValue） */
  extraFields?: Record<string, unknown>
}

/** CAP-47：推送/刷新结果（回读失败时 remoteStatus/issueType 为空，走「从 Jira 刷新」补齐） */
export interface JiraPushResult {
  externalKey: string
  externalUrl?: string
  remoteStatus?: string
  issueType?: string
  syncCovered: boolean
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
  deployments: { id: number; status: string; env?: string; agentNodeId?: string; buildId?: number; workItemId?: string; createdBy?: string; createdAt: string; finishedAt?: string }[]
  releases: { id: number; version?: string; status: string; executor?: string; rollbackOf?: number; createdAt: string; finishedAt: string }[]
  artifacts: { id: number; type: string; name?: string; path?: string; producerType?: string; createdAt: string }[]
  timeline: { time: string; type: string; label: string; refId: string }[]
}
