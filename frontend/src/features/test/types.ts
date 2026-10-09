// CAP-10 测试执行器类型，与后端 devmind-test 模块对齐
import type { WorkItemBrief } from '../../shared/types'

export type TestSuiteKind = 'api' | 'smoke' | 'script'
export type TestSuiteSource = 'openapi' | 'manual'
export type TestCaseKind = 'http' | 'health'
export type TestRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED'
export type CaseResultStatus = 'pass' | 'fail' | 'skip'

export interface TestSuite {
  id: number
  projectId: string
  name: string
  kind: TestSuiteKind
  source: TestSuiteSource
  docId: number | null
  caseCount: number
  cases: TestCase[]
  createdAt: string
}

export interface TestCase {
  id: number
  suiteId: number
  sort: number
  name: string
  kind: TestCaseKind
  method: string
  path: string
  params: Record<string, string>
  headers: Record<string, string>
  body: string | null
  expected: Record<string, unknown>
  enabled: boolean
  updatedAt: string
}

/** 用例写入（整体替换语义：不在列表中的现有用例被删除） */
export interface TestCaseInput {
  id?: number
  name: string
  kind: string
  method: string
  path: string
  params: Record<string, string>
  headers: Record<string, string>
  body: string | null
  expected: Record<string, unknown>
  enabled: boolean
}

export interface RunSummary {
  total: number
  passed: number
  failed: number
  skipped: number
}

export interface CaseResult {
  id: number
  caseId: number
  suiteId: number
  sort: number
  name: string
  status: CaseResultStatus
  requestSummary: string | null
  responseSummary: string | null
  error: string | null
  duration: number | null
}

export interface TestRun {
  id: number
  projectId: string
  workItemId: string | null
  suiteIds: number[]
  deploymentId: number | null
  /** 执行节点 id（runner；command 型健康检查在该节点执行） */
  agentNodeId: string | null
  environmentId: number | null
  baseUrl: string | null
  status: TestRunStatus
  summary: RunSummary
  reportDocId: number | null
  errorSummary: string | null
  triggeredBy: 'user' | 'deploy'
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  results: CaseResult[]
  /** 关联工作单元摘要（列表回链需求展示；未关联为 null） */
  workItem?: WorkItemBrief | null
}

export interface CreateTestRunInput {
  projectId: string
  workItemId?: string
  suiteIds: number[]
  deploymentId?: number
  agentNodeId?: string
  environmentId?: number
  baseUrl?: string
}

/** 缺陷线索（FR-06）：失败用例汇总，供流程层一键建缺陷单 */
export interface IssueDraft {
  runId: number
  caseId: number
  title: string
  requestSummary: string
  expected: string
  actual: string
  status: string
}

// ---------------- CAP-69 脚本套件（强制绑定项目） ----------------

/** env 条目：secret=true 的值在视图层恒为掩码 '******'，PUT 掩码原样回传 = 该条不变 */
export interface ScriptSuiteEnv {
  key: string
  value: string | null
  secret: boolean
}

export interface ScriptSuite {
  id: number
  projectId: string
  name: string
  repoUrl: string
  branch: string
  workSubdir: string | null
  command: string
  junitPath: string
  env: ScriptSuiteEnv[]
  agentNodeId: string | null
  timeoutSec: number | null
  workspaceKey: string | null
  createdAt: string
}

export interface ScriptSuiteInput {
  projectId: string
  name: string
  repoUrl: string
  branch: string
  workSubdir?: string
  command: string
  junitPath: string
  env?: ScriptSuiteEnv[]
  agentNodeId?: string
  timeoutSec?: number
  workspaceKey?: string
}

/** 触发运行：三字段全可选（agentNodeId 空=套件默认→平台默认；env 覆盖仅本次生效） */
export interface ScriptSuiteRunInput {
  agentNodeId?: string
  env?: Record<string, string>
  command?: string
}
