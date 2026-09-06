// 会话状态元数据：颜色/活跃态/筛选选项，供 SessionsBoard、SessionDetail、SessionListPane 共享
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
