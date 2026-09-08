// 场景（CAP-33）API 封装：/api/scenarios + 上下文快照/项目上下文资产
import { api } from '../../shared/api/client'
import type {
  AssetGroup,
  ContextSnapshot,
  Scenario,
  ScenarioInput,
  ScenarioPreview,
} from './types'

// ---------------- 场景 CRUD ----------------

/** enabled=true 仅出启用项（创建表单下拉）；缺省全量（管理页） */
export function listScenarios(enabledOnly = false): Promise<Scenario[]> {
  return api.get<Scenario[]>(`/scenarios${enabledOnly ? '?enabled=true' : ''}`)
}

export function createScenario(input: ScenarioInput): Promise<Scenario> {
  return api.post<Scenario>('/scenarios', input)
}

export function updateScenario(id: number, input: ScenarioInput): Promise<Scenario> {
  return api.put<Scenario>(`/scenarios/${id}`, input)
}

export function deleteScenario(id: number): Promise<void> {
  return api.del(`/scenarios/${id}`)
}

/** dryRun 装配预览（不 bumpHits，无副作用） */
export function previewScenario(
  code: string,
  params: { projectId?: string; taskSpec?: string } = {},
): Promise<ScenarioPreview> {
  const q = new URLSearchParams()
  if (params.projectId) q.set('projectId', params.projectId)
  if (params.taskSpec) q.set('taskSpec', params.taskSpec)
  const s = q.toString()
  return api.get<ScenarioPreview>(`/scenarios/${encodeURIComponent(code)}/preview${s ? `?${s}` : ''}`)
}

// ---------------- 已注入上下文快照（FR-07） ----------------

/** 404（未挂场景/无快照）由调用方按「无上下文」处理 */
export function getSessionContext(id: string): Promise<ContextSnapshot> {
  return api.get<ContextSnapshot>(`/sessions/${id}/context`)
}

export function getChatContext(id: string): Promise<ContextSnapshot> {
  return api.get<ContextSnapshot>(`/chats/${id}/context`)
}

// ---------------- 项目上下文资产（FR-06） ----------------

export function listContextAssets(projectId: string): Promise<AssetGroup[]> {
  return api.get<AssetGroup[]>(`/projects/${projectId}/context-assets`)
}
