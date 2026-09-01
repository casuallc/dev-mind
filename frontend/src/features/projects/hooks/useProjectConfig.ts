// 项目配置加载 hook：从 AdminProjectDetail 抽出，后台设置页与工作台 /settings 共用。
import { useCallback, useEffect, useState } from 'react'
import { message } from 'antd'
import {
  getLock,
  getSummary,
  listBuildSteps,
  listEnvironments,
  listRepos,
  listServers,
} from '../api'
import type {
  BuildStep,
  ContextSummary,
  ProjectEnvironment,
  ProjectLock,
  ProjectRepo,
  ProjectServer,
} from '../types'

export function useProjectConfig(id: string | undefined) {
  const [servers, setServers] = useState<ProjectServer[]>([])
  const [environments, setEnvironments] = useState<ProjectEnvironment[]>([])
  const [repos, setRepos] = useState<ProjectRepo[]>([])
  const [steps, setSteps] = useState<BuildStep[]>([])
  const [summary, setSummary] = useState<ContextSummary>({ projectId: id ?? '', summary: '' })
  const [lock, setLock] = useState<ProjectLock | null>(null)

  const loadConfig = useCallback(async () => {
    if (!id) return
    const [rp, s, env, b, sm, lk] = await Promise.all([
      listRepos(id).catch(() => []),
      listServers(id),
      listEnvironments(id).catch(() => []),
      listBuildSteps(id),
      getSummary(id).catch(() => ({ projectId: id, summary: '' })),
      getLock(id).catch(() => null),
    ])
    setRepos(rp)
    setServers(s)
    setEnvironments(env)
    setSteps(b)
    setSummary(sm)
    setLock(lk)
  }, [id])

  useEffect(() => {
    loadConfig().catch((e) => message.error(`加载配置失败：${(e as Error).message}`))
  }, [loadConfig])

  return {
    servers,
    setServers,
    environments,
    setEnvironments,
    repos,
    setRepos,
    steps,
    setSteps,
    summary,
    setSummary,
    lock,
    setLock,
    loadConfig,
  }
}
