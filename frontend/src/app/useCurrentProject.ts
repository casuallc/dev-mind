// 当前项目 hook：useCurrentProjectId 订阅 store；useCurrentProject 组合 useProject 加载项目对象。
import { useSyncExternalStore } from 'react'
import { getCurrentProjectId, subscribeCurrentProject } from './currentProjectStore'
import { useProject } from '../features/projects/hooks/useProject'

export function useCurrentProjectId(): string | null {
  return useSyncExternalStore(subscribeCurrentProject, getCurrentProjectId)
}

export function useCurrentProject() {
  const projectId = useCurrentProjectId()
  const { project, setProject, loading, reload } = useProject(projectId ?? undefined)
  return { projectId, project, setProject, loading, reload }
}
