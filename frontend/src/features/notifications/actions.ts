// 通知快捷动作执行（FR-04）：view 由前端跳转；其余动作调 REST 后本地标记已读。
import { setCurrentProject } from '../../app/currentProjectStore'
import { runAction } from './api'
import { markReadLocal } from './store'
import type { AppNotification } from './types'

export async function executeNotificationAction(
  n: AppNotification,
  action: string,
  opts: { navigate: (path: string) => void },
): Promise<string> {
  if (action === 'view') {
    const path = viewPath(n)
    if (path) {
      opts.navigate(path)
    }
    return '查看'
  }
  await runAction(n.id, action)
  markReadLocal(n.id)
  return n.actions.find((a) => a.action === action)?.label ?? action
}

/** view 动作路由表：按 entityType 定位目标页，projectId 缺失时退化为列表页/不跳转（历史通知无此字段）。 */
function viewPath(n: AppNotification): string | null {
  if (!n.entityId) {
    return null
  }
  switch (n.entityType) {
    case 'SESSION':
      return `/sessions/${n.entityId}`
    case 'REQUIREMENT':
      return n.projectId ? `/projects/${n.projectId}/requirements/${n.entityId}` : null
    case 'JIRA_SYNC':
      return n.projectId ? `/admin/projects/${n.projectId}/jira` : '/admin/integrations'
    case 'BUILD':
      return projectScoped(n, '/builds')
    case 'deployment':
      return projectScoped(n, '/deployments')
    case 'test_run':
      return projectScoped(n, '/tests')
    case 'release':
      return projectScoped(n, '/releases')
    default:
      return null
  }
}

/** 项目工作区页面（构建/部署/测试/发版）依赖当前项目上下文，跳转前先把项目切过去。 */
function projectScoped(n: AppNotification, path: string): string | null {
  if (!n.projectId) {
    return null
  }
  setCurrentProject(n.projectId)
  return path
}
