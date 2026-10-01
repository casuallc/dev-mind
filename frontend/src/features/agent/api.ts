// Agent 节点能力（CAP-21）的接口封装：页面只依赖本文件，不直接碰 shared client
import { api } from '../../shared/api/client'
import { getAccessToken } from '../auth/authStore'
import type {
  AgentConnLog,
  AgentNode,
  IssuedNode,
  NodeActiveSession,
  NodeFileContent,
  NodeFileList,
  RunnerPackage,
  UpgradeResult,
} from './types'

export function listAgentNodes(): Promise<AgentNode[]> {
  return api.get<AgentNode[]>('/agent-nodes')
}

/** 节点连接流水（接入/拒绝/断线，倒序） */
export function listConnLogs(limit = 200): Promise<AgentConnLog[]> {
  return api.get<AgentConnLog[]>(`/agent-nodes/conn-logs?limit=${limit}`)
}

export function createAgentNode(body: { name: string; labels?: string }): Promise<IssuedNode> {
  return api.post<IssuedNode>('/agent-nodes', body)
}

/**
 * 编辑节点（CAP-34 FR-07 标签 / CAP-43 外网代理 / CAP-65 文件访问根目录）。字段缺席 = 不动该配置；
 * labels "" 清空标签；proxyUrl "" 清空代理（连同 scopes）；fileRoots [] 清空白名单。
 */
export function updateAgentNode(
  id: number,
  body: { labels?: string; proxyUrl?: string; proxyScopes?: string; fileRoots?: string[] },
): Promise<AgentNode> {
  return api.put<AgentNode>(`/agent-nodes/${id}`, body)
}

export function disableAgentNode(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/disable`)
}

export function enableAgentNode(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/enable`)
}

export function deleteAgentNode(id: number): Promise<void> {
  return api.del(`/agent-nodes/${id}`)
}

export function setAgentNodeDefault(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/default`)
}

export function unsetAgentNodeDefault(id: number): Promise<AgentNode> {
  return api.post<AgentNode>(`/agent-nodes/${id}/unset-default`)
}

// ---------------- FR-09 runner 包托管与手动升级 ----------------

/** 当前托管包；未上传时后端 404，调用方 catch 视为 null */
export function getRunnerPackage(): Promise<RunnerPackage> {
  return api.get<RunnerPackage>('/agent-nodes/runner-package')
}

/** 上传替换托管包；旧构建覆盖新构建后端 409，force=true 确认降级 */
export function uploadRunnerPackage(file: File, force = false): Promise<RunnerPackage> {
  const form = new FormData()
  form.append('file', file)
  return api.upload<RunnerPackage>(`/agent-nodes/runner-package${force ? '?force=true' : ''}`, form)
}

export function upgradeAgentNode(id: number, force = false): Promise<UpgradeResult> {
  return api.post<UpgradeResult>(`/agent-nodes/${id}/upgrade${force ? '?force=true' : ''}`)
}

/** 节点上的活跃会话清单（强制升级前展示「会终止哪些会话」） */
export function listNodeActiveSessions(id: number): Promise<NodeActiveSession[]> {
  return api.get<NodeActiveSession[]>(`/agent-nodes/${id}/active-sessions`)
}

/** 管理员下载托管 jar（api client 只解 JSON，二进制走原生 fetch + blob） */
export async function downloadRunnerPackage(): Promise<void> {
  const res = await fetch('/api/agent-nodes/runner-package/download', {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  if (!res.ok) throw new Error(`下载失败: ${res.status}`)
  const blob = await res.blob()
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = 'devmind-agent-runner.jar'
  a.click()
  URL.revokeObjectURL(a.href)
}

// ---------------- CAP-65 节点文件浏览（全端点仅 ADMIN；节点离线/协议 <v18/未配白名单 后端 409） ----------------

const fileQs = (root: string, path: string) =>
  `root=${encodeURIComponent(root)}&path=${encodeURIComponent(path)}`

/** 目录一层列表（目录优先按名称排序；超 1000 条截断置 truncated） */
export function listNodeFiles(id: number, root: string, path: string): Promise<NodeFileList> {
  return api.get<NodeFileList>(`/agent-nodes/${id}/files/list?${fileQs(root, path)}`)
}

/** 文本读取（≤512KB 且非二进制；二进制/超限 409 引导下载） */
export function readNodeFile(id: number, root: string, path: string): Promise<NodeFileContent> {
  return api.get<NodeFileContent>(`/agent-nodes/${id}/files/read?${fileQs(root, path)}`)
}

/** UTF-8 文本保存（≤512KB，runner 侧原子写） */
export function writeNodeFile(id: number, root: string, path: string, content: string): Promise<void> {
  return api.put(`/agent-nodes/${id}/files/content`, { root, path, content })
}

/** 同目录改名（newName 不得含 / \ :，目标已存在拒绝） */
export function renameNodeFile(id: number, root: string, path: string, newName: string): Promise<void> {
  return api.post(`/agent-nodes/${id}/files/rename`, { root, path, newName })
}

/** 删除文件/空目录；非空目录须 recursive=true */
export function deleteNodeFile(id: number, root: string, path: string, recursive: boolean): Promise<void> {
  return api.post(`/agent-nodes/${id}/files/delete`, { root, path, recursive })
}

/** 上传（≤100MB，经服务端中转 + runner 拉取落位；带进度） */
export function uploadNodeFile(
  id: number,
  root: string,
  path: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<void> {
  const form = new FormData()
  form.append('file', file)
  return api.uploadWithProgress(`/agent-nodes/${id}/files/upload?${fileQs(root, path)}`, form, onProgress)
}

/** 拉取文件二进制（≤100MB；api client 只解 JSON，二进制走原生 fetch） */
export async function fetchNodeFileBlob(id: number, root: string, path: string): Promise<Blob> {
  const res = await fetch(`/api/agent-nodes/${id}/files/download?${fileQs(root, path)}`, {
    headers: { Authorization: `Bearer ${getAccessToken() ?? ''}` },
  })
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    const { parseApiError } = await import('../../shared/api/error')
    throw parseApiError(res.status, text)
  }
  return res.blob()
}

/** 下载（照 downloadRunnerPackage 先例：blob → a[download]） */
export async function downloadNodeFile(id: number, root: string, path: string, name: string): Promise<void> {
  const blob = await fetchNodeFileBlob(id, root, path)
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = name
  a.click()
  URL.revokeObjectURL(a.href)
}
