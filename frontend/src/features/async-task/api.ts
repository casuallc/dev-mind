// CAP-80 任务管理面 API：/async-tasks（任务）+ /async-task-runs（执行记录）。
// M4 起双通道创建：Java API（后端模块登记）+ REST POST/PUT（页面创建/编辑，仅 ADMIN）。
import { api } from '../../shared/api/client'
import type { AsyncTaskPage, AsyncTaskRunView, AsyncTaskView, TaskTypeView, TaskUpsertBody } from './types'

function qs(params: Record<string, string | number | undefined>): string {
  const q = new URLSearchParams()
  for (const [k, v] of Object.entries(params)) {
    if (v !== undefined && v !== '') q.set(k, String(v))
  }
  return q.toString()
}

/** M4：已注册任务类型元数据（新建表单的类型下拉/模板/执行位置），仅 ADMIN */
export const listTaskTypes = () => api.get<TaskTypeView[]>('/async-tasks/types')

/** M4：创建任务（仅 ADMIN） */
export const createTask = (body: TaskUpsertBody) => api.post<{ id: string }>('/async-tasks', body)

/** M4：编辑任务（终态 409，仅 ADMIN） */
export const updateTask = (id: string, body: TaskUpsertBody) =>
  api.put<AsyncTaskView>(`/async-tasks/${id}`, body)

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
