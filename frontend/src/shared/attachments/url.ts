// CAP-32 附件原始访问 URL 拼装：<img>/markdown/新窗口无法带 Authorization header，
// 统一拼 ?access_token=（后端 JwtAuthFilter 仅 GET 回退读该参数）。
import { getAccessToken } from '../../features/auth/authStore'

/** 同源 GET 地址拼当前登录 token——<img>/新窗口等无法带 Authorization header 的场景通用（如 Jira 附件代理图） */
export function withAccessToken(path: string): string {
  const token = getAccessToken()
  if (!token) {
    return path
  }
  return path + (path.includes('?') ? '&' : '?') + 'access_token=' + encodeURIComponent(token)
}

export function attachmentRawUrl(attachmentId: string): string {
  return withAccessToken(`/api/attachments/${attachmentId}/raw`)
}
