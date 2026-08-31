// CAP-06 通知中心类型
export type NotificationLevel = 'P0' | 'P1' | 'P2'

export interface NotificationAction {
  action: string
  label: string
}

export interface AppNotification {
  id: number
  level: NotificationLevel
  eventType: string
  title: string
  body: string | null
  entityType: string
  entityId: string
  actions: NotificationAction[]
  channelStatus: Record<string, string>
  readAt: string | null
  createdAt: string
}

export interface NotificationChannel {
  id: number
  code: string
  name: string
  enabled: boolean
  levelThreshold: NotificationLevel
  config: Record<string, unknown>
}

export interface NotificationPrefs {
  mutes: { eventTypes: string[] }
  quietStart: string | null
  quietEnd: string | null
  perSessionSilence: string[]
}

export const EVENT_TYPES = [
  'SESSION_STARTED',
  'WAITING_INPUT',
  'WAITING_AUTH',
  'SESSION_DONE',
  'SESSION_FAILED',
] as const

export const LEVEL_COLOR: Record<NotificationLevel, string> = {
  P0: 'red',
  P1: 'gold',
  P2: 'default',
}
