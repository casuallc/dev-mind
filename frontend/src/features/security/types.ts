// CAP-66 安全扫描类型（与 devmind-security 实体/记录一一对应）

export type ScanStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED'
export type FindingStatus = 'NEW' | 'EXISTING' | 'SUPPRESSED' | 'FIXED'
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'
export type EngineId = 'sca' | 'sast' | 'secrets'

export interface SecurityScan {
  id: number
  projectId: string
  repoId: number
  branch: string
  baselineCommitSha: string | null
  /** 逗号分隔引擎集 */
  engines: string
  agentNodeId: number | null
  status: ScanStatus
  engineStatus: string | null
  newCount: number | null
  existingCount: number | null
  fixedCount: number | null
  filteredCount: number | null
  errorMessage: string | null
  createdBy: string
  startedAt: string | null
  finishedAt: string | null
  createdAt: string
}

/** 引擎子状态（scan.engineStatus JSON 解析后） */
export interface EngineStatusEntry {
  displayName?: string
  status?: string
  error?: string
}

export interface ScanReport {
  id: number
  engine: string
  format: string
  attachmentId: string
  createdAt: string
}

export interface ScanDetail {
  scan: SecurityScan
  engineStatus: Record<string, EngineStatusEntry>
  reports: ScanReport[]
}

export interface SecurityFinding {
  id: number
  scanId: number
  projectId: string
  repoId: number
  engine: EngineId
  fingerprint: string
  ruleId: string
  severity: Severity
  title: string
  filePath: string | null
  lineNo: number | null
  depScope: string | null
  directDep: boolean | null
  status: FindingStatus
  firstSeenScanId: number | null
  fixedInScanId: number | null
  /** JSON 字符串：cve/cvss/description/depPath/remediation/epss/kev 等 */
  detailJson: string | null
  reviewNote: string | null
  reviewedAt: string | null
  reviewSessionId: string | null
  fixSessionId: string | null
  fixDiffSummary: string | null
  createdAt: string
}

/** detail_json 解析后的常用字段（宽松，字段随引擎增减） */
export interface FindingDetailJson {
  cve?: string
  groupId?: string
  artifactId?: string
  depVersion?: string
  description?: string
  cvssScore?: number
  cvssVector?: string
  cwes?: string[]
  depPath?: string
  depPathMissing?: string
  epssScore?: number
  epssPercentile?: number
  kev?: boolean
  kevDueDate?: string
  cveSuppressed?: boolean
  engineSuppressed?: boolean
  message?: string
  level?: string
  ruleDescription?: string
  ruleHelpUri?: string
  remediation?: {
    action?: string
    recommendedVersion?: string
    versionRangesRaw?: string[]
    depPath?: string
    note?: string
  }
}

export interface FindingDetail {
  finding: SecurityFinding
  history: SecurityFinding[]
}

export interface Suppression {
  id: number
  projectId: string
  repoId: number
  engine: string
  fingerprint: string
  type: 'FALSE_POSITIVE' | 'ACCEPTED_RISK' | 'THIRD_PARTY'
  reason: string
  expiresAt: string | null
  createdBy: string
  createdAt: string
}

export interface CveSuppression {
  id: number
  groupId: string
  artifactId: string
  cve: string
  /** null = 平台级（仅 ADMIN 可建删） */
  projectId: string | null
  type: 'FALSE_POSITIVE' | 'ACCEPTED_RISK'
  reason: string
  expiresAt: string | null
  createdBy: string
  createdAt: string
}

export interface SecurityConfig {
  projectId: string
  internalGroupPrefixes: string | null
  /** 平台默认 + 项目追加合并后的生效前缀 */
  effectivePrefixes: string[]
}

export interface ToolcheckItem {
  engine: string
  displayName: string
  ok: boolean
  output: string
}

/** Spring Page JSON（扫描列表服务端分页） */
export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}
