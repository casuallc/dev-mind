// CAP-10 测试执行器类型，与后端 devmind-test 模块对齐
export type TestSuiteKind = 'api' | 'smoke'
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
  requirementId: string | null
  suiteIds: number[]
  deploymentId: number | null
  serverId: number | null
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
}

export interface CreateTestRunInput {
  projectId: string
  requirementId?: string
  suiteIds: number[]
  deploymentId?: number
  serverId?: number
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
