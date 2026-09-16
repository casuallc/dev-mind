// 当前项目 hook：useCurrentProjectId 订阅 store；useCurrentProject 组合 useProject 加载项目对象。
import { useSyncExternalStore } from 'react'
import { getCurrentProjectId, subscribeCurrentProject } from './currentProjectStore'
import { useProject } from '../features/projects/hooks/useProject'

export function useCurrentProjectId(): string | null {
  return useSyncExternalStore(subscribeCurrentProject, getCurrentProjectId)
}

/**
 * 当前项目 hook；fixedProjectId 用于锁定项目的页面（如 /worklog/sessions 锁定 WORKLOG 空间），
 * 传入后不再跟随切换器。
 */
export function useCurrentProject(fixedProjectId?: string) {
  const storeProjectId = useCurrentProjectId()
  const projectId = fixedProjectId ?? storeProjectId
  const { project, setProject, loading, reload } = useProject(projectId ?? undefined)
  return { projectId, project, setProject, loading, reload }
}
