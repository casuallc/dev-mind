// Agent 节点能力（CAP-21）的类型定义，与后端 devmind-agent 模块对齐

export type AgentNodeStatus = 'ONLINE' | 'OFFLINE' | 'DISABLED'

export interface AgentNode {
  id: number
  name: string
  status: AgentNodeStatus
  os?: string
  labels?: string
  capabilities?: string
  runnerVersion?: string
  /** 最近一次接入的远端地址（IP:端口），离线后保留便于排查 */
  remoteAddr?: string
  /** 平台默认执行节点：会话/项目未指定节点时调度到此（全平台至多一个） */
  isDefault?: boolean
  /** 工作区磁盘占用（字节，CAP-34 FR-05；旧 runner 未上报为 undefined） */
  workspaceBytes?: number
  /** WS 协议版本（CAP-34 FR-08；未上报的老 runner 按 v1 对待） */
  protocolVersion?: number
  /** 探测到的工具链（JSON 对象串，如 {"git":"2.47"}，CAP-34 FR-07；未上报为 undefined） */
  toolchain?: string
  /** 节点外网代理 URL（CAP-43；空 = 直连） */
  proxyUrl?: string
  /** 代理适用范围 CSV（git/claude/exec，CAP-43；配了代理空 scope 服务端默认 git） */
  proxyScopes?: string
  /** 文件访问根目录白名单（CAP-65；空/缺席 = 文件浏览不可用） */
  fileRoots?: string[]
  /** 活跃会话数（列表接口聚合各会话模块 SPI 填充；RUNNING/WAITING_INPUT/WAITING_AUTH 三态） */
  activeSessionCount?: number
  lastHeartbeatAt?: string
  createdAt?: string
}

/** 创建节点响应：token 仅此一次可见 */
export interface IssuedNode {
  node: AgentNode
  token: string
}

/** FR-09 服务端托管的 runner 包（全局单份，上传即替换） */
export interface RunnerPackage {
  id: number
  version: string
  sha256: string
  sizeBytes: number
  originalFilename?: string
  uploadedAt?: string
  uploadedBy?: string
}

export type UpgradeStatus = 'ACCEPTED' | 'BUSY' | 'ALREADY_LATEST' | 'REJECTED'

/** 手动升级结果（后端恒 200，业务结果看 status） */
export interface UpgradeResult {
  status: UpgradeStatus
  message: string
  activeSessions?: number
}

/** 节点上的活跃会话（GET /agent-nodes/{id}/active-sessions，强制升级前展示） */
export interface NodeActiveSession {
  sessionId: string
  /** SESSION = 项目开发会话；CHAT = 通用问答 */
  kind: 'SESSION' | 'CHAT'
  title?: string
  status: string
  createdBy?: string
  createdAt?: string
}

/** 节点连接事件类型 */
export type ConnLogEvent = 'CONNECT' | 'REJECT' | 'DISCONNECT'

/** 节点连接流水（REJECT 时 nodeId/nodeName 为空，只有来源地址） */
export interface AgentConnLog {
  id: number
  nodeId?: number
  nodeName?: string
  event: ConnLogEvent
  remoteAddr?: string
  detail?: string
  createdAt?: string
}

/** CAP-65 节点文件浏览：目录一层条目 */
export interface NodeFileEntry {
  name: string
  dir: boolean
  /** 文件字节数（目录缺席） */
  size?: number
  /** 修改时间（ISO 串，渲染走 fmtTime） */
  mtime?: string
}

/** GET /agent-nodes/{id}/files/list 响应 */
export interface NodeFileList {
  entries: NodeFileEntry[]
  truncated: boolean
}

/** GET /agent-nodes/{id}/files/read 响应 */
export interface NodeFileContent {
  content: string
  size: number
}

// ---------------- CAP-70 出口规则（egress_rules，仅 ADMIN 可见/可配） ----------------

/** 出口规则：host glob 命中即经指定节点反向隧道出访（socks5h/CONNECT，runner 侧解析 DNS） */
export interface EgressRule {
  id: number
  /** host glob：精确或 *.后缀，小写规范化入库 */
  hostPattern: string
  nodeId: number
  nodeName?: string
  enabled: boolean
  sort: number
  remark?: string
  /** 节点 WS 协议版本（<21 = runner 太老无隧道能力，保存时前端警告） */
  nodeProtocolVersion?: number
  /** 隧道在线徽标（缺席 = 节点不在线或协议不支持） */
  tunnelOnline?: boolean
  createdAt?: string
  updatedAt?: string
}

/** GET /egress-rules/status：各节点隧道在线徽标数据 */
export interface EgressTunnelStatus {
  nodeId: number
  nodeName?: string
  nodeStatus?: string
  protocolVersion?: number
  supportsTunnel: boolean
  tunnelOnline: boolean
}

/** 新建/编辑出口规则请求 */
export interface EgressRuleBody {
  hostPattern: string
  nodeId: number
  enabled?: boolean
  sort?: number
  remark?: string
}
