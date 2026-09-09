// catch 块一行式错误提示：
//   .catch((e) => showError(e, '保存失败'))
// 普通错误退化为 message.error 轻提示（不打扰现有体验）；
// 后端返回了堆栈（本地排错模式）时弹 Modal 展示 ErrorAlert 完整信息。
import { Modal, message } from 'antd'
import { isApiRequestError } from '../api/error'
import ErrorAlert from '../components/ErrorAlert'

export function showError(e: unknown, title?: string): void {
  const msg = e instanceof Error ? e.message : String(e)
  if (isApiRequestError(e) && e.body?.stackTrace) {
    Modal.error({
      title: title ?? '操作失败',
      width: 760,
      content: <ErrorAlert error={e} />,
    })
    return
  }
  message.error(title ? `${title}：${msg}` : msg)
}
