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
  /** 上传时可选的描述信息 */
  description?: string
  /** CAP-68：逗号分隔自由文本标签 */
  tags?: string
  /** CAP-68：过期时间（到期定时硬删，不做引用检查）；空=永久 */
  expiresAt?: string
  uploadedBy: string
  createdAt: string
}

/** 上传附件（默认 PRIVATE；chat 附件无需全员可见）；onProgress 传了走 XHR 带上传进度 */
export function uploadAttachment(
  file: File | Blob,
  fileName?: string,
  scope = 'PRIVATE',
  description?: string,
  onProgress?: (percent: number) => void,
  /** CAP-68：逗号分隔标签 / 保留天数（undefined=永久） */
  tags?: string,
  expireDays?: number,
) {
  const form = new FormData()
  form.append('file', file, fileName)
  form.append('scope', scope)
  if (description?.trim()) form.append('description', description.trim())
  if (tags?.trim()) form.append('tags', tags.trim())
  if (expireDays) form.append('expireDays', String(expireDays))
  if (onProgress) return api.uploadWithProgress<AttachmentView>('/attachments', form, onProgress)
  return api.upload<AttachmentView>('/attachments', form)
}

export function listAttachments(params?: { scope?: string; keyword?: string; type?: 'image' | 'other'; tag?: string }) {
  const q = new URLSearchParams()
  if (params?.scope) q.set('scope', params.scope)
  if (params?.keyword) q.set('keyword', params.keyword)
  if (params?.type) q.set('type', params.type)
  if (params?.tag) q.set('tag', params.tag)
  const qs = q.toString()
  return api.get<AttachmentView[]>(`/attachments${qs ? `?${qs}` : ''}`)
}

export function updateAttachmentScope(attachmentId: string, scope: 'PRIVATE' | 'SHARED') {
  return api.put<AttachmentView>(`/attachments/${attachmentId}/scope`, { scope })
}

/** CAP-68：改描述/标签/过期时间。字段语义：不传=不变，空白串=清除；expiresAt 格式 yyyy-MM-dd HH:mm:ss */
export function updateAttachmentMeta(
  attachmentId: string,
  meta: { description?: string; tags?: string; expiresAt?: string },
) {
  return api.put<AttachmentView>(`/attachments/${attachmentId}/meta`, meta)
}

export interface BatchDeleteItemResult {
  attachmentId: string
  ok: boolean
  message?: string
}

/** CAP-68：批量删除（逐项权限校验，部分失败不整单回滚） */
export function batchDeleteAttachments(ids: string[]) {
  return api.post<BatchDeleteItemResult[]>('/attachments/batch-delete', { ids })
}

export function deleteAttachment(attachmentId: string) {
  return api.del(`/attachments/${attachmentId}`)
}

/** 图片类附件判定（content_type 前缀 image/，与后端 AttachmentEntity.isImage 同口径） */
export function isImageAttachment(contentType?: string): boolean {
  return !!contentType && contentType.startsWith('image/')
}
