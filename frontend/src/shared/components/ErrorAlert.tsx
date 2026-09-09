// 通用错误异常展示组件：后端结构化错误（ApiRequestError）展示 错误码/路径/时间，
// 带堆栈（devmind.error.include-stacktrace=true 时后端返回）时折叠展示完整 stackTrace。
// 内联用法：<ErrorAlert error={e} title="加载失败" />；弹窗用法见 shared/utils/showError。
import React from 'react'
import { Alert, Collapse } from 'antd'
import { isApiRequestError } from '../api/error'
import { fmtTime } from '../utils/format'

export interface ErrorAlertProps {
  /** catch 到的原始错误对象（任意类型，组件内部归一化） */
  error: unknown
  /** 业务上下文标题，如「保存失败」 */
  title?: string
  style?: React.CSSProperties
}

const errMessage = (e: unknown): string => (e instanceof Error ? e.message : String(e))

const ErrorAlert: React.FC<ErrorAlertProps> = ({ error, title, style }) => {
  const msg = errMessage(error)
  const apiErr = isApiRequestError(error) ? error : null
  const body = apiErr?.body
  const stackTrace = body?.stackTrace

  const metaLines: string[] = []
  if (apiErr) metaLines.push(`HTTP ${apiErr.status}`)
  if (body?.code) metaLines.push(`错误码 ${body.code}`)
  if (body?.path) metaLines.push(`路径 ${body.path}`)
  if (body?.timestamp) metaLines.push(`时间 ${fmtTime(body.timestamp)}`)

  return (
    <Alert
      type="error"
      style={style}
      message={title ? `${title}：${msg}` : msg}
      description={
        <>
          {metaLines.length > 0 && (
            <div style={{ color: '#666', fontSize: 12, marginBottom: stackTrace ? 8 : 0 }}>
              {metaLines.join('　·　')}
            </div>
          )}
          {stackTrace && (
            <Collapse
              size="small"
              items={[
                {
                  key: 'stack',
                  label: '异常堆栈（本地排错信息）',
                  children: (
                    <pre
                      style={{
                        margin: 0,
                        maxHeight: 320,
                        overflow: 'auto',
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-all',
                        fontSize: 12,
                        lineHeight: 1.5,
                        userSelect: 'text',
                      }}
                    >
                      {stackTrace}
                    </pre>
                  ),
                },
              ]}
            />
          )}
        </>
      }
    />
  )
}

export default ErrorAlert
