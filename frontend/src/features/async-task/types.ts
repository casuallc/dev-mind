// CAP-80 通用异步任务框架：任务与执行记录的视图类型（对应 async_tasks / async_task_runs）

export type TaskStatus = 'ACTIVE' | 'PAUSED' | 'DONE' | 'CANCELLED'
export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'

export interface AsyncTaskView {
  id: string
  taskType: string
  name: string
  scheduleKind: 'IMMEDIATE' | 'DELAY' | 'FIXED_DELAY' | 'CRON'
  runAt?: string | null
  intervalMs?: number | null
  cronExpr?: string | null
  /** null = 不限次数 */
  maxRuns?: number | null
  runCount: number
  retryMaxAttempts: number
  retryBackoff: 'FIXED' | 'EXPONENTIAL'
  retryIntervalMs: number
  timeoutMs: number
  overlapPolicy: 'SKIP' | 'QUEUE'
  nextRunAt?: string | null
  status: TaskStatus
  actor?: string
  projectId?: string | null
  dedupKey?: string | null
  createdAt: string
  updatedAt: string
}

export interface AsyncTaskRunView {
  id: string
  taskId: string
  runSeq: number
  attempt: number
  status: RunStatus
  scheduledAt?: string
  startedAt?: string | null
  finishedAt?: string | null
  durationMs?: number | null
  error?: string | null
  logs?: string | null
  createdAt: string
}

/** 分页响应（对应后端 PageView） */
export interface AsyncTaskPage<T> {
  items: T[]
  total: number
  page: number
  size: number
}
