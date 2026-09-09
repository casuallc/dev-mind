// 后端统一错误体（对应 devmind-common ApiError）与结构化请求错误。
// client.ts 在 !res.ok 时抛出 ApiRequestError，调用方可拿到 code/path/stackTrace 等字段。

/** 后端 ApiError record 的前端镜像；stackTrace 仅在 devmind.error.include-stacktrace=true 时返回 */
export interface ApiErrorBody {
  code?: string
  message?: string
  path?: string
  timestamp?: string
  stackTrace?: string
}

/** HTTP 请求失败：status 为 HTTP 状态码；body 为解析出的 ApiErrorBody（非 JSON 错误体时为 null） */
export class ApiRequestError extends Error {
  readonly status: number
  readonly body: ApiErrorBody | null

  constructor(status: number, body: ApiErrorBody | null, rawText: string) {
    // message 保持旧行为：能解析出后端 message 时用它，否则退化为 "status 原文"
    super(body?.message || `${status} ${rawText || '请求失败'}`)
    this.name = 'ApiRequestError'
    this.status = status
    this.body = body
  }
}

/** 判断 catch 到的错误是否为后端结构化错误 */
export function isApiRequestError(e: unknown): e is ApiRequestError {
  return e instanceof ApiRequestError
}

/** 解析错误响应体并抛出统一错误对象（client.ts 专用） */
export function parseApiError(status: number, rawText: string): ApiRequestError {
  let body: ApiErrorBody | null = null
  if (rawText) {
    try {
      const parsed = JSON.parse(rawText) as ApiErrorBody
      // 至少要有 code 或 message 才认为是 ApiError 结构，避免把网关 HTML/纯文本误判为 JSON 对象
      if (parsed && typeof parsed === 'object' && (parsed.code !== undefined || parsed.message !== undefined)) {
        body = parsed
      }
    } catch {
      // 非 JSON 错误体（网关 502 HTML 等），body 保持 null
    }
  }
  return new ApiRequestError(status, body, rawText)
}
