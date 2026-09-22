// CAP-55 决策记录查询与训练集导出。分页口径与全平台一致：page 从 0 起、size 上限 200。
import { api } from '../../shared/api/client'
import { getAccessToken } from '../auth/authStore'
import type {
  CaseGroupTemplate,
  CheckpointGateView,
  CheckpointDetail,
  CheckpointRequest,
  CheckpointView,
  DatasetDetail,
  DatasetItemDetail,
  DatasetItemRequest,
  DatasetItemView,
  DatasetView,
  DecisionRecord,
  DecisionRecordDetail,
  EvalDetail,
  EvalTriggerRequest,
  EvalView,
  FinetuneDetail,
  FinetuneTriggerRequest,
  FinetuneView,
  LabPage,
  RecordsIntakeResult,
  RecordsPreview,
  ServeCheckResult,
} from './types'

/** 服务端分页响应（PageView 的前端投影） */
export interface DecisionRecordPage {
  items: DecisionRecord[]
  total: number
  page: number
  size: number
}

export function listDecisionRecords(params: {
  capability?: string
  since?: string
  page?: number
  size?: number
} = {}): Promise<DecisionRecordPage> {
  const q = new URLSearchParams()
  if (params.capability) q.set('capability', params.capability)
  if (params.since) q.set('since', params.since)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  return api.get<DecisionRecordPage>(`/decision/records?${q.toString()}`)
}

export const getDecisionRecord = (id: number) =>
  api.get<DecisionRecordDetail>(`/decision/records/${id}`)

/**
 * 导出 laya 训练 JSONL。api client 只解 JSON，二进制走原生 fetch + blob；
 * 文件名用后端给的 Content-Disposition（带能力与时间戳，导多次不会互相覆盖），
 * 拿不到时回落到固定名。
 */
export async function exportDecisionRecords(params: { capability?: string; since?: string } = {}): Promise<string> {
  const q = new URLSearchParams()
  if (params.capability) q.set('capability', params.capability)
  if (params.since) q.set('since', params.since)
  const res = await fetch(`/api/decision/records/export?${q.toString()}`, {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  if (!res.ok) throw new Error(`导出失败: ${res.status}`)
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = filenameOf(res.headers.get('Content-Disposition')) || 'decision-records.jsonl'
  a.click()
  URL.revokeObjectURL(a.href)
  return a.download
}

/** 从 Content-Disposition 取文件名：`attachment; filename="…"` / `filename*=UTF-8''…` 两种形态都认 */
function filenameOf(header: string | null): string {
  if (!header) return ''
  const star = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (star) {
    try {
      return decodeURIComponent(star[1].trim())
    } catch {
      return star[1].trim()
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  return plain ? plain[1].trim() : ''
}

// ---------------- CAP-56 决策实验室：评测集 / 评测 / 微调 / 产物登记 ----------------
// 分页口径全站一致（page 从 0 起）；评测与微调除 DELETE/GET 外都是「发起一次运行」，没有 PUT。

/** 分页查询串（page 从 0 起、size 默认 20 上限 200） */
function pageQuery(p: { page?: number; size?: number }): URLSearchParams {
  const q = new URLSearchParams()
  q.set('page', String(p.page ?? 0))
  q.set('size', String(p.size ?? 20))
  return q
}

// ---- 评测集 ----

export const listDatasets = (params: { kind?: string; page?: number; size?: number } = {}) => {
  const q = pageQuery(params)
  if (params.kind) q.set('kind', params.kind)
  return api.get<LabPage<DatasetView>>(`/decision/datasets?${q.toString()}`)
}

export const createDataset = (body: { name: string; kind: string; note?: string }) =>
  api.post<DatasetDetail>('/decision/datasets', body)

export const getDataset = (id: number) => api.get<DatasetDetail>(`/decision/datasets/${id}`)

/** 改集：只能改 note（名字是修订链的主键，后端会拒） */
export const updateDataset = (id: number, body: { name: string; kind: string; note?: string }) =>
  api.put<DatasetDetail>(`/decision/datasets/${id}`, body)

export const deleteDataset = (id: number) => api.del<void>(`/decision/datasets/${id}`)

/** 冻结：后端校验对照组齐全 + 标签与内容相符 + 题面版本一致，任一不成立即 409 并逐条给原因 */
export const freezeDataset = (id: number) => api.post<DatasetDetail>(`/decision/datasets/${id}/freeze`)

/** 修订为新版本：从已冻结版本派生下一个草稿（冻结版本本身不动） */
export const reviseDataset = (id: number) => api.post<DatasetDetail>(`/decision/datasets/${id}/revise`)

/** 对照组模板（固定 3 条：空召回 / 逐字重复 / 不相关），新建样本时预填 */
export const listCaseTemplates = () => api.get<CaseGroupTemplate[]>('/decision/datasets/templates')

export const listDatasetItems = (
  id: number,
  params: { caseGroup?: string; page?: number; size?: number } = {},
) => {
  const q = pageQuery(params)
  if (params.caseGroup) q.set('caseGroup', params.caseGroup)
  return api.get<LabPage<DatasetItemView>>(`/decision/datasets/${id}/items?${q.toString()}`)
}

export const createDatasetItem = (id: number, body: DatasetItemRequest) =>
  api.post<DatasetItemDetail>(`/decision/datasets/${id}/items`, body)

export const getDatasetItem = (id: number, itemId: number) =>
  api.get<DatasetItemDetail>(`/decision/datasets/${id}/items/${itemId}`)

export const updateDatasetItem = (id: number, itemId: number, body: DatasetItemRequest) =>
  api.put<DatasetItemDetail>(`/decision/datasets/${id}/items/${itemId}`, body)

export const deleteDatasetItem = (id: number, itemId: number) =>
  api.del<void>(`/decision/datasets/${id}/items/${itemId}`)

/** 收编预览（只读）：把决策记录按 gold 转成候选样本，先看再决定 */
export const previewIntake = (
  id: number,
  params: { capability?: string; since?: string; sampleLimit?: number } = {},
) => {
  const q = new URLSearchParams()
  if (params.capability) q.set('capability', params.capability)
  if (params.since) q.set('since', params.since)
  q.set('sampleLimit', String(params.sampleLimit ?? 50))
  return api.get<RecordsPreview>(`/decision/datasets/${id}/from-records/preview?${q.toString()}`)
}

export const runIntake = (id: number, body: { capability?: string; since?: string }) =>
  api.post<RecordsIntakeResult>(`/decision/datasets/${id}/from-records`, body)

// ---- 评测运行 ----

export const listEvaluations = (
  params: { datasetId?: number; checkpointId?: number; page?: number; size?: number } = {},
) => {
  const q = pageQuery(params)
  if (params.datasetId != null) q.set('datasetId', String(params.datasetId))
  if (params.checkpointId != null) q.set('checkpointId', String(params.checkpointId))
  return api.get<LabPage<EvalView>>(`/decision/evaluations?${q.toString()}`)
}

export const triggerEvaluation = (body: EvalTriggerRequest) =>
  api.post<EvalView>('/decision/evaluations', body)

export const getEvaluation = (id: number) => api.get<EvalDetail>(`/decision/evaluations/${id}`)

/** 历史日志（纯文本，marker 行已剔除）；实时增量走 WS */
export const evaluationLogs = (id: number) => api.getText(`/decision/evaluations/${id}/logs`)

export const deleteEvaluation = (id: number) => api.del<void>(`/decision/evaluations/${id}`)

// ---- 微调任务 ----

export const listFinetunes = (params: { datasetId?: number; page?: number; size?: number } = {}) => {
  const q = pageQuery(params)
  if (params.datasetId != null) q.set('datasetId', String(params.datasetId))
  return api.get<LabPage<FinetuneView>>(`/decision/finetunes?${q.toString()}`)
}

export const triggerFinetune = (body: FinetuneTriggerRequest) =>
  api.post<FinetuneView>('/decision/finetunes', body)

export const getFinetune = (id: number) => api.get<FinetuneDetail>(`/decision/finetunes/${id}`)

export const finetuneLogs = (id: number) => api.getText(`/decision/finetunes/${id}/logs`)

export const deleteFinetune = (id: number) => api.del<void>(`/decision/finetunes/${id}`)

// ---- 产物登记 ----

export const listCheckpoints = (params: { serveSlot?: string; page?: number; size?: number } = {}) => {
  const q = pageQuery(params)
  if (params.serveSlot) q.set('serveSlot', params.serveSlot)
  return api.get<LabPage<CheckpointView>>(`/decision/checkpoints?${q.toString()}`)
}

/** 准入闸门状态：open=false 时消费方（分诊）整体不可用，reason 是给用户看的原因 */
export const checkpointGate = () => api.get<CheckpointGateView>('/decision/checkpoints/gate')

export const createCheckpoint = (body: CheckpointRequest) =>
  api.post<CheckpointView>('/decision/checkpoints', body)

export const getCheckpoint = (id: number) => api.get<CheckpointDetail>(`/decision/checkpoints/${id}`)

/** 人工放行（闸门看的就是它）；note 必填——放行依据要留痕 */
export const verifyCheckpoint = (id: number, note: string) =>
  api.post<CheckpointView>(`/decision/checkpoints/${id}/verify`, { note })

/** 撤销放行；reason 必填 */
export const unverifyCheckpoint = (id: number, reason: string) =>
  api.post<CheckpointView>(`/decision/checkpoints/${id}/unverify`, { reason })

export const deleteCheckpoint = (id: number) => api.del<void>(`/decision/checkpoints/${id}`)

/** 跑一次 serve 自检（打边车 /healthz，核对登记的那份是不是在跑的那份） */
export const serveCheck = (id: number) =>
  api.post<ServeCheckResult>(`/decision/checkpoints/${id}/serve-check`)
