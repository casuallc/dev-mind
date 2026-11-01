// CAP-10 测试执行器类型，与后端 devmind-test 模块对齐；CAP-104 增补模块/用例规范/资产/证据。
import type { WorkItemBrief } from '../../shared/types'

export type TestSuiteKind = 'api' | 'smoke' | 'script'
export type TestSuiteSource = 'openapi' | 'manual'
export type TestCaseKind = 'http' | 'health'
export type TestRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'BLOCKED'
export type CaseResultStatus = 'pass' | 'fail' | 'skip' | 'blocked' | 'error'
export type ReviewStatus = 'DRAFT' | 'APPROVED' | 'DEPRECATED' | 'REJECTED'
export type CaseType = 'HTTP' | 'COMMAND' | 'MANUAL'
export type FailureCategory = 'ASSERTION' | 'INFRA' | 'TIMEOUT' | 'PRECHECK' | 'SCRIPT_ERROR' | 'UNKNOWN'
export type RunConclusion = 'PASS' | 'FAIL' | 'PARTIAL' | 'BLOCKED' | 'CANCELLED'
export type PlanRunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'BLOCKED' | 'CANCELLED'
export type PlanMode = 'SEQUENTIAL' | 'SHARD' | 'MATRIX'

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
  // CAP-104 资产属性
  moduleId: number | null
  owner: string | null
  tags: string | null
  reviewStatus: ReviewStatus | null
  commitHash: string | null
  sourceAssetId: number | null
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
  // CAP-104 资产字段
  caseKey: string | null
  type: CaseType | null
  priority: string | null
  tags: string | null
  caseVersion: number | null
  reviewStatus: ReviewStatus | null
  signature: string | null
  sourceAssetId: number | null
  spec: CaseSpec | null
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
  blocked: number
  error: number
}

export interface AssertionResult {
  source: string
  selector: string | null
  op: string
  expected: string | null
  actual: string | null
  pass: boolean
  message: string | null
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
  // CAP-104 过程采集与可解释判定
  nodeId: string | null
  attempt: number | null
  failureCategory: FailureCategory | null
  assertions: AssertionResult[] | null
  command: string | null
  exitCode: number | null
  envSnapshot: Record<string, unknown> | null
  evidenceId: number | null
  startedAt: string | null
  finishedAt: string | null
  manualReview: string | null
  manualNote: string | null
  /** CAP-104 M1.5：程序型用例输出（普通用例为 null） */
  program: ProgramRunView | null
}

export interface ProgramManifest {
  contractVersion: string
  name: string
  version: string
  runtime: string
  runtimeVersion: string | null
  entrypoint: string
  targetOs: string
  targetArch: string
  description: string | null
}

export interface ProgramArtifact {
  path: string
  sizeBytes: number
  sha256: string
}

/** 程序 result.json 的结构化输出（原始 JSON 见 RESULT_JSON 证据） */
export interface ProgramRunView {
  assetId: number | null
  contractVersion: string | null
  name: string | null
  version: string | null
  runtime: string | null
  runtimeVersion: string | null
  entrypoint: string | null
  targetOs: string | null
  targetArch: string | null
  status: string | null
  message: string | null
  metrics: Record<string, number>
  artifacts: ProgramArtifact[]
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
  /** CAP-104：SUITE / CASE / SCRIPT */
  runScope: string | null
  /** CAP-104：PASS / FAIL / PARTIAL / BLOCKED */
  conclusion: RunConclusion | null
  /** CAP-104 M2/M3：所属方案运行 / 方案项 / 重试次数 / 关联需求 / 分片键 */
  planRunId?: number | null
  planItemId?: number | null
  attempt?: number | null
  requirementId?: string | null
  shardKey?: string | null
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

// ---------------- CAP-104 功能模块 / 用例规范 / 资产 / 证据 ----------------

/** 项目 → 模块 → 套件 → 用例：模块用于分组统计与检索 */
export interface TestModule {
  id: number
  projectId: string
  name: string
  description: string | null
  tags: string | null
  owner: string | null
  sortNo: number
  suiteCount: number
  createdAt: string
  updatedAt: string
}

export interface TestModuleInput {
  name: string
  description?: string
  tags?: string
  owner?: string
  sortNo?: number
}

export interface AssertionSpec {
  source: string
  selector?: string | null
  op: string
  expected?: string | null
}

/** 统一用例描述规范（与后端 CaseSpec 对齐） */
export interface CaseSpec {
  caseKey?: string | null
  title: string
  type: CaseType
  priority?: string | null
  tags?: string[]
  preconditions?: string[]
  steps?: string[]
  assertions?: AssertionSpec[]
  cleanup?: string[]
  executor?: {
    location?: string | null
    labels?: string[]
    nodeId?: string | null
    timeoutSec?: number | null
    retry?: { max?: number | null; on?: string[] } | null
    env?: Record<string, string> | null
  } | null
  http?: {
    method: string
    path: string
    params?: Record<string, string> | null
    headers?: Record<string, string> | null
    body?: string | null
  } | null
  command?: { command: string; workingDir?: string | null; contractVersion?: string | null } | null
  manual?: { instructions?: string | null } | null
  source?: { kind?: string | null; assetId?: number | null; note?: string | null } | null
}

export interface CaseSaveResult {
  testCase: TestCase
  /** 同签名疑似重复用例（只提示不合并） */
  duplicates: TestCase[]
}

export interface TestCaseVersion {
  id: number
  caseId: number
  versionNo: number
  reviewStatus: ReviewStatus
  changeNote: string | null
  createdBy: string | null
  createdAt: string
  reviewedBy: string | null
  reviewedAt: string | null
  spec: CaseSpec
}

export type TestAssetKind = 'GIT' | 'ZIP' | 'FILE'
export type TestAssetRole = 'DATA' | 'PROGRAM'

/** 测试资产：GIT 源登记 / ZIP / 单文件，未评审通过不可被引用执行 */
export interface TestAsset {
  id: number
  projectId: string
  kind: TestAssetKind
  name: string
  source: string | null
  branch: string | null
  commitHash: string | null
  sha256: string | null
  sizeBytes: number | null
  fileCount: number | null
  reviewStatus: ReviewStatus
  uploadedBy: string | null
  reviewedBy: string | null
  reviewedAt: string | null
  note: string | null
  createdAt: string
  // CAP-104 M1.5 程序包
  assetRole?: TestAssetRole | null
  runtime?: string | null
  entrypoint?: string | null
  targetOs?: string | null
  targetArch?: string | null
  manifest?: ProgramManifest | null
}

export interface TestAssetGitInput {
  name: string
  repoUrl: string
  branch: string
  commitHash?: string
  note?: string
}

/** 证据条目（只读、追加写；按 seqNo 串哈希链） */
export interface TestEvidence {
  id: number
  runId: number
  resultId: number | null
  nodeId: string | null
  kind: string
  filename: string | null
  sha256: string
  prevHash: string | null
  sizeBytes: number | null
  seqNo: number
  createdAt: string
}

export interface EvidenceChainVerification {
  valid: boolean
  count: number
  headHash: string | null
  message: string
}

export interface PrecheckItem {
  name: string
  pass: boolean
  expected: string
  actual: string
  message: string
}

export interface PrecheckReport {
  passed: boolean
  checkedAt: string
  items: PrecheckItem[]
}

export interface CaseRunInput {
  projectId?: string
  workItemId?: string
  environmentId?: number
  agentNodeId?: string
  baseUrl?: string
  parameters?: Record<string, string>
}

export interface SuiteMetaInput {
  moduleId?: number | null
  owner?: string
  tags?: string
  commitHash?: string
  sourceAssetId?: number | null
}

// ---------------- CAP-104 M2 测试方案 / 方案运行 / 基线 ----------------

export interface TestPlanItem {
  id: number
  refType: 'CASE' | 'SUITE'
  refId: number
  refVersion: number | null
  seq: number
  parallelGroup: string | null
  /** 允许键：parameters / env / agentNodeId / nodeId / labels / timeoutSec / retry */
  overrides: Record<string, unknown>
  enabled: boolean
  refName: string | null
  reviewStatus: ReviewStatus | null
  caseCount: number | null
}

export interface TestPlan {
  id: number
  projectId: string
  name: string
  description: string | null
  environmentId: number | null
  failStrategy: 'FAIL_FAST' | 'CONTINUE'
  concurrency: number
  parameters: Record<string, string>
  requirementId: string | null
  isTemplate: boolean
  planVersion: number
  createdBy: string | null
  createdAt: string
  updatedAt: string
  items: TestPlanItem[]
}

export interface TestPlanItemInput {
  refType: 'CASE' | 'SUITE'
  refId: number
  refVersion?: number | null
  seq?: number
  parallelGroup?: string | null
  overrides?: Record<string, unknown>
  enabled?: boolean
}

export interface TestPlanInput {
  name: string
  description?: string | null
  environmentId?: number | null
  failStrategy?: 'FAIL_FAST' | 'CONTINUE'
  concurrency?: number
  parameters?: Record<string, string>
  requirementId?: string | null
  isTemplate?: boolean
  items: TestPlanItemInput[]
}

export interface PlanItemResult {
  itemId: number
  refType: 'CASE' | 'SUITE'
  refId: number
  seq: number
  parallelGroup: string | null
  refName: string | null
  status: string
  conclusion: string | null
  runId: number | null
  attempts: number | null
  durationMs: number | null
  shardKey: string | null
  caseCount: number | null
  passed: number | null
  failed: number | null
  blocked: number | null
  errors: number | null
  error: string | null
}

export interface PlanChildRun {
  id: number
  status: TestRunStatus
  conclusion: RunConclusion | null
  agentNodeId: string | null
  shardKey: string | null
  attempt: number | null
  planItemId: number | null
  summary: RunSummary | null
  errorSummary: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface TestPlanRun {
  id: number
  projectId: string
  planId: number
  planName: string
  planVersion: number
  mode: PlanMode
  status: PlanRunStatus
  conclusion: RunConclusion | null
  summary: Record<string, unknown>
  items: PlanItemResult[]
  children: PlanChildRun[]
  errorSummary: string | null
  requirementId: string | null
  environmentId: number | null
  baseUrl: string | null
  triggeredBy: string | null
  cancelRequested: boolean | null
  reportDocId: number | null
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
  planSnapshotJson: string | null
  envSnapshotJson: string | null
}

export interface CreatePlanRunInput {
  environmentId?: number | null
  baseUrl?: string
  agentNodeId?: string
  nodeIds?: string[]
  mode?: PlanMode
  parameters?: Record<string, string>
  workItemId?: string
  requirementId?: string
  shardKeys?: string[]
  failedOnly?: boolean
  failStrategy?: 'FAIL_FAST' | 'CONTINUE'
  concurrency?: number
  timeoutSec?: number
  labels?: string[]
  capabilities?: string[]
}

export interface PlanCaseResult {
  caseId: number
  caseKey: string | null
  name: string
  version: number | null
  status: CaseResultStatus
  failureCategory: FailureCategory | null
  runId: number
  nodeId: string | null
  shardKey: string | null
  durationMs: number | null
  error: string | null
}

export interface TestBaseline {
  id: number
  projectId: string
  name: string
  productVersion: string | null
  planId: number
  planRunId: number
  summary: Record<string, unknown>
  caseCount: number
  cases: Record<string, unknown>[]
  createdBy: string | null
  createdAt: string
}

export interface BaselineCaseDiff {
  caseId: number | null
  caseKey: string | null
  name: string | null
  baselineStatus: string | null
  currentStatus: string | null
  baselineVersion: number | null
  currentVersion: number | null
  currentRunId: number | null
  error: string | null
}

export interface BaselineComparison {
  baselineId: number
  planRunId: number
  baselineSummary: Record<string, unknown>
  currentSummary: Record<string, unknown>
  newFailures: BaselineCaseDiff[]
  fixed: BaselineCaseDiff[]
  persistentFailures: BaselineCaseDiff[]
  newCases: BaselineCaseDiff[]
  removedCases: BaselineCaseDiff[]
  passRateDelta: number
  durationDeltaMs: number
}

// ---------------- CAP-104 M3 统计 ----------------

export interface StatsOverview {
  runs?: number
  cases?: number
  distinctCases?: number
  passed?: number
  failed?: number
  errors?: number
  blocked?: number
  skipped?: number
  passRate?: number | null
  avgDurationMs?: number | null
}

export interface StatsTrendPoint {
  bucket: string
  runs: number
  total: number
  passed: number
  failed: number
  errors: number
  blocked: number
  skipped: number
  passRate: number | null
}

export interface StatsFailure {
  caseId: number | null
  caseKey: string | null
  name: string | null
  failures: number
  errors: number
  lastStatus: string | null
  lastRunId: number | null
  lastAt: string | null
  lastError: string | null
  failureCategory: string | null
}

export interface StatsFlaky {
  caseId: number | null
  caseKey: string | null
  name: string | null
  samples: number
  passes: number
  failures: number
  blocked: number
  failRate: number | null
  retryPass: boolean
  lastStatus: string | null
  lastAt: string | null
}

export interface StatsDuration {
  count?: number
  p50Ms?: number | null
  p95Ms?: number | null
  avgMs?: number | null
  slowCases?: { caseId: number | null; name: string | null; samples: number; p95Ms: number }[]
}

export interface StatsEnvironment {
  environmentId: number | null
  name: string | null
  runs: number
  total: number
  passed: number
  failed: number
  errors: number
  blocked: number
  skipped: number
  passRate: number | null
  p95Ms: number | null
}

export interface TestStats {
  from: string
  to: string
  granularity: 'DAY' | 'WEEK'
  overview: StatsOverview
  trend: StatsTrendPoint[]
  topFailures: StatsFailure[]
  flaky: StatsFlaky[]
  duration: StatsDuration
  environments: StatsEnvironment[]
}

// ---------------- CAP-104 M3 大文件证据 ----------------

export interface EvidenceToken {
  token: string
  expiresAt: string
  uploadUrl: string
  maxBytes: number
  allowedKinds: string[]
}

export interface TestRunWorkItemInput {
  resultId?: number
  requirementId?: string
  type?: string
  title?: string
  spec?: string
}
