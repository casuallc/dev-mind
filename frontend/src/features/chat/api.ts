// 通用问答（CAP-30）REST 封装，对应后端 /api/chats
import { api } from '../../shared/api/client'
import type { ChatExecutor, ChatSummary } from './types'

export interface CreateChatPayload {
  /** 首条提问（即开场消息，发送即创建问答） */
  message: string
  model?: string
  permissionMode?: string
  /** 显式执行节点 id；留空 = 场景预设 / 平台默认节点，皆无命中创建失败（CAP-34 起无本机回落） */
  agentNodeId?: string
  /** CAP-33 FR-05：场景 code；骨架渲染为开场 prompt，绑定资产注入沙箱 */
  scenarioCode?: string
  /** CAP-46：绑定的知识库 id；启动注入库概览、每轮检索注入 <knowledge-context> */
  knowledgeBaseId?: number
  /** CAP-49：执行体；缺省 = AGENT（runner 上的 claude CLI） */
  executor?: ChatExecutor
  /** CAP-49：MODEL 时钉住的对话端点 id；缺省走平台默认 CHAT 端点，都没有则创建失败 */
  modelEndpointId?: number
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
