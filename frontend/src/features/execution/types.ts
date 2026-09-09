// CAP-36 执行底座视图类型：命令模板白名单（script_templates）与执行审计（audit_logs），与 devmind-execution 对齐

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
  /** 目标节点 id（数值；复用 audit_logs.server_id 列，CAP-07 时代的服务器记录同列） */
  serverId: number | null
  serverName: string | null
  /** 执行通道：agent = exec 帧下发 runner 节点（历史值 ssh/http 为 CAP-07 旧记录） */
  accessType: string
  action: string
  templateCode: string | null
  capability: string | null
  /** 模板渲染后的完整脚本串，不含凭证 */
  command: string | null
  exitCode: number | null
  success: boolean
  detail: string | null
  durationMs: number | null
  createdAt: string
}
