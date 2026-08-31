// 通知快捷动作执行（FR-04）：view 由前端跳转；其余动作调 REST 后本地标记已读。
import { runAction } from './api'
import { markReadLocal } from './store'
import type { AppNotification } from './types'

export async function executeNotificationAction(
  n: AppNotification,
  action: string,
  opts: { navigate: (path: string) => void },
): Promise<string> {
  if (action === 'view') {
    if (n.entityType === 'SESSION' && n.entityId) {
      opts.navigate(`/sessions/${n.entityId}`)
    }
    return '查看'
  }
  await runAction(n.id, action)
  markReadLocal(n.id)
  return n.actions.find((a) => a.action === action)?.label ?? action
}
