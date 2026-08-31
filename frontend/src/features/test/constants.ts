// test 模块共享常量（状态颜色映射）。
import type { CaseResultStatus, TestRunStatus } from './types'

export const STATUS_COLOR: Record<TestRunStatus, string> = {
  QUEUED: 'default',
  RUNNING: 'processing',
  SUCCESS: 'green',
  FAILED: 'red',
}

export const RESULT_COLOR: Record<CaseResultStatus, string> = {
  pass: 'green',
  fail: 'red',
  skip: 'orange',
}

export const SUITE_KIND_COLOR: Record<string, string> = { api: 'blue', smoke: 'purple' }
