// CAP-80 异步任务管理面 API：/async-tasks（任务）+ /async-task-runs（执行记录）。
// 任务创建没有 REST 端点（FR-10 走 Java API），这里只有查询与生命周期控制。
import { api } from '../../shared/api/client'
import type { AsyncTaskPage, AsyncTaskRunView, AsyncTaskView } from './types'

function qs(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  return q.toString()
}

export const listTasks = (params: {
  type?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
} = {}) => api.get<AsyncTaskPage<AsyncTaskView>>(`/async-tasks?${qs({ ...params })}`)

export const getTask = (id: string) => api.get<AsyncTaskView>(`/async-tasks/${id}`)

export const listTaskRuns = (taskId: string, page = 0, size = 10) =>
  api.get<AsyncTaskPage<AsyncTaskRunView>>(`/async-tasks/${taskId}/runs?${qs({ page, size })}`)

/** 执行记录横查（执行记录视图；admin 专用） */
export const listRuns = (params: { status?: string; taskId?: string; page?: number; size?: number } = {}) =>
  api.get<AsyncTaskPage<AsyncTaskRunView>>(`/async-task-runs?${qs({ ...params })}`)

export const getRun = (runId: string) => api.get<AsyncTaskRunView>(`/async-task-runs/${runId}`)

export const pauseTask = (id: string) => api.post(`/async-tasks/${id}/pause`)
export const resumeTask = (id: string) => api.post(`/async-tasks/${id}/resume`)
export const cancelTask = (id: string) => api.post(`/async-tasks/${id}/cancel`)
export const triggerTask = (id: string) => api.post(`/async-tasks/${id}/trigger`)
