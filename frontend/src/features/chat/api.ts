// 通用问答（CAP-30）REST 封装，对应后端 /api/chats
import { api } from '../../shared/api/client'
import type { ChatSummary } from './types'

export interface CreateChatPayload {
  /** 首条提问（即开场消息，发送即创建问答） */
  message: string
  model?: string
  permissionMode?: string
  /** 显式执行节点 id；"local" = 强制本机；留空 = 平台默认节点 → 本机 */
  agentNodeId?: string
}

export const createChat = (p: CreateChatPayload) => api.post<ChatSummary>('/chats', p)

export const listChats = (status?: string) =>
  api.get<ChatSummary[]>(`/chats${status && status !== 'ALL' ? `?status=${encodeURIComponent(status)}` : ''}`)

export const getChat = (id: string) => api.get<ChatSummary>(`/chats/${id}`)

export const suspendChat = (id: string) => api.post<ChatSummary>(`/chats/${id}/suspend`)

export const resumeChat = (id: string) => api.post<ChatSummary>(`/chats/${id}/resume`)

export const killChat = (id: string) => api.post<ChatSummary>(`/chats/${id}/kill`)

export const finishChat = (id: string) => api.post<void>(`/chats/${id}/finish`)

export const deleteChat = (id: string) => api.del<void>(`/chats/${id}`)
