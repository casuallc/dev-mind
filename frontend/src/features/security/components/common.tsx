// CAP-66 前端共享：枚举色板/标签与 detail_json 宽松解析
import type { FindingDetailJson, FindingStatus, ScanStatus, Severity } from '../types'

export const SEVERITY_COLOR: Record<Severity, string> = {
  CRITICAL: 'red',
  HIGH: 'volcano',
  MEDIUM: 'orange',
  LOW: 'gold',
  INFO: 'default',
}

export const FINDING_STATUS_COLOR: Record<FindingStatus, string> = {
  NEW: 'red',
  EXISTING: 'orange',
  SUPPRESSED: 'default',
  FIXED: 'green',
}

export const FINDING_STATUS_LABEL: Record<FindingStatus, string> = {
  NEW: '新增',
  EXISTING: '存量',
  SUPPRESSED: '已抑制',
  FIXED: '已修复',
}

export const SCAN_STATUS_COLOR: Record<ScanStatus, string> = {
  QUEUED: 'blue',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  CANCELED: 'default',
}

export const ENGINE_LABEL: Record<string, string> = {
  sca: 'SCA 依赖漏洞',
  sast: 'SAST 代码扫描',
  secrets: '密钥泄漏',
}

export const REMEDIATION_ACTION_LABEL: Record<string, string> = {
  UPGRADE_DIRECT: '直接依赖升版本',
  PIN_TRANSITIVE: '传递依赖钉版/排包',
  REPLACE: '替换构件',
  NONE_AVAILABLE: '暂无修复版',
}

/** detail_json 宽松解析（引擎字段随版本增减，坏串不炸页面） */
export function parseDetail(json: string | null): FindingDetailJson {
  if (!json) return {}
  try {
    return JSON.parse(json) as FindingDetailJson
  } catch {
    return {}
  }
}
