import { api } from '../../shared/api/client'
import type {
  AdminPlatformAccount,
  BotBinding,
  BotInteractionPage,
  CopilotConfigView,
  CopilotHealthView,
} from './types'

// ---- CAP-75 M3 机器人运维端点（/api/bot/admin/**，仅 ADMIN） ----

export function listBotInteractions(params: {
  channel?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
} = {}): Promise<BotInteractionPage> {
  const q = new URLSearchParams()
  if (params.channel) q.set('channel', params.channel)
  if (params.status) q.set('status', params.status)
  if (params.keyword) q.set('keyword', params.keyword)
  q.set('page', String(params.page ?? 0))
  q.set('size', String(params.size ?? 20))
  return api.get<BotInteractionPage>(`/bot/admin/interactions?${q.toString()}`)
}

export function listBotBindings(): Promise<BotBinding[]> {
  return api.get<BotBinding[]>('/bot/admin/bindings')
}

export function unbindBotBinding(id: number): Promise<void> {
  return api.del<void>(`/bot/admin/bindings/${id}`)
}

// ---- 用户映射（CAP-35 管理视图，/api/admin/user-platform-accounts，仅 ADMIN） ----

export function listUserPlatformAccounts(integrationType: string): Promise<AdminPlatformAccount[]> {
  return api.get<AdminPlatformAccount[]>(`/admin/user-platform-accounts?type=${encodeURIComponent(integrationType)}`)
}

export function adminUnbindPlatformAccount(id: number): Promise<void> {
  return api.del<void>(`/admin/user-platform-accounts/${id}`)
}

// ---- CAP-76 智能体网关运维（/api/copilot/admin/**，仅 ADMIN；配置只读） ----

export function getCopilotConfig(): Promise<CopilotConfigView> {
  return api.get<CopilotConfigView>('/copilot/admin/config')
}

export function getCopilotHealth(): Promise<CopilotHealthView> {
  return api.get<CopilotHealthView>('/copilot/admin/health')
}
