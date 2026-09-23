// CAP-57 分类服务（classify）类型：laya 边车的平台化管控——实例生命周期 / 安装包分发 / 在线试分类。
// 与后端 devmind-classify 的 DTO 一一对应；health 快照是边车 /healthz 应答原文，前端只展示不解析协议。

/** 实例状态：STOPPED 停止 | STARTING 启动宽限中 | RUNNING 健康 | UNHEALTHY 进程在但健康检查失败 */
export type ClassifyInstanceStatus = 'STOPPED' | 'STARTING' | 'RUNNING' | 'UNHEALTHY' | string

export interface ClassifyInstance {
  id: number
  name: string
  agentNodeId: number
  port: number
  baseUrl: string
  appPackageId?: number | null
  pythonBin: string
  env?: Record<string, string> | null
  commandOverride?: string | null
  status: ClassifyInstanceStatus
  lastStartAt?: string | null
  lastHealthAt?: string | null
  /** /healthz 应答快照（status/layaVersion/loaded/devices/sources/summary） */
  lastHealth?: Record<string, unknown> | null
  lastError?: string | null
  createdBy?: string | null
  createdAt: string
  updatedAt: string
}

export interface ClassifyInstanceInput {
  name: string
  agentNodeId: number
  port: number
  baseUrl: string
  appPackageId?: number | null
  pythonBin?: string
  env?: Record<string, string> | null
  commandOverride?: string | null
}

/** 安装包类型：SIDECAR_APP 边车程序包 | MODEL_WEIGHTS 模型权重包 | CORPUS 语料/数据包 */
export type ClassifyPackageKind = 'SIDECAR_APP' | 'MODEL_WEIGHTS' | 'CORPUS'

export interface ClassifyPackage {
  id: number
  kind: ClassifyPackageKind
  name: string
  pkgVersion: string
  sha256: string
  sizeBytes: number
  originalFilename: string
  uploadedBy?: string | null
  uploadedAt: string
}

/** 包在节点上的安装记录：PENDING 分发中 | INSTALLED 已安装 | FAILED 失败（可重试） */
export interface ClassifyPackageInstall {
  id: number
  packageId: number
  packageName?: string | null
  nodeId: number
  /** 节点侧绝对路径（仅展示/复制；${PKG_DIR:<packageId>} 展开成它） */
  installDir?: string | null
  status: 'PENDING' | 'INSTALLED' | 'FAILED' | string
  requestId?: string | null
  error?: string | null
  createdAt: string
  updatedAt: string
}

export interface PlaygroundRunRequest {
  instanceId?: number | null
  endpointId?: number | null
  state: Record<string, unknown>
  questions: Record<string, unknown>
}

export interface PlaygroundRunResult {
  recordRefId: string
  target: string
  answers: Record<string, import('../decision/types').DecisionAnswer>
  routingModel?: string | null
  routingReason?: string | null
  degraded: boolean
  degradedReason?: string | null
  latencyMs: number
}

export interface PlaygroundSample {
  state: Record<string, unknown>
  questions: Record<string, unknown>
}
