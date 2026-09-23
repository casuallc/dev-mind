// CAP-57 分类服务的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import type {
  ClassifyInstance,
  ClassifyInstanceInput,
  ClassifyPackage,
  ClassifyPackageInstall,
  ClassifyPackageKind,
  PlaygroundRunRequest,
  PlaygroundRunResult,
  PlaygroundSample,
} from './types'

// ---------- 实例管控 ----------

export function listClassifyInstances(): Promise<ClassifyInstance[]> {
  return api.get<ClassifyInstance[]>('/classify/instances')
}

export function createClassifyInstance(body: ClassifyInstanceInput): Promise<ClassifyInstance> {
  return api.post<ClassifyInstance>('/classify/instances', body)
}

export function updateClassifyInstance(id: number, body: ClassifyInstanceInput): Promise<ClassifyInstance> {
  return api.put<ClassifyInstance>(`/classify/instances/${id}`, body)
}

export function deleteClassifyInstance(id: number): Promise<void> {
  return api.del<void>(`/classify/instances/${id}`)
}

export function operateClassifyInstance(id: number, action: 'start' | 'stop' | 'restart'): Promise<ClassifyInstance> {
  return api.post<ClassifyInstance>(`/classify/instances/${id}/${action}`)
}

/** 实时状态（顺带做进程对账：节点侧进程已退出则回落 STOPPED） */
export function getClassifyInstanceStatus(id: number): Promise<ClassifyInstance> {
  return api.get<ClassifyInstance>(`/classify/instances/${id}/status`)
}

// ---------- 安装包管理 ----------

export function listClassifyPackages(kind?: ClassifyPackageKind): Promise<ClassifyPackage[]> {
  return api.get<ClassifyPackage[]>(`/classify/packages${kind ? `?kind=${kind}` : ''}`)
}

/** 大文件（模型权重可达 GB 级）流式上传：multipart 上限 4GB（CAP-57） */
export function uploadClassifyPackage(
  kind: ClassifyPackageKind,
  name: string,
  version: string,
  file: File,
): Promise<ClassifyPackage> {
  const form = new FormData()
  form.append('kind', kind)
  form.append('name', name)
  form.append('version', version)
  form.append('file', file)
  return api.upload<ClassifyPackage>('/classify/packages/upload', form)
}

export function deleteClassifyPackage(id: number): Promise<void> {
  return api.del<void>(`/classify/packages/${id}`)
}

/** 分发到节点（202 受理，异步安装；进度查 installs） */
export function installClassifyPackage(id: number, nodeId: number): Promise<void> {
  return api.post<void>(`/classify/packages/${id}/install?nodeId=${nodeId}`)
}

export function listClassifyInstalls(packageId?: number, nodeId?: number): Promise<ClassifyPackageInstall[]> {
  const params = new URLSearchParams()
  if (packageId != null) params.set('packageId', String(packageId))
  if (nodeId != null) params.set('nodeId', String(nodeId))
  const qs = params.toString()
  return api.get<ClassifyPackageInstall[]>(`/classify/installs${qs ? `?${qs}` : ''}`)
}

export function retryClassifyInstall(id: number): Promise<void> {
  return api.post<void>(`/classify/installs/${id}/retry`)
}

// ---------- 在线试分类 ----------

export function runClassifyPlayground(body: PlaygroundRunRequest): Promise<PlaygroundRunResult> {
  return api.post<PlaygroundRunResult>('/classify/playground/run', body)
}

/** 预填样例（与边车连接测试同一份中文样例） */
export function getClassifyPlaygroundSample(): Promise<PlaygroundSample> {
  return api.get<PlaygroundSample>('/classify/playground/sample')
}
