// CAP-48 模型接入接口封装（写操作服务端收紧为仅 ADMIN，列表对已登录用户开放）
import { api } from '../../shared/api/client'
import type { EndpointTestResult, ModelEndpoint, ModelEndpointInput } from './types'

export function listModelEndpoints(): Promise<ModelEndpoint[]> {
  return api.get<ModelEndpoint[]>('/model-endpoints')
}

export function createModelEndpoint(input: ModelEndpointInput): Promise<ModelEndpoint> {
  return api.post<ModelEndpoint>('/model-endpoints', input)
}

export function updateModelEndpoint(id: number, input: ModelEndpointInput): Promise<ModelEndpoint> {
  return api.put<ModelEndpoint>(`/model-endpoints/${id}`, input)
}

export function deleteModelEndpoint(id: number): Promise<void> {
  return api.del<void>(`/model-endpoints/${id}`)
}

export function changeEndpointStatus(id: number, status: 'active' | 'disabled'): Promise<ModelEndpoint> {
  return api.put<ModelEndpoint>(`/model-endpoints/${id}/status`, { status })
}

/** 设为平台默认调用方（同类端点唯一；被停用/删除后引用它的库自动回落到这里） */
export function setDefaultEndpoint(id: number): Promise<ModelEndpoint> {
  return api.put<ModelEndpoint>(`/model-endpoints/${id}/default`)
}

/** 已存端点连接测试：实调一次，回写 last_test_* 并把实测维度落库 */
export function testModelEndpoint(id: number): Promise<EndpointTestResult> {
  return api.post<EndpointTestResult>(`/model-endpoints/${id}/test`)
}

/** 未保存配置的连接测试（表单内预检，凭据不落库） */
export function testModelEndpointDraft(input: ModelEndpointInput): Promise<EndpointTestResult> {
  return api.post<EndpointTestResult>('/model-endpoints/test', input)
}
