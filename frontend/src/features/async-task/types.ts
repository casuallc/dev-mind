// CAP-80 通用任务框架：任务与执行记录的视图类型（对应 async_tasks / async_task_runs）

export type TaskStatus = 'ACTIVE' | 'PAUSED' | 'DONE' | 'CANCELLED'
export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'
export type ScheduleKind = 'IMMEDIATE' | 'DELAY' | 'FIXED_DELAY' | 'CRON'

export interface AsyncTaskView {
  id: string
  taskType: string
  name: string
  /** 创建时固化的 JSON 快照（编辑表单预填用） */
  payload?: string | null
  scheduleKind: ScheduleKind
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
  /** M4：执行节点（RUNNER 类任务显式指定；空=路由链兜底；LOCAL 类恒空） */
  agentNodeId?: string | null
  nextRunAt?: string | null
  status: TaskStatus
  actor?: string
  projectId?: string | null
  dedupKey?: string | null
  createdAt: string
  updatedAt: string
}

/** M4：已注册任务类型元数据（GET /async-tasks/types，仅 ADMIN） */
export interface TaskTypeView {
  type: string
  description: string
  payloadTemplate?: string | null
  executor: 'LOCAL' | 'RUNNER'
}

/** M4：创建/编辑请求体（POST/PUT /async-tasks，仅 ADMIN） */
export interface TaskUpsertBody {
  name?: string
  type: string
  payload?: string
  scheduleKind?: ScheduleKind
  /** DELAY 指定时刻（yyyy-MM-dd HH:mm:ss 或 ISO-8601） */
  runAt?: string
  intervalMs?: number
  cronExpr?: string
  maxRuns?: number
  agentNodeId?: string
  retryMaxAttempts?: number
  retryBackoff?: 'FIXED' | 'EXPONENTIAL'
  retryIntervalMs?: number
  timeoutMs?: number
  overlapPolicy?: 'SKIP' | 'QUEUE'
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
