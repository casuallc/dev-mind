// 会话状态元数据（CAP-30 由 sessions 上移）：颜色/活跃态/筛选选项，项目会话与通用问答共享
import type { SessionState } from './types'

export const stateColor: Record<string, string> = {
  RUNNING: 'processing',
  WAITING_INPUT: 'gold',
  WAITING_AUTH: 'orange',
  DONE: 'success',
  FAILED: 'error',
  SUSPENDED: 'default',
  TERMINATED: 'default',
}

export const STATE_OPTIONS = [
  'ALL',
  'RUNNING',
  'WAITING_INPUT',
  'WAITING_AUTH',
  'DONE',
  'FAILED',
  'SUSPENDED',
  'TERMINATED',
]

export const ACTIVE_STATES: SessionState[] = ['RUNNING', 'WAITING_INPUT', 'WAITING_AUTH']
