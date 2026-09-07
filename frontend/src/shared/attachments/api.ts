// CAP-32 公共附件 API 封装（chat/docs/附件管理页共用，防跨 feature 私引）。
import { api } from '../api/client'

export interface AttachmentView {
  attachmentId: string
  originalName: string
  contentType: string
  sizeBytes: number
  scope: 'PRIVATE' | 'SHARED'
  /** 图片类附件（图床用法，内联渲染）；非图片仅下载 */
  image: boolean
  /** 原始访问地址（不含 token，渲染时用 attachmentRawUrl 拼） */
  url: string
  uploadedBy: string
  createdAt: string
}

/** 上传附件（默认 PRIVATE；chat 附件无需全员可见） */
export function uploadAttachment(file: File | Blob, fileName?: string, scope = 'PRIVATE') {
  const form = new FormData()
  form.append('file', file, fileName)
  form.append('scope', scope)
  return api.upload<AttachmentView>('/attachments', form)
}

export function listAttachments(params?: { scope?: string; keyword?: string; type?: 'image' | 'other' }) {
  const q = new URLSearchParams()
  if (params?.scope) q.set('scope', params.scope)
  if (params?.keyword) q.set('keyword', params.keyword)
  if (params?.type) q.set('type', params.type)
  const qs = q.toString()
  return api.get<AttachmentView[]>(`/attachments${qs ? `?${qs}` : ''}`)
}

export function updateAttachmentScope(attachmentId: string, scope: 'PRIVATE' | 'SHARED') {
  return api.put<AttachmentView>(`/attachments/${attachmentId}/scope`, { scope })
}

export function deleteAttachment(attachmentId: string) {
  return api.del(`/attachments/${attachmentId}`)
}

/** 图片类附件判定（content_type 前缀 image/，与后端 AttachmentEntity.isImage 同口径） */
export function isImageAttachment(contentType?: string): boolean {
  return !!contentType && contentType.startsWith('image/')
}
