// CAP-32 附件原始访问 URL 拼装：<img>/markdown/新窗口无法带 Authorization header，
// 统一拼 ?access_token=（后端 JwtAuthFilter 仅 GET 回退读该参数）。
import { getAccessToken } from '../../features/auth/authStore'

export function attachmentRawUrl(attachmentId: string): string {
  const base = `/api/attachments/${attachmentId}/raw`
  const token = getAccessToken()
  return token ? `${base}?access_token=${encodeURIComponent(token)}` : base
}
