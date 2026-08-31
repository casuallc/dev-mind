// CAP-10 测试执行器接口封装
import { api } from '../../shared/api/client'
import type { CreateTestRunInput, IssueDraft, TestCaseInput, TestRun, TestSuite } from './types'

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

/** FR-03 套件沉淀为 docs-repo 的 api-suite 文档（版本化） */
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

/** 报告/日志为纯文本，走原生 fetch */
export async function getRunReport(id: number): Promise<string> {
  const res = await fetch(`/api/test-runs/${id}/report`)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.text()
}

export async function getRunLogs(id: number): Promise<string> {
  const res = await fetch(`/api/test-runs/${id}/logs`)
  if (!res.ok) throw new Error(`${res.status}`)
  return res.text()
}
