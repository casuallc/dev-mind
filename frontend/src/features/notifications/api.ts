// CAP-06 通知中心 API
import { api } from '../../shared/api/client'
import type { AppNotification, NotificationChannel, NotificationPrefs } from './types'

export interface ListParams {
  level?: string
  unreadOnly?: boolean
  limit?: number
}

export const listNotifications = (params: ListParams = {}) => {
  const q = new URLSearchParams()
  if (params.level) q.set('level', params.level)
  if (params.unreadOnly) q.set('unreadOnly', 'true')
  if (params.limit) q.set('limit', String(params.limit))
  const s = q.toString()
  return api.get<AppNotification[]>(`/notifications${s ? '?' + s : ''}`)
}

export const unreadCount = () => api.get<{ count: number }>('/notifications/unread-count')
export const markRead = (id: number) => api.post(`/notifications/${id}/read`)
export const readAll = () => api.post<{ count: number }>('/notifications/read-all')
export const runAction = (id: number, action: string) =>
  api.post<AppNotification>(`/notifications/${id}/action`, { action })

export const listChannels = () => api.get<NotificationChannel[]>('/notification-channels')
export const updateChannel = (
  id: number,
  body: { enabled?: boolean; levelThreshold?: string; config?: Record<string, unknown> },
) => api.put<NotificationChannel>(`/notification-channels/${id}`, body)

export const getPrefs = () => api.get<NotificationPrefs>('/notification-prefs')
export const updatePrefs = (body: Partial<NotificationPrefs>) =>
  api.put<NotificationPrefs>('/notification-prefs', body)
