// CAP-10 测试执行器接口封装；CAP-104 增补模块/用例规范/资产/证据/预检/方案/统计/基线。
import { api } from '../../shared/api/client'
import type {
  BaselineComparison,
  CaseRunInput,
  CaseSaveResult,
  CaseSpec,
  CreatePlanRunInput,
  CreateTestRunInput,
  EvidenceChainVerification,
  EvidenceToken,
  IssueDraft,
  PlanCaseResult,
  PrecheckReport,
  ScriptSuite,
  ScriptSuiteInput,
  ScriptSuiteRunInput,
  SuiteMetaInput,
  TestAsset,
  TestAssetGitInput,
  TestBaseline,
  TestCase,
  TestCaseInput,
  TestCaseVersion,
  TestEvidence,
  TestModule,
  TestModuleInput,
  TestPlan,
  TestPlanInput,
  TestPlanRun,
  TestRun,
  TestRunWorkItemInput,
  TestStats,
  TestSuite,
} from './types'

// ---------------- 套件 ----------------

export function listSuites(projectId: string): Promise<TestSuite[]> {
  return api.get<TestSuite[]>(`/projects/${projectId}/test-suites`)
}

export function createSuite(
  projectId: string,
  input: { name: string; kind: 'api' | 'smoke' },
): Promise<TestSuite> {
  return api.post<TestSuite>(`/projects/${projectId}/test-suites`, input)
}

/** FR-02 从项目 apiDocSource（OpenAPI）生成 API 套件 */
export function generateSuite(projectId: string): Promise<TestSuite> {
  return api.post<TestSuite>(`/projects/${projectId}/test-suites/generate`)
}

export function getSuite(id: number): Promise<TestSuite> {
  return api.get<TestSuite>(`/test-suites/${id}`)
}

export function deleteSuite(id: number): Promise<void> {
  return api.del(`/test-suites/${id}`)
}

/** FR-03 套件沉淀为 CAP-03 的 api-suite 文档（版本化） */
export function publishSuite(id: number): Promise<TestSuite> {
  return api.post<TestSuite>(`/test-suites/${id}/publish`)
}

/** FR-02 人工编辑用例（整体替换：增删改） */
export function saveCases(id: number, cases: TestCaseInput[]): Promise<TestSuite> {
  return api.put<TestSuite>(`/test-suites/${id}/cases`, cases)
}

// ---------------- 运行 ----------------

export function createRun(input: CreateTestRunInput): Promise<TestRun> {
  return api.post<TestRun>('/tests/runs', input)
}

export function getRun(id: number): Promise<TestRun> {
  return api.get<TestRun>(`/test-runs/${id}`)
}

export function listRuns(projectId: string, status?: string): Promise<TestRun[]> {
  const q = status ? `?projectId=${projectId}&status=${status}` : `?projectId=${projectId}`
  return api.get<TestRun[]>(`/test-runs${q}`)
}

/** FR-06 失败用例 → 缺陷线索 */
export function getIssues(runId: number): Promise<IssueDraft[]> {
  return api.post<IssueDraft[]>(`/test-runs/${runId}/issues`)
}

export function deleteRun(id: number): Promise<void> {
  return api.del(`/test-runs/${id}`)
}

/** 报告/日志为纯文本，走 getText */
export function getRunReport(id: number): Promise<string> {
  return api.getText(`/test-runs/${id}/report`)
}

export function getRunLogs(id: number): Promise<string> {
  return api.getText(`/test-runs/${id}/logs`)
}

// ---------------- CAP-69 脚本套件（强制绑定项目） ----------------

export function listScriptSuites(projectId: string): Promise<ScriptSuite[]> {
  return api.get<ScriptSuite[]>(`/script-suites?projectId=${projectId}`)
}

export function getScriptSuite(id: number): Promise<ScriptSuite> {
  return api.get<ScriptSuite>(`/script-suites/${id}`)
}

export function createScriptSuite(input: ScriptSuiteInput): Promise<ScriptSuite> {
  return api.post<ScriptSuite>('/script-suites', input)
}

export function updateScriptSuite(id: number, input: ScriptSuiteInput): Promise<ScriptSuite> {
  return api.put<ScriptSuite>(`/script-suites/${id}`, input)
}

export function deleteScriptSuite(id: number): Promise<void> {
  return api.del(`/script-suites/${id}`)
}

/** FR-02 触发运行（input 可整体省略：全走套件默认） */
export function runScriptSuite(id: number, input?: ScriptSuiteRunInput): Promise<TestRun> {
  return api.post<TestRun>(`/script-suites/${id}/run`, input)
}

// ---------------- CAP-104 功能模块 ----------------

export function listModules(projectId: string): Promise<TestModule[]> {
  return api.get<TestModule[]>(`/projects/${projectId}/test-modules`)
}

export function createModule(projectId: string, input: TestModuleInput): Promise<TestModule> {
  return api.post<TestModule>(`/projects/${projectId}/test-modules`, input)
}

export function updateModule(id: number, input: TestModuleInput): Promise<TestModule> {
  return api.put<TestModule>(`/test-modules/${id}`, input)
}

export function deleteModule(id: number): Promise<void> {
  return api.del(`/test-modules/${id}`)
}

// ---------------- CAP-104 用例库 ----------------

export interface CaseSearch {
  projectId: string
  suiteId?: number
  moduleId?: number
  type?: string
  reviewStatus?: string
  keyword?: string
  tags?: string
}

export function searchCases(params: CaseSearch): Promise<TestCase[]> {
  const q = new URLSearchParams({ projectId: params.projectId })
  if (params.suiteId != null) q.set('suiteId', String(params.suiteId))
  if (params.moduleId != null) q.set('moduleId', String(params.moduleId))
  if (params.type) q.set('type', params.type)
  if (params.reviewStatus) q.set('reviewStatus', params.reviewStatus)
  if (params.keyword) q.set('keyword', params.keyword)
  if (params.tags) q.set('tags', params.tags)
  return api.get<TestCase[]>(`/test-cases?${q.toString()}`)
}

export function getCase(id: number): Promise<TestCase> {
  return api.get<TestCase>(`/test-cases/${id}`)
}

export function createCase(suiteId: number, spec: CaseSpec, changeNote?: string): Promise<CaseSaveResult> {
  return api.post<CaseSaveResult>(`/test-suites/${suiteId}/test-cases`, { spec, changeNote })
}

export function updateCase(id: number, spec: CaseSpec, changeNote?: string): Promise<CaseSaveResult> {
  return api.put<CaseSaveResult>(`/test-cases/${id}`, { spec, changeNote })
}

export function deleteCase(id: number): Promise<void> {
  return api.del(`/test-cases/${id}`)
}

export function listCaseVersions(id: number): Promise<TestCaseVersion[]> {
  return api.get<TestCaseVersion[]>(`/test-cases/${id}/versions`)
}

export function reviewCase(id: number, reviewStatus: 'APPROVED' | 'DEPRECATED', note?: string): Promise<TestCase> {
  return api.post<TestCase>(`/test-cases/${id}/review`, { reviewStatus, note })
}

export function runCase(id: number, input?: CaseRunInput): Promise<TestRun> {
  return api.post<TestRun>(`/test-cases/${id}/run`, input)
}

// ---------------- CAP-104 测试资产 ----------------

export function listAssets(projectId: string, reviewStatus?: string): Promise<TestAsset[]> {
  const q = reviewStatus ? `?reviewStatus=${encodeURIComponent(reviewStatus)}` : ''
  return api.get<TestAsset[]>(`/projects/${projectId}/test-assets${q}`)
}

export function getAsset(id: number): Promise<TestAsset> {
  return api.get<TestAsset>(`/test-assets/${id}`)
}

export function createGitAsset(projectId: string, input: TestAssetGitInput): Promise<TestAsset> {
  return api.post<TestAsset>(`/projects/${projectId}/test-assets/git`, input)
}

export function uploadAsset(
  projectId: string,
  file: File,
  name?: string,
  note?: string,
  assetRole?: 'DATA' | 'PROGRAM',
): Promise<TestAsset> {
  const form = new FormData()
  form.append('file', file)
  if (name?.trim()) form.append('name', name.trim())
  if (note?.trim()) form.append('note', note.trim())
  if (assetRole) form.append('assetRole', assetRole)
  return api.upload<TestAsset>(`/projects/${projectId}/test-assets/upload`, form)
}

export function reviewAsset(
  id: number,
  reviewStatus: 'APPROVED' | 'REJECTED',
  note?: string,
): Promise<TestAsset> {
  return api.post<TestAsset>(`/test-assets/${id}/review`, { reviewStatus, note })
}

export function deleteAsset(id: number): Promise<void> {
  return api.del(`/test-assets/${id}`)
}

// ---------------- CAP-104 证据 / 预检 / 套件资产 / 人工复核 ----------------

export function listEvidence(runId: number): Promise<TestEvidence[]> {
  return api.get<TestEvidence[]>(`/test-runs/${runId}/evidence`)
}

export function verifyEvidence(runId: number): Promise<EvidenceChainVerification> {
  return api.get<EvidenceChainVerification>(`/test-runs/${runId}/evidence/verify`)
}

/** 证据下载：带鉴权取 blob 后触发浏览器保存 */
export async function downloadEvidence(id: number, filename: string): Promise<void> {
  const blob = await api.getBlob(`/test-evidence/${id}/download`)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

export function precheckEnvironment(projectId: string, envId: number, nodeId?: string): Promise<PrecheckReport> {
  const q = nodeId ? `?nodeId=${encodeURIComponent(nodeId)}` : ''
  return api.post<PrecheckReport>(`/projects/${projectId}/environments/${envId}/precheck${q}`)
}

export function updateSuiteMeta(id: number, input: SuiteMetaInput): Promise<TestSuite> {
  return api.put<TestSuite>(`/test-suites/${id}`, input)
}

export function reviewSuite(id: number, reviewStatus: 'APPROVED' | 'DEPRECATED', note?: string): Promise<TestSuite> {
  return api.post<TestSuite>(`/test-suites/${id}/review`, { reviewStatus, note })
}

/** 人工复核/判定：CONFIRMED / REJECTED / PASS / FAIL */
export function reviewRunResult(
  runId: number,
  resultId: number,
  review: 'CONFIRMED' | 'REJECTED' | 'PASS' | 'FAIL',
  note?: string,
): Promise<unknown> {
  return api.put(`/test-runs/${runId}/results/${resultId}/review`, { review, note })
}

/** FR-17 失败用例创建工作单元（requirementId 缺省取运行关联需求） */
export function createRunWorkItem(runId: number, input: TestRunWorkItemInput): Promise<unknown> {
  return api.post(`/test-runs/${runId}/work-items`, input)
}

/** FR-24 申请一次性证据上传 token（命令/程序可通过环境变量直接上传） */
export function requestEvidenceToken(
  runId: number,
  input?: { resultId?: number; nodeId?: string; kind?: string },
): Promise<EvidenceToken> {
  return api.post<EvidenceToken>(`/test-runs/${runId}/evidence-token`, input ?? {})
}

async function saveBlob(path: string, filename: string): Promise<void> {
  const blob = await api.getBlob(path)
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

/** FR-15 单运行证据包（zip） */
export function downloadRunEvidenceZip(runId: number): Promise<void> {
  return saveBlob(`/test-runs/${runId}/evidence.zip`, `test-run-${runId}-evidence.zip`)
}

/** FR-15 方案运行证据包（zip，含子运行目录） */
export function downloadPlanRunEvidenceZip(planRunId: number): Promise<void> {
  return saveBlob(`/test-plan-runs/${planRunId}/evidence.zip`, `test-plan-run-${planRunId}-evidence.zip`)
}

// ---------------- CAP-104 M2 测试方案 / 方案运行 / 基线 ----------------

export function listPlans(projectId: string, params?: { templateOnly?: boolean; keyword?: string }): Promise<TestPlan[]> {
  const q = new URLSearchParams()
  if (params?.templateOnly) q.set('templateOnly', 'true')
  if (params?.keyword) q.set('keyword', params.keyword)
  const suffix = q.toString() ? `?${q.toString()}` : ''
  return api.get<TestPlan[]>(`/projects/${projectId}/test-plans${suffix}`)
}

export function getPlan(id: number): Promise<TestPlan> {
  return api.get<TestPlan>(`/test-plans/${id}`)
}

export function createPlan(projectId: string, input: TestPlanInput): Promise<TestPlan> {
  return api.post<TestPlan>(`/projects/${projectId}/test-plans`, input)
}

export function updatePlan(id: number, input: TestPlanInput): Promise<TestPlan> {
  return api.put<TestPlan>(`/test-plans/${id}`, input)
}

export function deletePlan(id: number): Promise<void> {
  return api.del(`/test-plans/${id}`)
}

export function copyPlan(id: number, input?: Partial<TestPlanInput>): Promise<TestPlan> {
  return api.post<TestPlan>(`/test-plans/${id}/copy`, input ?? {})
}

export function runPlan(id: number, input?: CreatePlanRunInput): Promise<TestPlanRun> {
  return api.post<TestPlanRun>(`/test-plans/${id}/run`, input ?? {})
}

export function listPlanRuns(
  projectId: string,
  params?: { planId?: number; status?: string; conclusion?: string },
): Promise<TestPlanRun[]> {
  const q = new URLSearchParams({ projectId })
  if (params?.planId != null) q.set('planId', String(params.planId))
  if (params?.status) q.set('status', params.status)
  if (params?.conclusion) q.set('conclusion', params.conclusion)
  return api.get<TestPlanRun[]>(`/test-plan-runs?${q.toString()}`)
}

export function getPlanRun(id: number): Promise<TestPlanRun> {
  return api.get<TestPlanRun>(`/test-plan-runs/${id}`)
}

export function cancelPlanRun(id: number): Promise<TestPlanRun> {
  return api.post<TestPlanRun>(`/test-plan-runs/${id}/cancel`)
}

export function rerunPlanRun(id: number, input?: CreatePlanRunInput): Promise<TestPlanRun> {
  return api.post<TestPlanRun>(`/test-plan-runs/${id}/rerun`, input ?? {})
}

export function listPlanRunChildren(id: number): Promise<TestRun[]> {
  return api.get<TestRun[]>(`/test-plan-runs/${id}/children`)
}

export function listPlanRunCases(id: number): Promise<PlanCaseResult[]> {
  return api.get<PlanCaseResult[]>(`/test-plan-runs/${id}/cases`)
}

export function getPlanRunReport(id: number): Promise<string> {
  return api.getText(`/test-plan-runs/${id}/report`)
}

export function listBaselines(projectId: string, planId?: number): Promise<TestBaseline[]> {
  const q = new URLSearchParams()
  if (planId != null) q.set('planId', String(planId))
  const suffix = q.toString() ? `?${q.toString()}` : ''
  return api.get<TestBaseline[]>(`/projects/${projectId}/test-baselines${suffix}`)
}

export function createBaseline(
  planRunId: number,
  input?: { name?: string; productVersion?: string },
): Promise<TestBaseline> {
  return api.post<TestBaseline>(`/test-plan-runs/${planRunId}/baselines`, input ?? {})
}

export function deleteBaseline(id: number): Promise<void> {
  return api.del(`/test-baselines/${id}`)
}

export function compareBaseline(id: number, planRunId: number): Promise<BaselineComparison> {
  return api.get<BaselineComparison>(`/test-baselines/${id}/compare?planRunId=${planRunId}`)
}

// ---------------- CAP-104 M3 统计 ----------------

export interface StatsQuery {
  days?: number
  granularity?: 'DAY' | 'WEEK'
  minSamples?: number
  planId?: number
  environmentId?: number
  topLimit?: number
}

export function getTestStats(projectId: string, query?: StatsQuery): Promise<TestStats> {
  const q = new URLSearchParams()
  if (query?.days != null) q.set('days', String(query.days))
  if (query?.granularity) q.set('granularity', query.granularity)
  if (query?.minSamples != null) q.set('minSamples', String(query.minSamples))
  if (query?.planId != null) q.set('planId', String(query.planId))
  if (query?.environmentId != null) q.set('environmentId', String(query.environmentId))
  if (query?.topLimit != null) q.set('topLimit', String(query.topLimit))
  const suffix = q.toString() ? `?${q.toString()}` : ''
  return api.get<TestStats>(`/projects/${projectId}/test-stats${suffix}`)
}
