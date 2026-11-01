// test 模块共享常量（状态颜色映射 / 用例规范枚举）。
import type {
  CaseResultStatus,
  CaseType,
  FailureCategory,
  ReviewStatus,
  RunConclusion,
  TestAssetKind,
  TestRunStatus,
} from './types'

export const STATUS_COLOR: Record<TestRunStatus | 'CANCELLED', string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
  BLOCKED: 'orange',
  CANCELLED: 'default',
}

export const RESULT_COLOR: Record<CaseResultStatus, string> = {
  pass: 'green',
  fail: 'red',
  skip: 'orange',
  blocked: 'gold',
  error: 'magenta',
}

export const CONCLUSION_COLOR: Record<RunConclusion, string> = {
  PASS: 'green',
  FAIL: 'red',
  PARTIAL: 'orange',
  BLOCKED: 'gold',
  CANCELLED: 'default',
}

export const REVIEW_COLOR: Record<ReviewStatus, string> = {
  DRAFT: 'default',
  APPROVED: 'green',
  DEPRECATED: 'red',
  REJECTED: 'red',
}

export const REVIEW_LABEL: Record<ReviewStatus, string> = {
  DRAFT: '草稿',
  APPROVED: '已通过',
  DEPRECATED: '已废弃',
  REJECTED: '已驳回',
}

export const CASE_TYPE_COLOR: Record<CaseType, string> = {
  HTTP: 'blue',
  COMMAND: 'purple',
  MANUAL: 'cyan',
}

export const CASE_TYPE_OPTIONS = [
  { value: 'HTTP', label: 'HTTP' },
  { value: 'COMMAND', label: 'COMMAND' },
  { value: 'MANUAL', label: 'MANUAL（人工）' },
]

export const CASE_PRIORITY_OPTIONS = ['P0', 'P1', 'P2', 'P3'].map((p) => ({ value: p, label: p }))

export const CASE_REVIEW_OPTIONS = (['DRAFT', 'APPROVED', 'DEPRECATED'] as const).map((r) => ({
  value: r,
  label: REVIEW_LABEL[r],
}))

export const FAILURE_CATEGORY_COLOR: Record<FailureCategory, string> = {
  ASSERTION: 'red',
  INFRA: 'volcano',
  TIMEOUT: 'orange',
  PRECHECK: 'gold',
  SCRIPT_ERROR: 'magenta',
  UNKNOWN: 'default',
}

export const ASSET_KIND_COLOR: Record<TestAssetKind, string> = {
  GIT: 'blue',
  ZIP: 'purple',
  FILE: 'cyan',
}

export const SUITE_KIND_COLOR: Record<string, string> = { api: 'blue', smoke: 'purple', script: 'cyan' }

/** 断言 source 与 op 选项（与后端 AssertionSpec 对齐） */
export const ASSERTION_SOURCE_OPTIONS: Record<CaseType, { value: string; label: string }[]> = {
  HTTP: [
    { value: 'HTTP_STATUS', label: 'HTTP_STATUS 状态码' },
    { value: 'HEADER', label: 'HEADER 响应头' },
    { value: 'JSON_PATH', label: 'JSON_PATH 响应字段' },
    { value: 'BODY', label: 'BODY 响应文本' },
  ],
  COMMAND: [
    { value: 'EXIT_CODE', label: 'EXIT_CODE 退出码' },
    { value: 'STDOUT', label: 'STDOUT 标准输出' },
    { value: 'STDERR', label: 'STDERR 标准错误' },
    { value: 'FILE_EXISTS', label: 'FILE_EXISTS 文件存在' },
    { value: 'FILE_SHA256', label: 'FILE_SHA256 文件哈希' },
  ],
  MANUAL: [],
}

export const ASSERTION_OP_OPTIONS = [
  { value: 'EQUALS', label: 'EQUALS 等于' },
  { value: 'CONTAINS', label: 'CONTAINS 包含' },
  { value: 'NOT_CONTAINS', label: 'NOT_CONTAINS 不包含' },
  { value: 'REGEX', label: 'REGEX 正则' },
  { value: 'EXISTS', label: 'EXISTS 存在' },
  { value: 'NOT_EXISTS', label: 'NOT_EXISTS 不存在' },
]
