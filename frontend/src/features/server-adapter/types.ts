// CAP-07 服务器适配器类型，与后端 devmind-server-adapter 对齐

export interface ServerListItem {
  id: number
  projectId: string
  name: string
  env: string | null
  accessType: 'ssh' | 'http'
  capabilities: string[]
  enabled: boolean
}

export interface ConnectResult {
  ok: boolean
  message: string
  durationMs: number
}

export interface ExecResult {
  exitCode: number
  success: boolean
  stdout: string
  stderr: string
  durationMs: number
}

export interface HealthResult {
  ok: boolean
  message: string
  durationMs: number
}

export interface TemplateParam {
  name: string
  required: boolean
  label: string | null
  defaultValue: string | null
}

export interface TemplateView {
  id: number
  projectId: string
  code: string
  name: string
  templateText: string
  params: TemplateParam[]
  allowed: string[]
  createdAt: string
  updatedAt: string
}

export interface TemplateInput {
  projectId: string
  code: string
  name: string
  templateText: string
  params: TemplateParam[]
  allowed: string[]
}

export interface AuditView {
  id: number
  projectId: string
  serverId: number
  serverName: string
  accessType: string
  action: string
  templateCode: string | null
  capability: string | null
  command: string | null
  exitCode: number | null
  success: boolean
  detail: string | null
  durationMs: number | null
  createdAt: string
}

export interface StoredConfig {
  accessConfig: string | null
  fields: { field: string; encrypted: boolean }[]
}
