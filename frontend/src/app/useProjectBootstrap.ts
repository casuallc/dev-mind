// 项目列表引导：常驻 AppLayout 挂载——拉取全量项目（kind=ALL，含 WORKLOG 空间，
// 供 currentId 有效性校验），校验持久化的 currentId 并兜底自动选择。
// 数据与 UI 分离：ProjectSwitcher（纯展示）只在项目上下文页渲染，列表加载不依赖它挂载。
import { useCallback, useEffect, useState } from 'react'
import { listProjects } from '../features/projects/api'
import type { Project } from '../features/projects/types'
import {
  getCurrentProjectId,
  setCurrentProject,
  setProjectsLoaded,
} from './currentProjectStore'

export function useProjectBootstrap() {
  const [projects, setProjects] = useState<Project[]>([])
  const [loadError, setLoadError] = useState(false)

  const load = useCallback(async () => {
    try {
      const list = await listProjects(undefined, 'ALL')
      setProjects(list)
      setLoadError(false)
      // 兜底：无当前项目、持久化 id 已失效（项目被删）、或指向 WORKLOG 空间（历史残留；
      // 日志空间的会话/知识已收进 /worklog 页内视图，不再作为当前项目）→ 自动切到第一个 ACTIVE 普通项目
      const valid = getCurrentProjectId()
      if (!valid || !list.some((p) => p.id === valid && p.kind !== 'WORKLOG')) {
        const first = list.find((p) => p.status === 'ACTIVE' && p.kind !== 'WORKLOG') ?? null
        setCurrentProject(first?.id ?? null)
      }
    } catch {
      // 加载失败不清空已持久化的 currentId，避免一次网络抖动冲掉用户上下文
      setLoadError(true)
    } finally {
      setProjectsLoaded(true)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  return { projects, loadError, reload: load }
}
